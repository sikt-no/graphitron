# Bug: `@table` + `@record` Input Types Fail Validation in Rewrite

> **Status:** Pending Review
>
> C1–C4 landed on `claude/review-argument-handling-avbee`. Classifier now treats `@record` as authoritative on an input also carrying `@table`, emits a `BuildWarning` naming the shadowed directive, and classifies the input as if only `@record` were declared. Warnings flow through `GraphitronSchema.warnings()` to the Maven mojos. 471/471 unit tests green.

Affects: rewrite classifier/validator — does not affect the legacy code generator.

## Problem

An input type annotated with both `@table` and `@record` validates successfully in the legacy code generator but fails with "unresolvable fields" errors in the rewrite.

Example:

```graphql
input OpprettStudieoppbygningsdelerChildInput
    @table(name: "EMNEKOMB_I_EMNEKOMB")
    @record(record: {className: "...OpprettStudieoppbygningsdelerChildRecord"}) {
  childStudieoppbygningsdelId: ID!
  rekkefolgenummer: Int
  erLukket: Boolean
  # ...
}
```

Error:

```
Type 'OpprettStudieoppbygningsdelerChildInput': mapped to table 'EMNEKOMB_I_EMNEKOMB'
— unresolvable fields: 'childStudieoppbygningsdelId': field has no matching column
and no accessor methods (getChildStudieoppbygningsdelId/setChildStudieoppbygningsdelId)
found on record class; 'rekkefolgenummer': no column 'rekkefolgenummer' found in
table 'EMNEKOMB_I_EMNEKOMB'; ...
```

## Semantic of the combination

The two directives address different axes:

- `@record` declares how the GraphQL input's values surface to Java — an explicit
  `className` whose getters/setters match the input's fields 1:1.
- `@table` declares the conceptual target table of the operation using this input. In the real
  schemas we checked, it aligns with the **return type** of the `@service` method that consumes
  the input (e.g. a service returning `List<EmnekombinasjonRecord>` paired with an input
  `@table(name: "EMNEKOMBINASJON")`).

On paper the two axes are orthogonal, but in practice the rewrite has no classifier arm that
reads `@table` on a `@record` input and does something useful with it. See the legacy audit
below: the combination is functionally equivalent to `@record` alone in legacy, with one
accidental exception.

## Legacy audit — does `@table` still do work when `@record` is present?

Six legacy paths explicitly skip when `hasJavaRecordReference()` is true:

| File | Line | Effect |
|---|---|---|
| `InputParser.parseInputs` | 98 | Skips `@table` flattening |
| `TableValidator.validateRequiredTableFields` | 159 | Skips per-field column validation |
| `RecordValidatorMethodGenerator.generateAll` | 88 | Skips validator method generation |
| `TransformerMethodGenerator.useValidation` | 113 | Skips record validation |
| `AbstractMapperMethodGenerator.getMapperSpecBuilder` | 65 | Skips context declaration |
| `RecordMapperClassGenerator.filterProperties` | 29 | Skips mapper generation |

One validator path **does not** gate on `hasJavaRecordReference()`:

- `NodeValidator.validateNodeIdReferenceInJooqRecordInput` (line 211) — filters only on
  `hasTable()`. On an input with `@table + @record`, it will process `@nodeId` fields and
  validate foreign keys and PK overlap against the declared table.

The method name ("JooqRecordInput") suggests the gate was forgotten rather than deliberate.
The affected schema in this report has not adopted `@nodeId` at all — the documented
`@nodeId(typeName: "...")` path handles that case elsewhere — so this leakage is moot for the
bug's real-world impact.

**Conclusion:** `@table` on a `@record` input is effectively noise. The combination is an
undocumented accident that works in legacy because every relevant code path either skips it
or uses the `@record` class instead. The rewrite should call this out rather than treat it as
a supported configuration.

## Decision

On a `@record`-annotated input, **`@record` is the authoritative classifier** and `@table` is
ignored with a build warning. The input is classified as if only `@record` were present.

The warning is compatibility-preserving, not deprecation-tentative — schemas using the
combination continue to build. The long-term intent is that users clean up `@table` from
`@record` inputs, after which the warning disappears naturally.

## Fix plan

### Step 1 — Introduce a warnings channel on the builder

No warnings channel exists today (the only existing `LOG.warn` is for a one-off deprecation
message in `FieldBuilder`). This step is shared with item 2 of
[`plan-classification-vocabulary-followups.md`](plan-classification-vocabulary-followups.md#2-emit-a-build-warning-for-splitquery-on-a-result-mapped-parent)
and should be designed to serve both cases.

- Add `BuildWarning` record: `(String message, SourceLocation location)`, shape-parallel to
  `ValidationError`.
- Extend `GraphitronSchema` with a `warnings()` accessor, surfaced at construction time by
  `GraphitronSchemaBuilder`.
- `TypeBuilder` and `FieldBuilder` collect warnings on the existing `BuildContext` (or a
  dedicated `warnings` list passed through the same call sites as `errors`).
- `ValidateMojo` and `GenerateMojo` surface each warning as `getLog().warn(...)` alongside the
  existing error-as-warning output. Format matches `ValidateMojo.java:49-55`
  (file:line:col: message).

### Step 2 — Detect `@table + @record` on inputs and route through the `@record` path

In `TypeBuilder.buildInputType` (currently lines 335–361):

```java
if (inputType.hasAppliedDirective(DIR_TABLE) && inputType.hasAppliedDirective(DIR_RECORD)) {
    ctx.addWarning(new BuildWarning(
        "Input type '" + name + "': @table is shadowed by @record and will be ignored. "
        + "This combination is not supported — remove @table from this input.",
        locationOf(inputType)));
    return buildNonTableInputType(inputType, name, location);
}
if (inputType.hasAppliedDirective(DIR_TABLE)) {
    // existing path (lines 342–348)
    ...
}
```

The warning is emitted exactly once per input type. `buildNonTableInputType` already handles
the `@record` classification correctly — the bug was only that `@table` short-circuited the
dispatch before `@record` got a chance.

### Step 3 — Tests

- **Pipeline test** in `GraphitronSchemaBuilderTest`: schema with `@table + @record` input,
  assert (a) classification succeeds, (b) the resulting type is a
  `JavaRecordInputType`/`JooqTableRecordInputType`/`JooqRecordInputType`/`PojoInputType`
  matching the resolved `@record(className:)`, (c) exactly one warning is reported with the
  expected message substring.
- **Negative test**: schema with `@table` alone (no `@record`) still produces a
  `TableInputType` — confirm we haven't regressed the pure-`@table` path.
- **Reference schema**: add a test case in `graphitron-rewrite-test-spec` using the shape from
  this bug report (scalars + nested inputs + lists) so future refactors keep this working
  end-to-end.

### Step 4 — Docs

- Add a note to
  [`code-generation-triggers.md`](../code-generation-triggers.md) under the input-type section:
  "On input types, `@record` dominates `@table`. If both are present, `@table` is ignored and
  the input classifies as if only `@record` were declared; a build warning is emitted."
- Link this file from the roadmap's Active section so the fix is tracked.

## Out of scope

- **Broadening the `ID`-only accessor fallback to all field types.** With the `@record` path
  now reached, the fallback isn't needed for this bug. A separate concern worth filing:
  `hasPlatformIdAccessors` looks up the jOOQ table record, not the `@record(className:)` class
  — that's a distinct bug in the legacy platform-id path.
- **The `NodeValidator` ungated path in legacy.** Out of scope here because the affected
  schema doesn't use that feature, and it's a legacy-only concern: the rewrite rebuilds
  `@nodeId` handling from scratch and will either support `@nodeId` on `@record` inputs
  deliberately or reject it.
- **Rejecting `@table + @record` on inputs outright.** Not chosen because the combination is
  widespread in real schemas. Warning-first preserves compatibility; a later ratchet to
  rejection (if any) is a separate decision.

## Affected files (landed)

- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java` — `buildInputType` gains a precedence check for `@table` + `@record` that routes to `buildNonTableInputType` and adds a `BuildWarning`.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchema.java` — `warnings()` accessor added; three-arg convenience constructor for the builder.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java` — private `warnings` list, `addWarning(BuildWarning)` and `warnings()` accessor.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/BuildWarning.java` — new record, shape-parallel to `ValidationError`.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilder.java` — threads `ctx.warnings()` into the constructed schema.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphQLRewriteGenerator.java` — surfaces each warning via its SLF4J `LOGGER.warn` using the same `file:line:col: ...` format the error path uses.
- `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java` — surfaces warnings via `getLog().warn(formatWarning(...))` right after the builder call, before the validator runs.
- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` — new `InputTypeCase.TABLE_PLUS_RECORD` case asserts the input classifies as `JooqTableRecordInputType` and the warning is emitted with the expected message.
- `docs/code-generation-triggers.md` — table row added for the `@table` + `@record` combination, explaining `@record` dominates and the clean-up path.

Not touched: `GenerateMojo.java` — it calls `GraphQLRewriteGenerator.generate()`, which now surfaces warnings inline via its own SLF4J logger. The mojo doesn't need a separate integration point.
