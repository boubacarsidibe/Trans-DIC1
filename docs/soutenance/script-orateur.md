# Script orateur : soutenance Trans-DIC1

Généré depuis `docs/soutenance/deck/notes.mjs`.
**[CLIC n]** = un clic sur la télécommande ; **[PAUSE]** = se taire et regarder le jury ; **[→ diapositive suivante]** = clic qui change de diapositive.
Durée visée : 20 à 22 minutes, puis les questions.

## 1. Couverture

[PAUSE] Bonjour. Nous allons vous présenter Trans-DIC1, notre projet de supervision réseau pour l'École Polytechnique de Thiès.  
  
Je m'appelle Boubacar Sidibe, et voici Khadija Dieng. Nous sommes en deuxième année de DIC, et ce projet est notre travail de fin de cycle.  
  
[PAUSE] Le titre : « Votre réseau, sous contrôle. » C'est en une phrase ce que ce projet cherche à résoudre.  
  
En vingt minutes, nous allons vous montrer pourquoi ce problème mérite d'être résolu, comment nous l'avons abordé, et ce que nous avons construit.

## 2. Sommaire

Notre présentation suit six parties.  
  
Nous commençons par le contexte et la problématique — pourquoi l'EPT a besoin d'un outil de supervision.  
  
Ensuite la solution et la démarche — comment nous avons structuré le projet.  
  
La conception — les diagrammes que nous avons produits.  
  
L'architecture — c'est la partie la plus technique, et la plus importante. Nous y passerons le plus de temps.  
  
La réalisation — ce qui est construit, qui peut faire quoi, et une démonstration en direct.  
  
Et enfin le bilan — ce qui reste, et la suite.

## 3. Intercalaire 01

[CLIC 1] Première partie : contexte et problématique.  
  
Avant de présenter une solution, il faut comprendre le terrain.

## 4. Contexte — L'EPT et son réseau

L'École Polytechnique de Thiès est un campus multi-bâtiments. Plusieurs bâtiments interconnectés, des équipements hétérogènes : des routeurs, des commutateurs, des points d'accès.  
  
Le Centre de Ressources Informatiques — le CRI — est l'équipe chargée de maintenir tout ça en état de marche. Ils s'occupent du réseau, des serveurs, des services numériques.  
  
Et ce réseau est critique : si la connexion tombe pendant un examen, pendant un cours, pendant un envoi de notes, tout s'arrête.  
  
[CLIC 1] Trans-DIC1 est notre projet de fin de cycle. Sept milestones, soixante-sept issues fermées sur GitHub, dix-huit décisions d'architecture documentées. Nous avons commencé en avril, et nous voilà en novembre.

## 5. Problématique

[CLIC 1] Première constatation : personne ne voit ce qui se passe sur le réseau en temps réel. Les équipements sont répartis sur plusieurs bâtiments, et il n'existe pas d'écran qui montre leur état simultanément.  
  
[CLIC 2] Deuxième constatation : quand une panne survient, c'est souvent un étudiant ou un enseignant qui appelle le CRI pour le signaler. L'alerte vient de ceux qui subissent la panne, pas d'un outil de supervision.  
  
[CLIC 3] Troisième constatation : sans historique, impossible de savoir ce qui tombe le plus souvent, ni de préparer un rapport de disponibilité, ni de justifier un investissement en matériel.  
  
[PAUSE] En résumé : le CRI est réactif là où il devrait être proactif.

## 6. Objectifs et périmètre

[CLIC 1] Trois objectifs.  
  
Un : surveiller en continu. Mesures toutes les minutes, vingt-quatre heures sur vingt-quatre, sept jours sur sept.  
  
Deux : alerter avant les utilisateurs. Détecter les anomalies en moins de cinq minutes, et escalader automatiquement si personne ne prend en charge.  
  
Trois : produire des rapports. Disponibilité par site, incidents, niveaux de service — des données prêtes à présenter au directoire ou à un financeur.  
  
[PAUSE] Ce que nous ne couvrons pas : le débogage applicatif, la configuration à distance des équipements, la gestion des incidents au-delà de l'alerte.

## 7. Intercalaire 02

[CLIC 1] Deuxième partie : la solution et la démarche.  
  
Maintenant que le problème est posé, voici comment nous l'avons résolu.

## 8. Solution — le cycle en quatre temps

[CLIC 1] Notre solution s'organise en quatre temps, dans une seule application.  
  
Collecter : un agent Python tourne sur le réseau, envoie une mesure par minute à travers HTTPS.  
  
Détecter : le backend compare chaque mesure à des règles configurées par le CRI. Pas d'alerte immédiate — le seuil doit être dépassé pendant une durée configurable pour éviter les faux positifs.  
  
Prévenir : quand une alerte est confirmée, une notification part vers les bonnes personnes — par courriel, Teams ou Slack.  
  
Rendre compte : tout l'historique est conservé. On peut produire un rapport de disponibilité sur n'importe quelle période.  
  
[PAUSE] Et tout ça se met à jour en direct, sans recharger la page.

## 9. Démarche Scrum et ADR

Notre démarche est agile, avec des sprints d'environ trois semaines.  
  
Sprint zéro : on pose la base — dépôt, CI, Docker, pipeline.  
Sprint un : authentification et gestion des équipements.  
Sprint deux : collecte des métriques et agent de sonde.  
Sprint trois : alertes et notifications.  
Sprint quatre : connexions inter-sites et déploiement.  
Sprint cinq : rapports, SLA, et préparation de la soutenance.  
Sprint six : le périmètre par site, OIDC, et les dernières dettes techniques.  
  
[CLIC 1] Ce qui distingue notre démarche : chaque décision importante est documentée dans un ADR — un Architecture Decision Record. Dix-huit en tout. Le contexte, les options étudiées, le choix retenu, et les conséquences. Soixante-sept issues fermées sur GitHub tracent chaque fonctionnalité et chaque correction.

## 10. Intercalaire 03

[CLIC 1] Troisième partie : la conception.  
  
Avant d'écrire la première ligne de code, nous avons modélisé. Voici les diagrammes que nous avons produits.

## 11. Cas d'utilisation

[CLIC 1] Le diagramme de cas d'utilisation montre trois profils.  
  
Le lecteur peut consulter : tableau de bord, alertes, équipements, rapports. Il voit, il ne touche pas.  
  
L'opérateur NOC peut agir sur les alertes, modifier les équipements, gérer les maintenances.  
  
L'administrateur contrôle tout : comptes, règles d'alerte, périmètres.  
  
[CLIC 2] Ce que ce diagramme ne montre pas directement : chaque profil est limité au périmètre de sites qui lui est attribué. Un opérateur du bâtiment A ne voit pas les alertes du bâtiment B.  
  
[CLIC 3] Et tout ce contrôle est vérifié côté serveur, pas seulement dans l'interface.

## 12. Modèle du domaine

[CLIC 1] Le modèle du domaine montre comment les entités s'articulent.  
  
Site regroupe les équipements. Équipement génère des mesures. Mesure peut déclencher une alerte.  
  
Alerte suit un workflow : ouverte, prise en charge, résolue, archivée. Ce workflow est tracé dans le journal d'audit.  
  
Rapport agrège les mesures sur une période pour calculer la disponibilité et les SLA par site.  
  
[PAUSE] Tout est lié à un site. C'est la colonne vertébrale du modèle.

## 13. Séquences collecte → alerte

[CLIC 1] Ce diagramme montre le chemin d'une mesure, de l'agent jusqu'à l'alerte.  
  
L'agent Python envoie une mesure toutes les minutes via HTTPS. Le backend la stocke, puis le service de monitoring la compare à ses règles.  
  
Si le seuil est dépassé une fois, rien ne se passe encore. Si le dépassement dure plus longtemps que la durée de confirmation — configurable par règle — alors une alerte est créée.  
  
[PAUSE] Ce délai de confirmation est le mécanisme clé pour éviter les faux positifs. Un pic d'une seconde ne crée pas d'alerte.

## 14. Cycle de vie d'une alerte

[CLIC 1] Une alerte passe par quatre états.  
  
Elle s'ouvre quand le seuil est dépassé et confirmé. Un opérateur la prend en charge — ça met fin aux notifications d'escalade. Elle est résolue quand la mesure repasse sous le seuil ou manuellement. Elle est archivée par un administrateur pour clore le dossier.  
  
[PAUSE] Deux mécanismes de garde : l'hystérésis — le seuil de retour est différent du seuil d'alerte, ce qui évite les alertes en boucle — et l'escalade automatique après dix minutes pour les alertes critiques, trente minutes pour les autres.

## 15. Intercalaire 04

[CLIC 1] Quatrième partie : l'architecture.  
  
C'est le cœur technique du projet. Nous allons vous montrer les choix que nous avons faits et pourquoi.

## 16. C4 Conteneurs

[CLIC 1] La vue C4 des conteneurs montre cinq blocs.  
  
L'agent Python : il tourne sur le réseau de l'EPT, il sonde les équipements, et il envoie les mesures au backend par HTTPS. Il ne reçoit rien — il ne fait que pousser.  
  
[CLIC 2] Le backend Spring Boot : dix modules, toute la logique métier. C'est lui qui gère les alertes, les rapports, les comptes, les notifications.  
  
[CLIC 3] PostgreSQL : les mesures, les alertes, les configurations, le journal d'audit. Partitionné par mois pour les mesures.  
  
[CLIC 4] L'interface React : elle affiche tout en temps réel via SSE — Server-Sent Events. Pas de polling, pas de WebSocket.  
  
[CLIC 5] Nginx : le seul point exposé au réseau. HTTPS uniquement. L'agent et l'interface passent tous les deux par lui.

## 17. Architecture logique — 10 modules

[CLIC 1] Le backend est découpé en dix modules.  
  
Sécurité et sessions. Rôles et périmètres. Sites et équipements. Mesures et agent. Règles et seuils. Workflow d'alertes. Rapports et SLA. Journal d'audit. Tableau de bord. Et un module commun partagé.  
  
Ces modules ont des frontières strictes. Un module ne peut pas dépendre de n'importe quel autre.  
  
[CLIC 2] Comment est-ce que nous vérifions ça à chaque construction ? Vingt et une règles ArchUnit. Si un module viole une frontière, le build échoue. Pas de revue de code manuelle — c'est automatique et fail-fast.

## 18. L'agent Trans-DIC1

[CLIC 1] L'agent de collecte est en Python, sans dépendance externe. Pas de pip install en production — c'est intentionnel. Démarré par systemd comme n'importe quel service.  
  
[CLIC 2] L'enrôlement est sécurisé. Un administrateur génère un jeton à usage unique dans l'interface. L'agent ne connaît que ce jeton et l'URL du backend. Une fois enrôlé, il reçoit ses identifiants définitifs.  
  
[CLIC 3] Il sonde en SNMP v2c ou v3. Les secrets — community string, mots de passe authPriv — sont référencés par variables d'environnement, jamais écrits dans le code.  
  
[CLIC 4] Il ne reçoit aucune connexion entrante. Il pousse uniquement, en HTTPS. La découverte est limitée aux plages CIDR déclarées dans l'interface. Un attaquant qui compromet le réseau local ne peut pas atteindre le backend par l'agent.

## 19. Choix structurants

[CLIC 1] Premier choix : monolithe modulaire plutôt que microservices. Deux développeurs, un serveur. Les microservices auraient multiplié la complexité opérationnelle sans apporter de bénéfice à cette échelle. Dix modules isolés avec des frontières vérifiées par ArchUnit — ADR 0001.  
  
[CLIC 2] Deuxième choix : les mesures sont partitionnées par mois. Les données récentes sont au niveau minute. Les données anciennes sont agrégées à l'heure. Supprimer une vieille période, c'est supprimer une partition — instantané, sans verrou, sans impact sur les requêtes en cours — ADR 0004.  
  
[CLIC 3] Troisième choix : sessions courtes avec renouvellement silencieux, et connexion unique via le fournisseur d'identité de l'EPT. Le jeton d'accès dure quinze minutes, il est en mémoire. Le cookie de session renouvelle sans que l'utilisateur ne s'en aperçoive. OpenID Connect avec PKCE — ADR 0005 et 0017.

## 20. Sécurité en couches

[CLIC 1] Première couche : l'identité. Compte de l'école via OpenID Connect ou compte local. Protection contre les essais répétés — blocage après cinq tentatives.  
  
[CLIC 2] Deuxième couche : les droits. Trois rôles, un périmètre par site. Vérifiés par le serveur à chaque requête, pas seulement à la connexion — ADR 0018.  
  
[CLIC 3] Troisième couche : la traçabilité. Chaque action sensible est inscrite dans un journal d'audit en lecture seule. Impossible à effacer par un utilisateur, même administrateur.  
  
[CLIC 4] Quatrième couche : l'exposition. Aucun secret en base de données ni dans le code — tout est dans des variables d'environnement. Seul le proxy Nginx est exposé au réseau.  
  
[PAUSE] Ces quatre couches sont indépendantes. Si l'une est contournée, les autres tiennent.

## 21. Intercalaire 05

[CLIC 1] Cinquième partie : la réalisation.  
  
Passons de l'architecture à ce qui est construit.

## 22. Stack technique

[CLIC 1] Le backend : Spring Boot 4.1.1, Java 21, PostgreSQL 16. Flyway gère les vingt-six migrations de la base. Prometheus et Grafana supervisent le backend lui-même.  
  
[CLIC 2] Le frontend : React 19, Vite 8, TypeScript. Recharts pour les graphes. SSE pour le temps réel. Playwright pour les tests de bout en bout, Vitest pour les tests unitaires.  
  
[CLIC 3] L'infrastructure : Nginx en HTTPS, Docker Compose pour le développement local et le déploiement, systemd pour l'agent. Un chart Helm est préparé pour un déploiement Kubernetes si le CRI le souhaite.  
  
[PAUSE] Vingt-six migrations Flyway. Vingt et une règles ArchUnit. Dix-huit ADR. Couverture de tests à quatre-vingts pourcent minimum — en dessous, le build échoue.

## 23. Qui peut faire quoi ?

Cette table résume les droits de chaque profil.  
  
Le lecteur voit tout, mais ne touche à rien. Il peut consulter le tableau de bord, les alertes, les équipements, les rapports. Il ne peut pas traiter une alerte ni modifier un équipement.  
  
L'opérateur NOC peut agir : traiter les alertes, modifier les équipements, gérer les maintenances. Mais il ne peut pas toucher aux comptes ni aux règles d'alerte.  
  
L'administrateur a accès à tout. Et contrairement aux deux autres, il voit tous les sites — pas seulement ceux qui lui sont attribués.  
  
[PAUSE] La colonne « périmètre par site » en bas résume l'essentiel : lecteur et opérateur sont limités à leurs sites, l'administrateur voit tout.

## 24. Démonstration

[CLIC 1] Nous allons maintenant vous montrer l'application en direct.  
  
[ORATEUR Boubacar] Connexion, tableau de bord, topologie.  
Montrer la liste des équipements, l'état en temps réel.  
Déclencher une panne simulée — couper la réponse SNMP du routeur.  
L'alerte apparaît sur le tableau de bord.  
  
[ORATEUR Khadija] Prise en charge de l'alerte, assignation à un collègue.  
Rétablir la connexion SNMP.  
Alerte résolue automatiquement. Journal d'audit.  
Rapport SLA sur la période.  
  
[ORATEUR Boubacar] Écran mural — vue lecture seule, données en direct.  
Montrer l'interface vue par un compte lecteur limité à un site.

## 25. Qualité et exploitation

[CLIC 1] Quelques chiffres sur la qualité.  
  
Trois cent quatre-vingt-deux tests automatisés côté backend — sur une vraie base PostgreSQL, pas sur des mocks. Chaque test parle à la base comme le ferait le code en production.  
  
Cent quatre-vingt-sept tests côté interface — écrans, sessions, accessibilité.  
  
Vingt et une règles ArchUnit qui vérifient que les modules ne se mélangent pas à chaque build.  
  
Dix-huit ADR — le journal de toutes les décisions importantes.  
  
[PAUSE] Deux garanties opérationnelles. La couverture reste au-dessus de quatre-vingts pourcent — c'est une contrainte du build, pas une recommandation. Et le RTO est de quatre heures : après une panne grave du serveur, le service doit être rétabli en moins de quatre heures — sauvegarde quotidienne, procédure de restauration testée.

## 26. Intercalaire 06

[CLIC 1] Sixième et dernière partie : le bilan.  
  
Ce que nous avons livré, ce qui reste, et la suite.

## 27. Établi et reste

[CLIC 1] Ce qui est établi.  
  
Toutes les fonctions présentées sont livrées et couvertes par des tests. La sécurité et les droits sont vérifiés automatiquement à chaque construction. Le déploiement, la sauvegarde et le retour arrière ont été testés sur poste.  
  
[CLIC 2] Ce qui reste à faire.  
  
La validation sur l'infrastructure réelle de l'EPT — nous n'avons pas encore accès au réseau physique. Les seuils d'alerte devront être calibrés avec le CRI sur de vraies mesures. Et le serveur de préproduction — la CI était suspendue suite à un problème de compte GitHub, elle doit être rétablie.  
  
[PAUSE] Ces points sont connus, documentés, et un plan de validation est prêt.

## 28. Perspectives

[CLIC 1] La première perspective, c'est la validation avec le CRI. Le plan est rédigé, les seuils sont proposés. Nous sommes prêts dès que l'accès au réseau physique est accordé.  
  
[CLIC 2] Le calibrage des seuils suivra naturellement. Les seuils sont modifiables sans redéploiement, directement dans l'interface.  
  
[CLIC 3] L'architecture multi-sites est déjà en place. Ajouter un campus, c'est déclarer un périmètre dans l'interface et déployer un agent supplémentaire.  
  
[CLIC 4] D'autres agents sont envisageables : surveillance HTTP, latence vers le cloud, ou d'autres protocoles selon les besoins du CRI. L'agent est conçu pour être étendu.

## 29. Merci

[PAUSE] Merci de votre attention.  
  
Trans-DIC1 propose à l'EPT de passer d'une supervision réactive à une supervision proactive. Voir les pannes avant les utilisateurs, les documenter, les corriger plus vite.  
  
Nous sommes disponibles pour répondre à vos questions.

## 30. Annexe A1 — Cycle de vie d'une alerte

Annexe pour toute question sur le cycle de vie des alertes.  
  
Quatre états : Ouverte, Prise en charge, Résolue, Archivée.  
  
L'hystérésis évite les boucles : le seuil pour sortir d'alerte est différent du seuil pour y entrer. Par exemple, si l'alerte s'ouvre à 90 % de perte de paquets, elle ne se ferme qu'à 70 %.  
  
Une alerte résolue peut se rouvrir si la mesure redépasse le seuil avant archivage.  
  
Pendant une maintenance planifiée, les alertes sont tracées sans notification — l'équipe sait que l'équipement est en intervention.

## 31. Annexe A2 — Connexion unique OIDC

Annexe sur la connexion unique OpenID Connect avec PKCE.  
  
L'écran de connexion génère un secret à usage unique — le code verifier PKCE. Il redirige l'utilisateur vers le fournisseur de l'EPT. Trans-DIC1 ne voit jamais le mot de passe.  
  
Le fournisseur renvoie un code d'autorisation. Le backend l'échange contre des jetons en présentant le code verifier.  
  
Le serveur vérifie la signature, l'émetteur, et l'expiration du jeton. Il convertit les groupes LDAP de l'EPT en rôles Trans-DIC1.  
  
La déconnexion ferme la session du fournisseur en même temps.  
  
Si le CRI n'a pas encore validé son fournisseur OpenID, la connexion locale reste disponible en secours.

## 32. Annexe A3 — Mesures et partitions

Annexe sur le partitionnement des mesures.  
  
Les mesures sont stockées dans une table partitionnée par mois. Trois partitions sont créées à l'avance. Purger une vieille période, c'est supprimer une partition — instantané, sans verrou, sans impact sur les requêtes en cours.  
  
Pour les graphes sur longue période, des agrégats horaires sont maintenus automatiquement — moyenne, minimum et maximum par heure. Les courbes sur quatre-vingt-dix jours restent rapides malgré des millions de points.  
  
L'API choisit automatiquement le pas de temps selon la période demandée : minute pour les dernières heures, heure pour les derniers jours, jour pour les derniers mois. Cinq mille points au plus par requête.

## 33. Annexe A4 — Risques et parades

Annexe sur les risques identifiés et leurs parades.  
  
Pas de serveur de staging fourni par le CRI : la pile complète a été vérifiée sur poste, et un plan de validation est prêt pour le jour J.  
  
Intégration continue suspendue : les tests, le format et le lint sont relancés manuellement avant chaque fusion.  
  
Seuils non validés avec le CRI : ils sont modifiables sans redéploiement, et une proposition argumentée est prête.  
  
Serveur unique : redémarrage automatique par systemd, sauvegarde quotidienne avec restauration testée, chart Helm disponible si le CRI veut passer à Kubernetes.  
  
Perte de la clé de chiffrement des sauvegardes : nous recommandons de la conserver hors du serveur, dans le coffre du CRI.
