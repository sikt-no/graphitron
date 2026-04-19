# Typed lookup-input VALUES — retype `LookupValuesJoinEmitter` end-to-end

> **Status:** Draft
>
> Retypes the shared `@lookupKey` input-rows helper from untyped `RowN[]` to typed `Row<N+1><Integer, colType1, …>[]` and propagates the typing through all four lookup-field emission paths. Closes the typing asymmetry surfaced during Phase 2b review: `parentInput` in `SplitRowsMethodEmitter` is `Table<Record<N+1>>` while `lookupInput` is `Table<?>`, despite both having the same codegen-time arity-known shape.

## Why

Phase 2b landed typed `parentInput` via `Row<N+1><Integer, pkType…>[]` + `Table<Record<N+1>>`. The companion `lookupInput` stayed `Table<?>` because it consumes the shared `LookupValuesJoinEmitter` helper that predates the typed shape (Phase 2a / root lookup). At every use-site, the arity is known at codegen time (`LookupMapping.columns().size()`), so there is no actual dynamic-arity constraint — only a comment in the helper claiming there is one. Fixing it now, while the typed shape is fresh and only one downstream consumer (Phase 2c) is still pending, avoids compounding the cost.

## Current state (accurate per trunk)

- `LookupValuesJoinEmitter.addRowBuildingCore` (`:180-216`) emits an `Object[] cells = …` + `DSL.row(cells)` loop returning `RowN[]`. Comment at `:193-195` states: *"a varargs Field<?>[] call would bind to the more-specific RowN<T1, T2, …> overloads instead, which we cannot name dynamically."* The premise — dynamic arity — is false at every call site.
- `buildInputRowsMethod` (`:103-125`, env-based) and `buildChildInputRowsMethod` (`:139-161`, SelectedField-based) both return `ArrayTypeName.of(RowN)`. The helpers are per-field (one emitted method per lookup field), so their signatures can be arity-specific without breaking shared infrastructure.
- `buildFetcherBody` (`:251-286`) declares `Table<?> input = DSL.values(rows).as(…)` via the `WILDCARD_TABLE` constant. Uses `.using(table.COL, …)` for the JOIN (not `.on(input.field(…))`), so the input's field types never need to be named — the wildcard is invisible there. But any `.orderBy(input.field("idx"))` or `.field("<col>")` call downgrades to `Field<Object>`.
- `InlineLookupTableFieldEmitter` (`:108-147`) emits `RowN[] rows = <field>InputRows(sf, terminalAlias)`, then `Table<?> input = DSL.values(rows).as(…)`, then `.on(terminalAlias.COL.eq(input.field(terminalAlias.COL)))`. `input.field(Field<T>)` returns `Field<T>` via jOOQ's name-based overload, so the ON predicate recovers typing at use-site even with the wildcard table.
- `SplitRowsMethodEmitter.buildListMethod` (`:303-381`): declares `RowN[] lookupRows = <field>InputRows(env, terminalAlias)`, short-circuits on empty, then `Table<?> lookupInput = DSL.values(lookupRows).as(…)` with the `WildcardTypeName.subtypeOf(Object.class)` wildcard. ON uses `lookupInput.field(i+1, ColType.class)` — typed recovery at use-site.
- `RecordLookupTableField` — still in `NOT_IMPLEMENTED_REASONS`. Future Phase 2c will call `buildChildInputRowsMethod` from a `RecordN`-keyed context. If this plan lands first, 2c inherits typed shape at no extra cost.

## Asymmetry, precisely

The plan-argres-phase-2b.md `320394b` fix commit message spelled out the rule for `parentInput`: *"DSL.row(Field<T1>, Field<T2>, …) picks the typed Row<N> overload (not Row(Object...) returning untyped RowN), so DSL.inline(i) and k.fieldJ() types flow into parentRows[i]."* The same rule applies to lookup cells:

- `DSL.val(Object value, DataType<T> type)` is declared `<T> Field<T> val(Object, DataType<T>)`. `table.COL.getDataType()` is a typed `DataType<ColType>`, so `DSL.val(rawValue, table.COL.getDataType())` returns `Field<ColType>`.
- `DSL.row(DSL.inline(i), DSL.val(v1, t1.getDataType()), DSL.val(v2, t2.getDataType()))` — positional call with three typed `Field<?>` args → picks the `row(Field<A>, Field<B>, Field<C>)` overload → returns `Row3<Integer, colType1, colType2>`.
- The `Object[] cells + DSL.row(Object...)` detour erases this typing for no benefit once we accept that every call site knows arity.

## Design

### Decision: per-arity typed emission, shared core retained

We retain `addRowBuildingCore` as the single shared core but retype its output. The caller (arity-known) tells the core which `Row<N+1>` parameterisation to emit. Concretely:

- `addRowBuildingCore` takes the existing inputs plus the typed element-type list (`List<TypeName> cellJavaTypes`) — the first entry is always `Integer` (the `idx` cell), the rest come from `mapping.columns().stream().map(c -> javaTypeFor(c))`.
- The emitted Java reads:
  ```java
  @SuppressWarnings("unchecked")
  Row<N+1><Integer, T1, …, TN>[] rows = (Row<N+1><Integer, T1, …, TN>[]) new Row<N+1>[<size-expr>];
  for (int i = 0; i < <size-expr>; i++) {
      rows[i] = DSL.row(DSL.inline(i), DSL.val(v1, TABLE.COL1.getDataType()), …);
  }
  ```
  Same `@SuppressWarnings("unchecked")` + generic-array-cast pattern we already use for `parentRows` in `SplitRowsMethodEmitter`.
- `buildInputRowsMethod` and `buildChildInputRowsMethod` return `ArrayTypeName.of(ParameterizedTypeName.get(ClassName.get(RowN+1), Integer.class, T1…TN))` instead of `ArrayTypeName.of(RowN)`.
- `buildFetcherBody` declares `Table<Record<N+1>><Integer, T1, …, TN> input = DSL.values(rows).as(…)`. The JOIN still uses `.using(table.COL, …)` — unchanged — so the typed shape is invisible there, but use-sites that *do* read from `input` (order, field lookup by index) now return properly typed `Field<T>` without the `input.field(i, T.class)` crutch.

### Why this is safe

- jOOQ ships `Row1`…`Row22` and `Record1`…`Record22`. `LookupMapping.columns().size()` is bounded by the schema; the Phase 2b parent-side already caps at 22. Lookup arity is `1 + columns.size()` (the `idx` cell plus the mapping columns), so we enforce `columns.size() <= 21` at codegen time — identical to the parent cap mechanism. Arities exceeding 21 raise a codegen error with the same message shape as the parent cap.
- `DSL.row(Field<T1>, Field<T2>, …, Field<TN>)` is declared per arity up to 22 and returns `RowN<T1, …, TN>`. Passing N typed `Field<?>` arguments positionally resolves to this overload — the same rule the parent-rows code already relies on.
- `DSL.val(Object value, DataType<T> type)` returns `Field<T>`. `table.COL.getDataType()` carries the column's Java type through jOOQ's generated tables, so `DSL.val(rawValue, table.COL.getDataType())` returns a typed `Field<ColType>` without any cast.
- The generic-array cast is the same pattern we already use for parent rows; SonarQube-style nits about "unchecked array creation" are suppressed locally as before.

### Alternatives considered

- **Inline the row-building at each call site (no shared core).** Rejected — duplicates ~25 lines across three emitters and loses the single place where the `DSL.inline(i)` + cell-loop contract is defined.
- **Keep `Table<?>` on the jOOQ side, only type the `Row<N+1>[]` array.** Rejected — half-typing. The whole point of the typed array is that `DSL.values(Row<N+1>…)` returns `Table<Record<N+1>>`. Throwing that away at the `.as(…)` step would require every downstream consumer to keep using `.field(i, T.class)` for no reason.
- **Unchecked cast lying about the runtime type** (e.g. cast `Table<?>` to `Table<Record<N+1>>` without retyping the array). Rejected — introduces a real unsafe cast where the typed-array path has none (jOOQ's `DSL.values(Row<N+1>…)` genuinely returns `Table<Record<N+1>>`).

## Commit structure

Four small commits. Each leaves trunk green (all rewrite unit tests + compilation test + execution tests).

### C1 — Retype shared core in `LookupValuesJoinEmitter`

Scope: `LookupValuesJoinEmitter` only. No call sites change shape yet beyond consuming the new return type.

- Add a private helper resolving the typed-element list for a `LookupMapping`:
  ```java
  private static List<TypeName> cellJavaTypes(LookupMapping m) {
      List<TypeName> out = new ArrayList<>(1 + m.columns().size());
      out.add(TypeName.INT.box()); // idx
      for (var col : m.columns()) out.add(javaTypeFor(col));
      return out;
  }
  ```
  `javaTypeFor(col)` returns the boxed Java type backing the jOOQ column — the same resolution logic used by the parent-side typed-row emission in `SplitRowsMethodEmitter`. If a shared helper already exists, reuse it; otherwise extract one.
- Enforce arity: `if (m.columns().size() > 21) throw new IllegalStateException("@lookupKey arity exceeds jOOQ's Row22 limit")`. Same message shape as parent-side.
- Replace the `Object[] cells` + `DSL.row(cells)` loop with the typed-row positional call. Remove the stale comment at `:193-195`.
- `buildInputRowsMethod` + `buildChildInputRowsMethod` return type becomes `ArrayTypeName.of(ParameterizedTypeName.get(RowN+1, Integer.class, T1…TN))`. Update the emitted local variable type to match.
- `buildFetcherBody` declares `Table<Record<N+1>><…>` instead of `Table<?>`. The `WILDCARD_TABLE` constant is retired from the lookup path (kept only if any non-lookup caller still needs it — check with grep before deleting).

Tests: `LookupValuesJoinEmitterTest` structural assertions already check method return types — extend them to assert the arity-specific `Row<N+1>` parameterisation. Run `mvn test -pl :graphitron-rewrite` (expect unit-test churn on the structural checks). Then `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` — catches any call site we missed.

### C2 — Retype `lookupInput` in `SplitRowsMethodEmitter`

Scope: `SplitRowsMethodEmitter.buildListMethod` (`:303-381`) only.

- `lookupRows` local variable type becomes `Row<N+1><Integer, T1, …, TN>[]` instead of `RowN[]` (C1 already changed the helper return type; this is just the caller catching up).
- `lookupInput` declared `Table<Record<N+1>><Integer, T1, …, TN>` instead of `Table<?>`. Drop the `WildcardTypeName.subtypeOf(Object.class)`.
- The ON predicate already uses `lookupInput.field(i+1, ColType.class)` — this still compiles and is still correct, but now the typed `.field(i+1)` overload would also work. Leave the explicit-class form for readability and to keep the diff minimal (separate refactor if we want to switch).
- The empty short-circuit branch (`lookupRows.length == 0 → emptyScatter(keys.size())`) is unchanged.

Tests:
- `SplitRowsMethodEmitterTest` — existing structural assertions on `rowsActorsByKey` signature should pass unchanged (the return type is `List<List<Record>>`, unaffected). Add a structural assertion on the emitted `Table<Record<N+1>>` declaration type if the existing tests inspect body types.
- Execution test: the `actorsByKey` split-lookup case in `graphitron-rewrite-test-spec` already exercises the VALUES+JOIN against real Postgres — if it still passes, the runtime shape is compatible.

### C3 — Consume typed shape in `InlineLookupTableFieldEmitter` and root lookup path

Scope: `InlineLookupTableFieldEmitter` (`:108-147`) plus `QueryLookupTableFieldEmitter` / wherever `buildFetcherBody` is called.

- `Table<?> input` → `Table<Record<N+1>><Integer, T1, …, TN> input`.
- The ON predicate `terminalAlias.COL.eq(input.field(terminalAlias.COL))` — `input.field(Field<T>)` already returned `Field<T>` via name-based overload, so the ON shape is unchanged. But `input.field("<col>")` or `input.field(i)` calls (if any exist) now return properly typed `Field<T>` — audit call sites.
- `RowN[] rows = <field>InputRows(sf, terminalAlias)` → `Row<N+1><Integer, T1, …, TN>[] rows = <field>InputRows(sf, terminalAlias)`.

Tests: compilation test + existing execution tests in `graphitron-rewrite-test-spec` (root lookup + inline lookup cases). No new tests needed — C1's helper-return-type change forces every caller to match.

### C4 — Phase 2c placeholder note

Scope: one-line docstring update in the `NOT_IMPLEMENTED_REASONS` entry for `RecordLookupTableField` noting that the typed lookup-input shape is now available when 2c lands. No code change.

## Test strategy

- **Unit (`mvn test -pl :graphitron-rewrite`).** Extend `LookupValuesJoinEmitterTest` structural assertions to check that the emitted helper returns `Row<N+1><Integer, …>[]` for representative arities (1-col, 2-col lookup). Extend `SplitRowsMethodEmitterTest` if it inspects the `lookupInput` declaration type. Structural-only — no code-string body assertions (per `CLAUDE.md`).
- **Compilation (`mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db`).** This is the primary safety net — every call site is forced to compile against real jOOQ classes with the new typed shape. If C1 misses a caller, compilation fails.
- **Execution (`mvn test -pl :graphitron-rewrite-test-spec -Plocal-db`).** The existing root-lookup, inline-lookup, and split-lookup spec tests exercise the VALUES+JOIN end-to-end against native Postgres. No behavioural change expected; passing tests confirm the typed shape doesn't drift runtime semantics.
- **Generated-output inspection.** After C3, diff `target/generated-sources/graphitron/**/*.java` for a representative field of each shape and confirm: (a) no `Table<?>` on lookup paths, (b) no `.field(i, Class.class)` crutch where the typed overload is available (optional — cosmetic), (c) no regressions in parent-side typing.

## Risks

- **Low: arity-overflow surprise.** A future `@lookupKey` field with >21 composite columns would trip the codegen cap. Mitigated by matching the parent-side cap mechanism (same error message shape) and by the schema-level practicality that composite lookup keys above even 3-4 columns are rare.
- **Low: test-churn.** Unit tests that assert the return type of `buildInputRowsMethod` as `RowN[]` will fail and need updating. Expected and trivial; in scope for C1.
- **Low: `javaTypeFor(col)` edge cases.** Custom jOOQ converters or user-defined `DataType<T>` bindings may produce non-obvious Java types. The parent-side already resolves this — we reuse its helper, so any edge case that works for `parentRows` works for `lookupRows`.

## Non-goals

- Switching the inline-lookup ON predicate from `.field(Field<T>)` to `.field(int)` — cosmetic, separate if ever.
- Retiring the `input.field(i, Class.class)` form in existing call sites — typed overloads now work, but the explicit form is also correct and more readable at a glance. Leave alone.
- Changing the `.using(table.COL, …)` JOIN clause shape — the wildcard was already invisible here.
- Any change to the parent-side typed-row emission (Phase 2b landed; not touched).
- Phase 2c (`RecordLookupTableField`) — out of scope beyond the C4 docstring note.

## Cross-plan dependencies

- **Depends on:** Phase 2b (`plan-argres-phase-2b.md`) — landed. Provides the typed `parentRows` / `parentInput` pattern this plan mirrors on the lookup side, plus the arity-cap mechanism and `javaTypeFor(col)` resolution logic.
- **Unblocks:** Phase 2c (`RecordLookupTableField`) — inherits typed lookup-input shape at zero additional cost if this plan lands first.
- **Parallel-safe with:** none of the other Backlog items touch `LookupValuesJoinEmitter` or the lookup fetcher bodies.

## History

*(empty — to be filled on merge with commit hashes and any surprises.)*
