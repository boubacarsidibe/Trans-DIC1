# ADR 0054 : inscription automatique des agents

- Statut : accepté
- Date : 2026-11-26
- Évolution demandée par le CRI (lot 1 : déployer à grande échelle sans geste manuel)
- Complète l'[ADR 0012](0012-agent.md), l'[ADR 0023](0023-agent-windows.md) et l'[ADR 0006](0006-collection-realtime-runtime.md) ; respecte l'[ADR 0018](0018-perimetre-par-site.md) et l'[ADR 0005](0005-authentication-and-refresh.md)

## Contexte

Superviser un serveur demandait trois gestes dans l'interface : créer l'équipement, enregistrer
son agent, recopier le jeton affiché sur la machine. Pour une salle de dix serveurs, c'est
supportable. Pour les milliers de serveurs et de machines virtuelles que le CRI veut voir, c'est
impossible : personne ne crée mille équipements à la main, et un pipeline de déploiement ne peut
pas cliquer dans une page web.

Zabbix (auto-enregistrement des agents actifs) et Wazuh (`agent-auth` avec mot de passe partagé)
règlent ce problème de la même manière : un secret partagé par tout un parc permet à chaque agent
de se présenter seul. La question n'était donc pas de savoir s'il fallait le faire, mais **comment
le faire sans qu'un secret partagé devienne une porte d'entrée sur la plateforme**.

## Options

1. **Distribuer le jeton personnel de chaque agent par le pipeline** : il faut quand même créer
   chaque équipement et chaque jeton à l'avance, et stocker mille secrets dans le coffre du
   pipeline. Le problème est déplacé, pas résolu.
2. **Un jeton d'API d'administrateur dans le pipeline**, qui crée l'équipement puis enregistre
   l'agent par l'API existante : le pipeline détient alors un secret qui ouvre toute l'API, y
   compris la suppression d'équipements. Une fuite de la variable de CI vaut un compte
   administrateur.
3. **Un jeton d'inscription partagé, à portée unique** : il ne sait faire qu'une chose, inscrire
   une machine dans un site donné, et l'agent reçoit en retour son propre jeton.
4. **Certificats clients (mTLS)** : plus solide, mais il faut une autorité de certification, la
   distribution et le renouvellement des certificats sur chaque machine — exactement le geste
   manuel qu'on veut supprimer. Écartée pour la même raison que pour les proxys (ADR 0047).

## Décision

Option 3.

### Le jeton d'inscription n'ouvre que l'inscription

- Préfixe distinct (`tdic1e_` contre `tdic1_` pour les agents), 32 octets de `SecureRandom`,
  affiché une seule fois à la création ; seule l'empreinte SHA-256 est stockée.
- Une **chaîne de sécurité dédiée** (ordre 0) ne répond qu'à `POST /api/v1/agent/enroll`, et
  n'accepte que les jetons d'inscription. La chaîne des agents (ordre 1, `/api/v1/agent/**`) ne
  les reconnaît pas : un jeton d'inscription n'envoie aucune mesure. Réciproquement, un jeton
  d'agent ou un JWT d'utilisateur ne permet pas de s'inscrire. Les tests le vérifient dans les
  deux sens.
- La recherche se fait par empreinte dans un index : aucune comparaison de chaîne ne porte sur
  le jeton lui-même, donc rien dont le temps de réponse trahisse un préfixe correct.

### Chaque garde-fou refuse et laisse une trace

Un jeton porte : un site cible, une date d'expiration (365 jours au plus), un nombre maximal
d'utilisations, des plages d'adresses autorisées facultatives (IPv4 et IPv6, CIDR), un mode
(`AUTO` ou `APPROVAL`), un type d'équipement par défaut (serveur ou machine virtuelle seulement :
l'agent ne tourne pas sur un routeur).

- Révocation et plage d'adresses valent pour toute demande. Expiration et compteur valent pour
  toute demande **qui consomme une utilisation** (machine nouvelle ou qui se réinscrit).
- Une machine approuvée qui vient chercher son jeton n'en consomme pas, et l'expiration ne lui est
  pas opposée : elle a consommé en déposant sa demande, et l'approbation d'un administrateur ne
  doit pas être perdue parce que le jeton a expiré pendant la nuit. La révocation, elle, l'arrête.
- Chaque issue est tracée dans l'audit : inscrite (`AGENT_ENROLLED`), en attente
  (`AGENT_ENROLLMENT_PENDING`), refusée avec sa raison exacte (`AGENT_ENROLLMENT_REFUSED`),
  approuvée, refusée par un administrateur, équipement créé ou adopté. L'agent, lui, ne reçoit
  qu'un message générique : qui détient un jeton volé n'apprend pas s'il est expiré ou épuisé.
- Les refus ne sont pas levés comme exceptions mais rendus comme résultat : la transaction est
  validée, et l'entrée d'audit du refus n'est pas annulée avec elle.
- Limitation de débit par adresse, **avant** la lecture du jeton (30 demandes par minute par
  défaut, puis 429 avec `Retry-After`) : essayer des jetons au hasard coûte cher, et l'agent, qui
  réessaie seul, ne perd rien.
- Un jeton inconnu n'est rattaché à rien : une ligne `security_event` dans le journal, pas
  d'entrée d'audit (sinon n'importe qui remplirait la table d'audit).

### Une machine ne crée jamais deux équipements

L'agent envoie un identifiant de machine stable : **une empreinte HMAC-SHA256 propre à
Trans-DIC1** de `/etc/machine-id` (Linux) ou de `MachineGuid` (Windows), sur le modèle de
`sd_id128_get_machine_app_specific` de systemd. L'identifiant brut, que systemd demande de
considérer comme confidentiel, ne quitte jamais la machine ; l'empreinte reste la même d'une
réinstallation à l'autre.

Une contrainte unique (site, identifiant) rend l'inscription idempotente : une machine déjà
inscrite retrouve son équipement, reçoit un nouveau jeton, et l'ancien est révoqué — exactement
comme un nouvel enregistrement manuel par `AgentRegistrationService`, que l'inscription réutilise.

Un équipement déjà inventorié (par la synchronisation de la CMDB du lot 2, par exemple) est
**adopté** plutôt que doublé, à une condition stricte : son adresse de gestion est **l'adresse de
la connexion TCP**, il est dans le site du jeton, il n'a pas d'agent actif et n'est rattaché à
aucune autre inscription. Jamais sur la foi des adresses que l'agent déclare : sinon un jeton volé
permettrait de se greffer sur n'importe quel serveur du site.

### AUTO ou APPROVAL

- `AUTO` : l'équipement (code d'inventaire généré `SITE-AG-XXXXXXXX`, statut inconnu jusqu'à la
  première mesure) et l'agent sont créés aussitôt ; la réponse 201 porte le jeton personnel.
- `APPROVAL` : la demande est enregistrée en attente et l'agent reçoit 202 avec le délai conseillé.
  Il redemande avec un délai croissant (doublé à chaque fois, une heure au plus, un peu d'aléa pour
  que mille agents ne redemandent pas ensemble). Un administrateur approuve ou refuse, un par un
  ou par lot ; l'approbation crée l'équipement tout de suite, l'agent reçoit son jeton à sa
  prochaine demande. Une machine déjà inscrite qui se réinscrit avec un jeton `APPROVAL` repasse
  par l'approbation : changer le jeton d'une machine connue est un acte sensible.

### Côté agent

- `enroll_token_file` dans la configuration ; `--enroll-token-file` dans `install.sh`,
  `-EnrollTokenFile` dans `install.ps1`.
- Au démarrage, s'il n'a pas de jeton personnel, l'agent s'inscrit, écrit le jeton reçu d'un coup
  (fichier temporaire puis renommage, droits 600 sous Linux, ACL héritée et vérifiée sous Windows)
  et supprime le jeton d'inscription. Ni l'un ni l'autre n'apparaît dans un message.
- Linux : le jeton est écrit dans `/var/lib/trans-dic1-agent` (`StateDirectory` de systemd), seul
  endroit où l'agent peut écrire malgré `ProtectSystem=strict` ; `/etc` reste en lecture seule
  pour lui. Windows : `ProgramData\Trans-DIC1-Agent\state`, modifiable par le compte de l'agent,
  sans aucun groupe large.
- Relancer l'installation sur une machine déjà inscrite ne la réinscrit pas : le jeton
  d'inscription est ignoré (idempotence attendue par le rôle Ansible du lot 2).
- Le contrat de la demande est figé dans `agent/tests/fixtures/agent-enroll.json` : les tests de
  l'agent comparent leur sortie à sa forme, et le backend l'envoie à sa vraie API.

## Conséquences

- **Un jeton d'inscription fuité permet d'inscrire des machines, pas de lire ni de modifier quoi
  que ce soit d'autre.** Le compteur, l'expiration et les plages d'adresses bornent ce qu'un
  attaquant peut créer ; le mode `APPROVAL` met un humain dans la boucle pour les réseaux moins
  sûrs.
- **Un jeton d'inscription fuité permet aussi de réinscrire une machine connue dont on connaît
  l'identifiant**, ce qui révoque le jeton de son agent légitime : c'est le prix de l'idempotence,
  le même que chez Wazuh. Le risque est borné par le fait que l'identifiant est une empreinte
  locale (il ne se lit pas de l'extérieur), et il est visible : l'agent révoqué se tait, le chien
  de garde ouvre une alerte, l'audit dit `ancienJetonRevoque=oui`. Le mode `APPROVAL` le supprime.
- **Des VM clonées sans régénérer leur identifiant** (`/etc/machine-id` d'un modèle, image
  Windows non généralisée par sysprep) se présentent comme une seule machine : la seconde
  inscription révoque le jeton de la première, qui se tait et déclenche l'alerte de silence. Les
  images cloud récentes régénèrent l'identifiant au premier démarrage ; pour un modèle maison, il
  faut vider `/etc/machine-id` avant de figer le modèle (documenté dans `docs/devops/agent.md`).
- Les inscriptions d'un même jeton sont **sérialisées** par un verrou sur sa ligne (pour que deux
  machines ne dépassent pas ensemble le compteur). Chaque inscription est une transaction courte ;
  cela suffit pour quelques dizaines d'inscriptions par seconde, largement au-dessus de ce que la
  limitation par adresse laisse passer depuis un même bâtiment.
- La limitation de débit est **par instance** : derrière plusieurs instances du backend, le seuil
  effectif est multiplié d'autant.
- Rien ne change pour l'enregistrement manuel (`POST /api/v1/equipements/{id}/agent`) ni pour les
  agents déjà installés.

## Limites

- **Le critère du lot (trois machines réelles, un même jeton, trois équipements actifs) n'a pas
  été vérifié sur trois machines** : aucun parc de test n'était disponible. Ce qui a été vérifié :
  seize tests d'intégration contre PostgreSQL 16 (trois inscriptions de machines distinctes →
  trois équipements qui reçoivent leurs mesures, idempotence, expiration, révocation, compteur,
  plages IPv4 et IPv6, approbation et refus par lot, cloisonnement des jetons dans les deux sens,
  adoption, contrat partagé) ; et une installation **réelle sous Windows 11** avec
  `install.ps1 -EnrollTokenFile` contre le faux serveur de test : inscription, jeton personnel
  écrit avec l'ACL héritée (SYSTEM, administrateurs, SERVICE LOCAL, aucun groupe large), jeton
  d'inscription supprimé, mesures envoyées avec le jeton reçu, aucun jeton dans les journaux,
  relance de l'installation sans effet.
- **L'installation Linux par `install.sh --enroll-token-file` a été exécutée sous WSL 1** (Kali,
  noyau émulé 4.4, en root, avec `--no-systemd`) contre le faux serveur : dossier d'état 700,
  jeton d'inscription 600, inscription puis mesures avec le jeton reçu, jeton personnel écrit en
  600 par le compte de l'agent, jeton d'inscription supprimé, aucun jeton dans les journaux,
  relance et mise à jour sans effet, purge complète. **Pas sur une distribution réelle avec
  systemd** : `StateDirectory` et `ProtectSystem=strict` n'ont donc pas été éprouvés ensemble. Le
  test du paquet en conteneur (`agent/tests/install-test.sh`, complété pour l'inscription) demande
  Docker, absent du poste de développement, et n'a pas été lancé.
- **Les tests de l'agent ont tourné sous Python 3.12 (Windows 11) et 3.14 (Linux, WSL 1)**, pas
  sous 3.8 : aucun Python 3.8 n'était disponible. Le code n'emploie aucune construction postérieure
  à 3.8, mais ce n'est pas une preuve ; `agent/run-tests.sh` (Docker) lance 3.8 et 3.12.
- L'identifiant de machine sous Windows Server, et la lecture de `MachineGuid` par un Python 32 bits
  sur un Windows 64 bits (clé `KEY_WOW64_64KEY`), n'ont pas été essayés.
- Pas de renouvellement automatique du jeton personnel : un agent dont le jeton est révoqué ne se
  réinscrit pas seul (le jeton d'inscription a été supprimé, à dessein). Il faut relancer
  l'installation avec un jeton d'inscription.
