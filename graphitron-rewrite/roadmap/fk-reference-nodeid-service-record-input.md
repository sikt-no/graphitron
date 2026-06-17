---
id: R315
title: "Bind FK-reference @nodeId onto jOOQ-record @service params (port legacy @reference FK resolution; generalize R311)"
status: Spec
bucket: feature
priority: 4
theme: nodeid
depends-on: []
created: 2026-06-16
last-updated: 2026-06-17
---

# Bind FK-reference @nodeId onto jOOQ-record @service params (port legacy @reference FK resolution; generalize R311)

A `@service` parameter that is a generated jOOQ `TableRecord` cannot be
populated from an input type whose `@nodeId` fields are **foreign-key
references to other node types** (the common "status / history / junction
row" shape). R311 shipped the *same-table* identity case only and added a
gate that actively **rejects** the cross-table FK case. The cross-table
mechanism already exists and is battle-tested in **legacy Graphitron**
(`NodeIdReferenceHelpers` + `MapperContext.getSetMappingBlock`); this item
ports it into the rewrite and generalizes R311's `JooqRecord` extraction to
cover it. Self-reference (self-FK record population) is explicitly **out of
scope** — legacy never solved it either.

## Motivating consumer breakage

`utdanningsregisteret-graphql-spec/schema_beta.graphql` — four mutations
fail codegen, all on the same missing capability:

| Mutation | input type | param record |
|---|---|---|
| `endreUtdanningsspesifikasjonsstatus` | `EndreUtdanningsspesifikasjonsstatusInput` | `UtdanningsspesifikasjonsstatusRecord` |
| `endreUtdanningsmulighetstatus` | `EndreUtdanningsmulighetstatusInput` | `UtdanningsmulighetstatusRecord` |
| `endreUtdanningsinstansstatus` | `EndreUtdanningsinstansstatusInput` | `UtdanningsinstansstatusRecord` |
| `endreUtdanningsinstansnavn` | `EndreUtdanningsinstansnavnInput` | `UtdanningsinstansnavnRecord` |

Each input keys its record off `@nodeId` fields that reference **other**
node types, e.g.:

```graphql
input EndreUtdanningsspesifikasjonsstatusInput {
  utdanningsspesifikasjonsId: ID! @nodeId(typeName: "Utdanningsspesifikasjon")
  utdanningsstatusId:        ID! @nodeId(typeName: "Utdanningsstatus")
  fraDato:                   Date! @field(name: "DATO_FRA")
}
```

The status record's columns are `utdanningsspesifikasjonsnr`,
`utdanningsstatuskode`, `datoFra` — so each `@nodeId` decode must land in a
**foreign-key column** on the status record, not the record's own identity.

Two symptom shapes, one root cause (the difference is only the legacy
`@table` directive — see "Classification convergence"):

- No `@table` on the input → deduced as `JooqTableRecordInputType` → reaches
  R311's branch → fails the lifted record-type-mismatch gate
  (`InputBeanResolver.java:325`): *"the param is jOOQ record
  'UtdanningsspesifikasjonsstatusRecord', but @nodeId(typeName:
  "Utdanningsspesifikasjon") … decodes into 'UtdanningsspesifikasjonRecord'
  … A NodeId cannot be decoded into a different record type."*
- `@table` present → never reaches R311's branch → falls to the bean path →
  *"bean class '…Record' has no fields matching the SDL input type."*

## Root cause / why R311 doesn't cover it

R311 implemented legacy's **non-reference** branch only: when the
referenced node's `@table` equals the param record's table, write the node's
`keyColumns` directly. It then guards every other case with two gates that
reject the FK shape this item needs:

- `InputBeanResolver.java:266` — *"more than one @nodeId field … a
  jOOQ-record @service param has exactly one identity."* (FK records carry
  several FK references.)
- `InputBeanResolver.java:325` — same-record gate: the `@nodeId`'s node table
  record must equal the param record. (FK references point at a *different*
  table by definition.)

R311's own changelog entry (`c0e8626`) flags this: *"Out of scope:
FK-reference `@nodeId` and `@table`-on-input (both R97)."* That deferral was
imprecise — see "Relationship to R97."

## Authoritative behavior to port (legacy Graphitron)

Source of truth is legacy, under
`graphitron-codegen-parent/graphitron-java-codegen/src/main/java/`. The
rewrite must reproduce this, expressed in its own model:

- **Target column resolution is FK-constraint-driven, NOT name-match.**
  `NodeIdReferenceHelpers.mapKeyColumnsThroughForeignKey` (`:113-126`)
  resolves a jOOQ `ForeignKey` between the record's table and the node's
  table, then maps the node's `keyColumns` → the FK's **parent** columns
  (`inverseKey().getFields()`) → the FK's **child** columns (`getFields()`,
  which live on the record). The decoded wire ID is written into those child
  columns. (The motivating consumer happens to have FK-child names equal to
  parent-key names, which is why it superficially looks like name-match — but
  the real mechanism is FK pairing and so it handles renamed FK columns
  correctly.)
- **Reference vs. same-table** is decided by `isNodeIdReferenceField`
  (`:38-46`): node table ≠ record table (or any `@reference` present) → FK
  branch (`setRecordReferenceId`); node table == record table → identity
  branch, write `keyColumns` directly (`setRecordId`). The latter is exactly
  R311's shipped case.
- **`@reference(path: [{table, key}])`** names which FK when the catalog is
  ambiguous. Only the **first** path element is consulted for record
  population (`resolveForeignKey` `:103-111` uses `findFirst()`); `key:` is
  the FK constraint name (used verbatim), `table:` scopes an implicit lookup.
  Multi-hop paths are a fetch/join concern, not record population.
- **`@reference` requiredness:** deduced when **exactly one** FK connects the
  two tables (`TableReflection.findImplicitKey` returns a key only when
  `keys.size() == 1`); **required** (explicit `key:`) when **zero or >1**
  FKs exist.
- **Composite keys** map column-by-column through the one FK, preserving
  `keyColumns` order (`:113-126`).
- **Plain `@field(name:)`** (no `@nodeId`) → direct jOOQ setter, no FK
  indirection (`MapperContext.java:398`).
- **Two failure modes to reproduce** (as rewrite `UnclassifiedField`
  rejections, not runtime exceptions): ambiguous/zero FK with no explicit
  `@reference` key (legacy: *"Cannot find foreign key for nodeId field
  <path>"*); a node `keyColumn` not covered by the chosen FK's parent
  columns (legacy: *"Node ID field <X> is not found in foreign key <FK>'s
  fields."*).

### Consumer is already correct as written

Every `@nodeId` in all four mutations has **exactly one** FK between the
record's table and the referenced node's table (verified in the generated
`jooq/utdanning/Keys.java`):

| Record table | reference | FK | count |
|---|---|---|---|
| `UTDANNINGSSPESIFIKASJONSSTATUS` | → Utdanningsspesifikasjon | `…UTDANNINGSSPESIFIKASJON_…_FK` | 1 |
| | → Utdanningsstatus | `…UTDANNINGSSTATUS_…_FK` | 1 |
| `UTDANNINGSMULIGHETSTATUS` | → Utdanningsmulighet / Utdanningsstatus | single each | 1/1 |
| `UTDANNINGSINSTANSSTATUS` | → Utdanningsinstans / Utdanningsstatus | single each | 1/1 |
| `UTDANNINGSINSTANSNAVN` | → Utdanningsinstans | single | 1 |

So **no `@reference` needs to be added** to the consumer schema — every
reference is deduced from the single catalog FK. The schema is correct; the
rewrite just has to implement the deduced-FK branch. `@reference(key:)` only
becomes necessary for a future table carrying two FKs to the same node.

## Desired state

1. **Binding rule** for `@nodeId(typeName: X)` onto a jOOQ-record param:
   - X's table **==** record's table → write X's `keyColumns` directly
     (R311's existing branch, unchanged).
   - X's table **≠** record's table → resolve the FK between the record's
     table and X's table; map X's `keyColumns` through the FK's parent→child
     pairing; decode the wire ID into the FK's **child** columns on the
     record. FK **deduced when exactly one** exists, else `@reference(path:
     [{key:}])` names it. Multiple FK `@nodeId` fields per record allowed.
2. **`@field(name:)`** (no `@nodeId`) → direct setter, no FK indirection
   (unchanged from R311).
3. **Classification convergence.** On the `@service`-param path, binding and
   error messages must be **identical with or without `@table`** on the
   input. Today `@table`-present routes to the bean path and `@table`-absent
   to the jOOQ path — that divergence is the bug-behind-the-bug and must go.
   This item does **not** deprecate `@table` (that is R97); it only requires
   the `@service`-param binding to ignore-or-agree with it (if present, it
   must name the deduced table, else a build error).
4. **Strict rejections** (rewrite-style `UnclassifiedField`): ambiguous/zero
   FK with no explicit `@reference` key; a node `keyColumn` not covered by
   the chosen FK; `@field` resolving to no column (R311, unchanged). No
   silent drops.
5. **Null semantics** (jOOQ `changed` flags): omitted input key → setter not
   called → `changed=false` → excluded from INSERT/UPDATE; explicit `null` →
   `set(null)` → written as `NULL`; non-null fields (`ID!` / `!`) still
   required. Implementation note: R311's helper builds via
   `record.fromArray(...)` (sets all columns at once); honoring
   omitted-vs-null requires switching to **per-binding conditional `set`**
   keyed on `map.containsKey(...)`, applied uniformly to `@field` and
   `@nodeId`-decoded columns. See **D4** for the resolved mechanism (the
   conditional load stays on the non-deprecated `fromArray` coercion path)
   and the scope decision (folded into R315 with mitigations).
6. **Model shape.** Generalize R311's `CallSiteExtraction.JooqRecord`: drop
   the single-`@nodeId` gate (`:266`) and the same-record gate (`:325`);
   `keyDecode` goes from `Optional<RecordKeyDecode>` to
   `List<RecordKeyDecode>`; each decode names *where the decoded values land*
   via a sealed `KeyProjection` (`Identity` = the node's own key, R311;
   `FkChild` = the FK's child columns on this record, R315) rather than an
   overloaded column list (see **D1**). Factor the FK column-pairing core out
   of `synthesizeFkJoin` (see **D3**) so the join path and record population
   share one bug-fixed orientation site; a future pojo (member-axis) item
   reuses the decode-resolution core verbatim.

## Relationship to R311 and R97

- **R311 (shipped, `c0e8626`)** — this item generalizes R311's `JooqRecord`
  extraction from the same-table identity case to the cross-table FK case.
  R311's branch becomes the `X.table == record.table` arm of the rule above.
- **R97 (`consumer-derived-input-tables`, Backlog, gated behind R94)** —
  R311 deferred "FK-reference `@nodeId` and `@table`-on-input (both R97)",
  but R97 only owns the **`@table`-on-input deprecation** + `argMapping`
  grouping. R97 *presupposes* `@nodeId` binding already works
  (`R97:79,147-149`) and never specifies the FK-constraint mechanism. This
  item delivers that mechanism; R97 later removes `@table` and adds fan-out
  grouping on top. The two are independent (this item needs neither R94 nor
  R97). R97's "both R97" framing should be narrowed to the `@table` half.

## Out of scope

- **Self-reference (self-FK record population).** Legacy never solved it:
  `isNodeIdReferenceField` decides reference-ness by table identity, so it
  cannot tell a row's own PK apart from a self-FK; legacy *forbids*
  non-`@splitQuery` self-references (`SelfReferenceError`/`Warning`).
  Supporting it needs a new disambiguator (e.g. `@reference(key:)`
  overriding the own-identity default) — a separate design item. None of the
  four motivating mutations need it.
- **Pojo (member-axis) FK-`@nodeId`** — same decode core, but the "set"
  target is a Java member, and one `@nodeId` field → N key columns has no
  column catalog to match against (a real modeling decision). Separate item;
  factor the decode core here so it reuses cleanly.
- **Multi-hop record population** — legacy uses only the first `@reference`
  path element for record decode; later hops are a fetch/join concern.
- **`@table`-on-input deprecation / `argMapping` grouping** — R97.
- The other codegen errors in the same consumer run
  (`opprettUtdanningsspesifikasjonOgUtdanningsmulighet` payload
  classification, `opprettCampus` composite-PK INSERT, `Query.noop`) —
  unrelated, separate items.

## Design decisions (resolved at Spec)

Resolved against the code with a principles-architect read; these answer the
Backlog "Open questions" and supersede them. Confirmed along the way:
`@reference(path: [ReferenceElement{table, key, condition}])` is already fully
parsed in the rewrite (`BuildContext.parsePath` / `parsePathElement` →
`JoinStep.FkJoin`; `{key:}` resolves via `catalog.findForeignKey`, `{table:}`
deduces via `findForeignKeysBetweenTables`), and the catalog already exposes
every FK primitive this item needs (`findUniqueFkToTable`, `findForeignKey`,
`findForeignKeysBetweenTables`, `findTableByRecordClass`), so no `@reference`
classifier plumbing and no record-class→`TableRef` catalog lift are required.

### D1. Carrier shape: one `RecordKeyDecode`, projection as a sealed sub-axis

`JooqRecord.keyDecode` becomes `List<RecordKeyDecode> keyDecodes` (multiple
`@nodeId` fields per record); the at-least-one-binding floor updates to
`columnBindings.isEmpty() && keyDecodes.isEmpty()`. `RecordKeyDecode` keeps its
decode spine (`sdlFieldName`, `encoderClass`, `typeId`), gains a `nonNull` flag,
and names *where the decoded values land on this record* via a sealed
`KeyProjection`:

```java
record RecordKeyDecode(String sdlFieldName, ClassName encoderClass, String typeId,
                       KeyProjection projection, boolean nonNull) { ... }

sealed interface KeyProjection permits Identity, FkChild {
    /** Same-table identity (R311): load decoded values into the node's own key columns (== this record's). */
    record Identity(List<ColumnRef> keyColumns) implements KeyProjection {}
    /** Cross-table reference (R315): load decoded values into the FK's child columns on this record. */
    record FkChild(ForeignKeyRef fk, List<ColumnRef> childColumns) implements KeyProjection {}
}
```

Each arm carries its own load-target columns, so decode arity is the arm's
column count and no cross-field invariant is needed. We do **not** reuse R311's
`keyColumns` field for two meanings: the codebase's own
`JoinSlot.FkSlot(sourceSide, targetSide)` rejects exactly the "one accessor
whose meaning depends on the variant" shape, and "the record's identity
columns" vs "an FK-child projection of *another* node's identity" are different
facts. `nonNull` (the SDL field's `ID!`-vs-`ID`) drives throw-on-mismatch vs
conditional-set uniformly across **both** projections, read off one data field
rather than an `instanceof` on the projection. The `@service` method owns the
insert/update (Graphitron only populates the record), so the framework does
**not** force a same-table identity to be non-null: a nullable (`ID`) identity is
a legitimate service-side upsert input (omitted → unset PK → the service inserts)
and is handled exactly like a nullable FK reference. This deliberately changes
R311's same-table identity, which formerly always threw on null whether `ID!` or
`ID`; see **D4** for the behavior change and its mitigations. A full sibling
carrier is rejected: the decode spine is identical, so the cut is the projection
axis only.

### D2. Convergence: the binding axis is the Java param type; the SDL classification is a cross-check (requirement #3)

`InputBeanResolver.enrich`'s column-axis trigger moves from "SDL type classified
as `JooqTableRecordInputType`" to **"the param's Java element type implements
`org.jooq.TableRecord`"** (`elementClass` is already loaded; add a
`TableRecord`-specific test alongside `isJooqRecord`). The record's `TableRef`
is derived from the record class via `ServiceCatalog.resolveTableByRecordClass` /
`JooqCatalog.findTableByRecordClass` (both exist). The SDL type's classification
(`JooqTableRecordInputType` on the `@table`-absent path, `TableInputType` on the
`@table`-present path) becomes a *consistency cross-check*: if it carries a
table it must name the same table as the derived record class, else an
`UnclassifiedField` naming both. This collapses today's divergence
(`@table`-present → bean path → "has no fields matching"; `@table`-absent → R311
branch) onto one path: "derive from types, not directives" (R96/R97), with
`@table` demoted to a checked, ignorable corroborator. `JooqTableRecordInputType`
is **not** dead afterward: it still drives R94 input-record validation and LSP,
and is the cross-check signal on the `@table`-absent path; it loses only its
role as the `InputBeanResolver` gate.

### D3. FK pairing: extract and reuse `synthesizeFkJoin`'s slot core

Extract the FK-orientation-and-pairing body of `BuildContext.synthesizeFkJoin`
(`:1133-1155`) into `List<JoinSlot.FkSlot> resolveFkSlots(ForeignKey<?,?> f,
String sourceSqlName, boolean selfRefFkOnSource)`. `synthesizeFkJoin` keeps
wrapping it with alias / targetTable / whereFilter for the join case; the new
record-population resolver calls the *same* core with the record table as
source, getting `FkSlot(recordChildCol, nodeParentCol)` pairs directly. This
inherits the bug-fix `synthesizeFkJoin` documents (parent columns from
`ForeignKey.getKeyFields()`, **not** `getKey().getFields()` — legacy
`NodeIdReferenceHelpers` used the latter via `getInverseKey().getFields()`,
which mis-pairs composite FKs with reordered referenced columns) and keeps one
site owning FK orientation. We do *not* call `synthesizeFkJoin` whole (its
alias / `FkJoin` wrapper is join-emission machinery record population doesn't
need — the coupling smell) and do *not* re-port legacy's pairing (it would
re-introduce the fixed bug). `SynthesizeFkJoinReorderedKeysTest` then pins
orientation for both consumers.

### D4. Null semantics: fold the `fromArray` → conditional load in, with mitigations (requirement #5)

`JooqRecordInstantiationEmitter` switches from "set every column via
`fromArray`" to **per-binding conditional load keyed on
`raw.containsKey(sdlFieldName)`**, for both plain `@field` columns and each
`@nodeId` decode. Coercion stays on the non-deprecated `fromArray` path (a
`containsKey`-guarded arity-1 `fromArray` per present column, or a filtered
batch over present columns) — **not** the deprecated-for-removal
`DataType.convert(Object)` R311 deliberately avoids. Semantics split on
**nullability**, applied identically to identity and FK-reference decodes (and to
plain `@field` columns): a `nonNull` (`ID!` / `!`) binding → always
decode-and-load, throw on null/mismatch (R195); a nullable (`ID`) binding →
omitted key not loaded (`changed=false`, excluded from INSERT/UPDATE),
present-`null` loaded as `NULL`, present-value decoded (a wrong-type decode still
throws). The projection (identity vs FK child) decides only *where* the decoded
values land, never *whether* a missing value throws.

This change *also alters R311's shipped same-table behavior* on two counts: a
`fromArray` batch becomes conditional loads, **and** a nullable (`ID`) same-table
identity moves from always-throw-on-null to skip-when-omitted (the upsert input
chartered in D1). Folded into R315 deliberately (requirement #5 charters it; the
FK-reference case needs conditional-set to be correct), with three required
mitigations: (1) the R315 changelog entry states *both* R311 behavior changes
explicitly — the batching shift and the nullable-identity throw→skip; (2) an
**execution-tier** test pins omitted-vs-null-vs-set on a nullable column,
including a nullable same-table identity (omitted → unset PK, the service-owned
insert path) — no other tier can observe `changed=false` exclusion (pipeline sees
the carrier, compile sees column existence, emitted-body string assertions are
banned); (3) the emitter's "two disjoint `fromArray` groups" javadoc contract
**and** the `RecordKeyDecode` carrier javadoc that asserts identity "always
throws … whether `ID!` or `ID`" are both retired so neither becomes a false
invariant.
*Alternative considered:* carve the conditional-load change into a precursor
item with its own execution test, leaving R315 purely FK-references.
Recommended-against to honor chartered requirement #5 and avoid a cross-item
ordering dependency for a one-emitter change; the Spec → Ready reviewer may
split it if they prefer cleaner attribution.

## Implementation

Flat file-by-file (all land together; no observable intermediate state):

- **`model/CallSiteExtraction.java`** — `JooqRecord`: `Optional<RecordKeyDecode>
  keyDecode` → `List<RecordKeyDecode> keyDecodes`; update the floor. Reshape
  `RecordKeyDecode` to `{sdlFieldName, encoderClass, typeId, KeyProjection
  projection, boolean nonNull}`; add sealed `KeyProjection` (`Identity` /
  `FkChild`) with non-empty compact-constructor checks. (D1)
- **`BuildContext.java`** — extract `resolveFkSlots(...)` from
  `synthesizeFkJoin`; add a record-population FK resolver: given the record
  `TableRef`, the field's `@nodeId` typeName, and any `@reference(key:)`,
  resolve the FK (explicit `key` via `findForeignKey`; else
  `findUniqueFkToTable(recordTable, nodeTable)`; zero/multi → `fkCountMessage`),
  map the node's key columns (from `resolveNodeIdRecordDecode`, node-key order)
  through the slots to FK child columns, return a `KeyProjection.FkChild` or a
  rejection (uncovered key column). (D3)
- **`InputBeanResolver.java`** — change the `enrich` trigger to the param's
  Java `TableRecord` type; derive the table from the record class; cross-check
  the SDL classification's table when present (D2). In `buildJooqRecord` /
  `buildRecordKeyDecode`: drop the single-`@nodeId` gate (`:266`) and the
  same-record gate (`:325`); per `@nodeId` field, branch node-table ==
  record-table → `Identity` (R311) vs ≠ → `FkChild` (new resolver); collect a
  `List<RecordKeyDecode>`; set `nonNull` from the SDL field.
- **`generators/JooqRecordInstantiationEmitter.java`** — emit `containsKey`-
  guarded conditional loads for `@field` columns and each `RecordKeyDecode`,
  switching on `KeyProjection` for the load targets and honoring `nonNull`.
  Retire the stale "two disjoint `fromArray` groups" javadoc. (D4)
- **Docs** — note FK-reference `@nodeId` on `@service` jOOQ-record params in the
  `@nodeId` / record-input documentation (no roadmap markers in user-facing
  prose).

## Tests

Mirror `JooqRecordServiceParamPipelineTest` (the R311 template), backed by the
existing test catalog (`film_actor` → single FKs to `film` and `actor`;
`FilmActorRecord`; `TestServiceStub.modifyFilmActorRecord`):

- **Pipeline — pure FK references (motivating shape):** input `{ filmId: ID!
  @nodeId(typeName: "Film"), actorId: ID! @nodeId(typeName: "Actor") }` →
  `FilmActorRecord`. Assert two `keyDecodes`, each `FkChild` projecting to
  `film_actor.film_id` / `film_actor.actor_id`, FK deduced; `createFilmActorRecord`
  emitted; field not `UnclassifiedField`.
- **Pipeline — convergence (requirement #3):** the same input *with*
  `@table(name: "film_actor")` produces the identical carrier as without; a
  `@table` naming a different table rejects (`UnclassifiedField`, names both).
- **Pipeline — mixed Identity + FkChild + plain `@field`:** a record carrying
  its own `@nodeId` identity, an FK-reference `@nodeId`, and a `@field` column
  together (e.g. an `address`-shaped table: PK + FK to `city` + a scalar, if
  those are node types; else a synthesised fixture). Assert one `Identity`, one
  `FkChild`, one `ColumnBinding`.
- **Pipeline — composite / reordered FK pin:** an FK-reference whose FK is
  composite with referenced-column order differing from the parent PK order,
  asserting child columns map by FK pairing (the `getKeyFields()` reuse; mirrors
  `SynthesizeFkJoinReorderedKeysTest`).
- **Pipeline — rejections (all `UnclassifiedField`):** zero/multi FK between
  record and node table with no `@reference(key:)`; a node key column not
  covered by the chosen FK; (unchanged R311) `@field`→no column, cardinality
  parity, missing `typeName`.
- **Pipeline — explicit `@reference(key:)` disambiguation (positive):** a record
  table carrying **two** FKs to the same node type (a synthesized fixture; the
  existing catalog has no such shape); assert `@reference(key: "<fkName>")`
  selects the named FK so the projection lands in *that* FK's child columns,
  while omitting the key on the same shape gives the ambiguous rejection above.
  Pins the directive-arg → selected-FK binding the deduced-FK tests cannot reach.
- **Execution (sakila):** a `@service` mutation taking a `FilmActorRecord` built
  from `{filmId, actorId}` inserts a `film_actor` row; assert the persisted
  `film_id` / `actor_id` equal the decoded ids. Plus the **null-semantics** cases
  (D4), the only tier that observes `changed=false` exclusion: a nullable plain
  column omitted vs explicit-null vs set; and, on a single-PK table with a
  DB-assignable PK (e.g. `FilmRecord`), a nullable same-table identity (`filmId:
  ID @nodeId`) omitted (PK left unset → the service inserts) vs set (the update
  path), each yielding the correct persisted column set / value.
- **Compile (graphitron-sakila-example):** carry the FK-reference shape so the
  generated `*Fetchers` compile against real jOOQ at Java 17.

## Validator-mirror checklist

Every removed gate is replaced and every new branch has a build-time
`UnclassifiedField` (routed `InputBeanResolver.Result.Failed` →
`ServiceDirectiveResolver.Resolved.Rejected`, the path R311 already rides):

- Removed `:266` (more than one `@nodeId`) → no replacement; multiple `@nodeId`
  is now legal (each resolves independently).
- Removed `:325` (same-record gate) → the cross-table case now *resolves*
  (`FkChild`); its failure modes are newly guarded: (a) zero/multi FK without
  `@reference(key:)`; (b) a node key column not covered by the FK.
- New: `@table`-vs-derived-table disagreement on the convergence path.
- Unchanged: `@field`→no column; cardinality parity; `@nodeId` without
  `typeName`.
- Structural backstop: `KeyProjection` arms reject empty column lists in their
  compact constructors.

## Sequencing and scope note

Single cycle; the four file changes land together (the model reshape forces the
resolver and emitter, and the convergence trigger shares the path). The one
genuine scope fork is D4 (fold vs split the `fromArray`→conditional-load
change); recommended folded with the three mitigations above.
