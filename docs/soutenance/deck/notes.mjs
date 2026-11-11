// Notes orateur, une entrée par diapositive, DANS L'ORDRE DU SUPPORT (soutenance de 15 minutes, deux voix).
// Conventions : [B] = Boubacar parle, [K] = Khadija parle ; [CLIC] = un clic (apparition suivante) ;
// [PAUSE] = deux secondes de silence en regardant le jury ; ⏱ = repère de temps cumulé visé.
// {VIDEO} et {RETOUR} sont remplacés à la construction par les numéros de diapositive réels.
export const SLIDES = [
  {
    title: "Couverture",
    who: "B",
    time: "0:25",
    notes: `⏱ 0:00 → 0:25 · [B]
[PAUSE] Bonjour. Lundi, huit heures. Le routeur central de l'école tombe.
Qui le sait en premier ? Aujourd'hui… c'est un étudiant, qui appelle le CRI.
[PAUSE] Je suis Boubacar Sidibe, voici Khadija Dieng. Nous allons vous présenter Vigie, notre plateforme de supervision du réseau de l'EPT, réalisée dans le projet Trans-DIC1.
Khadija va d'abord vous montrer le problème.`,
  },
  {
    title: "Le problème",
    who: "K",
    time: "0:45",
    notes: `⏱ 0:25 → 1:10 · [K]
Reprenons ce lundi.
[CLIC] Huit heures, la panne. Vingt minutes plus tard, un enseignant appelle. Une heure après, la cause est enfin trouvée.
[CLIC] Premier constat : personne ne voit l'état du réseau d'un seul coup d'œil.
[CLIC] Deuxième constat : l'alerte vient de ceux qui subissent la panne, pas d'un outil.
[CLIC] Troisième constat : sans historique, impossible de savoir ce qui tombe le plus, ni de justifier un investissement.
[PAUSE] Le CRI est réactif, là où il devrait être proactif.`,
  },
  {
    title: "Problématique et objectifs",
    who: "K",
    time: "0:40",
    notes: `⏱ 1:10 → 1:50 · [K]
D'où notre problématique : comment rendre le CRI proactif, avec peu de fausses alertes, à deux développeurs et sur un seul serveur ?
[CLIC] Nous l'avons traduite en quatre objectifs mesurables.
Détecter une panne en moins de cinq minutes. Escalader automatiquement si personne ne réagit : dix minutes pour une alerte critique. Remettre la plateforme en service en moins de quatre heures après un incident grave. Et garder au moins quatre-vingts pour cent de couverture de tests : en dessous, la construction échoue.`,
  },
  {
    title: "La solution",
    who: "K",
    time: "0:45",
    notes: `⏱ 1:50 → 2:35 · [K]
Voici Vigie en une image : tout le cycle de supervision, dans une seule application.
[CLIC] Collecter : un agent Python sur Linux et Windows, le SNMP avec la découverte du réseau, des sondes sans agent — ping, port, HTTP, certificat TLS —, et l'inventaire avec sa topologie.
[CLIC] Détecter : des règles à seuil que l'on teste sur l'historique avant de les activer, la confirmation et l'hystérésis, l'alerte racine, les anomalies et la prévision de saturation des disques.
[CLIC] Prévenir : la prise en charge par un ou plusieurs opérateurs, l'escalade automatique, l'e-mail, Teams, Slack ou un webhook, et des maintenances planifiées qui ne réveillent personne.
[CLIC] Rendre compte : le tableau de bord en temps réel et le mur NOC, les rapports SLA par site, le journal d'audit, et les droits par rôle et par site.`,
  },
  {
    title: "Comparaison avec les outils existants",
    who: "K",
    time: "0:55",
    notes: `⏱ 2:35 → 3:30 · [K]
Comment se situe Vigie face aux outils existants ? Nous l'avons comparé aux références du logiciel libre : Zabbix, LibreNMS, Prometheus avec Grafana, et Uptime Kuma.
[CLIC] Vigie est le seul à couvrir toutes les lignes dont le CRI a besoin : le cycle d'incident complet, l'alerte racine par la topologie, la détection d'anomalies, le SLA par site hors maintenances, le périmètre par site, et une interface en français. Pour les très grands parcs, Zabbix reste la référence : ce n'est pas le besoin d'un campus.
[CLIC] D'où notre choix, écrit dans l'ADR 2 : développer ce qui fait la différence pour le CRI, et réutiliser les briques éprouvées — SNMP, PostgreSQL, Spring Security.`,
  },
  {
    title: "Démarche",
    who: "K",
    time: "0:25",
    notes: `⏱ 3:30 → 3:55 · [K]
Notre démarche est agile : sept sprints d'environ trois semaines, du socle technique jusqu'au périmètre par site, à la connexion unique et aux sondes.
[CLIC] Chaque décision importante est écrite dans un ADR — dix-neuf au total — et chaque fonctionnalité est tracée par une issue GitHub.`,
  },
  {
    title: "Cas d'utilisation",
    who: "K",
    time: "0:40",
    notes: `⏱ 3:55 → 4:35 · [K]
Avant de coder, nous avons modélisé. Trois profils.
[CLIC] Le lecteur consulte : tableau de bord, alertes, historiques, rapports.
[CLIC] L'opérateur NOC hérite de tout cela, et agit : il prend en charge les alertes, planifie les maintenances.
[CLIC] L'administrateur gère les règles, les comptes, et attribue à chacun ses sites.
[CLIC] Point essentiel : ces droits sont vérifiés par le serveur à chaque requête, pas seulement masqués dans l'interface.`,
  },
  {
    title: "Séquence collecte → alerte",
    who: "K",
    time: "0:35",
    notes: `⏱ 4:35 → 5:10 · [K]
Voici le chemin d'une mesure.
[CLIC] L'agent pousse ses mesures en HTTPS avec son jeton ; Nginx les transmet à l'API.
[CLIC] L'API les enregistre dans la partition du mois, puis demande au moteur de les évaluer.
[CLIC] Le moteur ne crée l'alerte que si le dépassement dure plus longtemps que la durée de confirmation. Alors seulement, l'écran est prévenu en temps réel et les notifications partent.
Boubacar va vous montrer l'architecture qui porte tout cela.`,
  },
  {
    title: "Architecture C4",
    who: "B",
    time: "0:50",
    notes: `⏱ 5:10 → 6:00 · [B]
Vue C4 des conteneurs.
[CLIC] Les utilisateurs et les agents n'entrent que par un seul point : Nginx, en HTTPS. L'agent ne fait que pousser ; il n'accepte aucune connexion entrante.
[CLIC] L'interface React se met à jour en direct par SSE.
[CLIC] L'API Spring Boot, en Java 21, porte toute la logique métier.
[CLIC] PostgreSQL stocke tout ; les mesures sont partitionnées par mois.
[CLIC] Vers l'extérieur : l'interrogation SNMP des équipements, et les canaux de notification.`,
  },
  {
    title: "Monolithe modulaire et choix",
    who: "B",
    time: "0:40",
    notes: `⏱ 6:00 → 6:40 · [B]
L'API est un monolithe modulaire : dix modules aux frontières strictes.
[CLIC] Ces frontières sont vérifiées par vingt et une règles ArchUnit : si un module en franchit une, la construction échoue.
[CLIC] Monolithe plutôt que microservices : à deux développeurs et un serveur, la complexité ne paie pas.
[CLIC] Partitions PostgreSQL natives plutôt que TimescaleDB : rien à installer chez le CRI, et purger un mois revient à supprimer une partition.
[CLIC] SSE plutôt que WebSocket : le flux ne va que du serveur vers l'écran, HTTP suffit.`,
  },
  {
    title: "Technologies",
    who: "B",
    time: "0:40",
    notes: `⏱ 6:40 → 7:20 · [B] (les logos apparaissent seuls, ligne par ligne)
Voici les technologies que nous avons utilisées.
Côté serveur : Java 21 et Spring Boot 4, Spring Security pour l'authentification, Hibernate, construit avec Maven.
Données et collecte : PostgreSQL 16 et Flyway pour faire évoluer le schéma, un agent écrit en Python, SNMP4J pour le SNMP, et OpenID Connect pour la connexion unique.
L'interface : React 19 avec Vite, Tailwind pour le style, Recharts pour les graphiques.
L'exploitation : Nginx, Docker Compose, Prometheus et Grafana, et un chart Helm pour Kubernetes.
Et la qualité : JUnit 5, Vitest, Playwright, le contrat OpenAPI, et GitHub pour le suivi.`,
  },
  {
    title: "Confirmation et hystérésis",
    who: "B",
    time: "0:45",
    notes: `⏱ 7:20 → 8:05 · [B]
Le cœur du projet, c'est la fiabilité des alertes. Premier mécanisme.
[CLIC] Un pic isolé au-dessus de quatre-vingt-dix pour cent : ignoré.
[CLIC] Un dépassement qui dure plus que la durée de confirmation, ici trois minutes : l'alerte s'ouvre.
[CLIC] La mesure redescend à quatre-vingts : l'alerte reste ouverte. C'est l'hystérésis : on ne ferme que sous un seuil de retour plus bas, soixante-dix. Sans cela, l'alerte clignoterait.
[CLIC] Ensuite, l'alerte suit son cycle de vie : prise en charge, résolution, archivage. Sans prise en charge, elle est escaladée.`,
  },
  {
    title: "Corrélation par la topologie",
    who: "B",
    time: "0:40",
    notes: `⏱ 8:05 → 8:45 · [B]
Deuxième mécanisme. Revenons à lundi huit heures.
[CLIC] Le routeur central tombe : les trois commutateurs derrière lui deviennent injoignables.
[CLIC] Sans corrélation : quatre notifications, et l'opérateur doit deviner. Avec Vigie : l'alerte du routeur devient la racine, les trois autres lui sont rattachées et ne notifient personne.
[CLIC] La règle est déterministe et explicable : la cause, c'est l'équipement en panne le plus en amont.`,
  },
  {
    title: "Détection d'anomalies",
    who: "B",
    time: "0:40",
    notes: `⏱ 8:45 → 9:25 · [B]
Troisième mécanisme : repérer l'inhabituel, même sous le seuil. Un serveur à soixante-dix pour cent un dimanche à trois heures, alors qu'il est d'habitude à dix.
[CLIC] La normale est calculée sur vingt-huit jours, à la même heure, pour le même type de jour ; on mesure l'écart en écarts-types ; au-delà de trois et demi, on alerte.
[CLIC] Sur notre jeu d'évaluation : zéro virgule cinquante-deux pour cent de fausses alertes, cent pour cent des anomalies détectées.
[CLIC] Et chaque alerte s'explique : voici une vraie alerte levée par Vigie sur notre démonstration. Pas de boîte noire : c'est pour cela que nous avons préféré une méthode statistique à l'apprentissage automatique.
(Si le jury demande : jeu reproductible, graine 42, 40 séries, 28 jours d'apprentissage et une semaine jugée.)`,
  },
  {
    title: "L'application",
    who: "K",
    time: "0:20",
    notes: `⏱ 9:25 → 9:45 · [K]
Voici l'application.
[CLIC] Le tableau de bord, en temps réel.
[CLIC] Les alertes, regroupées sous leur racine.
[CLIC] Et l'écran mural pour la salle du CRI.
Boubacar va vous la montrer en direct.`,
  },
  {
    title: "Démonstration",
    who: "B+K",
    time: "2:45",
    notes: `⏱ 9:45 → 12:30 · démonstration en direct (Alt+Tab vers le navigateur)
[B] 0. TOUT DE SUITE, avant de parler : couper le routeur de cœur et la branche A avec la commande préparée dans un terminal, New-Item docs\\soutenance\\deck\\build\\panne.flag. L'alerte arrive en 1 à 2 minutes (mesuré : 92 s) : les étapes 1 et 2 couvrent l'attente.
[B] 1. Connexion en opérateur NOC : le tableau de bord se met à jour seul ; l'alerte d'anomalie est déjà là. (25 s)
[B] 2. Topologie, puis l'historique d'un équipement : la courbe, le seuil. (50 s)
[B] L'alerte racine apparaît, les trois autres sont rattachées dessous. « Khadija va la traiter. »
[K] 3. Prise en charge de l'alerte racine, ajout d'un collègue : historique et assignés. (25 s)
[K] 4. On rétablit : Remove-Item docs\\soutenance\\deck\\build\\panne.flag. Résolution automatique de la racine et des alertes rattachées (mesuré : 13 s). (25 s)
[K] 5. Rapport SLA par site, export PDF. (20 s)
[B] 6. Connexion en lecteur : mêmes écrans, aucun bouton d'action. Les droits sont vérifiés par le serveur. (20 s)
SECOURS : si quelque chose ne répond pas, revenir au PowerPoint et taper {VIDEO} puis Entrée : la vidéo de la démonstration se lance.`,
  },
  {
    title: "Vidéo de secours (masquée)",
    who: "B",
    time: "—",
    notes: `Diapositive masquée : elle ne s'affiche que si on la demande (taper {VIDEO} puis Entrée pendant le diaporama).
Lancer la vidéo (clic dessus) et commenter les mêmes étapes que la démonstration en direct.
Ensuite, taper {RETOUR} puis Entrée pour reprendre à la sécurité.`,
  },
  {
    title: "Sécurité",
    who: "B",
    time: "0:40",
    notes: `⏱ 12:30 → 13:10 · [B]
Une requête traverse quatre couches indépendantes.
[CLIC] L'exposition : seul Nginx est ouvert, en HTTPS ; les sondes refusent les adresses internes, contre les attaques SSRF.
[CLIC] L'identité : le compte de l'école en OpenID Connect avec PKCE ; blocage après cinq essais.
[CLIC] Les droits : trois rôles et un périmètre par site, vérifiés par le serveur à chaque requête.
[CLIC] La traçabilité : un journal d'audit en lecture seule, et aucun secret en base ni dans le code.
[PAUSE] Si une couche cède, les autres tiennent. Khadija va vous parler de la qualité.`,
  },
  {
    title: "Qualité",
    who: "K",
    time: "0:35",
    notes: `⏱ 13:10 → 13:45 · [K]
La qualité est vérifiée à chaque construction : cinq cent cinquante tests côté serveur, sur une vraie base PostgreSQL, deux cent vingt-cinq côté interface, vingt et une règles d'architecture, dix-neuf décisions écrites.
[CLIC] Des parcours de bout en bout avec Playwright, le contrat d'API vérifié, et une couverture minimale imposée.
[CLIC] Côté exploitation : un déploiement avec retour arrière automatique, une sauvegarde chiffrée chaque jour avec restauration contrôlée, et la plateforme elle-même supervisée.`,
  },
  {
    title: "Bilan",
    who: "K",
    time: "0:25",
    notes: `⏱ 13:45 → 14:10 · [K]
En bilan, Vigie apporte au CRI une vue unique du réseau en temps réel, des alertes fiables, et des rapports pour décider.
[CLIC] En chiffres : sept sprints, dix-neuf décisions écrites, dix modules, plus de sept cent soixante-dix tests automatiques.
Boubacar conclut.`,
  },
  {
    title: "Conclusion",
    who: "B",
    time: "0:25",
    notes: `⏱ 14:10 → 14:35 · [B]
[CLIC] Revenons une dernière fois à lundi. Huit heures zéro une.
[CLIC] L'opérateur est prévenu. Il reçoit une seule alerte. Et la cause est désignée.
[PAUSE] C'est ce que Vigie apporte au CRI : voir les pannes avant les utilisateurs.
[CLIC] Merci de votre attention. Nous sommes prêts pour vos questions.`,
  },
];

// Annexes : pas de minutage, à ouvrir en réponse à une question (taper le numéro puis Entrée).
export const ANNEX_NOTES = {
  "Modèle du domaine": "Tout est rattaché au site : site → équipements → mesures → alertes. L'alerte suit un cycle de vie tracé dans le journal d'audit.",
  "Cycle de vie complet d'une alerte": "Ouverte, prise en charge, résolue, archivée ; réouverture si la mesure redépasse avant archivage ; pendant une maintenance, l'alerte est tracée sans notification.",
  "Séquence détaillée": "Les deux modes de collecte : l'agent pousse (aucune connexion entrante vers le serveur supervisé), le SNMP est interrogé par le planificateur.",
  "Rapport SLA et prévision de saturation": "Disponibilité mesurée par site contre son objectif, maintenances exclues, export PDF. La prévision de saturation des disques se fait par régression sur 7 jours, sans configuration.",
  "Connexion unique OIDC + PKCE": "Le mot de passe ne passe jamais par Vigie. Le code verifier PKCE empêche le vol du code d'autorisation. Les groupes de l'annuaire deviennent des rôles. Connexion locale de secours.",
  "Stockage des mesures": "Une partition par mois, créée trois mois à l'avance ; purge = suppression de partition. Agrégats horaires pour les longues périodes ; l'API choisit le pas (minute, heure, jour), 5 000 points au plus.",
  "Déploiement et exploitation": "Un seul point d'entrée, déploiement par étiquette avec retour arrière automatique, sauvegarde chiffrée quotidienne restaurée, plateforme supervisée, chart Helm pour Kubernetes.",
};
