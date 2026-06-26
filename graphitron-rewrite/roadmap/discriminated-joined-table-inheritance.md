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

Proper support means a participant declares its own `@table`, and the discriminated emitter joins each
participant's detail table to the discriminated base and projects via the participant's own table/alias
(the `MultiTablePolymorphicEmitter` stage-2 per-typename dispatch already threads each participant's own
table into `$fields(env.getSelectionSet(), t, env)` and is the closest existing template). This is a
model-level change: `ParticipantRef.TableBound` would carry its own table and join path, the emitter would
thread per-participant aliases, the validator would mirror the new invariants, and pipeline tests would
pin the shape. It deserves its own Spec rather than being bolted onto a bug fix.

Near-term correctness of the workaround is handled by R388 (qualify the discriminator column across the
three emission sites + reject the discriminator-column-with-`@reference` contradiction + add the missing
execution fixture). R388 is Done; R389 is the deeper feature that removes the need for the workaround.
R392 (In Review) further reworked this same emission site, routing the discriminated `TypeResolver` off a
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

**Projection shape (A2: split `$fields`, not aliased-column reuse).** A joined-table participant's fields
split across two tables: interface-declared shared fields resolve to columns on the base; the participant's
own distinguishing fields resolve to columns on its detail table. `$fields` (one table parameter) cannot
project both against one table. Two routes were weighed:

- **A1 (rejected):** auto-derive the existing `CrossTableField` set from the participant's detail `@table`
  and reuse R388's aliased-projection + per-field alias-read fetcher path unchanged. Smallest emitter
  delta, but it keeps the detail row as a flat bag of `detailAlias.COLUMN.as("Type_field")` projections
  whose typing is erased at the `.as(...)` alias boundary, read back by per-field wiring. That path exists
  as the *workaround's* escape hatch, not as a model of joined-table inheritance; promoting it to a
  load-bearing mechanism minimises the emitter patch at the cost of an interpret-the-aliases shape in the
  generated output that maintainers reverse-engineer. Wrong layer to minimise.
- **A2 (chosen):** split the projection. The interface's shared fields project once against the base via a
  new interface-level `$fields` (there is none today: `$fields` is generated per object type only, against
  a single jOOQ table parameter, `TypeClassGenerator` line ~216). Each participant's detail-resident fields
  project against its detail-table alias through the normal `$fields(restrictTo(sel, typeName), detailAlias,
  env)` path, typed against the detail table and read back as a nested-type record. The participant path
  thus *converges* with the multitable per-typename pattern instead of forking onto the alias-read
  machinery. The cross-module `graphitron-sakila-example` compile is the backstop that the detail-alias
  `$fields` typing lines up against real jOOQ classes.

## Model changes

The roadmap framing ("`ParticipantRef.TableBound` would carry its own table and join path") is the right
decomposition; these make it concrete.

- **Participant carries its own detail table.** Today every `TableInterfaceType` participant's
  `ParticipantRef.TableBound.table` equals the base. For a joined-table participant it becomes the
  participant's own detail table. The single-table case (participant table == base, no detail join) stays a
  valid shape: a `TableInterfaceType` may mix discriminator-only participants (data wholly on the base) with
  joined-table participants (own detail table). Whether the joined-table participant is a distinct sealed
  sub-variant of `ParticipantRef` or `TableBound` carries an optional resolved base→detail hop is the first
  fork for the reviewer; recommendation is a distinct variant so the emitter switches on identity and never
  inspects "is the hop null".
- **Field residence is a type fact, not a recomputed predicate.** "A field's column resolves on the base
  vs the detail table" is a predicate the partitioning step, the emitter, and the validator would each
  recompute (the same Generation-thinking drift R388's `@reference`-on-base-column rejection already guards
  one direction of). Lift it into the model: a participant's projected fields are partitioned at the parse
  boundary into base-resident and detail-resident sets (or sealed field-projection variants carrying their
  resolved table), so the emitter projects each set against the table the model already named and the
  validator reads rather than re-derives.
- **Base→detail join arrives as a resolved join shape, never a `ForeignKey`.** The hop resolves at the
  parse boundary into the existing `JoinStep` / `JoinSlot` vocabulary (the same family `@reference` paths
  and DTO-parent batching speak), stored on the participant variant and threaded to the emitter. Per
  "Classification belongs at the parse boundary", raw `ForeignKey<?,?>` lives only in the parse-boundary
  holders (`JooqCatalog` canonical, with `BuildContext` and the catalog builders `CatalogBuilder` /
  `CatalogFacts`); `TypeBuilder` / `FieldBuilder` / `ServiceCatalog` consume the classified output via
  `JooqCatalog` rather than holding raw types, so the resolution in `buildParticipantList` goes through
  `JooqCatalog` and the emitter receives an already-classified hop. Inference picks the unique catalog FK between detail and base; when
  ambiguous the author declares the path through the same `ctx.parsePath` mechanism `@reference` uses (an
  explicit path directive on the participant). The exact override directive surface is the second reviewer
  fork; the constraint is that it lands in the `JoinStep` vocabulary, not a re-parsed string.

## Implementation

- `TypeBuilder.buildParticipantList` / `buildTableInterfaceType`: resolve each participant's own `@table`,
  partition its fields base-vs-detail against the catalog, and resolve the base→detail hop into a
  `JoinStep`. Stop forcing the single-table assumption (participant table == base).
- `ParticipantRef`: add the joined-table participant shape (own detail table + resolved hop + residence
  partition) per the model-changes fork above.
- `TypeClassGenerator`: add an interface-level `$fields` that projects the interface's shared fields against
  the base; restrict a joined-table participant's `$fields` to its detail-resident fields, projected against
  the detail-table alias.
- `TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher` (and the child
  `buildTableInterfaceFieldFetcher`): project shared fields via the interface-level `$fields` against the
  base, and for each joined-table participant declare its detail alias, emit the discriminator-gated LEFT
  JOIN on the resolved hop, and project detail fields via `$fields(restrictTo(sel, typeName), detailAlias,
  env)`. Reuse R388's discriminator-gated ON-clause and qualified-column emission; do not reuse the
  per-field `CrossTableField` alias-read fetcher for joined-table participants.

## Validation

The validator mirrors every accept/reject the joined-table classifier makes, each routed through the
existing `drainBuildDiagnostics` / `Rejection` machinery (the R204/R279/R317/R388 pattern), surfaced as an
`INVALID_SCHEMA` author error with file:line:

- Base→detail FK ambiguity (more than one catalog FK between detail and base and no override path): reject
  with a candidate-FK hint.
- The base→detail hop must be PK=FK (the join assumption the emitter bakes in): reject a detail table whose
  join to base is not its primary key.
- A participant field classified detail-resident must actually resolve on the detail table (the inverse of
  R388's "reject `@reference` on a base-resident column").
- The joined-table participant field leaf must land in the four-way dispatch partition
  (`IMPLEMENTED` / `PROJECTED` / `NOT_DISPATCHED` / `STUBBED`); `GeneratorCoverageTest`'s
  `everyGraphitronFieldLeafHasAKnownDispatchStatus` fails by construction otherwise. Use that mechanism
  rather than a fresh ad-hoc check.

## Tests

- **Pipeline tier (primary):** a discriminated base + two detail-table participants SDL generates the
  expected `TypeSpec` shape (one base query, per-participant discriminator-gated LEFT JOIN, split
  projection). Pin the shape, not generated method-body strings.
- **Execution tier:** a corpus example alongside the existing `table-interface` example
  (`code-generation-triggers.adoc`), with new sakila fixtures (a discriminated base table + per-concrete-type
  detail tables joined PK=FK). Assert that a polymorphic query returns the right per-type detail columns and
  that non-matching rows carry NULL through the joins. No code-string assertions on emitted join bodies.
- **Validation pipeline tests:** one rejection test per new invariant above, plus a positive case for a
  `TableInterfaceType` mixing a discriminator-only participant with a joined-table participant.
- The full reactor compile under `-Plocal-db` (including the Java-17 `graphitron-sakila-example`) is the
  backstop for the detail-alias `$fields` typing.

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
  name: String!          # shared, resolves on subject (base)
  birthDate: Date!       # resolves on person (detail) — no @reference needed
}

type Organisation implements Subject @table(name: "organisation") @discriminator(value: "ORG") {
  id: ID!
  name: String!
  orgNumber: String!     # resolves on organisation (detail)
}
```

The promise: a concrete type declares the table its own data lives on, once, via `@table`; the generator
derives the base→detail join and the per-table projection. No `@reference` on every detail field. If this
does not read simply, the design is wrong and must change before implementation. The draft moves into
`getting-started.adoc` / the interface-union chapter when the feature ships, scrubbed of `R<n>` / phase
vocabulary per the user-facing-doc check.

## Open forks for the reviewer

1. Joined-table participant as a distinct `ParticipantRef` sub-variant vs `TableBound` carrying an optional
   resolved hop (recommendation: distinct variant).
2. The override directive surface for a non-inferrable base→detail hop (constraint: resolves into the
   `JoinStep` vocabulary at the parse boundary, not a re-parsed string).
