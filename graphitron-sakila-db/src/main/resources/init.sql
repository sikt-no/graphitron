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
    content_id        serial       PRIMARY KEY,
    content_type      varchar(10)  NOT NULL,
    title             varchar(255) NOT NULL,
    length            smallint,
    short_description varchar(255),
    film_id           int          REFERENCES film(film_id),
    last_update       timestamp    NOT NULL DEFAULT now()
);

-- -------------------------
-- film_endorsement: R315 FK-reference @nodeId fixture. The FK child column
-- (endorsed_film) is deliberately named differently from the referenced parent
-- key (film.film_id), so an FK-reference @nodeId(typeName: "Film") must resolve the
-- target column through the FK constraint, not a name-match shortcut: the decoded
-- Film id lands on endorsed_film, never a same-named film_id column.
-- -------------------------

CREATE TABLE film_endorsement (
    endorsement_id  serial        PRIMARY KEY,
    endorsed_film   int           NOT NULL REFERENCES film(film_id),
    note            varchar(255)
);

-- -------------------------
-- jti_subject (+ jti_app_account, jti_person): R389 COMPOSITE-shared-key discriminated joined-table
-- (class-table) inheritance fixture (the composite counterpart of the single-column party fixture
-- below). A discriminated base table (jti_subject, carrying the subject_kind discriminator) plus one
-- detail table per concrete kind. Each detail table's composite PRIMARY KEY (jti_subject_id,
-- subject_kind) IS its composite FK to the base — the composite-key form of the PK=FK invariant.
-- The composite key re-declares the discriminator column (subject_kind) on the detail table — the
-- dimension no other discriminated fixture has. With that column present on a joined table, an
-- unqualified discriminator reference in the generated interface fetcher's SELECT projection,
-- LEFT JOIN ON-clause, and WHERE filter is ambiguous and PostgreSQL rejects the query
-- ("column reference \"subject_kind\" is ambiguous"); the qualified discriminator (hardened by R388)
-- removes it, and R389 preserves that stress under a participant declaring its own detail @table.
-- The UNIQUE(jti_subject_id, subject_kind) on the base backs the detail composite FK. Each detail
-- table also has a detail-only column (client_id / full_name) projected off the detail alias.
-- -------------------------

CREATE TABLE jti_subject (
    jti_subject_id  serial       PRIMARY KEY,
    subject_kind    varchar(20)  NOT NULL,
    display_name    varchar(255) NOT NULL,
    UNIQUE (jti_subject_id, subject_kind)
);

-- R389: the detail tables carry the composite primary key (jti_subject_id, subject_kind), which IS
-- their composite foreign key to the base — the composite-key form of the PK=FK invariant. This makes
-- the base->detail join single-valued and lets each concrete type declare its own detail @table with
-- the shared key columns present on the detail too (so subjectId / subjectKind need no @reference; only
-- the base-only displayName does).
CREATE TABLE jti_app_account (
    jti_subject_id  int          NOT NULL,
    subject_kind    varchar(20)  NOT NULL,
    client_id       varchar(255),
    PRIMARY KEY (jti_subject_id, subject_kind),
    CONSTRAINT jti_app_account_subject_fk
        FOREIGN KEY (jti_subject_id, subject_kind) REFERENCES jti_subject (jti_subject_id, subject_kind)
);

CREATE TABLE jti_person (
    jti_subject_id  int          NOT NULL,
    subject_kind    varchar(20)  NOT NULL,
    full_name       varchar(255),
    PRIMARY KEY (jti_subject_id, subject_kind),
    CONSTRAINT jti_person_subject_fk
        FOREIGN KEY (jti_subject_id, subject_kind) REFERENCES jti_subject (jti_subject_id, subject_kind)
);

-- -------------------------
-- party (+ party_individual, party_company): R389 single-column discriminated joined-table
-- (class-table) inheritance fixture. A discriminated base table (party, carrying the party_kind
-- discriminator and the base-only shared column display_name) plus one detail table per concrete
-- kind. Each detail table's own primary key IS its foreign key to party(party_id): a single-column
-- shared primary key, the simplest form of the PK=FK invariant R389 requires. Authored the R389
-- way, each concrete type declares its own detail @table; its base-only inherited field
-- (displayName) carries @reference back to party, and its own columns (birth_date / org_number)
-- live on the detail table.
-- -------------------------

CREATE TABLE party (
    party_id     serial       PRIMARY KEY,
    party_kind   varchar(20)  NOT NULL,   -- discriminator: 'INDIVIDUAL' | 'COMPANY'
    display_name varchar(255) NOT NULL
);

CREATE TABLE party_individual (
    party_id   int  PRIMARY KEY REFERENCES party(party_id),
    birth_date date
);

CREATE TABLE party_company (
    party_id   int          PRIMARY KEY REFERENCES party(party_id),
    org_number varchar(64)
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
    ('23 Workhaven Lane',   'Alberta', 1),
    -- address_id 4: intentionally occupant-free (no store/staff/customer references it). The R367
    -- execution test points AddressOccupantCarrier at this hub so the single-cardinality polymorphic
    -- fetcher's empty-stage-1 null-payload arm is exercised (a non-null carrier over an empty hub),
    -- not the null-carrier short-circuit. District is distinct from 'Alberta'/'Lazio' so it cannot
    -- perturb the district-filter tests.
    ('1 Empty Hub Road',    'Tasmania', 1);

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

-- R61 execution-tier fixture: inventory rows linked to films 1, 2, 3 (one row per film).
-- Used by GraphQLQueryTest.inventoryById_filmRef_resolvesViaExternalFieldReturningTableRecord
-- and the FilmCardWrapper sibling test exercising the AccessorKeyedSingle lift.
INSERT INTO inventory (inventory_id, film_id, store_id) VALUES
    (1, 1, 1),
    (2, 2, 1),
    (3, 3, 1);

-- content seed data: two FilmContent rows linked to films 1 and 2, two ShortContent rows.
-- short_description is populated only on SHORT rows so per-participant column isolation tests
-- can verify NULL on FILM rows.
INSERT INTO content (content_type, title, length, short_description, film_id) VALUES
    ('FILM',  'ACADEMY DINOSAUR (extended)',  120, NULL,                  1),
    ('FILM',  'ACE GOLDFINGER (extended)',     90, NULL,                  2),
    ('SHORT', 'Sunrise',                       12, 'Dawn over a city',    NULL),
    ('SHORT', 'Interlude',                      8, 'Quiet jazz piece',    NULL);

-- jti_subject seed data: two APP subjects (with app-account detail rows) and two PERSON subjects
-- (with person detail rows). The detail rows re-state subject_kind in the composite FK, exercising
-- the discriminator-ambiguity case at runtime. display_name is on the base table; client_id /
-- full_name live only on their detail tables and are reached via the composite @reference.
INSERT INTO jti_subject (subject_kind, display_name) VALUES
    ('APP',    'Billing service'),
    ('APP',    'Reporting service'),
    ('PERSON', 'Ada Lovelace'),
    ('PERSON', 'Alan Turing');

INSERT INTO jti_app_account (jti_subject_id, subject_kind, client_id) VALUES
    (1, 'APP', 'billing-client-001'),
    (2, 'APP', 'reporting-client-002');

INSERT INTO jti_person (jti_subject_id, subject_kind, full_name) VALUES
    (3, 'PERSON', 'Ada Lovelace (full)'),
    (4, 'PERSON', 'Alan Turing (full)');

-- party seed data (R389 single-column shared-PK fixture): one INDIVIDUAL and one COMPANY with
-- detail rows, plus an INDIVIDUAL base row (party_id 3) with NO matching party_individual detail
-- row, so the interface query's LEFT JOIN NULL-through is asserted rather than assumed.
INSERT INTO party (party_kind, display_name) VALUES
    ('INDIVIDUAL', 'Grace Hopper'),
    ('COMPANY',    'Sikt'),
    ('INDIVIDUAL', 'Detached Individual');

INSERT INTO party_individual (party_id, birth_date) VALUES
    (1, DATE '1906-12-09');

INSERT INTO party_company (party_id, org_number) VALUES
    (2, 'NO-919477822');

-- R36 item 1 fixture: composite-PK participants in @asConnection multi-table polymorphic.
-- paged_a + paged_b share a (Integer, Integer) composite PK shape so the polymorphic emitter
-- can project DSL.jsonbArray(k1, k2) as the synthetic __sort__ column and type it as JSONB;
-- PostgreSQL's lexicographic JSONB ordering reproduces the multi-column ordering and the
-- cursor encoder round-trips JSONB through JSONB.toString() + Convert.convert(String, JSONB.class).
-- Six rows total (3 + 3) is enough to paginate with default first: 3.
CREATE TABLE paged_a (
    k1   integer     NOT NULL,
    k2   integer     NOT NULL,
    name varchar(50) NOT NULL,
    PRIMARY KEY (k1, k2)
);
INSERT INTO paged_a (k1, k2, name) VALUES (1, 1, 'A-1-1'), (1, 2, 'A-1-2'), (2, 1, 'A-2-1');

CREATE TABLE paged_b (
    k1   integer     NOT NULL,
    k2   integer     NOT NULL,
    name varchar(50) NOT NULL,
    PRIMARY KEY (k1, k2)
);
INSERT INTO paged_b (k1, k2, name) VALUES (1, 1, 'B-1-1'), (1, 3, 'B-1-3'), (3, 1, 'B-3-1');

-- R36 item 2 fixture: composite-PK parent for child interface @asConnection. Sakila has no
-- junction-as-parent shape (film_actor and film_category are pure junctions with no children
-- FK-referencing them), so we synthesise one. project (org_id, project_id) is the composite-PK
-- parent; project_note and project_event are single-PK children referencing it via a composite
-- FK. Exercises the B4c-2 RowN widening end-to-end (DataLoader key element widens to
-- Row2<Integer, Integer>; parentInput VALUES widens to Row3<Integer, Integer, Integer>;
-- batchedBranchJoinPredicate emits an AND-chain over (org_id, project_id)) against PostgreSQL.
CREATE TABLE project (
    org_id     integer     NOT NULL,
    project_id integer     NOT NULL,
    name       varchar(50) NOT NULL,
    PRIMARY KEY (org_id, project_id)
);
INSERT INTO project (org_id, project_id, name) VALUES
    (1, 100, 'Atlas'), (1, 101, 'Beacon'), (2, 100, 'Cipher');

CREATE TABLE project_note (
    note_id    serial      PRIMARY KEY,
    org_id     integer     NOT NULL,
    project_id integer     NOT NULL,
    body       varchar(50) NOT NULL,
    CONSTRAINT project_note_project_fkey FOREIGN KEY (org_id, project_id)
        REFERENCES project(org_id, project_id)
);
INSERT INTO project_note (org_id, project_id, body) VALUES
    (1, 100, 'Atlas-N1'), (1, 100, 'Atlas-N2'), (1, 100, 'Atlas-N3'),
    (1, 101, 'Beacon-N1'), (1, 101, 'Beacon-N2'),
    (2, 100, 'Cipher-N1');

CREATE TABLE project_event (
    event_id   serial      PRIMARY KEY,
    org_id     integer     NOT NULL,
    project_id integer     NOT NULL,
    summary    varchar(50) NOT NULL,
    CONSTRAINT project_event_project_fkey FOREIGN KEY (org_id, project_id)
        REFERENCES project(org_id, project_id)
);
INSERT INTO project_event (org_id, project_id, summary) VALUES
    (1, 100, 'Atlas-E1'),
    (1, 101, 'Beacon-E1'), (1, 101, 'Beacon-E2'),
    (2, 100, 'Cipher-E1');

-- R130 single-PK execution-tier fixture: client-supplied varchar PK (not SERIAL),
-- letting the SDL expose `id: ID! @nodeId` as a writable INSERT column. Pairs with
-- `Mutation.createKeyedNode` to exercise the ColumnField(NodeIdDecodeKeys) INSERT-arm
-- shape end-to-end (one decode local lifted to preGuard, one cell read from
-- `__insertKey_<fi>.value1()` in the values list). No seed rows — every test seeds
-- and cleans up its own.
CREATE TABLE keyed_node (
    id    varchar(64) PRIMARY KEY,
    label varchar(64) NOT NULL
);

-- R266 execution-tier fixture: the public-schema table whose UNIQUE constraint is distinct
-- from its primary key. A DELETE keyed on the unique (non-PK) `code` column drives the
-- DeleteRowsWalker's PK-or-UK match onto the UniqueKey arm end-to-end (every other Sakila
-- DELETE fixture covers the PK, so the UK arm had no execution proof). `bin_id` is the
-- surrogate PK the @node NodeId encodes for the `deleteStorageBinByCode` `: ID` return; the
-- emitted statement filters `WHERE code = ?` while `RETURNING` projects `bin_id`, so the row
-- is identified by the UK and the returned NodeId encodes the matched PK. No seed rows — the
-- execution test seeds and cleans up its own.
CREATE TABLE storage_bin (
    bin_id  serial      PRIMARY KEY,
    code    varchar(64) NOT NULL UNIQUE,
    label   varchar(64) NOT NULL
);

-- R338 execution-tier fixture: a parent referenced through a *non-PK unique key*, plus a child
-- whose FK targets that unique column (split_parent_tag.parent_code -> split_parent.parent_code,
-- the UNIQUE column, not the parent_id PK). A @splitQuery list field on the parent must project
-- the FK's referenced column (parent_code) into parentInput; keying off the parent PK made the
-- emitted correlation predicate reference an absent parentInput column, jOOQ resolved it to NULL,
-- and every parent silently returned zero rows. See split-query-non-pk-fk-referenced-column.md
-- (R338). The seed gives ALPHA two tags and BETA one so the execution test asserts the child rows
-- come back (not an empty list) and scatter per parent by the unique-key value, not the PK.
CREATE TABLE split_parent (
    parent_id   integer     PRIMARY KEY,
    parent_code varchar(64) NOT NULL UNIQUE,
    label       varchar(64) NOT NULL
);

CREATE TABLE split_parent_tag (
    tag_id      integer     PRIMARY KEY,
    parent_code varchar(64) NOT NULL REFERENCES split_parent(parent_code),
    tag         varchar(64) NOT NULL
);

INSERT INTO split_parent (parent_id, parent_code, label) VALUES
    (1, 'ALPHA', 'Alpha parent'),
    (2, 'BETA',  'Beta parent');

INSERT INTO split_parent_tag (tag_id, parent_code, tag) VALUES
    (1, 'ALPHA', 'a-one'),
    (2, 'ALPHA', 'a-two'),
    (3, 'BETA',  'b-one');

-- R450 execution-tier fixture: the grain proof for a hop-0 {key:, condition:} filter on a
-- single-cardinality @splitQuery. Two parents point at the SAME target row (shared FK-slot value
-- target_id=1) but carry different values in a parent-only column (include). A hop-0 filter reads
-- that parent column, so it passes for one parent and fails for the other. A slot-keyed batch (the
-- pre-R450 OnFkSlots grain) collapses both parents onto one key (target_id=1) and hands them one
-- shared verdict; parent-PK keying (OnParentJoin) distinguishes them. The filter method is typed
-- with the concrete parent/target jOOQ classes, so the pre-R450 emitter (which bound the hop-0
-- target alias as both filter parameters) additionally failed compile-spec with incompatible types.
CREATE TABLE split_filter_target (
    target_id integer     PRIMARY KEY,
    label     varchar(64) NOT NULL
);

CREATE TABLE split_filter_parent (
    parent_id integer PRIMARY KEY,
    target_id integer NOT NULL REFERENCES split_filter_target(target_id),
    include   boolean NOT NULL
);

INSERT INTO split_filter_target (target_id, label) VALUES (1, 'shared-target');

INSERT INTO split_filter_parent (parent_id, target_id, include) VALUES
    (1, 1, true),    -- passes the hop-0 filter → resolves the target
    (2, 1, false);   -- fails the hop-0 filter → resolves null, though it shares target_id=1

-- R300 routine fixture: the driving table-valued read function. A side-effect-free
-- PostgreSQL function with three TEXT IN parameters and RETURNS TABLE(...), the shape
-- @routine binds day-one. jOOQ generates this as a table-valued-function Table class in
-- the public-schema `tables` package (the .call(args) surface attached in FROM). The body
-- is deterministic (a fixed VALUES set, guarded on a non-null arg) so the execution-tier
-- test can assert exact rows come back with the IN params bound from GraphQL arguments.
CREATE OR REPLACE FUNCTION public.tilganger_for_feidebruker_med_fs_fiktivt_fnr(
    p_env        TEXT,
    p_service_id TEXT,
    p_feide_id   TEXT
) RETURNS TABLE(organisasjonskode INTEGER, rollekode TEXT)
LANGUAGE sql STABLE
AS $$
    SELECT t.organisasjonskode, t.rollekode
    FROM (VALUES (184, 'admin'), (185, 'user')) AS t(organisasjonskode, rollekode)
    WHERE p_feide_id IS NOT NULL
$$;

-- R435 routine fixture: the correlated table-valued read function backing a child-positioned
-- @routine (the films_for_actor(actor_id) shape named in the spec). p_actor_id is fed from the
-- parent row's actor_id column (columnMapping), p_min_length from a GraphQL argument
-- (argMapping) — the mixed call exercises the generated Field-overload surface. Reads the real
-- film / film_actor seed rows so the execution test asserts per-parent correlation under the
-- inline multiset emit.
CREATE OR REPLACE FUNCTION public.films_for_actor(
    p_actor_id   INTEGER,
    p_min_length INTEGER
) RETURNS TABLE(film_id INTEGER, title TEXT)
LANGUAGE sql STABLE
AS $$
    SELECT f.film_id, f.title
    FROM film f
    JOIN film_actor fa ON fa.film_id = f.film_id
    WHERE fa.actor_id = p_actor_id
      AND f.length >= p_min_length
    ORDER BY f.film_id
$$;

-- R451 routine fixture: the VOLATILE set-returning write function backing @routine on a
-- Mutation field (the routine call IS the write and commits before the follow-up query).
-- Inserts a rental row and returns its generated key as a one-row table, the shape the
-- write fetcher's step 1 captures inside the per-field transaction; the chained
-- @reference hop then re-reads the committed row from the rental table post-commit.
-- staff_id is pinned to the seeded Mike (staff_id 1) so the call needs only the two
-- domain-meaningful IN parameters.
CREATE OR REPLACE FUNCTION public.rent_film(
    p_inventory_id INTEGER,
    p_customer_id  INTEGER
) RETURNS TABLE(rental_id INTEGER)
LANGUAGE sql VOLATILE
AS $$
    INSERT INTO rental (rental_date, inventory_id, customer_id, staff_id)
    VALUES (now(), p_inventory_id, p_customer_id, 1)
    RETURNING rental.rental_id
$$;

-- R451 routine fixture: a scalar (non-table-valued) function pinning JooqCatalog's
-- exists-but-not-table-valued resolution arm. jOOQ places it in the generated `routines`
-- package (not `tables`), so @routine naming it must land the typed Deferred routed off
-- RoutineResolution.NonTableValuedRoutine rather than the absent-name rejection a typo gets.
CREATE OR REPLACE FUNCTION public.rental_count_for_customer(
    p_customer_id INTEGER
) RETURNS INTEGER
LANGUAGE sql STABLE
AS $$
    SELECT count(*)::int FROM rental WHERE customer_id = p_customer_id
$$;

-- R328 fixture: self-FK @nodeId reference on a Graphitron-owned DML input — the neutral
-- `email` / `mailbox` form of the CAMPUS self-FK case. `email` has a composite PK
-- (mailbox_id, message_no). Two foreign keys share the `mailbox_id` child column:
--   * cross-table  `email.mailbox_id -> mailbox(mailbox_id)`        (admitted since R189)
--   * self-FK      `email_in_reply_to_fk (mailbox_id, in_reply_to_no)
--                       -> email(mailbox_id, message_no)`          (this item)
-- An INSERT input that writes mailbox_id via a cross-table @nodeId(typeName:"Mailbox") AND
-- via a self-FK @nodeId(typeName:"Email") @reference exercises R322's per-column dedup +
-- value-agreement on the shared mailbox_id (a reply lives in its parent's mailbox, so the two
-- writers must agree). MATCH SIMPLE means a NULL in_reply_to_no skips the self-FK check, so a
-- root email needs no parent. Seeds one mailbox + one root email per owner so inserted replies
-- satisfy both FKs; the execution test inserts replies with distinct message_no and cleans up.
CREATE TABLE mailbox (
    mailbox_id int PRIMARY KEY,
    owner_name varchar(50)
);

CREATE TABLE email (
    mailbox_id     int NOT NULL REFERENCES mailbox(mailbox_id),
    message_no     int NOT NULL,
    in_reply_to_no int,
    subject        varchar(100),
    PRIMARY KEY (mailbox_id, message_no),
    CONSTRAINT email_in_reply_to_fk
        FOREIGN KEY (mailbox_id, in_reply_to_no)
        REFERENCES email (mailbox_id, message_no)
);

INSERT INTO mailbox (mailbox_id, owner_name) VALUES (5, 'alice'), (9, 'bob');
INSERT INTO email (mailbox_id, message_no, in_reply_to_no, subject) VALUES
    (5, 1, NULL, 'root-in-5'),
    (9, 1, NULL, 'root-in-9');

-- ===========================
-- R413 converter-domain fixture
-- ===========================
--
-- Mirrors the utdanningsregisteret shape that exposed R413: a numeric domain over BIGINT whose
-- jOOQ column carries a Converter<Long, String> (registered via <forcedTypes> on the public-schema
-- codegen execution; converter class OrgCodeStringConverter in graphitron-fixtures-codegen). A
-- parent/child pair keyed on the domain lets the execution tier prove the DataLoader parent-input
-- VALUES cells bind through the Converter: an untyped bind renders the converted user type
-- (character varying), and PostgreSQL has no <bigint domain> = varchar operator, so the pre-R413
-- correlation JOIN failed with "operator does not exist: org_code_domain = character varying".
CREATE DOMAIN org_code_domain AS bigint;

CREATE TABLE converter_org (
    org_code  org_code_domain PRIMARY KEY,
    org_name  varchar(50) NOT NULL
);

CREATE TABLE converter_campus (
    campus_id    serial      PRIMARY KEY,
    campus_name  varchar(50) NOT NULL,
    org_code     org_code_domain NOT NULL REFERENCES converter_org(org_code)
);

INSERT INTO converter_org (org_code, org_name) VALUES (186, 'UiT'), (1120, 'NTNU');
INSERT INTO converter_campus (campus_id, campus_name, org_code) VALUES
    (1, 'Tromsø',    186),
    (2, 'Trondheim', 1120),
    (3, 'Gjøvik',    1120);

-- R446 array-column fixture: a table carrying array-typed columns. jOOQ maps a PostgreSQL
-- `boolean[]` to `Field<Boolean[]>`, whose `getType().getName()` is the JVM binary descriptor
-- `[Ljava.lang.Boolean;` (not a source-form FQCN), which `ClassName.bestGuess` rejects. Any code
-- generation that reconstructs this table's full row per column, notably the
-- `SourceKey.Wrap.TableRecord` key-extraction arm, crashed with
-- `IllegalArgumentException: couldn't make a guess for [Ljava.lang.Boolean;` before the type-lift.
-- `id` is the scalar PK a TableRecord-keyed @service child batches on; `flags` / `tags` are the
-- single-dimension array columns; `label` is a scalar control column.
CREATE TABLE array_holder (
    id     serial      PRIMARY KEY,
    flags  boolean[]   NOT NULL DEFAULT '{}',
    tags   text[],
    label  varchar(50)
);

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

-- R377 fixture: single-key NodeType table whose __NODE_TYPE_ID ("10154") is a customized numeric
-- value distinct from any GraphQL type name. Used by NodeIdPipelineTest's R377 case to pin that the
-- decode helper for a @node + nesting-projection shape over one table resolves through the
-- @node-only NodeIndex (decode<TypeName>), not the typeId fallback (decode10154). The `label`
-- column gives a nesting-projection @table type a column to map so two object types can back the
-- same table.
CREATE TABLE nodeidfixture.shared_node (
    id    varchar(50) PRIMARY KEY,
    label varchar(50)
);

-- R50 phase (g-B) rooted-at-parent fixture. Single-key NodeType `parent_node` whose
-- __NODE_KEY_COLUMNS pin the encode/decode key as `pk_id`, plus a child table whose FK
-- targets parent's *alternate* unique column `alt_key`. The FK column does not positionally
-- match the parent NodeType's keyColumn — encoding `child_ref.parent`'s NodeId from the
-- child row alone is impossible, so the rooted-at-parent JOIN-with-projection path must
-- read `parent_node.pk_id` through a join. See lift-nodeid-out-of-model.md "Fixture growth".
CREATE TABLE nodeidfixture.parent_node (
    pk_id   varchar(50) PRIMARY KEY,
    alt_key varchar(50) NOT NULL UNIQUE,
    name    varchar(50)
);

CREATE TABLE nodeidfixture.child_ref (
    child_id        varchar(50) PRIMARY KEY,
    parent_alt_key  varchar(50) NOT NULL REFERENCES nodeidfixture.parent_node(alt_key),
    note            varchar(50)
);

-- 23-column-PK NodeType for the R79 §6 arity > 22 rejection test. jOOQ's typed
-- Record/Row tops out at Row22, so a NodeType with > 22 key columns must be
-- rejected at classification time by NodeIdLeafResolver.resolve. This fixture is
-- deliberately the smallest > 22 case (23 keys); changing the column list requires
-- a matching update in NodeIdFixtureGenerator.METADATA so the synthetic
-- __NODE_TYPE_ID / __NODE_KEY_COLUMNS metadata stays consistent.
CREATE TABLE nodeidfixture.too_wide (
    k1  varchar(20) NOT NULL, k2  varchar(20) NOT NULL, k3  varchar(20) NOT NULL,
    k4  varchar(20) NOT NULL, k5  varchar(20) NOT NULL, k6  varchar(20) NOT NULL,
    k7  varchar(20) NOT NULL, k8  varchar(20) NOT NULL, k9  varchar(20) NOT NULL,
    k10 varchar(20) NOT NULL, k11 varchar(20) NOT NULL, k12 varchar(20) NOT NULL,
    k13 varchar(20) NOT NULL, k14 varchar(20) NOT NULL, k15 varchar(20) NOT NULL,
    k16 varchar(20) NOT NULL, k17 varchar(20) NOT NULL, k18 varchar(20) NOT NULL,
    k19 varchar(20) NOT NULL, k20 varchar(20) NOT NULL, k21 varchar(20) NOT NULL,
    k22 varchar(20) NOT NULL, k23 varchar(20) NOT NULL,
    PRIMARY KEY (k1, k2, k3, k4, k5, k6, k7, k8, k9, k10, k11, k12,
                 k13, k14, k15, k16, k17, k18, k19, k20, k21, k22, k23)
);

-- Reordered-FK fixture: composite FK whose own ordered referenced-column list
-- (the third TableField[] arg jOOQ codegen passes to Internal.createForeignKey)
-- differs from the parent's PRIMARY KEY declaration order. jOOQ's
-- ForeignKey.getKeyFields() returns the FK's own list (parallel to getFields());
-- ForeignKey.getKey().getFields() returns the referenced UniqueKey's own list
-- (PK declaration order). The two CAN differ — and do here. Heterogeneous types
-- (bigint + two varchar) make any positional pairing of the two non-parallel
-- lists a Field<Long>↔Field<String> compile-time mismatch in emitted SQL.
--
-- Schema shape mirrors the failing downstream case (Kompetansekrav →
-- Kompetansekrav_grunnlag composite FK with reordered referenced columns):
--   reordered_pk_parent declares its PK in order (pk_a bigint, pk_b varchar,
--     pk_c varchar);
--   reordered_fk_child's FOREIGN KEY clause references the parent's PK columns
--     in REVERSED order (pk_b, pk_c, pk_a), so the FK's own referenced-column
--     list is (pk_b, pk_c, pk_a) while getKey().getFields() returns
--     (pk_a, pk_b, pk_c).
CREATE TABLE nodeidfixture.reordered_pk_parent (
    pk_a bigint      NOT NULL,
    pk_b varchar(50) NOT NULL,
    pk_c varchar(50) NOT NULL,
    PRIMARY KEY (pk_a, pk_b, pk_c)
);

CREATE TABLE nodeidfixture.reordered_fk_child (
    child_id varchar(50) PRIMARY KEY,
    fk_b     varchar(50) NOT NULL,
    fk_c     varchar(50) NOT NULL,
    fk_a     bigint      NOT NULL,
    CONSTRAINT reordered_fk_child_parent_fkey
        FOREIGN KEY (fk_b, fk_c, fk_a)
        REFERENCES nodeidfixture.reordered_pk_parent (pk_b, pk_c, pk_a)
);

-- R114 multi-hop @reference on @nodeId, identity-carrying lift fixture.
-- Three-table chain modeled on the user's sak / soknad / opptak example: each FK
-- preserves the next step's source-side columns positionally by SQL name, so the
-- terminal hop's source-side tuple lifts back through the chain to a sub-tuple of
-- the first hop's source-side columns on the parent table.
--
--   level_a  (PK (k1, k2))                        — the NodeType target.
--   level_b  (PK (s, k1, k2),  FK to level_a on (k1, k2))
--   level_c  (PK (c, s, k1, k2), FK to level_b on (s, k1, k2))
--
-- Filtering level_c by levelAId (a NodeId encoding (k1, k2)) traverses the chain
-- level_c -> level_b -> level_a and lifts back to level_c.(k1, k2). Both adjacent
-- pairs satisfy the lift predicate (hop[1].sourceSide ⊂ hop[0].targetSide by SQL
-- name), so the emitted SQL is a single-table predicate on level_c.(k1, k2) —
-- no JOIN, no subquery — identical to single-hop direct-FK shape.
CREATE TABLE nodeidfixture.level_a (
    k1   varchar(20) NOT NULL,
    k2   varchar(20) NOT NULL,
    name varchar(50),
    PRIMARY KEY (k1, k2)
);

CREATE TABLE nodeidfixture.level_b (
    s    varchar(20) NOT NULL,
    k1   varchar(20) NOT NULL,
    k2   varchar(20) NOT NULL,
    name varchar(50),
    PRIMARY KEY (s, k1, k2),
    CONSTRAINT level_b_level_a_fk
        FOREIGN KEY (k1, k2) REFERENCES nodeidfixture.level_a (k1, k2)
);

CREATE TABLE nodeidfixture.level_c (
    c    varchar(20) NOT NULL,
    s    varchar(20) NOT NULL,
    k1   varchar(20) NOT NULL,
    k2   varchar(20) NOT NULL,
    name varchar(50),
    PRIMARY KEY (c, s, k1, k2),
    CONSTRAINT level_c_level_b_fk
        FOREIGN KEY (s, k1, k2) REFERENCES nodeidfixture.level_b (s, k1, k2)
);

-- Seed data for the R114 execution test: one level_a row, one level_b under it,
-- two level_c rows under that level_b plus a sibling under a different level_b.
INSERT INTO nodeidfixture.level_a (k1, k2, name) VALUES
    ('A1', 'A2', 'levelA-target'),
    ('Z1', 'Z2', 'levelA-other');

INSERT INTO nodeidfixture.level_b (s, k1, k2, name) VALUES
    ('S1', 'A1', 'A2', 'levelB-under-target'),
    ('S2', 'A1', 'A2', 'levelB-under-target-2'),
    ('S3', 'Z1', 'Z2', 'levelB-under-other');

INSERT INTO nodeidfixture.level_c (c, s, k1, k2, name) VALUES
    ('C1', 'S1', 'A1', 'A2', 'levelC-1'),
    ('C2', 'S1', 'A1', 'A2', 'levelC-2'),
    ('C3', 'S2', 'A1', 'A2', 'levelC-3'),
    ('C4', 'S3', 'Z1', 'Z2', 'levelC-other');

-- R114 lift-failure (translation) fixture. Same structure as the level_a/b/c chain
-- but with intermediate hops that drop key columns the next hop needs, so the lift
-- predicate fails. Used by NodeIdLeafResolverTest.MULTI_HOP_LIFT_TRANSLATION_REJECTED
-- and the parallel pipeline-tier case.
--
--   trans_a  (PK (a))                       — NodeType target with single key 'a'.
--   trans_b  (PK b, alt_a; FK to trans_a on alt_a -> a)        — hop1 target list = (b, alt_a).
--   trans_c  (PK c; FK to trans_b on b)     — hop2.sourceSide = (b); hop1.target = (b, alt_a)
--                                              by SQL name: 'b' is in hop1.target ✓ — but
--                                              the terminal hop's targetSide is (b), which
--                                              does NOT positionally match trans_a's keys (a),
--                                              so this routes to TranslatedFk, NOT a lift
--                                              failure. To force lift failure we use a
--                                              different shape: see lift_fail_a/b/c below.
--
-- For genuine lift failure: hop1.target list does NOT contain hop2.source by name.
CREATE TABLE nodeidfixture.lift_fail_a (
    k1   varchar(20) NOT NULL,
    k2   varchar(20) NOT NULL,
    PRIMARY KEY (k1, k2)
);

CREATE TABLE nodeidfixture.lift_fail_b (
    b_id varchar(20) PRIMARY KEY,
    a_k1 varchar(20) NOT NULL,
    a_k2 varchar(20) NOT NULL,
    CONSTRAINT lift_fail_b_a_fk
        FOREIGN KEY (a_k1, a_k2) REFERENCES nodeidfixture.lift_fail_a (k1, k2)
);

-- lift_fail_c FKs to lift_fail_b on (b_id), but lift_fail_b -> lift_fail_a uses
-- (a_k1, a_k2) as the FK source — neither 'a_k1' nor 'a_k2' appears in lift_fail_b's
-- targetSide list when reached from c (which is just 'b_id'). The lift predicate at
-- hop[1] requires hop[1].sourceSide ('a_k1', 'a_k2') ⊂ hop[0].targetSide ('b_id') by
-- SQL name — fails on both columns. Test asserts the rejection.
CREATE TABLE nodeidfixture.lift_fail_c (
    c_id   varchar(20) PRIMARY KEY,
    fk_b   varchar(20) NOT NULL,
    CONSTRAINT lift_fail_c_b_fk
        FOREIGN KEY (fk_b) REFERENCES nodeidfixture.lift_fail_b (b_id)
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

-- ===========================
-- multischema fixture: two schemas exercising R78 multi-schema correctness.
-- ===========================
--
-- Two schemas with overlapping table names ('event' in both) and a cross-
-- schema FK (multischema_b.gadget -> multischema_a.widget). Used by R78
-- pipeline + compilation tests for FQN emission and resolution-time
-- disambiguation. Generated by a single jOOQ-codegen execution that omits
-- <inputSchema> so jOOQ produces a per-schema sub-package layout
-- (<root>.multischema_a.tables.X, <root>.multischema_b.tables.X). This is
-- the layout that all Sikt consumer projects with multiple schemas hit and
-- that the existing single-schema-per-execution fixtures don't reproduce.

CREATE SCHEMA multischema_a;
CREATE SCHEMA multischema_b;

-- Schema A: 'widget' is unique to A (exercises unqualified-and-unique
-- @table resolution); 'event' collides with B (exercises qualified-only
-- resolution and the structured ambiguity rejection on unqualified usage).

CREATE TABLE multischema_a.widget (
    widget_id  serial      PRIMARY KEY,
    name       varchar(50) NOT NULL
);

CREATE TABLE multischema_a.event (
    event_id   serial      PRIMARY KEY,
    name       varchar(50) NOT NULL
);

-- R442 pipeline fixture: an additive, uniquely-named table holding an in-schema FK into
-- multischema_a.event. The 'event' bare name collides across multischema_a / multischema_b, so a
-- concrete condition-method table parameter typed multischema_a.tables.Event vs
-- multischema_b.tables.Event can only be told apart by jOOQ class identity, not the bare-vs-qualified
-- name string that checkConcreteParamTable used before R442. This backs where-filter *source*-operand
-- coverage: a {table: "event_log", condition:} hop off a parent @table(name: "multischema_a.event")
-- carries the qualified parent echo on the source operand. (Target-position coverage rides a terminal
-- condition hop instead; the where-filter FK-endpoint path resolves 'event' by bare name and is
-- ambiguous, which is R440's scope, not R442's.)
CREATE TABLE multischema_a.event_log (
    event_log_id  serial      PRIMARY KEY,
    event_id      int         NOT NULL REFERENCES multischema_a.event(event_id),
    note          varchar(50)
);

-- R395 execution-tier fixture: a single-table discriminated interface living in a NAMED schema
-- (multischema_a) with a lowercase real name. The discriminator column signal_kind routes rows to
-- two concrete types; widget_id is an in-schema FK into multischema_a.widget so one participant can
-- carry a cross-table @reference, exercising the LEFT JOIN ON-clause discriminator gate under a
-- non-default schema. The SDL spells @table(name: "signal") (unqualified, lowercase); 'signal' is
-- unique to multischema_a, so it resolves there, yet jOOQ renders the FROM token schema-qualified as
-- "multischema_a"."signal". A discriminator qualifier built from the bare directive string emits
-- "signal"."signal_kind" and omits the schema, so it never matches FROM; before R395 Postgres
-- rejected the query with "missing FROM-clause entry". This pins the schema-qualification dimension;
-- the uppercase/case-mismatch dimension is pinned at the unit tier (TypeFetcherGeneratorTest's
-- INTERFACE_BASE fixture), not here.
CREATE TABLE multischema_a.signal (
    signal_id    serial      PRIMARY KEY,
    signal_kind  varchar(20) NOT NULL,
    label        varchar(50) NOT NULL,
    widget_id    int         REFERENCES multischema_a.widget(widget_id)
);

-- Schema B: 'gadget' is unique to B; its widget_id is a cross-schema FK
-- into multischema_a.widget. The FK constraint is held on multischema_b
-- (jOOQ exposes this as ForeignKey.getTable().getSchema() == multischema_b),
-- so the @reference Keys-class lookup must route to multischema_b.Keys —
-- not the FK target's schema. 'event' here collides with multischema_a.event.

CREATE TABLE multischema_b.gadget (
    gadget_id  serial      PRIMARY KEY,
    widget_id  int         NOT NULL REFERENCES multischema_a.widget(widget_id),
    note       varchar(50)
);

CREATE TABLE multischema_b.event (
    event_id   serial      PRIMARY KEY,
    code       varchar(50) NOT NULL
);

-- R440: a 'note' table in BOTH schemas, each with an FK explicitly named 'note_event_fk' into its
-- OWN schema's 'event'. This yields the two collisions the earlier fixture lacked: (1) the FK
-- target is 'event', a bare table name that collides across schemas and is reached via FK
-- synthesis, and (2) the FK CONSTRAINT name 'note_event_fk' is identical in both schemas. Postgres
-- constraint names are schema-scoped, so the duplicate name is legal. Before R440 the bare-name
-- endpoint lookup returned Ambiguous (join fails) and the bare-name FK lookup silently returned the
-- first schema's FK (wrong-join hazard). Class-identity / reference-identity resolution fixes both.
CREATE TABLE multischema_a.note (
    note_id    serial      PRIMARY KEY,
    event_id   int,
    body       varchar(50) NOT NULL,
    CONSTRAINT note_event_fk FOREIGN KEY (event_id) REFERENCES multischema_a.event(event_id)
);

CREATE TABLE multischema_b.note (
    note_id    serial      PRIMARY KEY,
    event_id   int,
    body       varchar(50) NOT NULL,
    CONSTRAINT note_event_fk FOREIGN KEY (event_id) REFERENCES multischema_b.event(event_id)
);

-- R83 execution-tier seed: minimal rows so a query that traverses the cross-schema
-- FK (gadget -> widget) round-trips end-to-end. Keeps the dataset small enough
-- that pagination / ordering concerns stay out of scope: one widget, two gadgets
-- pointing at it, plus one row in each event collision table to prove qualified
-- @table resolution serves real data.
INSERT INTO multischema_a.widget (widget_id, name) VALUES (1, 'alpha-widget');
INSERT INTO multischema_a.event  (event_id,  name) VALUES (10, 'launch-a');
INSERT INTO multischema_b.event  (event_id,  code) VALUES (20, 'B-001');
INSERT INTO multischema_b.gadget (gadget_id, widget_id, note) VALUES (100, 1, 'first-gadget');
INSERT INTO multischema_b.gadget (gadget_id, widget_id, note) VALUES (101, 1, 'second-gadget');

-- R440: one note row per schema so the colliding-FK fixture can round-trip at the execution tier.
INSERT INTO multischema_a.note (note_id, event_id, body) VALUES (200, 10, 'note-a');
INSERT INTO multischema_b.note (note_id, event_id, body) VALUES (300, 20, 'note-b');

-- R395 seed: two ALERT rows (one of which a NoticeSignal does not match) and one NOTICE row, with
-- the ALERT rows carrying an FK to the seeded widget so the cross-table @reference resolves.
INSERT INTO multischema_a.signal (signal_id, signal_kind, label, widget_id) VALUES (1, 'ALERT',  'disk-full', 1);
INSERT INTO multischema_a.signal (signal_id, signal_kind, label, widget_id) VALUES (2, 'NOTICE', 'login',     NULL);
INSERT INTO multischema_a.signal (signal_id, signal_kind, label, widget_id) VALUES (3, 'ALERT',  'cpu-high',  1);
