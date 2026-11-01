-- A missing phone is distinct from an unverified phone supplied by a user.
-- Existing values and the unique index are deliberately left unchanged.
ALTER TABLE users MODIFY COLUMN phone VARCHAR(32) NULL;
