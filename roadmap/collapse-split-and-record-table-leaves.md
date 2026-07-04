---
id: R432
title: "Collapse SplitTableField and RecordTableField into one source-gated leaf"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [decompose-sourcekey]
created: 2026-07-04
last-updated: 2026-07-04
---

# Collapse SplitTableField and RecordTableField into one source-gated leaf

The R333 beachhead: collapse `ChildField.SplitTableField` and `ChildField.RecordTableField` into one
leaf gated on the source fact. Thread A measured the two component-identical (11 shared components,
both `TableTargetField` + `BatchKeyField`; only `emitsSingleRecordPerKey()` and `sourceShape()`
differ), and both child sides already lower to the same `load<X>` rows-method and fetcher; Split's
only extra, the parent-key projection, already lands via `collectRequiredProjectionColumns` in the
parent type's `$fields`. Collapsing with zero residue retires one cross-product axis with no
generator rewrite and produces the lowering's first executable proof (R333 "First slice").

The re-query unification fork this depended on was settled 2026-07-04 (R333 open questions): **full
merge, laundered key**. The keyed re-query is one primitive `f(keys, correlation)`; the source
endpoint contributes only how the key tuple is lifted (a locator read gated on the held object's
shape), never visible to the query unit; correlation is the FK column pairs for split and PK
self-identity for re-fetch (the degenerate case R333 already names). So the outcome is one leaf whose
record-sourced arm is the reentry case, not two leaves sharing machinery.

Scope notes for Spec pickup: the lookup twin (`SplitLookupTableField` / `RecordLookupTableField`)
mirrors the same merge; decide at Spec whether it rides along or follows as its own slice. **This
merge adds the parent-projection containment check**: a validate-time (or pipeline-tier) assertion
that, for a coordinate whose key tuple is lifted off the parent's held object, each key column
appears in the enclosing anchor's projection set (`collectRequiredProjectionColumns`); R425 is the
bug you get without it. This is a facts-level referential-integrity check, distinct from the level-1
closure oracle (which checks method-name resolution only) — naming the invariant without the check
would be exactly the false-invariant family the design principles warn about. Acceptance is execution-tier equivalence (same rows, same order) plus the `@classified` corpus
classifying unchanged; byte-for-byte generated-output equality is explicitly not required. Sequenced
after R431 (`decompose-sourcekey`); feeds R314.
