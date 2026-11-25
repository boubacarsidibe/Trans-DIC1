# Rapport des ajouts du 25 novembre 2026

> Ce qui a été codé pour la soutenance, comment le montrer, et ce qui reste à dire honnêtement.
> Branche `develop`, modifications **non commitées** (à relire puis commiter).

## 1. Ce qui a été ajouté

Six fonctions, chacune codée de bout en bout : serveur, écran, tests automatiques, décision
d'architecture écrite, documentation.

| # | Fonction | Où la voir dans l'application | Décision |
|---|---|---|---|
| 1 | **Post-mortem automatique** d'un incident (écran + PDF) | Incidents → un incident → « Post-mortem » | ADR 0053 |
| 2 | **Indicateurs de service** MTTA, MTTR, MTBF | Menu Supervision → « Indicateurs » | ADR 0053 |
| 3 | **Actions correctives automatiques** exécutées par l'agent | Règles → « Action corrective » ; détail d'une alerte | ADR 0050 |
| 4 | **Prise en charge depuis Telegram** (boutons « Je prends », « Résolu ») | Mes notifications → « Prendre en charge depuis Telegram » | ADR 0051 |
| 5 | **Analyse d'impact** avant une maintenance | Topologie → cliquer un équipement → « Analyse d'impact complète » | ADR 0053 |
| 6 | **Prédiction du risque de panne à 24 h** (l'IA explicable) | Tableau de bord → carte « Risque de panne » ; menu « Risque de panne » | ADR 0052 |

Aucune de ces six fonctions n'existe en standard dans Zabbix. La troisième existe sous une autre
forme (« commandes à distance »), mais moins sûre : Zabbix envoie la commande, ici le serveur
n'envoie qu'un nom d'action que l'agent a lui-même déclaré.

## 2. Comment chaque fonction est faite (ce qu'il faut savoir expliquer)

Le détail, rédigé pour l'oral, est dans le **guide d'oral, section 15**
(`docs/soutenance/guide-oral-fonctionnement.pdf`). En résumé :

- **Post-mortem** : recalculé à la demande depuis la base (alertes, historique, notifications,
  astreinte, actions correctives, causes confirmées, maintenances), jamais stocké. Les constats sont
  des règles sur les faits, chacun avec une recommandation. PDF par OpenPDF.
- **Indicateurs** : MTTA = ouverture → prise en charge ; MTTR = ouverture → résolution ; MTBF =
  temps en service ÷ nombre de pannes. Moyenne **et** médiane. Alertes racines seulement.
- **Actions correctives** : la règle porte un nom d'action et un mode (automatique ou sur
  approbation) ; à l'ouverture de l'alerte, une exécution est mise en file ; l'agent la prend à son
  passage suivant (même connexion HTTPS sortante que ses mesures), exécute la commande associée à ce
  nom dans **sa** configuration, sans interpréteur, et rend compte. Garde-fous : une seule exécution
  à la fois (index unique en base), délai de grâce, expiration, rien en maintenance, audit.
- **Telegram** : liaison du compte par code à usage unique ; chaque clic est exécuté au nom du compte
  lié, avec ses droits et son périmètre ; écoute par interrogation longue, sans adresse publique.
- **Analyse d'impact** : aval par la topologie, avec repérage du second chemin (calcul de point
  fixe), sondes et pages de statut touchées, astreinte, niveau d'impact, recommandations,
  maintenance planifiée en un geste.
- **Risque de panne** : régression logistique écrite à la main, apprise chaque nuit sur 60 jours
  (une ligne par équipement et par journée, cible = panne le lendemain), poids tirés vers des poids
  initiaux tant que les pannes sont rares, évaluée sur la dernière semaine (AUC, Brier), chaque score
  expliqué par ses trois faits principaux.

## 3. Ce qui a été vérifié

| Contrôle | Résultat |
|---|---|
| Tests du serveur (toute la suite, vrai PostgreSQL) | 1 427 réussis sur 1 427 (49 nouveaux pour ces fonctions) |
| Tests de l'interface (Vitest) | 380 réussis sur 380 (22 nouveaux) |
| Tests de l'agent (Python) | 130 réussis sur 130 (19 nouveaux) |
| Règles d'architecture (ArchUnit) | réussies, avec 10 règles nouvelles pour les 5 nouveaux modules |
| Contrat d'API (OpenAPI) et types de l'interface | régénérés : 186 opérations |
| Compilation de production de l'interface | réussie |
| Analyse statique de l'interface (oxlint) | aucune erreur ; avertissements du même type que le reste du code |

### 3.1 Tests du serveur

Nouveaux tests : `PostMortemFindingsTests`, `ServiceKpisTests`, `PostMortemApiTests`,
`RemediationApiTests`, `ImpactAssessmentTests`, `ImpactApiTests`, `TelegramChatOpsTests`,
`TelegramSenderTests`, `LogisticModelTests`, `RiskApiTests`, et les règles ajoutées à
`ArchitectureTests`.

## 4. Fichiers principaux

| Partie | Fichiers |
|---|---|
| Serveur | modules `postmortem`, `remediation`, `impact`, `chatops`, `risk` ; `collection/controller/AgentActionController.java` ; boutons Telegram dans `reporting/sender/TelegramSender.java` |
| Base | migrations `V58__actions_correctives.sql`, `V59__telegram_chatops.sql`, `V60__risque_de_panne.sql` |
| Agent | `agent/trans_dic1_agent/actions.py` (+ configuration, client, boucle) ; exemples de configuration Linux et Windows |
| Interface | `PostMortemPanel`, `KpiPage`, `RemediationPanel`, `RemediationPolicyPanel`, `ImpactPanel`, `TelegramLinkCard`, `RiskPage`, `RiskCard` |
| Documentation | ADR 0050 à 0053, `CHANGELOG.md`, `docs/devops/agent.md`, `docs/devops/environment-variables.md`, rapport technique (chapitres 1, 7, 12), guide d'oral (section 15), scénario de démonstration |

## 5. Ce qu'il faut dire honnêtement

- Les six fonctions sont **testées automatiquement**, **jamais sur une vraie panne de l'EPT** (comme
  le reste de la plateforme).
- Le modèle de risque de panne n'a vu que des pannes **simulées** sur l'instance de démonstration :
  sa fiche affiche son mode, son volume et son AUC tels quels.
- Telegram dépend d'Internet ; la plateforme fonctionne sans, seuls les boutons manquent.
- Les actions correctives ne concernent que les machines équipées de l'agent (pas les équipements
  réseau).

## 6. Avant la soutenance

1. Relire et commiter ces modifications (et celles du seuil adaptatif, toujours non commitées).
2. Préparer la démonstration « face à Zabbix » (`docs/soutenance/presentation-et-demonstration.md`) :
   agent avec l'action `redemarrer-nginx`, bot Telegram créé et lié, modèle de risque réentraîné.
3. Toujours prioritaire : une mini-validation sur du vrai matériel et une mesure de charge.
