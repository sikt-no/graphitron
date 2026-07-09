---
id: R452
title: "Reject or emit parent-FK WHERE for condition/multi-hop @reference paths on multitable polymorphic child fields"
status: Backlog
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

`MultiTablePolymorphicEmitter.branchParentFkWhere` (`MultiTablePolymorphicEmitter.java:1153-1158`) returns `null` whenever join-path step 0 is not an `FkJoin`, or when `slotCount() == 0`. The single-cardinality caller `buildStage1Block` (`:1114-1130`) treats a `null` WHERE as "no filter", so the stage-1 UNION branch selects the participant's whole table and hands back `result[0]`. Multi-hop paths fare no better: only hop-0 slots are emitted, against the wrong alias.

Nothing upstream rejects the shape:

- `FieldBuilder.resolveChildPolymorphicJoinPaths` (`FieldBuilder.java:6274-6288`) checks only `parsed.hasError()`; unlike the single-table arm (`validateSingleHopFkJoin` at `:944`, `terminalTargetVerdict` at `:937`) it applies no shape gate.
- `GraphitronSchemaValidator.validateInterfaceField` / `validateUnionField` (`:797-808`) never inspect `participantJoinPaths`, and `validateReferencePath` (`:1204-1206`) is a no-op whose comment wrongly asserts the classifier already rejected unsupported shapes.

The batched (list-cardinality) sibling at `:1744` blind-casts `(JoinStep.FkJoin) path.get(0)` and throws `ClassCastException` loudly, so list cardinality fails safe while single cardinality fails silently — an asymmetry that proves the single arm is the odd one out.

## Why it is reachable

`BuildContext.fkCountMessage` (`:1474-1479`) explicitly advises authors to use condition paths or multi-hop key chains **when FK auto-discovery fails** — i.e. the build steers authors directly into the silent-wrong-data shape. The emitter Javadoc (`:1080-1082`) rationalizes the FK-only limit as "auto-discovery only produces single-hop FK paths", which explicit directive paths bypass. No test covers explicit `@reference` on a multitable child field.

## Fix direction (for Spec)

Two honest options, to be decided at Spec:

1. **Reject at build time** — add a shape gate (in `resolveChildPolymorphicJoinPaths` and/or the interface/union validators) that turns an unsupported participant join path into a typed rejection naming the field, matching the deny-by-default posture the validator exists to enforce. Smallest, safest; closes the silent hole immediately.
2. **Emit the correct WHERE** — teach `branchParentFkWhere` to lower condition-join and multi-hop paths into the per-branch parent predicate. Larger; delivers the feature the author asked for rather than refusing it.

At minimum the silent `null`-means-no-WHERE path must stop producing arbitrary data on a green build. Note the fail-safe precedent already present in the list-cardinality sibling.

## Relationship to other items

Distinct from R382 (lowering `orderBy` onto polymorphic *queries*) and R76 (per-participant `fieldsJoin`/`orderBy` emission refactor): both concern the root-query UNION shape, not the child-field parent-FK predicate. `dimensional-model-pivot.md` mentions `participantJoinPaths` only as a modeling concern. This item is the child-field data-correctness gap specifically.

Confirmed high severity by the architecture-trap audit (adversarially verified, including an end-to-end scratch pipeline test that reproduced the arbitrary-row result on a green build).
