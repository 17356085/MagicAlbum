-- A missing email is distinct from an unverified email supplied by a user.
-- Existing values and the unique index are deliberately left unchanged.
ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NULL;
