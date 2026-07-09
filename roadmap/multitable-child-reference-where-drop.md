---
id: R452
title: "Reject or emit parent-FK WHERE for condition/multi-hop @reference paths on multitable polymorphic child fields"
status: Spec
bucket: bug
priority: 1
theme: interface-union
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Reject or emit parent-FK WHERE for condition/multi-hop @reference paths on multitable polymorphic child fields

## Symptom

A single-cardinality multitable-interface (or union) child field whose join path is an explicit `@reference(path: [{condition: ...}])` or a multi-hop key chain generates a fetcher that returns an **arbitrary participant row per parent**, silently ignoring the parent relationship. The build is green: classification, validation, and generation all succeed. Every parent resolves the field to the same wrong data with no error at any tier.

## Mechanics

`MultiTablePolymorphicEmitter.branchParentFkWhere` (`MultiTablePolymorphicEmitter.java:1154-1179`) returns `null` whenever join-path step 0 is not a `JoinStep.Hop` carrying `On.ColumnPairs`, or when `slotCount() == 0`. The single-cardinality caller `buildStage1Block` (`:1089-1137`) treats a `null` WHERE as "no filter", so the stage-1 UNION branch selects the participant's whole table and hands back `result[0]`. Multi-hop paths fare no better: only hop-0 slots are emitted, against the wrong alias.

Nothing upstream rejects the shape:

- `FieldBuilder.resolveChildPolymorphicJoinPaths` (`FieldBuilder.java:6717-6731`) checks only `parsed.hasError()`; unlike the single-table arm (`validateSingleHopFkJoin` at `:963`, `terminalTargetVerdict` at `:956`) it applies no shape gate and ignores the parsed path's terminal-target verdict.
- `GraphitronSchemaValidator.validateInterfaceField` / `validateUnionField` (`:943-954`) never inspect `participantJoinPaths`, and `validateReferencePath` (`:1350`) is a no-op whose comment asserts the classifier already rejected unsupported shapes.

The batched (list-cardinality) sibling at `:1746` blind-casts `(On.ColumnPairs) ((JoinStep.Hop) path.get(0)).on()` and throws `ClassCastException` loudly, so list cardinality fails safe while single cardinality fails silently — an asymmetry that proves the single arm is the odd one out.

## Why it is reachable

`BuildContext.fkCountMessage` (`BuildContext.java:1710`) explicitly advises authors to use condition paths or multi-hop key chains **when FK auto-discovery fails** — i.e. the build steers authors directly into the silent-wrong-data shape. The emitter Javadoc (`:1079-1083`) rationalizes the FK-only limit as "auto-discovery only produces single-hop FK paths", which explicit directive paths bypass. No test covers explicit `@reference` on a multitable child field.

## Decision

**Reject at build time** (option 1 of the Backlog body). Option 2 (emit a correct WHERE for condition/multi-hop paths) is not structurally expressible today: `resolveChildPolymorphicJoinPaths` parses the field's *single* `@reference` path once per participant with that participant's table as target, so one stated path can be terminal-correct for at most one participant. Per-participant explicit paths need new syntax; the legacy `@multitableReference(routes:)` directive was exactly that mechanism and was deliberately retired (see `docs/manual/reference/directives/multitableReference.adoc`). The capability ships as a follow-up item (see Roadmap entries), not as a corner of this bug fix.

Beyond the shapes the Backlog body names, the research found a third silent-wrong-data door: a participant whose table equals the parent/hub table produces an *empty* auto-discovered path (`parsePath` skips FK discovery when source and target tables match), which also lowers to "no WHERE". The gate closes it too.

## Design

Architect-reviewed (principles consult, 2026-07-09). The single highest-leverage move is a type lift: carry the *resolved* parent-FK slot pairs per participant instead of a raw `List<JoinStep>`, so the classifier decides "supported shape" exactly once and the emitter cannot represent an unsupported one.

### 1. Classification gate in `resolveChildPolymorphicJoinPaths`

This method (`FieldBuilder.java:6717`) is the single choke point for all four producers: interface and union, table-backed parent (`FieldBuilder.java:979`/`:1003`) and record-backed parent (`:6086`).

a. **Reject any `@reference` application on the field** (structural `Rejection`, author-correctable by removing the directive). Message names the field, states that per-participant join paths on multi-table interface/union child fields are auto-discovered (one unique single-hop FK from each participant table to the parent table) and that a field-level `@reference` cannot express per-participant joins. This subsumes condition paths, multi-hop chains, and explicit `{key:}` hops in one clear author-facing rule.

b. **Per-participant shape check** after `parsePath`: the resolved path must be exactly one `JoinStep.Hop` whose `on()` is `On.ColumnPairs` with at least one slot. With (a) in place, its practical trigger is the same-table participant (empty path); give that its own message keyed to the actual cause (participant table equals parent table, so no FK correlation is derivable), using the deferred rejection arm carrying the follow-up item's slug — a self-FK participant is a legitimate schema the follow-up can serve, not an author error.

c. **Wrap per-participant `parsePath` errors with multitable-child context.** `BuildContext.fkCountMessage` (`BuildContext.java:1710`, `directiveAbsent=true`) advises adding `@reference` when auto-discovery finds zero or multiple FKs; on these fields that advice now leads straight into rejection (a). Keep `fkCountMessage` generic, but have `resolveChildPolymorphicJoinPaths`'s existing `"participant 'X': "` wrapper append that `@reference` is not supported on multi-table child fields and point at the follow-up item's capability instead.

d. **Single-source the shape predicate.** The single-table sibling `validateSingleHopFkJoin` (`FieldBuilder.java:6764`) hand-codes the same "exactly one FK hop" test; extract one shared predicate both arms call (messages stay per-arm). While there, fix its stale pointer to `stub-interface-union-fetchers.md` (file no longer exists; R36 shipped).

### 2. Type lift: carry resolved FK slots, not raw join paths

Change the per-participant carrier on `ChildField.InterfaceField` / `ChildField.UnionField` (`ChildField.java:702`/`:733`, `participantJoinPaths`) from `Map<String, List<JoinStep>>` to a map of a narrow resolved type carrying the ordered `(parentSide, participantSide)` column-pair slots — `On.ColumnPairs` directly, or a small dedicated record if a name helps; non-empty slots enforced at construction. Thread the type through `TypeFetcherGenerator` (`:587-608`) and `MultiTablePolymorphicEmitter`'s three forms (single `buildStage1Block`/`branchParentFkWhere` at `:1089`/`:1154`, batched list and batched connection via `batchedBranchJoinPredicate` at `:1744`).

Consequences, each retiring a guard the Backlog body complained about:

- `branchParentFkWhere` consumes slots directly: no `instanceof`, no silent `null`-for-unsupported-shape arm. `null` remains only for "participant absent from the map" (root-fetcher form), which stays legitimate.
- `batchedBranchJoinPredicate`'s blind cast `(On.ColumnPairs) ((JoinStep.Hop) path.get(0)).on()` disappears; the accidental `ClassCastException` enforcer retires in favour of the type.
- No defensive emit-time throw is needed: the unsupported shape is unrepresentable in the emitter's input. The pipeline tests are the regression guard on the gate itself.

### 3. Comment corrections

- Emitter Javadoc (`MultiTablePolymorphicEmitter.java:1079-1083`): "multi-hop chains and condition-joins are not supported in v1 (the classifier's auto-discovery only produces single-hop FK paths)" — rewrite to state the classifier *rejects* everything but the auto-discovered single FK hop and the carrier type enforces it.
- `GraphitronSchemaValidator.validateReferencePath` (`:1350`) stays a no-op, but re-read its "builder rejects unresolved paths at classification time" comment against the new gate and make it name what the multitable arm now guarantees.

## User documentation

`docs/manual/how-to/polymorphic-types.adoc` already calls auto-discovered per-branch FK paths "the supported multi-table-child idiom" (constraints bullet, ~line 122). Extend that bullet: an explicit `@reference` on a multi-table interface/union child field is rejected at build time, with a one-line pointer to what the rejection message says. Check the migration bullet list in `multitableReference.adoc` ("`@reference` on the field describes the FK-driven join") still reads correctly for the multi-table child case; qualify it if it can be read as endorsing the rejected shape.

## Tests

Pipeline tier, `RecordParentMultiTablePolymorphicPipelineTest` style (SDL fixture against the sakila catalog, classify, assert on the field record / rejection — no generated-body string assertions):

- `@reference(path: [{condition: ...}])` on a multitable interface child field → structural rejection naming the field (rule 1a).
- Multi-hop `@reference` key chain → same rejection.
- Explicit single-hop `{key:}` → same rejection (the rule is directive presence, not path shape).
- Union-typed and record-backed-parent variants of one rejection case each (all four producer arms covered between them).
- Same-table participant (empty auto-path) → deferred rejection with the cause-specific message (rule 1b).
- Zero-FK and two-FK auto-discovery failure → error text carries the multitable-child context wrapper, not a bare "add a @reference directive" steer (rule 1c).
- Control: the existing auto-discovered fixtures keep classifying; existing `RecordParentMultiTablePolymorphicPipelineTest` / execution-tier multi-table coverage stays green. Update its `participantJoinPaths()` shape assertions to the new carrier type.

## Roadmap entries

File a Backlog item (theme `interface-union`) for the real capability: per-participant explicit join paths on multi-table child fields — serving multi-FK disambiguation, condition joins, multi-hop chains, and same-table self-FK participants; open design question is the syntax (field-level `@reference` cannot express it; the retired `@multitableReference(routes:)` is prior art). The deferred rejection in 1b and the context wrapper in 1c reference its slug.

## Out of scope

- Emitting a correct parent WHERE for condition/multi-hop/per-participant paths (the follow-up capability item).
- Reworking `fkCountMessage`'s generic wording for its other call sites.
- R382 / R76 root-query UNION concerns (see below).

## Relationship to other items

Distinct from R382 (lowering `orderBy` onto polymorphic *queries*) and R76 (per-participant `fieldsJoin`/`orderBy` emission refactor): both concern the root-query UNION shape, not the child-field parent-FK predicate. `dimensional-model-pivot.md` mentions `participantJoinPaths` only as a modeling concern. This item is the child-field data-correctness gap specifically.

Confirmed high severity by the architecture-trap audit (adversarially verified, including an end-to-end scratch pipeline test that reproduced the arbitrary-row result on a green build).
