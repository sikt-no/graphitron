---
id: R595
title: "The graphitron-model module exists and capture fills it"
status: Spec
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
  key carries an ordinal (the `graphql_` families), every occurrence is captured and a repeat
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
section. Four families, each named for **whose vocabulary a row is written in**. `graphql_` is
reserved for generic GraphQL: a row any SDL reader could produce from the document without
knowing graphitron exists, which is every declaration, every directive definition, and every
directive application. `graphitron_` is what graphitron makes of that document: the decoded
directives, and the provenance of the rows macro expansion mints. `catalog_` is jOOQ catalog
facts and `extension_` the consumer's compiled extension classes.

The SDL families stack, `graphql_` under `graphitron_` under a third name, `intent_`, held in
reserve and filled by nothing here. A `graphitron_` row is still a transcription: it restates
what an application spelled, in graphitron's vocabulary instead of the document's, which is why
`graphitron_field_condition_context_arg` is a decode of an argument and not a claim about
anyone's intent. `intent_` is for the layer that resolves and combines those readings into what
the generator will do, and a new derived stratum is its own change rather than something a
relation drifts into.

Two consequences of naming families by vocabulary rather than by treatment. The `graphql_`
family is a **total transcription** with no hole where graphitron's namespace was: whether an
application survives into the emitted schema is a `source_name` question answered at emission,
not something capture decides by choosing a table, and a directive that is both re-emitted and
decoded (federation's `@key`) is simply a row in each family rather than a special case. And
all three synthesis-provenance relations are `graphitron_`, because macro expansion is
graphitron's doing however generic the relation it annotates.

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
-- may sit, what arguments it declares. Capture is total over the registry, so
-- user-authored, spec built-in, federation-imported, and graphitron's own
-- bundled definitions are all rows. An emitter re-declares the first three and
-- strips the fourth, telling them apart by source_name; the family does not
-- encode the answer. Totality is what makes every application's directive name
-- resolve to a definition, so reading a repeatable flag or an argument default
-- stays a join rather than a namespace case.

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

The `graphql_` families transcribe the application surface, and the transcription is total:
every application the author wrote is a row, graphitron's namespace included. The round trip
reads them by filtering rather than by trusting the family to have pre-filtered, because which
applications survive into the emitted runtime schema is a `source_name` question and belongs at
emission. An application that also carries meaning gets a second, decoded row in the
`graphitron_` family instead of moving out of this one, so graphitron's own directives and
federation's `@key` take the same shape and a gate query pins the two projections in agreement.

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
-- author passed. Values are the rendered SDL literal, so an application is
-- legible here without knowing what the directive means. Capture is total:
-- graphitron's own applications are rows like any other, and the ones that
-- carry meaning additionally get a decoded row in the semantic stratum.

-- A directive is applied to the schema definition (@link lives here).
CREATE TABLE graphql_schema_directive (
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- 0 unless the directive is repeatable; repeats number in document order
  source_name    VARCHAR,          -- position of the application site
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (directive_name, ordinal)
);

-- An argument the author passed to a schema-level application.
CREATE TABLE graphql_schema_directive_arg (
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL, -- the definition's formal argument this value binds
  value_sdl               VARCHAR NOT NULL, -- the value as written, rendered from the AST; omitted arguments are absent rows
  PRIMARY KEY (directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (directive_name, ordinal)
    REFERENCES graphql_schema_directive (directive_name, ordinal)
);

-- A directive is applied to a type (OBJECT, INTERFACE, UNION, ENUM,
-- INPUT_OBJECT, or SCALAR; the parent kind is a join away).
CREATE TABLE graphql_type_directive (
  type_name           VARCHAR NOT NULL,
  directive_name      VARCHAR NOT NULL,
  ordinal             INT     NOT NULL, -- as on graphql_schema_directive; federation's @key repeats here
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
CREATE TABLE graphql_type_directive_arg (
  type_name               VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, directive_name, ordinal)
    REFERENCES graphql_type_directive (type_name, directive_name, ordinal)
);

-- A directive is applied to a field (output or input-object; the parent
-- type's kind decides which SDL location this was).
CREATE TABLE graphql_field_directive (
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
CREATE TABLE graphql_field_directive_arg (
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, field_name, directive_name, ordinal)
    REFERENCES graphql_field_directive (type_name, field_name, directive_name, ordinal)
);

-- A directive is applied to a field argument (ARGUMENT_DEFINITION site).
CREATE TABLE graphql_argument_directive (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  argument_name  VARCHAR NOT NULL, -- the SDL argument the directive sits on
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- as on graphql_field_directive
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, field_name, argument_name, directive_name, ordinal),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);

-- An argument the author passed to an argument-level application.
CREATE TABLE graphql_argument_directive_arg (
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  argument_name           VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, field_name, argument_name, directive_name, ordinal)
    REFERENCES graphql_argument_directive (type_name, field_name, argument_name, directive_name, ordinal)
);

-- A directive is applied to an enum value (@deprecated lives here, and so does
-- the graphitron enum-value inventory, which is additionally decoded).
CREATE TABLE graphql_enum_value_directive (
  type_name      VARCHAR NOT NULL,
  value_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL, -- as on graphql_schema_directive
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, value_name, directive_name, ordinal),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);

-- An argument the author passed to an enum-value application.
CREATE TABLE graphql_enum_value_directive_arg (
  type_name               VARCHAR NOT NULL,
  value_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, value_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, value_name, directive_name, ordinal)
    REFERENCES graphql_enum_value_directive (type_name, value_name, directive_name, ordinal)
);

-- The one view the DDL ships: every application regardless of site, so a
-- consumer that wants "all applications of @x" reads one relation.
CREATE VIEW graphql_directive_site AS
SELECT 'SCHEMA' AS site_kind, CAST(NULL AS VARCHAR) AS type_name,
       CAST(NULL AS VARCHAR) AS member_name, CAST(NULL AS VARCHAR) AS argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_schema_directive
UNION ALL
SELECT 'TYPE', type_name, NULL, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_type_directive
UNION ALL
SELECT 'FIELD', type_name, field_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_field_directive
UNION ALL
SELECT 'ARGUMENT', type_name, field_name, argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_argument_directive
UNION ALL
SELECT 'ENUM_VALUE', type_name, value_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_enum_value_directive;
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
- **Every application-level relation carries the application's own source position.** A decoded
  row does have a `graphql_` twin, but a consumer working in graphitron's vocabulary should not
  have to join back to find where the author wrote something; detections mint located
  diagnostics from these columns, and document order between applications is recoverable
  where it is load-bearing (a field's `@routine` and `@reference` applications compose one
  table chain in written order, so the chain is an ORDER BY over positions). Child relations
  locate through their parent. On type-coordinate relations the position columns sit beside
  the declaration-site reference, the `graphql_field` pattern: `source_name` doubles as the
  site key part, `source_line` and `source_column` are the application's own.
- **Repeatable applications key by capture-assigned ordinal in document order**, as in the
  `graphql_` families. This also covers repetition the directive's own semantics key
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
  literal lands in `graphitron_undecoded_argument` with its location, so the authored text is
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
gets no decoded relation, and its applications are transcribed into the `graphql_` family like
every other application whether or not the stray declaration is removed. The `graphitron_`
prefix names what a row is written in: graphitron's vocabulary for what an application at a
coordinate spelled.

```sql
-- ==== Semantic stratum ======================================================

-- @table on a type: the author binds the type to a database table. On an
-- INPUT_OBJECT the application is captured like any other; the ignored-and-
-- warned status of that site is a detection.
CREATE TABLE graphitron_table (
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
CREATE TABLE graphitron_field_binding (
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
CREATE TABLE graphitron_argument_binding (
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
CREATE TABLE graphitron_enum_value_binding (
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
CREATE TABLE graphitron_scalar_type (
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
-- comes from graphitron_enum_value_binding).
CREATE TABLE graphitron_enum (
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
CREATE TABLE graphitron_field_condition (
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
CREATE TABLE graphitron_field_condition_context_arg (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL, -- 0-based position in the contextArguments list
  name       VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name)
    REFERENCES graphitron_field_condition (type_name, field_name)
);

-- An ordered pair of a field-site @condition's argMapping. Position-keyed so
-- an author's duplicate parameter survives for the duplicate detection.
CREATE TABLE graphitron_field_condition_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL, -- the Java parameter (left side)
  argument_path VARCHAR NOT NULL, -- the right side as written: a GraphQL argument name or dotted input path
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name)
    REFERENCES graphitron_field_condition (type_name, field_name)
);

-- @condition on an argument: the same decode over the three-part coordinate.
CREATE TABLE graphitron_argument_condition (
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

CREATE TABLE graphitron_argument_condition_context_arg (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  name          VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, position),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphitron_argument_condition (type_name, field_name, argument_name)
);

CREATE TABLE graphitron_argument_condition_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, position),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphitron_argument_condition (type_name, field_name, argument_name)
);

-- @reference on a field or input field: one row per application, because an
-- application is a fact of its own. An empty path means FK auto-discovery
-- between the endpoints, and the rule that every application in a
-- multi-application chain must carry an element is per-application; both are
-- invisible in a flat concatenated chain. The effective chain the consumers
-- read is the steps ordered by (ordinal, position), and the written-order
-- interleaving with @routine applications on the same field is an ORDER BY
-- over the two relations' source positions.
CREATE TABLE graphitron_field_reference (
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
CREATE TABLE graphitron_field_reference_step (
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
    REFERENCES graphitron_field_reference (type_name, field_name, ordinal)
);

-- An ordered pair of a step condition's argMapping.
CREATE TABLE graphitron_field_reference_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, ordinal, step_position)
    REFERENCES graphitron_field_reference_step (type_name, field_name, ordinal, position)
);

-- @reference on an argument: the same family over the three-part coordinate.
CREATE TABLE graphitron_argument_reference (
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

CREATE TABLE graphitron_argument_reference_step (
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
    REFERENCES graphitron_argument_reference (type_name, field_name, argument_name, ordinal)
);

CREATE TABLE graphitron_argument_reference_step_arg_mapping_pair (
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
    REFERENCES graphitron_argument_reference_step (type_name, field_name, argument_name, ordinal, position)
);

-- @referenceFor on a field: an explicit join path for one participant of a
-- multi-table interface or union child. Keyed by ordinal per the repeatable
-- rule; the consumption-side keying by participant makes a repeated
-- participant a detection, never a collision.
CREATE TABLE graphitron_reference_for (
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

CREATE TABLE graphitron_reference_for_step (
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
    REFERENCES graphitron_reference_for (type_name, field_name, ordinal)
);

CREATE TABLE graphitron_reference_for_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, ordinal, step_position)
    REFERENCES graphitron_reference_for_step (type_name, field_name, ordinal, position)
);

-- @service on a field: the external service reference.
CREATE TABLE graphitron_service (
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

CREATE TABLE graphitron_service_context_arg (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name       VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES graphitron_service (type_name, field_name)
);

CREATE TABLE graphitron_service_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES graphitron_service (type_name, field_name)
);

-- @externalField on a field: the static jOOQ-Field method. The omitted-method
-- fallback (the field name) is a derivation; arg_mapping is inert here (raw
-- column only, its rejection is presence-triggered).
CREATE TABLE graphitron_external_field (
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
CREATE TABLE graphitron_source_row (
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
CREATE TABLE graphitron_connection (
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
-- graphitron_field_binding, and every misuse arm is a detection.
CREATE TABLE graphitron_facet (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @orderBy on an argument: a marker; the input shape rules are detections.
CREATE TABLE graphitron_order_by (
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
CREATE TABLE graphitron_order (
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
CREATE TABLE graphitron_order_field (
  type_name  VARCHAR NOT NULL,
  value_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL, -- FieldSort.name, a column reference as written
  collate    VARCHAR,
  direction  VARCHAR, -- as written; author-spelled enum literal, open column
  PRIMARY KEY (type_name, value_name, position),
  FOREIGN KEY (type_name, value_name) REFERENCES graphitron_order (type_name, value_name)
);

-- @index on an enum value: the deprecated alias of @order(index:), still
-- honoured when @order is absent; the deprecation is a lint detection.
CREATE TABLE graphitron_index (
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
CREATE TABLE graphitron_default_order (
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

CREATE TABLE graphitron_default_order_field (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL,
  collate    VARCHAR,
  direction  VARCHAR,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES graphitron_default_order (type_name, field_name)
);

-- @mutation on a field: the DML statement spec.
CREATE TABLE graphitron_mutation (
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
CREATE TABLE graphitron_error (
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
CREATE TABLE graphitron_error_handler (
  type_name   VARCHAR NOT NULL,
  position    INT     NOT NULL,
  handler     VARCHAR NOT NULL, -- GENERIC / DATABASE / VALIDATION as written; open column
  class_name  VARCHAR,
  code        VARCHAR,
  sql_state   VARCHAR,
  matches     VARCHAR,
  description VARCHAR,
  PRIMARY KEY (type_name, position),
  FOREIGN KEY (type_name) REFERENCES graphitron_error (type_name)
);

-- @node on an object type: node identity. The type-name fallback for typeId
-- and the catalog-PK fallback for key columns are derivations; the
-- SDL-versus-jOOQ-metadata precedence rules are detections.
CREATE TABLE graphitron_node (
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
CREATE TABLE graphitron_node_key_column (
  type_name  VARCHAR NOT NULL,
  position   INT     NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (type_name, position),
  FOREIGN KEY (type_name) REFERENCES graphitron_node (type_name)
);

-- @nodeId on a field or input field.
CREATE TABLE graphitron_field_node_id (
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
CREATE TABLE graphitron_argument_node_id (
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
CREATE TABLE graphitron_argument_lookup_key (
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
CREATE TABLE graphitron_field_lookup_key (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @splitQuery on a field: a marker.
CREATE TABLE graphitron_split_query (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @tenantFanOut on a field: a marker; its many conflict arms are detections.
CREATE TABLE graphitron_tenant_fan_out (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);

-- @pivot on a field: the aggregate-projection spec.
CREATE TABLE graphitron_pivot (
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
-- interleaves these with graphitron_field_reference rows in written order.
CREATE TABLE graphitron_routine (
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

CREATE TABLE graphitron_routine_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES graphitron_routine (type_name, field_name, ordinal)
);

-- columnMapping pairs bind routine parameters to previous-node columns; a
-- dotted right side is captured as written and rejected by detection.
CREATE TABLE graphitron_routine_column_mapping_pair (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL,
  param_name VARCHAR NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES graphitron_routine (type_name, field_name, ordinal)
);

-- @experimental_constructType has no relation, and unlike every other name
-- in this stratum it is not a graphitron directive: its declaration in
-- directives.graphqls is a bug (the census found no consumer anywhere; the
-- declaration's only effect is that emission strips applications, silently
-- swallowing a directive graphitron does not own). Once the stray
-- declaration is removed the name is foreign like any user-authored
-- directive and its applications land in the graphql_ family as fidelity
-- rows, re-emitted verbatim; the store needs no special case for it.

-- @discriminate on an interface or union: the discriminator column.
CREATE TABLE graphitron_discriminate (
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
CREATE TABLE graphitron_discriminator (
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
-- graphql_type_directive for re-emission; a gate query pins agreement).
CREATE TABLE graphitron_federation_key (
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
CREATE TABLE graphitron_federation_key_field (
  type_name  VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL, -- 0-based within the field set
  field_path VARCHAR NOT NULL, -- dotted path for nested selections
  PRIMARY KEY (type_name, ordinal, position),
  FOREIGN KEY (type_name, ordinal)
    REFERENCES graphitron_federation_key (type_name, ordinal)
);

-- @link on the schema definition, decoded. All @link applications decode
-- here (the verbatim twin sits in graphql_schema_directive); whether a link
-- is the federation opt-in is a predicate over url, a derivation. @tag and
-- @shareable get no decoded relations: their only readers are the expansion
-- machinery itself, which is the capture walk with the AST in hand, so
-- downstream consumers see them only as fidelity rows for re-emission.
CREATE TABLE graphitron_link (
  ordinal       INT     NOT NULL, -- @link is repeatable; document order
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  url           VARCHAR, -- as written
  PRIMARY KEY (ordinal)
);

-- An ordered import entry of an @link, covering both the string form and the
-- object form.
CREATE TABLE graphitron_link_import (
  link_ordinal INT     NOT NULL,
  position     INT     NOT NULL,
  name         VARCHAR NOT NULL, -- the imported name (the object form's name:)
  alias        VARCHAR,          -- the object form's as:, when written
  PRIMARY KEY (link_ordinal, position),
  FOREIGN KEY (link_ordinal) REFERENCES graphitron_link (ordinal)
);

-- Retired directives: existence only, per the rules above.

-- @notGenerated, like @experimental_constructType above, is not a graphitron
-- directive and its declaration in directives.graphqls is a bug, so it gets
-- no relations. Once the stray declaration is removed its applications take
-- the graphql_ fidelity path, and the current hard rejection ("no longer
-- supported") becomes, if it is kept at all, a detection over the directive
-- name in the graphql_ rows; whether to keep steering on a name graphitron
-- does not own is a directive-lifecycle question outside this spec.

-- @multitableReference (removed) on a field; routes is never read.
CREATE TABLE graphitron_multitable_reference (
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
CREATE TABLE graphitron_record (
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
CREATE TABLE graphitron_undecoded_argument (
  source_name             VARCHAR NOT NULL, -- the application's position identifies the row; authored applications always have one
  source_line             INT     NOT NULL,
  source_column           INT     NOT NULL,
  directive_name          VARCHAR NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL, -- the literal as written, rendered from the AST
  PRIMARY KEY (source_name, source_line, source_column, directive_name, directive_argument_name)
);
```

Macros expand during the same capture walk when their contribution is a function of one carrier's
own declaration, which is the same type-locality rule the rest of the walk follows. `@asConnection`
qualifies: it is schema construction rather than a question over facts, the visitor holds everything
that construction needs (the AST, the wrapping decode, the naming conventions), and its element type
enters as a name that nothing here resolves. So the walk expands it inline:
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

`@asFacet` is the expansion that does **not** qualify, and the boundary is worth stating because it
is the same boundary the decode rule draws. The `<Conn>Facets` and `<Scalar>FacetValue` shapes read
through the carrier's arguments into the filter input type's fields, for the `@asFacet` marker, the
`@field(name:)` binding, and the value's scalar and nullability. That is an aggregate over the whole
schema, not a function of the carrier's declaration: the filter input type is free to live in
another file, so minting the container during the walk would leave it stale under the per-file
refresh below whenever a facet is added to that other file. Every input the shape needs is already a
captured column (`graphitron_facet` marks the fields, `graphitron_field_binding` gives the column,
`graphql_argument` links carrier to filter type, `graphql_field` gives the value's scalar and
nullability), so it is computable as a query over captured columns, which puts it in a derived
stratum by the same rule that keeps name resolution and effective-value defaulting out. `@asFacet`
stays a marker relation here; the container is out of this item's scope with the rest of derivation,
and the macro domains on the provenance relations carry no FACET value as a result.

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
CREATE TABLE graphitron_type_declaration_synthesis (
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
  CHECK (macro IN ('CONNECTION', 'FEDERATION'))
);

-- A field's type expression was rewritten by a macro; the authored expression
-- survives here while the field's graphql_field row holds the effective one.
CREATE TABLE graphitron_field_synthesis (
  type_name         VARCHAR NOT NULL,
  field_name        VARCHAR NOT NULL,
  macro             VARCHAR NOT NULL,
  authored_type_sdl VARCHAR NOT NULL, -- the type expression as the author wrote it, pre-expansion
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name),
  CHECK (macro IN ('CONNECTION'))
);

-- A type-level directive application was synthesized rather than authored
-- (federation key synthesis; the application itself sits in
-- graphql_type_directive and graphitron_federation_key like any other, and must
-- re-emit, so provenance is this relation, not exclusion).
CREATE TABLE graphitron_type_directive_synthesis (
  type_name      VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  macro          VARCHAR NOT NULL,
  PRIMARY KEY (type_name, directive_name, ordinal),
  FOREIGN KEY (type_name, directive_name, ordinal)
    REFERENCES graphql_type_directive (type_name, directive_name, ordinal),
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

- **SDL load.** One walk fills the `graphql_` and `graphitron_` families, reading the
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
  literals quarantined raw with their location in `graphitron_undecoded_argument`) stay dormant
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

## Where this stands

Shipped: the module and both boots, the whole DDL, the SDL and catalog capture loads wired into
the pipeline, the gate family, the mechanical agreement driver with its type-census,
applied-directive, catalog-census and extension-census anchors, and federation `@key` synthesis
as a walk macro with its provenance rows and its anchor against `KeyNodeSynthesiser`.

`@asConnection` expands too: the directive-driven arm rewrites the carrier field to the Connection
it mints (the authored expression surviving in `graphitron_field_synthesis`) and mints the
Connection, Edge and shared PageInfo as ordinary declaration sites at the causing application's
position, each provenance-marked. The structural arm rewrites and mints nothing, so its rows are
the author's and the walk already had them.

The facets container moved out of this item rather than shipping: `@asFacet` is an aggregate over
the whole schema, so it belongs to a derived stratum, as the macro section above now records. The
provenance relations' macro domains lost their FACET value with it.

The synthesis-provenance anchor against `connectionSynthesis` is in, in two halves: the minted
Connection and Edge names against the relation's directive-driven rows (scoped to those arms, since
the facet arms are the derived stratum's business and the assertion names them as such), and the
rewritten carriers against the same rows. PageInfo agrees on a count rather than a set, being
schema-grain on the model and per-carrier in the store.

The semantic anchor is in, and it splits the `graphitron_` family's content in two before sampling
it. A relation's coordinate half, which for a marker relation is the whole of it, is already pinned
for every relation at once by the applied-directive anchor: that anchor equates the store's
per-coordinate, per-directive application counts with the SDL's, which is exactly the claim a marker
row makes. What the semantic anchor adds is the payload half, sampled by payload kind rather than by
relation, because the decode reaches a kind through one shared helper and a relation not sampled is
decoded by machinery a sampled one exercises. Four kinds carry a comparison: a scalar reference
(`@table(name:)`, `@node(typeId:)`) against the resolved `TableRef` and typeId, a list-valued
argument (`@node(keyColumns:)`) against the resolved column list compared in order, a flattened
`ExternalCodeReference` (`@service`) against the coordinate's `MethodBackedField`, and an
author-spelled enum literal (`@mutation(typeName:)`) against the DML arm the model lifted it into.
Each was mutation-checked against a perturbed decode and fails on its own perturbation alone.

The comparisons are conditional in one direction and containment in the other, and both are the
decode rule showing through. Conditional, because capture stores what the author wrote while the
model stores what resolution made of it: where an argument is omitted the store holds NULL and the
model holds the fallback, and a fallback is a derivation with nothing to agree with. Containment,
because the model is reachability-pruned and the store is total. What a subset does not buy is
per-relation payload agreement, which arrives with the consumer that migrates onto a relation and
brings its own tests; the mechanical driver is what keeps that honest, since a new relation still
cannot arrive without a registration.

## Rework from the In Review gate (2026-08-07)

Everything above shipped and `mvn install -Plocal-db` is green; the whole DDL matches this
item's schema section column for column and constraint for constraint, the mechanical driver
registers every generated relation, and the gate family covers the acceptance list (the
`is_primary` count gate is homed in the agreement suite, which is where a real catalog exists
to range over, and it says so). One blocker sends the item back.

**The pipeline captures after the federation synthesis rewrite, so the walk macro is inert in
production.** The item fixes the walk's reading position: "after the loading rewrites ... and
before the synthesis rewrites", and `MacroCapture`'s own javadoc restates it. Both production
call sites do the opposite. `GraphQLRewriteGenerator.loadAttributedRegistry` runs
`KeyNodeSynthesiser.apply(registry, ...)` in place on the same registry object it returns, and
both `buildOutput` and `runPipeline` hand that mutated registry to `FactCapture.run`. By the
time `MacroCapture.expandFederationKeys` looks, `hasIdKey` is already true for exactly the
types the rewrite touched (the two implementations gate on the same nodehood predicate and the
same single-`id` field set), so the macro contributes nothing. Measured on a one-node federated
fixture: capturing the pristine registry writes 1 `graphitron_type_directive_synthesis` row,
capturing the post-`KeyNodeSynthesiser` registry writes 0.

Three consequences, none of which a test currently sees:

- The synthesized `@key` is captured as an authored application, so the authored picture is no
  longer the anti-join against the provenance relations, which is the property the whole
  provenance family exists to buy.
- Its `graphql_type_directive.source_line` / `source_column` are NULL rather than the type's
  declaration site, because a `Directive` the rewrite built carries no `SourceLocation`.
- `federationKeySynthesisAgreesWithTheRewrite` passes because it captures a freshly parsed
  registry and applies the rewrite to a second copy only to compute the expectation. It pins a
  path the pipeline never takes, which is exactly the drift the shadow-period anchors exist to
  catch.

The fix is a placement decision, not a redesign: either capture the registry before
`KeyNodeSynthesiser` runs (`loadAttributedRegistry` already builds the `JooqCatalog` the
`NodeDeclaration` needs), or hand capture a pre-synthesis handle alongside the attributed one.
Whichever way it goes, the anchor should exercise the registry the pipeline actually captures,
so this cannot come back.

While the placement is being settled, decide explicitly what the same ordering means for
`TagApplier` and `DescriptionNoteApplier`, which also mutate the registry before capture. Their
config-driven `@tag` applications and appended description notes land in the store as authored
facts today. That may well be right, since the round-trip constraint wants the emitted schema
reproducible and both are in it, but it is currently an accident of ordering rather than a
recorded decision, and the item should say which it is.

**Rename the `extension_` family to `jvm_`.** Raised against the family's own rule and upheld.
The rule names a family for whose vocabulary a row is written in; three families satisfy it and
this one does not. Its rows say class, method, descriptor, parameter, record component, field,
which is the JVM classfile's vocabulary, while `extension_` names a presumed role: code written
to extend graphitron. This item has already retired one family name for exactly this failure.
The `applied_` / `intent_` split died because "carried verbatim" named a treatment that only held
because capture pre-decided at write time a question belonging at read time; `extension_` names a
role that only holds because the scan happens to be scoped to reactor output directories. The
class census reading the compile classpath (R605, already measured) makes the role claim plainly
false rather than arguably true: `extension_class` goes from ~1.8k rows to ~30k and
`com.fasterxml.jackson.databind.ObjectMapper` becomes a row in a relation named for the
consumer's extension code. What earns it a row is that an author may legitimately name it in
`@record` / `@service` / `@enum` / `@scalarType` and the codegen loader resolves it, which is a
classpath fact.

`jvm_` rather than `classfile_` or `bytecode_`, on this item's own text. The conventions above
already name this vocabulary's owner, in the decode rule's "a JVM descriptor", and
`extension_method.descriptor` is commented as a raw JVM descriptor. `classfile_class` stutters,
and `bytecode_` names the encoding rather than the vocabulary, since a record component comes
from the classfile's `RecordAttribute` and not from any bytecode. The precedent is `catalog_`,
which names the vocabulary's owner rather than jOOQ, the mechanism that read it.

`extension_scalar_constant` becomes `jvm_scalar_type_field`, and the reasoning is worth keeping
because it decides the next relation of its shape. Purifying it to a `jvm_static_field` with a
descriptor column is the wrong move: the scan keeps only fields whose descriptor is exactly
`Lgraphql/schema/GraphQLScalarType;`, so a total-sounding name over a filtered relation would
mislead about the table's contents, which is worse than the present name misleading about the
reason for the row. The selector therefore stays in the name, and it can, because
`GraphQLScalarType` is a graphql-java class name, a JVM type rather than a graphitron concept.
The relation is a JVM fact whose reason for capture is GraphQL-side, and reason-for-capture is
the axis the family rule already rejected. Dropping `constant` is a correction on its own terms:
`ClasspathScanner.readScalarConstants` deliberately does not require `final`, so both the current
relation name and its comment overclaim.

The sibling Java name stays out. `CompletionData.ExternalReference` is not the same defect,
since "extension" asserts a role and "external" asserts a location, and a jar class is genuinely
outside the generated output however little it extends. The part of that name that ages badly
under the widened census is `Reference`, most entries being referenced by nobody, and that is
R605's call on its own merits; binding it here would make this item's remaining work wait on an
item still in Spec.

Blast radius is the same for both renames and is small: the DDL's table names and `COMMENT ON`
text, `CatalogFactCapture` (both of its loads), and the census anchors in
`FactCaptureAgreementTest`, which are the only two files in the reactor naming either family's
generated constants. The generated classes regenerate from the DDL and the compiler finds every
call site, which is the compile-time-only compatibility surface the module section describes.
Doing it in this pass rather than a follow-up is a cost argument: nothing reads the store yet,
so a rename is text plus compile fixes today and grows with every consumer that migrates, and
this item is reopened anyway. The registration list in the agreement driver moves with the
relation names, and `everyRelationIsRegistered` fails in both directions if it does not.

**Rename `catalog_` to `sql_` as well.** `jooq_` was proposed first and rejected, because it
names the reader where the rule asks for the owner: jOOQ defines neither table nor column nor
foreign key, and the precedent is `graphql_`, which is not `graphqljava_` though graphql-java
parses every row. But rejecting `jooq_` is not a defence of `catalog_`. SQL is the vocabulary's
owner, and naming the owner is the rule; `catalog_` names a category within that vocabulary
instead, which is a different job from the one the prefix has. Strict SQL makes a catalog the
top-level namespace of `catalog.schema.table`, and this family has no catalog level at all,
every key starting at `table_schema`, so the incumbent is already using the tooling sense of the
word rather than SQL's.

`sql_table`, `sql_column`, `sql_index`, `sql_index_column`, and the constraint relations the
next section reshapes. The resulting set is `graphql_`,
`sql_`, `jvm_`, `graphitron_`: three external vocabularies each named by its owner, plus
graphitron's own, where the incumbent set named one family by category and two by owner. It
passes the mechanism-independence test the `extension_` case turns on, since reading
`INFORMATION_SCHEMA` directly instead of walking jOOQ's generated classes leaves every relation
name correct. Alignment with `CatalogFacts` and `JooqCatalog` is not an argument for holding the
old prefix, on the same grounds that leave `CompletionData.ExternalReference` out of this: DDL
family names and Java class names answer to different rules.

One comment is wrong rather than missing, and the reshaping happens to retire it:
`catalog_foreign_key_column.source_column` is described as "source column, 1-based per the
graphql-java convention", the SDL families' position wording copy-pasted onto a column name, and
that column ceases to exist once the uniform `sql_constraint_column` replaces the paired row.
The lesson outlives the instance and is the reason the sweep below exists: the comment-coverage
gate checks that a comment is present and cannot check that it is true, so every claim a
relation makes about its own contents is unverified.

And the two `java_name` riders
on `sql_table` and `sql_column` should become `jooq_name`: in a relation whose prefix names SQL,
a jOOQ-generated identifier is visibly the one foreign column, and marking it is better than
leaving a reader to infer it. That was optional under `catalog_` and is not under `sql_`.

The prose glosses go with the prefix. The DDL header calls the family "jOOQ catalog facts" and
the section banner "what the jOOQ catalog scan sees", both naming the reader, and this item's
family sentence repeats it; all three should name SQL as the vocabulary and jOOQ as the reader.
"Catalog" stays available as the prose word for what the family is about, since only the prefix
carries the rule.

**The constraint relations unify under one typed supertype, and the primary key leaves the
flag.** This is the redesign that takes the item back to Spec rather than a rename riding along
with the others.

The schema models a table's uniqueness constraints and its foreign keys as two disjoint
relations with two column children, where every real catalog models them as one constraint
relation discriminated by type. Oracle's data dictionary carries `ALL_CONSTRAINTS` with a
`CONSTRAINT_TYPE` of `P` / `U` / `R` / `C` and one `ALL_CONS_COLUMNS` under it; the SQL
standard's `INFORMATION_SCHEMA` carries `TABLE_CONSTRAINTS` with a `constraint_type`,
`KEY_COLUMN_USAGE` for the local columns of every keyed form, and `REFERENTIAL_CONSTRAINTS` as
the foreign-key-only extension. Two independent designs converged on the supertype, which is
evidence about the shape rather than about either vendor. This schema already votes the same way
elsewhere: `graphql_type` is a supertype over six declaration forms with a CHECK-constrained
`kind` and the per-form detail in sibling relations, and the conventions state the pattern
outright, that closed taxonomies are CHECK constraints. The constraint families are the one
place the schema states a closed taxonomy by having separate relations instead.

The gain is not tidiness. "What constrains this table?" is a union today and one predicate under
the supertype; a detection ranging over constraints (a `@node(keyColumns:)` naming a column set
that is not unique) has one relation to read; and the forms this iteration does not capture
(CHECK, NOT NULL, deferrability) arrive later as type values rather than as new relations with
new anchors.

The shape is `sql_constraint` as the supertype, keyed by schema, table and constraint name and
carrying the CHECK-constrained `constraint_type`; `sql_constraint_column` for the ordered
columns; and two extensions, `sql_primary_key` and `sql_referential_constraint`. The extension
split follows the standard rather than Oracle, which hangs foreign-key-only columns off the
supertype to sit NULL on every other row, because this schema's discipline prefers an absent row
to a null column.

The primary key earns its extension on the natural-key rule rather than on taste. A table has at
most one, so the fact a primary-key row states is "table T's primary key is constraint C" and its
coordinate is T; keying it by the constraint name admits "T has primary keys C1 and C2", a
sentence the domain has no member for, and `is_primary` is the symptom of the key being wrong.
Keying `sql_primary_key` by `(table_schema, table_name)` makes the cardinality structural and
retires a gate: the convention list above names "at most one primary key per table" among the
invariants plain DDL cannot state, and that is false in an instructive way, since DDL cannot
state it only *given a constraint-keyed relation with a flag*. The limitation belonged to the
model and was attributed to the language. The unified relation alone cannot buy this either,
enforcement inside `sql_constraint` needing a filtered unique index H2 does not have, so the
extension is what makes the invariant structural rather than documented. Worth re-reading the
other two entries on that list with the same suspicion before either is accepted as gate-only.

The live model already draws the distinction, which is the tell that the store's shape is the
odd one out. `MatchedKey` is sealed over `PrimaryKey` and `UniqueKey`, `TableRef` carries
`primaryKeyColumns`, `MutationField` emits a primary-key-only RETURNING clause,
`@order(primaryKey:)` selects it by name, and `UpdateRowsError` and `DeleteRowsError` render
"PK" and "UK" differently in user-facing diagnostics. `CatalogFacts` splits them too, into an
`Optional<Key> primaryKey` and a `List<Key> uniqueKeys`, which is exactly why the census anchor
folds the store's relation to the `uniqueKeys` view before comparing. That projection is
recorded in the acceptance list as a projection choice; it is really the shadow model reporting
a mismatch the store introduced, and it disappears with the split. The driver cannot currently
tell a projection that bridges a genuine grain difference from one that bridges a modelling
error, which is worth remembering when the next registration wants one.

**The column child is uniform, and target columns are a join rather than a copy.** Every
constraint owns its columns in one relation,
`sql_constraint_column(table_schema, table_name, constraint_name, position, column_name)`, for
primary keys, unique constraints and the local side of foreign keys alike. A foreign key's
targets come from the referenced constraint's own rows, matched on `position`, which is how both
Oracle and the standard resolve them and is guaranteed by SQL semantics, the two column lists
corresponding positionally.

An earlier draft of this item kept the source and target columns paired on one row and argued
that capture stores facts while joins are derivation's business. That argument runs the other
way: a target column is a fact about the *referenced* constraint, so copying it onto the
referencing row is precomputing a join at capture time, which is the pre-resolution the
resolution-facts leave-out rejects. The cost objection does not survive either, since
`JooqCatalog.foreignKeyFactsOf` already calls `fk.getKey()` and takes its table, so the
referenced constraint's name is one more field on a record it is already building.

The reachability worry behind that draft was real but misaimed. It is not indirection that
threatens a dangling reference, it is `JooqCatalog.candidateKeys`, which dedupes on column set,
so a unique constraint sharing a column set with the primary key is dropped and a foreign key
referencing it would point at nothing. That dedup is a `CatalogFacts` projection choice rather
than a catalog fact, and the fix is for capture to read the full key set instead of distorting
the model around a projection. It is the same lesson the primary key teaches, and the two
together are why the census anchor needs folds at all: the store keeps inheriting
`CatalogFacts`' shape where it should take the catalog's.

`sql_referential_constraint` therefore carries
`(table_schema, table_name, constraint_name, referenced_schema, referenced_table,
referenced_constraint_name)` with a foreign key on each triple into `sql_constraint`. That
strengthens the structural claim rather than merely relocating it: the relation references
`catalog_table` today, saying the target table exists, where a foreign key in SQL references a
constraint and not a table. The referenced schema and table are not a denormalisation, being two
thirds of the composite key the reference needs.

Whether a foreign key can point out of the scanned catalog at all is the question this sharpens
and does not answer. `CatalogFactCapture`'s foreign-key loop writes the target from
`split(fk.targetTable())` with no guard that the table was scanned, and the relation already
declares a foreign key into `catalog_table`, so an out-of-catalog reference would land as a
constraint violation, which this item's doctrine reads as a capture bug when it would really be
a catalog boundary. Whether jOOQ's generated model can produce one should be settled during the
pass, and it matters more once the reference is to a constraint.

**The constraint's
backing index stays out.** A primary key or unique constraint is backed by an index, and
PostgreSQL gives both the same identifier, `actor_pkey` naming a constraint and the index
enforcing it; Oracle exposes the edge as `ALL_CONSTRAINTS.INDEX_NAME` and needs to, because it
adopts a suitable existing index instead of always creating one. The question is theoretical for
us and should be recorded as such so nobody re-derives it: jOOQ's `Table.getIndexes()` excludes
constraint-backing indexes, so the relations are already disjoint in captured data, sakila's
generated `Indexes` holding exactly one entry while every `*_pkey` arrives through `Keys`. It
becomes live only if a later capture reads indexes from somewhere jOOQ's generated model does
not filter.

Two near-misses ruled out, both already in the codebase's vocabulary. `sql_candidate_key` picks
up `JooqCatalog.candidateKeys`, but a candidate key is relational-model vocabulary rather than
SQL DDL's, and it overclaims an irreducibility SQL does not require of a UNIQUE declaration.
`sql_key` is not the supertype's name either: beside a foreign-key relation it implies a
containment that does not hold, jOOQ's `UniqueKey` and `ForeignKey` both extending `Key`, and in
MySQL and MariaDB `KEY` is a synonym for `INDEX`, which this schema keeps separate with different
contents.

**What the Spec pass must reconcile.** The DDL listing in the schema section above still shows
`catalog_key`, `catalog_key_column`, `catalog_foreign_key` and `catalog_foreign_key_column`, and
still carries the old prefixes throughout both renamed families. The listing is this item's
deliverable, so it is the Spec pass that rewrites it: the relations above with their `COMMENT ON`
text, the `catalog_` and `extension_` prefixes carried through every relation and every comment,
and the convention bullet that claims DDL cannot state the primary-key cardinality. The
acceptance list goes with it: its census bullet records the fold to `CatalogFacts`' `uniqueKeys`
view as a projection choice, and both halves of that fold, the excluded primary key and the
column-set dedup, are the projection artifacts the reshaping removes. Until all of it lands the
sections disagree, deliberately and visibly, rather than quietly.

**The two non-SDL loads read one layer too high, and it costs four more facts.** The constraint
findings above are not isolated. A sweep of every producer found the same defect wherever
capture reads a projection built for another surface, and found none where it reads the parse
directly, which locates the cause: `CatalogFacts` and `CompletionData` are shapes designed for
the MCP catalog tools and the LSP's completion popups, and capture inherits every narrowing they
made for those consumers. `SdlFactCapture` reads the registry AST and is clean. The fix
direction is for the loads to read `JooqCatalog` and `ClasspathScanner` output directly, or for
those producers to carry what the store needs. Relations below are named as this pass leaves
them, so the code carries `catalog_` and `extension_` where the text says `sql_` and `jvm_`; the
Java identifiers cited are unaffected by the rename.

- **`jvm_method.descriptor` is fabricated, and lossy inside a primary key.**
  `CatalogFactCapture.descriptorOf` concatenates `CompletionData.Parameter.type()` values into
  `(Type;Type;)Return`, and those values are `ClassDesc.displayName()`, package-stripped simple
  names. The column is commented "raw JVM descriptor; the overload discriminator that keeps this
  key natural" and is neither. `ClasspathScanner.readMethods` holds `m.methodTypeSymbol()`,
  whose `descriptorString()` is the real descriptor, and already calls `descriptorString()` on
  its return type one line later; `CompletionData.Method` drops it and capture invents a
  replacement. Two public methods taking `com.foo.Result` and `com.bar.Result` render the same
  string, collide on the key, and the second is dropped by first-wins with no quarantine row.
  The extension-method anchor compares descriptor-erased precisely because the model carries no
  descriptor, so it cannot catch this.
- **`sql_column.ordinal` is reflection order presented as catalog order.**
  `JooqCatalog.columnFactsOf` enumerates `table.getClass().getFields()`, whose contract states
  the result is in no particular order, and capture numbers the rows in that sequence. The
  column is commented "column position in the table definition". This is the determinism rule
  this item states, that iteration order is never load-bearing, broken by its own capture.
  jOOQ's `table.fields()` is declaration-ordered; the reflection exists only to reach the
  generated Java field name, which `Field` does not expose. No anchor covers ordinals, the
  census comparing names and counts.
- **`jvm_class` promises classpath existence and delivers four undisclosed filters.**
  `ClasspathScanner.readIfCandidate` skips any simple name containing `$`, which is every nested
  class, along with non-public classes, synthetic classes, and everything under the jOOQ
  package. The relation says only that a class exists on the consumer's extension classpath. A
  nested class named in `@record` resolves through the codegen loader and would be reported
  unknown by the resolution detection R605 plans over this relation, which is R605's own bug one
  axis over: it fixes directories against jars, this is top-level against nested.
- **`sql_index` overclaims the same way**, jOOQ's `getIndexes()` excluding
  constraint-backing indexes, so `@order(index:)` naming a primary key's index cannot resolve
  against a relation whose comment says an index exists on a table.

The renames sharpen three of these rather than relieving them, which is why the disclosure has
to ship with the prefix rather than after it. A column called `descriptor` under a family named
for the JVM claims the JVM's own artefact more loudly than it did under `extension_`; `sql_index`
and `sql_column.ordinal` claim SQL's index set and SQL's column order where `catalog_` at least
read as a tool's view of them; and `jvm_class` sounds more total than `extension_class` did,
since a role name invites the question "extending what?" while a vocabulary name simply asserts
the category. Every one of these names is more honest about whose vocabulary the row is written
in and more misleading about which rows are present, so the pass that lands them is the pass
that owes each relation a comment stating its filters.

Three producers came back clean and are recorded so the pass does not re-derive them. The
`graphitron_` decode helpers return null or an empty list for an absent argument and quarantine
a type mismatch, so no default-filling reaches the store and the authored-values convention
holds. `sql_column.sql_type` and `nullable` are jOOQ's readings, and their comments say so;
a disclosed projection is the healthy case and the pattern above is what an undisclosed one
looks like. Column and table comments normalise the empty string upstream, so no relation
confuses "" with absent.

The general lesson worth keeping past this pass: a fold in an agreement anchor is a symptom, not
plumbing. Every defect here and in the constraint families surfaced at one, and the driver
cannot distinguish a fold that bridges a real grain difference (capture total against a pruned
model) from one that bridges a mismatch capture introduced. Registering an arm that needs a fold
should carry the reason the grains differ.

One measurement rides along with the widened census rather than with the rename. R605 rules the
~213k `jvm_method` rows an insert-throughput question rather than a scoping one, which is
the right call and is this item's premise, but it makes the per-run load worth measuring rather
than assuming `FactSink`'s batching absorbs a 16x census.

Two improvements the contract does not demand, noted so the next pass can take or leave them:

- A decode arm that hits a missing required argument returns without writing either its
  decoded row or a `graphitron_undecoded_argument` row (`GraphitronFactCapture`'s `sourceRow`,
  `mutation` and `pivot` arms among others). The verbatim `graphql_` row survives, so a
  detection can still find the application, but nothing in the semantic stratum records that
  the decode declined. Worth either quarantining the application or naming the
  "verbatim graphitron application with no decoded row" detection as the intended reading.
- `captureFacts` builds a second `JooqCatalog` and re-walks the catalog and the classpath
  (`GraphQLRewriteGenerator`), while `buildOutput` reuses the `catalogFacts` it already has.
  Shadow-period cost only, and cheap to thread through.

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
  is vacuous by construction (`graphql_directive_site` is the first derived registrant, and the
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
  wrapping decode consistent with `type_sdl` where SQL can express the correspondence, every
  application resolving to a captured definition, a decoded application keeping its verbatim row,
  the federation dual projection in agreement). A repeated application of a non-repeatable
  directive is deliberately not in this list: under registry capture it is author-reachable, so
  it is a detection.
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
