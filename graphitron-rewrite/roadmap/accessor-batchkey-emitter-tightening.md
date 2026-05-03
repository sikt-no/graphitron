---
id: R65
title: "Tighten accessor-derived BatchKey model and emitter coordination"
status: Ready
bucket: architecture
priority: 5
theme: model-cleanup
depends-on: []
---

# Tighten accessor-derived BatchKey model and emitter coordination

> Six independent architectural cleanups surfaced during the R60 reviewer pass on `claude/r60-progress-1uXXe`. R60 shipped functionally correct: classifier auto-derivation, two new `BatchKey.RecordParentBatchKey` permits, paired load-bearing keys, end-to-end execution-tier coverage. These follow-ups tighten the model/emitter coordination so the new permits read with the same generation-thinking discipline as the existing four. Any of them can land independently; none is a release blocker.

Each item is one finding. Items are ordered roughly by leverage — collapsing dead model state and unifying capability-style switches first; structural dedup of two reflection-walks below; small dead-code cleanup last.

---

## 1. `AccessorRowKeyedMany.Container` is a dead slot at emit time

The `Container { LIST, SET }` enum on `BatchKey.AccessorRowKeyedMany` was kept as part of the type after R60's emit collapsed: the for-loop over `Iterable` works for both `List<X>` and `Set<X>`, so no codegen forks on it. R60's roadmap entry (Deviation 3, captured in `changelog.md` for `R60`) admits the slot exists "for documentation."

**Why it's weaker.** A model component no consumer branches on inverts the generation-thinking principle ("Generators receive a model in terms of what to emit, not what to interpret"). Worse, it's a trap: a future reader assumes the enum is load-bearing and writes a fork against it, drifting from the actual emission.

**Change.** Drop the `Container` slot from `AccessorRowKeyedMany`. If a future feature needs the distinction (preserving order semantics, dedupe, parallel iteration), introduce the fork at that point with a real emit divergence behind it. Update the unit-tier and pipeline-tier cases that pin the enum value to no longer carry the assertion.

**Risk.** None at runtime; the slot is dead. The pipeline-tier case `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_SET_ACCESSOR` currently asserts `arm.container() == Container.SET`. After the slot drops, the case still pins the variant identity (`AccessorRowKeyedMany`) and the resolved accessor; the SET vs LIST difference becomes implicit in the schema's `@record` backing class.

---

## 2. `hasSingleSplitField` predicate is a boolean OR over two unrelated reasons

`TypeFetcherGenerator.java:542-546` (the `fields.stream().anyMatch` predicate gating `scatterSingleByIdx` emission) now reads:

```java
boolean hasSingleSplitField = fields.stream().anyMatch(f ->
    (f instanceof ChildField.SplitTableField stf
        && !stf.returnType().wrapper().isList())
    || (f instanceof ChildField.RecordTableField rtf
        && rtf.batchKey() instanceof BatchKey.AccessorRowKeyedMany));
```

**Why it's weaker.** The variable name says "single split field" but the predicate covers two structurally unrelated triggers — single-cardinality `SplitTableField` and `RecordTableField` with `AccessorRowKeyedMany`. Both answer the same operational question: "do I need to emit `scatterSingleByIdx`?" A third trigger in the future (e.g. a future `LifterRowKeyedMany`) requires a third disjunct here, plus the corresponding match in `SplitRowsMethodEmitter.buildForRecordTable`. The principle on capability interfaces vs sealed switches: capabilities express what is uniformly true, switches express what varies by identity. This site asks a uniform question of multiple variants.

**Change.** Add a capability `BatchKeyField#emitsSingleRecordPerKey()` (or a sub-interface like `SingleRecordPerKey extends BatchKeyField`) — `true` iff the rows-method shape is "1 record per key." `SplitTableField` returns `!returnType().wrapper().isList()`; `RecordTableField` returns `batchKey() instanceof BatchKey.AccessorRowKeyedMany`; future variants implement once. The site collapses to `fields.stream().anyMatch(BatchKeyField::emitsSingleRecordPerKey)`.

**Notes.** A related predicate lives in `SplitRowsMethodEmitter.buildForRecordTable` (lines 387-392): `rtf.batchKey() instanceof BatchKey.AccessorRowKeyedMany` routes into `buildSingleMethod` vs `buildListMethod`. It answers the narrower "is this `RecordTableField` 1-record-per-key?" question rather than the wider "do any siblings need `scatterSingleByIdx`?" question. The same capability covers both: the routing site reads `rtf.emitsSingleRecordPerKey()`, the helper-emission site reads `fields.stream().anyMatch(BatchKeyField::emitsSingleRecordPerKey)`.

---

## 3. `emitParentInputAndFkChain` `pkCols` switch needs `default -> throw` because the parameter is too broad

`SplitRowsMethodEmitter.java:162-170` switches over `BatchKey` (parameter type) but only the four `RecordParentBatchKey`-or-`RowKeyed` permits ever reach the prelude. The `@service`-only permits (`MappedRowKeyed`, `RecordKeyed`, `MappedRecordKeyed`) are excluded by upstream routing, but the parameter type doesn't carry that fact, so a `default -> throw IllegalStateException(...)` arm covers them.

**Why it's weaker.** Adding a sixth `BatchKey` permit elsewhere will silently route through the throw rather than failing at compile time. The four real arms are uniform projections — each calls a getter returning `List<ColumnRef>`, no per-arm shape divergence. Per the principle ("capabilities express what is uniformly true"), this is exactly the case capabilities exist for.

**Change.** Add `BatchKey#preludeKeyColumns()` — implemented by the four prelude-reachable variants (`RowKeyed`, `LifterRowKeyed`, `AccessorRowKeyedSingle`, `AccessorRowKeyedMany`) and **not** implemented by the `@service`-only ones. Or introduce a `HasPreludeKeyColumns` interface with the same shape. The prelude reads `bk.preludeKeyColumns()`; the catch-all throw goes away; new prelude-reachable variants implement the capability or fail to compile.

**Notes.** Same shape as the existing `ParentKeyed#parentKeyColumns()` and `LifterRowKeyed#targetKeyColumns()` accessors. The capability subsumes both: each delegates to its own column source. The two `@DependsOnClassifierCheck` annotations on the prelude (`lifter-classifies-as-record-table-field`, `lifter-batchkey-is-lifterrowkeyed`) can shrink to just the routing claim ("only RecordParentBatchKey or RowKeyed reach this site"), since the columns themselves come from a typed accessor instead of an `instanceof` chain.

---

## 4. Two classifier sites independently walk `Class<?> → (container, element)`

`FieldBuilder.classifyAccessorReturn` (new in R60, lines ~2940-2959) and `ServiceCatalog.classifySourcesType` (existing) both walk a `java.lang.reflect.Type` and classify it as `(container × element)` for a `TableRecord`-extending element class. The new helper's javadoc explicitly notes: "Mirrors `ServiceCatalog.classifySourcesType`'s container-and-element walk."

**Why it's weaker.** Two reflection-walking classifiers now answer overlapping questions. Both are correctly placed inside parse-boundary classes (FieldBuilder / ServiceCatalog are both permitted to hold raw reflection types), so the *boundary discipline* holds — but the *shape walk* duplicates. Any future tightening (e.g. `Optional<X>`, primitive arrays, records-of-records) lands in two places. Per the generation-thinking rule of thumb ("the same multi-arm switch recurs across multiple generators"), two consumers evaluating the same predicate over a `Class<?>` is a smell.

**Change.** Lift the container/element walk into a shared helper inside `ServiceCatalog` (the more general SOURCES classifier). Parameterise it with the element-class marker the caller cares about: SOURCES wants `RowN`/`RecordN`-or-`TableRecord`; the accessor path wants only `TableRecord`. Both call sites remain inside parse-boundary classes; the walk has one home.

**Notes.** `ServiceCatalog.classifySourcesType` already lives inside one of the four classes permitted to hold raw reflection types per `rewrite-design-principles.adoc`. Moving the helper there keeps the boundary intact.

---

## 5. Dispatch fork in `buildRecordBasedDataFetcher` is re-evaluated at two sites

`TypeFetcherGenerator.buildRecordBasedDataFetcher` carries a `boolean usesLoadMany = batchKey instanceof BatchKey.AccessorRowKeyedMany`, used to fork the loader value type, the result value type, and the dispatch string (`loader.loadMany(keys, ...)` vs `loader.load(key, env)`). The matching key-extraction in `GeneratorUtils.buildRecordParentKeyExtraction` separately decides whether the emitted local is named `key` (singular) or `keys` (plural). The contract that the two emit sites agree is held together by a load-bearing-classifier-check annotation; the structural coupling lives only in a comment.

**Why it's weaker.** Two emit sites both fork on the same `BatchKey instanceof AccessorRowKeyedMany` predicate. Per generation-thinking ("if two consumers evaluate the same predicate over a model field, the branch belongs in the model"), a future arm wanting `loadMany` (e.g. a future `LifterRowKeyedMany`) needs to teach two sites about the same answer.

**Change.** Add a typed projection on `BatchKey.RecordParentBatchKey` — `LoaderDispatch dispatch()` returning a sealed `LoadOne | LoadMany`. `buildRecordBasedDataFetcher` reads it once at the top; `buildRecordParentKeyExtraction` reads the same value when emitting the keying statement. The local name is implied by the dispatch: `LoadOne → key`, `LoadMany → keys`. The `@DependsOnClassifierCheck` annotation `accessor-rowkey-cardinality-matches-field` on the dispatcher can shrink, since the type system now carries the guarantee.

**Notes.** The capability `emitsSingleRecordPerKey` from item 2 is related but answers a different question (rows-method shape, not loader dispatch). Both projections may belong on the same model, or one may collapse into the other once both are typed.

---

## 6. Drop `AccessorPayloads.ListAccessorOnSingleField` (unused fixture kept "for symmetry")

`AccessorPayloads.java:74` declares a `ListAccessorOnSingleField` test fixture whose javadoc explicitly says: "this case is not used by the cross-product test; it exists for symmetry should additional matrix coverage be added."

**Why it's weaker.** Test fixtures kept "for symmetry" rot the same way speculative production code does. Either the matrix needs the case (add the test), or it doesn't (remove the fixture). Per the project's "don't introduce abstractions beyond what the task requires" rule.

**Change.** Delete the unused record. If a future addition wants the matrix corner, the fixture takes one line to add back alongside the test that needs it.

**Risk.** None — no test references it.

---

## Out of scope

- **Renaming `AccessorRowKeyedSingle` / `AccessorRowKeyedMany`.** The names accurately reflect cardinality at the variant level; nothing the items above requires a rename.
- **The `Single` permit's emitter wiring.** Already complete in R60; the execution-tier coverage gap is a separate concern tracked under the validator's Invariant #10 lift (single-cardinality `RecordTableField` rejection).
- **`RecordBatchKeyResolution` and the `AccessorDerivation` / `AccessorMatch` two-stage builder hierarchy.** Clean applications of the "Builder-step results are sealed" rule; no change needed.
