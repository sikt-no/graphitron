---
id: R315
title: "Bind FK-reference @nodeId onto jOOQ-record @service params (port legacy @reference FK resolution; generalize R311)"
status: Backlog
bucket: feature
priority: 4
theme: nodeid
depends-on: []
created: 2026-06-16
last-updated: 2026-06-16
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
   `@nodeId`-decoded columns.
6. **Model shape.** Generalize R311's `CallSiteExtraction.JooqRecord`: drop
   the single-`@nodeId` gate (`:266`) and the same-record gate (`:325`);
   `keyDecode` goes from `Optional<RecordKeyDecode>` to
   `List<RecordKeyDecode>`; each decode carries its resolved FK child-column
   target(s). Factor the `@nodeId` decode-resolution core so a future
   pojo (member-axis) item reuses it verbatim.

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

## Open questions for Spec stage

- Reuse R311's `RecordKeyDecode` carrier (extended with the FK child-column
  target) vs. a new sibling carrier for the reference case.
- Where FK resolution lives: a rewrite analogue of `NodeIdReferenceHelpers`
  reading the jOOQ catalog (`ctx.catalog`), and whether the catalog model
  already exposes FK parent/child column pairings or needs a lift.
- Exact rewrite phrasing of the two ported rejection messages, consistent
  with the existing `UnclassifiedField` + candidate-hint style.
- Whether `@reference` is already parsed/modelled in the rewrite at all, or
  needs classifier plumbing (it is declared in `directives.graphqls` with
  `ReferenceElement{table, key, condition}`; confirm the classifier path).
