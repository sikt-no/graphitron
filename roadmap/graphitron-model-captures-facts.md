---
id: R595
title: "The graphitron-model module exists and capture fills it"
status: In Progress
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-05
last-updated: 2026-08-07
---

# The graphitron-model module exists and capture fills it

The fact-base architecture R589 (`validation-adds-facts`) arrived at needs its substrate: a new
reactor module, `graphitron-model`, holding the fact-schema DDL (the umbrella's normalised data
model reified as SQL; R333), jOOQ codegen over it, and an H2 in-memory bootstrap. On top of the
module, two infallible capture loads run beside the existing pipeline and change no behavior:
the SDL visitor records existence facts, expands the macro directives, stores foreign directive
applications verbatim for round-trip fidelity, and decodes the graphitron directive inventory
into semantic relations; the jOOQ and classpath scans record the catalog and extension facts.
Nobody reads the store yet. Agreement tests are the shadow
period's honesty check and retire as consumers migrate off `GraphitronSchema` piece by piece
(the strangler frame recorded in R589); while both models are live, new facts land only in the
store. Two spikes ground the stack: `roadmap/audits/2026-08-05-fact-base-h2-spike.md` (the
store itself) and `roadmap/audits/2026-08-05-h2-functions-jooq-spike.md` (the function surface
and the codegen path).

The main delivery of this spec is the target model itself: the first iteration of the fact
schema below. The module and the loads exist to make that schema real, compiled against, and
kept honest by tests.

## The module

`graphitron-model` is a jar module listed before `graphitron` in the root pom, so the reactor
builds it first and core depends on its artifact. It contains one source of truth, the fact-schema
DDL, as a single SQL resource (`src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`),
and two things generated from it:

- **Compile-time surface.** Codegen is jOOQ's live H2 metadata generation over a real store
  booted from the DDL, not `DDLDatabase` simulation: a maven-compiler execution at
  generate-sources compiles the module's bootstrap and build-driver packages, then an
  `exec:java` execution runs the codegen driver on the project classpath. The driver does not
  restate boot, it performs it: it calls the run-time bootstrap entry point below (which
  needs no generated classes) and points `GenerationTool` at the store the bootstrap handed
  back; build-helper adds the generated sources and the default compile builds the rest
  against them. Codegen is thereby a rehearsal of boot as a call, not as two procedures kept
  similar by hand: a bootstrap regression or a DDL error fails codegen with a real H2 error
  before it can fail a generator run. No external database process is involved (the
  `graphitron-sakila-db` contrast). Generated classes land in
  `target/generated-sources/jooq` under package `no.sikt.graphitron.model` and are never
  committed; the DDL is the single source. Because the module builds before core, editing the
  DDL fails javac in every consumer that touched the changed relation, and with no persisted
  state anywhere, compile-time is the schema's only compatibility surface. Changing the model
  is editing the DDL and following the compiler.
- **Run-time store.** A small bootstrap entry point opens a fresh H2 in-memory database,
  executes the same DDL resource, and hands back a jOOQ `DSLContext` over it. One database per
  generator run, created at startup, populated by capture, dead with the process. No
  migrations exist because no persisted state *of record* exists: a warm-start cache under
  `target/` (the populated store persisted at end of run, stamp-invalidated, discarded on any
  mismatch, never migrated) is admitted and owned by `warm-start-model-store` (R597), and
  changes neither property.

A Java function surface stays available as a contingency, not a plan. The functions spike
(`roadmap/audits/2026-08-05-h2-functions-jooq-spike.md`) proved H2 `CREATE ALIAS` scalar
functions workable end to end, in two build shapes that are both precedented in the reactor:
alias classes compiled inside this module before codegen runs (the `exec:java` driver
pattern `graphitron-mcp`'s docs-index builder already uses), or a
`graphitron-model-functions` sibling on the codegen plugin's classpath (the reason
`graphitron-fixtures-codegen` exists as a sibling). With structured directive arguments
decoded at capture (see the conventions below), no known derivation needs a SQL-side parse,
so no functions ship and none are planned. The contingency's home is decided anyway, since
deciding is cheapest here: if a function ever appears it lives in this module under the
single-module driver wiring (the driver already exists for codegen, and a contingency does
not justify reactor module churn); the sibling arm stays documented in the audit as the
fallback if plugin-classpath constraints ever force it.

Mechanical ride-alongs: the root pom module list, the module enumeration in CLAUDE.md and
`docs/architecture/reference/modules.adoc` (the `check-module-enumeration` gate checks the
backticked names; that file's prose module count is not gate-read and moves by hand), and the
H2 version pinned in the root pom (the same embedded H2 serves the build-time codegen store
and the run-time store).

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
- **Foreign keys encode capture structure; author references are detections.** A
  `FOREIGN KEY` appears only where the walk writes the child while standing on the parent, so
  the referent's existence is structural. A reference the author spells by name (a field's
  named type, a union member, an implements edge, an applied directive's name, a
  root-operation binding) carries no FK: on a schema assembly would reject, the reference may
  dangle, dangling is an author error, and author errors are detections minting located
  diagnostics, never constraint violations. The same split is what keeps capture order-free:
  every declared FK is satisfied within a single file's walk (the whichever-site-first
  existence rule makes even the owning-type reference file-local when an extension's file
  loads before the base's), so files load one at a time in any order and the incremental
  refresh unit stays a single file, whereas an FK on a cross-file reference would force a
  global definitions-before-uses parse order and destroy both. Cross-file integrity is
  checked once, by detection queries over the collected facts. The cost is jOOQ's implicit
  path joins on exactly those FK-free edges; explicit join conditions carry them.
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
- **Capture stores what the author wrote.** An omitted directive argument is a NULL column or
  an absent row, never a default-filled one; effective values are derivation views (for the
  graphitron namespace the defaults are generator constants, not captured facts). Structured
  values (reference paths, error handlers, field sets) decode at capture into typed columns
  and ordered child relations: the AST value is in hand during the walk, and the decode never
  rejects; while assembly runs upstream it has already validated the structure, and once
  capture stands alone a literal that does not fit its declared shape quarantines raw in the
  semantic stratum's undecoded-argument relation instead of throwing. Rows a macro synthesized
  rather than the author wrote are marked by the synthesis provenance relations; the authored
  picture is the anti-join. Synthesized rows inherit the causing application's source
  position (the compiler convention for macro expansion: the location shown is the one the
  author can edit), and a macro's contribution enters through the same door as authored
  text: it contributes a declaration site at that position, a definition site when it
  creates the type, an extension site when it adds members to an existing one, marked in the
  site-synthesis provenance relation. Every synthesized element hangs off its synthesized
  site through the ordinary declaration reference, so the site linkage is total (never
  NULL), additions need no per-element marking, and the authored picture is the rows whose
  sites are authored.
- **Source order is a captured fact.** Where declaration order is meaningful (fields, arguments,
  enum values, union members, key and index columns) it is an explicit ordinal column, so an
  `ORDER BY` reproduces it; iteration order is never load-bearing, per the determinism rule R589
  fixes at the emission boundary.
- **Only values are stored**: strings, booleans, integers. This mirrors the documented
  `CatalogFacts` invariant (never a live `Table<?>`, `ForeignKey`, or `Class<?>`, because the
  codegen classloader closes per pass); a SQL store enforces it structurally.
- **Decode at capture exactly when the decode needs parse-boundary knowledge SQL cannot
  express** (the graphql-java AST, a JVM descriptor, federation's field-set grammar);
  anything computable as a query over captured columns is derivation and stays out. This
  rule is why type wrapping, structured directive arguments, and `returns_condition` are
  columns and child relations, while name resolution (does a written table or class name
  denote a real one) and effective-value defaulting are not.
- **Constraint violations are generator bugs, never author errors.** Every key and `CHECK`
  below ranges over a domain capture controls (closed classfile forms, graphql-java's kind
  vocabulary) or an identity capture itself constructs, and two mechanisms construct the
  natural-key identities, because the registry retains what an author duplicates. Where the
  key carries an ordinal (the `applied_` families), every occurrence is captured and a repeat
  of a non-repeatable directive is a detection over ordinals. Everywhere else capture is
  first-wins in merge order, and the losing occurrence quarantines rendered and located in
  `graphql_duplicate_declaration`, where the duplicate-declaration detection reads it. An
  author mistake becomes a diagnostic row in the derived stratum; it never surfaces as a
  constraint violation here.
  Cross-relation invariants plain DDL cannot state (at most one primary key per table, defaults
  only on input-object fields, ordinal zero unless repeatable) get gate queries as their named
  enforcers, siblings of the comment-coverage gate.

## The fact schema, first iteration

Base relations only: what the two capture loads fill. The derived stratum (claims, reachability,
demand, occurrence paths, diagnostics, commands) is deliberately absent; see the leave-outs
section. Five families. The two non-SDL ones take their prefix from where the facts come from:
`catalog_` for jOOQ catalog facts, `extension_` for the consumer's compiled extension classes.
The three SDL families share an origin and are split by **how a row is treated**, which is the
distinction a reader actually needs at a query site: `graphql_` for what exists and is read,
`applied_` for what is carried verbatim into the emitted schema and interpreted by nobody,
`intent_` for what is decoded into meaning. That is why the graphitron namespace can be
forbidden from every `applied_` relation by a single gate, and why `applied_` and `intent_` are
prefix-siblings rather than one being nested under the other: they are the two halves of the
one directive-application surface, split by treatment, and federation's `@key` writes to both
in the same pass.

Two consequences of preferring treatment over origin here, both deliberate. The directive
*definition* relations are `graphql_` while the *application* relations are `applied_`, because
a definition is read (its `repeatable` flag governs what a repeated application means) while an
application is only carried. And a synthesis-provenance relation is named after the relation it
annotates, not after the family whose job it does, so `graphql_type_declaration_synthesis` and
`applied_type_directive_synthesis` sit under different prefixes; finding the provenance beside
the rows it explains is worth more than a uniform prefix for provenance.

A round-trip constraint binds the SDL families: the emitted runtime schema must be
reproducible from the store alone, the input schema minus the graphitron namespace plus the
macro expansions. That is why the existence family carries full fidelity (descriptions,
deprecations via applied rows, ordinals for stable output order, foreign directive
definitions and applications), and it is what the schema-emission consumers migrate onto. The
namespace strip itself becomes one predicate at emission instead of scattered knowledge of
which directives are ours.

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
-- directives.graphqls are stamped with that resource name as source_name
-- (for a type, the stamp sits on its declaration rows); consumers wanting
-- user-authored declarations filter on it, as
-- CatalogBuilder.projectTypeDefinitionLocations does today.

-- A named type is declared or extended in the schema; this row is the name's
-- existence, written by capture from whichever site it meets first (macro-
-- contributed sites included), and graphql_type_declaration carries every
-- site. The declared-or-extended
-- reading is load-bearing: it is what makes the site rows' FK structural
-- (capture writes this row before any site row), and on a base-less
-- extension chain (an author error a detection reports) the row still
-- exists, anchored by the extension sites.
CREATE TABLE graphql_type (
  type_name     VARCHAR NOT NULL, -- the GraphQL type name; the coordinate every other SDL fact hangs off
  kind          VARCHAR NOT NULL, -- the first declaration site's form in merge order (the base definition's, on a well-formed schema)
  description   VARCHAR,          -- SDL description string; net-new as a persisted fact (today read live off retained graphql-java objects). Extensions cannot carry descriptions, so this is the base definition's when one exists
  PRIMARY KEY (type_name),
  CHECK (kind IN ('OBJECT', 'INTERFACE', 'UNION', 'ENUM', 'INPUT_OBJECT', 'SCALAR'))
);

-- A declaration site of a type: the base definition or one extension. All
-- five extension kinds are live today, so a type's effective shape may be
-- assembled from several files; this relation records who contributed what
-- and indexes the incremental-refresh unit ("which types does this file
-- touch"). Engine-provided types (built-in scalars) have no declaration rows.
CREATE TABLE graphql_type_declaration (
  type_name     VARCHAR NOT NULL,
  source_name   VARCHAR NOT NULL, -- the site's file; a site is a syntactic occurrence, so its location is its identity
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL, -- in the key because a line does not identify a site: two extensions of one type can share a line in minified SDL
  merge_ordinal INT     NOT NULL, -- capture-assigned position in merge order: the base definition, then extensions in document order; on a base-less chain the first extension holds 0. Dense per type (a gate), and the order behind every element ordinal
  is_extension  BOOLEAN NOT NULL, -- FALSE exactly at merge_ordinal 0 on a well-formed schema; a base-less extension chain is an author error a detection reports, never a constraint
  kind          VARCHAR NOT NULL, -- the declaration form written at this site; a mismatch against the type row's kind is a detection
  PRIMARY KEY (type_name, source_name, source_line, source_column),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  CHECK (kind IN ('OBJECT', 'INTERFACE', 'UNION', 'ENUM', 'INPUT_OBJECT', 'SCALAR'))
);

-- A field exists at a coordinate. OBJECT and INTERFACE parents make it an
-- output field, INPUT_OBJECT parents an input field; the join decides.
CREATE TABLE graphql_field (
  type_name           VARCHAR NOT NULL, -- owning type
  field_name          VARCHAR NOT NULL,
  ordinal             INT     NOT NULL, -- order in the effective type: base declaration, then extensions in document order (capture merges them from the registry)
  declaration_line    INT     NOT NULL, -- the contributing declaration site, keyed with this row's own source_name (an authored row sits lexically inside its site; a synthesized row shares its synthesized site's inherited position)
  declaration_column  INT     NOT NULL, -- the site key's fourth part, as on graphql_type_declaration
  type_sdl          VARCHAR NOT NULL, -- the rendered type expression, e.g. '[Film!]!'; authoritative for wrapping fidelity
  named_type        VARCHAR NOT NULL, -- the named type the expression bottoms out in; author-spelled, no FK, integrity is a detection
  non_null          BOOLEAN NOT NULL, -- outermost non-null wrapper present
  is_list           BOOLEAN NOT NULL, -- a list wrapper is present
  item_non_null     BOOLEAN,          -- item-level non-null when is_list; NULL otherwise
  default_value_sdl VARCHAR,          -- rendered default value; input-object fields only
  description       VARCHAR,
  source_name       VARCHAR NOT NULL, -- every field row comes from an SDL site (built-in scalars declare none), and a NULL here would silently disable the site FK under MATCH SIMPLE
  source_line       INT,
  source_column     INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column),
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
  CHECK (is_list OR item_non_null IS NULL)
);

-- An enum declares a value. Net-new coordinate; deprecation is not a column
-- because @deprecated is an ordinary applied directive.
CREATE TABLE graphql_enum_value (
  type_name           VARCHAR NOT NULL, -- the owning ENUM type
  value_name          VARCHAR NOT NULL,
  ordinal             INT     NOT NULL, -- order in the effective enum: base declaration, then extensions
  declaration_line    INT     NOT NULL, -- the contributing site, as on graphql_field
  declaration_column  INT     NOT NULL,
  description         VARCHAR,
  source_name         VARCHAR NOT NULL, -- NOT NULL for the same reason as on graphql_field: half of the site FK
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- A union lists a member type.
CREATE TABLE graphql_union_member (
  union_name          VARCHAR NOT NULL,
  member_type_name    VARCHAR NOT NULL,
  ordinal             INT     NOT NULL, -- position in the effective member list
  declaration_line    INT     NOT NULL, -- the contributing site, as on graphql_field
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL, -- position of the member token itself; NOT NULL as on graphql_field
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (union_name, member_type_name),
  FOREIGN KEY (union_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (union_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- A type declares that it implements an interface. Stored in declaration
-- direction; today's model keeps only the inverted interface-to-participants
-- list and reads this edge live off graphql-java.
CREATE TABLE graphql_implements (
  type_name           VARCHAR NOT NULL, -- the implementing OBJECT or INTERFACE
  interface_name      VARCHAR NOT NULL,
  declaration_line    INT     NOT NULL, -- the contributing site, as on graphql_field
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL, -- position of the interface token itself; NOT NULL as on graphql_field
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (type_name, interface_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- The schema definition names a root operation type. These rows are the
-- seeds the reachability derivation grows from. The binding is an
-- author-spelled reference, so its dangling case mints a located diagnostic;
-- the position columns are what it locates from. (A double binding cannot
-- reach capture: a schema extension re-binding an operation throws at parse.)
CREATE TABLE graphql_root_operation (
  operation     VARCHAR NOT NULL, -- which root slot
  type_name     VARCHAR NOT NULL, -- the object type serving it
  source_name   VARCHAR,          -- position of the binding inside the schema { } block; all three NULL exactly when the binding is the name-convention default no SDL line spells
  source_line   INT,
  source_column INT,
  PRIMARY KEY (operation),
  CHECK (operation IN ('QUERY', 'MUTATION', 'SUBSCRIPTION'))
);

-- The duplicate-declaration overflow, sibling of the semantic stratum's
-- undecoded-argument relation. The registry retains element-level duplicates
-- without error (a field declared twice in one body or re-declared by an
-- extension, a repeated argument, enum value, union member, or implements
-- entry, a second application of a single-application graphitron directive,
-- a repeated location or formal argument in a directive definition), so
-- every element-level natural key in this schema is author-reachable.
-- Capture is first-wins in merge order; the losing occurrence records here,
-- rendered and located, so no authored text is lost and the
-- duplicate-declaration detection has its row. Empty while assembly runs
-- upstream (assembly rejects these schemas first). A second base definition,
-- of a type or of a directive, is the duplication family the registry itself
-- rejects at parse, so the TYPE kind is reachable only through the LSP's
-- per-file fragment path.
CREATE TABLE graphql_duplicate_declaration (
  source_name   VARCHAR NOT NULL, -- the losing occurrence's own position identifies the row
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL,
  element_kind  VARCHAR NOT NULL, -- which family's natural key collided
  coordinate    VARCHAR NOT NULL, -- the colliding key, rendered (e.g. 'Q.title')
  value_sdl     VARCHAR NOT NULL, -- the losing occurrence as written, rendered from the AST; children ride inside it, so a losing field keeps its arguments
  PRIMARY KEY (source_name, source_line, source_column),
  CHECK (element_kind IN ('TYPE', 'FIELD', 'ARGUMENT', 'ENUM_VALUE',
                          'UNION_MEMBER', 'IMPLEMENTS', 'DIRECTIVE_APPLICATION',
                          'DIRECTIVE_LOCATION', 'DIRECTIVE_ARGUMENT'))
);

-- ==== Directive definitions ==================================================
-- The definition side of the directive surface: what a directive is, where it
-- may sit, what arguments it declares. User-authored, spec built-in, and
-- federation-imported definitions are rows because the emitted runtime schema
-- re-declares them (round trip). Graphitron's own bundled definitions are
-- generator constants shipped in directives.graphqls, not author facts: they
-- stay out, and their fact-roles (argument defaults, repeatability) are
-- absorbed by the semantic stratum's shapes.

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
  source_name       VARCHAR,          -- position of the formal argument in the definition
  source_line       INT,
  source_column     INT,
  PRIMARY KEY (directive_name, argument_name),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name),
  CHECK (is_list OR item_non_null IS NULL)
);
```

The `applied_` families are the fidelity stratum, and their scope is the round trip: every
application that must survive into the emitted runtime schema verbatim lands here, which is
everything *outside* the graphitron namespace (`@deprecated`, user-authored directives, and
the federation surface). These rows are never interpreted; they are re-emitted. The
graphitron namespace never lands here at all: its applications are stripped from the output
and exist only decoded, in the semantic stratum below. Federation applications appear in
both strata (verbatim here for re-emission, decoded there for consumption, written in the
same pass; a gate query pins the two projections in agreement).

Applications are one table per element family rather than one generic table, because a
generic table would need nullable key parts (an argument application has a three-part element
coordinate, a schema application a zero-part one) and a key with holes stops being a natural
key. Five families: the four element sites plus the schema definition itself, whose `@link`
application is the federation opt-in and would otherwise have no home in a store claiming total
capture. Every application key carries an `ordinal`, 0 for the single application of a
non-repeatable directive and numbered in document order for repeats. The ordinal is uniform
across all families because repeatability is a property of arbitrary SDL, not of today's
bundled inventory: federation's `@key` is repeatable on OBJECT and the sakila federated fixture
already applies it twice to one type, so a type-level key without an ordinal collides on the
existing corpus. Raw argument values ride in a child table per family, keyed by the
application plus the formal argument name, and the DDL ships the union view over all five
families so no consumer hand-writes the five-arm `UNION ALL`.

```sql
-- ==== Directive applications =================================================
-- One row per application the author wrote, one child row per argument the
-- author passed. Values are the rendered SDL literal; these rows are
-- re-emitted verbatim, never decoded (the graphitron namespace never lands
-- here; it is decoded into the semantic stratum instead).

-- A directive is applied to the schema definition (@link lives here).
CREATE TABLE applied_schema_directive (
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- 0 unless the directive is repeatable; repeats number in document order
  source_name    VARCHAR,          -- position of the application site
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (directive_name, ordinal)
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
  type_name           VARCHAR NOT NULL,
  directive_name      VARCHAR NOT NULL,
  ordinal             INT     NOT NULL, -- as on applied_schema_directive; federation's @key repeats here
  declaration_line    INT     NOT NULL, -- the applying site (extensions apply type directives too); a synthesized @key hangs off the type's causing authored site, per its own provenance relation below
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL, -- NOT NULL as on graphql_field: half of the site FK
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (type_name, directive_name, ordinal),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
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
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
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
  ordinal        INT     NOT NULL, -- as on applied_field_directive
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, field_name, argument_name, directive_name, ordinal),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
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

-- A directive is applied to an enum value (@deprecated lives here; the
-- graphitron enum-value directives land in the semantic stratum).
CREATE TABLE applied_enum_value_directive (
  type_name      VARCHAR NOT NULL,
  value_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- as on applied_schema_directive
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, value_name, directive_name, ordinal),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
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

The semantic stratum decodes the graphitron and federation inventory, one relation family per
directive, filled by the same capture walk. Its rules:

- **Tables follow the coordinate shape, not the SDL location list.** A directive permitted on
  FIELD_DEFINITION and INPUT_FIELD_DEFINITION needs one table, because both sites share the
  `(type_name, field_name)` coordinate and the parent kind is a join away, exactly as
  `graphql_field` unifies the two; a directive that also sits on ARGUMENT_DEFINITION gets a
  second table for the three-part coordinate. No nullable key parts, ever: a multi-site
  directive gets one table per coordinate shape.
- **Structured arguments flatten per the decode rule.** An `ExternalCodeReference` becomes
  class, method, and arg-mapping columns; a list becomes an ordered child relation;
  `@reference`'s repeatable applications concatenate into one position-keyed step relation in
  document order.
- **Authored values only**, per the conventions: omitted arguments are NULL columns or absent
  rows, effective values are derivation views, and name resolution is a detection over the
  catalog and extension families, never capture's business.
- **Type-site relations carry the declaration-site reference** (an extension applies `@table` or
  `@key` as readily as a base definition), and a repeated application of a non-repeatable
  directive at one coordinate keeps the first row and mints a located detection; the walk
  never throws on author input.
- **Every application-level relation carries the application's own source position.** The
  graphitron namespace has no `applied_` twin (it is stripped, not re-emitted), so the intent
  row is the only record of where the author wrote the application; detections mint located
  diagnostics from these columns, and document order between applications is recoverable
  where it is load-bearing (a field's `@routine` and `@reference` applications compose one
  table chain in written order, so the chain is an ORDER BY over positions). Child relations
  locate through their parent. On type-coordinate relations the position columns sit beside
  the declaration-site reference, the `graphql_field` pattern: `source_name` doubles as the
  site key part, `source_line` and `source_column` are the application's own.
- **Repeatable applications key by capture-assigned ordinal in document order**, as in the
  `applied_` families. This also covers repetition the directive's own semantics key
  differently: `@referenceFor` applications are keyed by participant in consumption, but the
  relation keys by ordinal and keeps the participant as a column, so an author repeating a
  participant produces two rows and a detection, never a key collision.
- **Author-spelled enum literals are open columns, not CHECKs.** A `MutationType`,
  `ErrorHandlerType`, or `SortDirection` value arrives as a token the author typed; under
  registry capture nothing upstream has validated it, so a CHECK would turn a typo into a
  constraint violation. The column stores the token as written and vocabulary membership is
  a detection. (The `kind` CHECKs on the existence family are different: their domain is
  graphql-java's parser vocabulary, which capture controls.)
- **A literal that does not fit its declared shape quarantines raw.** A typed column
  (an Int, a Boolean, a structured input object) captures the decoded value; when the
  authored literal does not have the declared shape, the column stays NULL and the raw
  literal lands in `intent_undecoded_argument` with its location, so the authored text is
  never lost and the malformed-literal detection has its row. Dormant while assembly still
  runs upstream and rejects such schemas first, live when the tolerant path is the only one.
- **Pair-grammar strings keep the raw column and add an ordered pair child exactly where a
  consumer binds pairs.** One shared decoder serves every `argMapping`-shaped value today
  (`ArgBindingMap.parseArgMapping`), and its live sites (`@service`, `@condition` at every
  site, path-step conditions, `@routine`'s two mappings) get position-keyed pair relations;
  position keys deliberately preserve an author's duplicate parameter so the duplicate
  detection can see it. Inert sites (`@externalField`, `@enum`) keep only the raw column,
  because their sole consumer is a presence-triggered rejection.
- **Retired directives capture existence, not payload.** For `@multitableReference` the only
  consumer is the located rejection, so the relation is the
  coordinate alone; `@record` keeps its `className` because the warning arms compare the
  declared class against the reflected backing. Payload nobody reads is not a fact worth
  columns; if a consumer ever appears, the decode lands with it. (`@notGenerated` is not in
  this set: it is not a graphitron directive at all, and its stray declaration is a bug the
  DDL notes in place.)

The inventory below is the full census: every directive `directives.graphqls` declares, plus
the two federation applications the pipeline decodes (`@key`, `@link`), each relation grounded
in the directive's declared arguments and its actual consumers
(`roadmap/audits/2026-08-06-directive-consumer-census.md` records the census; today's
consumer surface is `BuildContext`'s name constants, the `no.sikt.graphitron.facts` visitors,
and the shared pair-grammar decoder). Grounding is load-bearing in both directions: it added
relations the declarations alone would not suggest (application-level `@reference` rows,
because an empty path means FK auto-discovery and is a fact about one application) and it
caught a bug: `@experimental_constructType` has no consumer anywhere in the pipeline and is
not a graphitron directive at all, so its declaration in `directives.graphqls` is wrong; it
gets no intent relation, and once the stray declaration is removed its applications are
foreign and take the `applied_` fidelity path like any other. The `intent_` prefix names what
a row is: the author's decoded intent at a coordinate.

```sql
-- ==== Semantic stratum ======================================================

-- @table on a type: the author binds the type to a database table. On an
-- INPUT_OBJECT the application is captured like any other; the ignored-and-
-- warned status of that site is a detection.
CREATE TABLE intent_table (
  type_name        VARCHAR NOT NULL, -- the OBJECT, INPUT_OBJECT, or INTERFACE carrying @table
  source_name      VARCHAR NOT NULL, -- the applying declaration site (keyed with the line and column below); doubles as the file of the position columns
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,              -- the application's own position
  source_column    INT,
  table_ref        VARCHAR,          -- the name argument as written (may carry a schema qualifier); NULL when omitted, the type-name fallback is a derivation
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- @field on an output or input-object field: the slot's bound name. A column,
-- a Java accessor, or a Java member depending on the backing, which is
-- classification's business; the $source / $errors sigil forms are stored as
-- written, their recognition being a prefix test SQL can express.
CREATE TABLE intent_field_binding (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR, -- the application's own position, here and below
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL, -- the name argument as written
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @field on an argument: the filter argument's bound column.
CREATE TABLE intent_argument_binding (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);

-- @field on an enum value: the database string (or Java constant) the value
-- maps to. The pivot vocabulary decode reads this relation too.
CREATE TABLE intent_enum_value_binding (
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);

-- @scalarType on a scalar: the Java constant backing it. Under registry
-- capture the application is read like any other; the SDL pre-pass the
-- current consumer needs (assembly strips directives off spec built-in
-- redeclarations) dies with the assembled source.
CREATE TABLE intent_scalar_type (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL, -- half of the site FK, so NOT NULL; a graphitron application always has an SDL position
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  scalar_ref       VARCHAR NOT NULL, -- the fully-qualified Java constant reference as written
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- @enum on an enum type. The full ExternalCodeReference is captured as
-- written, though today only arg_mapping is consumed (to reject a non-blank
-- value; the Java binding is derived by reflection and the per-value mapping
-- comes from intent_enum_value_binding).
CREATE TABLE intent_enum (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL, -- half of the site FK, so NOT NULL; a graphitron application always has an SDL position
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  class_name       VARCHAR, -- enumReference.className as written
  method           VARCHAR,
  arg_mapping      VARCHAR, -- structurally inert here; raw column only, no pair child
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- @condition on a field or input field (shared coordinate; the parent kind
-- decides which SDL site this was).
CREATE TABLE intent_field_condition (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR, -- ExternalCodeReference.className as written
  method        VARCHAR, -- ExternalCodeReference.method as written
  arg_mapping   VARCHAR, -- ExternalCodeReference.argMapping as written; the pair child below is its decode, the type_sdl-plus-decode pattern
  override      BOOLEAN, -- as written; NULL when omitted (the FALSE default is derivable)
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- An ordered context argument of a field-site @condition.
CREATE TABLE intent_field_condition_context_arg (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL, -- 0-based position in the contextArguments list
  name       VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name)
    REFERENCES intent_field_condition (type_name, field_name)
);

-- An ordered pair of a field-site @condition's argMapping. Position-keyed so
-- an author's duplicate parameter survives for the duplicate detection.
CREATE TABLE intent_field_condition_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL, -- the Java parameter (left side)
  argument_path VARCHAR NOT NULL, -- the right side as written: a GraphQL argument name or dotted input path
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name)
    REFERENCES intent_field_condition (type_name, field_name)
);

-- @condition on an argument: the same decode over the three-part coordinate.
CREATE TABLE intent_argument_condition (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  override      BOOLEAN,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);

CREATE TABLE intent_argument_condition_context_arg (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  name          VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, position),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES intent_argument_condition (type_name, field_name, argument_name)
);

CREATE TABLE intent_argument_condition_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, position),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES intent_argument_condition (type_name, field_name, argument_name)
);

-- @reference on a field or input field: one row per application, because an
-- application is a fact of its own. An empty path means FK auto-discovery
-- between the endpoints, and the rule that every application in a
-- multi-application chain must carry an element is per-application; both are
-- invisible in a flat concatenated chain. The effective chain the consumers
-- read is the steps ordered by (ordinal, position), and the written-order
-- interleaving with @routine applications on the same field is an ORDER BY
-- over the two relations' source positions.
CREATE TABLE intent_field_reference (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL, -- repeatable; document order
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- An ordered path element of one @reference application; the step's
-- ExternalCodeReference condition flattens in place.
CREATE TABLE intent_field_reference_step (
  type_name   VARCHAR NOT NULL,
  field_name  VARCHAR NOT NULL,
  ordinal     INT     NOT NULL,
  position    INT     NOT NULL, -- 0-based within the application's path
  table_ref   VARCHAR,          -- ReferenceElement.table as written
  key_ref     VARCHAR,          -- ReferenceElement.key as written (may carry a schema qualifier)
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES intent_field_reference (type_name, field_name, ordinal)
);

-- An ordered pair of a step condition's argMapping.
CREATE TABLE intent_field_reference_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, ordinal, step_position)
    REFERENCES intent_field_reference_step (type_name, field_name, ordinal, position)
);

-- @reference on an argument: the same family over the three-part coordinate.
CREATE TABLE intent_argument_reference (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, argument_name, ordinal),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);

CREATE TABLE intent_argument_reference_step (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  position      INT     NOT NULL,
  table_ref     VARCHAR,
  key_ref       VARCHAR,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (type_name, field_name, argument_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, argument_name, ordinal)
    REFERENCES intent_argument_reference (type_name, field_name, argument_name, ordinal)
);

CREATE TABLE intent_argument_reference_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, argument_name, ordinal, step_position)
    REFERENCES intent_argument_reference_step (type_name, field_name, argument_name, ordinal, position)
);

-- @referenceFor on a field: an explicit join path for one participant of a
-- multi-table interface or union child. Keyed by ordinal per the repeatable
-- rule; the consumption-side keying by participant makes a repeated
-- participant a detection, never a collision.
CREATE TABLE intent_reference_for (
  type_name            VARCHAR NOT NULL,
  field_name           VARCHAR NOT NULL,
  ordinal              INT     NOT NULL,
  source_name          VARCHAR,
  source_line          INT,
  source_column        INT,
  participant_type_ref VARCHAR NOT NULL, -- the type argument as written; author-spelled, no FK
  PRIMARY KEY (type_name, field_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

CREATE TABLE intent_reference_for_step (
  type_name   VARCHAR NOT NULL,
  field_name  VARCHAR NOT NULL,
  ordinal     INT     NOT NULL,
  position    INT     NOT NULL,
  table_ref   VARCHAR,
  key_ref     VARCHAR,
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES intent_reference_for (type_name, field_name, ordinal)
);

CREATE TABLE intent_reference_for_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, ordinal, step_position)
    REFERENCES intent_reference_for_step (type_name, field_name, ordinal, position)
);

-- @service on a field: the external service reference.
CREATE TABLE intent_service (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

CREATE TABLE intent_service_context_arg (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name       VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES intent_service (type_name, field_name)
);

CREATE TABLE intent_service_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES intent_service (type_name, field_name)
);

-- @externalField on a field: the static jOOQ-Field method. The omitted-method
-- fallback (the field name) is a derivation; arg_mapping is inert here (raw
-- column only, its rejection is presence-triggered).
CREATE TABLE intent_external_field (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @sourceRow on a field: the parent-side join-key lifter. Flat arguments by
-- declaration, not an ExternalCodeReference.
CREATE TABLE intent_source_row (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR NOT NULL,
  method        VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @asConnection on a field: the macro's spec, as authored. The expansion's
-- output is provenance-marked rows in the graphql_ tables, below.
CREATE TABLE intent_connection (
  type_name           VARCHAR NOT NULL,
  field_name          VARCHAR NOT NULL,
  source_name         VARCHAR,
  source_line         INT,
  source_column       INT,
  default_first_value INT,     -- as written; NULL when omitted
  connection_name     VARCHAR, -- the deprecated shared-type override, as written; honoured by the expansion, deprecation is a lint detection
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @asFacet on an input field: a marker; the bound column comes from
-- intent_field_binding, and every misuse arm is a detection.
CREATE TABLE intent_facet (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @orderBy on an argument: a marker; the input shape rules are detections.
CREATE TABLE intent_order_by (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);

-- @order on an enum value: a sorting specification. The exactly-one-of rule
-- over index, fields, and primaryKey is a detection.
CREATE TABLE intent_order (
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR, -- database index name as written
  primary_key   BOOLEAN, -- as written; NULL when omitted
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);

-- An ordered FieldSort entry of an @order.
CREATE TABLE intent_order_field (
  type_name  VARCHAR NOT NULL,
  value_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL, -- FieldSort.name, a column reference as written
  collate    VARCHAR,
  direction  VARCHAR, -- as written; author-spelled enum literal, open column
  PRIMARY KEY (type_name, value_name, position),
  FOREIGN KEY (type_name, value_name) REFERENCES intent_order (type_name, value_name)
);

-- @index on an enum value: the deprecated alias of @order(index:), still
-- honoured when @order is absent; the deprecation is a lint detection.
CREATE TABLE intent_index (
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR, -- the name argument, which the declaration leaves optional
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);

-- @defaultOrder on a field: the same specification shape plus the
-- directive-level direction that serves as the per-entry fallback.
CREATE TABLE intent_default_order (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR,
  primary_key   BOOLEAN,
  direction     VARCHAR, -- as written; open column, the ASC default is a derivation
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

CREATE TABLE intent_default_order_field (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL,
  collate    VARCHAR,
  direction  VARCHAR,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES intent_default_order (type_name, field_name)
);

-- @mutation on a field: the DML statement spec.
CREATE TABLE intent_mutation (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  operation     VARCHAR NOT NULL, -- the typeName argument as written (INSERT / UPDATE / DELETE / UPSERT); open column per the enum-literal rule
  multi_row     BOOLEAN, -- as written; NULL when omitted
  table_ref     VARCHAR, -- the DELETE write target as written
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @error on an object type: presence; the handlers list decodes into the
-- ordered child, and every cross-field handler rule is a detection.
CREATE TABLE intent_error (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL, -- half of the site FK, so NOT NULL; a graphitron application always has an SDL position
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- An ordered ErrorHandler of an @error application.
CREATE TABLE intent_error_handler (
  type_name   VARCHAR NOT NULL,
  position    INT     NOT NULL,
  handler     VARCHAR NOT NULL, -- GENERIC / DATABASE / VALIDATION as written; open column
  class_name  VARCHAR,
  code        VARCHAR,
  sql_state   VARCHAR,
  matches     VARCHAR,
  description VARCHAR,
  PRIMARY KEY (type_name, position),
  FOREIGN KEY (type_name) REFERENCES intent_error (type_name)
);

-- @node on an object type: node identity. The type-name fallback for typeId
-- and the catalog-PK fallback for key columns are derivations; the
-- SDL-versus-jOOQ-metadata precedence rules are detections.
CREATE TABLE intent_node (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL, -- half of the site FK, so NOT NULL; a graphitron application always has an SDL position
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  type_id          VARCHAR, -- as written
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- An ordered keyColumns entry of an @node.
CREATE TABLE intent_node_key_column (
  type_name  VARCHAR NOT NULL,
  position   INT     NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (type_name, position),
  FOREIGN KEY (type_name) REFERENCES intent_node (type_name)
);

-- @nodeId on a field or input field.
CREATE TABLE intent_field_node_id (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  node_type_ref VARCHAR, -- typeName as written; author-spelled type reference, no FK, inference when NULL is a derivation
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @nodeId on an argument.
CREATE TABLE intent_argument_node_id (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  node_type_ref VARCHAR,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);

-- @lookupKey on an argument: the live site, a marker.
CREATE TABLE intent_argument_lookup_key (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);

-- @lookupKey on an input field: the retired site; the sole consumer is the
-- located migration rejection.
CREATE TABLE intent_field_lookup_key (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @splitQuery on a field: a marker.
CREATE TABLE intent_split_query (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @tenantFanOut on a field: a marker; its many conflict arms are detections.
CREATE TABLE intent_tenant_fan_out (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @pivot on a field: the aggregate-projection spec.
CREATE TABLE intent_pivot (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  on_column      VARCHAR NOT NULL, -- the on: argument, the discriminator column as written
  value_column   VARCHAR NOT NULL, -- the value: argument as written
  vocabulary_ref VARCHAR, -- names an enum type; author-spelled, no FK
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @routine on a field: one row per application (repeatable). The table chain
-- interleaves these with intent_field_reference rows in written order.
CREATE TABLE intent_routine (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  routine_ref    VARCHAR NOT NULL, -- the routine name as written (may carry a schema qualifier)
  arg_mapping    VARCHAR,
  column_mapping VARCHAR,
  PRIMARY KEY (type_name, field_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

CREATE TABLE intent_routine_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES intent_routine (type_name, field_name, ordinal)
);

-- columnMapping pairs bind routine parameters to previous-node columns; a
-- dotted right side is captured as written and rejected by detection.
CREATE TABLE intent_routine_column_mapping_pair (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL,
  param_name VARCHAR NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES intent_routine (type_name, field_name, ordinal)
);

-- @experimental_constructType has no relation, and unlike every other name
-- in this stratum it is not a graphitron directive: its declaration in
-- directives.graphqls is a bug (the census found no consumer anywhere; the
-- declaration's only effect is that emission strips applications, silently
-- swallowing a directive graphitron does not own). Once the stray
-- declaration is removed the name is foreign like any user-authored
-- directive and its applications land in the applied_ family as fidelity
-- rows, re-emitted verbatim; the store needs no special case for it.

-- @discriminate on an interface or union: the discriminator column.
CREATE TABLE intent_discriminate (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL, -- half of the site FK, so NOT NULL; a graphitron application always has an SDL position
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  on_column        VARCHAR NOT NULL, -- the on: argument as written; catalog resolution is a derivation
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- @discriminator on an object type: the participant's discriminator value.
CREATE TABLE intent_discriminator (
  type_name           VARCHAR NOT NULL,
  source_name         VARCHAR NOT NULL, -- half of the site FK, so NOT NULL
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_line         INT,
  source_column       INT,
  discriminator_value VARCHAR NOT NULL, -- the value: argument as written (VALUE alone is an H2 reserved word)
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- Federation @key, decoded for consumption (its verbatim twin lives in
-- applied_type_directive for re-emission; a gate query pins agreement).
CREATE TABLE intent_federation_key (
  type_name        VARCHAR NOT NULL,
  ordinal          INT     NOT NULL, -- @key is repeatable; document order
  source_name      VARCHAR NOT NULL, -- the applying declaration site; a synthesized key inherits the causing authored site of the same type, so the reference holds for it too
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  fields_sdl       VARCHAR NOT NULL, -- the field-set literal as written
  resolvable       BOOLEAN,          -- as written; NULL when omitted
  PRIMARY KEY (type_name, ordinal),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- An ordered element of a @key field set (the field-set grammar is a parse
-- boundary, so the decode happens at capture). The grammar admits nested
-- selections as dotted paths; that today's consumer rejects nesting is a
-- detection, not a capture limit.
CREATE TABLE intent_federation_key_field (
  type_name  VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL, -- 0-based within the field set
  field_path VARCHAR NOT NULL, -- dotted path for nested selections
  PRIMARY KEY (type_name, ordinal, position),
  FOREIGN KEY (type_name, ordinal)
    REFERENCES intent_federation_key (type_name, ordinal)
);

-- @link on the schema definition, decoded. All @link applications decode
-- here (the verbatim twin sits in applied_schema_directive); whether a link
-- is the federation opt-in is a predicate over url, a derivation. @tag and
-- @shareable get no decoded relations: their only readers are the expansion
-- machinery itself, which is the capture walk with the AST in hand, so
-- downstream consumers see them only as fidelity rows for re-emission.
CREATE TABLE intent_link (
  ordinal       INT     NOT NULL, -- @link is repeatable; document order
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  url           VARCHAR, -- as written
  PRIMARY KEY (ordinal)
);

-- An ordered import entry of an @link, covering both the string form and the
-- object form.
CREATE TABLE intent_link_import (
  link_ordinal INT     NOT NULL,
  position     INT     NOT NULL,
  name         VARCHAR NOT NULL, -- the imported name (the object form's name:)
  alias        VARCHAR,          -- the object form's as:, when written
  PRIMARY KEY (link_ordinal, position),
  FOREIGN KEY (link_ordinal) REFERENCES intent_link (ordinal)
);

-- Retired directives: existence only, per the rules above.

-- @notGenerated, like @experimental_constructType above, is not a graphitron
-- directive and its declaration in directives.graphqls is a bug, so it gets
-- no relations. Once the stray declaration is removed its applications take
-- the applied_ fidelity path, and the current hard rejection ("no longer
-- supported") becomes, if it is kept at all, a detection over the directive
-- name in the applied_ rows; whether to keep steering on a name graphitron
-- does not own is a directive-lifecycle question outside this spec.

-- @multitableReference (removed) on a field; routes is never read.
CREATE TABLE intent_multitable_reference (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @record (deprecated, ignored) on an object or input type. class_name is
-- the one payload value a consumer reads: the warning arms compare it
-- against the reflected backing class.
CREATE TABLE intent_record (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL, -- half of the site FK, so NOT NULL; a graphitron application always has an SDL position
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  class_name       VARCHAR, -- record.className as written
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);

-- The tolerant-decode overflow: a graphitron application argument whose
-- literal does not fit the declared shape decodes to NULL in its typed
-- column and quarantines its raw text here, so the authored value is never
-- lost and the malformed-literal detection has its row. Empty while assembly
-- runs upstream.
CREATE TABLE intent_undecoded_argument (
  source_name             VARCHAR NOT NULL, -- the application's position identifies the row; authored applications always have one
  source_line             INT     NOT NULL,
  source_column           INT     NOT NULL,
  directive_name          VARCHAR NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL, -- the literal as written, rendered from the AST
  PRIMARY KEY (source_name, source_line, source_column, directive_name, directive_argument_name)
);
```

Macros expand during the same capture walk. `@asConnection` and `@asFacet` are schema
construction, not questions over facts, and the visitor holds everything construction needs
(the AST, the wrapping decode, the naming conventions), so the walk expands them inline:
a macro's contribution enters as declaration sites at the causing position (a definition
site for each type it creates, an extension site where it adds members to an existing type),
its element rows hang off those sites through the ordinary declaration reference, and the
provenance relations below mark the sites, the synthesized applications, and the rewrites.
The authored picture is the rows whose sites are authored; the effective picture is the
tables as they stand; the round trip emits the effective picture minus the graphitron
namespace. Name collision is
author-reachable (an author can declare the type a macro would synthesize), so the visitor
resolves collisions before inserting, following the current synthesis semantics; the
primary-key constraint stays a capture-bug detector, never an author-triggerable throw.
Federation's `@key` synthesis is a walk macro from day one, with no interim mechanics:
because capture reads the registry *before* the synthesis rewrites, the walk runs the key
synthesis itself and writes provenance directly, like every other macro. The registry
rewrite (`KeyNodeSynthesiser`) keeps running for the legacy pipeline's assembly only; while
both implementations of the rule are live, the agreement suite's applied-directive anchor
pins them to each other (the store's `@key` rows against the assembled schema's), and the
rewrite retires with its last legacy consumer.

```sql
-- ==== Macro synthesis provenance =============================================
-- The expansion's own record: which graphql_ rows a macro added, and the
-- authored text where the macro rewrote it. Synthesized rows inherit the
-- causing application's source position; these relations are what say a
-- position means "caused here" rather than "written here".

-- A declaration site was contributed by a macro rather than the author: a
-- definition site when the macro creates the type (Connection, Edge, facet
-- shapes, at merge_ordinal 0), an extension site when it adds members to an
-- existing type (the Query fields federation adds from @link), and an empty
-- extension site when a later carrier touches a shared machinery type
-- (PageInfo), so carrier multiplicity is the site count. Synthesized element
-- rows hang off these sites through the ordinary declaration reference,
-- which is what marks additions without per-element provenance; a type is
-- synthesized exactly when its merge_ordinal-0 site is.
CREATE TABLE graphql_type_declaration_synthesis (
  type_name          VARCHAR NOT NULL,
  source_name        VARCHAR NOT NULL, -- the causing application's position, which is the site's identity
  source_line        INT     NOT NULL,
  source_column      INT     NOT NULL, -- the site key's fourth part, as on graphql_type_declaration
  macro              VARCHAR NOT NULL, -- which expansion contributed the site
  carrier_type_name  VARCHAR,          -- the causing coordinate; NULL for schema-level causes (@link)
  carrier_field_name VARCHAR,
  PRIMARY KEY (type_name, source_name, source_line, source_column),
  FOREIGN KEY (type_name, source_name, source_line, source_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column),
  CHECK (macro IN ('CONNECTION', 'FACET', 'FEDERATION'))
);

-- A field's type expression was rewritten by a macro; the authored expression
-- survives here while the field's graphql_field row holds the effective one.
CREATE TABLE graphql_field_synthesis (
  type_name         VARCHAR NOT NULL,
  field_name        VARCHAR NOT NULL,
  macro             VARCHAR NOT NULL,
  authored_type_sdl VARCHAR NOT NULL, -- the type expression as the author wrote it, pre-expansion
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name),
  CHECK (macro IN ('CONNECTION', 'FACET'))
);

-- A type-level directive application was synthesized rather than authored
-- (federation key synthesis; the application itself sits in
-- applied_type_directive and intent_federation_key like any other, and must
-- re-emit, so provenance is this relation, not exclusion).
CREATE TABLE applied_type_directive_synthesis (
  type_name      VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  macro          VARCHAR NOT NULL,
  PRIMARY KEY (type_name, directive_name, ordinal),
  FOREIGN KEY (type_name, directive_name, ordinal)
    REFERENCES applied_type_directive (type_name, directive_name, ordinal),
  CHECK (macro IN ('FEDERATION_KEY'))
);
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

Both loads are infallible by construction, and construction is the only guarantee in play: the
registry validates nothing (it retains undeclared directives, unknown argument names,
wrong-typed literals, missing required arguments, and duplicate declarations without error),
which is exactly why every capture path is tolerant, recording what does not fit raw and
located instead of throwing. Capture is total, with no reachability pruning.

- **SDL load.** One walk fills the `graphql_`, `applied_`, and `intent_` families, reading the
  **`TypeDefinitionRegistry`**, not the assembled schema, and that source is decided here.
  Three reasons. Parse-to-registry is graphql-java's linear half (5.5 ms at sakila scale,
  46.8 ms at 10x) while assembly is the superlinear half (28 ms, 2.5 s; the diffing audit
  carries the split), so capture never pays the wall. The registry holds what assembly drops
  (applied directives on built-in scalar declarations), so the
  `GraphitronSchemaBuilder.recordSdlScalarDirectives` carve-out dies with no replacement.
  And the two guarantees assembly offered convert into store detections, R589's thesis
  applied at the parse boundary: a dangling author-spelled reference or a malformed directive
  argument is an author error that mints a located diagnostic, never a reason capture cannot
  run. The walk reads the registry after the loading rewrites (federation `@link` imports,
  `@oneOf` support, the bundled definitions) and before the synthesis rewrites, and is plain
  iteration over definitions and extensions in document order, no graphql-java visitor
  machinery; effective-type merging is the ordinal rule the schema section states. The
  registry's API is congruent with the schema families, so the walk transcribes rather than
  translates: `types()` is the type rows plus their definition sites, the per-kind
  `*TypeExtensions()` maps arrive pre-grouped by type name, `getParseOrder()` is the
  document-order oracle behind the ordinals, `scalars()` includes the built-ins that seed the
  engine-provided rows, and every node underneath is AST (source locations throughout, the
  three-node type tree for the wrapping decode, applied argument values rendering to
  `value_sdl` and walking into the semantic decode with no coerced runtime values to undo).
  One divergence from the API's shape is deliberate: the registry keeps definitions and
  extensions in separate per-kind maps because its consumer patches them differently at
  assembly, while the store's six element families each need one monomorphic contributed-by
  reference, which only the unified declaration relation gives them (split site tables would
  polymorph that reference across every element family; the unified key is the site's
  location, the identity a syntactic occurrence natively has, merge position is a
  capture-assigned column, and `is_extension` states the site's written form, a fact, not a
  key hole).
  Transcribing the two maps into the one relation is the same two loops the merge runs
  anyway. Four jobs
  in one pass: existence rows, fidelity rows for non-graphitron applications, semantic decode
  of graphitron and federation applications (federation dual-written to both strata), and
  macro expansion with its provenance rows. Capture never throws, even on schemas assembly
  would reject; while the shadow window lasts, assembly still runs upstream and rejects
  invalid schemas first, so the tolerant paths (dangling references, duplicate declarations
  quarantined in `graphql_duplicate_declaration`, undecodable argument
  literals quarantined raw with their location in `intent_undecoded_argument`) stay dormant
  until the LSP consumer arrives, and the
  agreement tests see only valid input. The capture writer gets its own package in core,
  importing the module's generated classes; it does not live inside
  `no.sikt.graphitron.facts`, whose import-direction allowance stays intact for the legacy
  side it serves. The walk constraint stands regardless of placement: a single pass over the
  registry, not two parallel walkers drifting apart.
- **Catalog load.** Fills the `catalog_` family from the same jOOQ catalog walk that builds
  `CatalogFacts` today, and the `extension_` family from the `ClasspathScanner` emission. Runs
  inside the codegen classloader scope; only values cross out, which the store enforces.

Insertion through the module's own generated jOOQ classes, so capture dogfoods the surface every
later consumer uses. A duplicate primary key on any base relation throws: that is a capture bug,
not an author error, per the constraint split R589 fixes, and it stays a capture bug only
because first-wins runs in front of every element-level natural key. The registry retains a
duplicated field (in one body or via an extension), a repeated argument, enum value, union
member, and implements entry, a second application of a single-application graphitron
directive, and a repeated location or formal argument in a directive definition
(`directive @foo on OBJECT | OBJECT`, `directive @foo(x: Int, x: String)`), all without
error, so each of those keys is author-reachable; capture writes the first occurrence in
merge order and quarantines the losers in `graphql_duplicate_declaration`, never throwing
on `enum E { A A }`. The duplication family the registry itself rejects at parse is a
second base *definition*, of a type or of a directive, so the TYPE case is reachable only
on the LSP's per-file fragment path, where the same first-wins rule covers it (an extension is the
ordinary path and gets its own declaration row; the editing transient the LSP will constantly
see is exactly the accidental second definition).

Incremental refresh falls out of this design, and the substrate protects the property now so a
later consumer can buy it. Capture is *type-local*: every SDL row's content is a function of
its own type's declaration sites (base plus extensions, the ordinal rule's merge) and nothing
else, because everything cross-element is derivation. When one schema file changes, the
refresh unit is therefore the types that file declares or extends, old version and new (the
declaration relation is the index that answers which types a file touches): parse
the one file to its own registry fragment, delete the partition (the synthesis provenance
relations make orphan cleanup exact, shared machinery types refcounting by carrier row),
re-walk it, and re-run the derivation strata, which the first spike priced under 20 ms for the
SDL stratum (synthesized sites are stamped with their carrier's file, so synthesized types sit
in the same declaration index as everything else). The strata re-run over the whole store,
never scoped to the changed partition, because classification is inherently cross-file: a
field's verdict reads its named type's kind, its arguments' types, and the tables they bind,
all free to live in files the edit never touched, so an edit to one file can flip verdicts on
types it never mentions. That is the deeper reason no classification happens during a file's
parse (the file in hand simply does not contain the facts a verdict needs), the same
observation the FK doctrine makes about cross-file references; per-file scope belongs to
capture alone, and the derivations stay cheap enough to re-run whole. No diffing is involved,
and unchanged
files are never re-read; the store itself
is the accumulated registry, and with the R597 cache an editor session boots warm and
refreshes per file from there. The refresh mechanics land with the LSP consumer migration,
not here; this increment only refuses to break type-locality, which is a review rule on
capture code: nothing at capture reads across types, and no verdict is computed during a
file's parse.

## What this iteration deliberately leaves out

- **The derived stratum.** Claims, reachability, demand, occurrence paths, diagnostics, and
  command relations are absent by design: per the strangler frame, a derivation's DDL lands with
  the first consumer that migrates onto it, and several shapes hang on R589's open questions
  (the axis declaration's home, inferred-claim provenance, slot-fact granularity, the
  path-valued key; slot-fact granularity left the list when the semantic stratum moved the
  decoded shapes into capture). The spike DDL in
  `roadmap/audits/2026-08-05-fact-base-h2-spike.md` is the standing sketch for that stratum.
- **Routines.** A catalog-side routine census is capture by the decode rule and cheap to
  load (the `@routine` applications themselves are already captured in the semantic
  stratum), but the catalog routine's identity is not settled: routines overload, carry
  parameter modes, and split table-valued
  from scalar, so the key needs the same inventory-grounded design the relations above got.
  Guessing the key is the speculation; the census lands with the `@routine` consumer whose
  queries fix its shape.
- **Javadoc and Java source positions.** The request-time join against `SourceWalker` is a
  deliberate cadence separation (a `.java` edit is visible without a rebuild) and stays outside
  the store.
- **Pipeline-output facts.** What a run *produced*, reported by oracles that run after
  capture: generated-code compile diagnostics (javac output the dev loop collects per
  compile round; the batch pipeline never sees it at all), and the emitted-file inventory.
  These are facts and they belong in the store eventually, but they cannot be filled by this
  item's capture loads (they do not exist at capture time) and need a writer with its own
  lifecycle, so the family lands as its own item with its first consumer:
  `pipeline-output-facts-family`.
- **Derived `GraphitronSchema` components.** Arrivals, reachable source shapes, tenant scopes
  and bindings, connection synthesis, operation members, and delivery facts are derivations over
  the base facts above; none of them is capture, so none of them is a table here.
- **Resolution facts and effective values.** Capture decodes structure (type wrapping,
  structured directive arguments, field sets, descriptors) but resolves nothing: whether a
  written table, constraint, or class name denotes a real one is a detection over the catalog
  and extension families, and effective values (defaults applied, fallback names filled) are
  derivation views; both land with consumers.

## Acceptance

- `graphitron-model` builds before core; its jOOQ classes are generated by the live-H2
  metadata path from a store booted from the DDL resource alone (no external database
  process, nothing generated is committed) and core compiles against them.
- The generator bootstraps the store at startup: fresh H2 in-memory database per run, DDL
  executed from the same resource the codegen read.
- Both capture loads run inside the standard build; the full fixture corpus shows generated-output
  identity, and no diagnostic text changes.
- Agreement tests pin the shadow copy to the live pipeline through one mechanical driver: it
  enumerates the generated jOOQ tables and fails on any relation without a registered agreement
  source, so a new relation cannot arrive unchecked. Registrations form a closed set of three
  arms, so every enumerated relation has a declared answer and there is no skip list:
  containment for the SDL side (capture is total, `GraphitronSchema` is reachability-pruned, so
  the store contains the model), equality for the `CatalogFacts` and scanner censuses, and
  derived for shipped views, which register the base relations they project so their agreement
  is vacuous by construction (`applied_directive_site` is the first derived registrant, and the
  later strata land as registrations, not exemptions). The
  anchor checks: type census against `GraphitronSchema.types`, per-coordinate applied-directive
  counts against the SDL, the semantic relations against the minted model components and
  directive-resolver outputs they shadow, synthesis provenance against the
  `connectionSynthesis` component, catalog table and column census against `CatalogFacts`,
  extension method census against the scanner's `CompletionData` view. Two census comparisons
  are projections rather than mirrors, and the driver compares them as such: `catalog_key`
  folds to `CatalogFacts`' uniqueKeys view before comparing (that view excludes the primary
  key and dedupes on column set), and `extension_method` compares descriptor-erased, because
  `CompletionData.Method` carries no descriptor. They are the shadow
  period's honesty check and retire as consumers migrate.
- The gate family runs against the bootstrapped store: comment coverage (every table and column
  commented, checked via `INFORMATION_SCHEMA`) and one query per cross-relation invariant the
  DDL cannot state (at most one `is_primary` row per catalog table, `default_value_sdl` only
  under INPUT_OBJECT parents, application ordinals and `merge_ordinal` dense from 0 per group,
  wrapping decode consistent with `type_sdl` where SQL can express the correspondence, no
  graphitron-namespace row in any `applied_` family, the federation dual projection in
  agreement). A repeated application of a non-repeatable directive is deliberately not in
  this list: under registry capture it is author-reachable, so it is a detection.
- All tests live in `graphitron` at the appropriate tier (the tier meta-annotations live in
  core's test root and the module order forbids the reverse dependency); `graphitron-model`
  hosts model code only: the DDL, the codegen output, the bootstrap, and the codegen driver,
  never tests. (Function aliases are a contingency; if one ever ships it lives here or in the
  functions sibling, per the module section.)
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
- **`roadmap/audits/2026-08-05-h2-functions-jooq-spike.md`:** the dated spike record grounding
  the function surface: the live-H2 codegen decision, the single-module build wiring, the
  scalar-bridge shape, and the table-valued-function ruling.
