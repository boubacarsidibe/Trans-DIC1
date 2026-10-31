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

## Dépendances

- Ajout de `@playwright/test` 1.64.1 (publié le 14/10/2026) en dépendance de développement : captures
  de contrôle maintenant, tests de bout en bout ensuite (QA-02). Il utilise le Chrome installé.
- `npm audit fix` : faille élevée de `follow-redirects` (via axios) corrigée. Restent 3 failles
  modérées dans `minimatch`, tiré par `openapi-typescript` (outil de développement, non livré) :
  le correctif impose une version majeure incompatible, laissé en l'état et à revoir.
