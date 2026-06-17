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
     (R311's existing branch, unchanged), **unless** the field carries an
     explicit `@reference`. An `@reference` on a same-table `@nodeId` can only
     name a self-FK (the out-of-scope self-reference request), so it is
     **rejected** (see #4 and "Out of scope"), not silently routed to the
     identity branch with the authored directive ignored. This mirrors legacy's
     `isNodeIdReferenceField`, where any `@reference` forces the reference branch.
   - X's table **≠** record's table → resolve the FK between the record's
     table and X's table; map X's `keyColumns` to the FK's **child** columns
     on the record **by column identity** (match each node key column against
     the FK's referenced column, not by positional zip; see **D3**), and decode
     the wire ID into those child columns in node-key (decode) order. FK
     **deduced when exactly one** exists, else `@reference(path: [{key:}])`
     names it. Multiple FK `@nodeId` fields per record allowed.
2. **`@field(name:)`** (no `@nodeId`) → direct setter, no FK indirection
   (unchanged from R311).
3. **Classification convergence.** On the `@service`-param path the binding and
   error behavior must be **identical-or-clearly-rejected with or without
   `@table`** on the input. Today `@table`-absent reaches the jOOQ path while
   `@table`-present routes to the bean path and dies on the misleading "bean
   class … has no fields matching"; that divergence is the bug-behind-the-bug
   and must go. `@table` on the input is contradictory here: it classifies the
   input as `TableInputType`, whose contract is *Graphitron owns the DML* (the
   `@mutation` path), whereas a jOOQ-record `@service` param means *the service
   owns the DML* (R311). So this item **rejects** `@table` on a `@service`
   jOOQ-record param with a clear `UnclassifiedField` ("drop `@table`; this
   input feeds a `@service` param, so the service owns record construction"),
   which replaces the misleading bean error with an honest one. It does **not**
   deprecate `@table` generally (that is R97, which the rejection is
   forward-compatible with). Silently *converging* `@table`-present onto the
   jOOQ path is rejected as an alternative: it would leave the `TableInputType`'s
   already-resolved `@reference` / `ColumnReferenceField`s computed-then-ignored
   while R315 re-derives the FK, i.e. two readers of one directive (see **D2**).
4. **Strict rejections** (rewrite-style `UnclassifiedField`): ambiguous/zero
   FK with no explicit `@reference` key; a node `keyColumn` not covered by
   the chosen FK; an explicit `@reference` on a same-table `@nodeId` (a self-FK
   request, out of scope); `@field` resolving to no column (R311, unchanged). Multiple
   `@nodeId` fields are now legal (the `:266` gate is removed). When two decodes
   target the **same** column, their runtime value-agreement is **not** guarded
   here: that is a data-dependent (runtime) concern deferred to **R322**
   (`nodeid-shared-column-agreement`). R315 emits the decodes independently
   (last-write-wins) for that overlap edge, which its motivating consumer never
   hits (all four mutations' references are disjoint-column). No silent
   *classification* drops.
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
   `List<RecordKeyDecode>`. `RecordKeyDecode` keeps its decode spine
   (`sdlFieldName`, `encoderClass`, `typeId`), gains a `nonNull` flag, and
   generalizes R311's `keyColumns` to `targetColumns`: the resolved columns
   **on this record** the decoded values load into, whether they are the
   record's own identity (same-table, R311) or an FK's child columns
   (cross-table, R315). The identity-vs-FK difference is computed by the
   resolver and is **not** carried on the model: there is no `KeyProjection`
   sub-axis, because the columns have one meaning, "load targets on this
   record," in both arms (see **D1**). Factor the FK column-pairing core out of
   `synthesizeFkJoin` (see **D3**) so the join path and record population share
   one bug-fixed orientation site; a future pojo (member-axis) item reuses the
   decode-resolution core.

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
  four motivating mutations need it. Until that item lands, the only way to
  *request* self-FK semantics (an explicit `@reference` on a same-table
  `@nodeId`) is **rejected** (requirement #4), preserving legacy's loud
  forbiddance instead of silently writing the record's own PK.
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

### D1. Carrier shape: one `RecordKeyDecode` carrying `targetColumns` (no projection sub-axis)

`JooqRecord.keyDecode` becomes `List<RecordKeyDecode> keyDecodes` (multiple
`@nodeId` fields per record); the at-least-one-binding floor updates to
`columnBindings.isEmpty() && keyDecodes.isEmpty()`. `RecordKeyDecode` keeps its
decode spine (`sdlFieldName`, `encoderClass`, `typeId`), gains a `nonNull` flag,
and generalizes R311's `keyColumns` to `targetColumns`:

```java
record RecordKeyDecode(String sdlFieldName, ClassName encoderClass, String typeId,
                       List<ColumnRef> targetColumns, boolean nonNull) { ... }
```

`targetColumns` is the resolved list of columns **on this record** that the
decoded values load into, in node-key (decode) order. The same-table identity
case (R311) resolves them to the record's own key columns; the cross-table FK
case (R315) resolves them to the FK's child columns (via **D3**). That
identity-vs-FK distinction lives **only in the resolver**; the carrier holds the
result.

There is deliberately **no** `KeyProjection` (`Identity` / `FkChild`) sub-axis.
Record population only loads decoded values into columns via `fromArray`; it
never emits `.onKey(...)`, so an `FkChild` arm has no use for an FK constant and
would carry only a column list, making it structurally identical to `Identity`.
Nothing downstream branches on which arm it is: the emitter loads `targetColumns`
the same way for both, D4's throw-vs-skip splits on `nonNull` (not on the arm),
and R322's overlap check reads the columns (not the arm). A sealed split would
therefore name a provenance fact no consumer reads. This is *not* the
`JoinSlot.FkSlot(sourceSide, targetSide)` situation that justifies a typed pair:
that one disambiguates a directional "which end of the FK" question with two live
readers, whereas `targetColumns` has a single meaning, "load targets on this
record," however it was computed.

`nonNull` (the SDL field's `ID!`-vs-`ID`) drives throw-on-mismatch vs
conditional-set, read off one data field. The `@service` method owns the
insert/update (Graphitron only populates the record), so the framework does
**not** force a same-table identity to be non-null: a nullable (`ID`) identity is
a legitimate service-side upsert input (omitted → unset PK → the service inserts)
and is handled exactly like a nullable FK reference. This deliberately changes
R311's same-table identity, which formerly always threw on null whether `ID!` or
`ID`; see **D4** for the behavior change and its mitigations.

### D2. Convergence by rejection: keep R311's trigger, add a `@table`/`TableInputType` reject arm (requirement #3)

The `@table`-absent path is unchanged: `InputBeanResolver.enrich` keeps R311's
trigger ("SDL type classified as `JooqTableRecordInputType`"), which is itself
type-derived (the classification is produced by matching the param record class),
so this honors "derive from types, not directives" without a trigger rewrite. The
record's `TableRef` continues to come off that classification, exactly as R311
reads it (`ServiceCatalog.resolveTableByRecordClass` /
`JooqCatalog.findTableByRecordClass` exist should a record-class-first derivation
ever be wanted).

Convergence is achieved by **rejecting** the `@table`-present case rather than
routing it onto the jOOQ path. Add a `TableRecord`-specific test (the
`elementClass` is already loaded; sit it alongside `isJooqRecord`). Note it must
be **narrower** than `isJooqRecord`: a non-table `Record` implements
`org.jooq.Record` but not `org.jooq.TableRecord` and has no `TableRef`, so it
keeps falling through to the bean path rather than NPE-ing on a null table. When
the param's Java element type is a `TableRecord` **and** the SDL input classified
as `TableInputType` (the `@table`-present shape), emit the requirement-#3
`UnclassifiedField` ("drop `@table`…") instead of the bean path's misleading
"has no fields matching."

That is the whole of D2: no trigger rewrite, no demotion of
`JooqTableRecordInputType` (its R94 / LSP roles are untouched), and no need to
read or cross-check the `TableInputType`'s already-resolved fields, because the
`@table`-present input is rejected before any of that would matter. Lower blast
radius than the originally-planned trigger move, and it removes the
two-readers-of-one-directive divergence (requirement #3) at the root.

### D3. FK pairing: extract and reuse `synthesizeFkJoin`'s slot core; reconcile by column identity

Extract the FK-orientation-and-pairing body of `BuildContext.synthesizeFkJoin`
(`:1133-1155`) into `List<JoinSlot.FkSlot> resolveFkSlots(ForeignKey<?,?> f,
String sourceSqlName, boolean selfRefFkOnSource)`. `synthesizeFkJoin` keeps
wrapping it with alias / targetTable / whereFilter for the join case; the new
record-population resolver calls the *same* core with the record table as
source, getting `FkSlot(recordChildCol, nodeParentCol)` pairs. This inherits the
bug-fix `synthesizeFkJoin` documents (parent columns from
`ForeignKey.getKeyFields()`, **not** `getKey().getFields()`, which legacy
`NodeIdReferenceHelpers` reached via `getInverseKey().getFields()` and which
mis-pairs composite FKs whose referenced-column order differs from the parent PK
order) and keeps one site owning FK orientation. We do *not* call
`synthesizeFkJoin` whole (its alias / `FkJoin` wrapper is join-emission machinery
record population does not need) and do *not* re-port legacy's pairing (it would
re-introduce the fixed bug).

**Reconcile by column identity, not position.** `resolveFkSlots` returns the
slots in the FK's own declaration order, but the decoded values arrive in
*node-key* order (the order `resolveNodeIdRecordDecode` lists the node's key
columns, which `decodeValues` follows). Those two orders can differ, e.g. the
`reordered_fk_child` fixture's FK references `(pk_b, pk_c, pk_a)` while the
node's PK is `(pk_a, pk_b, pk_c)`. So the record-population resolver, for each
node key column in node-key order, finds the slot whose **parent** side
(`nodeParentCol`) equals that node key column and takes its **child** side
(`recordChildCol`), producing `targetColumns` aligned with the decode order. A
naive zip of node-key order against the slots' child columns would mis-assign
every value on a reordered FK. This identity match is the surviving form of
legacy's case-insensitive name map (`NodeIdReferenceHelpers.java:117-119`),
layered on the now-correctly-oriented slots; it is a *second* ordering concern,
distinct from the `getKeyFields()` slot-internal pairing that `resolveFkSlots`
already fixes. `SynthesizeFkJoinReorderedKeysTest` pins slot orientation for both
consumers; the §4 pipeline test pins the decode-order reconciliation (see Tests).

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
throws). Which columns the values land on (identity vs FK child) is settled by
the resolver into `targetColumns`; the decode's `nonNull` alone decides *whether*
a missing value throws.

This change *also alters R311's shipped same-table behavior* on two counts: a
`fromArray` batch becomes conditional loads, **and** a nullable (`ID`) same-table
identity moves from always-throw-on-null to skip-when-omitted (the upsert input
chartered in D1). Folded into R315 deliberately (requirement #5 charters it; the
nullable FK-reference and nullable-identity cases need conditional-set to be
correct). The motivating consumer's keys are all `ID!`/`!`, so the model reshape
plus FK resolution already close the literal breakage; D4 is therefore a
completeness generalization rather than a fix the four mutations require, folded
with three required mitigations: (1) the R315 changelog entry states *both* R311 behavior changes
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
  `RecordKeyDecode` to `{sdlFieldName, encoderClass, typeId, List<ColumnRef>
  targetColumns, boolean nonNull}` (R311's `keyColumns` renamed and generalized
  to `targetColumns`, plus `nonNull`); keep the non-empty `targetColumns`
  compact-constructor check. No `KeyProjection` type. (D1)
- **`BuildContext.java`** — extract `resolveFkSlots(...)` from
  `synthesizeFkJoin`; add a record-population FK resolver: given the record
  `TableRef`, the field's `@nodeId` typeName, and any `@reference(key:)`,
  resolve the FK (explicit `key` via `findForeignKey`; else
  `findUniqueFkToTable(recordTable, nodeTable)`; zero/multi → `fkCountMessage`),
  then for each node key column (from `resolveNodeIdRecordDecode`, node-key
  order) find the slot whose parent side equals it and take its child side,
  yielding the FK child columns reconciled to decode order (the identity match,
  **not** a positional zip; D3). Return those `targetColumns`, or a rejection
  (a node key column not covered by the chosen FK). (D3)
- **`InputBeanResolver.java`** — keep R311's `enrich` trigger
  (`JooqTableRecordInputType`); add an `isTableRecord` test (narrower than
  `isJooqRecord`) and, when the param's Java element type is a `TableRecord` but
  the SDL input classified as `TableInputType` (the `@table`-present shape), emit
  the requirement-#3 reject `UnclassifiedField` (D2). In `buildJooqRecord` /
  `buildRecordKeyDecode`: drop the single-`@nodeId` gate (`:266`) and the
  same-record gate (`:325`); per `@nodeId` field, branch: an explicit `@reference`
  forces the FK branch (a self-FK resolution, record-table == node-table, is
  rejected as out of scope); else node-table == record-table → resolve the
  record's own key columns (R311) vs ≠ → call the new
  FK resolver for the reconciled child columns; build each `RecordKeyDecode` with
  the resolved `targetColumns` and `nonNull` from the SDL field; collect a
  `List<RecordKeyDecode>`.
- **`generators/JooqRecordInstantiationEmitter.java`** — emit `containsKey`-
  guarded conditional loads for `@field` columns and each `RecordKeyDecode`,
  loading the decoded values into the decode's `targetColumns` (no
  `KeyProjection` switch; the load is uniform) and honoring `nonNull`. Retire the
  stale "two disjoint `fromArray` groups" javadoc and the `RecordKeyDecode`
  "always throws … whether `ID!` or `ID`" javadoc. (D4)
- **Test fixtures (`graphitron-sakila-db/init.sql`)** — add a
  `public.film_endorsement` table (`endorsement_id` serial PK, `endorsed_film
  int NOT NULL REFERENCES film(film_id)`, `note varchar`) whose FK child column
  name (`endorsed_film`) differs from the referenced parent key (`film_id`): the
  positive renamed-FK pin a name-match shortcut cannot satisfy. Bump the
  `schemaVersion` property so jOOQ regenerates. Add a `TestServiceStub` method
  taking a `FilmEndorsementRecord` (pipeline) plus a `graphitron-sakila-service`
  method and `graphitron-sakila-example` SDL/seed (execution). Reuse the existing
  `idreffixture.studierett` (two FKs to `studieprogram`) and
  `nodeidfixture.reordered_fk_child` schemas; no new tables for §4/§6 (see Tests).
- **Docs** — note FK-reference `@nodeId` on `@service` jOOQ-record params in the
  `@nodeId` / record-input documentation (no roadmap markers in user-facing
  prose).

## Tests

Mirror `JooqRecordServiceParamPipelineTest` (the R311 template). The `film_actor`
junction (single FKs to `film` and `actor`, `FilmActorRecord`,
`TestServiceStub.modifyFilmActorRecord`) stays as a smoke fixture, but note its
PK columns **are** its FK columns, so it cannot discriminate an identity decode
from an FK-child decode, nor FK-constraint resolution from name-match. The
discriminating pins use renamed-FK fixtures (see Implementation / Test fixtures):

- **Pipeline — pure FK references (motivating shape, smoke):** input `{ filmId:
  ID! @nodeId(typeName: "Film"), actorId: ID! @nodeId(typeName: "Actor") }` →
  `FilmActorRecord`. Assert two `keyDecodes` resolving to `film_actor.film_id` /
  `film_actor.actor_id` (FK deduced), `createFilmActorRecord` emitted, field not
  `UnclassifiedField`. Smoke only: `film_actor`'s geometry makes these coincide
  with the PK, so it does not pin the FK mechanism.
- **Pipeline — FK-constraint not name-match (the real pin):** input `{ filmId:
  ID! @nodeId(typeName: "Film"), note: String @field(name: "note") }` →
  `FilmEndorsementRecord`. Assert the `filmId` decode's `targetColumns ==
  [endorsed_film]` (the renamed FK child column), **not** `[film_id]` (which a
  name-match shortcut would produce or fail on); one `ColumnBinding` for `note`.
- **Pipeline — convergence by rejection (requirement #3):** the motivating input
  *with* `@table(name: "film_actor")` (so the SDL classifies as `TableInputType`)
  rejects with the requirement-#3 `UnclassifiedField` ("drop `@table`…"), not the
  bean path's "has no fields matching"; the same input *without* `@table`
  classifies to the `JooqRecord` carrier. Convergence here means honest behavior
  either way, not a silent route to two different errors.
- **Pipeline — mixed identity + FK reference + plain `@field`:** a record
  carrying its own `@nodeId` identity, an FK-reference `@nodeId`, and a `@field`
  column. Assert two `keyDecodes`, one whose `targetColumns` are the record's own
  key and one whose `targetColumns` are the FK child columns, plus one
  `ColumnBinding`.
- **Pipeline — composite / reordered FK pin (decode-order reconciliation):**
  using `nodeidfixture.reordered_fk_child` (FK `(fk_b, fk_c, fk_a)` references
  `reordered_pk_parent (pk_b, pk_c, pk_a)`, whose PK is `(pk_a, pk_b, pk_c)`),
  assert the decode's `targetColumns == [fk_a, fk_b, fk_c]`, i.e. aligned with
  node-key / decode order `(pk_a, pk_b, pk_c)`, **not** the FK declaration order
  `[fk_b, fk_c, fk_a]`. A positional zip would land here; this pins the
  identity-match reconciliation (D3), distinct from
  `SynthesizeFkJoinReorderedKeysTest`'s slot-orientation pin.
- **Pipeline — rejections (all `UnclassifiedField`):** zero/multi FK between
  record and node table with no `@reference(key:)`; a node key column not covered
  by the chosen FK; an explicit `@reference` on a same-table `@nodeId` (self-FK
  request, out of scope) rejecting rather than silently doing identity; (unchanged
  R311) `@field`→no column, cardinality parity,
  missing `typeName`. "More than one `@nodeId`" is **no longer** a rejection (the
  `:266` gate is gone): the former `twoNodeIdFields_reject` becomes a positive
  classification test (two `keyDecodes` resolve; their overlap value-agreement is
  R322's concern, not asserted here).
- **Pipeline — explicit `@reference(key:)` disambiguation (positive):** using
  `idreffixture.studierett`, which already carries **two** FKs to `studieprogram`
  (`studieprogram_id` and the renamed `registrar_studieprogram`), assert
  `@reference(key: "<fkName>")` selects the named FK so the decode's
  `targetColumns` are that FK's child columns, while omitting the key on the same
  shape gives the ambiguous-FK rejection above. Pins the directive-arg →
  selected-FK binding the deduced-FK tests cannot reach.
- **Execution (sakila):** a `@service` mutation taking a `FilmEndorsementRecord`
  built from `{filmId, note}` inserts a `film_endorsement` row; assert the
  persisted `endorsed_film` equals the decoded `film_id` (end-to-end proof the
  value lands on the renamed FK child column, not a same-named PK column). Plus
  the **null-semantics** cases (D4), the only tier that observes `changed=false`
  exclusion: a nullable plain column omitted vs explicit-null vs set; and, on a
  single-PK table with a DB-assignable PK (e.g. `FilmRecord`), a nullable
  same-table identity (`filmId: ID @nodeId`) omitted (PK left unset → the service
  inserts) vs set (the update path), each yielding the correct persisted column
  set / value; and a nullable FK reference (`ID @nodeId` resolving to an FK child
  column) omitted (child column left unwritten, `changed=false`) vs set (decoded
  into the child column), the most direct pin of the D4 generalization.
- **Compile (graphitron-sakila-example):** carry the FK-reference shape so the
  generated `*Fetchers` compile against real jOOQ at Java 17.

## Validator-mirror checklist

Each removed gate is either replaced by a build-time `UnclassifiedField` or has
its hazard explicitly relocated (the `:266` value hazard to R322's runtime
check); new rejections route `InputBeanResolver.Result.Failed` →
`ServiceDirectiveResolver.Resolved.Rejected`, the path R311 already rides:

- Removed `:266` (more than one `@nodeId`) → no classification replacement;
  multiple `@nodeId` is now legal (each resolves independently). The
  overlapping-load-column *value* hazard is **runtime**, deferred to **R322**,
  not a build-time gate; the former `twoNodeIdFields_reject` flips to a positive
  classification test.
- Removed `:325` (same-record gate) → the cross-table case now *resolves* (to FK
  child columns); its failure modes are newly guarded: (a) zero/multi FK without
  `@reference(key:)`; (b) a node key column not covered by the FK.
- New: `@table` present on a `@service` jOOQ-record param (SDL classifies as
  `TableInputType`) → reject with the requirement-#3 message (replacing the bean
  path's misleading "has no fields matching").
- New: an explicit `@reference` on a same-table `@nodeId` (resolves to a self-FK)
  → reject as the out-of-scope self-reference case, instead of silently taking the
  identity branch and ignoring the authored `@reference`.
- Unchanged: `@field`→no column; cardinality parity; `@nodeId` without
  `typeName`.
- Structural backstop: `RecordKeyDecode` rejects an empty `targetColumns` in its
  compact constructor.

## Sequencing and scope note

Single cycle; the core code changes land together (the model reshape forces the
resolver and emitter, and the `@table` reject arm rides the same `enrich`
entry). R322 (overlap value-agreement) is the deferred follow-on, not part of
this cycle. The remaining scope fork is D4 (fold the
`fromArray`→conditional-load change in vs carve it into a precursor); recommended
folded with the three mitigations above, and the Spec → Ready reviewer may still
split it for cleaner attribution.
