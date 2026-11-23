# ADR 0043 : bande de tolérance sur les taux réseau

- Statut : accepté
- Date : 2026-11-23
- Lot 1, M6 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0015](0015-anomaly-detection.md)

## Contexte

Les interfaces sont découvertes par SNMP et leurs taux collectés port par port : débit entrant et
sortant, erreurs, rejets, occupation. La gigue et la perte viennent de la sonde ICMP. Tout cela est
mesuré, et seules des règles de seuil les surveillent.

La détection d'anomalies de l'ADR 0015 pourrait s'en charger : elle compare une mesure à l'habitude
du même créneau — même heure, même type de jour — sur vingt-huit jours. Mais il lui faut **six
moyennes horaires dans ce créneau** pour juger, soit plusieurs semaines d'historique **par
indicateur**. Pour un port fraîchement découvert, cela veut dire des semaines sans surveillance, et
un commutateur a quarante-huit ports.

## Décision

Ajouter une **bande de tolérance par lissage exponentiel**, calculée par indicateur et par port.

### Le calcul

Deux moyennes mobiles exponentielles : le **niveau**, et l'**écart absolu** à ce niveau. La bande
vaut le niveau plus ou moins six écarts. Chaque mesure met les deux à jour avec un poids de 0,05,
soit une mémoire d'une quarantaine de mesures.

L'état tient en **quatre nombres** par indicateur — niveau, écart, nombre de mesures, série en
cours —, ce qui permet d'en garder pour des milliers de ports là où une normale par créneau
demanderait des semaines d'historique chacune. Une normale est utilisable après **une vingtaine de
mesures**, soit quelques minutes.

### Le piège, mesuré puis corrigé

Une bande exponentielle naïve **s'auto-détruit** : la valeur aberrante qu'on veut signaler entre
dans la moyenne, gonfle l'écart, et la bande s'élargit assez pour accueillir la suivante. Au bout de
quelques pointes, elle accepte tout — en silence, ce qui est le pire.

Les mesures sont donc ramenées avant d'entrer dans le lissage (winsorisation). **Mais pas au bord de
la bande**, et c'est la correction d'une première version mesurée : ce bord vaut déjà six écarts, de
sorte que chaque pointe apprenait un écart sept fois plus grand que l'actuel. Dix pointes de suite
élargissaient la bande d'un facteur huit. La borne d'apprentissage est donc fixée à **deux écarts**,
bien plus serrée que la demi-largeur d'alerte : la bande alerte à six écarts, elle n'apprend jamais
plus de deux.

| Dix pointes de suite | Élargissement de la bande | La pointe est-elle encore signalée ? |
| --- | --- | --- |
| Sans winsorisation | × 20 et plus | **Non** |
| Ramenée au bord (6 écarts) | × 8 | oui, de justesse |
| Ramenée à 2 écarts | × 2 | oui |

### Ce qu'elle ne remplace pas

La bande **s'adapte** : un niveau qui change durablement devient la nouvelle normale au bout de
quelques heures. C'est voulu — un port dont le trafic grandit avec l'usage ne doit pas crier tous les
jours — mais cela veut dire qu'elle ne voit **pas** les changements durables. C'est le travail du
CUSUM (M5, [ADR 0038](0038-rupture-de-niveau.md)), et les deux se complètent sans se recouvrir.

### Ce qui est surveillé, et ce qui ne l'est pas

Les taux réseau (`net.*`) et la qualité des sondes (`probe.jitter`, `probe.loss`). Pas le
processeur, la mémoire ni le disque : ils ont peu d'instances par équipement, leur normale par
créneau se construit sans peine, et la bande n'y apporterait rien qu'une seconde alerte pour le même
fait.

Une seule mesure hors bande n'ouvre rien : il en faut trois de suite, et trois mesures revenues pour
refermer. Et rien ne s'ouvre si la détection par créneau a déjà une alerte sur le même indicateur.

Le message donne la plage apprise — « ce port tient d'ordinaire entre 410 et 590 Mb/s » — et dit
qu'elle est apprise et non réglée à la main, pour que personne ne cherche le seuil correspondant
dans les règles.

## Alternatives écartées

- **Abaisser l'exigence d'historique de la détection par créneau** : six valeurs sur vingt-huit
  jours est déjà peu ; moins, et la normale d'un créneau ne veut plus rien dire.
- **Un seuil fixe par port** : c'est ce qui existe déjà avec les règles, et personne ne réglera
  quarante-huit seuils par commutateur.
- **Un écart-type glissant classique** (fenêtre de N mesures) : il faut garder les N valeurs par
  port, soit des centaines de milliers de nombres, pour un résultat équivalent.
- **Ramener la valeur au bord de la bande** : mesuré ci-dessus, cela laisse la bande s'élargir d'un
  facteur huit en dix pointes.
- **Surveiller tous les indicateurs** : doublerait les alertes du processeur et de la mémoire, que
  la détection par créneau suit déjà très bien.

## Conséquences

- **Une ligne d'état par indicateur et par port.** Sur un parc d'école, quelques milliers de lignes
  de quatre nombres ; à surveiller si le parc grandit beaucoup.
- **Une dégradation lente devient la normale.** Assumé, et c'est pourquoi M5 existe.
- **Les premières minutes d'un port ne sont pas surveillées** (vingt mesures), ce qui reste très
  loin des semaines d'avant.
- **Un port à l'arrêt complet, puis rallumé**, verra sa bande apprise sur du zéro : les premières
  mesures de trafic sortiront de la bande et ouvriront une alerte. Elle se refermera seule en
  quelques dizaines de mesures. C'est le prix d'une normale qui s'apprend vite.
- **Rien n'a été éprouvé sur du trafic réel de l'EPT** : l'instance de démonstration n'a pas
  d'interfaces SNMP, donc aucune bande ne s'y construit — ce qui est le comportement correct, mais
  ne vaut pas une vérification. Les jeux d'épreuve sont construits à la main, avec un générateur à
  graine fixe.
