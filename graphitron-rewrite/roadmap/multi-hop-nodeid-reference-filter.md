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
- For length ≥ 2, run the lift predicate against each adjacent step pair. On failure, return a rejection (see "Diagnostics" for the exact text and constant name).

`NodeIdLeafResolver.resolve`:

- The `DirectFk` vs `TranslatedFk` decision (line 272: `sameColumnsBySqlName(fkJoin.targetSideColumns(), keys.keyColumns())`) switches from `joinPath.get(0)` to `joinPath.getLast()`. The terminal hop is the one whose target-side columns must positionally match the NodeType key columns; intermediate hops are constrained by the lift predicate above.
- The lifted tuple is computed once at the resolver and rides through to the carriers. Add a slot `liftedSourceColumns: List<ColumnRef>` on `Resolved.FkTarget.DirectFk`. For length-1 paths, `liftedSourceColumns == joinPath.get(0).sourceSideColumns()` — backward-compatible.

**Carriers grow the same slot.** `InputField.ColumnReferenceField`, `InputField.CompositeColumnReferenceField`, `ArgumentRef.ScalarArg.ColumnReferenceArg`, and `ArgumentRef.ScalarArg.CompositeColumnReferenceArg` each gain a `liftedSourceColumns: List<ColumnRef>` slot, set at carrier construction (`FieldBuilder.classifyArgument`, `BuildContext.classifyInputField`) from `direct.liftedSourceColumns()`. The emitter reads this slot directly and never re-walks `joinPath` to compute the lift. This keeps the resolver's decision the single source of truth: classifier guarantees in, validator and emitters out, no duplicated computation.

The existing `column` / `columns` slot continues to hold the NodeType key column tuple (unchanged semantics). A pre-existing naming asymmetry — `column` reads as "the column the predicate fires against" but actually holds NodeType keys, while the predicate LHS comes from `joinPath` — is widened by the new slot. This is acknowledged as a pre-existing condition; renaming `column` / `columns` to a role-explicit name (`decodedKeyColumns`, etc.) is filed as a sibling Backlog hygiene item rather than absorbed into R114, because the rename touches every existing single-hop consumer and conflates with the multi-hop scope.

The spec's previous draft proposed "no carrier-shape change", which is mutually exclusive with "computed once at the resolver"; the carrier-shape change is the load-bearing decision that enables the no-recomputation property.

## Emitter change

`FieldBuilder.projectFilters` (`ColumnReferenceArg` and `CompositeColumnReferenceArg` arms) and `FieldBuilder.walkInputFieldConditions` (`InputField.ColumnReferenceField` and `CompositeColumnReferenceField` arms): swap `((JoinStep.FkJoin) joinPath().get(0)).sourceSideColumns()` for `liftedSourceColumns()` read from the carrier. The `BodyParam.{Eq,In,RowEq,RowIn}` projection is otherwise unchanged.

`TypeConditionsGenerator.buildConditionMethod`: no change. The emitted SQL is the same `field.eq/in(...)` / `DSL.row(...).eq/in(...)` shape as single-hop today — only the column tuple differs.

The carrier hierarchy (`InputField.*`, `ArgumentRef.*`, `BodyParam.*`) gets no new variants. Multi-hop is encoded in `joinPath.size() > 1` plus the lifted-tuple slot on the resolver result; emitter sites read the lifted tuple uniformly. The variant-identity-tracks-shape rule still holds: the user-visible shape (lifted column tuple → row predicate) is the same shape single-hop direct-FK already emits. No new variant means no new emitter, validator, or test surface for the SQL projection itself; the new behaviour is concentrated in the resolver's lift computation.

## Diagnostics

Both rejection templates are exposed as `static final String` constants in `NodeIdLeafResolver` (e.g. `LIFT_FAILURE_MARKER`, `CONDITION_STEP_MARKER`); the marker names are imported by the L1/L3 tests rather than asserted as quoted prose. This is the diagnostic-anchoring step beyond R57's substring precedent: copyediting the user-facing message text leaves the marker — and therefore the test — intact. (The wider migration of R57's substring-based assertions is filed as a sibling hygiene item.)

The full message templates land at the constant sites (paraphrased here):

- **Lift failure** (`LIFT_FAILURE_MARKER = "identity-carrying FKs"` is the load-bearing token):
  `"@reference path on @nodeId leaf '<leafName>': hop <i> ('<fkName>') introduces a column translation — its source-side columns <[sql-names]> are not a positional subset of the previous hop's target-side columns <[sql-names]> by SQL name. Multi-hop @reference on @nodeId currently requires identity-carrying FKs at every step (the predicate compiles to a single-table SELECT). See <howto-link>."`
- **Non-FK step inside multi-hop** (`CONDITION_STEP_MARKER = "must be a foreign key"`):
  `"@reference path on @nodeId leaf '<leafName>': step <i> is a condition step; every step in a multi-hop @nodeId path must be a foreign key (use { key: ... } at every position)."`

Both messages route through `Resolved.Rejected` from the resolver and surface via `InputFieldResolution.Unresolved` (input-field side) or `ArgumentRef.UnclassifiedArg` (argument side), the same paths existing single-hop rejections take. The "see howto" pointer lands the author on the mental-model section of the howto article (see "Howto" below) so the rejection has prepared ground.

## @LoadBearingClassifierCheck / @DependsOnClassifierCheck

Two distinct keys, not one widened key. Each guarantees an independent invariant; future relaxations (e.g. translation-tolerant emitters) want to relax them one at a time, and the audit's job is to pinpoint which invariant a change breaks.

- **`nodeid-fk.direct-fk-keys-match`** widens from "FK target-side columns positionally match NodeType key columns" to "**the terminal hop's** target-side columns positionally match NodeType key columns". Producer: `NodeIdLeafResolver.resolve` (the `joinPath.getLast()` switch). Consumers stay the same (`FieldBuilder.projectFilters`, `FieldBuilder.walkInputFieldConditions`, `BuildContext.classifyInputField`); their `reliesOn:` text refreshes to mention "terminal hop".

- **`nodeid-fk.identity-carrying-lift`** is new. Invariant: every intermediate hop satisfies the lift predicate (each step's source-side columns are a positional subset of the previous step's target-side columns by SQL name), so the lifted tuple computed by the resolver is well-defined and lives on the parent's own table. Producer: `NodeIdLeafResolver.resolveFkJoinPath`. Consumers: `FieldBuilder.projectFilters` and `FieldBuilder.walkInputFieldConditions` (both `ReferenceArg` arms / both `ReferenceField` arms — they read `liftedSourceColumns` and pass it to `BodyParam.{Eq,In,RowEq,RowIn}`). `LoadBearingGuaranteeAuditTest` picks up the pairing automatically via the annotation.

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

## Howto

The howto article is part of the deliverable, not a follow-on. Concrete pin:

- **File**: `docs/manual/how-to/multi-hop-nodeid-filter.adoc` (sibling to R110's `source-row.adoc`).
- **Parent registration**: appended to the how-to index in `docs/manual/index.adoc` alongside R110's entry.
- **No-drift mechanism**: the worked example pulls from the new fixture (`nodeidfixture` chain — see "Fixture" below) via AsciiDoc `include::` with `tag::multi-hop-identity-carrying[]` / `tag::multi-hop-rejected-translation[]` markers in the SDL `.graphqls` file, mirroring R110's `tag::sourcerow-story-1[]` precedent.
- **Section order, mental-model first**: the article opens with "Why identity-carrying" — multi-hop `@nodeId` filters compile to a single-table predicate (no subquery, no JOIN), which is only well-defined when each step preserves the next step's source-side columns positionally by SQL name. The worked example follows. The "Rejection messages" section then names the two diagnostic markers (`LIFT_FAILURE_MARKER` / `CONDITION_STEP_MARKER`) and links each to a section that explains what the author should change. The diagnostic message's "see howto" pointer (see "Diagnostics") targets the rejection-messages section directly.

This ordering closes the author-ergonomics gap noted by the architect review: single-hop `@reference` works on any FK, but multi-hop quietly carries a precondition the SDL syntax does not advertise. Leading the howto with the mental model means the rejection message lands on prepared ground rather than on an author who reads "introduces a column translation" cold.

## Acceptance criteria

- `@reference(path: [...])` of length ≥ 2 on `@nodeId(typeName: T)` filter input fields and arguments is accepted by the classifier when (a) every step is a `FkJoin`, (b) the lift predicate holds at every adjacent pair, and (c) the terminal hop's target-side columns positionally match `T.keyColumns`.
- The generated `<TypeName>Conditions` method emits a direct row predicate (`field.eq`/`field.in` for arity 1; `DSL.row(...).eq/.in` for arity ≥ 2) against the lifted tuple on the parent's own table — no subquery, no JOIN.
- All existing single-hop tests pass unchanged; the L5/L6 pinning ensures no semantic drift.
- Diagnostic message text is exposed via `static final String LIFT_FAILURE_MARKER` / `CONDITION_STEP_MARKER` on `NodeIdLeafResolver`; L1/L3 tests assert against the constants by name (no quoted prose).
- `@LoadBearingClassifierCheck` covers the two distinct keys (`nodeid-fk.direct-fk-keys-match` widened to "terminal hop", new `nodeid-fk.identity-carrying-lift`); `@DependsOnClassifierCheck` consumer annotations updated in the same commit; `LoadBearingGuaranteeAuditTest` green.
- Carriers (`InputField.{Column,CompositeColumn}ReferenceField`, `ArgumentRef.ScalarArg.{Column,CompositeColumn}ReferenceArg`) gain a `liftedSourceColumns: List<ColumnRef>` slot. Single-hop carrier construction populates it as `joinPath.get(0).sourceSideColumns()`; this is the backward-compatible path.
- Howto article at `docs/manual/how-to/multi-hop-nodeid-filter.adoc` ships in the same change set, with mental-model section, two `tag::` markers in the fixture SDL, and rejection-messages section linked from the diagnostic text.

## Roadmap entries

- **R114**: this item — multi-hop `@reference` path on `@nodeId` filter input fields, identity-carrying lift only.
- **Sibling Backlog (file at end of R114)**:
  - **Non-identity-carrying multi-hop `@reference` on `@nodeId`** — EXISTS-subquery or JOIN-with-translation emission. Symmetric to R57 (single-hop translated). Pin the load-bearing structure (which hop fails, which carrier shape) here when a real schema needs it.
  - **`column` / `columns` slot rename** on `InputField.ColumnReferenceField` / `CompositeColumnReferenceField` and `ArgumentRef.ScalarArg.ColumnReferenceArg` / `CompositeColumnReferenceArg`. The slot holds NodeType key columns on the *target* table, but the name reads as "the predicate column" (which actually comes from `joinPath` / `liftedSourceColumns`). Rename to a role-explicit name (e.g. `decodedKeyColumns`) once R114 lands; the rename touches every existing single-hop consumer and is a hygiene step, not a behaviour change.
  - **Diagnostic-anchoring policy migration**. R114 introduces `static final String` markers on `NodeIdLeafResolver` for its two new diagnostics; R57 and earlier rejection sites still anchor on quoted prose substrings. File a tiny item to migrate the existing handful of substring-based assertions to constant markers, so the convention is uniform across the resolver / classifier surface.
  - **`Resolved.FkTarget.DirectFk.fkSourceColumns()` vestige.** This slot is set at `NodeIdLeafResolver.java:152, 275` but never read by either carrier-construction site (`FieldBuilder.classifyArgument`, `BuildContext.classifyInputField` both consume `joinPath` and `keyColumns`). After R114 lands, the slot's role is fully covered by `liftedSourceColumns` on the carrier; the producer-with-no-consumer slot can either be deleted or kept as a structural mirror of the new slot. Hygiene item, not blocking.
