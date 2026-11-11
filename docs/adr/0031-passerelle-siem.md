# ADR 0031 : passerelle vers un SIEM

- Statut : accepté
- Date : 2026-11-11
- Complète l'[ADR 0028](0028-supervision-securite.md) (S8 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

L'ADR 0028 a écarté l'installation d'un vrai SIEM à côté de la plateforme : pour une équipe de deux
personnes, c'est un second produit à exploiter. Elle a aussi dit ce que Trans-DIC1 ne fera pas —
intégrité des fichiers, recherche de vulnérabilités, repères MITRE ATT&CK, conservation longue des
journaux — et renvoyé ces besoins à Wazuh.

Reste à ce que les deux puissent se parler le jour où l'école en installe un, sans que personne
n'ait à choisir son camp ni à regarder deux écrans sans lien.

## Décision

Une passerelle dans les deux sens, en syslog, le seul langage que tous les SIEM comprennent.

### Sortant : les alertes de la plateforme partent au collecteur

Un canal de notification `SYSLOG` s'ajoute aux canaux existants (courriel, webhook, Teams, Slack,
Telegram). Il suit exactement les mêmes règles : il se choisit dans **Réglages > Mes
notifications**, il respecte la sévérité minimale, il passe par la file d'attente et ses
réessais, et il s'essaie depuis l'écran **Canaux**.

L'adresse du collecteur est une **référence de secret** (`env:SIEM_SYSLOG`) dont la valeur s'écrit
`udp://hote:514` ou `tcp://hote:514`. Ce n'est pas un secret à proprement parler ; c'est la façon
dont la plateforme désigne déjà toute destination de canal non courriel, et cela garde l'adresse
d'un équipement d'infrastructure hors de la base et hors des écrans.

Le message suit **RFC 5424** :

```
<162>1 2026-11-11T19:45:03.250Z supervision.ept.sn trans-dic1 - ALERT_OPENED - [CRITIQUE] … — …
```

La priorité combine l'origine (`notification.syslog.facility`, local4 par défaut) et la gravité
traduite depuis la sévérité de l'alerte : critique → `critical` (2), avertissement → `warning` (4),
information → `info` (6). Une alerte critique n'est délibérément pas une `emergency` syslog, qui
annonce un système inutilisable — ce qu'une alerte de supervision ne dit pas.

Le message tient sur une seule ligne et dans 2 048 caractères : un retour à la ligne couperait le
datagramme en deux, et un message trop long serait tronqué par le collecteur, pas par nous.

Le port d'envoi a gagné un **contexte facultatif** (sévérité, nature de l'événement). Les autres
canaux l'ignorent : un courriel se lit, il n'a pas besoin qu'on lui dise la gravité. Un collecteur,
lui, trie dessus.

### Entrant : les alertes du SIEM arrivent par le récepteur existant

Rien de neuf à écrire : le récepteur syslog de l'[ADR 0021](0021-evenements-reseau.md) lit déjà
RFC 3164 et RFC 5424, et les règles d'événements transforment un message en alerte. Wazuh envoie
ses alertes par son `syslog_output`.

Une règle est livrée **inactive**, comme les autres : « Alerte d'un SIEM (Wazuh) », source Syslog,
expression `(?i)\b(wazuh|ossec)\b`, avertissement, déduplication de cinq minutes. L'exploitation
l'active et l'affine — par exemple sur le niveau d'alerte Wazuh — quand le SIEM émet vraiment.

**Le gestionnaire Wazuh doit être inventorié** dans la plateforme, et l'adresse d'où il émet doit
être son adresse de gestion : le récepteur rejette tout message venu d'une adresse qui n'est celle
d'aucun équipement supervisé, et c'est voulu (ADR 0021).

## Alternatives écartées

- **Un connecteur Wazuh dédié** (son API REST, son format JSON) : plus riche, mais lié à un
  produit et à une version. Le syslog marche avec Wazuh, Graylog, Splunk, Elastic et le
  `rsyslog` d'un serveur, sans une ligne de plus.
- **Envoyer aussi les lignes de journal brutes** au SIEM : ce serait transformer la plateforme en
  relais de collecte, avec son volume et sa latence, alors que l'agent du SIEM fait cela mieux.
  Ce sont les **alertes** qui partent, c'est-à-dire ce que la plateforme a jugé.
- **TLS (RFC 5425)** : le syslog part en clair, UDP ou TCP. Sur le réseau d'administration de
  l'école, entre deux machines voisines, c'est le fonctionnement habituel ; pour franchir un
  réseau moins sûr, un tunnel reste la réponse. À reprendre si le besoin se présente.
- **Garantir la remise en UDP** : le protocole ne le permet pas. Qui veut la garantie choisit
  `tcp://`, dont l'échec de connexion remonte et déclenche les réessais de la file.

## Conséquences

- **En UDP, une alerte peut se perdre sans que personne ne le sache.** C'est le fonctionnement
  habituel du syslog, et la plateforme ne prétend pas le corriger : elle le dit, à l'écran comme
  ici, et propose TCP à qui veut mieux.
- Le syslog part **en clair** : adresses, noms d'équipements et messages d'alerte sont lisibles sur
  le réseau. À n'employer que sur le réseau d'administration.
- La valeur de `notification.syslog.facility` doit correspondre à ce que le collecteur attend,
  sinon les messages arrivent mais se rangent ailleurs que prévu.
- Le sens entrant ne marche que si le gestionnaire du SIEM est inventorié, et la règle livrée est
  volontairement large : activée telle quelle sur un SIEM bavard, elle ouvrirait beaucoup
  d'alertes. D'où l'état inactif et la consigne de l'affiner.
- **Rien n'a été essayé contre un vrai Wazuh.** Le format est vérifié caractère par caractère par
  les tests, et l'envoi de bout en bout contre un collecteur factice sur la machine de
  développement ; aucune installation Wazuh n'a reçu nos alertes, ni émis les siennes vers nous.
