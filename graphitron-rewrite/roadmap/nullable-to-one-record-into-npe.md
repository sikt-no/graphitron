---
id: R269
title: Null-guard split-query key extraction for nullable to-one records
status: Ready
bucket: structural
depends-on: []
created: 2026-06-01
last-updated: 2026-06-30
---

# Null-guard split-query key extraction for nullable to-one records

The record-parent split-query key extraction `GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany` (`GeneratorUtils.java:318-360`) reads a nested jOOQ record off the parent backing and calls `.into(<PK columns>)` on it with no null guard: `ElementRecord element = ((Backing) env.getSource()).accessor(); RecordN<...> key = element.into(T.PK1, T.PK2)`. When the accessor returns null (a nullable to-one `@table` relation that resolves to no row on an otherwise successful parent), this NPEs with `Cannot invoke "...Record.into(...)" because "element" is null` instead of resolving the field to null. The sibling table-parent path `buildKeyExtractionWithNullCheck` (`GeneratorUtils.java:393-440`) already short-circuits with `CompletableFuture.completedFuture(null)` when a key component is null; the accessor path never got the equivalent guard. This is independent of the error channel (it fires on the success arm, with no `@error` payload involved) and was split out of R268, which fixes only the error-arm case via the `Outcome` arm-switch (on the `ErrorList` arm the loader is never dispatched, so the key extraction is not reached). Scope: add the null short-circuit to the accessor key-extraction helpers; pin with an execution-tier fixture where a nullable to-one `@table` field resolves null on a non-error parent and the field renders null rather than throwing.

## The shape that NPEs

A reflection-derived record-backed parent (a Java pojo / generated bean, classified as `PojoResultType.Backed` or `JavaRecordType`; the backing is inferred from the producing field per R276, not declared by a directive) exposes a child `@table` relation through an instance accessor rather than an FK column. `FieldBuilder` classifies that child as a `BatchKeyField` (`ChildField.RecordTableField` / `RecordLookupTableField`) with a `SourceKey` whose reader is `Reader.AccessorCall` (constructed at `FieldBuilder.java:5453` and `:5769`): the DataLoader key is the element table's PK tuple, lifted off the nested record the accessor returns. `TypeFetcherGenerator.buildRecordBasedDataFetcher` (`TypeFetcherGenerator.java:5878`) routes this through `DataLoaderFetcherEmitter.build`, whose key-extraction `CodeBlock` is supplied by `GeneratorUtils.buildRecordParentKeyExtraction` → `buildAccessorKeySingle` (cardinality `ONE`) or `buildAccessorKeyMany` (`MANY`).

The `ONE` arm (the reported bug) emits:

```java
ElementRecord element = ((Backing) env.getSource()).accessor();
RecordN<...> key = element.into(Tables.T.PK1, Tables.T.PK2);   // NPE when element is null
return loader.load(key, env);
```

A nullable to-one relation that resolves to no row hands the accessor a `null`, and `.into(...)` dereferences it. There is no error payload in play; this is a perfectly ordinary "the related row doesn't exist" outcome that should resolve the field to `null`.

The `MANY` arm has the analogous hazard one level out: `for (ElementRecord element : ((Backing) env.getSource()).accessor())` NPEs when the accessor returns a `null` *collection* (an unpopulated to-many backing), before any `.into(...)` runs.

## The fix

Mirror the existing FK-side precedent. `buildKeyExtractionWithNullCheck` already establishes the contract and the exact return expression for this fetcher shape: when a key component is null, `return CompletableFuture.completedFuture(null)` before the loader is touched ("a NULL FK can never match `terminal.pk = parentInput.fk_value`, so dispatching to the DataLoader is a wasted round-trip"). The dispatch for the single arm is `LOAD_ONE` → `return loader.load(key, env)` and the fetcher's declared return is `CompletableFuture<DataFetcherResult<Record>>`, so `completedFuture(null)` is assignable and resolves the field to null. The accessor path wants the same short-circuit, keyed on the nested record being null rather than an FK column being null.

- **`buildAccessorKeySingle`** ; between the `element = ...accessor()` read and the `element.into(...)` key build, emit `if (element == null) return CompletableFuture.completedFuture(null);`. One statement; same return expression as the sibling helper.
- **`buildAccessorKeyMany`** ; guard the accessor result before the `for`. A null collection short-circuits to "no keys", which the existing `loadMany(keys, ...)` dispatch already renders as an empty list (the natural rendering for a to-many relation with nothing on the other side). Emit `var elements = ((Backing) env.getSource()).accessor(); if (elements == null) return CompletableFuture.completedFuture(...);` *or* skip the loop body so `keys` stays empty and the existing dispatch runs over the empty list. See the design fork below for which.

Element-level nulls *inside* a non-null collection stay unguarded: a `null` sitting in a populated to-many backing is a malformed parent, not a legitimate "no row" outcome, and swallowing it would hide a consumer bug rather than model a real cardinality.

**The two arms guard at different granularities, deliberately.** The `ONE` arm preserves a distinction (a null nested record renders `null`, i.e. null-vs-present is meaningful), while the `MANY` arm collapses one (a null collection and an empty collection both render `[]`, i.e. null-vs-empty is *not* meaningful at the field's surface). That asymmetry is a choice, not an oversight: a to-one's "no row" has a faithful surface rendering (`null`), whereas a to-many has no surface difference between "never populated" and "populated with zero rows" once the loader returns. The implementer should keep this asymmetry stated rather than presenting both arms as "the same guard". If a future caller needs null-collection to mean something other than empty-list, that is a model-level nullability decision, not an emit-time guard tweak.

## Convergence note (not a blocker; flag at In Progress)

After this slice, three helpers reachable from the record-parent path emit the same `return CompletableFuture.completedFuture(null)` short-circuit: `buildKeyExtractionWithNullCheck` (FK columns null), `buildAccessorKeySingle` (nested record null), and the contract is identical even though the null predicate differs ("a key that can't match the terminal PK must not dispatch the loader; resolve to null"). R269 deliberately emits its guard as a local one-liner mirroring the FK helper rather than introducing a shared seam, because consolidating three emit sites is a refactor with its own blast radius and is not what this bug-fix slice is for. But the duplication is real and drift-prone: name it here so it is not accreted silently. It is the natural companion to R268's source-binding consolidation (R268 threads the read *source* as one value; the null-*guard* is the other half of that prelude). A shared "null key → `completedFuture(null)`" emit helper that both the FK and accessor arms call is the obvious later move; left out of scope here, called out so a future item can pick it up.

## Implementation seams

1. **`GeneratorUtils.buildAccessorKeySingle`** (318-336) ; add the `if (element == null) return CompletableFuture.completedFuture(null);` line. `CompletableFuture` is already referenced by the sibling helper; reuse the same `ClassName.get("java.util.concurrent", "CompletableFuture")` constant/import path.
2. **`GeneratorUtils.buildAccessorKeyMany`** (338-365) ; guard the collection per the fork below.
3. **No model or classifier change ; but the guard does rely on a classifier-known fact.** The guard is purely emit-time; `SourceKey`, `AccessorRef`, and the `BatchKeyField` permits are untouched, and the fetcher's outer return type and dispatch line are unchanged ; only the key-extraction `CodeBlock` grows a short-circuit, exactly as the FK-side helper already does. The fact the guard exists *for* is "a nullable to-one `@table` accessor parent can hand the fetcher a null nested record", which is a nullability fact the classifier already carries on the field. No live test pins the correspondence between that classification and the guard, so carry a short `{@link}` (or a one-line comment) from the guarded helper back to the nullability source on the field/`SourceKey`, so that if a future change makes such a field non-null the guard is visibly dead code rather than silently orphaned. This is the lightweight producer/consumer-linkage mechanism the design principles prescribe when there is no validator invariant to mirror.

## Design fork for In Progress (flag to principles review)

The `MANY` guard has two faithful renderings of "null to-many collection":

- **(a) skip the loop, dispatch over empty `keys`** ; `loader.loadMany([], [])` returns a completed empty list, so the field renders `[]`. Smallest diff, keeps a single dispatch line, and matches the "no related rows" reading of a to-many.
- **(b) early `return CompletableFuture.completedFuture(...)`** with an empty/`null` payload before the loader ; symmetric with the single arm's early return and avoids a no-op loader round-trip, but introduces a second return path and a choice between empty-list and null for the list payload.

Recommendation: **(a)**. It is the smaller change, avoids deciding empty-vs-null for the list shape (the loader's empty result already is the answer), and a single skipped iteration is not a round-trip worth the extra return path. The single arm's early `completedFuture(null)` stays as-is because there is no loop to skip and `null` is the unambiguous to-one rendering. Confirm with `principles-architect` before the `MANY` arm lands; if a `MANY` accessor fixture proves expensive to stand up, the `MANY` guard may narrow to a follow-up, but the `ONE` guard plus its fixture is the irreducible core of this slice.

## Coordination with R268 (resolved ; R268 shipped)

R268 shipped (`fc365b4` + `f4ae047`), so the landing-order fork this section once carried is settled. The two helpers are now source-bound: they read from a threaded `sourceExpr` (`success.value()` under an `Outcome.Success<X>` narrowing, or `env.getSource()` for a non-outcome parent) rather than `env.getSource()` directly. R271 separately retired the `__elt` / `__k` dunders to readable `element` / `key` locals. R269 changes only whether the source-bound read *tolerates a null*, which is independent of both (R268 is the error-arm short-circuit before key extraction; R269 is the success-arm null guard inside it), so `depends-on` stays empty and there is no rebase fork left. The guard sits after the R268 source-binding prelude and tests the `element` read for null.

## Coordination with R400

R400 (remove the `@tableMethod` directive) deletes the `ChildField.RecordTableMethodField` leaf, which is one of the three callers of `buildRecordBasedDataFetcher` (`TypeFetcherGenerator.java:533`, alongside `RecordTableField` at `:516` and `RecordLookupTableField` at `:520`). The guarded helpers `buildAccessorKeySingle` / `buildAccessorKeyMany` are shared and untouched by R400, so the two are orthogonal: no shared-line rebase like R268, and either can land first. R400 only narrows the surface behind the guard, the guard still covers every surviving `AccessorCall` caller (`RecordTableField` / `RecordLookupTableField` and the polymorphic R370 path). R269 does not need the removed leaf for its fixture; the execution-tier case lives on the `RecordTableField` family.

## Tests

- **Execution (`@ExecutionTier`) is the primary and load-bearing net.** A nullable to-one `@table` relation, reached through a record-backed parent's accessor (the `AccessorCall` + `ONE` shape), resolving to no row on an otherwise-successful parent: the field renders `null` and the query does **not** raise `Cannot invoke "...Record.into(...)" because "element" is null`. This directly exercises the generated guard against real PostgreSQL. The `graphitron-sakila-example` record-parent fixtures (the `RecordTableField` family, former `SingleRecordTableField` per R305) are the place to add or extend a case where the accessor legitimately returns null. If the `MANY` guard lands (fork (a)), add the to-many sibling: a null to-many backing resolves to `[]` rather than NPEing in the `for`.
- **Pipeline (`@PipelineTier`)**: the nullable to-one child classifies as `RecordTableField` with `Reader.AccessorCall` + `Cardinality.ONE` and routes through `buildRecordBasedDataFetcher` ; a structural assertion on the classified model (reader variant, cardinality, the `BatchKeyField` `SourceKey`), not a fetcher-body string.
- **No generated-body string assertion.** Per `rewrite-design-principles.adoc` ("Code-string assertions on generated method bodies are banned at every tier"), the guard's *presence* is pinned behaviourally at the execution tier, not by grepping the emitted `if (element == null)` out of the fetcher `toString()`. (The pre-existing `SplitTableFieldPipelineTest.splitQueryField_singleCardinality_nullFkShortCircuitAppearsInFetcherBody` body-string check is legacy and against this rule; do not extend that pattern to the accessor path.)
- **Compilation (`@CompilationTier`)**: `mvn install -Plocal-db` end-to-end green over `graphitron-sakila-example`, which compiles the `CompletableFuture`-returning guarded arm and catches any type mismatch in the early-return expression.

## Out of scope

- **The error-arm short-circuit and the `Outcome` arm-switch**: R268. R269 is the success-arm null guard only.
- **Element-level nulls inside a populated to-many collection**: a malformed backing, not a cardinality to model; left to NPE rather than silently swallowed.
- **The FK-side path** (`Reader.ColumnRead` → `buildKeyExtractionWithNullCheck`): already guarded; unchanged here.
- **Renaming the `__elt` / `__k` dunder locals**: already done by R271; the helpers emit `element` / `key` today, so this is no longer R269's concern.
