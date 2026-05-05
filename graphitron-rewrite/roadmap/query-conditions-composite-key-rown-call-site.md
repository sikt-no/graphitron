---
id: R79
title: "Composite-key NodeId condition args: typed Row<N> end-to-end"
status: Spec
bucket: architecture
priority: 9
theme: nodeid
depends-on: []
---

# Composite-key NodeId condition args: typed Row<N> end-to-end

## Motivation

A composite-key NodeId argument feeding a generated query-condition method produces Java code that does not compile. The condition method's parameter is declared `org.jooq.RowN` (set in `FieldBuilder.compositeImplicitBodyParam`), and the body uses the `DSL.row(Field<?>[]).eq(arg)` / `.in(rows)` shape so it can stay arity-agnostic. The matching call-site code in `ArgCallEmitter.buildNodeIdDecodeExtraction` projects each decoded record via `((Record) _r).valuesRow()` (scalar arity-N) and `.map(Record::valuesRow)` (list arity-N). Both expressions resolve to `Record.valuesRow()` on the raw `Record` interface, which returns `org.jooq.Row`, not `org.jooq.RowN`. `Row3<...>` and `RowN` are siblings under `Row`, neither a subtype of the other, so javac rejects the assignment with `Row cannot be converted to RowN` (scalar) and an `inference variable T has incompatible bounds` error on `Collectors.toList()` (list). Reproduced in a real consumer (opptak-subgraph `QueryConditions.java`).

The narrow patch (build a `RowN` at the call site via `DSL.row(_r.intoArray())`) makes the code compile but locks in the underlying mistake: the framework chose `RowN` to keep the signature arity-agnostic, at the cost of (a) erasing column-type information at the boundary, (b) forcing `DSL.row(new Field<?>[]{...})` array-cast tricks in every body, and (c) embedding the very type-mismatch that R79 reports. The emitter already has each column's Java type via `ColumnRef.columnClass()`, so `Row<N>` is reachable today. Switching shape is a smaller patch than working around `RowN` and removes the workaround surface.

## Plan

One change-set covers the bug fix, the type-safety upgrade, and a readability pass on the emitted method. The arity > 22 case is rejected as a deferred validation error following the precedent in `GraphitronSchemaValidator.validateChildConnectionParentPk` (Rejection.structural, file:line).

### 1. Body-param shape (FieldBuilder)

`FieldBuilder.compositeImplicitBodyParam` keys `javaType` by `columns.size()`:

- Arity 1..22: `org.jooq.Row<N><T1, ..., TN>` where each `Tk` is `columns.get(k).columnClass()`.
- Arity > 22: not reached; rejected upstream (§4).

`BodyParam.RowEq` / `BodyParam.RowIn` already carry `columns`, so the typed signature is constructible without classifier rework.

### 2. Body emission (TypeConditionsGenerator)

`RowEq` / `RowIn` cases switch from the `Field<?>[]` array trick to the typed form:

```java
condition = condition.and(DSL.row(table.c1, table.c2, table.c3).eq(arg));
```

`buildColsArray` is removed. The columns expand directly as comma-separated cells. The condition method's body parameter is `Row<N><T1, ..., TN>` (or `List<Row<N><T1, ..., TN>>` for the list form), and `DSL.row(Field<T1>, ..., Field<TN>)` returns `Row<N><T1, ..., TN>`, so `.eq` / `.in` resolve without coercion.

### 3. Call-site emission (ArgCallEmitter)

`buildNodeIdDecodeExtraction`'s arity > 1 paths drop the raw `(Record)` cast and rely on the typed pattern variable. The decoded record is `Record<N><T1, ..., TN>`, so `_r.valuesRow()` resolves to `RecordN.valuesRow()` returning `Row<N><T1, ..., TN>` directly. The list-arm `.map(Record::valuesRow)` becomes `.map(Record<N>::valuesRow)` (typed method reference) — `Stream<Record<N><...>>::map` infers `Stream<Row<N><...>>`, which `Collectors.toList()` lands as `List<Row<N><...>>`.

The fixture comments at `ArgCallEmitter.java:212` and `:231` ("project Record → RowN") update to reflect the new shape.

### 4. Arity > 22 deferred rejection

Composite-key NodeId arguments above arity 22 must land as a deferred validation rejection at the field, not crash the emitter. Two candidate sites:

- `NodeIdLeafResolver` — the leaf-shape classifier knows the decoded record's column count.
- `FieldBuilder.compositeImplicitBodyParam` — the call site that materialises the `BodyParam`.

Place the guard at `NodeIdLeafResolver` so the rejection surfaces during classification (matching the `validateChildConnectionParentPk` precedent — validation, not codegen). The error is `Rejection.structural` with wording aligned to the existing precedent: `"Field '<qname>': composite-key NodeId argument '<argName>' has arity <n> which exceeds jOOQ's typed Row22 cap. Reduce key arity or expose components as separate scalar arguments."` File:line is the argument's source location.

The classifier already produces deferred rejections; this is one more arm in the same channel.

### 5. Readability pass

While we're touching emission for these methods:

- At each `QueryConditions.<method>` body, lift `env.getArgument("filter")` to a single local at the top of the method:
  ```java
  Map<?, ?> filter = env.getArgument("filter") instanceof Map<?, ?> _m ? _m : null;
  ```
  Each per-arg expression then starts from `filter` (and skips the per-arg rebind chain). The current shape rebinds `_m1` once per arg, evaluating the same `instanceof Map<?,?>` N times.

- Per (NodeId-type, arity) pair used by a `QueryConditions` class, emit one private static helper:
  ```java
  private static Row3<String, String, String> decodeKvotetypeRow(Object wire) {
      return wire instanceof String s
          && NodeIdEncoder.decodeKvotetype(s) instanceof Record3<String, String, String> r
          ? r.valuesRow() : null;
  }
  ```
  The call site collapses to `decodeKvotetypeRow(filter == null ? null : filter.get("kvotetypeId"))`. The helper is the natural place for the typed pattern; the call-site expression stays one short ternary.

The list-arm helper has the same shape, returning `List<Row<N><...>>`. Helpers are deduplicated per class — multiple condition methods that decode the same NodeId type share one helper.

This step removes `ArgCallEmitter.buildNodeIdDecodeExtraction`'s inline ternary as the de-facto API; that builder becomes "produce a call to the per-class helper," and the helper is generated by `TypeConditionsGenerator` alongside the condition methods. The call-site emitter no longer carries the arity > 1 ternary chain at all.

### 6. Tests

- **Compilation tier (regression guard for the original R79 bug):** new fixture exercising a composite-key NodeId argument feeding a query-field condition. The generated source compiles cleanly. This is the gap that hid the bug.
- **Unit:** arity > 22 produces a deferred `Rejection.structural` at the expected qualified-field name; the emitter does not throw.
- **Pipeline (string-fixture):** existing tests that pin emitted code update to the new typed shape — `Row<N><...>` parameter types, `_r.valuesRow()` without the raw cast, lifted `filter` local, and helper-call call sites. These updates are mechanical; they fail loudly when the new shape lands and the snapshot tools refresh in the same commit.

## Out of scope

- Non-NodeId composite-key arguments (the `@table` input path producing `BodyParam.RowEq` / `BodyParam.RowIn` from `CompositeColumnField` / `CompositeColumnReferenceField`) follow the same call-site emitter, so they're in scope for the body-param-shape switch (§1, §2). Verify before scoping; if the @table path's call-site emitter is independent of `ArgCallEmitter.buildNodeIdDecodeExtraction`, that arm of §3 is a no-op there.
- Arity > 22 codegen fallback to `RowN` is explicitly rejected: the deferred validation error covers it, and the emitter complexity savings are the whole point of the switch.
- Non-composite (arity-1) NodeId arguments — already use `Record1.value1()`, no change.
