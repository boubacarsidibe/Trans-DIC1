# Flux réseau (NetFlow)

Qui sature le lien, et pas seulement de combien il est saturé
([ADR 0032](../adr/0032-flux-reseau-netflow.md)). La réception est **coupée par défaut**.

## 1. Activer la réception

```sh
FLOW_ENABLED=true
FLOW_PORT=2055          # port d'écoute UDP, celui qu'emploient la plupart des exportateurs
FLOW_BIND_ADDRESS=0.0.0.0
```

Puis redémarrer le backend. Le journal annonce `Récepteur NetFlow à l'écoute sur 0.0.0.0:2055
(UDP)`.

Ouvrir le port côté pare-feu **uniquement depuis les équipements qui exportent**. Un port UDP
ouvert à tout le réseau est une invitation.

## 2. Déclarer les exportateurs

**L'équipement doit être inventorié dans la plateforme**, et l'adresse d'où il envoie ses flux
doit être son adresse de gestion. Sinon le paquet est jeté sans même être décodé : l'UDP se
falsifie, et sans cette règle n'importe qui remplirait la base de trafic inventé.

C'est la première chose à vérifier quand rien n'apparaît.

### MikroTik RouterOS

```
/ip traffic-flow set enabled=yes interfaces=all
/ip traffic-flow target add dst-address=10.0.0.5 port=2055 version=9
```

### Cisco IOS

```
ip flow-export version 9
ip flow-export destination 10.0.0.5 2055
interface GigabitEthernet0/1
 ip flow ingress
 ip flow egress
```

### pfSense / OPNsense

Installer `softflowd`, destination `10.0.0.5:2055`, version 9.

Les extraits ci-dessus viennent de la documentation des constructeurs : **aucun n'a été essayé sur
le matériel de l'école.** Vérifiez la syntaxe de votre version avant de l'appliquer en production.

## 3. Lire les résultats

**Supervision > Trafic** : volume de la période et débit moyen, courbe minute par minute, plus
grosses conversations, adresses les plus bavardes, services, et équipements exportateurs. Tout est
filtré par le périmètre de sites du compte.

Comptez une à deux minutes avant de voir quoi que ce soit : les flux sont additionnés en mémoire
et écrits une fois par minute.

## Réglages

| Propriété (variable) | Défaut | Rôle |
| --- | --- | --- |
| `flow.enabled` (`FLOW_ENABLED`) | `false` | réception NetFlow |
| `flow.port`, `flow.bind-address` | 2055, `0.0.0.0` | écoute UDP |
| `flow.packets-per-second` | 200 | plafond par exportateur ; au-delà, les paquets sont jetés |
| `flow.max-packet-bytes` | 8192 | datagramme plus long : tronqué à la lecture |
| `flow.template-ttl-minutes` | 60 | oubli des gabarits v9 d'un exportateur muet |
| `flow.max-conversations` | 20000 | conversations distinctes en mémoire ; au-delà, seuls les totaux comptent |
| `flow.top-conversations` | 300 | conversations écrites par minute et par équipement |
| `flow.flush-ms` | 60000 | intervalle d'écriture |
| `flow.retention-days` | 7 | conservation ; les adresses sont des données personnelles |
| `flow.overview-hours` | 3 | période par défaut de l'écran |

## Ce que cela ne dit pas

- **Les volumes sont ceux que l'équipement rapporte.** Échantillonnage, expiration des flux
  actifs, datagrammes perdus en UDP : un écart avec les compteurs SNMP du même lien est normal.
  Un exportateur qui échantillonne un paquet sur mille donnera des volumes mille fois trop
  petits, et la plateforme ne corrige pas ce facteur — il est annoncé différemment selon les
  constructeurs, et souvent faux.
- **Le détail s'arrête aux 300 plus grosses conversations par minute et par équipement.** Les
  totaux, eux, restent justes ; l'écran prévient quand le détail a été plafonné.
- **IPFIX (version 10) et sFlow ne sont pas lus.** Seules les versions 5 et 9 de NetFlow le sont.
- Une minute peut manquer si le serveur redémarre entre deux écritures : ce qui était compté en
  mémoire est perdu. Acceptable pour un agrégat de trafic ; à ne pas employer pour facturer.

## En cas de problème

| Symptôme | Cause la plus probable |
| --- | --- |
| L'écran reste vide | exportateur non inventorié, ou son adresse d'émission n'est pas son adresse de gestion |
| L'écran reste vide, l'équipement est bien inventorié | `flow.enabled` à `false`, port fermé au pare-feu, ou mauvaise destination côté équipement |
| Volumes très inférieurs aux compteurs SNMP | échantillonnage côté exportateur |
| « Le détail a été plafonné » en permanence | trafic très dispersé : augmenter `flow.max-conversations`, ou se contenter des totaux |
| Rien pendant une minute puis tout d'un coup | fonctionnement normal : l'écriture a lieu une fois par minute |
