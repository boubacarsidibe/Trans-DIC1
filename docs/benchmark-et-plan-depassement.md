# Benchmark Trans-DIC1 face à Zabbix et aux autres outils, état et plan de dépassement

Mis à jour le 2026-11-05 (première version le 2026-11-02, avant les sondes sans agent).

Fiabilité des sources : les capacités de Trans-DIC1 viennent du code, des tests et des revues de la branche. Pour les autres outils, les points marqués (web) ont été revérifiés en ligne le 2026-11-05 ; les autres viennent de ma connaissance de ces produits et sont à recouper avant le rapport. Les recherches web ont surtout remonté des blogs de comparaison, pas les pages officielles des fonctions de déclencheurs Zabbix.

## 1. Ce qui est réalisé

### Backlog officiel
59 Issues fermées, 8 ouvertes. Aucune des 8 n'est du développement neuf : #32 validation sur l'infrastructure EPT et #33 seuils avec le CRI (dépendent du CRI), #34 rapport, #35 slides, #36 répétition, #5 relecture croisée des diagrammes, #63 et #76 (livrés en scripts et chart Helm, jamais testés sur un cluster).

### Sondes sans agent (écart G1 du premier benchmark)
Faites sur la branche `feature/sondes-sans-agent` (24 commits, 73 fichiers, environ 8 100 lignes ajoutées). **Elle n'est pas fusionnée dans `develop`.** Tests au dernier passage : 483 côté serveur, 203 côté interface, 0 échec.

- Sondes ICMP, TCP, HTTP/HTTPS (code attendu, texte attendu), certificat TLS (jours restants).
- Garde anti-SSRF : refus de la boucle locale, des métadonnées cloud, des formes IPv4 déguisées en IPv6 ; connexion à l'adresse résolue seulement ; pas de redirection ; 64 Ko lus au plus.
- Alertes propres à un service (un équipement peut avoir plusieurs sondes qui alertent séparément) ; deux règles livrées inactives (sonde injoignable, certificat bientôt expiré).
- Planificateur borné, API d'administration (droits, audit, périmètre par site), page « Sondes », ADR-0019, manuel, déploiement (sysctl du ping en conteneur).
- Chaque tâche a eu une revue indépendante, plusieurs corrections, une relecture finale puis une re-revue des corrections.

## 2. Ce qui ne va pas

### Défaut connu non corrigé
- **Deux sondes ping sur un même équipement font clignoter l'alerte de collecte** : chaque succès de l'une la résout, chaque échec de l'autre la rouvre avec une notification. Correctifs possibles : une seule sonde ping pilote l'état, ou la reprise exige qu'aucune autre sonde ping n'ait 3 échecs, ou on documente « une sonde ping par équipement ». À trancher.

### Non vérifié, donc à ne pas annoncer comme acquis
- Le test de bout en bout Playwright des sondes est écrit mais **jamais exécuté** (Docker éteint).
- L'image Docker du serveur n'a pas été construite avec `ping`, et le ping par un utilisateur non-root n'a pas été essayé dans un conteneur. Le chart Helm n'a pas été rendu (helm absent).
- Les codes de sortie réels de `ping` sous Linux ne sont testés qu'avec une fausse commande.
- Aucune mesure réelle sur l'infrastructure de l'EPT : seuils, plages, charge. C'est ce qui rend la comparaison avec Zabbix invérifiable aujourd'hui.

### Limites connues des sondes (documentées)
- Pas de sonde DNS, pas de scénario web à étapes, pas d'en-têtes ni d'authentification HTTP.
- L'écran d'historique mélange les séries de plusieurs sondes du même type sur un équipement (D15).
- Le test d'une règle sur l'historique ne rejoue pas les mesures de sonde (D13).
- La page Sondes ne se rafraîchit pas en direct (bouton « Actualiser »).
- Seule la panne d'une sonde ping qui pilote l'état entre dans le SLA ; les autres alertes de sonde n'y comptent pas.
- ICMP géré sous Linux et Windows seulement.

### Dettes et risques du projet
- Aucune intégration continue (R2) : les contrôles se font à la main.
- Agent Linux seulement, quatre mesures (R7). Pas de modèles d'équipement, pas de traps SNMP ni de syslog, pas de prévision, pas de règles composées, pas de découverte des interfaces et disques, pas de proxys de collecte, pas de MFA, pas de jetons d'API, pas de rapports planifiés, un seul serveur sans haute disponibilité.
- Ouvert aussi : D3 (Java 21), D5 (avertissements oxlint, un de plus avec la page Sondes), D6, D7, D8 (PDF d'architecture périmé).
- Le journal de bord de la branche montre des écarts de comptage de tests entre agents (446, 454, 463, 493) : seul le chiffre mesuré à la fin (483) fait foi.

## 3. Benchmark

Légende : ✅ présent, 🟡 partiel, ❌ absent. « Branche » = Trans-DIC1 avec les sondes (non fusionné).

| Domaine | Trans-DIC1 `develop` | Trans-DIC1 branche | Zabbix | LibreNMS | Prometheus + Grafana | Checkmk | PRTG | Uptime Kuma |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Collecte SNMP v2c/v3 | ✅ OID à la main | ✅ | ✅ + modèles | ✅ auto | 🟡 snmp_exporter | ✅ auto | ✅ | ❌ |
| Agent | 🟡 Linux, 4 mesures | 🟡 | ✅ multi-OS, plugins | ❌ | 🟡 exporters | ✅ | ✅ | ❌ |
| Windows | ❌ | ❌ | ✅ | 🟡 SNMP | ✅ windows_exporter | ✅ | ✅ | ❌ |
| Découverte réseau | 🟡 plage + SNMP | 🟡 | ✅ règles + LLD imbriquée (web, 7.4) | ✅ (web) | ❌ | ✅ | ✅ | ❌ |
| Modèles par constructeur | ❌ | ❌ | ✅ | ✅ | 🟡 | ✅ | ✅ | ❌ |
| Ping, port, HTTP | ❌ | ✅ | ✅ | ✅ | 🟡 blackbox | ✅ | ✅ | ✅ cœur du produit |
| Certificat TLS | ❌ | ✅ jours restants | ✅ modèles + agent 2 (web) | ✅ | 🟡 blackbox | ✅ | ✅ | ✅ |
| Scénarios web à étapes | ❌ | ❌ | ✅ (web : depuis 2.4) | 🟡 | ❌ | ✅ | ✅ | ❌ |
| Traps SNMP, syslog, journaux | ❌ | ❌ | ✅ | ✅ (web) | ❌ | ✅ | ✅ | ❌ |
| Alertes à seuil + hystérésis | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 |
| Test d'une règle sur l'historique | ✅ | ✅ (pas encore pour les sondes) | 🟡 | ❌ | 🟡 | ❌ | ❌ | ❌ |
| Cycle d'incident (prise en charge, plusieurs assignés, escalade, audit) | ✅ | ✅ | 🟡 acquittement | 🟡 | ❌ (OnCall OSS archivé le 2026-03-24 (web)) | 🟡 | 🟡 | ❌ |
| Corrélation par topologie | ✅ | ✅ | 🟡 | ✅ | ❌ | 🟡 | ✅ | ❌ |
| Détection d'anomalies | ✅ | ✅ | 🟡 tendances | ❌ | 🟡 | 🟡 | ❌ | ❌ |
| Prévision de saturation | ❌ | ❌ | ✅ | ❌ | ✅ | 🟡 | 🟡 | ❌ |
| Notifications | ✅ e-mail, Teams, Slack, webhook | ✅ | ✅ + SMS, scripts | ✅ (web) | ✅ Alertmanager | ✅ | ✅ | ✅ |
| SLA par site, maintenances exclues | ✅ | ✅ (partiel pour les sondes) | ✅ arbre de services | 🟡 | ❌ | 🟡 | ✅ | 🟡 |
| Page de statut | ❌ | ❌ | 🟡 | ❌ | ❌ (web : pas d'outil intégré) | ❌ (web : outil à part) | ❌ | ✅ |
| Astreinte, planning de garde | ❌ | ❌ | 🟡 scripts | ❌ | ❌ (OnCall OSS archivé) | 🟡 | 🟡 | ❌ |
| Topologie / cartes | ✅ + impact d'une panne | ✅ | ✅ cartes manuelles | ✅ auto | ❌ | 🟡 | ✅ | ❌ |
| Tableaux de bord, mur NOC | ✅ | ✅ | ✅ | 🟡 | ✅ très riche | ✅ | ✅ | 🟡 |
| Rôles, périmètre par site | ✅ | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | 🟡 |
| SSO OIDC / 2FA | ✅ / ❌ | ✅ / ❌ | ✅ / ✅ | ✅ / ✅ | selon outil | ✅ | ✅ | 🟡 |
| Audit immuable | ✅ | ✅ | ✅ | 🟡 | ❌ | ✅ | 🟡 | ❌ |
| API publique | 🟡 OpenAPI, sans jeton | 🟡 | ✅ | ✅ (web) | ✅ | ✅ | ✅ | 🟡 |
| Passage à l'échelle (proxys, HA) | ❌ | ❌ | ✅ proxys, HA | 🟡 polling distribué (web) | ✅ | ✅ | 🟡 | ❌ |
| Sauvegarde restaurée chaque jour | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Interface en français | ✅ | ✅ | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 |
| Coût | gratuit | gratuit | gratuit | gratuit | gratuit | gratuit (version communautaire) | payant par capteur | gratuit |

Lecture rapide :
- Les sondes ferment l'écart que Zabbix, LibreNMS, Checkmk, PRTG et Uptime Kuma avaient tous sur les contrôles de disponibilité. Elles ne le dépassent pas : Zabbix garde les scénarios web, la découverte de bas niveau et l'échelle.
- Deux espaces restent libres chez les concurrents libres : **l'astreinte** (Grafana OnCall en logiciel libre est archivé depuis le 2026-03-24 (web)) et **la page de statut intégrée** à une supervision d'infrastructure (Uptime Kuma la fait, mais ne supervise pas un parc).

## 4. Forces réelles

1. Cycle d'incident natif : prise en charge, plusieurs opérateurs, escalade, audit immuable.
2. Test d'une règle sur l'historique avant activation ; pas encore pour les sondes.
3. Impact d'une panne et alerte racine par la topologie.
4. SLA par site avec maintenances exclues.
5. Périmètre par site, connexion unique OIDC, rôles adaptés à l'école.
6. Exploitation sérieuse : sauvegarde restaurée chaque jour, déploiement avec retour arrière, observabilité, tests automatisés.
7. Une seule application cohérente à la place d'un assemblage de plusieurs outils.

## 5. Écarts restants face à Zabbix, par priorité

| # | Écart | Statut | Proposition |
| --- | --- | --- | --- |
| G1 | Contrôles sans agent | **Fait sur la branche**, reste DNS, scénarios web, en-têtes HTTP | Voir limites ci-dessus |
| G2 | Agent Windows et plus de mesures | Ouvert (`agent-windows.py` existe, non suivi par git) | Terminer, tester, packager ; ajouter réseau, processus, services, température |
| G3 | Modèles d'équipement | Ouvert | Bibliothèque JSON/YAML versionnée (MikroTik, Cisco, HP, Ubiquiti, onduleurs, imprimantes) |
| G4 | Traps SNMP, syslog | Ouvert | Récepteurs UDP vers des événements reliés aux règles |
| G5 | Règles composées, absence de donnée | Ouvert | Conditions ET/OU, fenêtres, dépendances |
| G6 | Prévision de saturation | Ouvert | Régression linéaire sur l'historique |
| G7 | Découverte des interfaces et disques | Ouvert | Après la découverte SNMP |
| G8 | Proxys de collecte par site | Ouvert | Réutiliser l'interface `Probe` et l'agent actif |
| G9 | MFA, jetons d'API, rapports planifiés, SMS/Telegram | Ouvert | Voir premier benchmark |
| G10 | Haute disponibilité | Ouvert | Tester à deux instances (relais LISTEN/NOTIFY prêt) |

## 6. Plan pour dépasser ces outils

1. **Avant tout : rendre le travail acquis crédible.** Corriger le défaut des deux sondes ping, fusionner la branche, exécuter le test de bout en bout, construire l'image Docker et vérifier le ping dans un conteneur, rétablir une intégration continue, puis valider avec le CRI. Sans mesures réelles de l'EPT, aucune comparaison avec Zabbix ne tient en soutenance.
2. **Combler l'essentiel (4 semaines)** : G2 agent Windows, G3 modèles, G4 traps et syslog, G6 prévision.
3. **Ce que les autres font mal ou pas du tout (4 semaines de plus)** :
   - assistant de diagnostic d'incident (équipements touchés, cause racine probable, alertes voisines, changements récents) ;
   - astreinte avec planning de garde et escalade vers la personne de garde ;
   - page de statut intégrée à la supervision, pour les services de l'école (ENT, Wi-Fi, messagerie) ;
   - réglage automatique des seuils avec simulation (le test sur historique existe déjà ; l'étendre aux sondes) ;
   - score de santé par site ;
   - runbooks liés aux règles avec action en un clic ;
   - rapport de capacité ;
   - PWA mobile avec notifications.
4. **Chiffres à produire pour la soutenance** : temps de mise en route d'un site contre Zabbix, nombre d'alertes évitées par la corrélation et le test de règle sur l'historique réel du CRI, délai de prise en charge avant/après, capacité testée (équipements, mesures par seconde, mémoire de l'agent).
5. **Réviser l'ADR-0002 (déjà mis à jour pour les sondes) pour les modèles, traps et agent Windows** : sinon la soutenance se contredit sur le périmètre exclu.

## Sources consultées le 2026-11-05

- [Zabbix 7.4.0alpha1](https://www.zabbix.com/rn/rn7.4.0alpha1), [Zabbix 7.4 (Linuxiac)](https://linuxiac.com/?p=175514) : découverte de bas niveau imbriquée, assistant d'hôte, nouveaux modèles.
- [Surveiller l'expiration SSL avec Zabbix](https://medium.com/zabbix-tutorials/monitor-ssl-certificate-expiry-with-zabbix-860279519238), [modèle certificat agent 2](https://www.zabbix.com/forum/zabbix-help/494951-template-website-certificate-by-zabbix-agent-2).
- [Documentation LibreNMS](https://docs.librenms.org/) : découverte automatique, transports d'alertes, module de facturation, syslog, traps SNMP.
- [Comparatifs auto-hébergés](https://toolradar.com/guides/best-self-hosted-monitoring), [Uptime Kuma contre PRTG](https://toolradar.com/compare/uptime-kuma-vs-prtg), [outils de disponibilité open source](https://codeboxr.com/best-open-source-uptime-monitoring-tools-2024-complete-guide-self-hosted-solutions/) : pages de statut, positionnement ; sources commerciales ou d'opinion.
- [Annonce Grafana OnCall](https://grafana.com/blog/announcing-grafana-oncall/?pg=blog), [Grafana et OneUptime](https://oneuptime.com/blog/post/2026-04-15-grafana-stack-vs-oneuptime-unified-observability/markdown) : archivage de Grafana OnCall OSS le 2026-03-24 et absence de gestion d'incident et de page de statut en auto-hébergé ; à confirmer sur le dépôt `grafana/oncall`.
