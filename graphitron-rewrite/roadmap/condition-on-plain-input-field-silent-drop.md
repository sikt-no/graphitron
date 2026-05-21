---
id: R205
title: '@condition on a plain-input-type input field is silently dropped when no matching column exists'
status: Spec
bucket: bugs
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# @condition on a plain-input-type input field is silently dropped when no matching column exists

A `@condition` directive on an `INPUT_FIELD_DEFINITION` inside a plain (non-`@table`) filter input type is silently discarded at classification time when the input field's name does not resolve to a column on the surrounding query's table. The schema compiles, the generator emits `return DSL.noCondition();` for the query's `<query>Condition(...)` method, and at runtime the filter does nothing, even though `directives.graphqls` declares `@condition` valid on `INPUT_FIELD_DEFINITION` and the user supplied a complete condition method reference.

## Reproduction

```graphql
extend type Query {
  things(filter: ThingFilter): [Thing!]
}

input ThingFilter {
  name: String @condition(
    condition: {className: "com.example.ThingService", method: "byName"},
    override: true
  )
}
```

If table `thing` has no SQL column `name`, the generated `thingsCondition(...)` is `return DSL.noCondition();` and `ThingService.byName` is never referenced anywhere in generated code. A production caller hits this in `opptak-subgraph` with filter inputs like `SakFilterV2Input` where field names (`sakskode`, `statuskoder`, `levertTidspunkt`, …) do not match any column on the resolving `sak` table; every condition method the schema names is dropped at codegen time.

The behaviour is currently encoded as expected in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:79-85`:

> `# For Language, filmId does not exist → field skipped → no condition applied → all languages returned.`

so the silent drop has a deliberate fixture (`languagesByPlainInput`) but no user-facing surface (no log line, no validation error) and no migration path.

## Architectural violation, not a feature gap

The fix is to **reject loudly via the sealed contract**: extend the sealed `InputFieldResolution` so `Unresolved` carries the actionable-directive predicate, change `InputFieldResolver.resolve`'s signature so failures travel as a typed `Rejected` variant rather than mutations on an `errors: List<String>` out-param, and delete the javadoc paragraph that rationalises the current silent skip. The code-and-prose pair is the contract restoration; either alone re-licenses the violation.

The relevant audit: `InputFieldResolution` (graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolution.java) is a sealed result with `Resolved` and `Unresolved` arms; `Unresolved` carries `fieldName`, `lookupColumn`, and `reason` whose entire purpose is to surface the diagnostic and feed candidate-hint synthesis. The architectural invariant, documented at `FieldRegistry.java:103-110`, is that `Unresolved` "is a transient resolution outcome consumed by the caller." Two consumers exist:

- `TypeBuilder.buildTableInputType` (`TypeBuilder.java:1031-1066`) collects `Unresolved` results into a `failures` list, synthesises a candidate-hint via `BuildContext.candidateHint` against `Unresolved.lookupColumn`, and rejects the surrounding type with a focused diagnostic.
- `InputFieldResolver.resolve` (`InputFieldResolver.java:57-71`) iterates the same sealed type and silently filters the `Unresolved` arm out:
  ```java
  for (var f : iot.getFieldDefinitions()) {
      var res = ctx.classifyInputField(f, typeName, rt, new LinkedHashSet<>(), condErrors);
      if (res instanceof InputFieldResolution.Resolved r) {
          classified.add(r.field());
      }
      // Unresolved arm: dropped, reason() and lookupColumn() never read
  }
  ```

The class-level javadoc at `InputFieldResolver.java:30-41` rationalises this: *"Silently skips fields that fail column resolution; their conditions cannot be built, and the caller's accumulating errors list will surface the structural problem via the outer-argument classification path. … No sealed result wrapper: the projection is total, every input shape produces a list (possibly empty). The errors-list mutation pattern is preserved because …"* Both halves of that rationalisation are wrong in exactly the bug scenario: the outer-argument classifier *succeeds* (it produces a valid `PlainInputArg`) so the "outer path will surface it" promise never fires, and the errors-list mutation pattern is the principle violation (see `docs/typed-rejection.adoc` § "Sealed `Resolved` across the resolver siblings": "rejection is a typed sibling of success, not a return-string-or-null").

## Direction: extend the sealed contract, route the Rejected arm

### Step 1: Add the predicate to the sealed result

`InputFieldResolution.Unresolved` (currently `(fieldName, lookupColumn, reason)`) gains a fourth component `boolean hasActionableDirective`. The set of actionable directives at the input-field site is, today, exactly `@condition`; `@field` is a name-override and inert on its own; `@reference` is consumed before the `Unresolved` arm is reached; `@notGenerated` and `@lookupKey` are retired and rejected upstream by `FieldBuilder.classifyArgument` at `FieldBuilder.java:985-1005`, so they never reach this classifier. The predicate is populated by `BuildContext.classifyInputFieldInternal` (`BuildContext.java:1538-1656`) at every `Unresolved` construction site (terminal column-miss at the end of the method; reference-path miss in the `@reference` arm; nested-input failure aggregation in the `NestingField` arm; etc.) from `field.hasAppliedDirective(DIR_CONDITION)`. `TypeBuilder.buildTableInputType` keeps reading `lookupColumn` and `reason` and is otherwise unaffected.

If a future directive joins the actionable set, the change is one line at the construction sites in `BuildContext.classifyInputFieldInternal`. Naming the set in the javadoc on `Unresolved.hasActionableDirective` keeps that drift visible.

### Step 2: Change `InputFieldResolver.resolve` to a sealed-result signature

Introduce a sealed result, matching the resolver-sibling shape (`OrderByResolver.Resolved`, `ConditionResolver.ArgConditionResult`, etc.):

```java
sealed interface Resolution {
    record Ok(List<InputField> fields) implements Resolution {}
    record Rejected(String message) implements Resolution {}
}
```

`Resolution.Rejected` is produced when the input contains at least one `Unresolved` field with `hasActionableDirective == true`. Its `message` is synthesised at construction time by `InputFieldResolver`:

- enumerate the actionable-directive-bearing `Unresolved` results,
- format each as `"input field '<fieldName>': <reason>"`,
- append the first non-null `lookupColumn` candidate-hint via `BuildContext.candidateHint(column, ctx.catalog.columnJavaNamesOf(rt.tableName()))`, matching `TypeBuilder.buildTableInputType:1047-1052` exactly,
- prefix the result with `"plain input type '<typeName>': "`.

`Unresolved` results with `hasActionableDirective == false` are still silently skipped at this step; that policy (`FieldBuilder.java:1345-1346`: "Plain input types are silently skipped unless paired with @condition") is unchanged, see § "Out of scope". When every `Unresolved` is non-actionable, the result is `Resolution.Ok(classified)`; when at least one is actionable, the result is `Resolution.Rejected(message)`. The per-field `@condition` reflection errors (`condErrors`) already collected via the existing buffer fold into the rejection message when present.

The signature change touches exactly one other caller: `FieldBuilder.classifyArgument` (`FieldBuilder.java:1006-1008`). Today it reads:
```java
List<InputField> plainFields = inputFieldResolver.resolve(typeName, rt, errors);
return new ArgumentRef.InputTypeArg.PlainInputArg(name, typeName, nonNull, list, argCondition, plainFields);
```

The new shape switches on `Resolution`:
- `Ok(fields)` constructs `PlainInputArg` as today;
- `Rejected(message)` returns `ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list, message)`, mirroring the `@notGenerated` precedent at `FieldBuilder.java:985-1005` exactly.

The `errors` parameter on `InputFieldResolver.resolve` goes away; `errors` accumulation at the call site happens through the existing `UnclassifiedArg` path in `projectFilters` at `FieldBuilder.java:1351-1354` (`hadError = true` plus a prefixed message append).

### Step 3: Delete the rationalising javadoc

The class-level javadoc bullet at `InputFieldResolver.java:30-34` ("Silently skips fields that fail column resolution; their conditions cannot be built…") and the "No sealed result wrapper" rationale at `InputFieldResolver.java:37-41` both go in the same change. The bullet at `InputFieldResolver.java:27-29` describing the `condErrors` forwarding (`InputFieldResolver.java:61, 69`) is replaced by a description of the new sealed shape. Leaving the rationalising prose intact would re-license the violation for the next contributor; the code change and the prose change are co-equal pieces of the contract restoration.

The retired-directive note at `InputFieldResolver.java:32-34` (`@notGenerated rejected upstream …`) stays — it's accurate and points at a real precedent the implementer should mirror.

### Rationale

- The fix lives on the type system, not on a `List<String>` mutation. The `Unresolved.reason()` and `Unresolved.lookupColumn()` ride from the classifier that knows them down to the `UnclassifiedArg.reason()` that surfaces them, in a sealed chain.
- The predicate "did this field carry `@condition`?" lives on the `Unresolved` record where the classifier already has the GraphQL AST in scope; the consumer reads it. Generation-thinking: model carries the pre-resolved decision.
- The candidate-hint contract is preserved: a typo-style `sakskode` → `saksKode` mistake produces `"no column 'saksKode' found in table 'sak'. Did you mean 'sakskode'?"` at the build surface, identical in shape to the `@table` input case at `TypeBuilder.java:1053-1054`.
- The change matches every adjacent surface: retired directives (`@notGenerated`, `@lookupKey` on input fields) reject loudly via `UnclassifiedArg`; classifier column-misses on `@table` inputs reject loudly via `UnclassifiedType`; the orphan was the plain-input arm.

### Migration

Schemas relying on the silent-no-op (`opptak-subgraph`'s `SakFilterV2Input`, `OpptakFilterInput`, and adjacent) will fail the build with a message naming the input type, the field, and the underlying column-miss reason (plus a candidate-hint when applicable). Fix options for an author hit by this:

- Add `@field(name: "<actual_column>")` to the input field so the column resolves, then the existing `@condition` wires correctly.
- Lift `@condition` to the outer `filter:` argument and reshape the method to take the whole filter input as one parameter (the working `opptakstyper` shape).
- Remove the input field if the condition method was vestigial.

None of these are free, but the build error tells the author exactly where to look, which is strictly better than discovering at runtime that the filter returns "successful" responses that ignore the filter argument.

## Out of scope

- `@table` input fields whose column does resolve: that path already works (see `filmsWithInputFieldCondition` fixture at schema.graphqls:78).
- Argument-level `@condition` on the outer filter argument: that path already works (see `opptakstyper` working comparison; the routing lives at `FieldBuilder.java:1347` via `pia.argCondition()`).
- Updating `directives.graphqls` to drop `INPUT_FIELD_DEFINITION` from `@condition`'s `on` clause: the retired-directive pattern leaves the location declared so the parser does not fail with "unknown directive location"; we keep the location declared and reject use at the classifier.
- **Unresolved plain-input fields with no actionable directive remain silently skipped.** This pre-existing policy (`FieldBuilder.java:1345-1346`, "Plain input types are silently skipped unless paired with @condition") is preserved, scoped by the `hasActionableDirective` predicate added in Step 1. Widening the rejection to "every `Unresolved` on a plain input is a build error" would break unrelated user-facing schemas and is a separate policy item; the scope-cut is pinned by an explicit test (see § Acceptance tests, test 3) so a future contributor cannot widen it accidentally without first revising that test.
- A separate "wire it up" variant that gives `@condition` on column-less plain-input fields runtime semantics: if a real schema needs that surface it should be filed as its own item with explicit emitter and runtime requirements; R205 closes the silent-drop wound, it does not open a new feature.
- The YAML-front-matter footgun the roadmap-tool's `status` subcommand hits when the title starts with `@` (drops surrounding double-quotes, re-emits as an unquoted scalar, YAML scanner then refuses the file): out of scope here. The workaround is single-quotes around the title.

## Acceptance tests

Pipeline-tier, model-shape assertions throughout; no generated-source-string assertions (per `docs/rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural tier": "Code-string assertions on generated method bodies are banned at every tier").

1. **Rejection on actionable-directive-bearing unresolved.** Schema: plain input `ThingFilter { name: String @condition(...) }` against a query `things(filter: ThingFilter): [Thing!]` where `thing` has no column `name`. Assert: the surrounding `QueryField` classifies as `GraphitronField.UnclassifiedField`, its `Rejection` message contains `"ThingFilter"`, `"name"`, and the underlying column-miss reason.
2. **Candidate hint surfaces.** Same shape but the schema spells the input field `saksKode` against a table with column `sakskode`. Assert: the `UnclassifiedField.Rejection` message contains `"Did you mean 'sakskode'?"` (or whatever the existing `candidateHint` formats to, copied from `TypeBuilder.buildTableInputType`).
3. **Scope pin: unresolved without actionable directive still silently skipped.** Schema: plain input `ThingFilter { name: String }` (no `@condition`) against `things(filter: ThingFilter): [Thing!]` where `thing` has no column `name`. Assert: the surrounding `QueryField` classifies as `QueryField.QueryTableField` (not `UnclassifiedField`); the contained `PlainInputArg.fields()` is empty.
4. **Positive case: column does resolve, condition wires.** Plain input `ThingFilter { name: String @field(name: "thing_name") @condition(...) }` against a table with column `thing_name`. Assert: `PlainInputArg.fields()` contains exactly one `InputField.ColumnField` whose `condition()` is `Optional.present` and points at the user's `className` + `methodName`.
5. **Existing fixture conversion.** The `languagesByPlainInput` fixture at `schema.graphqls:85` and its execution-tier assertion at `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java:1907-1917` (the `inputFieldCondition_plainInput_languageTable_fieldNotResolved_noFilterApplied_returnsAllLanguages` test asserting "all languages returned") currently encodes the silent-drop as expected. Under this direction the fixture must change to one of: (a) deleted outright (the `filmsByPlainInput` Film-side test still pins the "column resolves" path); (b) repurposed as the rejection-test fixture by leaving `filmId` unresolved against `Language` and asserting the resulting pipeline-test rejection; (c) reshaped so `filmId` resolves against `Language` via a synthesised column. The implementer picks (a) or (b); (c) is rejected because Language has no `film_id` column to bind against. The execution-tier assertion that depends on the silent-drop ("expect all languages") goes away in the same commit.

## References

Files the implementer will touch or read closely:

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolution.java` — add `hasActionableDirective` to `Unresolved`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolver.java` — sealed `Resolution`, no `errors` out-param, candidate-hint synthesis, javadoc rewrite.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1538-1656` — populate `hasActionableDirective` at every `Unresolved` construction site (`classifyInputFieldInternal`).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:985-1009` (`@notGenerated` precedent) and `:1006-1008` (call site updated to switch on `Resolution`) and `:1351-1354` (`UnclassifiedArg` propagation, unchanged but the new code path lands here).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java:1031-1066` — read-only reference for the candidate-hint shape; unaffected by this change.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldRegistry.java:93-111` — read-only reference for the architectural invariant documentation; unaffected.
- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:79-85, :110, :1000-1006` — fixture changes per acceptance test 5.
- `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java:1907-1917` — execution-tier assertion that goes away in the same commit.
- `docs/rewrite-design-principles.adoc` § "Builder-step results are sealed…" and `docs/typed-rejection.adoc` § "sealed Resolved across the resolver siblings" — principle anchors; not modified.
