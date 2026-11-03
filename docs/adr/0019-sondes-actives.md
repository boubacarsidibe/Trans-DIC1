# ADR-0019 — Sondes actives sans agent : ICMP, TCP, HTTP(S) et certificat TLS lancés depuis le serveur

- Statut : acceptée, implémentée ; test de bout en bout écrit mais non exécuté, ping en conteneur non vérifié (voir Limites)
- Date : 2026-11-03
- Précise : ADR-0002 (périmètre exclu), ADR-0006 (collecte). Écart G1 du [benchmark](../benchmark-et-plan-depassement.md). Spécification : [sondes sans agent](../superpowers/specs/2026-11-02-sondes-sans-agent-design.md)

## Contexte

Trans-DIC1 ne savait mesurer qu'avec un agent installé sur un serveur ou avec SNMP. Une imprimante, une borne Wi-Fi, l'ENT, la messagerie ou le certificat d'un site web n'ont ni l'un ni l'autre : on voulait pourtant savoir s'ils répondent, en combien de temps, et si un certificat va expirer. Zabbix et LibreNMS le font en standard ; c'était l'écart G1 du benchmark.

Contraintes :

- le résultat doit suivre le circuit existant (règles d'alerte, cycle de l'incident, notifications, SLA, maintenances, périmètre par site) sans le réécrire ;
- une sonde lancée par le serveur est une porte ouverte vers le réseau interne : il faut un garde-fou contre le détournement (SSRF) ;
- pas de dépendance nouvelle lourde, ni de composant à installer sur les cibles.

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Réutiliser `monitored_service` et le pipeline de mesures et d'alertes ; sondes lancées par le serveur | peu de code neuf, tout l'aval fonctionne tel quel, rien à déployer sur les cibles | le serveur ne voit que son propre réseau ; surface SSRF à maîtriser |
| B. Table et module séparés (`probe`, `probe_result`) avec leurs propres alertes | modèle sur mesure | duplique règles, alertes, SLA, notifications ; deux cycles d'incident à maintenir |
| C. Exécuteurs déportés par site (petit programme qui lance les sondes et les remonte) | voit le réseau de chaque bâtiment | à installer, authentifier et superviser ; hors de la première livraison |

## Décision

Option A. L'interface `Probe` est écrite pour qu'un exécuteur déporté (option C) puisse l'appeler plus tard sans changer le modèle.

- **Types** : ICMP, TCP, HTTP, HTTPS, TLS_CERT. Un administrateur déclare une sonde sur un équipement (`/api/v1/equipements/{id}/sondes`) ; une tâche planifiée (`collection.probe.*`) exécute les sondes à échéance dans un pool borné, sous verrou de tâche.
- **Mesures** (avec `service_id`) : `probe.up` (100 ou 0), `probe.latency` (ms, absente si échec), `probe.tls.days_left` (jours, HTTPS et TLS_CERT). Les règles d'alerte ordinaires les consomment ; deux règles prêtes à l'emploi, « Sonde injoignable » et « Certificat bientôt expiré », sont créées **inactives** (migration V27) : l'administrateur les active et les limite aux bons équipements.
- **Une alerte par (équipement, règle, service)** (migration V28) : deux sondes du même équipement ouvrent deux alertes distinctes. Les alertes sans service ne changent pas.
- **Chemin d'ingestion dédié** `ingestProbe` : une mesure de sonde ne marque pas l'équipement « joignable » et ne résout pas l'alerte de collecte. Exception : sur un équipement **sans agent actif ni configuration SNMP active**, une sonde ICMP pilote le statut (hors service après 3 échecs consécutifs, en service au premier succès) ; sinon les sondes ne changent jamais le statut.
- **HTTP écrit à la main** : requête HTTP/1.1 `GET` sur une socket, parce que `java.net.http` ne permet pas de fixer SNI et `Host` en se connectant à une adresse IP imposée. Un seul délai total couvre connexion, envoi et lecture.
- **TLS_CERT lit le certificat sans valider la chaîne, volontairement** : on mesure la date, et même un certificat invalide en a une. Les sondes HTTPS, elles, valident la chaîne.
- **ICMP** : commande système `ping` lancée sans shell (adresse IP uniquement, arguments en tableau) ; repli sur `InetAddress.isReachable`.

### Garde-fous

- Création, modification, suppression et test réservés aux **administrateurs**, mutations auditées. Lecture ouverte à tous les rôles, selon le périmètre par site (ADR-0018).
- La cible est **résolue une seule fois** ; la connexion se fait **à l'adresse résolue uniquement** (pas de seconde résolution, contre le rebinding DNS).
- **Refusées** : boucle locale (127.0.0.0/8, ::1), lien local et métadonnées cloud (169.254.0.0/16, fe80::/10), adresse non spécifiée, multicast, formes IPv4 encapsulées dans IPv6. Les adresses privées du réseau local **restent permises** : c'est la cible normale d'une sonde.
- HTTP : **redirections non suivies** (un 3xx est comparé au statut attendu), lecture limitée à **64 Ko**, corps **jamais stocké** ni journalisé, `GET` seulement, ni en-tête personnalisé ni authentification, **identifiants dans l'URL refusés**.
- Limites : intervalle ≥ 30 s, délai de 100 à 30 000 ms, 50 sondes actives par équipement, pool borné (`collection.probe.parallelism`, 16 par défaut), au plus `collection.probe.max-per-scan` sondes par passage.
- Sondes HTTP : **sans texte attendu, seule la ligne de statut est jugée** ; un serveur qui envoie ses en-têtes puis se fige compte comme « en ligne ». Renseigner un texte attendu pour juger le contenu.
- Le motif d'échec stocké est court (`last_error`), sans trace de pile ni donnée de la cible.

## Conséquences

- Établi par les tests : chaque sonde contre de vrais sockets locaux (`ServerSocket`, serveur HTTP du JDK, serveur TLS à certificat de test), table d'adresses autorisées et refusées du garde-fou, échéances et isolement des échecs, alertes distinctes par service, statut piloté par ICMP uniquement dans le cas prévu, règle ArchUnit (`collection.probe` indépendant du web).
- La migration V27 a été **modifiée sur place avant publication** : quiconque a exécuté une première V27 en local doit recréer sa base de développement.
- Désactiver une sonde résout ses alertes ouvertes (« Sonde désactivée »). Supprimer une sonde qui a un historique ne fait que la désactiver (son nom reste pris ; un message l'explique : utiliser **Réactiver** ou renommer).
- Le bouton **Tester** exécute la sonde sur le fil de la requête (30 s au plus) et ne stocke rien.
- **ICMP : Linux et Windows seulement.** Sous macOS, `ping -W` est en millisecondes : non pris en charge. Sous Windows, la sortie de `ping` n'est lue qu'en anglais, français, allemand (`zeit`) et espagnol (`tiempo`) ; dans une autre langue, les sondes ICMP paraissent en panne.
- **En conteneur**, l'utilisateur non-root a besoin du paquet `iputils` (installé dans l'image) et soit de la capacité `NET_RAW` (`cap_add: [NET_RAW]`), soit du sysctl `net.ipv4.ping_group_range` couvrant son groupe. Sans cela, le repli `isReachable` (écho TCP port 7) est utilisé et il est **moins fiable**. Voir [ping dans le conteneur](../devops/sondes-icmp-conteneur.md).
- Risque R9 ajouté ([risques](../architecture/risks-and-technical-debt.md)) : les sondes ne voient que ce que voit le réseau du serveur.
- Dettes D13 et D14 ouvertes (aperçu des règles, SSE et test e2e).

## Limites

- Pas de sonde DNS, pas de scénario web à plusieurs étapes, pas d'en-têtes ni d'authentification HTTP.
- Pas d'exécuteur déporté par site (écart G8) : un bâtiment non joignable depuis le serveur n'est pas supervisable par sonde.
- Le panneau « tester une règle sur l'historique » ne rejoue pas encore les mesures `probe.*` (il ne rejoue que les mesures sans service).
- Pas de rafraîchissement temps réel (SSE) de la page Sondes : bouton **Actualiser**.
- Le test de bout en bout `frontend/e2e/probes.spec.js` est écrit mais **n'a pas été exécuté** (pas de Docker pendant le développement) ; à lancer avant toute livraison.
- La construction de l'image et le `ping` en conteneur non-root n'ont pas été vérifiés (pas de Docker disponible).
