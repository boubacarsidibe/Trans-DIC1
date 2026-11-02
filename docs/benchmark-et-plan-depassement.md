# Benchmark Trans-DIC1 face à Zabbix et aux autres outils, et plan pour les dépasser

État au 2026-11-02. Les capacités de Trans-DIC1 viennent du code et du CHANGELOG du dépôt. Celles des autres outils viennent de la documentation publique connue (Zabbix 7.x, LibreNMS, Prometheus/Grafana, Nagios/Icinga, PRTG) et n'ont pas été revérifiées en ligne le jour de la rédaction : à recouper avant le rapport.

## 1. État des tâches

59 Issues GitHub fermées, 8 ouvertes.

| Issue | Statut réel | Reste à faire |
| --- | --- | --- |
| #76 OPS-03 Helm | Chart livré (CHANGELOG) ; critère « validé sur un cluster local » non prouvé | Tester sur kind/minikube, puis fermer |
| #63 OPS-01 livraison staging | Scripts livrés ; pas de CI, pas de registre (risque R2, dette D6) | Rétablir une CI, publier les images |
| #5 S0-ARCH diagrammes canoniques | Cases non cochées, relecture à deux jamais tracée | Relecture croisée, cocher, fermer |
| #32 S4-TEST validation EPT | Bloquée : dépend du CRI (plan prêt dans `docs/validation/`) | Serveur, plages réseau, autorisation |
| #33 S4-SEUILS seuils avec le CRI | Proposition rédigée ; atelier à tenir | Atelier CRI |
| #34 S5-REPORT rapport final | Brouillon seulement | Rédiger, régénérer le PDF d'architecture (D8) |
| #35 S5-SLIDES présentation | Deck et script d'orateur présents mais non suivis par git | Relire, commiter |
| #36 S5-REHEARSAL répétition | Non fait | Répéter |

Conclusion : tout le développement fonctionnel du backlog est fait. Il ne reste que des dépendances externes (CRI), de la rédaction et deux fermetures administratives. Risques ouverts : R1 à R8, dont l'absence de CI (R2) et l'agent Linux seulement (R7). Dette ouverte : D3, D5, D6, D7, D8, D11.

## 2. Benchmark

Légende : ✅ présent, 🟡 partiel, ❌ absent.

| Domaine | Trans-DIC1 | Zabbix | LibreNMS | Prometheus + Grafana | PRTG |
| --- | --- | --- | --- | --- | --- |
| Collecte SNMP v2c/v3 | ✅ OID configurés à la main | ✅ + modèles | ✅ auto | 🟡 via snmp_exporter | ✅ |
| Agent | 🟡 Linux, 4 mesures | ✅ Linux/Windows/macOS, plugins | ❌ (SNMP) | 🟡 node_exporter | ✅ |
| Windows | ❌ | ✅ | 🟡 SNMP | ✅ windows_exporter | ✅ |
| Découverte réseau | 🟡 plage + SNMP, désactivée par défaut | ✅ règles + découverte de bas niveau | ✅ très bonne | ❌ | ✅ |
| Modèles par constructeur | ❌ | ✅ des centaines | ✅ | 🟡 | ✅ |
| Ping ICMP, ports, HTTP, certificats TLS | ❌ | ✅ | ✅ | 🟡 blackbox | ✅ |
| Traps SNMP, syslog, journaux | ❌ | ✅ | ✅ | ❌ | ✅ |
| Alertes à seuil + hystérésis | ✅ | ✅ expressions riches | ✅ | ✅ | ✅ |
| Test d'une règle sur l'historique | ✅ | 🟡 | ❌ | 🟡 | ❌ |
| Cycle d'incident (prise en charge, plusieurs assignés, escalade, audit) | ✅ natif | 🟡 acquittement, escalade | 🟡 | ❌ | 🟡 |
| Corrélation par topologie (cause racine) | ✅ | 🟡 dépendances de triggers | ✅ | ❌ | ✅ |
| Détection d'anomalies | ✅ profil jour/heure | 🟡 fonctions de tendance | ❌ | 🟡 | ❌ |
| Prévision (saturation disque) | ❌ | ✅ `forecast`, `timeleft` | ❌ | ✅ `predict_linear` | 🟡 |
| Notifications | ✅ e-mail, Teams, Slack, webhook signé | ✅ + SMS, Telegram, scripts | ✅ | ✅ Alertmanager | ✅ |
| SMS / WhatsApp / Telegram | ❌ | ✅ | ✅ | ✅ | ✅ |
| SLA et disponibilité par site | ✅ maintenances exclues, PDF | ✅ arbre de services | 🟡 | ❌ | ✅ |
| Rapports PDF/CSV planifiés | 🟡 à la demande | ✅ planifiés | 🟡 | ❌ | ✅ |
| Topologie | ✅ + impact d'une panne | ✅ cartes manuelles | ✅ automatique | ❌ | ✅ |
| Tableaux de bord personnels, mur NOC | ✅ | ✅ | 🟡 | ✅ très riche | ✅ |
| Rôles, périmètre par site | ✅ | ✅ groupes d'hôtes | ✅ | 🟡 | ✅ |
| SSO OIDC | ✅ | ✅ SAML, LDAP | ✅ | ✅ | ✅ |
| 2FA / MFA | ❌ | ✅ | ✅ | selon outil | ✅ |
| Audit immuable | ✅ | ✅ | 🟡 | ❌ | 🟡 |
| API publique documentée | 🟡 OpenAPI, jetons internes seulement | ✅ JSON-RPC | ✅ | ✅ | ✅ |
| Passage à l'échelle (proxys, HA) | ❌ un serveur, relais LISTEN/NOTIFY | ✅ proxys, HA natif | 🟡 | ✅ | 🟡 |
| Auto-supervision | ✅ Prometheus, traces, logs JSON | ✅ | 🟡 | ✅ | 🟡 |
| Interface en français | ✅ | 🟡 traductions partielles | 🟡 | 🟡 | 🟡 |
| Sauvegarde testée chaque jour | ✅ restauration quotidienne | ❌ à la charge de l'admin | ❌ | ❌ | ❌ |
| Coût | gratuit | gratuit | gratuit | gratuit | payant par capteur |

### Forces réelles de Trans-DIC1

1. **Le cycle d'incident est natif** : prise en charge, plusieurs opérateurs, escalade, journal d'audit immuable. Zabbix et Grafana se limitent à l'acquittement.
2. **Test d'une règle sur l'historique avant activation** : peu d'outils le proposent, ce qui réduit le bruit d'alertes.
3. **Impact d'une panne et alerte racine** par la topologie, avec ADR écrit.
4. **SLA par site** avec maintenances exclues et comparaison de périodes, pensé pour le CRI.
5. **Périmètre par site, OIDC avec le fournisseur de l'EPT, rôles adaptés à l'école.**
6. **Exploitation sérieuse** : sauvegarde restaurée chaque jour, déploiement avec retour arrière, observabilité, Helm, tests automatisés (ArchUnit, Playwright).
7. **Une seule application cohérente** à la place d'un assemblage Prometheus + Alertmanager + Grafana + base d'inventaire.

### Ce qui manque face à Zabbix (par priorité)

| # | Écart | Gravité pour l'EPT | Proposition |
| --- | --- | --- | --- |
| G1 | Pas de contrôles de disponibilité sans agent (ICMP, TCP, HTTP/HTTPS, certificat TLS, DNS) | Haute : supervise les équipements sans agent ni SNMP et les services web de l'EPT | Sondes actives dans le backend, `ServiceType` existe déjà en base. Alerte d'expiration de certificat à 30/14/7 jours |
| G2 | Agent Linux seulement, 4 mesures | Haute (R7) | Agent Windows (`agent-windows.py` est déjà non suivi dans le dépôt), mesures réseau, processus, services, température, uptime ; contrôles personnalisés par script |
| G3 | Pas de modèles d'équipement | Haute : chaque OID est saisi à la main | Bibliothèque de modèles (JSON/YAML versionné) : MikroTik, Cisco, HP, Ubiquiti, serveurs Linux/Windows, onduleurs APC, imprimantes. Application d'un modèle à un équipement ou à un groupe |
| G4 | Pas de traps SNMP ni syslog | Moyenne : événements « lien tombé » en temps réel | Récepteur de traps et de syslog UDP transformés en événements, reliés aux règles d'alerte |
| G5 | Règles limitées à un seuil sur une mesure | Moyenne | Conditions composées (ET/OU), fenêtres (« moyenne sur 15 min »), absence de donnée, dépendances entre règles |
| G6 | Pas de prévision | Moyenne | « Disque plein dans N jours » par régression linéaire sur l'historique |
| G7 | Pas de découverte de bas niveau (interfaces, partitions) | Moyenne | Après découverte SNMP, créer les capteurs par interface/disque |
| G8 | Pas de proxys de collecte | Moyenne si plusieurs sites distants | Collecteur léger par site qui relaie les mesures (réutiliser l'agent actif) |
| G9 | Pas de MFA | Moyenne (sécurité) | TOTP pour la connexion locale ; MFA délégué à l'OIDC pour le reste |
| G10 | API sans jetons d'accès pour les scripts | Basse | Jetons personnels à portée limitée |
| G11 | Rapports non planifiés | Basse | Envoi hebdomadaire/mensuel du SLA par e-mail |
| G12 | Pas de SMS/Telegram | Basse | Canaux supplémentaires sur la file d'envoi existante (ARCH-07) |
| G13 | Pas de HA, un seul serveur | Basse tant que R1 n'est pas levé | Garder Helm, tester à deux instances (le relais LISTEN/NOTIFY est prêt) |

Les points G1, G2, G3 et G4 sont ceux qu'un jury comparera en premier à Zabbix. G1 est le meilleur rapport valeur/coût.

## 3. Plan pour dépasser ces outils

Zabbix gagne sur la largeur. Trans-DIC1 ne peut pas le rattraper en largeur, mais peut le dépasser sur l'exploitation, l'aide à la décision et l'usage par une petite équipe. Idées classées par effet de démonstration.

### Vague A — Combler l'essentiel (semaines 1 à 4)

G1 sondes sans agent, G3 modèles d'équipement, G2 agent Windows, G6 prévision. Après cela, tableau comparatif en soutenance : plus aucune ligne « ❌ » sur les fonctions de base.

### Vague B — Ce que Zabbix ne fait pas bien (semaines 4 à 8)

| Idée | Pourquoi ça dépasse |
| --- | --- |
| **Assistant de diagnostic d'incident** : à l'ouverture d'une alerte, afficher les équipements touchés, la cause racine probable, les alertes voisines des 30 dernières minutes, les changements récents (audit) et les incidents passés similaires | Zabbix liste les problèmes, ne les explique pas. Les données existent déjà (topologie, corrélation, audit) |
| **Résumé en langage naturel et assistant LLM** (optionnel, local ou API) : « que s'est-il passé cette nuit au site X ? », résumé d'incident pour le rapport | Aucun des outils comparés ne le fait nativement |
| **Réglage automatique des seuils** : proposer un seuil d'après l'historique et le percentile 95, avec simulation du nombre d'alertes évitées (le test de règle existe déjà) | Réduit la fatigue d'alerte, thème central du CRI |
| **Score de santé par site et par équipement** (disponibilité, anomalies, saturation prévue, alertes ouvertes) | Lecture en un coup d'œil pour la direction |
| **Mode « page de statut » publique ou interne** pour les services de l'école (ENT, Wi-Fi, messagerie) | Zabbix n'a pas de page de statut ; utile aux étudiants |
| **Runbooks liés aux règles** : procédure à suivre affichée dans l'alerte, avec action en un clic (redémarrer un service via l'agent, après confirmation) | Passe de la supervision à la remédiation |
| **Astreinte** : planning de garde, escalade vers la personne de garde, relance par canal | Zabbix demande des scripts ou un outil tiers |
| **Rapport de capacité** (croissance du disque, du trafic, prévision d'achat) | Aide à la décision budgétaire |
| **Application mobile / PWA** avec notifications push et prise en charge d'une alerte au téléphone | Les équipes réagissent hors du bureau |

### Vague C — Confiance et industrialisation (en parallèle)

Rétablir la CI (R2), publier les images (D6), valider sur un cluster local (#76), puis en staging EPT (#32, #33). Sans mesures réelles de l'EPT, aucune comparaison ne sera crédible : un test de charge (nombre d'équipements et de mesures par seconde supportés) donne un chiffre à opposer à celui de Zabbix.

### Chiffres à produire pour la soutenance

- Temps de mise en route d'un site : Trans-DIC1 contre installation Zabbix + modèles (mesurer sur le même poste).
- Nombre d'alertes évitées grâce à la corrélation et au test de règle sur l'historique réel du CRI.
- Délai moyen de prise en charge avant/après (si le CRI fournit un historique).
- Capacité testée : équipements, mesures par seconde, mémoire de l'agent (limite actuelle 64 Mo).

## 4. Réponse à « pourquoi ne pas utiliser Zabbix ? »

L'ADR-0002 écarte la découverte automatique, les modèles et la supervision de milliers d'équipements. Le plan ci-dessus en fait reculer une partie (G1 à G4) : ce changement doit être inscrit dans une révision de l'ADR-0002, sinon la soutenance se contredit. Un argument honnête reste : Zabbix est supérieur en largeur et à grande échelle ; Trans-DIC1 vise le cycle d'incident, l'explication de la panne, le SLA par site et la simplicité d'exploitation pour une équipe réduite. Et si le CRI exploite déjà Zabbix, Trans-DIC1 doit pouvoir importer ses mesures (point déjà noté dans l'ADR) : l'intégration Zabbix → Trans-DIC1 par l'API est une cinquième idée de la vague A, qui transforme le concurrent en source de données.
