---
id: R501
title: "@pivot: discriminator-keyed aggregate projections"
status: Spec
bucket: feature
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-17
last-updated: 2026-07-17
---

# @pivot: discriminator-keyed aggregate projections

Real subgraphs model a multi-valued attribute (translations being the reported case, but the shape
is general) as a narrow `(owner-key…, discriminator, value)` table, then hand-write a service that
pivots the rows into a wide record: one filtered aggregate per discriminator value. The reported
instance is opptak's `OversatteTeksterService`, a dozen near-identical methods, each
`max(<value>).filterWhere(<discriminator>.eq(<code>))` grouped by the owner key and `fetchMap`'d back
to the parent. This is exactly the repetitive, error-prone data-plumbing the generator exists to
absorb, but no directive expresses a row-to-column pivot today. Inline mapping binds each output
field to a distinct column of one joined row; a pivot binds every field to the *same* value column
filtered by a distinct discriminator value, and reuses the output type across different value
columns. Those are two axes inline mapping does not have, which is why the pattern currently has to
drop to a service.

This item introduces `@pivot(on:, value:)`, a field-level directive that turns a child field into a
discriminator-keyed aggregate projection, generating the pivot the service writes by hand.

## Design

The pattern decomposes into five orthogonal facts, four of which existing machinery already carries.
Each is stated once, at its grain:

| Fact | Grain | Where it is stated |
|---|---|---|
| Join owner → attribute table | per usage (the FK) | `@reference(path:)` (exists; composite-FK-aware) |
| Delivery: inline vs batched | per usage | inline default; `@splitQuery` opts in (exists) |
| Discriminator column (`sprakkode`) | per attribute table | `@pivot(on:)` (new) |
| Value column (`navn` / `beskrivelse`) | per usage | `@pivot(value:)` (new) |
| Field → discriminator value (`nn`→`nno`) | intrinsic to the reused type | `@field(name:)` on the projection type's fields (exists as a concept) |

The return type becomes a **pivot projection type**: a non-`@table` type whose fields are attribute
slots, each carrying `@field(name:)` naming the discriminator value it selects. This reuses
`@field(name:)`'s existing `ENUM_VALUE` semantics verbatim ("the database string this value maps to",
lifted at classify time into `EnumValueSpec.runtimeValue` by `TypeBuilder`); the projection field is
a new site for the same axis, disambiguated by the return type being a pivot projection. No type-level
marker is needed: the type's pivot-ness is inferred from the `@pivot` field consuming it, consistent
with how a type's backing is already inferred from its producer (the `@record` deprecation rationale).

**Nullability.** Which discriminator values are actually populated is data, not schema:
`max(value).filterWhere(disc.eq('sme'))` returns SQL null whenever no row carries that value, and the
generator cannot know at build time whether any does. So every projection slot is inherently
best-effort. A **non-null slot is a validate-time rejection** (typed, LSP-coded), naming the slot and
the reason. The dual holds and is worth stating: the projection *field itself* may be non-null, since
a correlated aggregate over an empty set still returns one row (of nulls), never a null record; only
its slots must be nullable.

**Delivery.** Inline is the default and the better runtime shape: the projection is a single-valued
field, so it folds into the parent query as a correlated aggregate subselect, one round-trip, no
DataLoader, no N+1 by construction, and no `GROUP BY` (the aggregate over the correlated set collapses
to one row on its own). `@splitQuery` opts into the existing batched DataLoader seam
(`BatchKeyField` → `SplitRowsMethodEmitter`, positional `__idx__` scatter), which re-introduces
`GROUP BY __idx__` over the batch; it earns its place only when the parent is not SQL-backed or the
field is costly and rarely selected.

**Selection-set gating.** The projection obeys the same discipline as `Type.$fields(selectionSet,
alias, env)`: walk the selection set on the projection type and emit one
`max(<value>).filterWhere(<disc>.eq(<mappedValue>)).as(<alias>)` column per *requested* slot, using
the `@field(name:)` → value map. A `{ navn { nn en } }` selection projects two filtered aggregates,
not four.

**No generated record.** The subselect returns a jOOQ `Record`; the slot fields resolve through the
existing record-extraction datafetchers. No output type is generated and no
`.convertFrom(mapping(...))` is emitted. In `(source, operation, target)` terms this is
`source = Child(Table)` (or the parent's own scope, inline), a new pivot `operation`, and
`target = Single(Record)` where the record is graphitron-built, the "a target `Column` can be the
source `Record` for further child fields" shape R222 already anticipates.

**Composite keys are supported from the start**, not deferred. Key arity is orthogonal to the pivot:
the correlation is an AND-chain over the owner-key columns (inline) or a `Row<N>` `VALUES` tuple with a
multi-column join (split), both already arity-generic in `@reference` / `SourceKey`. The projection
never touches the key. So opptak's 3-column `beskrivelseForKravelement` and single-column `navnForLand`
generate through identical code, just a longer AND-chain.

## `@pivot` reference documentation (first-client draft)

Ships to `docs/manual/reference/directives/pivot.adoc` when the feature lands. Drafted here as the
first client of the design: if it does not read simply, the design is wrong.

---

### `@pivot`

Turns a single-valued child field into a **discriminator-keyed aggregate projection**: one output
field per discriminator value, each holding an aggregate of a value column filtered to that value.
This generates, declaratively, the row-to-column pivot that would otherwise be a hand-written service.

The field's return type is a **pivot projection type**: a type with no `@table`, whose every field
carries `@field(name:)` naming the discriminator value that field selects (the same meaning
`@field(name:)` has on an enum value). The field reaches the attribute table with `@reference`; it
delivers inline by default, or batched if `@splitQuery` is also present.

#### SDL signature

```graphql
directive @pivot(on: String!, value: String!) on FIELD_DEFINITION
```

#### Parameters

| Name | Type | Description |
|---|---|---|
| `on` | `String!` | The discriminator column on the referenced attribute table. Each projection field's `@field(name:)` is matched against this column's value. |
| `value` | `String!` | The column on the referenced attribute table whose value is aggregated per discriminator value. |

#### Example 1: translations

An attribute table `land_sprak(landkode, sprakkode, navn)` holds a country's name once per language.
`AdmissioLand.navn` pivots those rows into one field per language:

```graphql
type OversatteTekster {
    nn: String @field(name: "nno")
    nb: String @field(name: "nob")
    se: String @field(name: "sme")
    en: String @field(name: "eng")
}

type AdmissioLand @key(fields: "landkode") @table(name: "land") {
    landkode: String! @shareable
    navn: OversatteTekster
        @reference(path: [{table: "land_sprak"}])
        @pivot(on: "sprakkode", value: "navn")
}
```

`OversatteTekster` is reused across every translated field; the value column varies per usage. A
sibling field over `kravelement_sprak.beskrivelse`, keyed on a three-column FK, reuses the identical
type and directive:

```graphql
beskrivelse: OversatteTekster
    @reference(path: [{table: "kravelement_sprak"}])
    @pivot(on: "sprakkode", value: "beskrivelse")
```

#### Example 2: prices by currency

The shape is not specific to i18n. Any narrow `(owner, discriminator, value)` attribute table
pivots the same way. Given `product_price(product_id, currency_code, amount)`:

```graphql
type PriceByCurrency {
    nok: BigDecimal @field(name: "NOK")
    usd: BigDecimal @field(name: "USD")
    eur: BigDecimal @field(name: "EUR")
}

type Product @table(name: "product") {
    productId: Int! @field(name: "product_id")
    listPrice: PriceByCurrency
        @reference(path: [{table: "product_price"}])
        @pivot(on: "currency_code", value: "amount")
}
```

`PriceByCurrency` is reused for any price column; the value column (`amount`, a discounted-price
column, etc.) is the per-field choice, exactly as with translations. The value type is arbitrary
(here `BigDecimal`), not just `String`.

#### Constraints

* The return type must be a pivot projection type: no `@table`, and every field carries
  `@field(name:)`. A field without it, or with a value that cannot be a discriminator string, fails
  the build.
* Every field on the projection type must be **nullable**. A non-null slot fails the build: pivot
  slots are filtered aggregates that are null when no row carries the value.
* A pivot projection type must be reached only through `@pivot` fields. Using it as an ordinary
  object type elsewhere fails the build, since its `@field(name:)` values are discriminator values,
  not column names.
* `on` and `value` must both resolve to columns on the `@reference` terminus (attribute) table. The
  build fails, naming the unresolved column, if either does not.
* The field must be single-valued (one projection record per parent). A list return type is rejected.
* Inline delivery is the default; add `@splitQuery` for batched delivery when the parent is not
  SQL-backed or the field is expensive and rarely selected.

#### See also

* xref:field.adoc[`@field`] names the discriminator value on each projection-type field (the same
  role it plays on an enum value).
* xref:reference.adoc[`@reference`] establishes the join to the attribute table, including
  composite-FK and multi-hop paths.
* xref:splitQuery.adoc[`@splitQuery`] switches delivery from inline to batched.

---

## Model

**New type variant `GraphitronType.PivotProjection`.** A record-backed output type that, unlike the
existing `ResultType` arms (`JavaRecordType`, `PojoResultType`, `JooqRecordType`,
`JooqTableRecordType`), is *not* reflected from a Java class: its runtime carrier is an anonymous jOOQ
`Record` produced by the pivot subselect, and it has no `fqClassName`. It therefore cannot satisfy
`ResultType`'s contract and lands as a direct `GraphitronType` sibling, not a `ResultType` arm. It
carries its ordered slots, each slot being `(sdlName, outputAlias, discriminatorValue)`. The
`discriminatorValue` is lifted from the slot field's `@field(name:)` through the *same* helper that
lifts `EnumValueSpec.runtimeValue` (one shared function, not a parallel implementation; SDL name is the
fallback when the directive is absent). `TypeBuilder` classifies a type as `PivotProjection` when it is
consumed by a `@pivot` field; the type carries no directive of its own, mirroring how a type's backing
is inferred from its producer. The per-slot scalar type is *not* stored; it is derived from the single
value `ColumnRef`, so there is no drift copy.

*Emission surface.* `PivotProjection` is a real GraphQL type clients query, so like `NestingType` it
implements `EmitsPerTypeFile` and appears in `schema.types()`: a schema type and per-slot datafetchers
are emitted, only the Java DTO and `.convertFrom(...)` are not. The new field leaves (below) land in
the `TypeFetcherGenerator` dispatch partition (`IMPLEMENTED_LEAVES`), so
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces coverage; the slot
`PropertyField`s already sit in that partition.

**New producer leaves, split on the delivery axis.** Delivery (inline vs batched) is a real orthogonal
axis the model already splits for tables (`ChildField.TableField`, inline, does *not* implement
`BatchKeyField`; `ChildField.BatchedTableField` does and carries `SourceKey` / `LoaderRegistration` /
`KeyLift`). The pivot mirrors that split rather than fusing it into one nullable-bag leaf:

- `ChildField.PivotField` (inline): the correlated aggregate subselect; no `BatchKeyField`.
- `ChildField.BatchedPivotField` (`@splitQuery`): implements `BatchKeyField`, carrying the
  `SourceKey` / `LoaderRegistration` triple from `FieldBuilder.deriveSplitQuerySource`.

Both compose a shared `PivotSpec` sub-record carrying the facts common to both deliveries: the resolved
`@reference` join path (reuse `ctx.parsePath`, so composite-FK and multi-hop come free), the
discriminator `ColumnRef` (from `on:`), the value `ColumnRef` (from `value:`), and the projection type.
Both report `SourceShape.Record` for their children. This keeps the delivery fact forked on leaf
identity, never re-derived from a null check.

**Slot fields reuse `ChildField.PropertyField`.** Each projection slot is a `PropertyField` with
`columnName = outputAlias` and `column = null` (no `TableRef`), which routes through the existing
by-name record read. No new slot leaf is needed.

## Implementation

Anchored on symbols; line numbers are omitted deliberately (they drift).

- **Directive.** Declare `directive @pivot(on: String!, value: String!) on FIELD_DEFINITION` in
  `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`.
- **Field classification.** `FieldBuilder` classifies a `@pivot`-bearing field into `PivotField`
  (inline) or `BatchedPivotField` (`@splitQuery` present): parse the `@reference` path, resolve
  `on`/`value` to `ColumnRef` on the terminus into a shared `PivotSpec`, and for the batched leaf derive
  the `SourceKey`/`LoaderRegistration` triple via the existing `deriveSplitQuerySource` path.
- **Type classification.** `TypeBuilder` produces `GraphitronType.PivotProjection` for the return type,
  lifting each slot's `@field(name:)` into its discriminator value (reuse the `EnumValueSpec`
  lift), and recording each slot's output alias.
- **Projection emission.** A new emitter builds the selection-set-gated correlated aggregate subselect:
  for each *selected* slot, `DSL.max(value).filterWhere(disc.eq(DSL.inline(discriminatorValue))).as(alias)`,
  wrapped in `DSL.row(...)` and delivered as a scalar-record subselect field
  (`field(select(row(...)).from(terminus).where(<correlation>))`). The
  `max(...).filterWhere(...)` column form is new with this item; nothing in the generator emits a
  filtered aggregate today. The `@asFacet` facets emission (`ConnectionHelperClassGenerator`, a
  UNION ALL of per-facet `GROUP BY` arms) is precedent only for the best-effort-aggregate
  nullability stance, not a reusable column form. Inline needs no `GROUP BY`. The `@splitQuery` path routes through `SplitRowsMethodEmitter` with the pivot
  projection as the select list and `GROUP BY __idx__` over the batch, scattered single-per-key via the
  existing `scatterSingleByIdx`.
- **Slot extraction.** Extend the record-by-name arm guard in `FetcherEmitter.propertyOrRecordBinding`
  (and its Outcome sibling `inlineSuccessRead`) to accept `GraphitronType.PivotProjection` alongside
  `JooqRecordType` / `JooqTableRecordType`, so a slot emits
  `return ((Record) source).get(DSL.field("<alias>"));`. This is the one edit to an existing emitter
  arm; everything else is additive. The fact widened here ("children read by name off a generic jOOQ
  `Record`") is now uniformly true across three `GraphitronType` variants; if a second read site tests
  the same disjunction, reify it as a "reads-by-name-off-`Record`" capability the record-backed variants
  implement and have the emitters consult, rather than growing the `instanceof` OR-list at each site.
- **Selection gating** reads the pivot type's slots off `env.getSelectionSet()`, the same source
  `Type.$fields(...)` already consults.

## Validation

Every classifier decision that implies a generator branch fails at validate time if violated (the
"validator mirrors classifier invariants" rule), each a typed `Rejection.AuthorError` with a stable
LSP code:

- A non-null slot on a `PivotProjection` type is rejected, naming the slot and the reason (filtered
  aggregates are null when no row carries the value). This mirrors the best-effort-aggregate nullability
  the `@asFacet` `FacetValueType` / `FacetsType` already carry.
- **Ambiguity enforcer.** A `PivotProjection`-classified type consumed by any non-`@pivot` producer is
  rejected. `@field(name:)` on that type's fields means "discriminator value" only under pivot
  consumption; reached as an ordinary nesting/record type the same directive would mean "column name."
  Since the pivot-ness is inferred non-locally (from the consuming `@pivot` field, not the slot's own
  site), the conflict has no other enforcer, so the build must fail on the dual use rather than pick a
  meaning silently.
- A slot without a resolvable discriminator value, or a `PivotProjection` type field that is itself an
  object/list rather than a scalar, is rejected.
- `on` / `value` that do not resolve to columns on the `@reference` terminus are rejected, naming the
  unresolved column.
- All slots must share one scalar type (they read the same value column); a divergent slot type is
  rejected.
- A `@pivot` field with a list return type, or with no establishable join to an attribute table, is
  rejected.
- `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` covers the new `PivotField`
  leaf; `SealedHierarchyDocCoverageTest` covers the new `PivotProjection` variant and any new rejection
  arm.

## Tests

Per the test-tier discipline (behaviour pinned at the pipeline tier and above; no code-string
assertions on method bodies):

- **Pipeline tier.** SDL → classified model → generated `TypeSpec`: a `@pivot` field classifies as
  `PivotField`, its return type as `PivotProjection` with the expected slot→discriminator map; a
  selection subset narrows the projected slot set; a composite-key `@reference` produces the AND-chained
  correlation. Inline and `@splitQuery` variants both classify.
- **Compilation tier.** `graphitron-sakila-example` fixtures for both examples (a translation pivot and
  a numeric/currency pivot), inline and split, single and composite key, compile under `<release>17>`.
- **Execution tier (PostgreSQL).** Add attribute tables to `graphitron-sakila-db` (a single-key
  translation table over an existing entity, a composite-key one, and a numeric currency-price table),
  seed rows that leave at least one discriminator value unpopulated, and assert: pivoted values land on
  the right slots; an unpopulated slot returns null; the composite-key pivot keys correctly; inline and
  split return identical results (the existing inline/split parity convention).
- **Validation tier.** Each rejection above has a negative fixture asserting the build fails with the
  typed error.

## Out of scope

- Aggregate functions other than the value-collapsing `max` (the value is unique per
  `(owner, discriminator)`, so `max` just returns it). Custom aggregates are a later item.
- A projection whose slots draw from *different* value columns (one value column per projection in v1).
- Non-equality discriminators (ranges, `IN`-sets per slot).
- Writing through a pivot (mutations); `@pivot` is read-only.

## Roadmap entries

Single slice: inline + `@splitQuery`, single + composite keys, in one item. The additive-then-cutover
discipline applies only to the one existing-emitter edit (the `propertyOrRecordBinding` guard); the new
type variant, leaf, and emitter are purely additive.

