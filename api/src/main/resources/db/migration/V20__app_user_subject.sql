-- Sign-in no longer goes through Keycloak: the subject is the identity provider and its ID for
-- the person ("google:1234"), set by the web app. Nobody had signed up when this ran.
ALTER TABLE app_user RENAME COLUMN keycloak_subject TO subject;
