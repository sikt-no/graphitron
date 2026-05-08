---
id: R114
title: "Multi-hop @reference path on @nodeId filter input fields"
status: Spec
bucket: architecture
priority: 6
theme: nodeid
depends-on: []
---

# Multi-hop `@reference` path on `@nodeId` filter input fields

## Problem

`NodeIdLeafResolver.resolveFkJoinPath` rejects every `@reference(path:)` longer than one hop on `@nodeId(typeName: T)` filter inputs and arguments with `"@reference path on @nodeId must be single-hop; multi-hop FK filters are not supported"`. The carriers (`InputField.{Column,CompositeColumn}ReferenceField`, `ArgumentRef.ScalarArg.{Column,CompositeColumn}ReferenceArg`) already hold `joinPath: List<JoinStep>`; the model is general, only the resolver and the consumer in `FieldBuilder` are not.

This blocks the natural shape for a filter that reaches a related node type through one or more intermediate tables, even when the database is designed so the related node's keys are physically present on the parent's row. Example:

```graphql
extend type Query {
    sakerV2(filter: SakFilterV2Input): [Sak!] @asConnection
}

input SakFilterV2Input {
    opptaksId: ID @nodeId(typeName: "Opptak") @reference(path: [
      { key: "sak__sak_soknad_fk" },
      { key: "soknad__soknad_opptak_fk" }
    ])
}
```

There is no direct FK between `sak` and `opptak`, but the chain `sak --sak_soknad_fk--> soknad --soknad_opptak_fk--> opptak` is well-defined, and the schema's identity-carrying FKs make `(opptakstype_kode, opptak_kode)` directly observable on a `sak` row. Today the author has to drop down to a hand-written `@condition` method.

## Scope: identity-carrying lift only

The user-visible feature is "multi-hop `@reference` path on `@nodeId`". The implementation insight is that the cleanest case admits a direct column projection on the parent table, with no JOIN and no subquery, when each step preserves the next step's source-side columns positionally by SQL name. R114's scope is exactly this case: identity-carrying multi-hop chains.

**Definition (lift predicate).** Walk `joinPath` from the second hop forward. At each step `i ≥ 1`, every column in `hop[i].sourceSideColumns` must match a column in `hop[i-1].targetSideColumns` by SQL name (case-insensitive). Equivalently: the FK at step `i` projects onto columns the previous hop already carried as targets. When this holds at every step, the terminal hop's source-side column tuple lifts back through the chain, position by position, to a sub-tuple of the first hop's source-side columns: a column tuple **on the parent's own table**, positionally aligned with the decoded NodeType keys.

When the lift succeeds, emission is unchanged from single-hop direct-FK: `BodyParam.Eq` / `In` (arity 1) or `BodyParam.RowEq` / `RowIn` (arity ≥ 2) against the lifted columns. The only emitter change is "compute the column tuple from the lifted path instead of `joinPath[0].sourceSideColumns()`".

**Worked example.** From the user's catalog:

```
SAK__SAK_SOKNAD_FK   sak.(SOKER_ID, OPPTAKSTYPE_KODE, OPPTAK_KODE) -> soknad.(SOKER_ID, OPPTAKSTYPE_KODE, OPPTAK_KODE)
SOKNAD__SOKNAD_OPPTAK_FK   soknad.(OPPTAKSTYPE_KODE, OPPTAK_KODE) -> opptak.(OPPTAKSTYPE_KODE, OPPTAK_KODE)
OPPTAK_PK   opptak.(OPPTAKSTYPE_KODE, OPPTAK_KODE)
```

- hop2.sourceSideColumns = `soknad.(OPPTAKSTYPE_KODE, OPPTAK_KODE)`. Each of these matches a column in hop1.targetSideColumns = `soknad.(SOKER_ID, OPPTAKSTYPE_KODE, OPPTAK_KODE)` at positions 1 and 2.
- Lift through hop1: positions 1 and 2 of hop1.sourceSideColumns = `sak.(OPPTAKSTYPE_KODE, OPPTAK_KODE)`.
- Lifted tuple = `sak.(OPPTAKSTYPE_KODE, OPPTAK_KODE)`. Decoded NodeType keys = `Opptak.(OPPTAKSTYPE_KODE, OPPTAK_KODE)`. Both arity-2; both positions match by SQL name.
- Emission: `BodyParam.RowIn(name="opptaksId", columns=[sak.OPPTAKSTYPE_KODE, sak.OPPTAK_KODE], extraction=NodeIdDecodeKeys.SkipMismatchedElement(decode<Opptak>))`.

The emitted SQL is `DSL.row(sak.OPPTAKSTYPE_KODE, sak.OPPTAK_KODE).in(decodedKeys)` — identical to a single-hop direct-FK shape. The chain length is purely a classifier-time concept; the runtime SQL is direct.

## Out of scope (deferred to siblings)

- **Non-identity-carrying multi-hop** (lift fails at some step). Reject at classify time with a message naming the failing step and a pointer to the deferred-emission item. File a Backlog sibling for EXISTS-subquery / JOIN-with-translation emission when a real schema reaches the shape; do not pre-build the carrier shape now. The shape is symmetric to R57 (single-hop translated FK on the input side) and will likely route through the same emitter follow-on.
- **Condition-join steps inside multi-hop `@nodeId` paths.** Every step must be `JoinStep.FkJoin`. A `{ condition: ... }` step is rejected.
- **Output-side multi-hop `ChildField.ColumnReferenceField` / `CompositeColumnReferenceField`** stays under R24's umbrella.
- **Mutation-key `@nodeId` args.** Same shape would generalise but lives outside this item; mutation-key support is itself a deferred surface.

## Classifier change

`NodeIdLeafResolver.resolveFkJoinPath`:

- Drop the `path.elements().size() != 1` rejection.
- After parsing, every step must still be `JoinStep.FkJoin`. The loop replaces the existing single-element `instanceof FkJoin` check.
- For length ≥ 2, run the lift predicate against each adjacent step pair. On failure, return a rejection: `"@reference path on @nodeId leaf '<name>': hop <i> ('<fk-name>') introduces a column translation — its source-side columns are not a positional subset of the previous hop's target-side columns by SQL name. Multi-hop @reference on @nodeId currently requires identity-carrying FKs at every step. <hop[i].sourceSideColumns> not all present in <hop[i-1].targetSideColumns>."`
- The rejection message is anchored to a load-bearing test substring (see "Diagnostics" below).

`NodeIdLeafResolver.resolve`:

- The `DirectFk` vs `TranslatedFk` decision (line 272: `sameColumnsBySqlName(fkJoin.targetSideColumns(), keys.keyColumns())`) switches from `joinPath.get(0)` to `joinPath.getLast()`. The terminal hop is the one whose target-side columns must positionally match the NodeType key columns; intermediate hops are constrained by the lift predicate above.
- The lifted tuple is computed once at the resolver and exposed on `Resolved.FkTarget.DirectFk`. Add a slot `liftedSourceColumns: List<ColumnRef>` (or rename the existing `fkSourceColumns` slot to make the multi-hop semantics explicit). For length-1 paths, `liftedSourceColumns == joinPath.get(0).sourceSideColumns()` — backward-compatible.

`BuildContext.classifyInputField` and `FieldBuilder.classifyArgument`: no carrier-shape change. Both consumers already accept `joinPath: List<JoinStep>` of any length on the existing carriers; the resolver returns a lifted tuple via the new slot.

## Emitter change

`FieldBuilder.projectFilters` (`ColumnReferenceArg` and `CompositeColumnReferenceArg` arms) and `FieldBuilder.walkInputFieldConditions` (`InputField.ColumnReferenceField` and `CompositeColumnReferenceField` arms): swap `((JoinStep.FkJoin) joinPath().get(0)).sourceSideColumns()` for the resolver-supplied lifted tuple. The `BodyParam.{Eq,In,RowEq,RowIn}` projection is otherwise unchanged.

`TypeConditionsGenerator.buildConditionMethod`: no change. The emitted SQL is the same `field.eq/in(...)` / `DSL.row(...).eq/in(...)` shape as single-hop today — only the column tuple differs.

The carrier hierarchy (`InputField.*`, `ArgumentRef.*`, `BodyParam.*`) gets no new variants. Multi-hop is encoded in `joinPath.size() > 1` plus the lifted-tuple slot on the resolver result; emitter sites read the lifted tuple uniformly. The variant-identity-tracks-shape rule still holds: the user-visible shape (lifted column tuple → row predicate) is the same shape single-hop direct-FK already emits. No new variant means no new emitter, validator, or test surface for the SQL projection itself; the new behaviour is concentrated in the resolver's lift computation.

## Diagnostics

Two diagnostic templates are anchored to test substrings, mirroring R57's `"FK's target columns do not positionally match"` precedent:

- **Lift failure**: `"@reference path on @nodeId leaf '<leafName>': hop <i> ('<fkName>') introduces a column translation — its source-side columns <[sql-names]> are not a positional subset of the previous hop's target-side columns <[sql-names]> by SQL name. Multi-hop @reference on @nodeId currently requires identity-carrying FKs at every step."` Test substring: `"introduces a column translation"` and `"identity-carrying FKs"`.
- **Non-FK step inside multi-hop**: `"@reference path on @nodeId leaf '<leafName>': step <i> is a condition step; multi-hop @nodeId paths require every step to be a foreign key (use { key: ... } at every position)."` Test substring: `"every step to be a foreign key"`.

Both messages route through `Resolved.Rejected` from the resolver and surface via `InputFieldResolution.Unresolved` (input-field side) or `ArgumentRef.UnclassifiedArg` (argument side), the same paths existing single-hop rejections take.

## @LoadBearingClassifierCheck / @DependsOnClassifierCheck

The existing key `nodeid-fk.direct-fk-keys-match` widens its description from "FK target-side columns positionally match NodeType key columns" to "the **terminal hop's** target-side columns positionally match NodeType key columns AND every intermediate hop satisfies the lift predicate". The producer (`NodeIdLeafResolver.resolve`) and the four consumers already annotated (`FieldBuilder.projectFilters`, `FieldBuilder.walkInputFieldConditions`, `BuildContext.classifyInputField`) need their `reliesOn:` text updated to read the resolver-supplied lifted tuple rather than `joinPath[0].sourceSideColumns()`.

A new key `nodeid-fk.identity-carrying-lift` covers the lift computation itself: producer at `NodeIdLeafResolver`, consumed by every site reading the lifted tuple. Pair via `@DependsOnClassifierCheck` so `LoadBearingGuaranteeAuditTest` flags drift if a future change starts emitting against `joinPath[0].sourceSideColumns()` directly when length > 1. The two keys may compose as a single key with a richer description; pick the cleanest at implementation time.

## Test coverage

- **L1 / unit (`NodeIdLeafResolverTest`)** — three cases:
  1. `MULTI_HOP_IDENTITY_CARRYING_LIFT`: 2-hop chain on a synthetic fixture (or sakila-extended; see fixture note) where the lift succeeds. Assert `Resolved.FkTarget.DirectFk` with `liftedSourceColumns` matching the expected parent-table tuple.
  2. `MULTI_HOP_LIFT_TRANSLATION_REJECTED`: 2-hop chain where intermediate hops drop a key column the next hop needs. Assert `Resolved.Rejected` with the lift-failure message.
  3. `MULTI_HOP_CONDITION_STEP_REJECTED`: a `{ condition: ... }` step in the path. Assert `Resolved.Rejected` with the non-FK-step message.

- **L3 / pipeline (`NodeIdPipelineTest`)** — two cases mirroring the input-field carrier and the argument carrier:
  - `InputFieldFkTargetNodeIdCase.MULTI_HOP_IDENTITY_CARRYING`: verifies the `InputField.ColumnReferenceField` (or `Composite*`) has `joinPath.size() > 1`, `column = NodeType key`, and the resolver-supplied lifted tuple lives on the parent's own table.
  - `ArgumentFkTargetNodeIdCase.MULTI_HOP_IDENTITY_CARRYING`: argument-side mirror.

- **L4 / compilation** — generated `<TypeName>Conditions` class compiles cleanly against the lifted tuple; no warnings.

- **L5 / pipeline-tier emitter** (`QueryConditionsGeneratorLiftTest` or sibling): verify the emitted method body uses `DSL.row(parentTable.LIFTED_COL_1, parentTable.LIFTED_COL_2).in(arg)` (composite case) or `parentTable.LIFTED_COL.in(arg)` (scalar case). Differentiates from a hypothetical EXISTS-subquery follow-on.

- **L6 / execution (`GraphQLQueryTest`)** — `multiHopReferenceFilter_returnsRows`: round-trip a query that filters through a 2-hop identity-carrying chain. Assert (a) returned rows match the filter by node ID; (b) `ExecuteListener` SQL contains a single-table FROM clause and no subquery (pin the direct-projection shape).

## Fixture

The user's `sak/soknad/opptak` schema is the natural fixture, but it is not part of the open-source sakila DB. Two options:

1. **Extend `nodeidfixture`** with a 3-table identity-carrying chain inspired by `sak/soknad/opptak` — flat 4-table fixture: `level_a` (PK `(k1, k2)`), `level_b` (PK `(s, k1, k2)`, FK to `level_a` on `(k1, k2)`), `level_c` (PK `(c, s, k1, k2)`, FK to `level_b` on `(s, k1, k2)`). The R114 test surface filters `level_c` by `levelAId` through the 2-hop chain. Surrogate single-column variants for the arity-1 lift case live in the same fixture.
2. **Sakila** with a synthetic adjustment: rejected. Sakila uses single-column surrogate keys throughout; identity-carrying composite FKs are not naturally present.

R114 picks option 1. The fixture lives alongside the existing `parent_node` + `child_ref` (R50 phase g-B) and uses the same `KjerneJooqGenerator` machinery; no new infrastructure.

## Acceptance criteria

- `@reference(path: [...])` of length ≥ 2 on `@nodeId(typeName: T)` filter input fields and arguments is accepted by the classifier when (a) every step is a `FkJoin`, (b) the lift predicate holds at every adjacent pair, and (c) the terminal hop's target-side columns positionally match `T.keyColumns`.
- The generated `<TypeName>Conditions` method emits a direct row predicate (`field.eq`/`field.in` for arity 1; `DSL.row(...).eq/.in` for arity ≥ 2) against the lifted tuple on the parent's own table — no subquery, no JOIN.
- All existing single-hop tests pass unchanged; the L5/L6 pinning ensures no semantic drift.
- The two new diagnostic templates surface for translation-required and condition-step paths, with their test substrings asserted at L3.
- `@LoadBearingClassifierCheck` keys updated; `@DependsOnClassifierCheck` consumer annotations re-pointed in the same commit; `LoadBearingGuaranteeAuditTest` green.
- Howto article (or the existing nodeId/`@reference` docs page) gains a "Multi-hop identity-carrying filter" section with a worked example pulled from the new fixture via AsciiDoc `include::`/`tag::` markers, mirroring R110's no-drift mechanism. The example mirrors the Sak/Soknad/Opptak case from the problem statement.

## Roadmap entries

- **R114**: this item — multi-hop `@reference` path on `@nodeId` filter input fields, identity-carrying lift only.
- **Sibling Backlog (file at end of R114)**: non-identity-carrying multi-hop `@reference` on `@nodeId` — EXISTS-subquery or JOIN-with-translation emission. Symmetric to R57 (single-hop translated). Pin the load-bearing structure (which hop fails, which carrier shape) here when a real schema needs it.
