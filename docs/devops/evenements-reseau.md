# Recevoir les traps SNMP et les messages syslog

Décisions et modèle de sécurité : [ADR-0021](../adr/0021-evenements-reseau.md). Les deux récepteurs sont **désactivés par défaut**.

## 1. Activer les récepteurs

Variables du backend (détail dans [environment-variables.md](environment-variables.md)) :

```bash
EVENTS_SNMP_TRAP_ENABLED=true
EVENTS_SNMP_TRAP_PORT=1162                    # 162 demande des droits root
EVENTS_SNMP_TRAP_COMMUNITY=env:TRAP_COMMUNITY # référence, jamais la valeur
TRAP_COMMUNITY=une-communaute-longue          # le secret, dans l'environnement du serveur
# SNMPv3 (facultatif, un seul utilisateur, SHA + AES-128)
EVENTS_SNMP_TRAP_V3_USERNAME=trapuser
EVENTS_SNMP_TRAP_V3_AUTH_REFERENCE=env:TRAP_V3_AUTH
EVENTS_SNMP_TRAP_V3_PRIV_REFERENCE=env:TRAP_V3_PRIV

EVENTS_SYSLOG_ENABLED=true
EVENTS_SYSLOG_PORT=1514                       # 514 demande des droits root
```

Au démarrage, le journal du backend indique « Récepteur de traps SNMP à l'écoute sur 0.0.0.0:1162 » et « Récepteur syslog à l'écoute sur 0.0.0.0:1514 ». Sans `EVENTS_SNMP_TRAP_COMMUNITY`, un avertissement rappelle que les traps v1/v2c ne sont pas authentifiés.

**Chaque équipement émetteur doit être inventorié avec, comme adresse de gestion, l'adresse IP d'où partent ses datagrammes** (souvent l'adresse de l'interface de sortie, ou l'adresse source configurée ci-dessous). Sinon ses événements sont rejetés (`events.dropped{reason="unknown_source"}`) et rien n'est stocké. La supervision de l'équipement doit être active.

Ensuite, dans **Réglages > Règles d'événements**, activer les règles voulues (les trois règles livrées sont inactives).

## 2. Ouvrir les ports

N'ouvrir les ports qu'aux réseaux d'administration : l'adresse source UDP se falsifie, le pare-feu est la première protection.

Linux (nftables), réseau d'administration 10.10.0.0/16 :

```bash
nft add rule inet filter input ip saddr 10.10.0.0/16 udp dport { 1162, 1514 } accept
nft add rule inet filter input udp dport { 1162, 1514 } drop
```

Garder les ports standard côté équipements (162, 514) en redirigeant vers les ports non privilégiés :

```bash
nft add table ip nat
nft add chain ip nat prerouting '{ type nat hook prerouting priority -100; }'
nft add rule ip nat prerouting udp dport 162 redirect to :1162
nft add rule ip nat prerouting udp dport 514 redirect to :1514
```

Windows (poste de démonstration, PowerShell administrateur) :

```powershell
New-NetFirewallRule -DisplayName "Trans-DIC1 traps" -Direction Inbound -Protocol UDP -LocalPort 1162 -RemoteAddress 10.10.0.0/16 -Action Allow
New-NetFirewallRule -DisplayName "Trans-DIC1 syslog" -Direction Inbound -Protocol UDP -LocalPort 1514 -RemoteAddress 10.10.0.0/16 -Action Allow
```

Avec Compose ou Kubernetes, publier les ports en UDP (`1162:1162/udp`, `1514:1514/udp`) ; ces fichiers ne le font pas encore (à ajouter au moment de l'activation). Une traduction d'adresse entre l'équipement et le backend (NAT, équilibreur) masque l'adresse source : le récepteur verrait alors l'adresse du NAT et rejetterait tout.

## 3. Configurer les émetteurs

Exemples tirés de la documentation des constructeurs ; **aucun n'a été essayé sur un équipement réel** dans ce projet. Remplacer 10.10.0.5 par l'adresse du serveur Trans-DIC1.

### MikroTik RouterOS 7

```
/snmp community add name=une-communaute-longue addresses=10.10.0.5/32
/snmp set enabled=yes trap-community=une-communaute-longue trap-version=2 trap-target=10.10.0.5 trap-generators=interfaces trap-interfaces=all
/system logging action add name=transdic1 target=remote remote=10.10.0.5 remote-port=1514 bsd-syslog=yes
/system logging add topics=error action=transdic1
/system logging add topics=critical action=transdic1
```

RouterOS envoie les traps au port 162 : prévoir la redirection 162 → 1162 du § 2.

### Cisco IOS

```
snmp-server community une-communaute-longue RO
snmp-server enable traps snmp linkdown linkup authentication coldstart
snmp-server host 10.10.0.5 version 2c une-communaute-longue udp-port 1162
snmp-server trap-source GigabitEthernet0/0
logging host 10.10.0.5 transport udp port 1514
logging source-interface GigabitEthernet0/0
logging trap warnings
```

`trap-source` et `source-interface` fixent l'adresse source : c'est elle qu'il faut inventorier.

### Linux (rsyslog)

`/etc/rsyslog.d/90-transdic1.conf` :

```
*.warning action(type="omfwd" target="10.10.0.5" port="1514" protocol="udp"
                 template="RSYSLOG_SyslogProtocol23Format")
```

puis `systemctl restart rsyslog`. Le gabarit `RSYSLOG_SyslogProtocol23Format` envoie du RFC 5424 ; sans gabarit, rsyslog envoie du RFC 3164, également accepté.

## 4. Vérifier

- Envoyer un message de test depuis un équipement inventorié : `logger -n 10.10.0.5 -P 1514 -d -p user.err "test Trans-DIC1"` (util-linux).
- Page **Événements** : l'événement apparaît avec l'équipement, la source et la sévérité.
- Métriques Prometheus : `events_received_total`, `events_stored_total`, `events_dropped_total{reason=...}`. Un `unknown_source` qui monte signale une adresse source non inventoriée (souvent l'interface de sortie) ; `rate_limited`, un émetteur trop bavard (plus de `EVENTS_RATE_LIMIT_PER_SOURCE` messages par seconde) ; `bad_community`, une communauté différente.
- Une règle s'essaie sans rien enregistrer avec le bouton **Tester** de la page Règles d'événements.

## 5. Rétention

Les événements sont supprimés après `EVENTS_RETENTION_DAYS` jours (30 par défaut), chaque nuit à 4 h 15 UTC (`EVENTS_RETENTION_CRON`), sous le verrou des tâches planifiées.
