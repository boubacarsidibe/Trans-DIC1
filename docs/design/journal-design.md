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

## 2026-10-31 — Page de connexion

Refaite entièrement.

- **Panneau gauche « tableau de garde »** : un schéma du réseau du campus (routeur cœur,
  commutateurs, serveurs, pare-feu) avec les trois états de la plateforme. C'est l'objet que les
  utilisateurs retrouveront dans l'application, pas une illustration générique. Le nœud hors service
  émet une onde lente (coupée en mouvement réduit). Le schéma est décoratif (`aria-hidden`) ; la
  légende en texte en donne le sens.
- **Formulaire** : libellés visibles, bouton « Afficher / Masquer » le mot de passe (`aria-pressed`),
  avertissement « Verrouillage majuscules activé », erreur encadrée avec icône, bouton avec
  indicateur de chargement (`aria-busy`), champs marqués `aria-invalid` après un échec. Le message
  d'erreur reste générique (il ne dit pas si c'est le compte ou le mot de passe qui est faux).
- **Aide** : « Pas de compte ou mot de passe oublié ? Contactez l'administrateur du CRI » — il n'y a
  pas de réinitialisation en libre-service, on ne promet pas un lien qui n'existe pas.
- **Téléphone** : le panneau se réduit à un bandeau de marque ; le formulaire passe en premier.

## Dépendances

- Ajout de `@playwright/test` 1.64.1 (publié le 14/10/2026) en dépendance de développement : captures
  de contrôle maintenant, tests de bout en bout ensuite (QA-02). Il utilise le Chrome installé.
- `npm audit fix` : faille élevée de `follow-redirects` (via axios) corrigée. Restent 3 failles
  modérées dans `minimatch`, tiré par `openapi-typescript` (outil de développement, non livré) :
  le correctif impose une version majeure incompatible, laissé en l'état et à revoir.
