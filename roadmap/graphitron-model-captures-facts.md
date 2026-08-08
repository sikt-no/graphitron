---
id: R595
title: "The graphitron-model module exists and capture fills it"
status: Ready
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-05
last-updated: 2026-08-08
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

The main delivery of this spec is the target model itself, the first iteration of the fact schema.
The module and the loads exist to make that schema real, compiled against, and kept honest by
tests. Most of it shipped before the item reopened; "Where this stands" separates what is already
in the tree from what the delivery plan still owes.

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
  mismatch, never migrated) is specified below, and preserves the no-state-of-record and
  no-migration properties while retiring the dead-with-the-process one.

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

These bind the shipped DDL and every relation added to it later.

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
- **Every base relation is partitionable by the source that produced it.** A refresh deletes
  exactly the rows one source wrote and re-walks it, so every base row must be reachable from a
  source identity, and a relation that cannot be traced back to one is a relation the store can
  only ever discard wholesale. This is the convention persistence turns from a nicety into a
  requirement, and it is why it is stated here rather than left to the surface that wants it: a
  schema written without it cannot acquire it later without rekeying. The SDL families satisfy
  it through the declaration site's `source_name`, with the synthesis provenance relations making
  orphan cleanup exact and shared machinery types refcounted by carrier row. The classpath and
  catalog families satisfy it through a source relation naming the classpath entry, one
  mechanism for both, since the jOOQ catalog is itself loaded from generated classes on the
  codegen classpath (`JooqCatalog` resolves its catalog through `codegenLoader`) and so has the
  same kind of provenance a scanned jar does. A source's kind is a closed taxonomy: a schema
  file, a directory root, or a jar.
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
  Cross-relation invariants plain DDL cannot state (defaults only on input-object fields, ordinal
  zero unless repeatable) get gate queries as their named enforcers, siblings of the
  comment-coverage gate. Membership of that list is a claim about the model as much as about SQL,
  so it earns re-checking whenever a relation is rekeyed: "at most one primary key per table" sat
  here and was false, DDL being unable to state it only *given a constraint-keyed relation with a
  flag*, and the constraint reshaping below makes the cardinality structural instead. Both
  remaining entries deserve the same suspicion before either is accepted as gate-only.

## The fact schema, first iteration

**The shipped DDL is the schema; this section is the design behind it and the delta still owed.**
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql` carries all 97
relations with their `COMMENT ON` text, and it is the only copy. An earlier draft of this item
transcribed the whole file, which drifted the moment the item reopened, and the transcription
bought nothing: the schema has no downstream consumers and no persisted state of record, so
compile-time is its only compatibility surface and changing the model is editing the DDL and
following the compiler. What this section carries instead is the reasoning that is not derivable
from the DDL, plus full target DDL for the relations the delivery below adds or reshapes. The 84
relations of the `graphql_` and `graphitron_` families shipped and are settled; they are reviewed
in the file.

Base relations only: what the capture loads fill. The derived stratum (claims, reachability,
demand, occurrence paths, diagnostics, commands) is deliberately absent; see the leave-outs
section. Five families, each named for **whose vocabulary a row is written in**. `graphql_` is
reserved for generic GraphQL: a row any SDL reader could produce from the document without
knowing graphitron exists, which is every declaration, every directive definition, and every
directive application. `graphitron_` is what graphitron makes of that document: the decoded
directives, and the provenance of the rows macro expansion mints. `sql_` is what the consumer's
database declares, read through jOOQ's generated model; `jvm_` is what the classfiles on the
compile classpath declare; and `store_` is the store's record of what it read and what it was
built from.

Naming a family for a vocabulary rather than a role or a reader is what decided three of the
five, and each rejection is worth keeping. `extension_` named a presumed role (code written to
extend graphitron) that held only while the scan was scoped to reactor output; once the census
reads the compile classpath, `com.fasterxml.jackson.databind.ObjectMapper` is a row, and what
earns it one is that an author may name it in `@record` / `@service` / `@enum` / `@scalarType`
and the codegen loader resolves it, which is a classpath fact. `jvm_` rather than `classfile_`
or `bytecode_`: `classfile_class` stutters, and a record component comes from the classfile's
`RecordAttribute` rather than from any bytecode. `catalog_` named a category *within* SQL's
vocabulary rather than the vocabulary, and used the tooling sense of the word at that, strict SQL
making a catalog the top level of `catalog.schema.table` where this family has no catalog level
at all. `jooq_` was proposed and rejected for naming the reader: jOOQ defines neither table nor
column nor foreign key, and the precedent is `graphql_`, which is not `graphqljava_` though
graphql-java parses every row. The resulting set is three external vocabularies each named by its
owner, plus graphitron's own and the store's own. Java-side names answer to different rules and
do not move with the DDL: `CompletionData.ExternalReference` stays, "external" asserting a
location rather than a role, and a jar class genuinely is outside the generated output.

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

The shipped inventory is the full census: every directive `directives.graphqls` declares, plus
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

Macros expand during the same capture walk when their contribution is a function of one carrier's
own declaration, which is the same type-locality rule the rest of the walk follows. `@asConnection`
qualifies: it is schema construction rather than a question over facts, the visitor holds everything
that construction needs (the AST, the wrapping decode, the naming conventions), and its element type
enters as a name that nothing here resolves. So the walk expands it inline:
a macro's contribution enters as declaration sites at the causing position (a definition
site for each type it creates, an extension site where it adds members to an existing type),
its element rows hang off those sites through the ordinary declaration reference, and the
three `graphitron_*_synthesis` provenance relations mark the sites, the synthesized applications,
and the rewrites.
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

### `sql_`, and the constraint supertype

`sql_` rows are keyed `(table_schema, table_name)` end to end; ambiguity of an unqualified
`@table(name:)` is a resolution question and therefore derivation, so capture just records every
table. Foreign keys are stored once, on the declaring side; the incoming direction `CatalogFacts`
denormalizes bidirectionally is a query here, which is the point of having a store. Multi-column
constraints and indexes are ordered child tables, the spike's rich-value pattern.

Constraints take the shape every real catalog uses: **one supertype relation discriminated by
type**, with per-form detail in siblings. Oracle's dictionary carries `ALL_CONSTRAINTS` with a
`CONSTRAINT_TYPE` of `P` / `U` / `R` / `C` over one `ALL_CONS_COLUMNS`; the standard's
`INFORMATION_SCHEMA` carries `TABLE_CONSTRAINTS` with a `constraint_type`, `KEY_COLUMN_USAGE` for
the local columns of every keyed form, and `REFERENTIAL_CONSTRAINTS` as the foreign-key-only
extension. Two independent designs converged, which is evidence about the shape rather than about
either vendor, and this schema already votes the same way elsewhere: `graphql_type` is a supertype
over six declaration forms with a CHECK-constrained `kind`, and the conventions state outright that
closed taxonomies are CHECK constraints. The gain is not tidiness. "What constrains this table?" is
a union under the old shape and one predicate under this one; a detection ranging over constraints
(a `@node(keyColumns:)` naming a column set that is not unique) has one relation to read; and the
forms this iteration does not capture (CHECK, NOT NULL, deferrability) arrive later as type values
rather than as new relations with new anchors.

The extension split follows the standard rather than Oracle, which hangs foreign-key-only columns
off the supertype to sit NULL on every other row: this schema prefers an absent row to a null
column.

```sql
-- ==== SQL catalog facts ======================================================
-- What the consumer's database declares, in SQL's vocabulary. jOOQ's generated
-- model is the reader, not the owner: reading INFORMATION_SCHEMA directly
-- instead would leave every relation name here correct.

-- A named constraint exists on a table. The supertype: one row per constraint
-- whatever its form. UNIQUE and PRIMARY KEY and FOREIGN KEY are what this
-- iteration captures; CHECK and NOT NULL arrive as further type values.
CREATE TABLE sql_constraint (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  constraint_type VARCHAR NOT NULL, -- the standard's TABLE_CONSTRAINTS vocabulary
  PRIMARY KEY (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name) REFERENCES sql_table (table_schema, table_name),
  CHECK (constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY'))
);

-- An ordered column of a constraint: the key columns of a primary key or a
-- unique constraint, and the referencing columns of a foreign key, in one
-- relation for all three forms as KEY_COLUMN_USAGE does.
CREATE TABLE sql_constraint_column (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  position        INT     NOT NULL, -- 0-based position in the constraint's column list
  column_name     VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name, position),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name, column_name)
    REFERENCES sql_column (table_schema, table_name, column_name)
);

-- Table T's primary key is constraint C. Keyed by the table, because a table
-- has at most one and the coordinate of the fact is therefore the table; that
-- is what makes the cardinality structural instead of a gate query over a flag.
CREATE TABLE sql_primary_key (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name)
);

-- A foreign key references a constraint. Referencing the constraint rather
-- than the table is what SQL declares; the target columns are that
-- constraint's own sql_constraint_column rows matched on position, which is
-- how both Oracle and the standard resolve them and is guaranteed by SQL
-- semantics, never copied onto the referencing row.
CREATE TABLE sql_referential_constraint (
  table_schema               VARCHAR NOT NULL,
  table_name                 VARCHAR NOT NULL,
  constraint_name            VARCHAR NOT NULL,
  referenced_schema          VARCHAR NOT NULL, -- two thirds of the composite reference below, not a denormalisation
  referenced_table           VARCHAR NOT NULL,
  referenced_constraint_name VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name),
  FOREIGN KEY (referenced_schema, referenced_table, referenced_constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name)
);
```

Two near-misses ruled out, both already in the codebase's vocabulary. `sql_candidate_key` picks up
`JooqCatalog.candidateKeys`, but a candidate key is relational-model vocabulary rather than SQL
DDL's, and it overclaims an irreducibility SQL does not require of a UNIQUE declaration. `sql_key`
is not the supertype's name either: beside a foreign-key relation it implies a containment that does
not hold, jOOQ's `UniqueKey` and `ForeignKey` both extending `Key`, and in MySQL and MariaDB `KEY`
is a synonym for `INDEX`, which this schema keeps separate with different contents.

The constraint's backing index stays out, and the reason is recorded so nobody re-derives it. A
primary key or unique constraint is backed by an index, and PostgreSQL gives both the same
identifier, `actor_pkey` naming a constraint and the index enforcing it; Oracle exposes the edge as
`ALL_CONSTRAINTS.INDEX_NAME` and needs to, because it adopts a suitable existing index instead of
always creating one. The question is theoretical for us: jOOQ's `Table.getIndexes()` excludes
constraint-backing indexes, so the relations are already disjoint in captured data, sakila's
generated `Indexes` holding exactly one entry while every `*_pkey` arrives through `Keys`. It
becomes live only if a later capture reads indexes from somewhere jOOQ's generated model does not
filter, and `sql_index`'s own comment owes the exclusion either way.

### `jvm_`

`jvm_` rows come from the bytecode-only classpath walk (`ClasspathScanner`: stdlib classfile
parsing, no classloading). Overloads make the plain method name a non-key, so the raw JVM
descriptor joins the key; it is ugly and it is the identity, which is exactly what an
identity-carrying key is for. `jvm_method.descriptor` must be the real thing,
`methodTypeSymbol().descriptorString()`, and not a rendering of erased display names.

`jvm_class` gains the source reference that makes the census partitionable, and its comment owes
the census's filters rather than the bare existence claim it makes today:

```sql
-- A class exists on the compile classpath, as the codegen loader would resolve
-- it. Filtered: public, non-synthetic, top-level (a simple name containing '$'
-- is skipped, so nested classes are absent), and outside the generated jOOQ
-- package. A resolution detection over this relation reads those filters as
-- absence, so they are stated rather than implied.
CREATE TABLE jvm_class (
  class_name  VARCHAR NOT NULL, -- fully qualified binary name
  class_kind  VARCHAR NOT NULL, -- the classfile's declared form; the domain is closed over classfile shapes, so a violation is a capture bug
  source_name VARCHAR NOT NULL, -- the classpath entry it was read from; the partition this row belongs to
  PRIMARY KEY (class_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name),
  CHECK (class_kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION'))
);
```

### `store_`

The fifth family, and the only one whose rows capture does not transcribe from somewhere else: the
store's record of itself. It earns a family name on the same rule as the others, the vocabulary
being the store's own metamodel rather than SQL's, the JVM's or GraphQL's. It cannot wear any of
their prefixes, because one mechanism covers all three source kinds.

```sql
-- ==== Store bookkeeping ======================================================

-- A source the store read. One relation for all three kinds, because a
-- partition delete is one mechanism whether the source is a schema file, a
-- compile-output directory, or a jar. The jOOQ catalog is itself loaded from
-- generated classes on the codegen classpath, so the sql_ family's provenance
-- is a classpath entry like the jvm_ family's.
CREATE TABLE store_source (
  source_name VARCHAR NOT NULL, -- the schema file path or the classpath entry path
  source_kind VARCHAR NOT NULL,
  stamp       VARCHAR,          -- content hash; NULL for a directory root, which changes on every compile and is never cached
  PRIMARY KEY (source_name),
  CHECK (source_kind IN ('SCHEMA_FILE', 'DIRECTORY', 'JAR'))
);

-- What this store was built from. At most one row, stated structurally: a
-- whole-store stamp deciding whether a persisted file is intelligible at all,
-- which is what keeps migrations out of a schema with no state of record.
CREATE TABLE store_stamp (
  singleton         CHAR(1) NOT NULL, -- always 'X'
  ddl_hash          VARCHAR NOT NULL,
  generator_version VARCHAR NOT NULL,
  PRIMARY KEY (singleton),
  CHECK (singleton = 'X')
);
```

One partition question stays open for the implementation pass rather than being guessed here.
`jvm_class` and `sql_table` reference `store_source` outright. The `graphql_` declaration sites
already carry a `source_name` and the walk stands on the file while writing them, so the FK doctrine
admits the reference, but schema-level relations (`graphitron_link`) hold a nullable `source_name`
and synthesized sites inherit a causing position rather than a read file. Whether the SDL side
declares the FK therefore depends on whether `source_name` can be made total there; reachability is
the convention's requirement and a declared FK is the stronger form of it.

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
  `@oneOf` support, the bundled definitions, and the config-driven `@tag` and description-note
  attribution, all of which are in the emitted schema the store owes a round trip of) and before
  the synthesis rewrites, and is plain
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
- **Catalog and classpath loads.** Fill the `sql_` family from the jOOQ catalog walk and the `jvm_`
  family from the `ClasspathScanner` emission, each recording its `store_source` row as it goes.
  Both read their producer directly rather than through `CatalogFacts` or `CompletionData`, which
  are shapes designed for the MCP catalog tools and the LSP's completion popups and narrow the
  census for those consumers; reading through them is what the delivery below is undoing. Runs
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
is the accumulated registry, and with the persisted store an editor session boots warm and
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

Part of this item shipped before it reopened, so an implementer picking it up is extending a
working tree rather than starting one. Read this section for what is already there, then the
delivery plan for what is left.

Shipped and standing: the module and both boots, the whole DDL, the SDL and catalog capture loads
wired into the pipeline, the gate family, and the mechanical agreement driver with its type-census,
applied-directive, catalog-census and extension-census anchors.

**Slices 1 through 4 have landed; slice 5 has not.** Each ended with a green
`mvn install -Plocal-db`, and what each settled is recorded in its own section below. In outline:

- **Slice 1.** Capture takes `AttributedRegistry.preSynthesisRegistry()`, a `readOnly()` snapshot
  taken where the loading rewrites end. `TagApplier` and `DescriptionNoteApplier` sit above the cut
  by decision rather than by accident, and `KeyNodeSynthesiser` moved below them so the pipeline's
  order states the split. The federation anchor runs one pipeline pass and compares the store
  against the registry the rewrite mutated; the provenance rows are the assertion that catches a
  capture reading the wrong handle, verified by perturbing the handle.
- **Slice 2.** The families are `sql_` and `jvm_`, `java_name` is `jooq_name`, and
  `extension_scalar_constant` is `jvm_scalar_type_field`. Every renamed relation's comment now
  states its filters, which is what the sharper names owe.
- **Slice 3.** `sql_constraint`, `sql_constraint_column`, `sql_primary_key` and
  `sql_referential_constraint` replace the four `catalog_` constraint relations, capture reads
  `JooqCatalog` instead of `CatalogFacts`, `sql_column.ordinal` comes from `Table.fields()`, and
  the census anchors compare without a fold.
- **Slice 4.** The census is the compile classpath, jars included; `store_source` records every
  entry and every schema file, stamped by content hash where there are bytes to hash;
  `jvm_method.descriptor` is the real JVM descriptor; completion ranks reactor classes first.

A module-wiring defect surfaced in slice 2, the first slice to edit the DDL, and is fixed there.
The codegen driver finds the DDL through the classpath, where `target/classes` precedes the source
tree. On a clean build that directory is empty and the source wins, which is what made the
resource's ordinary `process-resources` copy look sufficient; on an incremental build the previous
run's copy shadowed the edit, so codegen regenerated the schema the build before had and the
compile-time surface lagged the DDL by a build. The module copies its resources ahead of the driver
now, which keeps "edit the DDL and follow the compiler" true on every build rather than only on a
clean one.

Shipped and replaced by slices 2 through 4: the two non-SDL capture loads read `CatalogFacts`
and `CompletionData` rather than the catalog and the scanner, which is where every defect slices 3
and 4 fix came from; the `catalog_` and `extension_` family names; and the four constraint
relations. Federation `@key` synthesis shipped as a walk macro with its provenance rows and an
anchor against `KeyNodeSynthesiser`, but the macro was inert in production and the anchor pinned a
path the pipeline never took, which slice 1 fixed.

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

## Delivery

The item reopened at its In Review gate on 2026-08-07 with one blocker, and then absorbed three
filings that turned out not to be separable from it: a constraint-modelling item, a classpath-census
item, and a warm-start-store item. All three are discarded into this one, their numbers left as
permanent gaps and their slugs deliberately not cited here, the files being gone. What follows is the whole of the remaining work in five slices, ordered. Each ends with a
green `mvn install -Plocal-db` and tells one story; none of them half-lands a relation.

Two ordering choices are deliberate and worth stating, because both look like they could go the
other way. The rename (slice 2) deliberately does **not** touch the four constraint relations,
which slice 3 deletes: renaming them first would mint `sql_key`, a name this item argues against by
name, for the length of one commit, and leaving them under `catalog_` for one slice is visibly
transitional in a way that minting a rejected name is not. And the source identity is folded into
slice 4 rather than following it, because an intermediate commit shipping a 16x census with nothing
to invalidate against is a build-time regression someone would have to bisect through.

### Slice 1: capture reads the pre-synthesis registry

The blocker. The item fixes the walk's reading position ("after the loading rewrites ... and before
the synthesis rewrites") and `MacroCapture`'s own javadoc restates it; both production call sites do
the opposite. `GraphQLRewriteGenerator.loadAttributedRegistry` runs `KeyNodeSynthesiser.apply` in
place on the same registry object it returns, and both `buildOutput` and `runPipeline` hand that
mutated registry to `FactCapture.run`. By the time `MacroCapture.expandFederationKeys` looks,
`hasIdKey` is already true for exactly the types the rewrite touched (the two implementations gate
on the same nodehood predicate and the same single-`id` field set), so the macro contributes
nothing. Measured on a one-node federated fixture: capturing the pristine registry writes 1
`graphitron_type_directive_synthesis` row, capturing the post-`KeyNodeSynthesiser` registry writes 0.

Three consequences, none of which a test currently sees. The synthesized `@key` is captured as an
authored application, so the authored picture is no longer the anti-join against the provenance
relations, which is the property the whole provenance family exists to buy. Its
`graphql_type_directive.source_line` / `source_column` are NULL rather than the type's declaration
site, because a `Directive` the rewrite built carries no `SourceLocation`. And
`federationKeySynthesisAgreesWithTheRewrite` passes because it captures a freshly parsed registry
and applies the rewrite to a second copy only to compute the expectation, pinning a path the
pipeline never takes, which is exactly the drift the shadow-period anchors exist to catch.

The fix is a placement decision, not a redesign: either capture the registry before
`KeyNodeSynthesiser` runs (`loadAttributedRegistry` already builds the `JooqCatalog` the
`NodeDeclaration` needs), or hand capture a pre-synthesis handle alongside the attributed one.
Shipped as the second: `AttributedRegistry` carries a `preSynthesisRegistry` beside the attributed
one, a `readOnly()` snapshot taken where the loading rewrites end. It is cheap (immutable copies of
the registry's maps over the same AST nodes), it cannot drift out of the pipeline the way a second
parse would, and it refuses mutation, so a rewrite inserted after the cut cannot silently leak into
the capture handle.

Also in this slice, because it is the same ordering question: `TagApplier` and
`DescriptionNoteApplier` also mutate the registry before capture, and their config-driven `@tag`
applications and appended description notes land in the store as authored facts. **Decided: they
stay above the cut**, so capture keeps seeing them. Both are in the emitted schema, and the store
owes a round trip of it; a store that omitted them could not reproduce what the run emitted. This
makes them loading rewrites for the reading position's purposes, whatever "applier" suggests, and
`KeyNodeSynthesiser` moves below them so the pipeline's order states the split rather than
accidentally realising it. The move changes no output: neither applier touches the type-level
directive list `KeyNodeSynthesiser` rewrites, and its `transform` preserves the descriptions and
tagged members the appliers produce, so the two orders reach the same registry.

**Done when** the federation anchor exercises the registry the pipeline actually captures, so a
future move of the capture call fails a test rather than silently emptying a relation.

### Slice 2: the families are renamed

Mechanical, wide, no semantic change. Landing it as its own slice is what keeps slice 3's diff
readable, and doing it in this pass rather than a follow-up is a cost argument: nothing reads the
store yet, so a rename is text plus compile fixes today and grows with every consumer that migrates.

| from | to |
| --- | --- |
| `catalog_table`, `catalog_column`, `catalog_index`, `catalog_index_column` | `sql_*` |
| `catalog_*.java_name` | `jooq_name` |
| `extension_class`, `extension_method`, `extension_method_parameter`, `extension_record_component` | `jvm_*` |
| `extension_scalar_constant` | `jvm_scalar_type_field` |

The reasoning for the family names themselves is in the schema section above, with the rejected
candidates. Three riders belong here.

`java_name` becomes `jooq_name` because in a relation whose prefix names SQL, a jOOQ-generated
identifier is visibly the one foreign column, and marking it beats leaving a reader to infer it.
That was optional under `catalog_` and is not under `sql_`.

`extension_scalar_constant` becomes `jvm_scalar_type_field`, and the reasoning decides the next
relation of its shape. Purifying it to a `jvm_static_field` with a descriptor column is the wrong
move: the scan keeps only fields whose descriptor is exactly `Lgraphql/schema/GraphQLScalarType;`,
so a total-sounding name over a filtered relation would mislead about the table's contents, which is
worse than the present name misleading about the reason for the row. The selector therefore stays in
the name, and it can, because `GraphQLScalarType` is a graphql-java class name, a JVM type rather
than a graphitron concept. Dropping `constant` is a correction on its own terms:
`ClasspathScanner.readScalarConstants` deliberately does not require `final`, so both the current
relation name and its comment overclaim.

The prose glosses go with the prefix. The DDL header calls the family "jOOQ catalog facts" and the
section banner "what the jOOQ catalog scan sees", both naming the reader; both should name SQL as
the vocabulary and jOOQ as the reader. "Catalog" stays available as the prose word for what the
family is about, since only the prefix carries the rule.

**Blast radius** is small and the same for both renames: the DDL's table names and `COMMENT ON`
text, `CatalogFactCapture` (both of its loads), and the census anchors in `FactCaptureAgreementTest`,
which are the only two files in the reactor naming either family's generated constants. The
generated classes regenerate from the DDL and the compiler finds every call site.

**Done when** the build is green with no behavioural diff, and `everyRelationIsRegistered` holds,
which it will fail in both directions if the driver's registration list does not move with the names.

**Landed**, with one finding the slice did not go looking for: this was the first slice to edit the
DDL, and the edit did not reach codegen. The module-wiring fix is recorded under "Where this
stands"; the symptom was "cannot find symbol" in core against a relation the DDL declares, which is
exactly the failure the `drop-stale-generated-classes` execution exists to prevent, one phase
earlier than it was looking.

### Slice 3: the SQL family models constraints as the catalog does

`sql_constraint`, `sql_constraint_column`, `sql_primary_key` and `sql_referential_constraint`
replace `catalog_key`, `catalog_key_column`, `catalog_foreign_key` and
`catalog_foreign_key_column`. The target DDL and the argument for the supertype are in the schema
section above; what belongs here is what capture has to change to fill it.

**Capture stops reading `CatalogFacts` and reads `JooqCatalog` directly.** This is the load-bearing
change, not the DDL. `CatalogFacts` is a projection built for the MCP catalog tools, and three of its
narrowings are currently baked into the store. It splits `Optional<Key> primaryKey` from
`List<Key> uniqueKeys`, which is why the store modelled the primary key as a flag and why the census
anchor folds to the `uniqueKeys` view before comparing. `JooqCatalog.candidateKeys` dedupes on
column set, so a unique constraint sharing a column set with the primary key is dropped, and under
the new shape a foreign key referencing it would point at nothing: that dedup is a projection choice
rather than a catalog fact, and capture must read the full key set. And `OutgoingForeignKey` carries
no referenced-constraint name, which is why the old shape copied target columns onto the referencing
row; `JooqCatalog.foreignKeyFactsOf` already calls `fk.getKey()` and takes its table, so the
referenced constraint's name is one more field on a record it is already building.

Two more truthfulness fixes ride here, both in the same file and both the same defect class.
`sql_column.ordinal` comes from `table.fields()`, which is declaration-ordered, instead of
`table.getClass().getFields()`, whose contract states the result is in no particular order; the
reflection exists only to reach the generated Java field name, which `Field` does not expose, so it
stays for `jooq_name` and stops deciding `ordinal`. The column is commented "column position in the
table definition" and today is not, which is the determinism rule this item states, that iteration
order is never load-bearing, broken by its own capture. And `sql_index`'s comment states that
`getIndexes()` excludes constraint-backing indexes, so `@order(index:)` naming a primary key's index
resolves against a documented absence rather than an apparent one.

**Settle during the pass** whether a foreign key can point out of the scanned catalog at all.
`CatalogFactCapture`'s foreign-key loop writes the target from `split(fk.targetTable())` with no
guard that the table was scanned, and the relation declares a foreign key into the table relation, so
an out-of-catalog reference would land as a constraint violation, which this item's doctrine reads as
a capture bug when it would really be a catalog boundary. It matters more now that the reference is
to a constraint. If jOOQ's generated model can produce one, the reference is not structural and the
relation says so.

**Two gates change.** The `is_primary` count gate retires, its invariant having become structural in
`sql_primary_key`'s key; the conventions list above already records why. The census anchor loses its
`uniqueKeys` fold, both halves of which (the excluded primary key, the column-set dedup) are
projection artifacts this slice removes.

**Done when** the store's constraint census equals the catalog's rather than `CatalogFacts`' view of
it, and the anchor compares without a fold.

**Landed.** The out-of-catalog question settled the way the target DDL assumed: both endpoints of a
foreign key come out of the same generated model and the census enumerates every schema that model
declares, so the referenced constraint is present by construction and the relation declares the
foreign key. Capture unions the primary key into `Table.getKeys()` before writing, which is a no-op
under jOOQ's contract and is what keeps `sql_primary_key`'s reference resolvable if a model ever
separates them. There was no `uniqueKeys` fold to remove, because there was no constraint census
anchor at all; four now pin the constraint census, the position-matched resolution of a foreign
key's target columns, the primary-key form, and the definition order of column ordinals.

### Slice 4: the class census reads the compile classpath

Absorbed from the classpath-census filing, which was a follow-on that argued for landing ahead of
this item on the grounds that its residue was small. The sweep changed
that arithmetic: the same file now also owes the real JVM descriptor, the disclosed filters and the
family rename, so three passes over `ClasspathScanner` with a rename between them is worse than one.

**The bug.** `scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")`
generates fine and red-squiggles in the editor: `Unknown class 'graphql.scalars.ExtendedScalars' on
@scalarType. Not found in compiled target/classes.` The two paths read different classpaths. Codegen
resolves the constant reflectively through `RewriteContext.codegenLoader`, a `URLClassLoader` the
mojo builds over `project.getCompileClasspathElements()` with jars included. The LSP catalog reads
`CompletionData.externalReferences()`, built from `RewriteContext.classpathRoots()`, which is reactor
compile-output *directories* only, and `ClasspathScanner.scan` hard-skips any root failing
`Files.isDirectory`, so no jar is ever opened. The same gap silently disables completion at that
coordinate, since `ScalarTypeCompletions` sources candidates from `ExternalReference.scalarConstants()`,
so the library constants the custom-scalars manual page documents as the primary use case can never
be offered.

**The decision.** The directories-only premise goes. It was never argued from a property of the
schema language, only from a guess about where consumer vocabulary lives, recorded on
`RewriteContext.classpathRoots` as "external jars are not scanned: services live in reactor source,
not third-party libraries", and `@scalarType` falsifies it outright. A scalar-constant-only jar
census was considered and rejected: it keeps the same bug latent at every other class-bearing
coordinate, a `@record` naming a DTO from a shared internal library, a `@service` naming an interface
published as a jar, an `@enum` naming a library enum, all of which resolve at codegen and
red-squiggle today. The census becomes the set of classes on the compile classpath, which is what
`codegenLoader` can resolve. Absorbing this is what keeps the store from shipping a census known to
be missing rows: left alone, unification would land both paths on a single *wrong* answer, the
constant still absent from the class relation, the detection still firing, now consistently in both
places.

**Cost, measured.** Against `graphitron-sakila-example`'s resolved compile classpath (282 jars),
replicating the scanner's existing filter over jar entries: 65,261 class entries, of which 29,656
pass the public / non-synthetic / no-`$` filter, carrying 213,118 public methods and 74
`GraphQLScalarType` constants. 156 MB of classfile bytes parsed, 4.0 s cold and 1.4 s warm page
cache, single-threaded. The reactor's compile-output directories hold 1,825 candidate classes today,
so this is roughly a 16x increase against a per-build cost that is currently ~0.

**Steps.**

1. **Plumb the classpath.** `AbstractRewriteMojo.buildContext` passes `resolveCompileClasspath()`
   where it passes `resolveClasspathRoots()` today. That method already unions
   `getCompileClasspathElements()` with the reactor roots and already feeds `buildCodegenLoader` and
   the incremental compiler, so the two paths become one list by construction rather than by
   coincidence. Keep the `classpathRoots` component name, which still describes a list of classpath
   entries, and rewrite its javadoc: the premise is now false and must not survive as a stale claim.
2. **Teach `ClasspathScanner` to read jars.** `scan` currently continues past anything failing
   `Files.isDirectory`. Split the per-entry walk: directories keep `Files.walk`, `.jar` entries open
   a `ZipFile` and feed the same `readIfCandidate` filter over each entry's bytes, which is already
   byte-oriented and needs only its `Path`-typed signature loosened. The existing FQN dedup across
   roots carries over and gives classpath-order precedence, matching how a classloader resolves a
   duplicated class.
3. **Record the source, then invalidate against it.** `store_source` and `jvm_class.source_name`
   land here; their DDL is in the schema section. The absorbed plan proposed a static map keyed on
   (absolute path, size, last-modified), which pays each jar once per `graphitron:dev` process and
   nothing more: every `mvn install`, every forked test JVM and every fresh dev process pays the
   4.0 s again. The reason it could not do better is that the family had nowhere to record what it
   scanned, `ClasspathScanner.scan` holding the root while it walks and discarding it. The SDL
   families solved this already, and the asymmetry was the finding: a declaration site carries its
   `source_name`, which is what lets the refresh unit be "the types this file touches", while the
   classpath family's only available refresh was discarding the whole census, the most expensive
   thing in the store thrown away by any edit that invalidated anything. With the source recorded,
   per-entry invalidation is a query rather than a mechanism and the static map becomes its
   in-process degenerate case.

   The stamp is a content hash, not (path, size, last-modified). That triple is a heuristic,
   tolerable while a wrong answer dies with the JVM and not tolerable once it survives a build: CI
   caches, container image layers and reproducible-build normalisation all produce jars whose
   modification time is constant or arbitrary. A hash is exact and is an order of magnitude cheaper
   than the parse it protects, which step 6 confirms rather than assumes. A release-coordinate jar
   under the local repository is immutable by Maven's contract and could key on path alone, but that
   is a second rule earning milliseconds, and one invalidation story is worth more. Directory roots
   stay uncached whatever the choice, changing on every compile.

   With a source recorded, the cross-root dedup also stops being lossy: today first-wins discards
   both which root won and that a shadow existed. Recording the winner and quarantining the shadowed
   duplicate is what `graphql_duplicate_declaration` does on the SDL side, and a classpath collision
   is something an author may want told. Whether to build the detection is a call for the pass;
   the source column is what makes it possible either way.
4. **Ordering, not filtering, for completion.** `ClassNameCompletions` and friends will see ~30k
   candidates and must not filter them back out, the whole point being that they are legitimately
   referenceable. Rank reactor-resident classes ahead of jar-resident ones so the common case stays
   first, and let the client's prefix filter do the rest.
5. **Take the census truthfully.** `jvm_method.descriptor` comes from
   `methodTypeSymbol().descriptorString()` instead of `CatalogFactCapture.descriptorOf`, which
   concatenates `CompletionData.Parameter.type()` values into `(Type;Type;)Return` from
   `ClassDesc.displayName()`, package-stripped simple names. The column is commented "raw JVM
   descriptor; the overload discriminator that keeps this key natural" and is neither: two public
   methods taking `com.foo.Result` and `com.bar.Result` render the same string, collide on the key,
   and the second is dropped by first-wins with no quarantine row. `ClasspathScanner.readMethods`
   holds the real descriptor and already calls `descriptorString()` on its return type one line
   later; `CompletionData.Method` drops it and capture invents a replacement. The widened census is
   what makes the collision ordinary rather than exotic, 282 jars making two same-named types in
   different packages a near-certainty. The extension-method anchor compares descriptor-erased
   precisely because the model carries no descriptor, so it cannot catch this.

   `jvm_class`'s comment states its four filters, which the relation currently presents as a bare
   existence claim: non-public, synthetic, jOOQ-package and any simple name containing `$`, the last
   of which excludes every nested class. A nested class named in `@record` resolves through the
   codegen loader and would be reported unknown by the resolution detection that later migrates onto
   this relation, which is this slice's own bug one axis over: that one is directories against jars,
   this is top-level against nested. Whether to keep the nested-class filter is a call for the pass;
   disclosing it is not optional.
6. **Time the load.** The insert cost of a 31k-class, 213k-method census through `FactSink`,
   measured rather than assumed, alongside the cost of hashing the same classpath so the stamp choice
   rests on a number. Per the analysis under "notes carried forward" the answer to a slow load is a
   faster load, not a narrower census.

**Landed**, with step 6's numbers, which decide more than the item expected. Against
`graphitron-sakila-example`'s 282-entry compile classpath, warm page cache, single-threaded:
28,556 classes, 205,262 methods, 277,374 parameters and 74 `GraphQLScalarType` fields; the scan
costs 2.2 s and hashing the whole classpath costs 0.6 s over 121 MiB, so the stamp is an order of
magnitude cheaper than the parse it protects, which the item assumed and this confirms.

The insert dominated, not the scan: 23 s for half a million rows. Per the note carried forward the
answer is a faster load, so `FactSink` now renders one insert per relation and binds per row rather
than going through `batchInsert`, which re-derives each record's changed-field set and renders per
record; that brings it to 13 s.

**What the remaining gap is, measured properly.** The first pass at this compared a cold JVM running
`batchInsert` against a cold JVM running a hand-written JDBC batch and read the difference as an
insert technique; it is not one, and the number it produced (about threefold) was mostly JIT warmup.
Warm, best of three, in one JVM, over 206,702 `jvm_method` rows: `batchInsert` 3.7 s, the shipped
bind batch 2.1 s, a raw JDBC prepared-statement batch 1.4 s. Three candidate explanations for the
residual are ruled out by the same run. Wrapping the load in a transaction changes nothing either
way (2.07 vs 2.04 s through jOOQ, 1.45 vs 1.36 s raw), because `executeBatch` is one round trip that
commits once regardless. Declaring the bind parameters with their `DataType` changes nothing (2.03
s), because jOOQ already resolves values against the query's declared field types. Turning
`executeLogging` off changes nothing. jOOQ's `Loader` API is slower than the bind batch, not faster
(2.6 s).

So the residual 1.5x is jOOQ's per-value binding layer itself, and no setting reaches it. Closing it
means rendering the SQL with jOOQ and binding through `dsl.connection(...)` directly, which the
schema's own "only values are stored" convention is what makes safe: every column is a `VARCHAR`,
`INT` or `BOOLEAN`, so `setObject` needs no conversion. That is the next faster load if one is
wanted, worth about 4 s of the 13 s; it is measured and left, not overlooked.

The reactor build goes 5:07 to 5:56, with `graphitron-sakila-example` carrying all of it (1:21 to
2:14 across five generator passes). No narrowing was taken: the cost is per pass and per fresh
store, which is exactly what slice 5 removes, since a run that opens the previous run's file
re-reads no unchanged jar and re-inserts no unchanged partition.

Three riders on the delivery. The nested-class filter stays, disclosed on `jvm_class` rather than
removed: a nested class named in `@record` resolving through the codegen loader is a real gap, but
it is a second axis from this slice's, and widening both at once would leave neither measured.
`CompletionData` gained the two components the scan was dropping (`ExternalReference.sourceName`,
`Method.descriptor`) rather than growing a second capture-facing shape beside it, because both are
facts the scan holds and the LSP surface can use, and a second shape would be a second thing to
keep in agreement. And the cross-root dedup's shadowed duplicate is still discarded rather than
quarantined; the source column is what makes the detection possible, and it can land with the
consumer that wants it.

**Not landed here: `sql_table` carries no `source_name`.** The delivery plan for this slice names
`store_source` and `jvm_class.source_name`, and those are in; the catalog's own provenance is the
classpath entry its generated classes were loaded from, which needs a code-source probe on the
generated `Table` class, and inventing that reading before anything needs it is the speculation the
rest of the item avoids. It is what the partitionability acceptance line still owes.

**Tests.** A jar-resident `@scalarType` reference raises no diagnostic, which is the reported bug and
the regression guard. Completion on `scalar LocalDate @scalarType(scalar: "|")` offers the
jar-resident constant. `ClasspathScanner` unit tier: a fixture jar is scanned, its public classes and
methods surface, and a class present in both a jar and a directory root surfaces once with
classpath-order precedence. Two overloads taking same-named types from different packages both
survive capture, the descriptor regression guard. A jar whose stamp is unchanged is not re-scanned
and one whose contents change is, asserted against `store_source` rather than against a private
cache. `graphitron-sakila-example` already carries the live build-through fixture, so the codegen
half needs no new coverage; what is new is the LSP tier asserting the two classpaths agree.

### Slice 5: the store persists under `target/`

Absorbed from the warm-start-store filing. The reason it could not stay a follow-on is the
partitionability convention above: persistence does not add a feature on top of the schema, it
imposes a requirement the schema must already satisfy, and a model written without it cannot acquire
it later without rekeying. The absorbed item's own open list ended with "which meta relation carries
the stamp", which is a DDL question wearing a scheduling item's clothes.

**Why persist.** The store is in-memory and per-run, so every surface that wants facts pays a full
capture pipeline before it can answer anything and the LSP and MCP server boot cold. With the
populated store written to an H2 file under `target/` at the end of a run, a surface opens the
previous run's facts about as soon as the JVM has booted and serves completions, schema queries and
the read-only SQL surface immediately, refreshing when its own run completes; with registry capture
that refresh is per-file incremental rather than a full pipeline. A plain SQL client, an agent among
them, can query the fact base as a build artifact without booting graphitron at all.

**What it does not change.** The file is persisted state and never state *of record*. It is stamped,
and any mismatch discards and rebuilds, so no migration ever exists and `target/` semantics keep
deletion always correct. Surfaces label answers with the stamp's run identity, so staleness is
visible rather than silent.

**What it does change, which this item previously denied.** "Dead with the process" stops being
true, and the store becomes an accumulation across runs, which the incremental-refresh section
already assumes when it calls the store the accumulated registry. The stamp is therefore two things.
`store_stamp` decides whether the file is intelligible at all, and that is what keeps migrations out.
`store_source.stamp` decides which partitions survive, and without it every edit discards everything,
including a classpath scan measured at 4.0 s that no schema edit had any reason to invalidate. The
absorbed item carried only the first, which is what made it look separable.

**Open for this slice.** The persist mechanism, whether the store is file-backed during the run or
exported at the end. Reader concurrency while a build writes: H2 file locking, copy-on-open, or
auto-server mode. Both are runtime questions the schema does not constrain, which is why they are
the last thing decided rather than the first.

**Not started.** The schema side it depended on is in: `store_source` exists, carries a stamp, and
every base row the classpath and SDL families write is reachable from one, so the partitionability
requirement persistence imposes is satisfied ahead of the mechanism (the `sql_` family's own source
column excepted, noted under slice 4). What remains is the whole of the mechanism: the file under
`target/`, `store_stamp` and its discard-and-rebuild on mismatch, and the two runtime questions
above. Slice 4's measurement is the case for it: 15 s of scan-plus-insert per generator pass, paid
five times in one module's build, is work a warm start does not repeat.

### Notes carried forward

**A fold in an agreement anchor is a symptom, not plumbing.** Every defect slices 3 and 4 fix
surfaced at one, and the driver cannot distinguish a fold that bridges a real grain difference
(capture total against a pruned model) from one that bridges a mismatch capture introduced.
Registering an arm that needs a fold should carry the reason the grains differ. The sweep that found
these ran over every producer and found the same defect wherever capture reads a projection built
for another surface and none where it reads the parse directly, which locates the cause:
`CatalogFacts` and `CompletionData` are shapes designed for the MCP catalog tools and the LSP's
completion popups, and capture inherited every narrowing they made. `SdlFactCapture` reads the
registry AST and is clean.

**Three producers came back clean**, recorded so the pass does not re-derive them. The `graphitron_`
decode helpers return null or an empty list for an absent argument and quarantine a type mismatch, so
no default-filling reaches the store and the authored-values convention holds. `sql_column.sql_type`
and `nullable` are jOOQ's readings and their comments say so; a disclosed projection is the healthy
case and the pattern above is what an undisclosed one looks like. Column and table comments normalise
the empty string upstream, so no relation confuses "" with absent.

**The renames sharpen three findings rather than relieving them**, which is why the disclosure ships
with the prefix rather than after it. A column called `descriptor` under a family named for the JVM
claims the JVM's own artefact more loudly than it did under `extension_`; `sql_index` and
`sql_column.ordinal` claim SQL's index set and SQL's column order where `catalog_` at least read as a
tool's view of them; and `jvm_class` sounds more total than `extension_class` did, since a role name
invites the question "extending what?" while a vocabulary name simply asserts the category. Every one
of these names is more honest about whose vocabulary the row is written in and more misleading about
which rows are present, so the pass that lands them owes each relation a comment stating its filters.

**Narrowing the method census to relevant signatures was proposed and is not implementable without
pre-resolution.** Recorded because the row count invites the idea. Nine coordinates name a Java
method: `graphitron_service`, `graphitron_source_row`, `graphitron_external_field`,
`graphitron_enum`, the field and argument conditions, and the three reference-step relations.
Exactly one shape constrains a signature. Conditions must return `org.jooq.Condition`, which
`ClasspathScanner` already classifies from the un-erased descriptor into `returns_condition`, so
that filter is available as a predicate and applying it at capture buys nothing a `WHERE` does not.
The other four constrain neither return type nor parameters, parameters binding as Arg, Context,
Sources, DslContext, Table or SourceTable, so every public method is a candidate. Filtering those
would mean inferring relevance during capture, the defect class the sweep found four instances of,
and its failure mode is slice 4's own bug moved from the class axis to the method axis: a `@service`
naming a real method the filter excluded reads as unknown in the store while the codegen loader
resolves it, and completion fails the same way, since an author typing a `method:` value needs every
public method of the named class offered. The cost the proposal aims at is insert throughput and not
query cost, a resolution being `WHERE class_name = ? AND method_name = ?` however many rows it
ignores, which is why slice 4's measurement is the next step rather than a scoping decision. Should
it show the load dominating, the least-bad narrowing is a configured package scope: author-controlled,
disclosable in the relation's comment, and failing safe, since an author who scoped too tightly gets a
diagnostic to act on where a signature filter hides a method nobody knows is absent. Any narrowing at
all inherits the disclosure obligation the sweep establishes.

**Two improvements the contract does not demand**, noted so a pass can take or leave them. A decode
arm that hits a missing required argument returns without writing either its decoded row or a
`graphitron_undecoded_argument` row (`GraphitronFactCapture`'s `sourceRow`, `mutation` and `pivot`
arms among others); the verbatim `graphql_` row survives, so a detection can still find the
application, but nothing in the semantic stratum records that the decode declined, and it is worth
either quarantining the application or naming the "verbatim graphitron application with no decoded
row" detection as the intended reading. And `captureFacts` builds a second `JooqCatalog` and re-walks
the catalog and the classpath (`GraphQLRewriteGenerator`) while `buildOutput` reuses the
`catalogFacts` it already has, which is shadow-period cost only and cheap to thread through.

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
  `connectionSynthesis` component, the federation macro against the registry the pipeline
  actually captures, SQL table and column census against the catalog, JVM method census against
  the scanner. No census fold survives the delivery below: the constraint reshaping removes the
  one that bridged `CatalogFacts`' uniqueKeys view (which excluded the primary key and deduped on
  column set), and the real descriptor removes the descriptor-erased comparison, so a fold
  reappearing is a signal rather than plumbing. The anchors are the shadow period's honesty check
  and retire as consumers migrate.
- The gate family runs against the bootstrapped store: comment coverage (every table and column
  commented, checked via `INFORMATION_SCHEMA`) and one query per cross-relation invariant the
  DDL cannot state (`default_value_sdl` only
  under INPUT_OBJECT parents, application ordinals and `merge_ordinal` dense from 0 per group,
  wrapping decode consistent with `type_sdl` where SQL can express the correspondence, every
  application resolving to a captured definition, a decoded application keeping its verbatim row,
  the federation dual projection in agreement). A repeated application of a non-repeatable
  directive is deliberately not in this list: under registry capture it is author-reachable, so
  it is a detection.
- Every base relation is partitionable by the source that produced it, per the convention above:
  `store_source` carries every schema file, directory root and jar the store read, and no base row
  is unreachable from one. A per-source refresh deletes exactly that source's rows.
- The class census is the compile classpath, so the LSP and the codegen loader resolve the same
  set: a jar-resident `@scalarType` constant raises no diagnostic and is offered in completion.
  Re-scanning is stamped per source, so an unchanged jar is read once.
- Every relation whose contents are filtered says so in its comment. The comment-coverage gate
  cannot check that a comment is true, so this one is a review rule, named as such.
- The store persists to an H2 file under `target/` at the end of a run and a surface opens it
  without a capture pass; a `store_stamp` mismatch discards and rebuilds rather than migrating.
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
