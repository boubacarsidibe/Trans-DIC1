# ADR 0029 : modèle appris pour les charges utiles web

- Statut : accepté
- Date : 2026-11-11
- Complète l'[ADR 0028](0028-supervision-securite.md) (S4 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

Les signatures de l'ADR 0028 reconnaissent ce qu'on leur a décrit. Une charge utile d'une forme
qu'aucune règle ne couvre passe sans rien laisser : c'est la limite assumée de la méthode, et c'est
celle que l'ADR 0028 renvoyait à « plus tard ».

L'ADR 0028 écartait un modèle appris pour deux raisons. La première tient toujours : aucun modèle
libre à licence claire n'a été trouvé, et charger des poids depuis un dépôt public est un risque en
soi. La seconde — le jeu public CSIC 2010 — a été revérifiée : ni la page du CSIC ni les copies
publiques n'énoncent de conditions d'usage. Un jeu de données dont on ne connaît pas la licence
n'entre pas dans un dépôt d'école.

## Décision

Un classifieur **entraîné par nous**, sur un corpus versionné dans le dépôt, qui complète les
signatures sans les remplacer.

### Ce que le modèle regarde

Une régression logistique sur les **suites de trois caractères** de la requête, décodée et mise en
minuscules comme pour les signatures. Les suites sont rangées par empreinte FNV-1a dans 2 048
cases : le modèle tient dans un fichier texte de 2 000 lignes, sans dictionnaire à transporter.

Seule la présence d'une suite compte, pas son nombre, et la somme des poids est divisée par la
racine du nombre de suites distinctes — sans quoi un catalogue à l'URL longue deviendrait suspect
du seul fait de sa longueur.

Pourquoi des caractères et non des mots-clés : `')--`, `${j`, `../` ou `%00` ne se rencontrent pas
dans une URL ordinaire, quel que soit le nom de l'attaque. C'est ce qui permet de reconnaître une
forme jamais apprise.

### Le corpus

Dans `backend/src/test/resources/threat/`, versionné :

- **requêtes d'attaque** (973 lignes) : charges utiles de listes publiques sous licence MIT
  (SecLists, PayloadsAllTheThings), posées dans des chemins et paramètres plausibles, étiquetées
  par famille — injection SQL, script injecté, traversée de répertoire, exécution de commande,
  injection de modèle, XXE.
- **requêtes légitimes** (889 lignes) : les chemins du contrat d'API de la plateforme, et des
  requêtes de site d'école écrites à la main — accents, encodage, apostrophes, parenthèses,
  `union des étudiants`, `select une formation`. Ce sont elles qui décident du taux de fausses
  alertes, donc elles ressemblent volontairement à ce qui pourrait tromper le modèle.

La reconnaissance (chemins d'outils connus, fichiers cachés) n'entre pas dans le corpus : c'est une
question d'appartenance à une liste, que les signatures traitent déjà, et des chemins courts et
anodins étiquetés « attaque » apprendraient au modèle à se méfier des requêtes ordinaires.

L'apprentissage est reproductible au bit près (graine fixe, classes rééquilibrées) et un test
vérifie que le fichier livré est bien ce que le corpus produit.

### Ce que cela donne

Mesuré par `UrlModelTests`, un exemple sur cinq mis de côté avant l'apprentissage :

| Mesure | Résultat |
| --- | --- |
| Attaques de familles connues repérées (exemples jamais vus) | 97,0 % |
| Requêtes légitimes prises pour des attaques | 0,0 % (sur 178) |
| Familles jamais apprises (injection de modèle, XXE) repérées par le modèle | 72,4 % |
| Les mêmes, repérées par les signatures | 22,9 % |

La dernière ligne est la raison d'être du modèle : sur des familles retirées de l'apprentissage, il
voit trois fois plus que les règles.

### Comment il est employé

Prudemment, parce qu'un modèle se trompe là où une signature sait pourquoi elle parle :

- il n'est **interrogé que si aucune signature n'a parlé** — une règle dit mieux de quoi il
  s'agit ;
- au-dessus de `threat.model.threshold` (0,9), la requête est conservée comme **forme
  inhabituelle**, une nature à part de l'attaque reconnue ;
- une seule ne réveille personne : il en faut `threat.suspect-requests` (5) de la même adresse dans
  la fenêtre pour ouvrir une alerte, et c'est un **avertissement**, jamais une alerte critique ;
- `threat.model.enabled: false` le coupe entièrement.

Un établissement peut réapprendre le modèle sur son propre trafic (`UrlModelTrainer` en ligne de
commande) et désigner son fichier par `threat.model.file`. C'est le seul vrai remède aux fausses
alertes, et c'est pour cela que l'outil d'apprentissage est livré avec l'application et non gardé
dans les tests.

## Alternatives écartées

- **CSIC 2010** : licence introuvable, donc pas versé au dépôt. Si l'école obtient un jour des
  conditions d'usage écrites, le même outil l'apprend sans changer une ligne de code.
- **Réseau de neurones à caractères (CNN, LSTM)** : les articles annoncent mieux sur CSIC 2010,
  mais il faudrait une bibliothèque d'apprentissage dans le backend, des poids de plusieurs méga-
  octets et un entraînement qu'on ne saurait pas refaire à l'école. La régression logistique
  s'explique, se réapprend en deux secondes et tient dans un fichier texte lisible.
- **Apprendre la normale du site lui-même** (une seule classe, sans exemples d'attaque) : séduisant
  parce qu'il n'y aurait rien à étiqueter, mais il faudrait conserver des requêtes ordinaires pour
  apprendre, ce que l'ADR 0028 refuse. Un comptage agrégé de suites de caractères, sans URL, le
  permettrait : c'est la suite naturelle, elle n'est pas faite.
- **Faire juger le modèle par l'agent** : même raison qu'à l'ADR 0028 — le jugement reste au
  serveur pour que la règle soit la même partout.

## Conséquences

- **Le modèle n'explique pas.** Il ne nomme pas de famille : l'écran dit « forme inhabituelle » et
  renvoie l'équipe au journal du serveur. C'est volontaire : annoncer une famille que le modèle n'a
  pas vraiment reconnue serait mentir.
- **Il ne voit que ce qui ressemble à ce qu'il a appris.** Essayé sur des familles absentes du
  corpus : l'octet nul et l'injection de commande inconnue sont vus (0,95 et 0,99), l'injection
  NoSQL ne l'est pas (0,23 et 0,51). Ajouter cette famille au corpus est une affaire de quelques
  lignes.
- **Les mesures portent sur notre corpus, pas sur le trafic de l'EPT.** Un corpus légitime écrit à
  la main est forcément plus régulier que la vraie vie ; le taux de fausses alertes en service sera
  plus élevé que 0 %. C'est précisément pourquoi le modèle seul n'ouvre qu'un avertissement, et
  après cinq requêtes.
- Le coût au passage est négligeable : quelques milliers d'additions par requête, et seulement pour
  les requêtes qu'aucune signature n'a reconnues.
- Toujours pas un pare-feu applicatif : on lit un journal, l'attaque est vue après coup.
