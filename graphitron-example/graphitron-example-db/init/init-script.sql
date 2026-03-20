ALTER TABLE FILM ALTER COLUMN rental_duration DROP NOT NULL;

ALTER TABLE CUSTOMER ADD COLUMN IF NOT EXISTS USERNAMES TEXT[];
ALTER TABLE CUSTOMER ADD COLUMN IF NOT EXISTS PAST_ADDRESS_IDS INTEGER[];

UPDATE CUSTOMER SET USERNAMES = ARRAY['user1', 'user2'] WHERE CUSTOMER_ID = 1;
UPDATE CUSTOMER SET USERNAMES = ARRAY[]::TEXT[] WHERE CUSTOMER_ID = 2;
UPDATE CUSTOMER SET USERNAMES = ARRAY['MyUsername'] WHERE CUSTOMER_ID = 3;

UPDATE CUSTOMER SET PAST_ADDRESS_IDS = ARRAY[2, 3] WHERE CUSTOMER_ID = 1;
UPDATE CUSTOMER SET PAST_ADDRESS_IDS = ARRAY[]::INTEGER[] WHERE CUSTOMER_ID = 2;
UPDATE CUSTOMER SET PAST_ADDRESS_IDS = ARRAY[4] WHERE CUSTOMER_ID = 3;

-- Mixed-case names to demonstrate collation differences.
-- With case-insensitive collation: ABNEY, abel, Acosta (alphabetical regardless of case).
-- Without collation (default C locale): ABNEY, Acosta, abel (uppercase before lowercase).
UPDATE CUSTOMER SET LAST_NAME = 'abel' WHERE CUSTOMER_ID = 504;   -- was ADAM
UPDATE CUSTOMER SET LAST_NAME = 'Acosta' WHERE CUSTOMER_ID = 36;  -- was ADAMS

-- Create a case-insensitive collation for testing.
CREATE COLLATION IF NOT EXISTS case_insensitive (provider = icu, locale = 'und-u-ks-level2', deterministic = false);