# ADR 0028 : supervision de sécurité à partir des journaux

- Statut : accepté
- Date : 2026-11-11
- Lot : « sécurité » (section 5 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

Trans-DIC1 voit si un équipement répond, pas s'il est attaqué. Un serveur web qui encaisse une
injection SQL ou un balayage de mots de passe reste « en service » pour la supervision. Les outils
libres de sécurité (Wazuh, CrowdSec) font ce travail, mais les faire cohabiter demande un second
produit à installer, à mettre à jour et à expliquer à une équipe de deux personnes.

La demande était d'avoir, dans la plateforme déjà en place, de quoi voir une attaque en cours.

## Décision

Un module `threat`, feuille de l'architecture comme `events` et `diagnostic` : il lit les entités
des autres modules et ouvre des alertes, personne ne dépend de lui. Seul son point d'entrée
(`POST /api/v1/agent/journaux`) vit dans `collection`, avec les autres envois de l'agent.

### Qui juge

**L'agent lit et découpe, le serveur juge.** L'agent suit les journaux d'authentification du
système (`/var/log/auth.log`, `/var/log/secure`) et, si on les lui indique, des journaux d'accès
web au format courant. Il envoie des lignes découpées ; il ne décide jamais si c'est une attaque.
Ainsi la règle est la même sur tout le parc et s'améliore sans remettre à jour les agents.

Le suivi est incrémental et part de la fin du fichier au démarrage : installer l'agent ne renvoie
pas trois mois de journal. 500 lignes au plus par passage et par fichier. Les journaux ne sont pas
mis en file d'attente en cas de panne du serveur : une ligne manquée vaut mieux qu'une mémoire qui
enfle sur une machine de production.

### Ce qui est détecté

| Détection | Méthode | Ouvre |
| --- | --- | --- |
| Attaque dans une requête web | signatures à score cumulé, principe de l'OWASP CRS | alerte critique, dès une requête |
| Force brute d'authentification | 10 échecs d'une même adresse en 5 minutes | alerte critique |
| Attaque patiente, compte volé | habitude du compte et du créneau ([ADR 0030](0030-comportement-inhabituel.md)) | avertissement |
| Balayage web | 40 requêtes en erreur d'une même adresse en 5 minutes | alerte d'avertissement |

Les signatures couvrent six familles : injection SQL, script injecté (XSS), traversée de
répertoire, exécution de commande, injection de modèle (JNDI et semblables), reconnaissance
(chemins d'outils connus). Chaque signature reconnue ajoute les points de sa famille ; cinq points
suffisent. Une signature de reconnaissance en vaut deux : un seul `/wp-login.php` n'est pas une
attaque, trois indices sur la même requête en font une. La requête est décodée deux fois avant
comparaison, parce que les outils d'attaque encodent en double pour passer les filtres.

Une alerte par adresse, par équipement et par nature : une attaque qui continue met son message à
jour au lieu d'empiler des alertes.

### Ce qui est conservé

Rien d'une requête ordinaire : ni l'URL, ni l'adresse de l'usager. Seules sont gardées les
tentatives d'authentification, les requêtes reconnues comme attaques et les requêtes en erreur.
Ces lignes portent des données personnelles : elles sont purgées au bout de 30 jours
(`threat.retention-days`), par lots, comme les événements réseau.

## Alternatives écartées

- **Installer Wazuh à côté** : plus complet (intégrité des fichiers, vulnérabilités, MITRE
  ATT&CK), mais c'est un second produit à exploiter pour une équipe de deux personnes. La
  passerelle reste possible : nos alertes peuvent partir en syslog ou par webhook vers un vrai
  SIEM, et les siennes revenir par le récepteur syslog déjà en place (ADR 0021).
- **Bloquer automatiquement les adresses** (à la façon de fail2ban) : un faux positif couperait un
  usager de l'école. À décider avec le CRI, pas à imposer.
- **Un modèle appris sur les requêtes** (S4 du plan) : repoussé ici faute de modèle libre à
  licence claire, puis fait dans l'[ADR 0029](0029-modele-appris-requetes-web.md) — un classifieur
  entraîné par nous sur un corpus versionné, qui complète les signatures sans les remplacer.
- **Faire juger l'agent** : moins de trafic, mais il faudrait mettre à jour tous les agents à
  chaque nouvelle signature.

## Conséquences

- **Ce n'est pas un pare-feu applicatif.** On lit un journal : l'attaque est vue après la requête,
  jamais pendant, et le corps des requêtes n'est pas visible. Une injection passée en POST ne se
  voit donc pas.
- Les formes d'attaque inconnues des signatures passent. C'est la limite assumée de cette méthode,
  que le modèle appris de l'[ADR 0029](0029-modele-appris-requetes-web.md) réduit sans l'effacer.
- Les journaux d'accès web ne sont pas suivis par défaut : leur chemin dépend du site servi, un
  administrateur doit l'indiquer (`access_logs`).
- Le format d'accès reconnu est le format courant (nginx, Apache). Un format personnalisé n'est
  pas lu.
- **Rien n'a été éprouvé sur du vrai trafic.** Les signatures sont vérifiées sur des exemples
  écrits à la main et sur des requêtes légitimes du même genre que celles de l'école ; le taux de
  fausses alertes sur le trafic réel de l'EPT reste à mesurer.
