---
id: R389
title: "First-class discriminated joined-table inheritance (participants on their own tables)"
status: Spec
bucket: feature
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# First-class discriminated joined-table inheritance (participants on their own tables)

graphitron supports two polymorphic shapes: single-table discriminated inheritance
(`TableInterfaceType`: `@table @discriminate`, all participants share one table, distinguished by a
discriminator column) and multi-table polymorphism (`MultiTablePolymorphicEmitter`: wholly independent
PK-bearing participant tables UNION'd together). It does **not** support **joined-table (class-table)
inheritance**: a discriminated base table plus a per-concrete-type detail table joined by FK, where the
participant's distinguishing columns live on its own table.

Today an author who has this model is forced to contort it into the single-table path: declare every
participant `@table(name: "<base>")` and put `@reference(path: [<FK to detail table>])` on every
detail-table field, because the discriminated fetcher is hardwired single-table. Specifically
`TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher` selects `FROM tableLocal` (the interface
table) and projects **every** participant's `$fields(sel, tableLocal, env)` against that one shared table
(line ~1005); `$fields`'s `table` parameter is typed as the participant's own jOOQ table class
(`TypeClassGenerator` line ~220) and a plain column emits `fields.add(table.<COLUMN>)` (line ~277). So a
participant on its own detail table fails to compile two ways: the `$fields` parameter type
(`<DetailTable>`) cannot accept the `Subjekt` argument, and a detail column projected against the base
table (`subjektTable.NAVN`) names a column the base table does not have. The `@reference`-per-field
workaround is the escape hatch out of that wrong column path. The visible symptom is
`fields.addAll(FeideApplikasjon.$fields(env.getSelectionSet(), subjektTable, env))` for a subtype whose
data does not live on `subjekt`.

Proper support means a participant declares its own `@table` for its detail data, and declares its
inherited (base-resident) fields with `@reference` back to the discriminated base. The discriminated
emitter then joins each participant's detail table to the base and projects each field against the table
the author named: shared fields off the base, the participant's own fields off its detail alias. This is
a model-level change: a distinct `ParticipantRef` variant carries the participant's detail table and the
resolved child&rarr;parent hop, the emitter threads per-participant detail aliases, the validator mirrors
the new invariants, and pipeline tests pin the shape. It deserves its own Spec rather than being bolted
onto a bug fix.

**Why `@reference` on the inherited fields, rather than inferring residence from the catalog.** The
child&rarr;parent hop is a PK=FK relationship: single-valued, total (every detail row has exactly one base
row), and direction-canonical, so it is *always correct* to resolve a base-resident field from the detail
row by traversing it. Declaring that hop with `@reference` does three things for free, all by reusing
machinery that already exists: (1) it tells `FieldBuilder` to resolve the column on the base table via the
existing `resolveColumnForReference` path (`FieldBuilder.java:5974`) rather than failing to find it on the
detail table; (2) the annotation *is* the residence marker (a field with a parent-`@reference` is
base-resident, a field without is detail-resident) so residence is declared, never inferred; and (3) it
names the FK, so there is nothing to infer and no ambiguous-FK case to defer. The earlier "declare only
`@table`, infer everything" framing is what created two hard problems this version avoids: a new
residence-aware column-resolution path in `FieldBuilder`, and a concrete type that could not be used
outside its interface (see "Standalone use" below). Reusing the cross-table reference vocabulary dissolves
both.

Near-term correctness of the workaround is handled by R388 (qualify the discriminator column across the
three emission sites + reject the discriminator-column-with-`@reference` contradiction + add the missing
execution fixture). R388 is Done; R389 is the deeper feature that removes the need for the workaround.
R392 (Done) further reworked this same emission site, routing the discriminated `TypeResolver` off a
synthetic discriminator alias (`MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`, projected near
`buildInterfaceFieldsList`) to disambiguate the double-projection; the joined-table emitter inherits that
aliasing alongside R388's qualified-column emission, so the "Reuse R388's discriminator-gated ON-clause"
note below reads against the post-R392 shape on trunk.

## Design

**Query shape (confirmed): one discriminated base query plus a per-participant discriminator-gated
LEFT JOIN to each detail table.** Joined-table participants share the discriminated base (one PK space,
one discriminator column, FK-correlated details), so the database can do one indexed join per
participant from a single `FROM base`. This is the grain the existing `TableInterfaceType` fetcher
already speaks, and the join machinery R388 hardened (qualified discriminator column, discriminator-gated
ON-clause, NULL-for-non-matching rows) is exactly what carries the detail joins. We do **not** generalize
the two-stage `MultiTablePolymorphicEmitter` topology: that emitter pays a UNION + per-typename second
pass precisely because multi-table participants share no common table to filter on. Modelling the
base→detail correlation the schema guarantees as if it were absent would work around the database rather
than with it. What we borrow from `MultiTablePolymorphicEmitter` is its *per-typename `$fields` threading*
(`$fields(PolymorphicSelectionSet.restrictTo(sel, typeName), t, env)` against the participant's own table),
not its query topology.

**Projection shape: each field projects against the table its `@reference` (or absence of one) names.**
A joined-table participant's fields split across two tables: a field with a parent-`@reference` is
base-resident and its column lives on the base; a field without one is detail-resident and its column lives
on the participant's own detail table. Because residence is *declared* by the annotation, the classifier
hands the emitter fields already typed by residence: base-resident fields are `ChildField.ColumnReferenceField`
(the existing cross-table read), detail-resident fields are plain `ChildField.ColumnField` on the detail
table. The emitter never re-derives residence; it reads the field variant.

The two query topologies are mirror images, and the declared hop is correct in both:

- **Interface (discriminated) query** is `FROM base`, with a per-participant discriminator-gated LEFT JOIN
  to each detail table. The interface's shared fields are projected directly off the base; each
  participant contributes only its **detail-resident** fields, projected against its detail alias. The
  participant's base-resident fields are *not* re-projected here, the base row is already in hand, so the
  parent-`@reference` is simply not traversed on this path.
- **Standalone query** (the concrete type used as a return type outside the interface) is `FROM detail`.
  Here the participant's **base-resident** fields traverse the parent-`@reference` (the existing
  `ColumnReferenceField` join to the base), and its detail-resident fields read directly off the detail
  table.

So the participant's full `$fields` (every field, parent-references traversed) is what serves standalone
use, and a **detail-only projection** (detail-resident fields against the detail alias) is what the
interface fetcher uses. Critically these are *two distinct projections*, not one mutated method: the
earlier draft's "restrict the participant's `$fields` to detail-resident fields" globally corrupts the
standalone projection (it silently drops the inherited fields). The interface path must therefore use a
detail-scoped projection while the type's full `$fields` stays intact. Implementation may realise the
detail-scoped projection as a second generated method on the participant class or as a residence-filtered
emission at the interface call site; the requirement is that the interface path does not re-traverse
parent-references and the standalone path does. The cross-module `graphitron-sakila-example` compile is
the backstop that both projections line up against real jOOQ classes.

**Standalone use is in scope.** Because the parent hop is declared, a joined-table participant is a
first-class return type on its own, not only reachable through its interface. This is a deliberate gain
over the inference-only framing, where the inherited columns lived on a table the standalone query never
joined. One acceptance check gates it: confirm the specific `ColumnReferenceField` shape we lean on
(single-hop FK to the parent, scalar projection, in a standalone table-type `$fields`) is an implemented
shape and not one of the deferred variants `validateColumnReferenceField` gates; if it is deferred, that
deferral is the first thing to lift.

## Model changes

- **A distinct `ParticipantRef` variant for joined-table participants.** Today every `TableInterfaceType`
  participant is a `ParticipantRef.TableBound` whose table equals the base. A joined-table participant gets
  its own sealed variant (`ParticipantRef.JoinedTableBound`) carrying its detail table, its discriminator
  value, and the resolved child&rarr;parent hop. A distinct variant (not `TableBound` carrying an optional
  hop) lets the emitter switch on identity and never inspect "is the hop null". The single-table case
  (participant table == base, no detail) stays `TableBound`, so a `TableInterfaceType` may freely mix
  discriminator-only participants with joined-table participants. (Both share a `TableBacked` capability for
  the type-name / table / discriminator-value reads the TypeResolver-routing and discriminator-collection
  sites need.)
- **Residence is carried by field classification, declared by `@reference`.** Because the author declares
  the parent hop, residence is not a predicate anyone recomputes: a base-resident field classifies as
  `ChildField.ColumnReferenceField` (resolved on the base via the existing reference path), a detail-resident
  field as `ChildField.ColumnField` on the participant's own table. The emitter and validator read the field
  variant; they never ask the catalog "which table does this column live on". This is the Generation-thinking
  win the inference framing only approximated, achieved by reusing the classifier path that already exists
  rather than adding a residence-aware resolver to `FieldBuilder`.
- **The child&rarr;parent hop arrives as a resolved `JoinStep`, never a `ForeignKey`.** The hop the author
  names in `@reference` resolves at the parse boundary into the existing `JoinStep.FkJoin` / `JoinSlot`
  vocabulary and is stored on the `JoinedTableBound` variant. Per "Classification belongs at the parse
  boundary", raw `ForeignKey<?,?>` stays inside the parse-boundary holders (`JooqCatalog` canonical, with
  `BuildContext` and the catalog builder `CatalogBuilder`); `TypeBuilder` / `FieldBuilder` consume the
  classified hop. The hop is direction-blind (`slot.sourceSide()` / `slot.targetSide()`), so the interface
  fetcher reads it as `base.pk = detail.pk` for the detail LEFT JOIN while the standalone reference path
  reads it as `detail -> base`, both off the same resolved slot pair. Because the FK is named by the author,
  there is no inference and no ambiguous-FK case (contrast the inference framing, which had to defer the
  ambiguous case to R393).

> Note on the in-flight model code. The inert `JoinedTableBound` already on trunk (commit `073443e`) was
> shaped for the inference framing (it carries a `detailResidentFields` list and a `baseToDetail` hop named
> as an inferred FK). Under this design residence is implicit in field classification and the hop is the
> author-declared child&rarr;parent reference, so that record will be reshaped during implementation (drop
> `detailResidentFields`; the hop is the parent reference). The `TableBacked` capability and the
> routing-site edits carry over unchanged.

## Implementation

- `ParticipantRef`: the `JoinedTableBound` variant + `TableBacked` capability (foundation already on trunk
  at `073443e`; reshape per the model-changes note). Carries detail table, discriminator, and the resolved
  child&rarr;parent `JoinStep.FkJoin`.
- `TypeBuilder.buildParticipantList` / `buildTableInterfaceType`: detect a participant whose `@table`
  differs from the base, resolve its child&rarr;parent hop from the parent-`@reference` it declares, and
  build a `JoinedTableBound`. Stop forcing the single-table assumption (participant table == base).
- `FieldBuilder`: **no new residence resolver.** A joined-table participant's base-resident fields carry
  `@reference`, so they reach the existing cross-table `ColumnReferenceField` classification
  (`resolveColumnForReference`, `FieldBuilder.java:5974`) unchanged. The one reconciliation: R388's guard
  that rejects a `@reference` whose resolved column lives on the base table (`TypeBuilder.java:820`) was
  written under the workaround's "participant table == base" assumption, where such a reference *was*
  meaningless. For a joined-table participant (table &ne; base) the parent-reference is the legitimate base
  bridge, so the guard must be scoped to fire only when participant table == base. This scoping is itself a
  validator-mirrored invariant (see Validation).
- `TypeClassGenerator`: the interface fetcher needs a **detail-only projection** of each joined-table
  participant (detail-resident fields against the detail alias) that does *not* re-traverse parent-references.
  Realise it as a second generated projection on the participant class, or as a residence-filtered emission
  at the interface call site; do not mutate the participant's full `$fields` (that one stays whole for
  standalone use). The interface's own shared fields project off the base, either via a small interface-level
  `$fields` (there is none today: `$fields` is generated per object type only, `TypeClassGenerator` line
  ~216) or by reading them directly in the fetcher.
- `TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher` (and the child
  `buildTableInterfaceFieldFetcher`): project the interface's shared fields off the base, and for each
  joined-table participant declare its detail alias, emit the discriminator-gated LEFT JOIN on the resolved
  child&rarr;parent hop (`base.pk = detail.pk AND base.discriminator = '<value>'`), and project that
  participant's detail-resident fields via the detail-only projection against the detail alias. Reuse R388's
  discriminator-gated ON-clause and qualified-column emission (and R392's synthetic discriminator alias for
  TypeResolver routing).

## Validation

The validator mirrors every accept/reject the joined-table classifier makes, each routed through the
existing `drainBuildDiagnostics` / `Rejection` machinery (the R204/R279/R317/R388 pattern), surfaced as an
`INVALID_SCHEMA` author error with file:line:

- The child&rarr;parent hop must be PK=FK (the join assumption the interface fetcher bakes in): reject a
  participant whose declared reference to the base is not via the detail table's own primary key. This is
  classic shared-PK class-table inheritance.
- A participant's parent-references must all resolve to the same base, the discriminated interface's table.
  A parent-reference pointing at some other table is not a base bridge; reject it.
- R388-guard scoping (the mirrored invariant for the guard reconciliation above): the "`@reference` whose
  column lives on the base table is meaningless" rejection must fire only when participant table == base.
  For a joined-table participant the same shape is the legitimate parent bridge, so the guard's accept/reject
  flips with the participant kind, and the validator reads that classification rather than recomputing the
  column-on-base predicate.
- The joined-table participant's field leaves must land in the four-way dispatch partition
  (`TypeFetcherGenerator.IMPLEMENTED_LEAVES` / `PROJECTED_LEAVES` / `NOT_DISPATCHED_LEAVES` /
  `STUBBED_VARIANTS`); base-resident fields are `ColumnReferenceField` and detail-resident fields are
  `ColumnField`, both `PROJECTED_LEAVES`, so `GeneratorCoverageTest`'s
  `everyGraphitronFieldLeafHasAKnownDispatchStatus` covers them by construction. The one caveat is the
  acceptance check noted under "Standalone use": the single-hop-FK scalar `ColumnReferenceField` shape must
  be implemented, not deferred.

No ambiguous-FK invariant: the author names the FK in `@reference`, so the inference-era ambiguity case
(and its R393 deferral) does not arise here.

## Tests

- **Pipeline tier (primary):** a discriminated base + two detail-table participants (inherited fields with
  parent-`@reference`, own fields without) generates the expected `TypeSpec` shape: one base query,
  per-participant discriminator-gated LEFT JOIN on the child&rarr;parent hop, shared fields off the base,
  detail fields off the detail alias. Pin the shape, not generated method-body strings.
- **Standalone-use pipeline test:** the same joined-table participant used as a standalone query return type
  generates a `FROM detail` fetcher whose inherited fields resolve via the parent-reference join. This is
  the regression guard for the property that motivated the redesign, the concrete type is first-class
  outside its interface.
- **Execution tier:** a corpus example alongside the existing `table-interface` example
  (`code-generation-triggers.adoc`), with new sakila fixtures (a discriminated base table + per-concrete-type
  detail tables joined PK=FK). Assert that a polymorphic query returns the right per-type detail columns,
  that non-matching rows carry NULL through the joins, and that a standalone query of one concrete type
  returns both its inherited and its own columns.
- **Validation pipeline tests:** one rejection test per new invariant above (non-PK=FK hop; parent-reference
  to a non-base table; the R388-guard scoping in both directions), plus a positive case for a
  `TableInterfaceType` mixing a discriminator-only participant with a joined-table participant.
- The full reactor compile under `-Plocal-db` (including the Java-17 `graphitron-sakila-example`) is the
  backstop for the detail-alias projection typing.

## User documentation (first-client check)

This changes the authoring surface, so the docs draft is the first client of the design. The author writes,
on a discriminated base interface:

```graphql
interface Subject @table(name: "subject") @discriminate(on: "subject_type") {
  id: ID!
  name: String!
}

type Person implements Subject @table(name: "person") @discriminator(value: "PERSON") {
  id: ID!
  name: String! @reference(...)   # inherited: lives on subject, reached via the person -> subject PK=FK
  birthDate: Date!                # own: lives on person (detail)
}

type Organisation implements Subject @table(name: "organisation") @discriminator(value: "ORG") {
  id: ID!
  name: String! @reference(...)   # inherited
  orgNumber: String!              # own: lives on organisation (detail)
}
```

The promise: a concrete type declares the table its own data lives on via `@table`, and declares its
inherited fields with `@reference` back to the base. The annotation says exactly what is true, the
inherited value lives on the parent and is reached by the PK=FK hop, and the generator does the rest: the
discriminated join for interface queries, the parent join for standalone queries. The cost relative to the
discarded "infer everything" sketch is one `@reference` per inherited field (typically few, since
class-table inheritance keeps the shared columns small); the gain is that the concrete type is usable on
its own, residence is explicit rather than magic, and there is no ambiguous-FK case to reason about. If
this does not read simply, the design is wrong and must change before implementation. The draft moves into
`getting-started.adoc` / the interface-union chapter when the feature ships, scrubbed of `R<n>` / phase
vocabulary per the user-facing-doc check.

> The exact `@reference` argument spelling for the parent hop (PK=FK, single hop, scalar projection of the
> named base column) should be pinned during the docs draft against the existing `@reference` syntax; this
> sketch elides it. A behavior delta worth stating in the chapter: the old `@table(base)` + per-detail-field
> `@reference` workaround let you query the concrete type standalone because every column physically sat on
> the base. This model splits the data across two tables, so standalone querying now works *because* the
> inherited fields carry the parent hop, not because everything lives on one table.

## Out of scope

- **Detail tables that do not share the base's primary key.** The child&rarr;parent hop is required to be
  PK=FK (the detail table's join column to the base is its own primary key, i.e. classic shared-PK
  class-table inheritance). A detail table with a separate surrogate PK plus an independent FK column to the
  base is rejected (Validation), not silently joined on a guessed column.
- **Multi-level joined-table chains (base &rarr; mid &rarr; leaf).** Each level would reference its
  immediate parent; the hops compose, and the PK=FK invariant holds at each. The mechanism extends to it
  cleanly, but v1 ships and tests the single base &rarr; detail level; deeper chains are a follow-up if a
  schema needs them.

## Bearing on R393

R393 ("author-declared base&rarr;detail FK override for the ambiguous-FK case") was carved out of the
*inference* framing: when the generator infers the unique catalog FK between detail and base, a schema with
more than one FK is ambiguous and needs an override. This design has the author name the FK in `@reference`
from the outset, so the ambiguity never arises and there is nothing to override. **R393 is very likely moot
under this design and should be revisited** (Discarded, or repurposed if a non-`@reference` shorthand is
ever wanted) rather than carried as a live dependency. Left as a decision for the reviewer / user, not
unilaterally closed here.

## Resolved design decisions

1. **Authoring mechanism:** inherited (base-resident) fields carry a parent-`@reference`; own fields do not.
   Residence is declared, not inferred, and the parent hop reuses the existing cross-table reference path.
   This supersedes the first draft's "declare only `@table`, infer residence and FK" sketch, which forced a
   new residence resolver into `FieldBuilder` and left the concrete type unusable outside its interface.
2. **Joined-table participant shape:** a distinct `ParticipantRef.JoinedTableBound` sub-variant (not
   `TableBound` carrying an optional hop), so the emitter switches on identity.
3. **Standalone use:** in scope, and a first-class motivation rather than a deferred follow-up. The concrete
   type is queryable on its own via the declared parent hop.
4. **Projection mechanism:** the participant's full `$fields` stays whole (serves standalone); the interface
   path uses a separate detail-only projection. The two are distinct, never one globally-restricted method.
