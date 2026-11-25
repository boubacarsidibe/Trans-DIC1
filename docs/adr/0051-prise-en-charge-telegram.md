# ADR 0051 : prise en charge depuis Telegram

- Statut : accepté
- Date : 2026-11-25
- S'appuie sur le canal Telegram des notifications et sur le cycle de vie des alertes

## Contexte

La personne d'astreinte reçoit déjà l'alerte sur Telegram. Pour la prendre en charge — ce qui arrête
l'escalade — elle doit ouvrir un ordinateur, se connecter, retrouver l'alerte. La nuit, c'est là que
se perdent les minutes que mesure le MTTA (ADR 0053).

## Options

1. **Un lien dans le message** vers l'alerte : il faut toujours se connecter.
2. **Un webhook Telegram** : Telegram appelle la plateforme à chaque clic. Il faut une adresse
   publique en HTTPS, donc exposer le serveur de l'EPT à Internet.
3. **Interrogation longue (`getUpdates`)** : la plateforme va chercher les clics. Rien d'entrant.

## Décision

Option 3, avec des boutons « Je prends », « Résolu » et « Diagnostic » sur les messages d'alerte.

- **Qui agit** : un clic n'est accepté que d'un compte Telegram **lié** à un compte de la plateforme.
  La personne génère dans l'application un code à usage unique (6 caractères, 10 minutes, gardé sous
  forme d'empreinte SHA-256) et l'envoie au bot (`/lier CODE`). Un Telegram ne sert qu'un compte.
- **Avec quels droits** : l'action est exécutée par `AlertWorkflowService`, au nom du compte lié, avec
  son rôle et son périmètre de sites (le contexte de sécurité est celui de ce compte le temps de
  l'action). Un lecteur ne peut que consulter. L'historique dit « Depuis Telegram » ; l'audit porte
  le nom de la personne.
- **Où** : conversation privée seulement ; un groupe reçoit un refus.
- **Le message est mis à jour** : « Prise en charge par awa à 03:12 », boutons retirés une fois
  résolue. L'écran du NOC se met à jour en direct comme pour une action faite au bureau.
- Commandes : `/lier`, `/alertes` (alertes ouvertes du périmètre, les plus graves d'abord, avec
  boutons), `/delier`.
- Désactivé par défaut ; le jeton du bot est une référence de secret (`env:NOM`).

## Conséquences

- Prise en charge en deux secondes depuis un téléphone, sans VPN ni adresse publique.
- Dépend d'Internet et de Telegram : la plateforme continue de fonctionner sans, seuls les boutons
  manquent. Pas de SMS (canal hors bande) pour l'instant.
- Une session Telegram volée (téléphone déverrouillé) permet d'agir au nom de la personne : délier
  le compte depuis l'application coupe l'accès immédiatement.
