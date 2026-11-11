# ADR 0032 : flux réseau NetFlow

- Statut : accepté
- Date : 2026-11-11
- Lot 4 (section 2.1 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

La plateforme sait si un lien est saturé — l'occupation par port vient du SNMP depuis l'ADR 0027 —
mais pas **qui** le sature. « Le lien du bâtiment A est à 95 % » ne dit pas s'il s'agit d'une
sauvegarde, d'une mise à jour de système ou d'un poste qui part en vrille, et c'est pourtant la
seule question que pose l'exploitant à ce moment-là.

Tous les outils comparables le font : PRTG, LibreNMS avec NFSen, Checkmk avec ntopng, SolarWinds
NTA. C'était l'un des deux manques de la collecte réseau les plus coûteux relevés dans l'analyse.

## Décision

Un récepteur NetFlow dans un module `flow`, feuille de l'architecture : il reçoit, agrège et rend
à l'écran ; personne ne dépend de lui.

### Ce qui est lu

**NetFlow v5** (format figé) et **NetFlow v9** (à gabarits). Ce sont les deux versions qu'exportent
les équipements qu'on rencontre dans une école — Cisco IOS, MikroTik RouterOS, pfSense.

En v9, l'exportateur annonce séparément des gabarits qui décrivent ses paquets de données. Ils
sont retenus par exportateur, avec un plafond et un oubli au bout d'une heure, et **les champs
inconnus sont sautés d'après leur longueur** : c'est ce qui permet de lire les gabarits d'un
constructeur sans les connaître.

Ne sont **pas** pris en charge : IPFIX (version 10) et sFlow. La machinerie de gabarits est là et
IPFIX lui ressemble beaucoup, mais sans matériel pour l'éprouver, livrer un décodeur jamais
confronté à un vrai paquet serait se mentir.

### Ce qui est conservé

**Rien flux par flux.** Un routeur d'école exporte des milliers de flux par minute ; les écrire un
par un ferait enfler la base sans que personne ne lise jamais un flux isolé. Les flux sont
additionnés en mémoire par conversation — source, destination, protocole, port de destination —
puis écrits une fois par minute dans deux tables :

| Table | Contenu | Pourquoi |
| --- | --- | --- |
| `flow_bucket` | totaux par équipement et par minute | reste juste même quand le détail est plafonné |
| `flow_aggregate` | les `flow.top-conversations` (300) plus grosses conversations de la minute | ce qu'on regarde vraiment |

Cette séparation est le cœur de la décision : un parc qui déborde doit encore pouvoir dire combien
d'octets sont passés, quitte à ne plus savoir entre qui et qui. L'écran le signale quand c'est le
cas, au lieu de présenter un classement incomplet comme s'il était complet.

L'instant retenu est celui de la **réception**, arrondi à la minute, et non celui que porte le
flux : les horodatages NetFlow sont relatifs au temps de fonctionnement de l'exportateur, et les
convertir demanderait de connaître l'instant de son démarrage, pour un gain nul sur des agrégats
d'une minute.

Les adresses sont des données personnelles : conservation de **7 jours** (`flow.retention-days`),
purge par lots, comme les journaux de sécurité.

### Garde-fous

Le port est ouvert sur le réseau et l'UDP se falsifie. Donc, dans cet ordre :

- l'adresse d'origine doit être l'adresse de gestion d'un **équipement supervisé**, sinon le paquet
  est jeté sans être décodé (même règle que le récepteur syslog, ADR 0021) ;
- un plafond de paquets par seconde et par source ;
- un plafond de conversations distinctes en mémoire ;
- un plafond d'enregistrements lus par paquet, et toute longueur annoncée est vérifiée avant
  lecture : un en-tête menteur fait abandonner le paquet, pas planter le récepteur.

Le thread du socket ne fait que lire, décoder et additionner en mémoire. Il ne touche jamais la
base : c'est le vidage d'une minute sur l'autre qui écrit.

La réception est **coupée par défaut** (`flow.enabled`), comme les récepteurs de traps et de
syslog.

## Alternatives écartées

- **ntopng ou NFSen à côté** : plus complets, mais c'est un second produit à exploiter, et le
  raccrochement à l'inventaire et au périmètre de sites de la plateforme serait à refaire à la
  main.
- **Conserver les flux bruts** : la seule façon de répondre à des questions qu'on n'a pas encore
  posées, mais le volume et la durée de conservation deviennent le problème principal, et le
  règlement sur les données personnelles s'y oppose pour un gain d'exploitation faible.
- **Suivre le sens du trafic par interface** (entrée, sortie) : les index SNMP sont lus et
  pourraient se raccrocher aux interfaces découvertes par l'ADR 0027. Non fait : cela doublerait
  le modèle, et personne n'a demandé mieux que « qui parle à qui » pour l'instant.
- **Corriger l'échantillonnage** : un exportateur qui n'envoie qu'un paquet sur mille donne des
  volumes mille fois trop petits. Le facteur est annoncé différemment selon les constructeurs et
  souvent faux ; multiplier par un chiffre douteux donnerait une fausse précision. La plateforme
  ne corrige rien et le dit.

## Conséquences

- **Les volumes affichés sont ceux que l'équipement veut bien rapporter.** Échantillonnage,
  expiration des flux actifs, flux perdus en UDP : un écart avec les compteurs SNMP du même lien
  est normal, et les deux ne se recoupent pas au pourcentage près.
- Un exportateur non inventorié n'apparaît jamais, même s'il émet correctement. C'est la cause la
  plus fréquente de « je ne vois rien ».
- Le détail s'arrête aux 300 plus grosses conversations par minute et par équipement : une
  conversation modeste peut disparaître des classements tout en comptant dans les totaux.
- Une minute entière peut manquer si le serveur redémarre entre deux vidages : ce qui était compté
  en mémoire est perdu. Pour un agrégat de trafic, c'est acceptable ; pour de la facturation, ce ne
  le serait pas — et la facturation est hors périmètre.
- **Rien n'a été éprouvé contre un vrai exportateur.** Les paquets d'épreuve des tests sont
  construits à la main d'après les formats publiés. Aucun routeur de l'EPT n'a encore envoyé un
  seul flux à la plateforme.
