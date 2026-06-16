---
id: R317
title: "Inline type classification into the field-first walk (retire TypeBuilder.buildTypes)"
status: Backlog
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [field-first-classification-driver]
created: 2026-06-16
last-updated: 2026-06-16
---

# Inline type classification into the field-first walk (retire TypeBuilder.buildTypes)

R279 made the field-first walk the sole classifier and collapsed `TypeRegistry` to a single
`register` verb, but it stopped short of the "true single-pass DFS fold" its slice-3b honest scope
named "approach A" and explicitly deferred. Today `TypeBuilder.buildTypes` still hosts a *type-classify
loop* that iterates the walk's discovered reachable set (`SchemaReachability.reachableTypeNames`) and
calls `classifyType` per type, plus the narrowed non-output-composite sweep, the second
participant-enrichment pass, and the post-passes (`promoteSingleRecordPayloads`,
`validateNodeTypeIdUniqueness`, `surfaceMultiProducerRejections`, `emitDirectiveIgnoredWarning`). So
the driver is field-first in *which types it classifies* (reachability-pruned) but the type verdict is
still computed in a separate loop keyed off the reachable-name set, not registered at the field edge
that reaches the type. Approach A folds that loop into the field walk itself: each field visit
registers its target type's classification as a byproduct (the model's "fields drive types"), so
`TypeBuilder.buildTypes` as a standalone type pass disappears and the reachable-name set is no longer
materialised as an intermediate. This is a behaviour-preserving simplification over the
already-inverted R279 driver, not a behavioural change; the risk is reordering the delicate post-passes
(node-typeId uniqueness, multi-producer rejection, directive-ignored warning order) that R279's slice
3b deliberately kept as registry post-passes for exactly this reason. Gate, as for every R279 slice:
the `GraphitronSchemaBuilderTest` truth table + sakila pipeline tiers, byte-identical output. Worth
doing only if it measurably simplifies the driver; if the post-pass reordering cost outweighs the
buildTypes deletion, the item can be discarded with that finding recorded.
