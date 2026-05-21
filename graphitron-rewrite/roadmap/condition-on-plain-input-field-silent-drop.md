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

A sixth layer worth surfacing because Step 2 hits it directly: `docs/argument-resolution.adoc:400-412` carries an explicit rationale ("Plain-input classification failure: per-field skip, not whole-arg failure") that defends the silent-skip behaviour using `PlainFilmIdInput`-on-Language as the worked example. The doc paragraph asserts cross-table input reuse as a feature; in practice the only schema that exercises the pattern is the test fixture itself, and cross-table reuse where columns don't match everywhere is bad API design rather than a load-bearing capability. Step 4 retires that paragraph along with the other rationalising prose.

## Direction

Five concrete changes, all landing in the same item:

### Step 1: Restore the implicit-predicate symmetry, eliminate the null overload

Two co-equal moves in one change:

**(a)** Change `walkInputFieldConditions`' signature so `implicitBodyParams` is never `null`. The method always takes a non-null `List<BodyParam>`; the per-call decision of whether to drain it into the outer `bodyParams` lives at the call site. The current `if (implicitBodyParams != null && …)` guards at `FieldBuilder.java:1511, :1524, :1546` collapse to the body-param-emission predicate without the nullability check. The structural property "every `InputTypeArg` branch in `projectFilters` walks `walkInputFieldConditions` with a non-null `implicitBodyParams`" becomes a type-system fact rather than a per-call meta-test invariant. The bug from §1.1 cannot be reintroduced by accident because the type signature no longer permits it.

**(b)** `FieldBuilder.projectFilters`' `PlainInputArg` branch (`:1344-1350`) becomes structurally identical to the `TableInputArg` branch (`:1337-1342`):

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

The differences from `TableInputArg` collapse to: no `@lookupKey` binding set (plain inputs don't carry `LookupKeyField`s), and the override-accumulator seeds from `pia.argCondition().override()` instead of the table-input arg's `tia.argCondition().override()`. Both branches now wire the truth table at `argument-resolution.adoc:262-275` identically.

The "Plain input types are silently skipped unless paired with @condition" comment at `:1345-1346` goes.

### Step 2: Restore the sealed contract at `InputFieldResolver.resolve`

`InputFieldResolver.resolve` returns a sealed `Resolution`, matching `OrderByResolver.Resolved.{Ok, Rejected}` — two arms, no `None` (`Ok` absorbs the empty-fields case where `rt` is null or the type is not an input object):

```java
sealed interface Resolution {
    record Ok(List<InputField> fields) implements Resolution {}
    record Rejected(Rejection rejection) implements Resolution {}
}
```

`Rejected.rejection` is a typed `Rejection` carrying the structured payload (`attempt`, `candidates`, `attemptKind`) so LSP fix-its and watch-mode formatters consume the same data the build-log surface renders — the principle pinned at `docs/typed-rejection.adoc` § "Sealed `Resolved` across the resolver siblings": "rejection is a typed sibling of success, not a return-string-or-null."

`Rejected` fires when the input contains at least one `InputFieldResolution.Unresolved`, **regardless of whether the field carried `@condition`**. Any `Unresolved` is a build error: the field declared by the schema isn't backed by anything codegen can emit, and the cross-table-reuse pattern the existing doc section at `:400-412` defends has no real production-schema instances (the only fixture is `PlainFilmIdInput` on Language, which is the fixture-encodes-the-bug case from § "How this gap survived" layer 4). Bare-field-without-`@condition` signals intent just as much as `@condition`-annotated does; a schema author who writes `name: String` in an input type and applies it where `name` doesn't resolve has a typo or bad API design either way.

Construction:

- **Single `Unresolved` with non-null `lookupColumn`** → `Rejection.unknownColumn(summary, lookupColumn, ctx.catalog.columnJavaNamesOf(rt.tableName()))`, where `summary` is `"plain input type '<typeName>', input field '<fieldName>'"`. `AuthorError.UnknownName.message()` synthesises the candidate-hint internally; the structured `attempt` + `candidates` ride the typed payload to consumers that want the structured data.
- **Multiple `Unresolved` results, or `Unresolved` with null `lookupColumn`** (path-resolution failures, nesting failures) → `Rejection.structural(joinedReasons)` where `joinedReasons` enumerates each `Unresolved` as `"input field '<fieldName>': <reason>"` joined by `; `, prefixed with `"plain input type '<typeName>': "`. Mirrors `TypeBuilder.buildTableInputType:1053-1054`.
- **`@condition` reflection errors** (the `condErrors` channel that `BuildContext.buildInputFieldCondition` appends to today via the `errors: List<String>` out-param at `BuildContext.java:1494, :1499, :1503, :1511`) fold into the same `Rejected` arm. `InputFieldResolver.resolve` catches its own `condErrors` buffer (already private to the method at `:61`) and lifts non-empty buffers into `Rejection.structural` — no `condErrors` escapes to the outer caller's `errors: List<String>` because the outer caller's `errors` parameter goes away entirely. Single sealed failure channel, no second un-modeled mutation path.

`FieldBuilder.classifyArgument` (`FieldBuilder.java:1006-1008`) switches on the new `Resolution`:

- `Ok(fields)` constructs `PlainInputArg` as today.
- `Rejected(rejection)` returns `ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list, rejection.message())`, mirroring the `@notGenerated` precedent at `FieldBuilder.java:985-1005`. (Open question for the implementer: whether `UnclassifiedArg` should grow a typed `Rejection` payload rather than a `String reason` so the typed-rejection chain runs all the way to the validator surface. If `UnclassifiedArg` only carries `String`, a separate small refactor lifts it to `Rejection`; that's appropriate to absorb here if the touch is light, otherwise file as a follow-up. Currently `UnclassifiedArg` carries `String`; `UnclassifiedField` carries `Rejection`. Make the call during implementation; the spec doesn't pin it because both shapes preserve the architectural property R205 cares about.)

The `errors` out-param on `InputFieldResolver.resolve` goes away.

### Step 3: Lock the symmetry with a meta-test

Drop the `PojoInputType.class` entry from `ProjectionCoverageTest.NO_PROJECTION_REQUIRED` (`:65-69`). The meta-test now requires every `GraphitronType` projection leaf — `PojoInputType` included — to surface in at least one `ClassificationCase` with a real projection assertion.

To pass the now-stricter meta-test, the existing `GraphitronSchemaBuilderTest` plain-input cases (`ARG_CONDITION_OVERRIDE`, `FIELD_CONDITION_OVERRIDE`, `INPUT_FIELD_CONDITION_OVERRIDE`) gain a `variants()` override returning `Set.of(PojoInputType.class)` paired with a projection-shape assertion: the surrounding query field classifies as `QueryField.QueryTableField` whose `filters()` includes a `GeneratedConditionFilter` whose `bodyParams()` contains the expected `BodyParam.Eq` / `BodyParam.In`. This is the assertion shape `FilmImplicitInput`'s `@table` analogue uses at the `COLUMN_FIELD_WITH_CONDITION` family; the symmetry is asserted directly.

Add one new `ClassificationCase` exercising the bare plain-input-resolved-no-condition case: `input PlainFilter { filmId: Int! @field(name: "film_id") }` used as `films(filter: PlainFilter): [Film]`. Assert `PlainInputArg.fields()` contains exactly one `InputField.ColumnField` (no `condition`); the projected `GeneratedConditionFilter.bodyParams()` contains exactly one `BodyParam.Eq` on `film.film_id`. **This is the test that's missing entirely today.** Combined with Step 1(a)'s type-level structural pin, the symmetry is enforced two ways — once by the type signature (`walkInputFieldConditions` cannot be called with `null`) and once by a positive case asserting the runtime shape.

### Step 4: Documentation-vs-implementation cleanup

Four drift sites land in the same change:

- **`InputFieldResolver.java:30-41`** — the class-level javadoc paragraph rationalising the silent skip (`"Silently skips fields that fail column resolution; their conditions cannot be built, and the caller's accumulating errors list will surface the structural problem via the outer-argument classification path. … No sealed result wrapper: the projection is total …"`) is replaced by a description of the new sealed `Resolution` shape and the contract that every `Unresolved` becomes a `Rejected.rejection`. Leaving the rationalising prose intact would re-license the violation for the next contributor.
- **`FieldBuilder.java:1345-1346`** — the `"Plain input types are silently skipped unless paired with @condition; see the out-of-scope note in docs/argument-resolution.md."` comment goes. The file name is wrong (`.adoc`, not `.md`), the section it cites doesn't exist (`docs/argument-resolution.adoc:429-437` lists Mutations and `NodeIdField` with `@condition`, nothing about plain-input implicit predicates), and the behaviour it documents is the bug.
- **`docs/argument-resolution.adoc:400-412`** — the "Plain-input classification failure: per-field skip, not whole-arg failure" rationale paragraph is retired entirely (Path B from the Spec-stage design fork: any `Unresolved` on a plain input is a build error; cross-table input reuse where columns don't match everywhere is bad API design rather than a feature to preserve). The author-facing migration story moves to the truth table at `:262-275` (which Step 1 already aligns with).
- **`docs/argument-resolution.adoc:262-275`** — the truth table's prose is augmented with one sentence: "Enforced by the symmetric-implicit-predicate-emission pipeline test added in R205 acceptance test #1." No `<<>>` xref convention — the prose anchor is the convention used by the principles doc and `typed-rejection.adoc`. Introducing a project-wide doc-to-test xref convention is R207's job, not R205's.

### Step 5: Convert the `languagesByPlainInput` fixture

`schema.graphqls:85` (the `languagesByPlainInput(filter: PlainFilmIdInput): [Language!]!` query) and `GraphQLQueryTest.java:1907-1917` (`inputFieldCondition_plainInput_languageTable_fieldNotResolved_noFilterApplied_returnsAllLanguages`) encode the silent-drop as expected behaviour by name and by assertion. Both are deleted outright. Under Step 2's path-B rejection, the only repurpose options would have been (a) ship a build-rejection fixture out of `PlainFilmIdInput`-on-Language, or (b) make `filmId` resolve against Language (impossible — no `film_id` column). Repurposing as a build-rejection fixture is structurally redundant with acceptance tests 4 and 5 below; cleaner to delete the fixture entirely and let the new tests carry the rejection coverage. The existing `filmsByPlainInput` Film-side test continues to pin the `@condition` projection path.

## Out of scope

- A project-wide audit of "where else does the design doc say X and the code do Y." Filed as R207 (`design-doc-implementation-conformance-audit`); the five-layer survival pattern named in § "How this gap survived" is plausibly not unique to this surface, but a comprehensive sweep is a substantial separate item.
- `@table` input fields whose column resolves and carry no `@condition`: that path already works (`FilmImplicitInput` family at `schema.graphqls:155-171`); the symmetry restoration in Step 1 brings plain inputs up to that path, not the other way around.
- Argument-level `@condition` on the outer filter argument: routes through `pia.argCondition()` at `FieldBuilder.java:1347` and is unchanged.
- `directives.graphqls` updates: the retired-directive pattern leaves locations declared so the parser does not fail with "unknown directive location"; nothing changes there.
- **`@service` query fields with plain-input args.** `@service` fields emit no jOOQ predicates (the developer's method owns the result composition), so even if their plain-input args classify identically under Steps 1-3, no `GeneratedConditionFilter` is projected and an existing `@service` schema doesn't gain new filtering after Step 1. Step 2's rejection arm DOES apply to `@service` field args that reach `InputFieldResolver.resolve` — an `Unresolved` field on a plain input arg to a `@service` field will fail the build. The fix for an affected `@service` schema is the same as for non-`@service`: `@field(name:)` to make the column resolve, or remove the unused field.
- **Nested plain-input inside a `@table` input.** `classifyInputFieldInternal:1624-1626` gates nesting on the nested type not carrying `@table`, so plain inputs nest into `@table` inputs but `@table` inputs do not nest into plain inputs. The symmetry-restoration in Step 1 inherits this gate; the `lookupBoundNames` contract has no cross-boundary propagation problem to solve today. If that gate relaxes in a future item, Step 3's positive coverage case for plain-input projection rebreaks and the failure surfaces as a meta-test failure rather than a silent regression.
- A separate "wire it up via the @condition method even when no column resolves" variant: out of scope; R205 closes the silent-drop wound by escalating to a build error, not by inventing runtime semantics for column-less @condition.
- The roadmap-tool YAML footgun where `status` subcommand drops quotes around a `@`-prefixed title and leaves the file un-parseable: separate maintenance fix; the workaround is single-quotes around the title.

## Acceptance tests

Pipeline-tier, model-shape assertions throughout; no generated-source-string assertions (per `docs/rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural tier": "Code-string assertions on generated method bodies are banned at every tier").

1. **Symmetric implicit-predicate emission (the test that's missing entirely today).** Schema: `input PlainFilter { filmId: Int! @field(name: "film_id") }` against `films(filter: PlainFilter): [Film]`. Assert: `PlainInputArg.fields()` contains exactly one `InputField.ColumnField` with `condition() == Optional.empty()`; the projected `GeneratedConditionFilter.bodyParams()` contains exactly one `BodyParam.Eq` on `film.film_id`.
2. **Symmetric explicit-method-plus-implicit composition.** Schema: `input PlainFilter { filmId: Int! @field(name: "film_id") @condition(condition: {className: "...", method: "filmIdCondition"}) }`. Assert: the projected `WhereFilter` list contains both a `GeneratedConditionFilter` carrying `BodyParam.Eq` on `film.film_id` AND a `ConditionFilter` pointing at `filmIdCondition`. The pre-existing `filmsByPlainInput` execution-tier test stays as the runtime pin.
3. **Override propagation parity.** Schema: `input PlainFilter { filmId: Int! @field(name: "film_id") @condition(..., override: true) }`. Assert: no implicit `BodyParam.Eq` (override suppresses it); `ConditionFilter` for `filmIdCondition` present. Mirrors the `@table`-input override behaviour pinned by `filmsWithInputFieldOverride`.
4. **Rejection on Unresolved with `@condition`.** Schema: `input PlainFilter { name: String @condition(...) }` against a query whose target table has no column `name`. Assert: the surrounding `QueryField` classifies as `GraphitronField.UnclassifiedField`; its `Rejection` is an `AuthorError.UnknownName` with `attempt == "name"` and `candidates == columnJavaNamesOf(targetTable)`; the `message()` includes the input type name, the field name, and a `; did you mean: …` suffix when candidates exist.
5. **Rejection on bare Unresolved (no `@condition`).** Schema: `input PlainFilter { name: String }` against a query whose target table has no column `name`. Assert: identical to acceptance test 4 — the bare-unresolved-no-condition case rejects loudly with the same `AuthorError.UnknownName` shape. **This is the test that pins Path B**; future contributors cannot quietly reintroduce per-field skip without breaking this test by name.
6. **Rejection on `@condition` reflection failure.** Schema: `input PlainFilter { name: String @condition(condition: {className: "NonExistent", method: "nope"}) }` against a query whose target table has column `name`. Assert: `UnclassifiedField` whose `Rejection.message()` contains the input type name, the field name, and the underlying reflection-failure prose. This is the test that pins finding #2's "condErrors fold into the same sealed channel."
7. **Meta-test: `ProjectionCoverageTest` no longer allowlists `PojoInputType`.** With the `NO_PROJECTION_REQUIRED` entry removed, the test must pass without it. Tests 1-3 above provide the projection cases that satisfy the meta-test for `PojoInputType`.
8. **Type-level structural pin (compile-time, not a runtime test).** `walkInputFieldConditions`' signature is `List<BodyParam> implicitBodyParams` (not `@Nullable`, no `null` default); javac forbids passing `null`. No separate JUnit case asserts this; the type signature is the assertion. Mentioned here so reviewers can verify Step 1(a) landed.

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolver.java:30-41, :57-71` — sealed `Resolution`, drop the rationalising javadoc.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolution.java:15-25` — unchanged; sealed `Resolved | Unresolved` continues to carry `fieldName`, `lookupColumn`, `reason`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:985-1005` — `@notGenerated` precedent; `:1006-1008` — `classifyArgument` PlainInputArg construction (switch on `Resolution`); `:1337-1350` — `projectFilters` branches for both ArgumentRef variants; `:1351-1354` — `UnclassifiedArg` propagation (unchanged); `:1500-1568` — `walkInputFieldConditions` and `:1511, :1524, :1546` — the `if (implicitBodyParams != null && …)` guards that simplify after Step 1(a).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1489-1518` — `buildInputFieldCondition` (`condErrors`-appending path that Step 2 closes); `:1538-1656` — `classifyInputFieldInternal` Unresolved construction sites (`@reference` path miss, terminal column miss, NestingField failure aggregation).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java:1031-1066` — read-only reference for the candidate-hint shape; `:1053-1054` — the prose form Step 2 mirrors.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java:48-65, :418-420` — `AuthorError.UnknownName` record and `Rejection.unknownColumn(summary, attempt, candidates)` factory used by Step 2.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/OrderByResolver.java:78-80` — `Resolved.{Ok, Rejected}` two-arm sibling shape Step 2 matches.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:4072-4176` — plain-input classification cases to extend with projection-shape assertions; new bare-resolved-no-condition case.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/ProjectionCoverageTest.java:65-69` — `PojoInputType.class` allowlist entry to delete.
- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:79-85, :110, :1000-1006` — fixture changes for Step 5.
- `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java:1907-1917` — `languagesByPlainInput` execution-tier assertion that goes away.
- `docs/argument-resolution.adoc:44-46, :262-275, :400-412, :429-437` — design-doc anchors; Step 4 retires `:400-412` and adds the prose-anchor to `:262-275`.
- `docs/typed-rejection.adoc` § "Sealed `Resolved` across the resolver siblings" — principle anchor for the sealed-signature change.
- `roadmap/changelog.md:243` — Phase 4 spin-out language; documented here for the next contributor to read.
- R207 (`design-doc-implementation-conformance-audit.md`) — follow-up audit item for the project-wide equivalent of R205's drift detection.
