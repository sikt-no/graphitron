---
id: R501
title: "@pivot: discriminator-keyed aggregate projections"
status: Spec
bucket: feature
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-17
last-updated: 2026-07-19
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

The pattern decomposes into five orthogonal facts. Only two are new (`@pivot`'s `on:` and `value:`);
the other three reuse existing machinery. Each is stated once, at its grain:

| Fact | Grain | Where it is stated |
|---|---|---|
| Join owner → attribute table | per usage (the FK) | `@reference(path:)` (exists; composite-FK-aware) |
| Delivery: inline vs batched | per usage | inline default; `@splitQuery` opts in (exists) |
| Discriminator column (`sprakkode`) | per attribute table | `@pivot(on:)` (new) |
| Value column (`navn` / `beskrivelse`) | per usage | `@pivot(value:)` (new) |
| Slot → discriminator token (`nn`→`nno`) | the code vocabulary | a text-mapped enum (`@field(name:)` on its *values*), named by `@pivot(vocabulary:)`; identity when omitted |

The return type is a **plain, directive-free output type**: its fields are attribute slots named for
the languages/codes, carrying no pivot markers of their own. Nothing on the type says "pivot"; the
binding lives entirely on the consuming `@pivot` field. This is the key move away from an earlier
draft that put `@field(name:)` on the slots to name discriminator values. That overloaded
`@field(name:)` (which means "column" on an object field) with a second, consumer-imported meaning,
forcing a non-local "reached only through `@pivot`" enforcer and making the type all-or-nothing pivot.
Removing it makes the projection type context-free and freely **reusable in both modes**, including
within one schema: reached by a `@pivot` field it is a pivot record; reached by a plain field on a
`@table` parent it is an ordinary nesting object. This works because pivot-ness never becomes a fact
of the type at any layer. In the model, the return type registers as the ordinary
`GraphitronType.NestingType` whichever consumer reaches it, so mixed consumption is two registrations
of the same value, an idempotent repeat in `TypeRegistry.register`; every pivot fact lives on the
consuming field. At runtime, graphql-java wires one datafetcher per `(type, field)` coordinate, and
one datafetcher genuinely serves both sources: nested children already read by column name on the
generic jOOQ `Record` precisely so the same nested type can be reached from multiple parents
(`ChildField.NestingField`'s documented contract, pinned by
`GraphitronSchemaBuilderTest.SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE`), and the pivot subselect
aliases each aggregate by the same derived read name, so the by-name read cannot tell the sources
apart (see Model).

**The slot → discriminator token map lives where `@field(name:)` is already canonical: on the values
of a text-mapped enum.** When the GraphQL slot names differ from the database tokens (`nn` vs `nno`),
the pivot names a vocabulary enum with `@pivot(vocabulary: "Sprak")`; each selected slot's SDL name is
matched to a `Sprak` value, whose `@field(name:)` gives the token (reusing the existing
`buildTextEnumMapping` / `EnumValueSpec.runtimeValue` machinery at its unambiguous `ENUM_VALUE` site,
not overloading the object-field site). When slot names already equal the tokens, `vocabulary:` is
omitted and the mapping is identity. The vocabulary is per-`@pivot`, which is the honest grain: two
attribute tables could use different code vocabularies for the same GraphQL type. `Sprak` is an
ordinary enum, independently reusable as a real field type.

**Nullability.** Which discriminator values are actually populated is data, not schema:
`max(value).filterWhere(disc.eq('sme'))` returns SQL null whenever no row carries that value, and the
generator cannot know at build time whether any does. So every projection slot is inherently
best-effort. A **non-null slot is a validate-time rejection** (typed, LSP-coded), naming the slot and
the reason. The dual holds and is worth stating: the projection *field itself* may be non-null; only
its slots must be nullable. That dual is an invariant of the pivot, not of one delivery: **one
projection record exists per parent, always; which slots are null is the only data-dependent part.**
Inline satisfies it for free (a correlated aggregate over an empty set still returns one row, of
nulls, never a null record). The split path must preserve it rather than inherit the table shape's
absent-key behaviour: `SplitRowsMethodEmitter`'s table shape inner-joins the `VALUES` parent-input
table, so a parent with no attribute rows would produce no `GROUP BY __idx__` group and
`scatterSingleByIdx` would hand that key a null record, silently null-bombing a non-null projection
field and diverging from inline. The pivot's batch query therefore joins the attribute table *from*
the parent-input table key-preservingly (a left join; the one deviation from the table shape's
join), so every batch key produces a group and a row-less parent scatters to a record of null slots
on both deliveries. Key preservation must hold over the *entire* parent-input → terminus chain,
which is why v1 restricts the pivot's `@reference` path to a single FK hop:
`SplitRowsMethodEmitter`'s bridging hops are forward inner joins and its per-hop filters land in the
WHERE clause, either of which would re-drop the null-extended row on a longer chain. On the
single-hop shape the one left join suffices; extending key preservation chain-wide is a later item
(see Out of scope).

**Delivery.** Inline is the default and the better runtime shape: the projection is a single-valued
field, so it folds into the parent query as a correlated aggregate subselect, one round-trip, no
DataLoader, no N+1 by construction, and no `GROUP BY` (the aggregate over the correlated set collapses
to one row on its own). `@splitQuery` opts into the existing batched DataLoader seam
(`BatchKeyField` → `SplitRowsMethodEmitter`, positional `__idx__` scatter), which re-introduces
`GROUP BY __idx__` over the batch; it earns its place when the field is costly and rarely selected.
The parent must be SQL-backed in v1: a record-backed (class-backed) parent is rejected at validate
time. Inline correlation needs a parent query to fold into, and the record-parent batched seam
derives its keys from accessors (`FieldBuilder.resolveRecordParentSource`), a different capability
than the `deriveSplitQuerySource` path this item reuses. `@splitQuery` is not an escape hatch there:
on record-backed parents the directive is already lint-ignored as redundant
(`LintRule.SPLITQUERY_REDUNDANT_ON_RECORD_PARENT`), so "add `@splitQuery`" cannot be the authoring
answer; extending the pivot to record-backed parents is a later item (see Out of scope).

**Selection-set gating.** The projection obeys the same discipline as `Type.$fields(selectionSet,
alias, env)`: walk the selection set on the projection type and emit one
`max(<value>).filterWhere(<disc>.eq(<token>)).as(<alias>)` column per *requested* slot, using the
`PivotSpec` slot → token map. A `{ navn { nn en } }` selection projects two filtered aggregates,
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

The field's return type is a **plain output type** with no `@table` and no per-field directives: its
fields are the projection slots, named for the languages or codes. The field reaches the attribute
table with `@reference`; it delivers inline by default, or batched if `@splitQuery` is also present.

Each slot selects the row whose discriminator column equals a database token. By default the token is
the slot's own name; when the two differ (a `nn` slot selecting the token `nno`), name a text-mapped
enum with `vocabulary:` and the enum's `@field(name:)` values supply the tokens.

#### SDL signature

```graphql
directive @pivot(on: String!, value: String!, vocabulary: String) on FIELD_DEFINITION
```

#### Parameters

| Name | Type | Description |
|---|---|---|
| `on` | `String!` | The discriminator column on the referenced attribute table. Each slot is matched to a token that this column is filtered to. |
| `value` | `String!` | The column on the referenced attribute table whose value is aggregated per token. |
| `vocabulary` | `String` | Optional. Names a text-mapped enum whose values map each slot's SDL name to the database token (via the enum values' `@field(name:)`). Omit when slot names already equal the tokens (identity). |

#### Example 1: translations

An attribute table `land_sprak(landkode, sprakkode, navn)` holds a country's name once per language.
`AdmissioLand.navn` pivots those rows into one field per language:

The GraphQL slot names (`nn`, `nb`, ...) differ from the database tokens (`nno`, `nob`, ...), so a
text-mapped enum carries the mapping once, at `@field`'s canonical enum-value site:

```graphql
enum Sprak {
    nn @field(name: "nno")
    nb @field(name: "nob")
    se @field(name: "sme")
    en @field(name: "eng")
}

type OversatteTekster {
    nn: String
    nb: String
    se: String
    en: String
}

type AdmissioLand @key(fields: "landkode") @table(name: "land") {
    landkode: String! @shareable
    navn: OversatteTekster
        @reference(path: [{table: "land_sprak"}])
        @pivot(on: "sprakkode", value: "navn", vocabulary: "Sprak")
}
```

`OversatteTekster` is a plain type carrying no pivot markers, so it is reused freely across every
translated field (the value column varies per usage) and may also serve as an ordinary nested object
elsewhere in the schema. A sibling field over `kravelement_sprak.beskrivelse`, keyed on a
three-column FK, reuses the identical type, enum, and directive:

```graphql
beskrivelse: OversatteTekster
    @reference(path: [{table: "kravelement_sprak"}])
    @pivot(on: "sprakkode", value: "beskrivelse", vocabulary: "Sprak")
```

#### Example 2: prices by currency

The shape is not specific to i18n. Any narrow `(owner, discriminator, value)` attribute table
pivots the same way. Given `product_price(product_id, currency_code, amount)`, and slot names chosen
to equal the database tokens, no `vocabulary:` is needed:

```graphql
type PriceByCurrency {
    NOK: BigDecimal
    USD: BigDecimal
    EUR: BigDecimal
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
(here `BigDecimal`), not just `String`. Because the slot names are the tokens, the mapping is identity
and the enum is unnecessary; name a `vocabulary:` enum only when the two diverge.

#### Constraints

* The return type must be a plain output type: no `@table`. Its fields are the slots; they carry no
  pivot directives. The type is not reserved for pivots, so it may also be reached as an ordinary
  nested object elsewhere, including in the same schema.
* Every slot must be **nullable**. A non-null slot fails the build: pivot slots are filtered
  aggregates that are null when no row carries the token. The projection record itself always exists,
  one per parent, even when the parent has no attribute rows at all; absence surfaces as null slots,
  never as a null record, so the `@pivot` field itself may be non-null.
* When `vocabulary:` is given, every slot's SDL name must resolve to a value of the named enum; a slot
  with no matching enum value fails the build, naming the slot. When omitted, each slot's name is used
  as the token directly (identity).
* The `@reference` path must be a single FK hop to the attribute table (composite keys are fine;
  multi-hop chains and condition-join hops are not supported under `@pivot`).
* `on` and `value` must both resolve to columns on the `@reference` terminus (attribute) table. The
  build fails, naming the unresolved column, if either does not.
* The field must be single-valued (one projection record per parent). A list return type is rejected.
* The parent type must be SQL-backed (`@table`); a `@pivot` field on a record-backed parent fails
  the build.
* Inline delivery is the default; add `@splitQuery` for batched delivery when the field is
  expensive and rarely selected.

#### See also

* xref:field.adoc[`@field`] on the vocabulary enum's values names the database token each slot maps
  to (its enum-value role); the `vocabulary:` enum is an ordinary enum, reusable as a field type.
* xref:reference.adoc[`@reference`] establishes the join to the attribute table, including
  composite-FK paths (a single hop under `@pivot`).
* xref:splitQuery.adoc[`@splitQuery`] switches delivery from inline to batched.

---

## Model

**No new type variant.** The pivot imposes no classification on its return type: the classifier
registers the ordinary `GraphitronType.NestingType` for it, with the same assembled-schema form a
plain nesting consumer registers, so dual use is an idempotent repeat in `TypeRegistry.register` and
the one-classification-per-name invariant never engages. An earlier draft introduced a
`PivotProjection` type variant; it is gone because everything it would carry is either derivable from
the SDL type (the slot names) or a fact of the consuming `@pivot` field. The slot → token map is the
field's fact: the same plain type is reused across pivots that may resolve different tokens, so the
token lives on `PivotSpec`, never the slot. The per-slot scalar type is likewise not stored on the
type; it is derived from the value `ColumnRef` on the consuming field. A type variant that only
restates its consumers' facts would be the drift copy the model bans.

*Emission surface.* Nested-type fetcher wiring already rides the consumer edge, not the type:
`FetcherRegistrationsEmitter.collectNestedTypes` gathers each nested type's wiring off the consuming
fields (first occurrence wins as the representative) and its `nestedBody` emits one by-name read per
field against the generic jOOQ `Record`, behind the `FetcherEmitter.nestedTypeOwnsFetchers` gate it
shares with `TypeFetcherGenerator.collectNestedFetcherClasses`. The pivot leaves join that
collection: a pivot-only type gets its wiring from the pivot edge, a dual-use type from whichever
edge is seen first, and either representative produces the same fetcher because the read name
derives from the slot's SDL name by one function that both the nesting read and the pivot's
projected alias consume. That single-sourced read name is the load-bearing fact of dual use; the
execution-tier dual-use case below pins it end-to-end. The new field leaves (below, including the
slot leaf) land in the `TypeFetcherGenerator` dispatch partition (`IMPLEMENTED_LEAVES`), so
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces coverage. There is
**no "reached only through `@pivot`" enforcer** and nothing for one to enforce: the earlier draft
needed it only because `@field(name:)` on the slots carried a consumer-dependent meaning, and that
overload is gone.

**New producer leaves, split on the delivery axis.** Delivery (inline vs batched) is a real orthogonal
axis the model already splits for tables (`ChildField.TableField`, inline, does *not* implement
`BatchKeyField`; `ChildField.BatchedTableField` does and carries `SourceKey` / `LoaderRegistration` /
`KeyLift`). The pivot mirrors that split rather than fusing it into one nullable-bag leaf:

- `ChildField.PivotField` (inline): the correlated aggregate subselect; no `BatchKeyField`.
- `ChildField.BatchedPivotField` (`@splitQuery`): implements `BatchKeyField`, carrying the
  `SourceKey` / `LoaderRegistration` triple from `FieldBuilder.deriveSplitQuerySource`.

Both compose a shared `PivotSpec` sub-record carrying the facts common to both deliveries: the resolved
`@reference` join path (reuse `ctx.parsePath`; composite FKs come free, and the parsed path is
validated to be a single FK hop in v1, see Nullability), the
discriminator `ColumnRef` (from `on:`), the value `ColumnRef` (from `value:`), the projection type, and
the resolved **slot → token map**. That map is built at classify time: when `vocabulary:` is present,
by looking each slot's SDL name up in the named enum's `buildTextEnumMapping` (SDL name → token from
the values' `@field(name:)`); when absent, by identity (token = slot name). This is the one place the
token vocabulary is consumed. Both leaves report `SourceShape.Record` for their children. This keeps
the delivery fact forked on leaf identity, never re-derived from a null check.

**Slot fields are a dedicated leaf `ChildField.PivotSlotField`.** Each projection slot is a new
`ChildField` leaf carrying exactly one fact: its read name, the projected column alias derived from
the slot's SDL name (the token never reaches the slot; it is consumed only when `PivotSpec` builds
the subselect). The pivot edge contributes its slots to the nested-type wiring above exactly as
`NestingField.nestedFields` does, and the emitted read is the same by-name generic-`Record` read
nesting children emit, which is what lets one registered fetcher serve both source shapes. A
nulled-out `ChildField.PropertyField` reuse was considered and rejected: `PropertyField`'s meaning is
a read off a `ResultType`-classified parent carrying a resolved `AccessorResolution` or `ColumnRef`,
neither of which a slot has. Reuse saves nothing and puts one leaf's meaning in the hands of its
parent's classification, the shared-accessor-whose-meaning-depends-on-variant smell. The dedicated
leaf forks on identity in the sealed dispatch instead.

## Implementation

Anchored on symbols; line numbers are omitted deliberately (they drift).

- **Directive.** Declare `directive @pivot(on: String!, value: String!, vocabulary: String) on FIELD_DEFINITION`
  in `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`.
- **Field classification.** `FieldBuilder` classifies a `@pivot`-bearing field into `PivotField`
  (inline) or `BatchedPivotField` (`@splitQuery` present): parse the `@reference` path, resolve
  `on`/`value` to `ColumnRef` on the terminus, resolve the slot → token map (via
  `buildTextEnumMapping` on the `vocabulary:` enum, or identity when absent) against the return
  type's slots into a shared `PivotSpec`, and for the batched leaf derive the
  `SourceKey`/`LoaderRegistration` triple via the existing `deriveSplitQuerySource` path.
- **Type classification.** Nothing new: the return type registers as the ordinary
  `GraphitronType.NestingType`, the same registration a plain nesting consumer produces, so dual use
  is an idempotent repeat. No per-slot directive is read; the token map is the consuming field's
  fact, not the type's.
- **Projection emission.** A new emitter builds the selection-set-gated correlated aggregate subselect:
  for each *selected* slot, `DSL.max(value).filterWhere(disc.eq(DSL.inline(token))).as(alias)` where the
  token comes from `PivotSpec`'s resolved slot → token map,
  wrapped in `DSL.row(...)` and delivered as a scalar-record subselect field
  (`field(select(row(...)).from(terminus).where(<correlation>))`). The
  `max(...).filterWhere(...)` column form is new with this item; nothing in the generator emits a
  filtered aggregate today. The `@asFacet` facets emission (`ConnectionHelperClassGenerator`, a
  UNION ALL of per-facet `GROUP BY` arms) is precedent only for the best-effort-aggregate
  nullability stance, not a reusable column form. Inline needs no `GROUP BY`. The `@splitQuery` path routes through `SplitRowsMethodEmitter` with the pivot
  projection as the select list, the key-preserving join from the parent-input table (the one
  deviation from the table shape's inner join; see Nullability), and `GROUP BY __idx__` over the
  batch, scattered single-per-key via the existing `scatterSingleByIdx`.
- **Slot extraction.** A new `PivotSlotField` arm in `FetcherEmitter.bindRaw` emits the by-alias
  read, `return ((Record) source).get(DSL.field("<alias>"));`, via the existing `columnByAlias`
  helper (already the emission four `bindRaw` arms share), where the alias is the slot's derived
  read name. No existing emitter arm is edited: the `ResultType`-typed helper chain
  (`propertyOrRecordBinding`, `inlineSuccessRead`) keeps its narrow parameter and its
  accessor/`fqClassName` contract untouched; `PivotSlotField` never threads through it (its parent
  is a `NestingType`, not a `ResultType`), and the `JooqRecordCarrier` partition R502 introduced
  (see the R502 entry in `roadmap/changelog.md`) stays `ResultType`-scoped; this slice does not
  touch that seam. The read deliberately matches the nesting children's by-name read so one
  registered fetcher per slot coordinate serves both the pivot subselect's `Record` and a
  compatible nesting parent's record.
- **Selection gating** reads the pivot type's slots off `env.getSelectionSet()`, the same source
  `Type.$fields(...)` already consults.

## Validation

Every classifier decision that implies a generator branch fails at validate time if violated (the
"validator mirrors classifier invariants" rule), each a typed `Rejection.AuthorError` with a stable
LSP code:

- A non-null slot on the return type of a `@pivot` field is rejected, naming the slot and the reason
  (filtered aggregates are null when no row carries the value). The check is per `@pivot` field: the
  same type reached only by plain nesting consumers carries no such constraint. This mirrors the
  best-effort-aggregate nullability the `@asFacet` `FacetValueType` / `FacetsType` already carry.
- When `vocabulary:` is given, a slot whose SDL name is not a value of the named enum is rejected,
  naming the slot and the enum. When `vocabulary:` names a type that is not a text-mapped enum, that is
  rejected too. (There is no "reached only through `@pivot`" enforcer and nothing for one to enforce:
  the pivot imposes no type classification, so mixed consumption registers the same `NestingType`
  twice, an idempotent repeat; see Model.)
- A projection slot that is itself an object or list rather than a scalar is rejected.
- `on` / `value` that do not resolve to columns on the `@reference` terminus are rejected, naming the
  unresolved column.
- All slots must share one scalar type (they read the same value column); a divergent slot type is
  rejected.
- The `value:` column's type must map to the slots' declared scalar type. The projection type is
  reused across usages with different value columns, so this check is per `@pivot` field, not per
  type: a usage whose value column cannot produce the declared slot scalar is rejected, naming the
  column, its type, and the expected scalar.
- A `@pivot` field with a list return type, or with no establishable join to an attribute table, is
  rejected.
- A `@pivot` field whose `@reference` path is anything other than a single FK hop (a multi-hop chain,
  or a condition-join hop) is rejected, naming the field: the split delivery's one-record-per-parent
  invariant requires the whole parent-input → terminus chain to be key-preserving, and v1 guarantees
  that only for the single-hop shape (see Nullability).
- A `@pivot` field on a record-backed (class-backed) parent is rejected, naming the parent type:
  inline correlation requires a parent query, and the record-parent batched seam is out of scope in
  v1 (see Delivery). The rejection message must not suggest `@splitQuery`, which is lint-ignored as
  redundant on record-backed parents.
- Two slots on one `@pivot` field resolving to the same token (whether by two enum values sharing a
  `@field(name:)`, or by identity collision) are rejected: the pivot would emit two identical
  aggregates under different aliases, always an authoring mistake. This is a per-`@pivot` check, since
  the token map is the field's fact.
- `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` covers the three new
  leaves (`PivotField`, `BatchedPivotField`, `PivotSlotField`); `VariantCoverageTest` requires a
  classification case for each new leaf (the pipeline-tier tests below provide them);
  `SealedHierarchyDocCoverageTest` covers any new rejection arm (its scope is the `Rejection`
  permit-to-doc mapping, not `GraphitronType` variants).

## Tests

Per the test-tier discipline (behaviour pinned at the pipeline tier and above; no code-string
assertions on method bodies):

- **Pipeline tier.** SDL → classified model → generated `TypeSpec`: a `@pivot` field classifies as
  `PivotField`, its return type registers as the ordinary `NestingType`, and the field's `PivotSpec`
  carries the expected slot → token map (both the `vocabulary:`-enum and the identity cases); a
  dual-use schema reaching the same type through a `@pivot` field *and* a plain nesting field
  classifies both edges with no conflict (the pivot sibling of
  `SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE`); a selection subset narrows the projected slot
  set; a composite-key `@reference` produces the AND-chained correlation. Inline and `@splitQuery`
  variants both classify.
- **Compilation tier.** `graphitron-sakila-example` fixtures for both examples (a translation pivot and
  a numeric/currency pivot), inline and split, single and composite key, compile under `<release>17>`.
- **Execution tier (PostgreSQL).** Add attribute tables to `graphitron-sakila-db` (a single-key
  translation table over an existing entity, a composite-key one, and a numeric currency-price table),
  seed rows that leave at least one discriminator value unpopulated *and* at least one parent with no
  attribute rows at all, and assert: pivoted values land on the right slots; an unpopulated slot
  returns null; a row-less parent yields a projection record with every slot null, not a null record,
  on both deliveries (this pins the split path's key-preserving join); the composite-key pivot keys
  correctly; inline and split return identical results (the existing inline/split parity convention);
  and a dual-use case reaches one type both through a `@pivot` field and as an ordinary nested object
  on a compatible `@table` parent and asserts both read correctly, pinning that the one registered
  fetcher per slot coordinate serves both source shapes.
- **Validation tier.** Each rejection above has a negative fixture asserting the build fails with the
  typed error.

## Out of scope

- Aggregate functions other than the value-collapsing `max` (the value is unique per
  `(owner, discriminator)`, so `max` just returns it). Custom aggregates are a later item.
- A projection whose slots draw from *different* value columns (one value column per projection in v1).
- Non-equality discriminators (ranges, `IN`-sets per slot).
- Multi-hop `@reference` paths (and condition-join hops) under `@pivot`. Inline delivery would
  tolerate them (a correlated aggregate over an empty joined set still yields one row), but the split
  delivery's key-preserving join must span every hop, `SplitRowsMethodEmitter`'s bridging hops are
  inner joins today, and inline/split parity is an invariant of the pivot; v1 rejects the path shape
  at validate time. Extending the batch shape chain-wide is a later item.
- `@pivot` on a record-backed (class-backed) parent. The record-parent batch seam derives its keys
  from accessors (`FieldBuilder.resolveRecordParentSource`) rather than `deriveSplitQuerySource`;
  extending the pivot there is a later item. V1 rejects it at validate time.
- Writing through a pivot (mutations); `@pivot` is read-only.

## Roadmap entries

Single slice: inline + `@splitQuery`, single + composite keys, in one item. The change is purely
additive: three new field leaves, a new `bindRaw` arm, a new projection emitter, and the pivot edge
joining the existing nested-type wiring collection; no new type variant, and no existing emitter arm
or narrowed helper contract is edited, so the additive-then-cutover discipline has nothing to cut
over.

