---
id: R595
title: "The graphitron-model module exists and capture fills it"
status: Spec
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-05
last-updated: 2026-08-05
---

# The graphitron-model module exists and capture fills it

The fact-base architecture R589 (`validation-adds-facts`) arrived at needs its substrate: a new
reactor module, `graphitron-model`, holding the fact-schema DDL (the umbrella's normalised data
model reified as SQL; R333), jOOQ codegen over it, and an H2 in-memory bootstrap. On top of the
module, two infallible capture loads run beside the existing pipeline and change no behavior:
the SDL visitor records existence and application facts, and the jOOQ and classpath scans record
the catalog and extension facts. Nobody reads the store yet. Agreement tests are the shadow
period's honesty check and retire as consumers migrate off `GraphitronSchema` piece by piece
(the strangler frame recorded in R589); while both models are live, new facts land only in the
store. The spike grounding the stack choice is `roadmap/audits/2026-08-05-fact-base-h2-spike.md`.

The main delivery of this spec is the target model itself: the first iteration of the fact
schema below. The module and the loads exist to make that schema real, compiled against, and
kept honest by tests.

## The module

`graphitron-model` is a jar module listed before `graphitron` in the root pom, so the reactor
builds it first and core depends on its artifact. It contains one source of truth, the fact-schema
DDL, as a single SQL resource (`src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`),
and two things generated from it:

- **Compile-time surface.** `jooq-codegen-maven` runs `org.jooq.meta.extensions.ddl.DDLDatabase`
  over the DDL resource: no live database at build time, the `graphitron-sakila-db` shape made
  hermetic. Generated classes land in `target/generated-sources/jooq` under package
  `no.sikt.graphitron.model` and are never committed; the DDL is the single source. Because the
  module builds before core, editing the DDL fails javac in every consumer that touched the
  changed relation, and with no persisted state anywhere, compile-time is the schema's only
  compatibility surface. Changing the model is editing the DDL and following the compiler.
- **Run-time store.** A small bootstrap entry point opens a fresh H2 in-memory database,
  registers the module's function aliases, executes the same DDL resource, and hands back a
  jOOQ `DSLContext` over it. One database per generator run, created at startup, populated by
  capture, dead with the process. No migrations exist because no persisted state exists.

The model's Java functions live in this module too. H2 functions are `CREATE ALIAS` bindings
to static methods; jOOQ's open-source parser keeps `CREATE ALIAS` out of the codegen DDL, and
H2 refuses to create a view over an unregistered function, so the bootstrap registers aliases
before the DDL runs, which is only possible when the methods ship with the module. That makes
the store whole from this module alone: any process that boots it gets working views without
core on the classpath, and the module carries a graphql-java dependency for the literal
decoders. No functions ship in this increment; the first bridge decoders arrive with the
derivations that need them. The spike record grounding all of this is
`roadmap/audits/2026-08-05-h2-functions-jooq-spike.md`.

Mechanical ride-alongs: the root pom module list, the module enumeration in CLAUDE.md and
`docs/architecture/reference/modules.adoc` (the `check-module-enumeration` gate), and the H2
version pinned in the root pom (H2 serves both `DDLDatabase` parsing at build time and the
in-memory store at run time).

## Schema conventions

These bind the DDL below and every relation added to it later.

- **snake_case throughout**, tables and columns alike. Table names are singular nouns naming the
  fact one row states.
- **Natural, composite, identity-carrying keys.** A row's primary key is the coordinate of the
  fact it states, so a key value alone tells you which fact you are looking at; no surrogate ids
  anywhere. The resulting keys get wide (a directive argument on a repeated field-directive
  application carries a five-part key), and that is accepted: jOOQ's path-based and on-key joins
  over the declared foreign keys absorb the width at query sites, so the unwieldiness stays in
  the DDL where it documents identity instead of leaking into every query.
- **Every table and every column is commented.** The shipped DDL states them as `COMMENT ON`
  clauses so they land in `INFORMATION_SCHEMA` and jOOQ carries them into the generated classes'
  Javadoc; the schema is then self-describing at both the SQL prompt and the call site. A gate
  test queries `INFORMATION_SCHEMA` and fails on any uncommented table or column. (The DDL below
  uses inline `--` comments for reviewability; the shipped file expresses the same text as
  `COMMENT ON`.)
- **Closed taxonomies are `CHECK` constraints**, so the schema itself rejects a kind value the
  model does not know.
- **`VARCHAR` is unbounded.** Length limits tune storage; this store has none, and semantics
  live in keys and constraints, not in column widths.
- **Capture stores what the author wrote.** A directive argument the author omitted is an absent
  row, not a default-filled one; defaults are derivable by joining the definition's
  `default_value_sdl`. Structured values (reference paths, error handlers) are captured as the
  rendered SDL literal; decoding them into slot facts is derivation and lands with consumers.
- **Source order is a captured fact.** Where declaration order is meaningful (fields, arguments,
  enum values, union members, key and index columns) it is an explicit ordinal column, so an
  `ORDER BY` reproduces it; iteration order is never load-bearing, per the determinism rule R589
  fixes at the emission boundary.
- **Only values are stored**: strings, booleans, integers. This mirrors the documented
  `CatalogFacts` invariant (never a live `Table<?>`, `ForeignKey`, or `Class<?>`, because the
  codegen classloader closes per pass); a SQL store enforces it structurally.
- **Decode at capture exactly when the decode needs parse-boundary knowledge SQL cannot
  express** (the graphql-java AST, a JVM descriptor); anything computable as a query over
  captured columns is derivation and stays out. This rule is why the type-wrapping decode and
  `returns_condition` are columns while reference-path and error-handler decoding are not.
- **Constraint violations are generator bugs, never author errors.** Every key and `CHECK`
  below ranges over a domain capture controls (closed classfile forms, graphql-java's kind
  vocabulary) or an identity graphql-java guarantees unique in an assembled schema (it rejects
  a repeated non-repeatable directive before capture runs). An author mistake becomes a
  diagnostic row in the derived stratum; it never surfaces as a constraint violation here.
  Cross-relation invariants plain DDL cannot state (at most one primary key per table, defaults
  only on input-object fields, ordinal zero unless repeatable) get gate queries as their named
  enforcers, siblings of the comment-coverage gate.

## The fact schema, first iteration

Base relations only: what the two capture loads fill. The derived stratum (claims, reachability,
demand, occurrence paths, diagnostics, commands) is deliberately absent; see the leave-outs
section. Three families, prefixed by origin: `graphql_` and `applied_` for SDL facts,
`catalog_` for jOOQ catalog facts, `extension_` for the consumer's compiled extension classes.

Two representation choices up front. First, output fields and input-object fields share one
table: a field's identity is `(type_name, field_name)` in both cases and the owning type's
`kind` distinguishes them, so the SDL location kind of a directive application falls out of a
join instead of duplicating the table. Second, type wrapping is two columns with distinct jobs:
`type_sdl` is the captured literal, the type expression as the author wrote it, and the three
decoded columns (`non_null`, `is_list`, `item_non_null`) are the capture-time decode the decode
rule admits, covering the single-level wrapping the generator supports and keeping the input
side's list-item nullability, which the current model's boolean pair loses. Both are written
from the same AST in the same insert, so divergence between them is a capture bug, and a gate
query checks the correspondences SQL can express. Deeper nesting (a list of lists) keeps a
faithful `type_sdl` while the decode describes the outermost list and innermost item; whether
the generator accepts such a shape is a detection's business, not capture's. An ordered
wrapping child table can follow if a consumer ever needs the interior levels relationally.

Today's model has no argument coordinate and no enum-value coordinate (`FieldCoordinates` is
two-part), and stores the object-to-interface edge only inverted (interface to participants).
The schema makes all three first-class: `graphql_argument` and `graphql_enum_value` rows are
directly addressable, and `graphql_implements` states the edge the declaring type wrote.

```sql
-- ==== SDL existence facts ====================================================
-- One row per element the SDL declares. Capture is total: built-in scalars,
-- @oneOf, federation definitions arriving via @link, and user-authored
-- directives are ordinary rows. Source positions follow the 1-based
-- graphql-java convention and are NULL only for engine-provided elements no
-- SDL line declares (built-in scalars). Elements contributed by the bundled
-- directives.graphqls are stamped with that resource name as source_name;
-- consumers wanting user-authored declarations filter on it, as
-- CatalogBuilder.projectTypeDefinitionLocations does today.

-- A named type exists in the schema.
CREATE TABLE graphql_type (
  type_name     VARCHAR NOT NULL, -- the GraphQL type name; the coordinate every other SDL fact hangs off
  kind          VARCHAR NOT NULL, -- which declaration form introduced the type
  description   VARCHAR,          -- SDL description string; net-new as a persisted fact (today read live off retained graphql-java objects)
  source_name   VARCHAR,          -- which SDL source declared it
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name),
  CHECK (kind IN ('OBJECT', 'INTERFACE', 'UNION', 'ENUM', 'INPUT_OBJECT', 'SCALAR'))
);

-- A field exists at a coordinate. OBJECT and INTERFACE parents make it an
-- output field, INPUT_OBJECT parents an input field; the join decides.
CREATE TABLE graphql_field (
  type_name         VARCHAR NOT NULL, -- owning type
  field_name        VARCHAR NOT NULL,
  ordinal           INT     NOT NULL, -- order in the effective assembled type (base declaration, then extensions in document order)
  type_sdl          VARCHAR NOT NULL, -- the rendered type expression, e.g. '[Film!]!'; authoritative for wrapping fidelity
  named_type        VARCHAR NOT NULL, -- the named type the expression bottoms out in
  non_null          BOOLEAN NOT NULL, -- outermost non-null wrapper present
  is_list           BOOLEAN NOT NULL, -- a list wrapper is present
  item_non_null     BOOLEAN,          -- item-level non-null when is_list; NULL otherwise
  default_value_sdl VARCHAR,          -- rendered default value; input-object fields only
  description       VARCHAR,
  source_name       VARCHAR,
  source_line       INT,
  source_column     INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name)  REFERENCES graphql_type (type_name),
  FOREIGN KEY (named_type) REFERENCES graphql_type (type_name),
  CHECK (is_list OR item_non_null IS NULL)
);

-- An argument exists on a field. Net-new coordinate: today arguments are
-- classified per-field and mostly projected away, with no location kept.
CREATE TABLE graphql_argument (
  type_name         VARCHAR NOT NULL, -- owning type of the field the argument sits on
  field_name        VARCHAR NOT NULL,
  argument_name     VARCHAR NOT NULL,
  ordinal           INT     NOT NULL, -- declaration order within the field
  type_sdl          VARCHAR NOT NULL, -- rendered type expression, as on graphql_field
  named_type        VARCHAR NOT NULL,
  non_null          BOOLEAN NOT NULL,
  is_list           BOOLEAN NOT NULL,
  item_non_null     BOOLEAN,
  default_value_sdl VARCHAR,          -- rendered default value, when declared
  description       VARCHAR,
  source_name       VARCHAR,
  source_line       INT,
  source_column     INT,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name),
  FOREIGN KEY (named_type) REFERENCES graphql_type (type_name),
  CHECK (is_list OR item_non_null IS NULL)
);

-- An enum declares a value. Net-new coordinate; deprecation is not a column
-- because @deprecated is an ordinary applied directive.
CREATE TABLE graphql_enum_value (
  type_name     VARCHAR NOT NULL, -- the owning ENUM type
  value_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL, -- order in the effective assembled enum (base declaration, then extensions)
  description   VARCHAR,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name)
);

-- A union lists a member type.
CREATE TABLE graphql_union_member (
  union_name       VARCHAR NOT NULL,
  member_type_name VARCHAR NOT NULL,
  ordinal          INT     NOT NULL, -- position in the member list
  PRIMARY KEY (union_name, member_type_name),
  FOREIGN KEY (union_name)       REFERENCES graphql_type (type_name),
  FOREIGN KEY (member_type_name) REFERENCES graphql_type (type_name)
);

-- A type declares that it implements an interface. Stored in declaration
-- direction; today's model keeps only the inverted interface-to-participants
-- list and reads this edge live off graphql-java.
CREATE TABLE graphql_implements (
  type_name      VARCHAR NOT NULL, -- the implementing OBJECT or INTERFACE
  interface_name VARCHAR NOT NULL,
  PRIMARY KEY (type_name, interface_name),
  FOREIGN KEY (type_name)      REFERENCES graphql_type (type_name),
  FOREIGN KEY (interface_name) REFERENCES graphql_type (type_name)
);

-- The schema definition names a root operation type. These rows are the
-- seeds the reachability derivation grows from.
CREATE TABLE graphql_root_operation (
  operation VARCHAR NOT NULL, -- which root slot
  type_name VARCHAR NOT NULL, -- the object type serving it
  PRIMARY KEY (operation),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  CHECK (operation IN ('QUERY', 'MUTATION', 'SUBSCRIPTION'))
);

-- ==== Directive definitions ==================================================
-- The definition side of the directive surface: what a directive is, where it
-- may sit, what arguments it declares. Bundled, user-authored, and
-- federation-imported definitions are all rows.

-- A directive is defined.
CREATE TABLE graphql_directive (
  directive_name VARCHAR NOT NULL,
  repeatable     BOOLEAN NOT NULL, -- whether the definition says 'repeatable'; governs the ordinal on applications
  description    VARCHAR,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (directive_name)
);

-- A directive definition names a permitted location.
CREATE TABLE graphql_directive_location (
  directive_name VARCHAR NOT NULL,
  location       VARCHAR NOT NULL, -- introspection location name, e.g. FIELD_DEFINITION, INPUT_FIELD_DEFINITION
  PRIMARY KEY (directive_name, location),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name)
);

-- A directive definition declares a formal argument. Carries the same
-- wrapping decode as graphql_field, so list-ness of a directive argument is
-- a column read, not a string parse.
CREATE TABLE graphql_directive_argument (
  directive_name    VARCHAR NOT NULL,
  argument_name     VARCHAR NOT NULL,
  ordinal           INT     NOT NULL, -- declaration order in the definition
  type_sdl          VARCHAR NOT NULL, -- rendered argument type, e.g. '[ReferenceElement!]!'
  named_type        VARCHAR NOT NULL,
  non_null          BOOLEAN NOT NULL,
  is_list           BOOLEAN NOT NULL,
  item_non_null     BOOLEAN,
  default_value_sdl VARCHAR,          -- rendered default; the value an application inherits when it omits the argument
  description       VARCHAR,
  PRIMARY KEY (directive_name, argument_name),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name),
  FOREIGN KEY (named_type) REFERENCES graphql_type (type_name),
  CHECK (is_list OR item_non_null IS NULL)
);
```

Directive applications are one table per element family rather than one generic table, because a
generic table would need nullable key parts (an argument application has a three-part element
coordinate, a schema application a zero-part one) and a key with holes stops being a natural
key. Five families: the four element sites plus the schema definition itself, whose `@link`
application is the federation opt-in and would otherwise have no home in a store claiming total
capture. Every application key carries an `ordinal`, 0 for the single application of a
non-repeatable directive and numbered in document order for repeats. The ordinal is uniform
across all families because repeatability is a property of arbitrary SDL, not of today's
bundled inventory: federation's `@key` is repeatable on OBJECT and the sakila federated fixture
already applies it twice to one type, so a type-level key without an ordinal collides on the
existing corpus. Where order is semantics it is captured order: `@reference` applications
concatenate into one chain. Raw argument values ride in a child table per family, keyed by the
application plus the formal argument name, and the DDL ships the union view over all five
families so no consumer hand-writes the five-arm `UNION ALL`.

```sql
-- ==== Directive applications =================================================
-- One row per application the author wrote, one child row per argument the
-- author passed. Values are the rendered SDL literal; decoding is derivation.

-- A directive is applied to the schema definition (@link lives here).
CREATE TABLE applied_schema_directive (
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- 0 unless the directive is repeatable; repeats number in document order
  source_name    VARCHAR,          -- position of the application site
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (directive_name, ordinal),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name)
);

-- An argument the author passed to a schema-level application.
CREATE TABLE applied_schema_directive_arg (
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL, -- the definition's formal argument this value binds
  value_sdl               VARCHAR NOT NULL, -- the value as written, rendered from the AST; omitted arguments are absent rows
  PRIMARY KEY (directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (directive_name, ordinal)
    REFERENCES applied_schema_directive (directive_name, ordinal)
);

-- A directive is applied to a type (OBJECT, INTERFACE, UNION, ENUM,
-- INPUT_OBJECT, or SCALAR; the parent kind is a join away).
CREATE TABLE applied_type_directive (
  type_name      VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- as on applied_schema_directive; federation's @key repeats here
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, directive_name, ordinal),
  FOREIGN KEY (type_name)      REFERENCES graphql_type (type_name),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name)
);

-- An argument the author passed to a type-level application.
CREATE TABLE applied_type_directive_arg (
  type_name               VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, directive_name, ordinal)
    REFERENCES applied_type_directive (type_name, directive_name, ordinal)
);

-- A directive is applied to a field (output or input-object; the parent
-- type's kind decides which SDL location this was).
CREATE TABLE applied_field_directive (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- 0 unless the directive is repeatable; repeats number in document order
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, field_name, directive_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name),
  FOREIGN KEY (directive_name)        REFERENCES graphql_directive (directive_name)
);

-- An argument the author passed to a field-level application.
CREATE TABLE applied_field_directive_arg (
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, field_name, directive_name, ordinal)
    REFERENCES applied_field_directive (type_name, field_name, directive_name, ordinal)
);

-- A directive is applied to a field argument (ARGUMENT_DEFINITION site).
CREATE TABLE applied_argument_directive (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  argument_name  VARCHAR NOT NULL, -- the SDL argument the directive sits on
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- as on applied_field_directive; @reference is repeatable here too
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, field_name, argument_name, directive_name, ordinal),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name)
);

-- An argument the author passed to an argument-level application.
CREATE TABLE applied_argument_directive_arg (
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  argument_name           VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, field_name, argument_name, directive_name, ordinal)
    REFERENCES applied_argument_directive (type_name, field_name, argument_name, directive_name, ordinal)
);

-- A directive is applied to an enum value (@order, @index, @field,
-- @deprecated live here).
CREATE TABLE applied_enum_value_directive (
  type_name      VARCHAR NOT NULL,
  value_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- as on applied_schema_directive
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, value_name, directive_name, ordinal),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name),
  FOREIGN KEY (directive_name)        REFERENCES graphql_directive (directive_name)
);

-- An argument the author passed to an enum-value application.
CREATE TABLE applied_enum_value_directive_arg (
  type_name               VARCHAR NOT NULL,
  value_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, value_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, value_name, directive_name, ordinal)
    REFERENCES applied_enum_value_directive (type_name, value_name, directive_name, ordinal)
);

-- The one view the DDL ships: every application regardless of site, so a
-- consumer that wants "all applications of @x" reads one relation.
CREATE VIEW applied_directive_site AS
SELECT 'SCHEMA' AS site_kind, CAST(NULL AS VARCHAR) AS type_name,
       CAST(NULL AS VARCHAR) AS member_name, CAST(NULL AS VARCHAR) AS argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM applied_schema_directive
UNION ALL
SELECT 'TYPE', type_name, NULL, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM applied_type_directive
UNION ALL
SELECT 'FIELD', type_name, field_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM applied_field_directive
UNION ALL
SELECT 'ARGUMENT', type_name, field_name, argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM applied_argument_directive
UNION ALL
SELECT 'ENUM_VALUE', type_name, value_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM applied_enum_value_directive;
```

Catalog facts are keyed `(table_schema, table_name)` end to end, matching `CatalogFacts`'
schema-qualified keying; ambiguity of an unqualified `@table(name:)` is a resolution question
and therefore derivation, so capture just records every table. Foreign keys are stored once, on
the declaring side; the incoming direction `CatalogFacts` denormalizes bidirectionally is a
query here, which is the point of having a store. Multi-column keys and foreign keys are ordered
child tables, the spike's rich-value pattern.

```sql
-- ==== Catalog facts ==========================================================
-- What the jOOQ catalog scan sees in the consumer's generated database model.

-- A table exists in the consumer's catalog.
CREATE TABLE catalog_table (
  table_schema VARCHAR NOT NULL, -- SQL schema the table lives in
  table_name   VARCHAR NOT NULL, -- SQL table name
  java_name    VARCHAR NOT NULL, -- the generated jOOQ Java field name for the table
  description  VARCHAR,          -- the database comment on the table, when present
  PRIMARY KEY (table_schema, table_name)
);

-- A column exists on a table. SQL name is the coordinate, matching
-- CatalogFacts' SQL-name-centric keying; the Java name rides along because
-- the LSP surface is Java-name-centric.
CREATE TABLE catalog_column (
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  column_name  VARCHAR NOT NULL, -- SQL column name
  ordinal      INT     NOT NULL, -- column position in the table definition
  java_name    VARCHAR NOT NULL, -- generated jOOQ Java field name
  sql_type     VARCHAR NOT NULL, -- the column's SQL type as jOOQ reports it
  nullable     BOOLEAN NOT NULL,
  description  VARCHAR,          -- the database comment on the column, when present
  PRIMARY KEY (table_schema, table_name, column_name),
  FOREIGN KEY (table_schema, table_name) REFERENCES catalog_table (table_schema, table_name)
);

-- A uniqueness constraint exists on a table. Every unique constraint jOOQ
-- reports is a row, with the primary key flagged rather than segregated
-- (CatalogFacts excludes the PK from uniqueKeys; that is a projection choice,
-- not a fact).
CREATE TABLE catalog_key (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  is_primary      BOOLEAN NOT NULL, -- TRUE for the primary key, FALSE for other unique constraints
  PRIMARY KEY (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name) REFERENCES catalog_table (table_schema, table_name)
);

-- An ordered column of a uniqueness constraint.
CREATE TABLE catalog_key_column (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  position        INT     NOT NULL, -- 0-based position in the constraint's column list
  column_name     VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name, position),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES catalog_key (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name, column_name)
    REFERENCES catalog_column (table_schema, table_name, column_name)
);

-- A foreign key exists, keyed by the declaring (source) table. Implicit-path
-- inference ("exactly one FK between these two tables") is a derivation over
-- this relation, not a captured fact.
CREATE TABLE catalog_foreign_key (
  table_schema    VARCHAR NOT NULL, -- schema of the declaring table
  table_name      VARCHAR NOT NULL, -- the declaring (source) table
  constraint_name VARCHAR NOT NULL,
  target_schema   VARCHAR NOT NULL,
  target_table    VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name)    REFERENCES catalog_table (table_schema, table_name),
  FOREIGN KEY (target_schema, target_table) REFERENCES catalog_table (table_schema, table_name)
);

-- An ordered column pair of a foreign key. Parallel source and target
-- columns; multi-column FKs are first-class, matching CatalogFacts.
CREATE TABLE catalog_foreign_key_column (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  position        INT     NOT NULL, -- 0-based position in the FK's column list
  source_column   VARCHAR NOT NULL,
  target_column   VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name, position),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES catalog_foreign_key (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name, source_column)
    REFERENCES catalog_column (table_schema, table_name, column_name)
);

-- An index exists on a table (@order(index:) and @index resolve against it).
CREATE TABLE catalog_index (
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  index_name   VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, index_name),
  FOREIGN KEY (table_schema, table_name) REFERENCES catalog_table (table_schema, table_name)
);

-- An ordered column of an index.
CREATE TABLE catalog_index_column (
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  index_name   VARCHAR NOT NULL,
  position     INT     NOT NULL, -- 0-based position in the index's column list
  column_name  VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, index_name, position),
  FOREIGN KEY (table_schema, table_name, index_name)
    REFERENCES catalog_index (table_schema, table_name, index_name)
);
```

Extension facts come from the bytecode-only classpath walk (`ClasspathScanner`: stdlib classfile
parsing, no classloading, jOOQ package and synthetic classes skipped). Overloads make the plain
method name a non-key, so the raw JVM descriptor joins the key; it is ugly and it is the
identity, which is exactly what an identity-carrying key is for.

```sql
-- ==== Extension-class facts ==================================================
-- What the consumer's compiled classes offer: service methods, conditions,
-- record shapes, scalar constants. Javadoc and source positions deliberately
-- stay out; those live on the LSP's SourceWalker cadence and are joined at
-- request time, so a .java edit is seen without a generator rebuild.

-- A class exists on the consumer's extension classpath.
CREATE TABLE extension_class (
  class_name VARCHAR NOT NULL, -- fully qualified binary name
  class_kind VARCHAR NOT NULL, -- the classfile's declared form; the domain is closed over classfile shapes, so a violation is a capture bug
  PRIMARY KEY (class_name),
  CHECK (class_kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION'))
);

-- A public method exists on an extension class.
CREATE TABLE extension_method (
  class_name        VARCHAR NOT NULL,
  method_name       VARCHAR NOT NULL,
  descriptor        VARCHAR NOT NULL, -- raw JVM descriptor; the overload discriminator that keeps this key natural
  return_type       VARCHAR NOT NULL, -- erased source-form return type
  returns_condition BOOLEAN NOT NULL, -- matched on the un-erased org.jooq.Condition descriptor, so a consumer's own Condition type does not false-match
  PRIMARY KEY (class_name, method_name, descriptor),
  FOREIGN KEY (class_name) REFERENCES extension_class (class_name)
);

-- An ordered parameter of an extension method. Deliberately no
-- parameter-source column: which ParamSource a parameter binds to is decided
-- per directive application, not per method, so it is a derived relation
-- keyed by the application coordinate and lands with its first consumer.
CREATE TABLE extension_method_parameter (
  class_name     VARCHAR NOT NULL,
  method_name    VARCHAR NOT NULL,
  descriptor     VARCHAR NOT NULL,
  position       INT     NOT NULL, -- 0-based parameter position
  parameter_name VARCHAR,          -- NULL when the consumer compiled without -parameters
  parameter_type VARCHAR NOT NULL, -- erased source-form parameter type
  PRIMARY KEY (class_name, method_name, descriptor, position),
  FOREIGN KEY (class_name, method_name, descriptor)
    REFERENCES extension_method (class_name, method_name, descriptor)
);

-- A record component of an extension record class (from the classfile
-- RecordAttribute; backs record-mapping facts).
CREATE TABLE extension_record_component (
  class_name     VARCHAR NOT NULL,
  component_name VARCHAR NOT NULL,
  position       INT     NOT NULL, -- component position in the record header
  display_type   VARCHAR NOT NULL, -- erased display form of the component type
  PRIMARY KEY (class_name, component_name),
  FOREIGN KEY (class_name) REFERENCES extension_class (class_name)
);

-- A public static GraphQLScalarType constant (backs @scalarType resolution).
CREATE TABLE extension_scalar_constant (
  class_name VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL, -- the constant's field name, matched on the exact GraphQLScalarType descriptor
  PRIMARY KEY (class_name, field_name),
  FOREIGN KEY (class_name) REFERENCES extension_class (class_name)
);
```

## The capture loads

Both loads are infallible by construction: they record what is there, and graphql-java has
already validated directive arguments against their definitions before we see the schema, so raw
capture cannot reject. Capture is total, with no reachability pruning.

- **SDL load.** One pass fills the `graphql_` and `applied_` families, reading the **assembled
  `GraphQLSchema`**, and that source is decided here, not at implementation: assembly is what
  guarantees every type reference resolves (so the `named_type` FK cannot dangle) and every
  directive application has been validated (so raw capture cannot reject); neither holds of the
  raw registry. One named carve-out: `makeExecutableSchema` drops applied directives targeting
  built-in scalar names, the loss `GraphitronSchemaBuilder.recordSdlScalarDirectives` exists to
  patch, so the load captures exactly those from the registry in the same pass, and that
  pre-pass can retire when its consumer migrates. The capture writer gets its own package in
  core, importing the module's generated classes; it does not live inside
  `no.sikt.graphitron.facts`, whose import-direction allowance ("reads the assembled schema,
  imports nothing else of the tree") stays intact. The walk constraint stands regardless of
  placement: a single pass over the schema, not two parallel walkers drifting apart.
- **Catalog load.** Fills the `catalog_` family from the same jOOQ catalog walk that builds
  `CatalogFacts` today, and the `extension_` family from the `ClasspathScanner` emission. Runs
  inside the codegen classloader scope; only values cross out, which the store enforces.

Insertion through the module's own generated jOOQ classes, so capture dogfoods the surface every
later consumer uses. A duplicate primary key on any base relation throws: that is a capture bug,
not an author error, per the constraint split R589 fixes.

## What this iteration deliberately leaves out

- **The derived stratum.** Claims, reachability, demand, occurrence paths, diagnostics, and
  command relations are absent by design: per the strangler frame, a derivation's DDL lands with
  the first consumer that migrates onto it, and several shapes hang on R589's open questions
  (the axis declaration's home, inferred-claim provenance, slot-fact granularity, the
  path-valued key). The spike DDL in
  `roadmap/audits/2026-08-05-fact-base-h2-spike.md` is the standing sketch for that stratum.
- **Routines.** A routine census is capture by the decode rule and cheap to load, but its
  identity is not settled: routines overload, carry parameter modes, and split table-valued
  from scalar, so the key needs the same inventory-grounded design the relations above got.
  Guessing the key is the speculation; the census lands with the `@routine` consumer whose
  queries fix its shape.
- **Per-extension declaration sites.** All five type-extension kinds are live today, so a type
  may be declared across several sources; `graphql_type` keeps one site (the base declaration)
  and the `ordinal` columns mean order in the effective assembled type. A declaration-site
  relation keyed `(type_name, source_name, source_line)` lands when a consumer needs
  per-extension sites; the description-note appliers are the known candidate.
- **Javadoc and Java source positions.** The request-time join against `SourceWalker` is a
  deliberate cadence separation (a `.java` edit is visible without a rebuild) and stays outside
  the store.
- **Derived `GraphitronSchema` components.** Arrivals, reachable source shapes, tenant scopes
  and bindings, connection synthesis, operation members, and delivery facts are derivations over
  the base facts above; none of them is capture, so none of them is a table here.
- **Decoded slot facts.** Capture decodes only what the decode rule admits (type wrapping,
  `returns_condition`, the erased display types); everything else (reference paths, error
  handlers, mutation kinds) stays a rendered literal until a consumer's derivation decodes it.

## Acceptance

- `graphitron-model` builds before core; its jOOQ classes are generated from the DDL resource
  alone (no live database, nothing generated is committed) and core compiles against them.
- The generator bootstraps the store at startup: fresh H2 in-memory database per run, DDL
  executed from the same resource the codegen read.
- Both capture loads run inside the standard build; the full fixture corpus shows generated-output
  identity, and no diagnostic text changes.
- Agreement tests pin the shadow copy to the live pipeline through one mechanical driver: it
  enumerates the generated jOOQ tables and fails on any relation without a registered agreement
  source, so a new relation cannot arrive unchecked. Each registration declares its direction:
  containment for the SDL side (capture is total, `GraphitronSchema` is reachability-pruned, so
  the store contains the model), equality for the `CatalogFacts` and scanner censuses. The
  anchor checks: type census against `GraphitronSchema.types`, per-coordinate applied-directive
  counts against the SDL, catalog table and column census against `CatalogFacts`, extension
  method census against the scanner's `CompletionData` view. They are the shadow period's
  honesty check and retire as consumers migrate.
- The gate family runs against the bootstrapped store: comment coverage (every table and column
  commented, checked via `INFORMATION_SCHEMA`) and one query per cross-relation invariant the
  DDL cannot state (at most one `is_primary` row per catalog table, `default_value_sdl` only
  under INPUT_OBJECT parents, `ordinal` 0 unless the directive is repeatable, wrapping decode
  consistent with `type_sdl` where SQL can express the correspondence).
- All tests live in `graphitron` at the appropriate tier (the tier meta-annotations live in
  core's test root and the module order forbids the reverse dependency); `graphitron-model`
  hosts model code only: the DDL, the codegen output, the bootstrap, and in later increments
  the model's function aliases, never tests.
- Ride-alongs land: root pom module list, CLAUDE.md and `docs/architecture/reference/modules.adoc`
  enumeration (the `check-module-enumeration` gate holds), H2 version pinned in the root pom.

## Out of scope

- **Anyone reading the store.** No derivation, no detection, no consumer migration; those are
  the follow-on migration pieces. This item ends with a populated database nobody queries except
  the tests.
- **Any behavior change.** What the build accepts, rejects, emits, and reports is byte-identical.
- **Touching `GraphitronSchema`.** The surface being strangled is not extended and not shrunk
  here; both models simply coexist, with the store as the only place new facts land from now
  on. That landing rule is review-only and named as such: no mechanical guard fails when a
  fact is added to `GraphitronSchema` instead, so reviewers hold the line.

## Relationships

- **`validation-adds-facts` (R589):** the architecture this substrate serves; carries the
  strangler frame, the store decision, and the emission-boundary determinism rule. Its
  classification-stage migration is expected to be the store's first reader.
- **Umbrella (`coordinate-lowers-to-datafetcher-queryparts`, R333):** this schema is the
  umbrella's normalised data model reified as SQL; the umbrella's arm-by-arm migration language
  is what the strangler frame executes.
- **`roadmap/audits/2026-08-05-fact-base-h2-spike.md`:** the dated spike record grounding the
  H2-through-jOOQ stack, the latency envelope, and the rich-value encoding patterns the DDL
  above uses.
