---
title: "`@notGenerated` directive removal"
status: In Review
priority: 13
---

# Plan: Remove `@notGenerated` directive support

## Why

The legacy `ElementRemovalFilter` honoured `@notGenerated` by stripping marked
elements from the type-definition registry plus a reachability re-scan. The
rewrite carried a placeholder for that path: `NotGeneratedField` was a
classified leaf, dropped by every generator's filter, then rejected at
validation time with `"@notGenerated is not supported by the rewrite pipeline; the field must be fully described by the schema."`

Continuing to model the directive as a sealed leaf kept the surface area open
for a "skip-and-prune" implementation we no longer want to build. The directive
encodes a "let the generator partially describe this type" workflow that the
rewrite has rejected as a design principle (the schema is the source of truth).
Rather than re-implement skip-and-prune, the directive is removed from the
supported set: the rewrite keeps it defined in `directives.graphqls` only so
the GraphQL parser doesn't fail with `unknown directive`, and the classifier
rejects any application with a clear "no longer supported" error.

## What landed (commit `92bcfda`)

- `directives.graphqls` description rewritten to a removal notice; on-clause unchanged.
- `FieldBuilder.classifyField`: `@notGenerated` short-circuits to `UnclassifiedField` with reason `"@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema."`, hoisted above `detectChildFieldConflict` so a `@notGenerated @service` field reports the no-longer-supported reason rather than a misleading conflict. `hasNotGenerated` removed from `detectChildFieldConflict`.
- `FieldBuilder` nested-type-expansion skip removed (the classifier now produces `UnclassifiedField` for `@notGenerated` nested fields and propagates up).
- `FieldBuilder.classifyPlainInputFields` silent skip replaced with an explicit `condErrors` entry for the plain-input-arg path.
- `NotGeneratedField` record + sealed permit deleted; `GraphitronSchemaValidator.NotGeneratedField` dispatch case and `validateNotGeneratedField` helper deleted; `NotGeneratedField` filters in `TypeFetcherGenerator` and `FetcherRegistrationsEmitter` deleted (including the `NOT_DISPATCHED_LEAVES` entry and the assertion arm).
- `directives.graphqls` keeps the `@notGenerated` directive declared so user schemas still parse; `SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES` keeps `"notGenerated"` so the directive-definition emitter still treats it as generator-only.
- Tests: `NotGeneratedFieldValidationTest` deleted; `GraphitronSchemaBuilderTest.NotGeneratedFieldCase` collapsed into a `NOT_GENERATED_DIRECTIVE_REJECTED` entry under `UnclassifiedFieldCase`; `NOT_GENERATED_AND_SERVICE_CONFLICT` deleted (now subsumed by the short-circuit); `notGeneratedField_isExcluded` and `fieldsMethod_excludesNotGeneratedFields` deleted (their schemas no longer build).
- Docs: `code-generation-triggers.md` table + footnote updated; `rewrite-model.md` diagram pruned; changelog entry added; roadmap sub-item flipped to `[In Review]` linking this plan.

## What remains (this revision)

Review of `92bcfda` flagged that the "input fields now emit an explicit error"
claim is only half implemented. Two silent-skip filters survived the first
landing and still strip `@notGenerated` from input fields before the new
explicit error path can fire. Closing the gap means centralising the
rejection so every input-field call site surfaces the same error.

**Production code**

- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`
  - `classifyInputField`: short-circuit at the top with `if (field.hasAppliedDirective(DIR_NOT_GENERATED)) return new InputFieldResolution.Unresolved(field.getName(), null, "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");`. Every input-field call path converges through this method, so a single rejection covers `TypeBuilder.buildTableInputType`, the nested-input recursion below, and `FieldBuilder.classifyPlainInputFields`.
  - Nested-input expansion: drop the `.filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED))` skip so the field flows into the recursive `classifyInputField` call and the central rejection fires.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`
  - `buildInputType`: drop the `filteredFields` local; pass `inputType.getFieldDefinitions()` directly into `buildTableInputType`. The `@notGenerated` field then reaches `classifyInputField`, comes back as `Unresolved` with the no-longer-supported reason, and surfaces as `UnclassifiedType` "mapped to table 'X' — unresolvable fields: '<field>': @notGenerated is no longer supported…".
  - Drop the `DIR_NOT_GENERATED` import (no remaining reader in this file).
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`
  - `classifyArgument`: the plain-input branch's per-field errors are silently dropped by `projectFilters` unless paired with a `@condition` / `@lookupKey` gate (the design intentionally drops column-miss errors). `@notGenerated` is a hard policy violation, so reject the whole arg up front: pre-walk the input type's top-level field definitions, and if any carries `@notGenerated`, return `ArgumentRef.UnclassifiedArg` with the no-longer-supported reason. `UnclassifiedArg` already routes through the existing `hadError` path in `projectFilters`, surfacing as `UnclassifiedField` on the surrounding query field.
  - `classifyPlainInputFields`: drop the now-redundant local `@notGenerated` check (the explicit `condErrors.add(...)` line previously added by `92bcfda` was dead — it never surfaced because `projectForFilter`'s gates don't trigger on unrelated errors). The central rejection in `classifyInputField` (above) covers any nested `@notGenerated` field via the recursive `classifyInputField` call returning `Unresolved`; the pre-walk in `classifyArgument` covers top-level fields. Update the javadoc to point at `classifyArgument` as the source of `@notGenerated` rejection.

**Tests**

- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`
  - Add a `NOT_GENERATED_REJECTED` case to `TableInputTypeCase` with SDL `input CustomerInput @table(name: "customer") { customerId: Int! @field(name: "customer_id"), hidden: String @notGenerated } type Query { x: String }`, asserting the type classifies as `UnclassifiedType` with reason containing `"@notGenerated"` and `"no longer supported"`.
  - Add a `NOT_GENERATED_REJECTED_NESTED` case (or sibling) covering a `@notGenerated` field on a plain input object nested inside a `@table` input, asserting the same `UnclassifiedType`-with-reason surface.
  - Add a `NOT_GENERATED_REJECTED_PLAIN_ARG` case covering a `@notGenerated` field on a plain input arg (the `classifyPlainInputFields` path), asserting the build-time error contains `"@notGenerated"` and `"no longer supported"`. (Vehicle for this assertion depends on how plain-input errors are currently surfaced in tests; reuse the existing build-error capture if one exists, otherwise add via `assertThatThrownBy(() -> build(sdl))` against the aggregated message.)

**Docs**

- `graphitron-rewrite/docs/planning/changelog.md`: amend the existing `@notGenerated` entry to note that the input-field path now centralises the rejection in `BuildContext.classifyInputField` and that `TableInputType` / nested-input fields also surface the no-longer-supported reason. Reconcile the test-count drift between the commit message ("733") and the changelog ("703") with the post-fix count.

## Roadmap entries

`graphitron-rewrite/docs/planning/rewrite-roadmap.md` sub-item already flipped
to `[In Review]` linking this plan. On review approval of this revision, flip
to `[Done]` and delete this plan file.
