---
id: R437
title: "Shape-aware create-Record service helper dedup (R311 correctness bug)"
status: Backlog
bucket: architecture
priority: 2
theme: service
depends-on: []
created: 2026-07-03
last-updated: 2026-07-03
---

# Shape-aware create-Record service helper dedup (R311 correctness bug)

> The R311/R315 jOOQ-`TableRecord` `@service` param feature emits one
> `create<Record>` / `create<Record>List` helper per record class, deduplicated
> **by record class alone**. When two `@service` fields on one type take the
> *same* jOOQ record through *different* input shapes (different `@field` column
> sets), only the first-seen shape's helper is emitted and every call site for
> that record class routes to it, so the other mutation silently drops the
> columns unique to its input. This item re-keys the helper dedup, naming, and
> call-site routing by the full binding *shape* (record class + ordered column
> bindings + ordered key decodes) instead of the record class, so distinct
> shapes each get their own helper and identical shapes still collapse to one.

---

## Motivation

The R311/R315 feature lets a `@service` param be a generated jOOQ `TableRecord`
built from a GraphQL input object: each `@field(name:)` binds a column, each
`@nodeId` decodes into resolved target columns, and a `create<Record>` /
`create<Record>List` helper on the `<Type>Fetchers` class instantiates the
record from the wire `Map` at the fetcher boundary.

Those helpers are deduplicated **by record class alone**. In
`TypeFetcherGenerator` the collection is
`jooqRecordHelpers.putIfAbsent(jr.table().recordClass(), jr)`, and the
root-coordinate walk in `collectFromValueShape` does the same
`putIfAbsent(recordClass, carrier)`. Both the helper namer
(`JooqRecordInstantiationEmitter.singularName`/`pluralName`) and the two
call-site namers (`ArgCallEmitter.buildJooqRecordCallExtraction` for the child
coordinate, `ServiceMethodCallEmitter.jooqRecordHelperCall` / `listExpression`
for the root coordinate) derive the name from `recordClass.simpleName()`. So
three sites independently assume **one binding shape per record class**.

That assumption is false. Two `@service` fields can bind the same jOOQ record
through two different input types carrying different column sets. Only the
first-seen shape's helper is emitted; the `putIfAbsent` drops the second, and
every call site (both derive the name the same way) routes to the survivor. The
second mutation loses the columns unique to its input, which the helper never
sets, so they are written as `NULL` / the DB default. This is a silent
correctness bug: a caller-supplied field is dropped with no error.

Found in the utdanningsregisteret consumer (`fs-plattform`):
`registrerCampusForUtdanningsmulighet` (input carries `fraDato: Date @field(name:
"DATO_FRA")`, 5 fields) and `deaktivereCampusForUtdanningsmulighet` (4 fields, no
`fraDato`) both map `UtdanningsmulighetCampusRecord`. The emitted
`MutationFetchers` had a single `createUtdanningsmulighetCampusRecord` (the
deaktivere shape, without `DATO_FRA`) and both fetchers called it, so
`registrerCampus` never set `dato_fra` and the service fell back to `1900-01-01`.
Each field's carrier is individually correct; the defect is purely that one
shared helper cannot serve two binding sets.

---

## Design

Key the dedup, naming, and routing by the full **binding shape** rather than the
record class.

### D1 — shape signature is the dedup and naming key

A carrier's shape is exactly what determines its emitted helper body: the record
class plus its ordered `ColumnBinding`s (path + resolved column) and ordered
`RecordKeyDecode`s (path + type id + encoder + target columns + nullability). A
stable content signature is built from those names (never `Object.hashCode`, so
it is deterministic across runs). Two carriers share a signature iff they would
emit an identical helper, so it is precisely the dedup key: identical shapes
collapse to one helper (two mutations with the same input shape still share it),
distinct shapes split.

### D2 — naming: bare for the common case, ordinal-suffixed when contended

A record class reached by exactly one distinct shape keeps the bare
`create<Record>` / `create<Record>List` name. This is the overwhelmingly common
case and it stays byte-identical to today, so no existing generated output or
snapshot churns. A record class reached by more than one distinct shape is
*contended*: its shapes are ordered by signature (stable) and each gets a 1-based
ordinal suffix (`create<Record>1`, `create<Record>2`, ...), so the
disambiguation is visible in the emitted names and there is no hidden "primary"
shape. The singular/plural pair shares the shape-derived stem.

The suffix is deliberately **shape-derived, not input-type-derived**: two
different SDL input types with an identical column/decode shape must still share
one helper (D1), so naming by input type name would wrongly split them (or be
ambiguous about which name wins). The ordinal-by-sorted-signature is the stable,
readable disambiguator that respects the dedup.

### D3 — one resolver, computed up front, shared by all sites

The contention decision is a per-`<Type>Fetchers`-class property, so a call site
and its helper can only agree if they consult the same resolver. A new
`JooqRecordHelperNames` (built from every jOOQ-record carrier collected on the
class, both coordinates) owns the signature → name mapping and the distinct-shape
work-list. `TypeFetcherGenerator` builds it **before** any field body is emitted
and stashes it on `TypeFetcherEmissionContext`; the call-site emitters read it
off `ctx`, and the helper-emission drain emits one pair per distinct shape from
the same resolver. Because the resolver is derived once from the class's
carriers, the emitted helper set and every call site's chosen name are consistent
by construction.

### Alternatives considered

- *Name by SDL input type name.* Rejected: breaks the identical-shape-collapse
  invariant (D1/D2), and the carrier does not currently carry the input type
  name.
- *Content hash suffix (e.g. hex digest).* Deterministic and self-contained, but
  ugly in generated code, which the project explicitly values as read/debugged.
  The ordinal-by-sorted-signature is equally deterministic and more readable.
- *Resolve the name at model-build time, stored on the carrier.* The carrier is
  built per field before sibling carriers are known; contention is a
  class-level property, so resolution naturally belongs at the
  `<Type>Fetchers`-class emission scope, not the model.

---

## Scope

- New `JooqRecordHelperNames` resolver (signature, contention, naming, work-list).
- `TypeFetcherEmissionContext` carries the resolver (defaulting to bare so
  schema-free / unit / out-of-band contexts behave exactly as before).
- `TypeFetcherGenerator` collects all carriers up front (both coordinates),
  builds and stashes the resolver, and emits one pair per distinct shape.
- `ArgCallEmitter` (child) and `ServiceMethodCallEmitter` (root, incl. the list
  arm) resolve the name for their own carrier's shape through `ctx`.
- `JooqRecordInstantiationEmitter` takes the resolver for its helper names.

## Test plan

- Red regression: two `@service` fields binding one record through different
  input shapes emit two distinct singular helpers (one setting `RELEASE_YEAR`,
  one not) and each fetcher routes to its own.
- Collapse pin: two fields with an identical input shape share one bare
  `create<Record>` helper (no ordinal suffix, no churn).
- The existing R311/R315/R322/R336 pipeline pins (single-shape bare naming,
  presence, classification, rejections) keep passing.
- Full `mvn install -Plocal-db` (incl. `graphitron-sakila-example` Java-17
  compilation) confirms no snapshot / generated-output churn.
