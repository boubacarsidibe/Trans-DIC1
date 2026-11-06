# Benchmark Trans-DIC1 face à Zabbix et aux autres outils : état, écarts, améliorations

Troisième version, le 2026-11-05 (après les sondes, la prévision de saturation et les récepteurs de traps et de syslog).

Fiabilité des sources : les capacités de Trans-DIC1 viennent du code, des tests et des revues. Pour les autres outils, les points marqués (web) ont été vérifiés sur des pages en ligne le 2026-11-05 ; les autres viennent de ma connaissance de ces produits et sont à recouper avant le rapport.

## 1. Où en est le projet

### Fait et fusionné dans `develop` (en local, non poussé)
- Tout le backlog de développement : 59 Issues fermées.
- **Sondes sans agent** : ping, TCP, HTTP/HTTPS, certificat TLS, alertes par service, garde anti-SSRF, API, page dédiée, ADR-0019.

### Fait sur la branche `feature/depassement` (pas encore fusionné)
- **Contrôle local type intégration continue** : `scripts/verify-all.ps1` (serveur, interface, validation des fichiers de déploiement, parcours de bout en bout sur une pile native sans Docker). Dernier passage complet : serveur 611 tests, interface 220 tests, 12 parcours de bout en bout, 0 échec.
- **Prévision de saturation** : régression linéaire sur l'historique, alerte « disque plein dans N jours » avec garde de confiance, carte « Prévision » dans l'historique, API (ADR-0020).
- **Récepteurs de traps SNMP et de syslog** : désactivés par défaut, sources reconnues par l'adresse de gestion de l'équipement, règles d'événements qui ouvrent et ferment des alertes, pages Événements et Règles d'événements (ADR-0021).
- **Ping corrigé** quand plusieurs sondes ping surveillent un même équipement (verrou et compteurs).
- **Diagnostic d'incident expliqué** : dans le détail d'une alerte, résumé, impact, causes probables classées par un score déterministe avec leurs preuves (amont en panne, trap, changement tracé, silence de collecte, prévision, anomalie), alertes voisines, événements et changements récents, incidents similaires et pistes ; règles fixes, sans IA ni appel réseau (F2.1, ADR-0024). Poids non validés sur incidents réels.
- **Astreinte** : plannings (tous les sites ou par site), gardes principal/secondaire, remplacements, rotations générées avec aperçu (heure d'été gérée), passation de sa garde ; la personne principale est prévenue à l'ouverture d'une alerte, la secondaire après 10 min (5 si critique), puis les administrateurs ; page Astreinte et carte du tableau de bord (F2.2, ADR-0025). Pas de SMS ni d'appel ; jamais essayé avec une vraie équipe de garde.
- **Page de statut** : pages internes ou publiques des services de l'école (messagerie, ENT, Wi-Fi…), chaque service rattaché à une sonde, un équipement ou un site sous un nom choisi pour les usagers ; état calculé à partir des sondes, des alertes et des maintenances, disponibilité sur 90 jours (jour sans mesure : inconnu), incidents avec mises à jour, incident automatique facultatif ; adresse publique `/statut/<slug>` sans connexion, en liste blanche stricte (aucun nom d'hôte ni adresse), même 404 pour une page inconnue ou privée, limitée par adresse et mise en cache 30 s (F2.3, ADR-0026). Pas d'abonnement des usagers ; configuration du reverse proxy ajoutée mais jamais exécutée (pas de Docker ici).

### En cours
Deux vagues de corrections issues des relectures : prévision de saturation (fausse alerte juste après un nettoyage de disque, tests manquants) et traps/syslog (course entre deux fils de traitement, rejet tardif des sources inconnues, coût des expressions régulières, cache des règles).

## 2. Ce qui ne va pas

- **Rien de ce travail n'a tourné chez un vrai utilisateur ni à l'EPT.** Pas de mesure réelle, pas d'essai avec un vrai MikroTik, Cisco ou serveur syslog. Les seuils proposés ne sont pas validés par le CRI.
- **Les relectures trouvent des défauts à chaque fonction** (une dizaine de points importants au total, tous corrigés ou en cours). La qualité vient des relectures, pas du premier jet : il faut continuer à relire.
- **Non vérifié** : Docker et Kubernetes (l'image, le ping sans privilèges en conteneur et le chart Helm n'ont jamais été exécutés, car ce poste n'a pas de virtualisation), le comportement réel d'un envoi UDP depuis du matériel, la charge, la lecture à l'écran des nouvelles pages dans un navigateur au-delà des tests.
- **Limites connues** : pas de sonde DNS ni de scénario web, l'historique mélange les séries de plusieurs sondes du même type, le test de règle sur l'historique ne rejoue pas encore les sondes, l'usurpation d'adresse UDP reste possible pour les traps et le syslog (documentée), les ports UDP 1162 et 1514 ne sont pas publiés dans les fichiers de déploiement.
- **Absents** : modèles d'équipement, découverte des interfaces et disques, règles composées, jetons d'API, rapports planifiés, MFA propre, proxys de collecte, haute disponibilité ; pour la page de statut, pas d'abonnement des usagers (e-mail, RSS) ; pour l'astreinte, pas de SMS ni d'appel téléphonique.
- **Dette** : aucune intégration continue distante (le script local la remplace en partie), un test instable ancien (agrégation horaire), avertissements du linter en hausse lente, ADR et documents d'architecture à régénérer.

## 3. Benchmark

Légende : ✅ présent, 🟡 partiel, ❌ absent. « Trans-DIC1 » = état de la branche actuelle.

| Domaine | Trans-DIC1 | Zabbix | LibreNMS | Prometheus + Grafana | Checkmk | PRTG | Uptime Kuma |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Collecte SNMP v2c/v3 | ✅ OID à la main | ✅ + modèles | ✅ auto | 🟡 snmp_exporter | ✅ auto | ✅ | ❌ |
| Agent | 🟡 Linux et Windows, 4 mesures (+ uptime sous Windows) | ✅ multi-OS, plugins | ❌ | 🟡 exporters | ✅ | ✅ | ❌ |
| Windows | 🟡 agent natif (stdlib, tâche planifiée) + SNMP ; validé sur Windows 11 seulement, pas sur Windows Server | ✅ | 🟡 SNMP | ✅ windows_exporter | ✅ | ✅ | ❌ |
| Découverte réseau | 🟡 plage + SNMP | ✅ règles, découverte imbriquée (web, 7.4) | ✅ (web) | ❌ | ✅ | ✅ | ❌ |
| Modèles par constructeur | ❌ | ✅ | ✅ | 🟡 | ✅ | ✅ | ❌ |
| Ping, port, HTTP | ✅ | ✅ | ✅ | 🟡 blackbox | ✅ | ✅ | ✅ cœur du produit |
| Certificat TLS | ✅ jours restants | ✅ modèles, agent 2 (web) | ✅ | 🟡 | ✅ | ✅ | ✅ |
| Scénarios web à étapes | ❌ | ✅ (web) | 🟡 | ❌ | ✅ | ✅ | ❌ |
| Traps SNMP | ✅ récepteur intégré, sans outil externe | ✅ mais indirect : `snmptrapd` écrit un fichier que Zabbix lit (web) | ✅ (web) | ❌ | ✅ | ✅ | ❌ |
| Syslog | ✅ récepteur intégré | ✅ | ✅ (web) | ❌ | ✅ | ✅ | ❌ |
| Alertes à seuil + hystérésis | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 |
| Test d'une règle sur l'historique | ✅ (pas encore sondes) | 🟡 | ❌ | 🟡 | ❌ | ❌ | ❌ |
| Prévision de saturation | ✅ linéaire, avec garde de confiance | ✅ `forecast()` et `timeleft()` (web) | ❌ | ✅ `predict_linear` | 🟡 | 🟡 | ❌ |
| Absence de donnée | 🟡 silence de l'agent et des sondes | ✅ `nodata()` | 🟡 | ✅ | ✅ | ✅ | ✅ |
| Conditions composées ET/OU | ❌ | ✅ expressions | 🟡 | ✅ | 🟡 | 🟡 | ❌ |
| Cycle d'incident (prise en charge, plusieurs assignés, escalade, audit) | ✅ | 🟡 acquittement | 🟡 | ❌ (Grafana OnCall libre archivé le 2026-03-24 (web)) | 🟡 | 🟡 | ❌ |
| Corrélation par topologie | ✅ | 🟡 | ✅ | ❌ | 🟡 | ✅ | ❌ |
| Diagnostic d'incident expliqué (causes classées avec preuves, changements récents, incidents similaires) | ✅ branche, règles fixes, poids non validés sur incidents réels | 🟡 cause/symptômes par corrélation d'événements, sans explication ni changements | ❌ | ❌ (version libre) | 🟡 dépendances parent/enfant | 🟡 dépendances | ❌ |
| Détection d'anomalies | ✅ | 🟡 tendances | ❌ | 🟡 | 🟡 | ❌ | ❌ |
| Notifications | ✅ e-mail, Teams, Slack, webhook | ✅ + SMS, scripts | ✅ (web) | ✅ | ✅ | ✅ | ✅ |
| SLA par site, maintenances exclues | ✅ | ✅ arbre de services | 🟡 | ❌ | 🟡 | ✅ | 🟡 |
| Page de statut | ✅ branche : interne ou publique, branchée sur sondes, alertes et maintenances du parc, disponibilité 90 jours, incidents, liste blanche et limitation par adresse ; sans abonnement des usagers | 🟡 | ❌ | ❌ (web) | ❌ (web) | ❌ | ✅ cœur du produit, abonnements, mais sans parc supervisé |
| Astreinte (planning de garde, rotations, escalade principal → secondaire) | ✅ branche : plannings par site, rotations avec heure d'été, passation, chaîne idempotente ; canaux e-mail/Teams/Slack/webhook seulement, sans SMS ni appel | 🟡 scripts | ❌ | ❌ (Grafana OnCall libre archivé le 2026-03-24 (web)) | 🟡 | 🟡 | ❌ |
| Topologie | ✅ + impact d'une panne | ✅ cartes manuelles | ✅ auto | ❌ | 🟡 | ✅ | ❌ |
| Tableaux de bord, mur NOC | ✅ | ✅ | 🟡 | ✅ très riche | ✅ | ✅ | 🟡 |
| Rôles, périmètre par site | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | 🟡 |
| SSO OIDC / MFA | ✅ / ❌ | ✅ / ✅ | ✅ / ✅ | selon outil | ✅ | ✅ | 🟡 |
| Audit immuable | ✅ | ✅ | 🟡 | ❌ | ✅ | 🟡 | ❌ |
| API publique | 🟡 OpenAPI, sans jeton | ✅ | ✅ (web) | ✅ | ✅ | ✅ | 🟡 |
| Passage à l'échelle | ❌ un serveur | ✅ proxys | 🟡 polling distribué (web) | ✅ | ✅ | 🟡 | ❌ |
| Haute disponibilité | ❌ | ✅ grappe active/veille, bascule de 10 s à 15 min (web) | 🟡 | ✅ | ✅ | 🟡 | ❌ |
| Sauvegarde restaurée chaque jour | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Interface en français | ✅ | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 |
| Coût | gratuit | gratuit | gratuit | gratuit | gratuit (communautaire) | payant par capteur | gratuit |

## 4. Comparaison honnête

**Où Trans-DIC1 est devant**
- Cycle d'incident complet et audit immuable, que les outils libres n'ont pas.
- Récepteur de traps et de syslog intégré : Zabbix exige `snmptrapd`, un script récepteur et un fichier intermédiaire (web).
- Test d'une règle sur l'historique, détection d'anomalies, corrélation par topologie, SLA par site.
- Diagnostic d'incident : l'alerte arrive avec sa cause probable, ses preuves, les changements et événements qui l'ont précédée et les résolutions passées, là où les autres outils libres la listent. À nuancer : heuristiques non éprouvées sur de vrais incidents.
- Astreinte intégrée (plannings, rotations, chaîne principal → secondaire → administrateurs) alors que Grafana OnCall libre est archivé et que Zabbix et LibreNMS n'en ont pas. À nuancer : sans SMS ni appel, elle dépend d'un canal qui sonne sur le téléphone de la personne de garde.
- Une seule application, sauvegarde restaurée chaque jour, interface en français.

**À égalité (sur le papier)**
- Contrôles ping, TCP, HTTP, certificat : tous les concurrents les ont ; nous avons rattrapé.
- Prévision : Zabbix a `forecast()` et `timeleft()` avec des modes variés ; la nôtre est linéaire et plus simple, avec une garde de confiance.

**Où Trans-DIC1 est derrière**
- Largeur de collecte : modèles d'équipement, agent multi-système, découverte automatique, scénarios web.
- Échelle et disponibilité : Zabbix a des proxys et une grappe de serveurs ; nous avons un serveur unique.
- Expressions d'alerte : Zabbix combine librement fonctions, tendances et conditions ; nous avons des seuils simples.
- Maturité : Zabbix et les autres ont des années d'usage réel ; nous n'avons aucune mesure en production.

## 5. Ce qu'il faut améliorer

### Tout de suite (crédibilité)
1. Finir les deux vagues de corrections en cours, puis fusionner la branche dans `develop` après un passage complet de `verify-all.ps1`.
2. Ajouter des parcours de bout en bout pour les pages Événements, Règles d'événements et la carte Prévision.
3. Publier les ports UDP 1162 et 1514 dans les fichiers de déploiement, avec la mise en garde sur l'adresse source.
4. Mesurer la charge sur le poste : équipements, mesures par seconde, événements par seconde. Un chiffre honnête vaut mieux qu'une promesse.
5. Réaliser la validation à l'EPT (#32, #33) : c'est la seule preuve qui compte face à un jury.

### Écarts à combler (priorité décroissante)
1. Modèles d'équipement (MikroTik, Cisco, HP, Ubiquiti, onduleurs, imprimantes) et leur application en un clic.
2. ~~Agent Windows~~ : livré (F1.5, ADR-0023), à valider sur Windows Server ; reste : davantage de mesures (réseau, services).
3. Absence de donnée par indicateur et conditions composées ET/OU.
4. Découverte des interfaces et disques par SNMP.
5. Jetons d'API et rapports de SLA planifiés par e-mail.
6. Proxys de collecte par site, puis haute disponibilité (besoin de plusieurs machines).

### Pour dépasser les concurrents
1. ~~**Assistant de diagnostic d'incident**~~ : livré (F2.1, ADR-0024) ; reste à éprouver les poids sur de vrais incidents à l'EPT et à brancher les runbooks (F2.6) sur son point d'extension.
2. ~~**Astreinte**~~ : livrée (F2.2, ADR-0025) ; reste un canal hors bande (SMS ou appel) et un essai réel avec l'équipe du CRI.
3. ~~**Page de statut**~~ : livrée (F2.3, ADR-0026) ; restent l'abonnement des usagers (e-mail ou RSS), le choix avec le CRI des services à rendre publics et de leurs noms, et un essai derrière le vrai reverse proxy.
4. **Réglage automatique des seuils** avec simulation, étendu aux mesures de sondes.
5. **Score de santé** par site et par équipement, **runbooks** liés aux règles, **rapport de capacité** fondé sur la prévision, **application installable** avec notifications.

### À corriger dans le discours de soutenance
- Ne pas dire « mieux que Zabbix » : dire « plus adapté à l'exploitation d'une école avec un petit NOC ». Zabbix reste supérieur en largeur et à grande échelle.
- Réviser l'ADR-0002 : le périmètre exclu (découverte, modèles, grande échelle) évolue avec ce qui a été ajouté.

## Sources consultées le 2026-11-05

- [Fonctions prédictives de déclencheurs Zabbix](https://www.zabbix.com/documentation/current/en/manual/config/triggers/prediction) : `forecast` et `timeleft`.
- [Grappe haute disponibilité Zabbix](https://www.zabbix.com/documentation/current/en/manual/concepts/server/ha) : nœud actif et nœuds de veille, bascule de 10 s à 15 min, proxys.
- [Traps SNMP dans Zabbix](https://www.zabbix.com/documentation/current/en/manual/config/items/itemtypes/snmptrap) : passage par `snmptrapd`, fichier intermédiaire, clés `snmptrap[regexp]` et `snmptrap.fallback`.
- [Zabbix 7.4.0alpha1](https://www.zabbix.com/rn/rn7.4.0alpha1), [Zabbix 7.4 (Linuxiac)](https://linuxiac.com/?p=175514) : découverte imbriquée, assistant d'hôte.
- [Surveiller l'expiration SSL avec Zabbix](https://medium.com/zabbix-tutorials/monitor-ssl-certificate-expiry-with-zabbix-860279519238), [modèle certificat agent 2](https://www.zabbix.com/forum/zabbix-help/494951-template-website-certificate-by-zabbix-agent-2).
- [Documentation LibreNMS](https://docs.librenms.org/) : découverte, transports d'alertes, syslog, traps, API, polling distribué.
- [Comparatifs auto-hébergés](https://toolradar.com/guides/best-self-hosted-monitoring), [Uptime Kuma contre PRTG](https://toolradar.com/compare/uptime-kuma-vs-prtg) : sources commerciales ou d'opinion.
- [Annonce Grafana OnCall](https://grafana.com/blog/announcing-grafana-oncall/?pg=blog), [Grafana et OneUptime](https://oneuptime.com/blog/post/2026-04-15-grafana-stack-vs-oneuptime-unified-observability/markdown) : archivage de Grafana OnCall libre le 2026-03-24 ; à confirmer sur le dépôt `grafana/oncall`.
