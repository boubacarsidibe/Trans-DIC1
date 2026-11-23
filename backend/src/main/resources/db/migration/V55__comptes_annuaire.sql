-- Comptes adosses a un annuaire LDAP (ADR 0046).
--
-- Un compte est soit local, soit adosse a l'annuaire, jamais les deux. Sans ce drapeau, un mot de
-- passe local perime continuerait d'ouvrir la porte apres un changement cote annuaire : c'est tout
-- l'interet de deleguer l'authentification que de ne pas garder de second secret.
ALTER TABLE app_user
    ADD COLUMN directory BOOLEAN NOT NULL DEFAULT FALSE;

-- Les comptes d'annuaire n'ont pas de mot de passe utilisable : la colonne reste obligatoire (le
-- schema ne change pas), on y ecrit une valeur aleatoire que personne ne peut presenter.
COMMENT ON COLUMN app_user.directory IS
    'Vrai si le compte est authentifie par l''annuaire LDAP ; son mot de passe local est alors inutilisable';
