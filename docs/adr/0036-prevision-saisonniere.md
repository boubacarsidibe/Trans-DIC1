# ADR 0036 : prévision à cycle journalier

- Statut : accepté
- Date : 2026-11-12
- Lot 4, M13 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0020](0020-prevision-saturation.md)

## Contexte

La prévision de saturation de l'ADR 0020 est une droite des moindres carrés sur quatorze jours de
moyennes horaires. Elle convient à un disque, qui monte sans se soucier de l'heure.

Elle ne vaut rien pour la mémoire d'un serveur d'application ou l'occupation d'un lien : ceux-là
montent le matin quand les salles se remplissent, redescendent la nuit. Une droite tirée au milieu
de ce cycle passe entre les pics ; elle annonce la saturation bien trop tard, ou conclut à
l'absence de tendance. C'est pourquoi l'ADR 0020 limitait les indicateurs suivis au disque et à la
mémoire, et c'est la limite que M13 lève.

## Décision

Ajouter un second modèle — lissage exponentiel triple, **Holt-Winters additif**, de période 24
heures — et choisir entre les deux par une épreuve.

### Le modèle

Trois composantes lissées séparément : le **niveau** (où l'on en est), la **tendance** (de combien
cela monte par heure) et la **saison** (ce que vaut l'heure en cours par rapport à la moyenne de
la journée). La prévision d'une heure future additionne les trois.

Additif et non multiplicatif : un pourcentage d'occupation a une amplitude de cycle à peu près
constante, non proportionnelle au niveau. Coefficients fixes (0,3 / 0,05 / 0,2) et non optimisés :
les ajuster demanderait une recherche à chaque calcul, pour un gain illusoire sur quatorze jours
de données horaires.

Il faut **deux cycles complets** (48 moyennes horaires) pour distinguer une saison d'un accident.
En dessous, la droite reste seule.

La saturation est cherchée **heure par heure** jusqu'à soixante jours : avec un cycle, la limite
se touche au pic et non en moyenne. C'est là tout le gain — la prévision ne dit plus seulement
« saturé dans trois jours », mais « saturé dans trois jours, vers 14 h ».

### Le choix entre les deux

Il n'y a pas de bonne méthode dans l'absolu, et deviner d'après le nom de l'indicateur serait
fragile. Les deux modèles sont donc **mis à l'épreuve** : chacun est ajusté sur tout l'historique
sauf les dernières 24 heures, puis on compare ce qu'il avait prédit pour ces heures-là à ce qui
s'est réellement passé. L'erreur moyenne absolue départage.

Le modèle saisonnier doit faire **nettement** mieux (10 % d'erreur en moins) pour être retenu : il
a plus de paramètres, donc plus d'occasions de coller au passé sans rien prévoir. À égalité, la
droite l'emporte parce qu'elle est plus simple à lire et à expliquer.

L'écran et l'alerte disent la méthode retenue, et l'API rend les deux erreurs : on peut donc
vérifier le choix plutôt que le croire.

### La qualité annoncée est celle d'une prévision

Point découvert en écrivant les tests, et qui a changé la conception. Un r² calculé sur
l'ajustement lui-même donne au lissage une note excellente même sur une série erratique : il suit
le bruit pas à pas. Branché tel quel sur le seuil de confiance des alertes (`forecast.min-r2`),
il aurait ouvert des alertes sur des séries que la droite écartait à juste titre.

Le r² du modèle saisonnier est donc calculé **sur les dernières 24 heures, mises de côté avant
l'ajustement**. Il mesure une capacité à prévoir, pas une capacité à épouser le passé, et reste
comparable au r² de la droite. Faute d'assez d'historique pour cette épreuve, il est nul — et
aucune alerte ne s'ouvre.

## Alternatives écartées

- **Remplacer la droite** : non. Sur un disque qui monte régulièrement, elle est plus juste, plus
  lisible, et son r² se comprend d'un coup d'œil.
- **Choisir d'après le nom de l'indicateur** (`memory.usage` → saisonnier) : simple, mais faux dès
  qu'un serveur sort de l'ordinaire. L'épreuve sur les données tranche mieux que notre intuition.
- **Saisonnalité hebdomadaire** (période 168) : le samedi d'une école ne ressemble pas au mardi.
  Il faudrait quatorze semaines d'historique là où nous en gardons deux. À reprendre quand la
  rétention des agrégats horaires (treize mois) aura été exploitée.
- **Optimiser les coefficients de lissage** par recherche : gain faible, coût à chaque calcul, et
  un modèle qu'on ne saurait plus expliquer.
- **ARIMA saisonnier, Prophet** : il faudrait une bibliothèque, et le résultat ne s'expliquerait
  pas devant un jury ni devant le CRI.

## Conséquences

- Les indicateurs suivis par les alertes de prévision restent ceux de `forecast.keys` ; le modèle
  saisonnier les rend simplement justes pour ceux qui ont un cycle. Élargir cette liste au débit
  réseau devient possible, mais n'est pas fait ici.
- **Un cycle hebdomadaire passe pour du bruit.** Un pic du lundi matin n'est pas reconnu, et il
  dégrade un peu la qualité annoncée du modèle journalier.
- La fenêtre reste de quatorze jours : douze cycles, c'est peu pour une saison. Le modèle est donc
  sensible à une semaine atypique (vacances, examens).
- Le coût de calcul double, à peu près : deux ajustements pour l'épreuve, deux pour la prévision.
  Sur quelques centaines de points horaires, cela reste négligeable.
- **Rien n'a été éprouvé sur des cycles réels de l'EPT.** Les séries de test sont construites à la
  main, avec cycle, sans cycle, et irrégulières. Que la méthode choisie soit la bonne sur le
  trafic réel du campus reste à constater.
