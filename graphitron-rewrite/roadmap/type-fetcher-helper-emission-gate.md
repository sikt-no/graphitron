---
id: R80
title: "Replace string-scan helper-emission gate in `TypeFetcherGenerator`"
status: Spec
bucket: cleanup
depends-on: []
---

# Replace string-scan helper-emission gate in `TypeFetcherGenerator`

`TypeFetcherGenerator.generateTypeSpec` decides whether to emit the
`graphitronContext` helper by serialising every just-emitted method's
`CodeBlock` to a `String` and substring-greping it for `graphitronContext(env)`
(`TypeFetcherGenerator.java:478-482`). This was introduced as a fix for a
regression where a `*Fetchers` class containing only `ServiceRecordField`
fetchers called the helper without emitting it: the previous predicate
enumerated `SqlGeneratingField` plus a handful of interface/union/DML variants
and silently dropped `ServiceRecordField`, which is the only `BatchKeyField`
that does not extend `SqlGeneratingField` (via `TableTargetField`).

The string-scan fixes the symptom but is generation-thinking inverted: the
generator reads its own emitted source to gate a sibling emission. Any future
emitter that writes `graphitronContext(dfe)` instead of `graphitronContext(env)`,
or splits the call across `CodeBlock` arguments (e.g.
`CodeBlock.of("graphitronContext($N)", envParam)`), or routes through
`$T.class` literal interpolation that produces an equivalent body, would
silently regress with no compile signal. The production code is itself a
code-string assertion on a generated method body — the shape the test-tier
rules ban — and the companion unit test
(`graphitronContextHelper_emittedForServiceRecordOnlyClass`,
`TypeFetcherGeneratorTest.java:1354`) carries the same string assertion.

## Why a per-class emission context, not a capability interface

The original Backlog body listed two shapes: (a) a per-class emission context
that emitters call when they write a `graphitronContext(env)` lookup, with
class assembly draining the requested-helpers set, or (b) lifting "fetcher
emits a `graphitronContext` call" into a capability interface alongside
`SqlGeneratingField` / `MethodBackedField` / `BatchKeyField` so the gate is one
`instanceof` over a real classification.

Option (b) is the wrong unit of resolution. The call sites that emit
`graphitronContext(env)` cross-cut field classification:

- `TypeFetcherGenerator` itself (~14 sites: every `dsl = graphitronContext(env)
  .getDslContext(env)` for SQL-emitting fetchers, the validator pre-step at
  `:1291`, the multitable tenant-id at `:2532`).
- `ArgCallEmitter:115,159` — emits `graphitronContext(env).getContextArgument(env, $S)`
  for any `ParamSource.Context` parameter on a method-backed field. Whether the
  emitter writes the call depends on the *parameter shape*, not the host
  field's variant: a `MethodBackedField` may or may not have a `Context`
  param. A field-side capability flag would have to be set conservatively
  (every `MethodBackedField`, regardless of params) and re-derive the predicate
  — which is precisely the brittleness we're removing.
- `LookupValuesJoinEmitter:411`, `SplitRowsMethodEmitter:218`,
  `MultiTablePolymorphicEmitter:238,347,705,815`. These are dispatched off
  field shape for the most part, so a marker would work here, but the
  `ArgCallEmitter` case alone defeats the "one `instanceof`" framing.
- `ConnectionHelperClassGenerator:274` is **out of scope**: it emits its own
  helper class with its own assembly, not the `*Fetchers` class. R80 is the
  `*Fetchers` gate only.

The capability-marker approach also doesn't make the structural mistake
impossible — it just relocates the predicate from `generateTypeSpec`'s gate to
each `case` arm in the emitter dispatch (every variant remembers to set the
marker if it could route to a graphitronContext-emitting path). The bug shape
("emitter writes the call, classifier didn't enumerate that variant") returns
unchanged.

The emission-context shape pushes the decision to the call site itself: every
emitter that writes `graphitronContext(env)` goes through one helper that both
returns the `CodeBlock` *and* records the helper request. The bug becomes
structurally impossible: you cannot write the call without recording the
dependency, because the call only exists as a return value of that helper.

## Shape of the change

1. **Introduce `TypeFetcherEmissionContext`** (package-private record/class in
   the generators package) carrying a mutable `EnumSet<HelperKind>`. Today
   `HelperKind` has one member, `GRAPHITRON_CONTEXT`. The shape leaves room
   for future helpers that face the same problem; the existing `scatterByIdx`,
   `scatterSingleByIdx`, `scatterConnectionByIdx`, and `emptyScatter` gates
   stay where they are — they're already forward-declared on field shape and
   don't trigger the bug class.

2. **Single emission helper** on the context:
   ```java
   CodeBlock graphitronContextCall() {
       requested.add(HelperKind.GRAPHITRON_CONTEXT);
       return CodeBlock.of("graphitronContext(env)");
   }
   ```
   Call sites change from `"graphitronContext(env)"` (literal in a format
   string) to `$L`/`$T` interpolation of the helper's return value. The helper
   does not take an env-param name; `env` is the `*Fetchers` calling
   convention and any future `dfe`-style call site is itself a refactor that
   should pass through this helper.

3. **Thread the context** from `generateTypeSpec` into every emitter that
   today writes `graphitronContext(...)`. The threading is mechanical: an
   extra parameter on `ArgCallEmitter`'s `emit*` methods,
   `LookupValuesJoinEmitter.emit*`, `SplitRowsMethodEmitter.build*`,
   `MultiTablePolymorphicEmitter.build*`, plus the in-file
   `TypeFetcherGenerator.build*` private statics. There is no thread-local
   shortcut — the explicit parameter keeps the dependency visible.

4. **Class assembly drains the set.** At the end of `generateTypeSpec`,
   replace the post-scan with:
   ```java
   if (ctx.requested().contains(HelperKind.GRAPHITRON_CONTEXT)) {
       builder.addMethod(buildGraphitronContextHelper(outputPackage));
   }
   ```
   The `builder.methodSpecs.stream().anyMatch(...)` block at `:478-482`
   deletes; the explanatory comment about "post-scan over emitted method
   bodies" deletes with it.

5. **`ConnectionHelperClassGenerator` is not migrated.** It assembles its own
   class in isolation; the call site at `:274` stays unchanged and continues
   to emit its own helper using its own (already-correct) gating.

## Test impact

- **Delete the body-string assertion** in
  `graphitronContextHelper_emittedForServiceRecordOnlyClass`
  (`TypeFetcherGeneratorTest.java:1369-1371`). The test keeps its purpose by
  asserting only that the helper method is present in
  `spec.methodSpecs()` (lines `:1372-1375` already do this). The "sanity"
  body-string check at `:1369-1371` is exactly the test-tier code-string
  pattern the principles ban.
- `graphitronContextHelper_notEmittedWhenNoBodyReferencesIt`
  (`:1379`) keeps its current shape — it already asserts only on method-name
  presence, no body strings.
- **Add a pipeline fixture** covering a service-record-only type. The schema
  in `graphitron-sakila-example` already has `@service` scalars on
  `FilmDetails` (`schema.graphqls:418`), but `FilmDetails` carries other
  field shapes too. Add a small type whose only field is a `@service` scalar
  with a batch key, so its `*Fetchers` class would have been the regression
  shape pre-fix. Compile-tier coverage in `graphitron-sakila-example` then
  catches the original "cannot find symbol: graphitronContext" failure
  without a unit-tier guard standing in for it.

## Touchpoints

- `TypeFetcherGenerator.java:478-482` — string-scan gate; deletes.
- `TypeFetcherGenerator.java:2631-2639` — `buildGraphitronContextHelper` stays;
  invocation moves under the requested-helpers drain.
- `ArgCallEmitter.java:115,159` — switches to context-helper call.
- `LookupValuesJoinEmitter.java:411`,
  `SplitRowsMethodEmitter.java:218`,
  `MultiTablePolymorphicEmitter.java:238,347,705,815` — same.
- `TypeFetcherGenerator.java` SQL-context call sites
  (`:605,617,684,745,1005,1201,1291,1651,1829,2419,2532`) — same; thread the
  context through `buildXxx` private statics.
- `TypeFetcherGeneratorTest.java:1354,1369-1371` — drop body-string assertion.
- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` —
  add a service-record-only type fixture.

## Out of scope

- The other class-assembly gates (`scatterByIdx`, `scatterSingleByIdx`,
  `scatterConnectionByIdx`, `emptyScatter`). They're forward-declared on
  field shape and don't read emitted source; not the bug class. Migrating
  them onto `TypeFetcherEmissionContext` is a future refactor only if a
  similar string-scan ever appears for them.
- `ConnectionHelperClassGenerator`'s own `graphitronContext` helper. Separate
  class, separate assembly, separate gate. R80 is `*Fetchers` only.
- Any change to the *generated* helper signature or body. `graphitronContext`
  in the emitted output stays exactly as it is today.
