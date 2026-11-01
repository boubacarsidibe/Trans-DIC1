# Journal de design de l'interface

Décisions de design de Trans-DIC1, avec leur raison. Chaque passe est vérifiée par captures
(`frontend/scripts/ui-snapshots.mjs`, bureau 1440×900 et téléphone 390×844) et par les tests.

## Principes retenus

- **Public et usage** : opérateurs NOC et administrateurs du CRI de l'École Polytechnique de Thiès,
  sur poste de travail le plus souvent, parfois sur téléphone en astreinte. L'interface doit se lire
  vite, à froid, sans formation.
- **Thème « salle de contrôle de jour »** (conservé) : papier gris-bleu `#eef2f7`, encre bleu nuit
  `#10203a`, bleu signal `#0a5878` pour l'action, jaune de veille `#f2b705` comme seule couleur de
  marque. Les couleurs de sévérité (vert, ambre, rouge) sont réservées aux états.
- **Un état ne dépend jamais de la couleur seule** : chaque état a aussi une forme (point plein,
  losange, croix) ou un libellé. Critère WCAG 1.4.1.
- **Typographie** (conservée) : Bricolage Grotesque pour les titres, Source Sans 3 pour le texte et
  les chiffres (chiffres tabulaires).
- **Accessibilité minimale à chaque écran** : contraste de 4,5:1 pour le texte, focus visible
  (anneau jaune), zones cliquables de 44 px de haut au moins, libellés visibles au-dessus des
  champs, erreurs annoncées (`role="alert"`), animations coupées si `prefers-reduced-motion`.

## Sources consultées

- Plugin `ui-ux-pro-max` 2.15.0 (base locale de règles UX) : règles d'accessibilité, de formulaires
  et de tableaux. Sa proposition de système de design (« Trust & Authority + Conversion », thème
  sombre, Poppins) a été **écartée** : elle vise une page commerciale de conversion, pas un outil
  interne de supervision, et aurait cassé la cohérence du thème déjà en place.
- Règles reprises du plugin : tableaux dans un conteneur à défilement horizontal sur mobile,
  libellés visibles plutôt que placeholders, retour de chargement sur les boutons, survol en
  150–300 ms, curseur pointeur sur les éléments cliquables.

## 2026-10-31 — Page de connexion (version 2, retenue)

Première version (schéma de réseau dessiné, texte d'explication, liste d'atouts) **refusée** par
Boubacar : trop de texte, pas de vraie image. Demande : une vraie photo en fond, un seul slogan,
une ergonomie nette, et tout sur un seul écran.

- **Photo plein écran** : câbles réseau bleus branchés sur des commutateurs, voyants jaunes. C'est
  du réseau (le métier de la plateforme), pas un cliché générique de serveurs ; le bleu rejoint le
  bleu signal et les voyants le jaune de marque.
  - Photo de Scott Rodgerson sur Unsplash (`PSpf_XgOM5w`), **licence Unsplash** : usage commercial et
    non commercial libre, sans autorisation ni crédit obligatoire. Crédit laissé en commentaire dans
    `LoginPage.jsx` et ici.
  - Écartées : deux photos « Unsplash+ » (payantes, Getty Images) ; une allée de baies claire (texte
    blanc illisible dessus) ; la photo de baies de Taylor Vick (très utilisée, déjà vue partout).
  - Servie en WebP local (pas de lien vers Unsplash à l'exécution) : 2000 px pour les écrans larges
    (160 Ko), 900 px pour les téléphones (57 Ko), choisie par `srcset`.
- **Contraste** : voile bleu nuit en dégradé, dense sous le slogan, plus léger derrière la carte ;
  le formulaire est sur une carte blanche opaque, donc son contraste ne dépend pas de la photo.
- **Texte réduit au minimum** : marque, slogan « Votre réseau, sous contrôle. », la carte, une
  ligne d'aide, le nom de l'école.
- **Un seul écran** : hauteur `100dvh`, sans défilement, vérifié en 1440×900, 1280×720 (avec le
  message d'erreur affiché) et 390×844.
- **Ergonomie du formulaire** : libellés visibles, focus automatique sur le nom d'utilisateur, œil
  pour afficher/masquer le mot de passe (`aria-pressed`, zone de 42 px), avertissement de
  verrouillage des majuscules, erreur encadrée avec icône et annoncée (`role="alert"`), champs en
  rouge (`aria-invalid`), bouton avec indicateur de chargement. Message d'erreur générique (on ne
  révèle pas si le compte existe).
- **Téléphone** : la photo devient un bandeau sous la marque et le slogan, la carte suit.

## 2026-10-31 — Page de connexion : proportions sur grand écran

Retour : à 100 % sur grand écran, le formulaire paraissait minuscule. Toutes les tailles de la page
sont désormais en `em`, rapportées à une police fluide (`clamp(1rem, 0.42vw + 0.6rem, 1.45rem)`) :
la carte fait 432 px de large à 1366 px, 477 px à 1920 px, 550 px à 2560 px, sans défilement.

## 2026-10-31 — Écrans de l'application (version retenue : console sombre et grise)

Deux versions intermédiaires **refusées** par Boubacar :

1. Thème clair, cartes arrondies avec icônes et ombres : « fait par une IA », trop de petits
   éléments, pas un logiciel.
2. Bandeau photo + fond blanc : le blanc ne plaisait pas, les chiffres en cartes à bordure non plus,
   les tableaux faisaient « IA », l'ensemble prenait trop de place.

Références étudiées : les démos publiques de Grafana, Uptime Kuma et LibreNMS n'ont pas pu être
capturées (installation ou connexion exigées, et on ne saisit pas d'identifiants sur un site tiers).
Le design s'appuie donc sur la structure commune, connue, des consoles de supervision (Grafana,
Datadog, PagerDuty, Zabbix 7, PRTG) et sur des guides d'interfaces d'exploitation : en
exploitation on contrôle plus qu'on analyse, donc les tableaux dominent, la couleur d'état se lit
avant le chiffre, on évite jauges et camemberts décoratifs.

Décisions :

- **Continuité avec la connexion** : le fond de l'application est la même photo réseau, désaturée
  (version grise servie par Unsplash, `sat=-100`, 62 Ko) sous un voile gris très sombre ; le menu
  garde la photo en couleur, très assombrie. Les bords restent texturés, le contenu reste lisible.
- **Thème sombre** : texte clair `#e6ebf1`, surfaces translucides `rgb(22 27 35 / 82%)`, traits à
  8 % de blanc, angles de 4 px, aucune ombre. Les classes Tailwind des pages (écrites à l'origine
  pour un thème sombre) retrouvent leurs valeurs sombres.
- **Chiffres récapitulatifs** : plus de cartes. Une ligne de chiffres posés en texte (valeur en gros,
  libellé à côté, rouge seulement si un équipement est hors service ou une alerte active) et deux
  **anneaux** de répartition (équipements par statut, alertes par sévérité) avec leur légende chiffrée.
- **Tableaux façon logiciel d'exploitation** : lignes serrées (≈ 36 px), en-tête sobre, lignes
  alternées, survol bleu, sévérité en **cellule colorée** (repère classique de Zabbix), état en point
  de couleur + mot, actions groupées à droite, identifiants techniques en petit sous le nom.
- **Compacité** : titres de page à 1,35 rem, phrases d'en-tête raccourcies à une ligne, boutons de
  32 px, contenu pleine largeur.
- **Uniformité** : mêmes boutons (`btn`), champs (`field`), messages (`notice`), tableaux
  (`data-table`) et pagination sur toutes les pages ; libellés français pour toutes les valeurs
  techniques (`ui/labels.js`).
- **Téléphone** : menu repliable ; aucune page ne déborde en largeur (vérifié sur les 11 écrans à
  390 px ; un libellé masqué `sr-only` non rattaché élargissait la page, corrigé).
- **Icônes** : `lucide-react` 1.45.0 (licence ISC), version figée de plus de six semaines.

## 2026-10-31 — Correction : feuille gris clair sur fond sombre brun (version retenue)

La console entièrement noire **ne correspondait pas** à la demande. Ce que Boubacar voulait :
réduire la zone centrale et la passer en gris assez clair, posée sur un fond sombre qui tire vers
le brun.

- **Fond** : photo réseau désaturée sous un voile brun très sombre (`rgb(38 33 29 / 84%)` →
  `rgb(30 26 23 / 90%)`). Le menu est transparent sur ce fond : un seul cadre sombre et texturé.
- **Feuille de travail** : gris clair chaud `#e2dfda`, plus petite que l'écran (marge de 1,25 rem),
  coins de 8 px, ombre portée douce ; texte foncé `#1d232c`. Les bords sombres restent visibles
  tout autour.
- **Sur la feuille** : sections `#ebe9e5`, tableaux à en-tête `#d8d5cf` et lignes alternées claires,
  boutons secondaires presque blancs, bouton principal bleu signal (le même que la connexion).
  Les anneaux, chiffres en texte, cellules de sévérité et la compacité de la version précédente sont
  conservés.
- Vérifié : 140 tests frontend, build, aucune page ne déborde à 390 px.

## 2026-10-31 — Finition des pages restantes

Demande : appliquer la même finition à Maintenances et à toutes les pages incluses.

- **Historique** et **Collecte** passent dans la feuille grise, avec l'en-tête commun et un lien
  de retour « Équipements » (le titre était illisible, sombre sur fond sombre).
- **Choix de période** de l'historique : boutons accolés, la période active en bleu signal.
- **Fenêtre de confirmation** : le voile blanchissait la page (couleur inversée par la palette) ;
  désormais voile brun sombre, boîte gris clair avec titre, texte explicite et actions à droite.
- **États** des règles, comptes, sites et maintenances : point de couleur + mot, comme partout.
- Derniers styles hérités remplacés (champs des indicateurs SNMP, bouton secondaire, encadré
  d'avertissement) : mêmes hauteurs et mêmes formes sur toutes les pages.

## 2026-10-31 — Connexion : composition centrée

Retour sur un écran de 1852×909 : slogan collé au bord gauche, carte au bord droit, grand vide au
milieu, marges inégales ; lettres du slogan trop serrées ; icône d'œil barré affichée alors que le
mot de passe était masqué (bouton natif d'Edge en doublon du nôtre).

- Contenu centré dans une largeur de 70 em : marges égales (318 px à 1852 px, 123 px à 1366 px,
  568 px à 2560 px), slogan et carte regroupés ; la photo reste plein écran.
- Slogan : interlettrage −0,005 em (au lieu de −0,02 em), interligne 1,06.
- Bouton natif d'Edge masqué (`::-ms-reveal`) : une seule icône, la nôtre.

## 2026-10-31 — Connexion : même taille qu'au zoom 175 %

Demande : à 100 % le formulaire reste trop petit ; il doit avoir la taille qu'il a au zoom 175 %,
quel que soit le zoom.

- Police de base de la page : `clamp(1rem, min(1.5vw, 3.05vh), 2.2rem)`, calée sur la largeur et
  la hauteur de l'écran. Sur 1852×909 : carte de 749 px à 100 % contre 756 px à 175 %.
- Hauteur minimale passée en `rem` (elle grandissait avec la police et faisait défiler la page) ;
  marges verticales en `vh`. Aucun défilement en 1366×768, 1852×909, 1920×1080, 2560×1440, 390×844.
- Icônes du formulaire à la taille du texte.

## Zoom du navigateur sans barre de défilement (01/11/2026)

- Constat : à partir de 175 % de zoom, les tableaux (alertes, équipements, règles, audit) devenaient
  plus larges que leur cadre, qui affichait une barre de défilement horizontale ; la connexion
  défilait verticalement à partir de 200 %.
- Tableaux : `ui/fitTables.js` mesure chaque `.data-table` ; s'il déborde, il passe en fiches
  (une par ligne, nom de colonne devant chaque valeur, actions en bas), et redevient un tableau dès
  qu'il tient. Libellés repris des en-têtes : aucune page modifiée. Préféré au masquage de
  colonnes (on perdait de l'information) et au défilement (refusé).
- Connexion : plus de plancher `1rem` sur la police ni de hauteur minimale ; la mise en page
  téléphone ne s'applique qu'en portrait. La carte garde la même taille à l'écran (789 px) de 100 %
  à 300 % de zoom, sans défilement.
- Vérifié sur 16 pages à 100, 125, 150, 175, 200, 250 et 300 % : aucun débordement horizontal.

## Dépendances

- Ajout de `@playwright/test` 1.64.1 (publié le 14/10/2026) en dépendance de développement : captures
  de contrôle maintenant, tests de bout en bout ensuite (QA-02). Il utilise le Chrome installé.
- `npm audit fix` : faille élevée de `follow-redirects` (via axios) corrigée. Restent 3 failles
  modérées dans `minimatch`, tiré par `openapi-typescript` (outil de développement, non livré) :
  le correctif impose une version majeure incompatible, laissé en l'état et à revoir.
