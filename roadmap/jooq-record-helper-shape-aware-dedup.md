---
id: R437
title: "Shape-aware create-Record service helper dedup (R311 correctness bug)"
status: Ready
bucket: architecture
priority: 2
theme: service
depends-on: []
created: 2026-07-03
last-updated: 2026-07-06
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

### D1 — the carrier's structural equality is the dedup key; a canonical render only orders

A carrier's shape is exactly what determines its emitted helper body: the record
class plus its ordered `ColumnBinding`s (path + resolved column) and ordered
`RecordKeyDecode`s (path + type id + encoder + target columns + nullability).
`CallSiteExtraction.JooqRecord` is a record whose every component is a value
type with structural `equals` (`TableRef`, `ColumnRef`, `ClassName`, element-wise
`List`), so `jr1.equals(jr2)` is *already* precisely "these two carriers emit an
identical helper body". The dedup therefore keys on the carrier itself
(`LinkedHashMap<JooqRecord, ...>` replacing the two
`putIfAbsent(recordClass, ...)` sites), not on a hand-built string signature: a
parallel signature function would have to re-enumerate every body-affecting
component and would silently resurrect this exact bug if a component were later
added without updating it. Identical shapes collapse to one helper (two
mutations with the same input shape still share it), distinct shapes split.

A canonical string render of the shape is still needed, but for the one job
structural equality cannot do: a deterministic *sort* of a record class's
distinct shapes (record `hashCode` is not stable across runs). The render is
built from names only and used solely for ordering, never as the identity.

One accepted limitation: collapse compares bindings in producer order, so two
input types carrying the same columns in different SDL declaration order would
not collapse (two helpers constructing the same record). That is a missed
collapse, not a correctness bug; canonicalizing binding order is out of scope
here and can be a follow-up if it ever bites.

### D2 — naming: bare for the common case, ordinal-suffixed when contended

A record class reached by exactly one distinct shape keeps the bare
`create<Record>` / `create<Record>List` name. This is the overwhelmingly common
case and it stays byte-identical to today, so no existing generated output or
snapshot churns. A record class reached by more than one distinct shape is
*contended*: its shapes are ordered by canonical render (stable) and each gets a
1-based ordinal suffix (`create<Record>1`, `create<Record>2`, ...), so the
disambiguation is visible in the emitted names and there is no hidden "primary"
shape. The singular/plural pair shares the shape-derived stem.

The suffix is deliberately **shape-derived, not input-type-derived**: two
different SDL input types with an identical column/decode shape must still share
one helper (D1), so naming by input type name would wrongly split them (or be
ambiguous about which name wins). The ordinal-by-sorted-render is the stable,
readable disambiguator that respects the dedup.

Two legibility/stability notes, both deliberate:

- Each *contended* helper gets a one-line javadoc naming the columns/decodes it
  binds (e.g. `/** Binds ADDRESS, DISTRICT, DATO_FRA. */`), so a reader of the
  generated `MutationFetchers` can map helper to mutation without
  reverse-engineering the sort. Uncontended helpers stay javadoc-free
  (byte-identical to today).
- Ordinals are position-sensitive under the sort: adding or removing a shape
  renumbers siblings. Acceptable because these helpers are private and
  wholesale-regenerated; they are not a stable public identifier.

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

That guarantee is only as good as the routing, so the lookup is hardened: a
*populated* resolver throws when asked to name a carrier it never collected,
rather than silently falling back to the bare name; a silent bare name on an
unrouted path would be this exact bug re-buried, while a generation-time
failure surfaces the routing hole. Only the *default* (never-populated)
resolver of schema-free / unit / out-of-band contexts answers bare
unconditionally, preserving today's behaviour for contexts that by construction
carry at most one shape per record class; part of implementation is confirming
that every production path reaching the two call-site emitters runs under the
populated resolver.

### Alternatives considered

- *Hand-built string content signature as the dedup key.* Rejected in favour of
  the carrier's structural equality (D1): a parallel signature function is a
  second definition of shape-identity that drifts silently when a body-affecting
  component is added to `ColumnBinding`/`RecordKeyDecode` without updating the
  signer, resurrecting this exact bug with nothing to catch it. Structural
  equality is drift-proof by construction; the string render is kept only for
  ordering.
- *Name by SDL input type name.* Rejected: breaks the identical-shape-collapse
  invariant (D1/D2), and the carrier does not currently carry the input type
  name.
- *Content hash suffix (e.g. hex digest).* Deterministic and self-contained, but
  ugly in generated code, which the project explicitly values as read/debugged.
  The ordinal-by-sorted-render is equally deterministic and more readable.
- *Semantic suffix from the distinguishing columns (e.g. `...WithDatoFra`).*
  Readable but unstable: there is often no single clean discriminator, names
  blow up with wide shape differences, and a third shape can shift which
  columns "distinguish". The shape→helper legibility is provided by the
  contended-helper javadoc (D2) instead.
- *Resolve the name at model-build time, stored on the carrier.* The carrier is
  built per field before sibling carriers are known; contention is a
  class-level property, so resolution naturally belongs at the
  `<Type>Fetchers`-class emission scope, not the model.

---

## Scope

- New `JooqRecordHelperNames` resolver (structural-equality dedup, canonical
  render for ordering, contention, naming incl. contended-helper javadoc,
  work-list, throw-on-uncollected-carrier for populated resolvers).
- `TypeFetcherEmissionContext` carries the resolver (defaulting to bare so
  schema-free / unit / out-of-band contexts behave exactly as before).
- `TypeFetcherGenerator` collects all carriers up front (both coordinates),
  builds and stashes the resolver, and emits one pair per distinct shape.
- `ArgCallEmitter` (child) and `ServiceMethodCallEmitter` (root, incl. the list
  arm) resolve the name for their own carrier's shape through `ctx`.
- `JooqRecordInstantiationEmitter` takes the resolver for its helper names.

## Test plan

- Red regression (singular): two `@service` fields binding one record through
  different input shapes emit two distinct singular helpers (one setting
  `RELEASE_YEAR`, one not) and each fetcher routes to its own.
- Red regression (list arm): the same contention through a list-shaped param
  emits two distinct `create<Record>List` helpers; the plural path derives its
  name at the same three sites and must be pinned separately, not assumed from
  the singular case.
- Collapse pin across input types: two fields whose inputs are two
  *differently named* SDL input types with an identical column/decode shape
  share one bare `create<Record>` helper (no ordinal suffix, no churn). Using
  distinct type names is what pins shape-keying over input-type-name keying; a
  same-type collapse test would pass under the wrong design too.
- Determinism pin: the contended ordinals are stable across generator runs
  (ordering by canonical render, not `hashCode`).
- The existing R311/R315/R322/R336 pipeline pins (single-shape bare naming,
  presence, classification, rejections) keep passing.
- Full `mvn install -Plocal-db` (incl. `graphitron-sakila-example` Java-17
  compilation) confirms no snapshot / generated-output churn.
