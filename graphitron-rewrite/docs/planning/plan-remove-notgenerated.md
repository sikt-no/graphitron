# Plan: Remove `@notGenerated` directive support

> **Status:** In Review

## Why

The legacy `ElementRemovalFilter` honoured `@notGenerated` by stripping marked
elements from the type-definition registry plus a reachability re-scan. The
rewrite has carried a placeholder for that path: `NotGeneratedField` is a
classified leaf, dropped by every generator's filter, then rejected at
validation time with `"@notGenerated is not supported by the rewrite pipeline; the field must be fully described by the schema."`

Continuing to model the directive as a sealed leaf keeps the surface area open
for a "skip-and-prune" implementation we no longer want to build. The directive
encodes a "let the generator partially describe this type" workflow that the
rewrite has rejected as a design principle (the schema is the source of truth).
Rather than re-implement skip-and-prune, the directive is removed from the
supported set: the rewrite keeps it defined in `directives.graphqls` only so the
GraphQL parser doesn't fail with `unknown directive`, and the classifier rejects
any application with a clear "no longer supported" error.

## Implementation

Single landing; every change is needed for the build to stay green.

**Production code**

- `graphitron-rewrite/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`; replace the directive description with a removal notice; keep the on-clause unchanged so existing user schemas parse and our validator (not GraphQL-Java) emits the error.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`
  - Drop the `NotGeneratedField` import.
  - `classifyField`: hoist the `@notGenerated` check **above** `detectChildFieldConflict` and emit `UnclassifiedField` with reason `"@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema."`. The hoist makes the rejection win over conflict detection so a field with `@notGenerated @service` reports the no-longer-supported reason rather than a misleading conflict.
  - Nested-type expansion: drop the `if (nestedDef.hasAppliedDirective(DIR_NOT_GENERATED)) continue;` skip (line ~343) and the `if (nested instanceof NotGeneratedField) continue;` arm (line ~349). The classifier now produces `UnclassifiedField` for those nested fields, which propagates up through the existing `UnclassifiedField` handling.
  - `classifyPlainInputFields`: replace the silent skip (line ~794) with an explicit `condErrors.add("input field '<type>.<field>': @notGenerated is no longer supported. Remove the directive.")` followed by `continue`. Input fields don't have an Unclassified leaf to land on, so the error string is the equivalent surface.
  - `detectChildFieldConflict`: drop `hasNotGenerated` since the hoisted check makes it unreachable.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java`; remove `NotGeneratedField` from the sealed permits and delete the record.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`; drop the `NotGeneratedField` dispatch case and the `validateNotGeneratedField` helper.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`; drop the `NotGeneratedField` filter (line ~114), the `NOT_DISPATCHED_LEAVES` entry (line ~166), and the assertion arm (line ~398-399). Update the javadoc on `NOT_DISPATCHED_LEAVES`.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/schema/FetcherRegistrationsEmitter.java`; drop the `NotGeneratedField` filter (line ~102).

`SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES` keeps `"notGenerated"`: the
directive is still defined in our SDL, so it must still be listed as
generator-only (not a survivor) for the directive-definition emitter.
`BuildContext.DIR_NOT_GENERATED` stays; the constant is still read in the new
classifier short-circuit and the input-field error path.

**Tests**

- Delete `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/validation/NotGeneratedFieldValidationTest.java`; the validated leaf no longer exists.
- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`
  - Drop the `NotGeneratedField` import (line ~35) and the `NotGeneratedFieldCase` enum + its `@ParameterizedTest` (lines ~325-350).
  - Add a `NOT_GENERATED_REJECTED` case to `UnclassifiedFieldCase` (or the closest existing classifier-rejection enum) asserting the `UnclassifiedField` reason contains `"no longer supported"`.
  - Retarget `NOT_GENERATED_AND_SERVICE_CONFLICT` (lines ~3287-3295): under the hoisted check the reason now reads "no longer supported", not the conflict message. Update the case to assert the no-longer-supported reason; the co-occurring `@service` is irrelevant once `@notGenerated` short-circuits.
  - Update the comment at line ~3235 to drop `@notGenerated` from the listed exclusive directives.
- Delete `notGeneratedField_isExcluded` (`generators/FetcherPipelineTest.java:225`) and `fieldsMethod_excludesNotGeneratedFields` (`generators/TablePipelineTest.java:98`). Their schemas no longer build; the new `UnclassifiedFieldCase` entry is the surviving coverage.

**Docs**

- `graphitron-rewrite/docs/code-generation-triggers.md:185`; drop the `@notGenerated` row; update the `**` footnote (line ~209) to reflect the surviving leaves.
- `graphitron-rewrite/docs/rewrite-model.md:31`; remove `NotGeneratedField` from the sealed-hierarchy diagram.
- `graphitron-rewrite/docs/planning/changelog.md`; landing entry calling out the breaking change for any consumer still carrying `@notGenerated` in their schema.

## Roadmap entries

`graphitron-rewrite/docs/planning/rewrite-roadmap.md:65`; replace the
"skip-and-prune" sub-item description with a note that the directive was
removed from the supported set with a clear validator error, link this plan,
flip status to `[In Review]` for the implementation commit, then `[Done]` (and
delete this plan file) on review approval.
