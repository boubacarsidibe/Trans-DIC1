# Plan de validation sur l'infrastructure de l'EPT (S4-TEST, #32)

Ce plan est à faire approuver par le CRI **avant** toute intervention. Il ne contient aucune
adresse, aucun nom d'hôte ni aucun identifiant réel : ces informations restent dans le formulaire
d'autorisation signé, conservé hors du dépôt.

## 1. Autorisation (à signer avant de commencer)

| Élément | Valeur |
|---|---|
| Responsable CRI qui autorise | _nom, fonction_ |
| Équipe Trans-DIC1 présente | Boubacar Sidibe, Khadija |
| Fenêtre de test | _date, heure de début, heure de fin_ |
| Cibles autorisées | _liste remise par le CRI (N serveurs Linux, N équipements SNMP)_ |
| Protocoles autorisés | agent Trans-DIC1 (HTTPS sortant vers la plateforme), SNMP v2c/v3 en lecture seule |
| Interdits | tout balayage de ports ou de plages, toute écriture SNMP, toute modification de configuration des équipements, la découverte automatique hors des plages écrites ici |
| Plages de découverte autorisées | _aucune par défaut ; sinon la plage exacte_ (`DISCOVERY_ALLOWED_RANGES`) |
| Serveur de la plateforme | _machine fournie par le CRI ou poste de l'équipe_ |
| Contact d'urgence CRI | _nom, téléphone_ |

Sans ce tableau rempli et signé, on ne lance rien.

## 2. Préparation (la veille)

1. Installer la plateforme selon `docs/devops/deployment.md` et lancer `deploy/smoke-test.sh` :
   tous les contrôles doivent être verts.
2. Sauvegarde initiale : `docker compose --profile backup up -d`, puis vérifier qu'une sauvegarde
   chiffrée et sa restauration de contrôle existent (`docs/devops/postgresql.md`).
3. Retour arrière : relancer `deploy/deploy.sh` avec l'étiquette précédente et vérifier que la
   plateforme revient (`docs/devops/deployment.md`, section retour arrière).
4. Créer les comptes de test (un par rôle) ; noter seulement leur rôle dans le journal.
5. Vérifier que la découverte automatique est **désactivée** ou limitée aux plages autorisées.

## 3. Cas de test

Chaque cas a un critère de succès mesurable. La preuve est une capture **assainie** (voir §5).

| # | Cas | Étapes | Critère de succès |
|---|---|---|---|
| V1 | Santé de la plateforme | `GET /actuator/health`, page de connexion | `UP`, page affichée en moins de 2 s |
| V2 | Agent sur un serveur autorisé | installer l'agent (`docs/devops/agent.md`), jeton d'enrôlement | équipement « En service », première mesure en moins de 2 min |
| V3 | SNMP sur un équipement autorisé | configurer la collecte (Administration > Collecte) en lecture seule | mesures reçues à chaque cycle, aucune erreur d'authentification |
| V4 | Stockage et historique | attendre 30 min, ouvrir l'historique | courbe continue, pas de trou supérieur à 2 cycles |
| V5 | Tableau de bord temps réel | ouvrir le tableau de bord sur deux postes | les deux se mettent à jour sans rechargement |
| V6 | Alerte sur seuil | règle temporaire au seuil bas (ex. CPU > 1 %) sur une seule cible | alerte ouverte après la durée de confirmation, une seule |
| V7 | Notification | canal courriel de test vers l'adresse de l'équipe | message reçu en moins de 2 min |
| V8 | Traitement | prise en charge puis résolution par l'opérateur NOC | historique complet, audit à jour |
| V9 | Retour à la normale | supprimer la règle temporaire | alerte résolue automatiquement |
| V10 | Perte de collecte | arrêter l'agent sur la cible de test | alerte de collecte, puis résolution au redémarrage |
| V11 | Droits | lecteur, opérateur, administrateur | chacun ne voit et ne fait que ce que son rôle permet |
| V12 | Connexion unique (si le CRI fournit le client OIDC) | liste de `docs/devops/connexion-unique.md` | toutes les cases cochées |
| V13 | Healthchecks et redémarrage | arrêter le conteneur backend | redémarrage automatique, alerte technique |
| V14 | Retour arrière | déployer une étiquette précédente | plateforme fonctionnelle en moins de 5 min |

## 4. Minimisation des données

- Seules les métriques techniques prévues sont collectées : processeur, mémoire, disque,
  disponibilité, latence, trafic. Aucune donnée personnelle, aucun contenu de trafic.
- Les noms des équipements dans Trans-DIC1 sont des codes neutres (ex. `EPT-SRV-01`), pas les noms
  d'hôte réels.
- La rétention est réduite pendant le test ; les données sont supprimées à la fin (§7) sauf
  accord contraire du CRI.

## 5. Preuves assainies

Avant d'enregistrer une capture : masquer adresses IP, noms d'hôte, noms de personnes et jetons.
Enregistrer dans `docs/validation/preuves/` (hors dépôt si le CRI le demande) avec le numéro du cas.

Journal (une ligne par cas) :

| Cas | Date et heure | Résultat (OK / KO) | Preuve | Anomalie (issue) | Commentaire |
|---|---|---|---|---|---|
| V1 | | | | | |
| … | | | | | |

## 6. Anomalies

Chaque écart crée une issue GitHub séparée : titre `[S4-TEST] <constat>`, étapes, résultat attendu
et obtenu, sans donnée sensible, liée à #32.

## 7. Clôture

1. Supprimer les règles temporaires et les comptes de test.
2. Désinstaller l'agent des cibles si le CRI le demande (`docs/devops/agent.md`).
3. Supprimer les mesures collectées, ou les garder selon l'accord.
4. Faire signer le procès-verbal : cas réussis, anomalies ouvertes, décision du CRI.
