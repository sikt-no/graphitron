-- Rewrite-test database schema: Sakila-inspired subset owned by this project.
-- Extend freely as new test cases require.

CREATE TYPE mpaa_rating AS ENUM ('G', 'PG', 'PG-13', 'R', 'NC-17');

-- -------------------------
-- Lookup / reference tables
-- -------------------------

CREATE TABLE language (
    language_id  serial      PRIMARY KEY,
    name         char(20)    NOT NULL,
    last_update  timestamp   NOT NULL DEFAULT now()
);

CREATE TABLE country (
    country_id   serial      PRIMARY KEY,
    country      varchar(50) NOT NULL,
    last_update  timestamp   NOT NULL DEFAULT now()
);

CREATE TABLE city (
    city_id      serial      PRIMARY KEY,
    city         varchar(50) NOT NULL,
    country_id   int         NOT NULL REFERENCES country(country_id),
    last_update  timestamp   NOT NULL DEFAULT now()
);

CREATE TABLE address (
    address_id   serial      PRIMARY KEY,
    address      varchar(50) NOT NULL,
    address2     varchar(50),
    district     varchar(20) NOT NULL,
    city_id      int         NOT NULL REFERENCES city(city_id),
    postal_code  varchar(10),
    phone        varchar(20) NOT NULL DEFAULT '',
    last_update  timestamp   NOT NULL DEFAULT now()
);

-- -------------------------
-- Store / staff  (circular FK resolved with ALTER TABLE)
-- -------------------------

CREATE TABLE store (
    store_id          serial  PRIMARY KEY,
    address_id        int     NOT NULL REFERENCES address(address_id),
    last_update       timestamp NOT NULL DEFAULT now()
);

CREATE TABLE staff (
    staff_id     serial      PRIMARY KEY,
    first_name   varchar(45) NOT NULL,
    last_name    varchar(45) NOT NULL,
    address_id   int         NOT NULL REFERENCES address(address_id),
    email        varchar(50),
    store_id     int         NOT NULL REFERENCES store(store_id),
    active       boolean     NOT NULL DEFAULT true,
    username     varchar(16) NOT NULL,
    last_update  timestamp   NOT NULL DEFAULT now()
);

ALTER TABLE store ADD COLUMN manager_staff_id int REFERENCES staff(staff_id);

-- -------------------------
-- Customer
-- -------------------------

CREATE TABLE customer (
    customer_id  serial      PRIMARY KEY,
    store_id     int         NOT NULL REFERENCES store(store_id),
    first_name   varchar(45) NOT NULL,
    last_name    varchar(45) NOT NULL,
    email        varchar(50),
    address_id   int         NOT NULL REFERENCES address(address_id),
    activebool   boolean     NOT NULL DEFAULT true,
    create_date  date        NOT NULL DEFAULT current_date,
    last_update  timestamp   DEFAULT now(),
    active       integer
);

-- -------------------------
-- Actor
-- -------------------------

CREATE TABLE actor (
    actor_id    serial      PRIMARY KEY,
    first_name  varchar(45) NOT NULL,
    last_name   varchar(45) NOT NULL,
    last_update timestamp   NOT NULL DEFAULT now()
);

CREATE INDEX idx_actor_last_name ON actor(last_name);

-- -------------------------
-- Film / category
-- -------------------------

CREATE TABLE film (
    film_id                serial          PRIMARY KEY,
    title                  varchar(255)    NOT NULL,
    description            text,
    release_year           int,
    language_id            int             NOT NULL REFERENCES language(language_id),
    original_language_id   int             REFERENCES language(language_id),
    rental_duration        smallint        NOT NULL DEFAULT 3,
    rental_rate            numeric(4,2)    NOT NULL DEFAULT 4.99,
    length                 smallint,
    replacement_cost       numeric(5,2)    NOT NULL DEFAULT 19.99,
    rating                 mpaa_rating     DEFAULT 'G',
    text_rating            varchar(10),
    last_update            timestamp       NOT NULL DEFAULT now()
);

CREATE TABLE film_actor (
    actor_id    int  NOT NULL REFERENCES actor(actor_id),
    film_id     int  NOT NULL REFERENCES film(film_id),
    last_update timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, film_id)
);

CREATE TABLE category (
    category_id         serial      PRIMARY KEY,
    name                varchar(25) NOT NULL,
    parent_category_id  int         REFERENCES category(category_id),
    last_update         timestamp   NOT NULL DEFAULT now()
);

CREATE TABLE film_category (
    film_id      int  NOT NULL REFERENCES film(film_id),
    category_id  int  NOT NULL REFERENCES category(category_id),
    last_update  timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (film_id, category_id)
);

-- -------------------------
-- Inventory / rental / payment
-- -------------------------

CREATE TABLE inventory (
    inventory_id  serial  PRIMARY KEY,
    film_id       int     NOT NULL REFERENCES film(film_id),
    store_id      int     NOT NULL REFERENCES store(store_id),
    last_update   timestamp NOT NULL DEFAULT now()
);

CREATE TABLE rental (
    rental_id     serial    PRIMARY KEY,
    rental_date   timestamp NOT NULL,
    inventory_id  int       NOT NULL REFERENCES inventory(inventory_id),
    customer_id   int       NOT NULL REFERENCES customer(customer_id),
    return_date   timestamp,
    staff_id      int       NOT NULL REFERENCES staff(staff_id),
    last_update   timestamp NOT NULL DEFAULT now()
);

CREATE TABLE payment (
    payment_id    serial        PRIMARY KEY,
    customer_id   int           NOT NULL REFERENCES customer(customer_id),
    staff_id      int           NOT NULL REFERENCES staff(staff_id),
    rental_id     int           NOT NULL REFERENCES rental(rental_id),
    amount        numeric(5,2)  NOT NULL,
    payment_date  timestamp     NOT NULL
);

-- -------------------------
-- film_list: PK-less summary view (as a plain table for test purposes)
-- -------------------------

CREATE TABLE film_list (
    title       varchar(255),
    description text,
    category    varchar(25)
);

-- -------------------------
-- content: discriminated-interface fixture for TableInterfaceType tests.
-- content_type distinguishes FilmContent ('FILM') from ShortContent ('SHORT').
-- film_id optionally links a content entry back to a film (used by ChildField.TableInterfaceField fixture).
-- -------------------------

CREATE TABLE content (
    content_id    serial       PRIMARY KEY,
    content_type  varchar(10)  NOT NULL,
    title         varchar(255) NOT NULL,
    length        smallint,
    film_id       int          REFERENCES film(film_id),
    last_update   timestamp    NOT NULL DEFAULT now()
);

-- ===========================
-- Seed data
-- ===========================

INSERT INTO actor (first_name, last_name) VALUES
    ('PENELOPE', 'GUINESS'),
    ('NICK',     'WAHLBERG'),
    ('ED',       'CHASE');

INSERT INTO language (name) VALUES ('English'), ('Italian'), ('Japanese');

INSERT INTO country (country) VALUES ('United States'), ('Italy'), ('Japan');

INSERT INTO city (city, country_id) VALUES
    ('Lethbridge', 1),
    ('Rome',       2),
    ('Tokyo',      3);

INSERT INTO address (address, district, city_id) VALUES
    ('47 MySakila Drive',   'Alberta', 1),
    ('28 MySQL Boulevard',  'Lazio',   2),
    ('23 Workhaven Lane',   'Alberta', 1);

INSERT INTO store (address_id) VALUES (1), (2);

INSERT INTO staff (first_name, last_name, address_id, email, store_id, active, username) VALUES
    ('Mike', 'Hillyer',  3, 'mike.hillyer@example.com',  1, true, 'mike'),
    ('Jon',  'Stephens', 2, 'jon.stephens@example.com',  2, true, 'jon');

UPDATE store SET manager_staff_id = 1 WHERE store_id = 1;
-- store_id = 2 intentionally left with manager_staff_id = NULL to exercise the
-- null-FK short-circuit in single-cardinality @splitQuery fetchers; see
-- plan-single-cardinality-split-query.md §4 and Store.manager in schema.graphqls.

-- 3 active, 2 inactive customers
INSERT INTO customer (store_id, first_name, last_name, email, address_id, activebool, active) VALUES
    (1, 'Mary',      'Smith',    'mary.smith@example.com',      1, true,  1),
    (1, 'Patricia',  'Johnson',  'patricia.johnson@example.com', 2, true,  1),
    (2, 'Linda',     'Williams', 'linda.williams@example.com',   3, true,  1),
    (1, 'Barbara',   'Jones',    'barbara.jones@example.com',    1, false, 0),
    (2, 'Elizabeth', 'Brown',    'elizabeth.brown@example.com',  2, false, 0);

-- Films spanning all ratings and a range of rental rates
INSERT INTO film (title, description, release_year, language_id, rental_rate, rating, text_rating, length) VALUES
    ('ACADEMY DINOSAUR', 'A Epic Drama',         2006, 1, 0.99, 'PG',    'PG',    86),
    ('ACE GOLDFINGER',   'A Thrilling Saga',     2006, 1, 4.99, 'G',     'G',     48),
    ('ADAPTATION HOLES', 'A Quirky Comedy',      2006, 1, 2.99, 'NC-17', 'NC-17', 50),
    ('AFFAIR PREJUDICE', 'A Classic Romance',    2006, 1, 2.99, 'G',     'G',    117),
    ('AGENT TRUMAN',     'An Action Adventure',  2006, 1, 2.99, 'PG',    'PG',   169);

-- Self-referential category hierarchy for G5 depth-2 recursion tests:
--   Genre          (id=1)
--   ├── Action     (id=2, parent=1)
--   │   └── Thriller (id=5, parent=2)  -- depth-2 leaf
--   ├── Animation  (id=3, parent=1)
--   └── Comedy     (id=4, parent=1)
INSERT INTO category (name, parent_category_id) VALUES
    ('Genre',     NULL),
    ('Action',    1),
    ('Animation', 1),
    ('Comedy',    1),
    ('Thriller',  2);

INSERT INTO film_category (film_id, category_id) VALUES
    (1, 4), (2, 1), (3, 3), (4, 4), (5, 1);

-- Cast each film with actors from the seeded actor pool (1=PENELOPE, 2=NICK, 3=ED).
-- Used by argres Phase 2a execution tests (Film.actors inline @lookupKey via film_actor).
--   film 1 (ACADEMY DINOSAUR) → PENELOPE, NICK
--   film 2 (ACE GOLDFINGER)   → PENELOPE, ED
--   film 3 (ADAPTATION HOLES) → PENELOPE
--   film 4 (AFFAIR PREJUDICE) → NICK
--   film 5 (AGENT TRUMAN)     → ED
INSERT INTO film_actor (actor_id, film_id) VALUES
    (1, 1), (2, 1),
    (1, 2), (3, 2),
    (1, 3),
    (2, 4),
    (3, 5);

-- content seed data: two FilmContent rows linked to films 1 and 2, two ShortContent rows.
INSERT INTO content (content_type, title, length, film_id) VALUES
    ('FILM',  'ACADEMY DINOSAUR (extended)',  120, 1),
    ('FILM',  'ACE GOLDFINGER (extended)',     90, 2),
    ('SHORT', 'Sunrise',                       12, NULL),
    ('SHORT', 'Interlude',                      8, NULL);

-- ===========================
-- nodeidfixture schema
-- ===========================
--
-- Synthetic fixture tables used exclusively by NodeIdPipelineTest and the JooqCatalog
-- metadata probe tests. Generated by the NodeIdFixtureGenerator (see
-- graphitron-rewrite-fixtures-codegen) which appends __NODE_TYPE_ID / __NODE_KEY_COLUMNS
-- constants on `bar` and `baz` to model the shape Sikt's KjerneJooqGenerator emits in
-- production. `qux` is the negative-case table — no metadata.

CREATE SCHEMA nodeidfixture;

-- Composite-key NodeType, targeted by the generator's metadata hard-coding.
-- bar.id_1 references baz.id so NodeIdReferenceField tests have a reachable target.
CREATE TABLE nodeidfixture.baz (
    id   varchar(50) PRIMARY KEY
);

CREATE TABLE nodeidfixture.bar (
    id_1 varchar(50) NOT NULL REFERENCES nodeidfixture.baz(id),
    id_2 varchar(50) NOT NULL,
    name varchar(50),
    PRIMARY KEY (id_1, id_2)
);

-- Plain TableType — no metadata, and deliberately no `id` column so that any
-- `id: ID!` SDL field on this table lands on UnclassifiedField.
CREATE TABLE nodeidfixture.qux (
    name varchar(50) PRIMARY KEY
);

-- ===========================
-- idreffixture schema
-- ===========================
--
-- Synthetic fixture for IdReferenceField synthesis shim tests. `studieprogram` is the
-- target table; it receives __NODE_TYPE_ID / __NODE_KEY_COLUMNS via NodeIdFixtureGenerator
-- so that catalog.nodeIdMetadata("studieprogram") returns present, satisfying the shim
-- gate.  Two FKs from `studierett` to `studieprogram` exercise two qualifier shapes:
--
--   FK1 studierett.studieprogram_id -> studieprogram.studieprogram_id
--       HAR role (src col = tgt col) -> qualifier "StudieprogramId"
--       raw map key "studieprogram_id" coincides with the source column name, so the
--       shim-before-column-lookup ordering determines whether the field becomes
--       IdReferenceField (shim wins) or ColumnField (column lookup wins).
--
--   FK2 studierett.registrar_studieprogram -> studieprogram.studieprogram_id
--       Role prefix "registrar_studieprogram_" -> qualifier "RegistrarStudieprogramStudieprogramId"
--       raw map key "registrar_studieprogram_studieprogram_id" does NOT match any column
--       on studierett, so without the shim the field is Unresolved.

CREATE SCHEMA idreffixture;

CREATE TABLE idreffixture.studieprogram (
    studieprogram_id varchar(50) PRIMARY KEY
);

CREATE TABLE idreffixture.studierett (
    studierett_id        serial      PRIMARY KEY,
    studieprogram_id     varchar(50) REFERENCES idreffixture.studieprogram(studieprogram_id),
    registrar_studieprogram varchar(50) REFERENCES idreffixture.studieprogram(studieprogram_id)
);
