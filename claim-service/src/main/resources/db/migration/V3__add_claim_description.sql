ALTER TABLE claims ADD COLUMN description VARCHAR(2000);
UPDATE claims SET description = reason WHERE description IS NULL;
ALTER TABLE claims ALTER COLUMN description SET NOT NULL;
