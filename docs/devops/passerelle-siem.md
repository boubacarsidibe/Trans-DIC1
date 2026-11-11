# Passerelle vers un SIEM (Wazuh et autres)

Trans-DIC1 n'est pas un SIEM et ne cherche pas à le devenir ([ADR 0028](../adr/0028-supervision-securite.md)).
Si l'école installe Wazuh — ou Graylog, Splunk, Elastic, un simple `rsyslog` central —, les deux
produits se parlent en syslog, dans les deux sens ([ADR 0031](../adr/0031-passerelle-siem.md)).

Rien de tout cela n'est actif par défaut.

## 1. Envoyer les alertes de la plateforme au collecteur

### Déclarer l'adresse du collecteur

L'adresse vit dans l'environnement du serveur, comme les autres destinations de canal :

```sh
SIEM_SYSLOG=udp://siem.ept.sn:514
```

`tcp://siem.ept.sn:514` si le collecteur écoute en TCP. Sans port, 514 est pris. Toute autre forme
est refusée avec un message qui dit quoi écrire.

**UDP ou TCP ?** En UDP, rien ne garantit l'arrivée : un datagramme perdu l'est sans que personne
ne le sache. C'est le fonctionnement habituel du syslog et cela suffit pour un flux d'information.
En TCP, un collecteur injoignable fait échouer l'envoi, et la notification est réessayée comme
toutes les autres. Pour des alertes de sécurité, TCP est le choix raisonnable.

Dans les deux cas le trafic part **en clair** : à réserver au réseau d'administration.

### Choisir le canal

**Réglages > Mes notifications** : canal « Collecteur syslog (SIEM) », référence `env:SIEM_SYSLOG`,
sévérité minimale au choix. L'écran **Administration > Canaux** envoie un message d'essai.

Le canal suit les mêmes règles que les autres : file d'attente, réessais, journal des envois,
respect de la sévérité minimale et du périmètre de sites du compte.

### Ce que reçoit le collecteur

```
<162>1 2026-11-11T19:45:03.250Z supervision.ept.sn trans-dic1 - ALERT_OPENED - [CRITIQUE] Attaque web depuis 203.0.113.77 — …
```

| Champ | Valeur |
| --- | --- |
| Priorité | origine `notification.syslog.facility` (local4, 20, par défaut) × 8 + gravité |
| Gravité | critique → 2 (`critical`), avertissement → 4 (`warning`), information → 6 (`info`) |
| Horodatage | UTC, à la milliseconde |
| Hôte | `notification.syslog.hostname`, sinon le nom de la machine |
| Application | `trans-dic1` |
| Identifiant | nature de l'événement (`ALERT_OPENED`, `ALERT_RESOLVED`, `ALERT_ONCALL`…) |

Une alerte critique n'est volontairement pas une `emergency` syslog : cette gravité annonce un
système inutilisable, ce qu'une alerte de supervision ne dit pas. Accordez
`notification.syslog.facility` avec ce que votre collecteur attend, sinon les messages arrivent
mais se rangent ailleurs que prévu.

Exemple de tri côté `rsyslog` :

```
:app-name, isequal, "trans-dic1"   /var/log/trans-dic1.log
& stop
```

## 2. Recevoir les alertes du SIEM

Rien à installer de plus : le récepteur syslog de la plateforme
([ADR 0021](../adr/0021-evenements-reseau.md), [evenements-reseau.md](evenements-reseau.md)) lit
déjà RFC 3164 et RFC 5424.

### Côté Wazuh

Dans `/var/ossec/etc/ossec.conf` du gestionnaire :

```xml
<ossec_config>
  <syslog_output>
    <server>supervision.ept.sn</server>
    <port>1514</port>
    <level>7</level>
    <format>default</format>
  </syslog_output>
</ossec_config>
```

puis `systemctl restart wazuh-manager`. Le port est celui du récepteur de la plateforme (1514 par
défaut, voir `events.syslog.port`), et `level` évite de déverser toutes les alertes de niveau
faible.

### Côté Trans-DIC1

1. **Inventorier le gestionnaire Wazuh** comme équipement, avec pour adresse de gestion l'adresse
   d'où il émet. Le récepteur rejette, sans rien conserver, tout message venu d'une adresse qui
   n'est celle d'aucun équipement supervisé : c'est voulu, et c'est la cause la plus fréquente de
   « je ne reçois rien ».
2. **Activer la réception** si ce n'est pas déjà fait (`events.syslog.enabled`).
3. **Activer la règle livrée** « Alerte d'un SIEM (Wazuh) » dans **Supervision > Règles
   d'événements**. Elle est livrée inactive et volontairement large :
   `(?i)\b(wazuh|ossec)\b`, avertissement, déduplication de cinq minutes.
4. **L'affiner** une fois que les messages arrivent vraiment. Les messages Wazuh portent leur
   niveau ; pour ne retenir que les niveaux 10 et au-delà, par exemple :
   `(?i)(wazuh|ossec).*Alert Level: (1[0-5])\b`. Regardez d'abord **Supervision > Événements**
   pour voir la forme exacte de ce que votre installation envoie, puis écrivez l'expression
   là-dessus plutôt que sur l'exemple ci-dessus.

Les alertes ainsi ouvertes sont des alertes ordinaires : elles notifient, s'escaladent à
l'astreinte et se résolvent comme les autres.

## Qui fait quoi

| Besoin | Qui |
| --- | --- |
| Disponibilité, métriques, cartographie, capacité | Trans-DIC1 |
| Attaques web, force brute, balayages dans les journaux | Trans-DIC1 (ADR 0028 à 0030) |
| Intégrité des fichiers, recherche de vulnérabilités, MITRE ATT&CK, conformité | le SIEM |
| Conservation longue des journaux bruts | le SIEM (la plateforme purge à 30 jours) |

## Ce qui n'a pas été vérifié

Le format du message est vérifié caractère par caractère par les tests, et l'envoi de bout en bout
contre un collecteur factice. **Aucune installation Wazuh réelle n'a reçu nos alertes ni émis les
siennes vers la plateforme** : les extraits de configuration ci-dessus viennent de la
documentation de Wazuh, pas d'un essai sur le parc de l'école.
