---
id: R20
title: "`IdReferenceField` code generation"
status: Spec
priority: 4
theme: nodeid
depends-on: []
---

# `IdReferenceField` code generation

## Overview

`InputField.IdReferenceField` ships in the model and classifier (see the
"`IdReferenceField` classifier + synthesis shim" entry in
[`changelog.md`](changelog.md)) but lives in
`TypeFetcherGenerator.NOT_DISPATCHED_LEAVES`: classification produces the
variant and logs migration WARNs on the synthesis-shim path, but the generator
emits no Java for it. A schema using `[ID!] @nodeId(typeName: T)` (or one of
the two equivalent canonical/legacy forms) builds without `UnclassifiedType`
errors, but the resulting fetcher does not actually filter by ID.

This spec covers lifting `IdReferenceField` out of `NOT_DISPATCHED_LEAVES` and
emitting `tableAlias.has<Qualifier>(s)(decodedIds)` as a filter predicate at
the appropriate point in the fetcher pipeline.

## Current state

`InputField.IdReferenceField` carries:

- `targetTypeName` — the GraphQL type the IDs encode.
- `fkName` — the jOOQ FK constraint name (resolved via
  `findUniqueFkToTable`, `@reference(path: [{key:}])`, or the shim's qualifier
  reverse-map).
- `qualifier` — the UpperCamelCase qualifier (`"FilmId"`,
  `"RegistrarStudieprogramStudieprogramId"`); predicate method names are
  `"has" + qualifier` and `"has" + qualifier + "s"`.
- `list` / `nonNull` — `[ID!]` vs scalar `ID!`.
- `synthesized` — `true` on the shim path; classifier already logged the WARN.

`FieldBuilder.walkInputFieldConditions` has a no-op switch arm for the variant;
nothing contributes to `GeneratedConditionFilter`. `TypeFetcherGenerator`
excludes the class from dispatch.

## Desired end state

For each `IdReferenceField` leaf reachable from a query field's filter input,
the generated fetcher emits a WHERE-clause predicate calling the appropriate
`has<Qualifier>` / `has<Qualifier>s` method on the resolved table alias with
decoded ID values. Predicate slots into the same filter the existing
`ColumnField` / `ColumnReferenceField` leaves contribute to, so a filter input
mixing column-mapped fields and `IdReferenceField` fields produces a single
AND'd WHERE.

`TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` no longer contains
`IdReferenceField.class`; `GeneratorCoverageTest` passes without the entry.

## What we're NOT doing

- **Mutation argument shapes.** This spec covers query filter inputs; mutation
  inputs that carry `IdReferenceField` are a separate piece of work if a
  consumer wants the shape.
- **Multi-hop FK chains.** Already excluded at classification time; no codegen
  surface for `@reference(path:)` chains longer than one element.
- **`@condition` co-existence on the same field.** Already excluded by the
  variant's lack of an `Optional<ArgConditionRef> condition` slot.
- **NodeIdEncoder evolution.** The existing locally-emitted encoder is what we
  have to work with; new encoder API surface, if needed, is its own piece of
  work.
- **Retiring the synthesis shim.** Tracked at
  [`retire-synthesis-shims.md`](retire-synthesis-shims.md).

## Phases (sketches; refine before Ready)

### Phase 1 — Predicate emission shape

Decide where the `has<Qualifier>` call slots into the existing filter pipeline:

- Inline in `FieldBuilder.walkInputFieldConditions` alongside the
  `ColumnField` / `ColumnReferenceField` arms, building a synthetic
  `ConditionFilter` whose method body emits the `has*` call.
- A parallel record-class-backed filter shape that lives next to
  `GeneratedConditionFilter` in the filter list.
- Something else.

Decision pending — see Open Questions §1.

### Phase 2 — ID decoding at the call site

Emit the `NodeIdEncoder` decode + key-extraction for each input ID before
passing it to `has<Qualifier>`. Single-key target NodeTypes are a one-liner;
composite-key targets need the FK target columns to align positionally with
the NodeType's `__NODE_KEY_COLUMNS`. Failure-mode contract should match the
existing `Query.node(id:)` decode behaviour (return null vs. throw).

### Phase 3 — Lift from `NOT_DISPATCHED_LEAVES`

Remove `InputField.IdReferenceField.class` from `NOT_DISPATCHED_LEAVES`.
`GeneratorCoverageTest` drives this — once dispatch is wired, the test fails
with the entry still in the set.

### Phase 4 — Fixtures + execution-tier tests

Wire test surface so:

- A query whose filter has `[ID!] @nodeId(typeName: T)` emits one round-trip
  with the `has<Qualifier>s` call in the WHERE.
- Mixed filters (column equality + `IdReferenceField`) AND together.
- FK-inferred and FK-explicit canonical forms emit identically.
- The synthesis shim path emits the same SQL as the canonical form.

`GraphQLQueryTest`-style execution coverage; `QUERY_COUNT` assertions to pin
single-round-trip behaviour. Fixture choice covered in Open Questions §4.

## Open Questions

1. **Where does the predicate sit?** `GeneratedConditionFilter` carries
   `params: List<MethodRef.Param>` and `bodyParams: List<BodyParam>`
   describing a method body that returns a `Condition`. Inserting a
   `has<Qualifier>` call inside that method body is the path of least
   resistance, but `BodyParam` is shaped around column-equality (`ColumnRef`
   plus call-site extraction). Either widen `BodyParam` to carry a
   method-call descriptor, or emit a separate record-predicate filter shape
   that lives alongside `GeneratedConditionFilter` in the filter list.
2. **ID decoding contract.** `NodeIdEncoder` is locally emitted. The
   decode-and-extract-key shape needs both a single-key path and a
   composite-key path; pin which methods get called and whether the key
   extraction lives on the encoder or in a helper next to it.
3. **List handling for the predicate's argument.** `has<Qualifier>s` takes
   `Collection<String>` per the KjerneJooqGenerator contract. Pin
   null-safety (`[ID!]` is non-null elements but the list itself may be
   optional), empty-list semantics (no-op vs. always-false), and ordering
   (irrelevant for set membership but worth being explicit).
4. **Test fixture.** Sakila has `inventory.film_id`-style FKs but no
   `__NODE_TYPE_ID` on the targets. The `idreffixture` schema has node
   metadata but no seed data and no fetcher-bearing query surface. Decide
   between extending `NodeIdFixtureGenerator.METADATA` to add metadata to a
   Sakila target (e.g. `film`) versus extending `idreffixture` with seed
   data and a query surface.

## References

- Variant + classifier landed in changelog entry "`IdReferenceField`
  classifier + synthesis shim".
- `InputField.IdReferenceField` javadoc — variant carrier shape.
- `BuildContext.classifyInputField` — canonical-form arm and synthesis-shim
  arm.
- `JooqCatalog` — `findUniqueFkToTable`, `buildQualifierMap`,
  `qualifierForFk`, `localGetQualifier`.
- `roadmap/retire-synthesis-shims.md` — Backlog stub for promoting the
  per-site WARN to a terminal classifier error post-migration.
