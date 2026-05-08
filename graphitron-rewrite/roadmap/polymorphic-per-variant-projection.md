---
id: R108
title: "Per-variant projection on polymorphic fields"
status: Backlog
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
---

# Per-variant projection on polymorphic fields

Stage-2 of the multi-table polymorphic dispatcher over-selects when the
GraphQL query carries asymmetric inline fragments on a union/interface.
A query like

```graphql
{ customers { address { occupants {
    __typename
    ... on Customer { firstName }
} } } }
```

renders two SELECTs — one against `customer`, one against `staff` — and
**both** project `first_name` even though the staff branch was never
asked for it. The bug is observable in the rendered SQL today; it does
not corrupt response payloads (graphql-java drops unselected fields at
serialisation), but it costs a column read per non-selected branch and
breaks the "selection set drives projection" contract.

## Where the bug lives

`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/MultiTablePolymorphicEmitter.java:1211`
emits the per-typename Stage-2 helper as

```java
fields = new ArrayList<>(Customer.$fields(env.getSelectionSet(), t, env));
```

`env.getSelectionSet()` here is the parent's selection set (the one on
the `occupants` field), which graphql-java exposes as a flat
`getFieldsGroupedByResultKey()` containing every `SelectedField` from
every inline fragment. The generated `$fields` body
(`TypeClassGenerator.emitSelectionSwitch`,
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeClassGenerator.java:236-238`)
loops that map and `switch (sf.getName())` — there is no fragment- or
type-condition awareness. When `Customer` and `Staff` share a field
*name* (e.g. `firstName`), `Staff.$fields` matches the Customer-fragment
`firstName` entry and projects `staff.first_name`.

`SelectedField.getObjectTypeNames()` already carries the per-fragment
type-condition information graphql-java parsed out of the document.
The fix is to consume it.

## Why existing tests don't catch it

`GraphQLQueryTest.addressOccupants_perBranchWhereScopesToAddress`
(`graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java:3037`)
queries `... on Customer { firstName } ... on Staff { firstName }` —
both branches request the field, so the over-selection is invisible.
There is no test today that exercises the asymmetric case.

## Proposed shape (sketch — to be firmed up at Spec stage)

Two reasonable knobs, both leaning on
`SelectedField.getObjectTypeNames()`:

1. **Filter at the call site.** In `buildPerTypenameSelect`, build a
   filtered view of the selection set restricted to selected fields
   whose `getObjectTypeNames()` contains the participant's type name,
   and pass it into `$fields`. Cheapest if `$fields` keeps its current
   signature; requires either a thin wrapper around
   `DataFetchingFieldSelectionSet` or a new `$fields` overload that
   takes a pre-filtered `Set<String>` of permitted result-keys.
2. **Push the filter into `$fields`.** Add a `String concreteTypeName`
   parameter; the generated loop checks
   `sf.getObjectTypeNames().contains(concreteTypeName)` before the
   switch. Cleaner contract, but every `$fields` call site changes —
   non-polymorphic callers would pass the type's own name, which is
   redundant but harmless.

Option (1) keeps the blast radius inside the polymorphic emitter, which
matches the principle "every type with polymorphic dispatch already
goes through `MultiTablePolymorphicEmitter` — non-polymorphic
projection should not pay tax for a polymorphic-only correctness fix".
Final shape decided at Spec.

## Test coverage to add

- **Pipeline-tier**: extend `MultiTablePolymorphicEmitter`'s pipeline
  test (or add one) asserting that the generated Stage-2 body for
  `Customer` does not project `Staff`-only columns when the SDL has
  shared field names — and vice versa.
- **Execution-tier**: a `PolymorphicProjectionQueryTest` (sibling to
  the existing `CompositeKeyLookupQueryTest` SQL-shape tier) that
  captures the rendered SQL via `ExecuteListener` and asserts the
  asymmetric-fragment query renders `select … from "staff"` *without*
  `"staff"."first_name"`. Graphql-java drops the field at serialisation
  today; only the SQL string is load-bearing for the regression.
- **Behavioural** (`GraphQLQueryTest`): one test naming the asymmetric
  case so the response payload contract is locked alongside the
  projection.

## Out of scope

- Same-table polymorphic dispatch (single-table interface) — that path
  goes through a different emitter and per-variant projection is
  already structurally sound there. Confirm during Spec; if shared, fold
  in. If not, leave a one-line note on the same-table path's emitter
  pointing back here.
- The `__typename`-only and PK-only Stage-1 narrow SELECT — Stage 1
  intentionally does not consult the selection set; this item is
  Stage-2 only.
- DataLoader-batched connection arms vs inline arms — the projection
  call site is shared between them, so the fix lands once.

## Acceptance

- Asymmetric-fragment query renders one column per branch matching the
  selection set (no shared-name leakage).
- New `@LoadBearingClassifierCheck` (or equivalent guarantee marker) on
  the call site so a future refactor can't silently revert to passing
  the parent selection set.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green.
