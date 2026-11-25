# ADR 0053 : post-mortem d'incident, indicateurs de service et analyse d'impact

- Statut : accepté
- Date : 2026-11-25
- S'appuie sur les incidents (ADR 0039), le diagnostic (ADR 0024, 0045), l'astreinte (ADR 0025) et
  la topologie (ADR 0012, 0034)

## Contexte

La plateforme traçait tout — alertes, prises en charge, notifications, escalades, causes confirmées
— mais ne le restituait que morceau par morceau. Trois questions de gestion d'incident restaient
sans réponse directe :

1. *Que s'est-il passé pendant cet incident, et qu'en retient-on ?* (le post-mortem)
2. *L'équipe réagit-elle assez vite ?* (MTTA, MTTR, MTBF)
3. *Que se passe-t-il si j'éteins cet équipement ?* (l'impact, avant une maintenance)

Zabbix ne propose aucune des trois en standard.

## Décision

Trois modules en bout de chaîne (personne ne dépend d'eux), qui relisent la base par leurs propres
requêtes bornées, comme le diagnostic, et respectent le périmètre par site.

### Post-mortem (module `postmortem`)

- Calculé à la demande, jamais stocké : il ne peut pas se désynchroniser de l'historique.
- Chronologie complète : début, alertes, notifications (canal, destinataire, résultat), astreinte,
  prises en charge, commentaires, escalade, actions correctives, causes confirmées, maintenances,
  fin.
- Durées : délai de prise en charge (début de l'incident → première prise en charge), durée de
  l'incident. Objectif de prise en charge réglable (15 min par défaut).
- Constats « sans reproche » tirés des faits, chacun avec ses chiffres et une recommandation :
  prise en charge hors objectif, retour à la normale très bref (règle bavarde ?), notifications en
  échec, astreinte injoignable, escalade, récidive sur 30 jours, cause non confirmée, composant
  public touché sans communication, action corrective réussie ou en échec.
- PDF imprimable (OpenPDF), même charte que les rapports.

### Indicateurs de service

- Alertes racines seulement (une alerte rattachée à une panne en amont n'a été ni notifiée ni
  traitée pour elle-même).
- MTTA et MTTR en moyenne **et** en médiane : une alerte oubliée un week-end fait exploser la
  moyenne, pas la médiane.
- Par sévérité, site, opérateur, semaine ; MTBF des équipements les plus touchés (temps en service
  divisé par le nombre de pertes de collecte, arrêts rognés aux bornes de la période).

### Analyse d'impact (module `impact`)

- Aval par la topologie, avec repérage du **second chemin** : un équipement qui a un autre amont non
  touché reste joignable (calcul de point fixe, une boucle saisie par erreur ne crée pas de chemin).
- Services surveillés interrompus, composants de pages de statut touchés (publics signalés), liens
  appris acceptés, personne d'astreinte, maintenances prévues.
- Niveau d'impact (faible, moyen, élevé, critique) et recommandations ; planification de la
  maintenance sur la cible et son aval perdu en un geste.

## Conséquences

- Le vocabulaire de la gestion d'incidents (ITIL) entre dans la plateforme : MTTA, MTTR, MTBF,
  post-mortem.
- Les chiffres ne valent que ce que vaut la discipline de prise en charge : une équipe qui ne prend
  jamais en charge n'a pas de MTTA.
- Le post-mortem ne remplace pas la discussion d'équipe : il en est le point de départ.
