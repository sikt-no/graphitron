---
id: R205
title: 'Plain-input filter fields drop resolved-column implicit predicates and Unresolved diagnostics'
status: Spec
bucket: bugs
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Plain-input filter fields drop resolved-column implicit predicates and Unresolved diagnostics

Plain-input (non-`@table`) filter input types classified at the call site against the surrounding query field's target table diverge from `@table` input types in two independent ways, both silent:

1. **Resolved un-annotated `ColumnField` / `ColumnReferenceField` on a plain input contributes no predicate.** The `PlainInputArg` branch of `FieldBuilder.projectFilters` (`FieldBuilder.java:1344-1350`) passes `null` for `walkInputFieldConditions`' `implicitBodyParams` parameter; the same code path in the `TableInputArg` branch immediately above (`:1337-1342`) allocates an `ArrayList<BodyParam>` and adds it to `bodyParams`. Result: an un-annotated `filmId: ID! @field(name: "film_id")` on a `@table` input generates `WHERE film.film_id = ?`; the structurally identical field on a plain input generates nothing.
2. **`Unresolved` results from `InputFieldResolver.resolve` are silently filtered out** at `InputFieldResolver.java:65-67`. The `InputFieldResolution.Unresolved` arm carries `fieldName`, `lookupColumn`, and `reason` whose entire purpose is to surface the diagnostic, but no consumer reads them — even though `TypeBuilder.buildTableInputType` at `:1031-1066` handles the same sealed result correctly by collecting failures, synthesising a candidate-hint, and rejecting the surrounding type.

The combined effect is the symptom alf reported in `opptak-subgraph`: filter inputs (`SakFilterV2Input`, `OpptakFilterInput`) where every field either lacks `@condition` or has a name that doesn't resolve to a column generate `return DSL.noCondition();` at codegen, and the filter does nothing at runtime. Each field looks well-formed per `directives.graphqls`; nothing in the build surface tells the schema author the field is dead.

## How this gap survived

Five layers that could have caught the symmetry break each had a plausibly-worded reason not to. Naming the layers is part of the fix so the next contributor doesn't recreate any of them.

1. **Design doc says symmetric, code is asymmetric.** `docs/argument-resolution.adoc:44-46` ("The input-field position covers both `@table`-annotated input types (primary case) and plain input types used under the legacy 'implicit-table' heuristic, where the input's fields resolve against the enclosing query field's target table.") and the truth table at `:262-275` make no `@table` / plain distinction. The `null` argument at `FieldBuilder.java:1349` contradicts both, with a comment at `:1346` pointing at a stale doc reference (`docs/argument-resolution.md` — file is `.adoc`) to a non-existent "out-of-scope note."
2. **Classifier-tier tests assert the type, not the projection.** `GraphitronSchemaBuilderTest`'s plain-input `@condition` cases (`ARG_CONDITION_OVERRIDE`, `FIELD_CONDITION_OVERRIDE`, `INPUT_FIELD_CONDITION_OVERRIDE` at `:4072, :4083, :4170`) assert `schema.type(...) isInstanceOf PojoInputType.class` and stop there. The `@table` siblings (`COLUMN_FIELD_WITH_CONDITION`, `COLUMN_REFERENCE_FIELD_WITH_CONDITION`, `NESTING_FIELD_WITH_CONDITION` at `:4106-4168`) assert `it.inputFields().get(0).condition()` is populated. The asymmetric assertion shape masked the asymmetric projection.
3. **The projection meta-test explicitly allowlisted `PojoInputType`.** `ProjectionCoverageTest.NO_PROJECTION_REQUIRED` at `:65-69`: "PojoInputType lands via a @record-bound input fixture; the type-level classifier produces it but pipeline-tier fixtures cover it implicitly through their input-field walks rather than as a top-level projection assertion target." The rationale was true for the absent `@table` walks and false for the present `PlainInputArg` walks; the meta-test allowed the gap because the prose read plausibly without verifying which walks were involved.
4. **The execution-tier negative test encoded the bug as expected behaviour.** `GraphQLQueryTest.inputFieldCondition_plainInput_languageTable_fieldNotResolved_noFilterApplied_returnsAllLanguages` at `:1907-1917` asserts "all languages returned" with the comment "classifyPlainInputFields skips the field → PlainInputArg.fields is empty → walkInputFieldConditions adds nothing → field.filters() is empty → no WHERE." The test passes today because the broken behaviour is the asserted behaviour.
5. **The Phase 4 handoff named the follow-up `@table`-only.** `roadmap/changelog.md:243`: "Auto-column binding for `@table` input types (63 alf call sites) spun out as its own Active plan." The follow-up's title scoped itself to `@table` inputs; plain inputs were never in scope, never deferred, never anyone's job. The fixture comments at `schema.graphqls:115, :156, :165, :168` (all under `# implicit-input-conditions plan:`) sit exclusively on `@table` inputs.

## Direction

Five concrete changes, all landing in the same item:

### Step 1: Restore the implicit-predicate symmetry

`FieldBuilder.projectFilters`' `PlainInputArg` branch (`:1344-1350`) becomes structurally identical to the `TableInputArg` branch (`:1337-1342`):

```java
case ArgumentRef.InputTypeArg.PlainInputArg pia -> {
    pia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
    boolean enclosingOverride = fieldOverride
        || pia.argCondition().map(ArgConditionRef::override).orElse(false);
    var implicitParams = new ArrayList<BodyParam>();
    walkInputFieldConditions(pia.fields(), pia.name(), List.of(),
        enclosingOverride, Set.of(), implicitParams, argConditions);
    bodyParams.addAll(implicitParams);
}
```

The differences from `TableInputArg` collapse to: no `@lookupKey` binding set (plain inputs don't carry `LookupKeyField`s), and the override-accumulator starts from `pia.argCondition().override()` instead of the table-input arg's `tia.argCondition().override()`. Both branches now wire the truth table at `argument-resolution.adoc:262-275` identically.

The "Plain input types are silently skipped unless paired with @condition" comment at `:1345-1346` goes; the stale `docs/argument-resolution.md` reference goes; both are part of the cleanup, not collateral.

### Step 2: Restore the sealed contract at `InputFieldResolver.resolve`

Once Step 1 lands, the `Unresolved` arm is no longer "doesn't matter, plain inputs are silent anyway." A field that doesn't resolve to a column is structurally indistinguishable from a typo, and the implicit-predicate path can't emit anything for it. Today the field is dropped silently; with Step 1 the user sees "filter has no effect" instead of "filter applies to my method"; either way the schema is broken and the author should know.

Change `InputFieldResolver.resolve`'s signature to a sealed result (matching the resolver-sibling shape — `OrderByResolver.Resolved`, `ConditionResolver.ArgConditionResult`):

```java
sealed interface Resolution {
    record Ok(List<InputField> fields) implements Resolution {}
    record Rejected(String message) implements Resolution {}
}
```

`Rejected` is produced when the input contains at least one `Unresolved`. Its `message` is synthesised at construction time by `InputFieldResolver`: enumerate the `Unresolved` results as `"input field '<fieldName>': <reason>"`; append the first non-null `lookupColumn` candidate-hint via `BuildContext.candidateHint(column, ctx.catalog.columnJavaNamesOf(rt.tableName()))` matching `TypeBuilder.buildTableInputType:1047-1052` exactly; prefix with `"plain input type '<typeName>': "`.

`FieldBuilder.classifyArgument` (`:1006-1008`) switches on the new `Resolution`:
- `Ok(fields)` constructs `PlainInputArg` as today.
- `Rejected(message)` returns `ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list, message)`, mirroring the `@notGenerated` precedent at `FieldBuilder.java:985-1005` exactly.

The `errors` out-param on `InputFieldResolver.resolve` goes away; accumulation happens via the existing `UnclassifiedArg` path in `projectFilters` at `:1351-1354`.

### Step 3: Lock the symmetry with a meta-test

Drop the `PojoInputType.class` entry from `ProjectionCoverageTest.NO_PROJECTION_REQUIRED` (`:65-69`). The meta-test now requires every `GraphitronType` projection leaf — `PojoInputType` included — to surface in at least one `ClassificationCase` with a real projection assertion.

To pass the now-stricter meta-test, the existing `GraphitronSchemaBuilderTest` plain-input cases (`ARG_CONDITION_OVERRIDE`, `FIELD_CONDITION_OVERRIDE`, `INPUT_FIELD_CONDITION_OVERRIDE`) gain a `variants()` override returning `Set.of(PojoInputType.class)` paired with a projection-shape assertion: the surrounding query field classifies as `QueryField.QueryTableField` whose `filters()` includes a `GeneratedConditionFilter` whose `bodyParams()` contains the expected `BodyParam.Eq` / `BodyParam.In`. This is the assertion shape `FilmImplicitInput`'s `@table` analogue uses at the `COLUMN_FIELD_WITH_CONDITION` family; the symmetry is asserted directly.

Add one new `ClassificationCase` exercising the bare plain-input-resolved-no-condition case: `input PlainFilter { filmId: Int! @field(name: "film_id") }` used as `films(filter: PlainFilter): [Film]`. Assert `PlainInputArg.fields()` contains exactly one `InputField.ColumnField` (no `condition`); the projected `GeneratedConditionFilter.bodyParams()` contains exactly one `BodyParam.Eq` on `film.film_id`. This is the assertion the existing test suite is missing entirely — without it the symmetry can re-break and no test fails.

### Step 4: Documentation-vs-implementation cleanup

Three drift sites land in the same change:

- **`InputFieldResolver.java:30-41`** — the class-level javadoc paragraph rationalising the silent skip (`"Silently skips fields that fail column resolution; their conditions cannot be built, and the caller's accumulating errors list will surface the structural problem via the outer-argument classification path. … No sealed result wrapper: the projection is total …"`) is replaced by a description of the new sealed `Resolution` shape and the contract that every `Unresolved` becomes a `Rejected.message`. Leaving the rationalising prose intact would re-license the violation for the next contributor.
- **`FieldBuilder.java:1345-1346`** — the `"Plain input types are silently skipped unless paired with @condition; see the out-of-scope note in docs/argument-resolution.md."` comment goes. The file name is wrong (`.adoc`, not `.md`), the section it cites doesn't exist (`docs/argument-resolution.adoc:429-437` lists Mutations and `NodeIdField` with `@condition`, nothing about plain-input implicit predicates), and the behaviour it documents is the bug.
- **`docs/argument-resolution.adoc:262-275`** truth table — pinned to the new pipeline test from Step 3 via a `<<>>` xref so future contributors can find the test that enforces the table, mirroring how `typed-rejection.adoc` cross-references its resolver siblings. If the truth table can't be enforced (the table claims something the test doesn't pin), the entry is wrong; either the test grows or the table loses the row, but the two co-evolve.

### Step 5: Convert the `languagesByPlainInput` fixture

`schema.graphqls:85` and `GraphQLQueryTest.java:1907-1917` (`inputFieldCondition_plainInput_languageTable_fieldNotResolved_noFilterApplied_returnsAllLanguages`) encode the silent-drop as expected behaviour by name and by assertion. Both go.

The replacement is either (a) delete outright (the symmetric-shape coverage in Step 3 supersedes the "all rows returned" assertion), or (b) repurpose as a build-rejection fixture by leaving `filmId` unresolved against `Language` and asserting the pipeline-test rejection from Step 2 — `films` against `Language` schema classifies as `UnclassifiedField` with a `Rejection` message containing `"PlainFilmIdInput"`, `"filmId"`, and `"no column 'film_id' found in table 'language'"`. The implementer picks (a) or (b). The existing `filmsByPlainInput` Film-side test continues to pin the `@condition` projection path.

## Out of scope

- A project-wide audit of "where else does the design doc say X and the code do Y." The five-layer survival pattern named in § "How this gap survived" is plausibly not unique to this surface, but a comprehensive sweep is a substantial separate item (audit method, sweep depth, owner all need their own Spec). Filed as a follow-up Backlog stub `design-doc-implementation-conformance-audit` rather than absorbed here.
- `@table` input fields whose column resolves and carry no `@condition`: that path already works (`FilmImplicitInput` family at `schema.graphqls:155-171`); the symmetry restoration in Step 1 brings plain inputs up to that path, not the other way around.
- Argument-level `@condition` on the outer filter argument: routes through `pia.argCondition()` at `:1347` and is unchanged.
- `directives.graphqls` updates: the retired-directive pattern leaves locations declared so the parser does not fail with "unknown directive location"; nothing changes there.
- `@service` query fields with plain-input args: those route through `buildServiceField` at `FieldBuilder.java:2957` and never reach `InputFieldResolver.resolve`, so they're unaffected by Steps 1-3.
- The roadmap-tool YAML footgun where `status` subcommand drops quotes around a `@`-prefixed title and leaves the file un-parseable: separate maintenance fix; the workaround is single-quotes around the title.

## Acceptance tests

Pipeline-tier, model-shape assertions throughout; no generated-source-string assertions (per `docs/rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural tier": "Code-string assertions on generated method bodies are banned at every tier").

1. **Symmetric implicit-predicate emission.** Schema: `input PlainFilter { filmId: Int! @field(name: "film_id") }` against `films(filter: PlainFilter): [Film]`. Assert: `PlainInputArg.fields()` contains exactly one `InputField.ColumnField` with `condition() == Optional.empty()`; the projected `GeneratedConditionFilter.bodyParams()` contains exactly one `BodyParam.Eq` on `film.film_id`. **This is the test that's missing entirely today.**
2. **Symmetric explicit-method-plus-implicit composition.** Schema: `input PlainFilter { filmId: Int! @field(name: "film_id") @condition(condition: {className: "...", method: "filmIdCondition"}) }`. Assert: the projected `WhereFilter` list contains both a `GeneratedConditionFilter` carrying `BodyParam.Eq` on `film.film_id` AND a `ConditionFilter` pointing at `filmIdCondition`. The pre-existing `filmsByPlainInput` execution-tier test stays as the runtime pin.
3. **Override propagation parity.** Schema: `input PlainFilter { filmId: Int! @field(name: "film_id") @condition(..., override: true) }`. Assert: no implicit `BodyParam.Eq` (override suppresses it); `ConditionFilter` for `filmIdCondition` present. Mirrors the `@table`-input override behaviour pinned by `filmsWithInputFieldOverride`.
4. **Rejection on Unresolved.** Schema: `input PlainFilter { name: String @condition(...) }` against a query whose target table has no column `name`. Assert: the surrounding `QueryField` classifies as `GraphitronField.UnclassifiedField`; its `Rejection` message contains the input type name, the field name, and the underlying column-miss reason.
5. **Candidate-hint surfaces.** Same shape but `saksKode` against a table with column `sakskode`. Assert: the `UnclassifiedField.Rejection` message contains the `candidateHint` output (`"; did you mean: 'sakskode'"` or whatever the existing helper formats to), copied from `TypeBuilder.buildTableInputType`'s pattern.
6. **Meta-test: `ProjectionCoverageTest` no longer allowlists `PojoInputType`.** With the `NO_PROJECTION_REQUIRED` entry removed, the test must pass without it. Tests 1-3 above provide the projection cases that satisfy the meta-test for `PojoInputType`.

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolver.java:30-41, :57-71` — sealed `Resolution`, drop the rationalising javadoc.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolution.java:15-25` — unchanged; sealed `Resolved | Unresolved` continues to carry `fieldName`, `lookupColumn`, `reason`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:985-1005` — `@notGenerated` precedent; `:1006-1008` — `classifyArgument` PlainInputArg construction (switch on `Resolution`); `:1337-1350` — `projectFilters` branches for both ArgumentRef variants; `:1351-1354` — `UnclassifiedArg` propagation (unchanged).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java:1031-1066` — read-only reference for the candidate-hint shape.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:4072-4176` — plain-input classification cases to extend with projection-shape assertions; new bare-resolved-no-condition case.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/ProjectionCoverageTest.java:65-69` — `PojoInputType.class` allowlist entry to delete.
- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:79-85, :110, :1000-1006` — fixture changes for Step 5.
- `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java:1907-1917` — `languagesByPlainInput` execution-tier assertion that goes away.
- `docs/argument-resolution.adoc:44-46, :262-275, :429-437` — design-doc anchor (Step 4 cross-references the truth table to the new pipeline test).
- `docs/typed-rejection.adoc` § "Sealed `Resolved` across the resolver siblings" — principle anchor for the sealed-signature change.
- `roadmap/changelog.md:243` — Phase 4 spin-out language; documented here for the next contributor to read.
