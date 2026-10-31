-- Mot de passe temporaire (FEAT-01) : un compte créé ou réinitialisé par un administrateur doit
-- choisir son propre mot de passe à la première connexion. Les comptes existants ne sont pas
-- concernés (valeur par défaut false).
ALTER TABLE app_user ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;
