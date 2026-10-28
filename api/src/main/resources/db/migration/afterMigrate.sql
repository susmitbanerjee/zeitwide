-- Flyway runs this after every migrate, even when nothing was pending, so grants
-- reach logins created after the migration that defined them (see V9).
SELECT apply_role_grants();
