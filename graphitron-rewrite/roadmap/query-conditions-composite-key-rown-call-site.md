---
id: R79
title: "Composite-key NodeId condition args: typed Row<N> end-to-end"
status: Ready
bucket: architecture
priority: 9
theme: nodeid
depends-on: []
---

# Composite-key NodeId condition args: typed Row<N> end-to-end

## Motivation

The plan applies the *Wire boundaries are typed adapter / composer pairs* principle (`graphitron-rewrite/docs/rewrite-design-principles.adoc`) to the QueryConditions emitter. The two `kvotetyperV2Condition` overloads — `QueryConditions.kvotetyperV2Condition(Table, DataFetchingEnvironment)` (adapter) and the user-written `KvotetypeConditions.kvotetyperV2Condition(Table, ...)` (composer) — are the two halves of one boundary, and they should type each other honestly: whatever the adapter produces after decoding is what the composer's signature declares. The current shape breaks that symmetry — the adapter has `Record3<String, String, String>` in hand from the typed pattern but erases it to `RowN` when handing across, so the composer's signature stops documenting the contract. R79 restores the symmetry.

A composite-key NodeId argument feeding a generated query-condition method produces Java code that does not compile. The condition method's parameter is declared `org.jooq.RowN` (set in `FieldBuilder.compositeImplicitBodyParam`), and the body uses the `DSL.row(Field<?>[]).eq(arg)` / `.in(rows)` shape so it can stay arity-agnostic. The matching call-site code in `ArgCallEmitter.buildNodeIdDecodeExtraction` projects each decoded record via `((Record) _r).valuesRow()` (scalar arity-N) and `.map(Record::valuesRow)` (list arity-N). Both expressions resolve to `Record.valuesRow()` on the raw `Record` interface, which returns `org.jooq.Row`, not `org.jooq.RowN`. `Row3<...>` and `RowN` are siblings under `Row`, neither a subtype of the other, so javac rejects the assignment with `Row cannot be converted to RowN` (scalar) and an `inference variable T has incompatible bounds` error on `Collectors.toList()` (list). Reproduced in a real consumer (opptak-subgraph `QueryConditions.java`).

The narrow patch (build a `RowN` at the call site via `DSL.row(_r.intoArray())`) makes the code compile but locks in the underlying mistake: the framework chose `RowN` to keep the signature arity-agnostic, at the cost of (a) erasing column-type information at the boundary, (b) forcing `DSL.row(new Field<?>[]{...})` array-cast tricks in every body, and (c) embedding the very type-mismatch that R79 reports. The emitter already has each column's Java type via `ColumnRef.columnClass()`, so `Row<N>` is reachable today. Switching shape is a smaller patch than working around `RowN` and removes the workaround surface.

## Plan

One change-set covers the bug fix, the type-safety upgrade, and a readability pass on the emitted method. The arity > 22 case is rejected as a deferred validation error following the precedent in `GraphitronSchemaValidator.validateChildConnectionParentPk` (Rejection.structural, file:line).

### 1. Body-param shape (FieldBuilder + BodyParam model)

`BodyParam.RowEq` / `BodyParam.RowIn` already carry `columns`, so once the generator builds the parameter type from `columns` directly, the `javaType` slot on those two records is dead weight (today it is always the literal string `"org.jooq.RowN"`; see `FieldBuilder.java:1289`, `:1070`, `:1112`). Drop `javaType` from `RowEq` and `RowIn`. `Eq` and `In` keep theirs (they carry a single column-type string that is still consumed downstream).

`FieldBuilder.compositeImplicitBodyParam` simplifies to `new BodyParam.RowIn(name, columns, nonNull, nested)` / `RowEq(...)`. Both call sites in `walkInputFieldConditions` (`:1213-1232`) and the corresponding `@table`-input arms (`:1066-1116`) lose their dead `String javaType` arg.

Arity > 22 is not reached here; rejected upstream (§4).

### 2. Body emission (TypeConditionsGenerator)

`RowEq` / `RowIn` cases switch from the `Field<?>[]` array trick to the typed form:

```java
condition = condition.and(DSL.row(table.c1, table.c2, table.c3).eq(arg));
```

`buildColsArray` is removed. The columns expand directly as comma-separated cells. The body parameter type is built as `ParameterizedTypeName.get(rowClass(arity), <T1, ..., TN>)` (compare existing `MultiTablePolymorphicEmitter.rowClass(int)` at `:70`) — replacing today's `ClassName.bestGuess(bp.javaType())` at `TypeConditionsGenerator.java:104`. The list form wraps that in `LIST` for `RowIn`. `DSL.row(Field<T1>, ..., Field<TN>)` returns `Row<N><T1, ..., TN>`, so `.eq` / `.in` resolve without coercion.

### 3. Call-site emission (ArgCallEmitter)

§5's per-class helpers absorb the arity > 1 inline ternary entirely; the call-site emitter for `NodeIdDecodeKeys` arity > 1 reduces to a `<helper>(<wireExpr>)` invocation. `buildNodeIdDecodeExtraction`'s arity-1 paths and the `Throw` arms are unchanged.

For the list-arm body inside the helper (§5), the decoded element type is `Record<N><T1, ..., TN>` (from `HelperRef.Decode.returnType()`), so `_nl.stream().map(s -> NodeIdEncoder.decodeFoo((String) s))` is a `Stream<Record<N><...>>`. After `.filter(Objects::nonNull)`, `.map(RecordN::valuesRow)` infers `Stream<Row<N><...>>`, and `.collect(toList())` lands as `List<Row<N><...>>`. The raw `Record::valuesRow` method reference (current `ArgCallEmitter.java:232`) is replaced with the typed `Record<N>::valuesRow`.

**Java-17 compatibility (load-bearing for §5).** Today's emitter cannot use the `instanceof Record3<...> _r` parameterized pattern — that's Java 21+ — and explicitly works around this for arity 1 via the `((Object) decoded) instanceof Record1 _r` raw-pattern + cast trick (`ArgCallEmitter.java:245-249`). The composite case is simpler: since `decodeFoo` already returns the typed `Record<N><T1, ..., TN>`, the helper does not need any `instanceof` on the decode result — a null check is sufficient. See §5 for the exact shape.

The fixture comments at `ArgCallEmitter.java:212` and `:231` ("project Record → RowN") update to reflect the new shape, or are removed once §5 collapses the inline ternary to a helper call.

### 4. Arity > 22 deferred rejection

Composite-key NodeId arguments above arity 22 must land as a deferred validation rejection at the field, not crash the emitter. Two candidate sites:

- `NodeIdLeafResolver` — the leaf-shape classifier knows the decoded record's column count.
- `FieldBuilder.compositeImplicitBodyParam` — the call site that materialises the `BodyParam`.

Place the guard at `NodeIdLeafResolver` so the rejection surfaces during classification (matching the `validateChildConnectionParentPk` precedent at `GraphitronSchemaValidator.java:329-358` — validation, not codegen). The threshold is `> 22` (no `+ idx` widen here — this case fits a plain `Row<N>`, unlike `validateChildConnectionParentPk`'s `> 21` for parent-PK + idx). The error is `Rejection.structural` with wording aligned to the existing precedent: `"Field '<qname>': composite-key NodeId argument '<argName>' has arity <n> exceeding jOOQ's typed Row22 cap. Reduce key arity or expose components as separate scalar arguments."` File:line is the argument's source location.

The classifier already produces deferred rejections; this is one more arm in the same channel.

### 5. Readability pass

All three sub-steps target `QueryConditionsGenerator` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/QueryConditionsGenerator.java`), which emits the env-aware `QueryConditions.<method>(Table, DataFetchingEnvironment)` shim. They do **not** touch `TypeConditionsGenerator` (the entity-side composer can have multiple `BodyParam`s per method, so its AND chain stays as is).

- **Lift shared outer-arg locals.** For each distinct `NestedInputField.outerArgName` referenced by ≥2 body params on a single emitted method, hoist one local at the top of the method:
  ```java
  Map<?, ?> filter = env.getArgument("filter") instanceof Map<?, ?> _m ? _m : null;
  ```
  Per-arg expressions then start from `filter` (no per-arg `instanceof Map<?,?>` re-bind). When the outer arg appears only once, leave the inline form. When the method has multiple distinct outer args, lift each one.

- **Drop the `noCondition()`-and chain when there's one term.** Today `QueryConditionsGenerator.buildConditionMethod` always seeds `Condition condition = DSL.noCondition()` and ANDs each filter into it. In practice each `QueryField.QueryTableField` produces a single composer call (`extractGeneratedConditionFilter` uses `findFirst()` upstream at `TypeConditionsGenerator.java:71`, but `QueryConditionsGenerator` still iterates a list). Reduce: zero filters → `return DSL.noCondition();`; one filter → `return <X>Conditions.<method>(table, ...);`; ≥2 → keep the chain.

- **Per-class helpers for composite-key decode.** Per (NodeId-type, arity, failure-mode) tuple used by a `QueryConditions` class, emit one private static helper. Java-17-friendly form (no parameterized `instanceof` pattern), leaning on `HelperRef.Decode.returnType()` already being typed:

  ```java
  private static Row3<String, String, String> decodeKvotetypeRow(Object wire) {
      if (!(wire instanceof String s)) return null;
      var r = NodeIdEncoder.decodeKvotetype(s);
      return r == null ? null : r.valuesRow();
  }

  private static List<Row3<String, String, String>> decodeKvotetypeRows(Object wire) {
      if (!(wire instanceof List<?> _nl)) return null;
      return _nl.stream()
          .map(x -> x instanceof String s ? NodeIdEncoder.decodeKvotetype(s) : null)
          .filter(Objects::nonNull)
          .map(Record3::valuesRow)
          .collect(Collectors.toList());
  }
  ```

  The `ThrowOnMismatch` arm gets its own helper variant (different body — raise `GraphqlErrorException` on null decode rather than returning null / filtering). Tuple key is (type, arity, mode); `Skip` and `Throw` do not share a helper.

  Call sites collapse to `decodeKvotetypeRow(filter == null ? null : filter.get("kvotetypeId"))` (scalar) or `decodeKvotetypeRows(filter == null ? null : filter.get("kvotetypeIds"))` (list). Helpers are deduplicated per `QueryConditions` class.

The helpers live on the `QueryConditions` class (the call site), emitted by `QueryConditionsGenerator` alongside the condition methods. `ArgCallEmitter.buildNodeIdDecodeExtraction`'s arity > 1 builder collapses to "produce a call to the per-class helper"; the helper itself is the only place where the decode-and-project chain appears in the generated source.

#### Implementation seam (Spec→Ready note)

The Spec→Ready review flagged that "the call-site emitter reduces to a `<helper>(<wireExpr>)` invocation" and "helpers live on the `QueryConditions` class" together imply an unspecified API contract on `ArgCallEmitter`. The contract is:

1. **First, fix the inline arity > 1 form so it compiles in isolation.** The bug at `ArgCallEmitter.java:232` (`Record::valuesRow` raw method reference) and `:267` / `:275` (`(($T) _r).valuesRow()` cast through raw `Record`) both lose the `Record<N><T1, ..., TN>` type that `HelperRef.Decode.returnType()` already provides. Drop the raw cast and use `decode.returnType()` (the typed `RecordN`) for both the `instanceof` pattern and the method-reference receiver. After this fix, every `ArgCallEmitter.buildCallArgs` call site (`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`, `TypeFetcherGenerator`, `QueryConditionsGenerator`) emits compiling code for composite-key NodeId args, with no helper involved. This is the load-bearing fix; helpers are a strict readability layer on top.

2. **Then, add an opt-in helper registry for QueryConditions.** Introduce a small `CompositeDecodeHelperRegistry` (a mutable collector). `ArgCallEmitter.buildCallArgs` and `buildArgExtraction` get an extra parameter `CompositeDecodeHelperRegistry registry` (nullable). When non-null, the composite NodeIdDecodeKeys arity > 1 path registers a helper keyed on `(decode.encoderClass(), decode.methodName(), mode, list)` and emits a call to it; when null, falls back to the inline form from §1. Other emit sites (Inline*, SplitRows*, TypeFetcher) pass `null` initially — they don't exercise composite-key NodeId decode in practice today, and lifting them is out of scope for R79.

   ```java
   final class CompositeDecodeHelperRegistry {
       enum Mode { SKIP, THROW }
       record Key(ClassName encoderClass, String methodName, Mode mode, boolean list) {}
       private final Map<Key, MethodSpec> helpers = new LinkedHashMap<>();
       String register(HelperRef.Decode decode, Mode mode, boolean list);  // returns helper method name, builds spec lazily
       Collection<MethodSpec> emit();                                       // for QueryConditionsGenerator to add to the class
   }
   ```

   Helper naming (keyed-deduplicated, private static, no leak risk): `decode<NodeType>Row` / `decode<NodeType>Rows` for `Mode.SKIP`; `decode<NodeType>RowOrThrow` / `decode<NodeType>RowsOrThrow` for `Mode.THROW`. `<NodeType>` derives from `decode.methodName()` (which is already `decode<TypeName>`, so strip the `decode` prefix).

3. **`QueryConditionsGenerator` ownership.** `generateConditionsClass` (or its rewrite-equivalent in the QueryConditions emitter — name pinned at implementation) instantiates one registry per class, threads it through each `buildConditionMethod` invocation, and calls `registry.emit()` after the method-emit loop, adding each `MethodSpec` to the class builder.

4. **Why a registry rather than returning a structured result.** The alternative (have `ArgCallEmitter` return `(CodeBlock, List<MethodSpec>)` and let the caller dedupe) pushes deduplication onto every caller and breaks the "callers pass `null` to opt out" pattern. A nullable side-effect collector is the idiomatic shape elsewhere in the rewrite (compare `TypeClassGenerator`'s field/method accumulators).

5. **Test coverage of the seam.** The pipeline test rewritten in §6 asserts on `TypeConditionsGenerator` (the composer side, no helper involved). A second new pipeline-tier test on the `QueryConditions` emitter asserts that two distinct condition methods on the same class consuming the same NodeId type emit exactly one shared helper (deduplication works) and that the throw-mode arm gets a separate helper from the skip-mode arm (key separation works). Test name pinned at implementation.

### Implementation status (updated during In Progress)

Shipped in the first In-Progress cycle:

- §1 dead `javaType` slot dropped from `BodyParam.RowEq/RowIn`; the interface method comes off; `FieldBuilder` and `TypeConditionsGenerator` switch per variant. (`R79 §1+§2+§4` commit)
- §2 typed `Row<N><T1, ..., TN>` body emission via `ParameterizedTypeName` over `columnClass()`; `buildColsArray`'s `Field<?>[]` erasure trick is gone. (same commit)
- §3 inline arity > 1 forms in `ArgCallEmitter` fixed to use raw `RecordN` pattern + cast to typed `Row<N><...>` (Java-17 compatible — parameterized `instanceof` patterns are JDK 21+). The list arm uses `Record<N>::valuesRow` instead of raw `Record::valuesRow`. This is the load-bearing bug fix; helpers (below) are a strict readability layer on top. (same commit)
- §4 arity > 22 deferred `Rejection.structural` lands in `NodeIdLeafResolver.resolve` before the decode-helper resolution, with wording tracking the `validateChildConnectionParentPk` precedent (Row22 cap, suggest scalar fan-out). (same commit)
- §5 `noCondition()`-and chain reduction in `QueryConditionsGenerator`: zero filters → `return DSL.noCondition()`, one filter → direct return, ≥2 → keep the chain. JooqConvert+list locals are pre-lifted ahead of the dispatch so they stay in scope. (`R79 §5` commit)
- §6 compilation-tier regression-guard fixture (`filmActorsByCompositeNodeIds` + `FilmActorCompositeNodeIdFilter`) in `graphitron-sakila-example`'s schema, exercising the `compositeImplicitBodyParam` → `BodyParam.RowIn` → typed `Row<N>` path against real jOOQ. Generated source now compiles where it did not before. (`R79 §6` commit)
- §6 pipeline test rewritten: `nodeIdInFilter_compositeColumns_emitsRowInWithUntypedRowN` → `_emitsTypedRowIn` plus a sibling assertion that the parameter type is `List<Row2<Integer, Integer>>`. (`R79 §1+§2+§4` commit)

Pending for In Review or a follow-up cycle:

- §3+§5 helper-registry layer. The inline form compiles correctly today, so this is a readability-only pass. The seam shape is pinned in the *Implementation seam (Spec→Ready note)* section above; nothing in it is invalidated by the inline-form fix. Worth landing before approval if the In-Review reviewer thinks the inline expression at the call site (visible in `target/generated-sources/.../QueryConditions.java::filmActorsByCompositeNodeIdsCondition`) is harder to read than the helper version would be.
- §5 sub-step 1: lift shared outer-arg locals (`Map<?, ?> filter = ... instanceof Map<?, ?> _m ? _m : null`). Independent of the helper registry; lands in `QueryConditionsGenerator`. The current code re-binds `_m1` per filter call — fine for one-filter methods (the common case after the §5 reduction shipped above), but worth doing for the two-or-more case.
- §6 unit test for the arity > 22 rejection. Needs a 23-column-PK fixture in `nodeidfixture` (schema in `graphitron-sakila-db/src/main/resources/init.sql` plus the matching jOOQ codegen entry). The rejection path itself is a 5-line guard following a documented precedent, so the test cost vs. defensive value justifies a follow-up; leaving it to the In-Review reviewer's call.
- §6 pipeline-tier helper-dedup test (only meaningful once the registry lands).

### 6. Tests

- **Compilation tier (regression guard for the original R79 bug):** new fixture exercising a composite-key NodeId argument feeding a query-field condition. Sakila has no composite-PK NodeId arg today, so this fixture lives in `graphitron-fixtures-codegen` (or a small extension to the sakila example schema if a composite-PK table can be reused / synthesized; pin the choice during In Progress, before the implementation step lands). The generated source compiles cleanly against real jOOQ. This is the gap that hid the bug.
- **Unit:** arity > 22 produces a deferred `Rejection.structural` at the expected qualified-field name with wording matching §4; the emitter does not throw.
- **Pipeline (string-fixture):** rename and rewrite `TypeConditionsGeneratorTest.nodeIdInFilter_compositeColumns_emitsRowInWithUntypedRowN` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/TypeConditionsGeneratorTest.java:75-84`) — its name and `DSL.row(new org.jooq.Field<?>[]{...}).in(ids)` assertion both encode the old shape. Replace with assertions on the typed `DSL.row(table.ID_1, table.ID_2).in(ids)` form and a parameter-type assertion of `List<Row2<Integer, Integer>>`. The existing test's helper `nodeIdRowIn` drops the `"org.jooq.RowN"` arg once §1 lands.

## Out of scope

- Non-NodeId composite-key arguments (the `@table` input path producing `BodyParam.RowEq` / `BodyParam.RowIn` from `CompositeColumnField` / `CompositeColumnReferenceField` at `FieldBuilder.java:1066-1116`) follow the same call-site emitter and are in scope for §1 + §2. The §3 / §5 helper arm is no-op for the non-NodeId path: those carriers have non-`NodeIdDecodeKeys` extraction leaves, so `buildNodeIdDecodeExtraction` is never reached. Confirmed by tracing `BodyParam.RowEq.extraction()` consumers in `ArgCallEmitter.buildCallArg` — the `case CallSiteExtraction.NodeIdDecodeKeys` arm is the only path through `buildNodeIdDecodeExtraction`. (Pin during In Progress; if a future carrier widens this surface, §3 grows accordingly.)
- Arity > 22 codegen fallback to `RowN` is explicitly rejected: the deferred validation error covers it, and the emitter complexity savings are the whole point of the switch.
- Non-composite (arity-1) NodeId arguments — already use `Record1.value1()`, no change.
