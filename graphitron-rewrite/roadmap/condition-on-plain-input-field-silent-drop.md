---
id: R205
title: "@condition on a plain-input-type input field is silently dropped when no matching column exists"
status: Backlog
bucket: bugs
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# @condition on a plain-input-type input field is silently dropped when no matching column exists

A `@condition` directive on an `INPUT_FIELD_DEFINITION` inside a plain (non-`@table`) filter input type is silently discarded at classification time when the input field's name does not resolve to a column on the surrounding query's table. The schema compiles, the generator emits `return DSL.noCondition();` for the query's `<query>Condition(...)` method, and at runtime the filter does nothing — even though `directives.graphqls` declares `@condition` valid on `INPUT_FIELD_DEFINITION` and the user supplied a complete condition method reference.

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

If table `thing` has no SQL column `name`, the generated `thingsCondition(...)` is `return DSL.noCondition();` and `ThingService.byName` is never referenced anywhere in generated code. A production caller hits this in `opptak-subgraph` with filter inputs like `SakFilterV2Input` where field names (`sakskode`, `statuskoder`, `levertTidspunkt`, …) do not match any column on the resolving `sak` table — every condition method the schema names is dropped at codegen time.

The behavior is currently encoded as expected in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`:

> `# For Language, filmId does not exist → field skipped → no condition applied → all languages returned.`

— so the silent drop has a deliberate fixture (`languagesByPlainInput`) but no user-facing surface (no log line, no validation error) and no migration path.

## Where the drop happens

The classify path lives in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java#classifyInputFieldInternal`. The terminal arm is column lookup against the resolving table:

```java
var colEntry = catalog.findColumn(tableName, columnName);
if (colEntry.isPresent()) {
    // … build ColumnField (with @condition attached via buildInputFieldCondition)
}
// otherwise → InputFieldResolution.Unresolved("no column 'name' found in table '…'")
```

`InputFieldResolver.resolve` then iterates the resolutions and silently drops every `Unresolved` arm — `Unresolved` returns flow nowhere and produce no diagnostic. The `@condition` directive's parsed reference is only built (`buildInputFieldCondition`) on the `Resolved` arms above, so any `@condition` on a column-less plain-input field is collateral damage.

`argument-level @condition` on the outer filter argument (`filter: T @condition(...)`) routes through `pia.argCondition()` in `FieldBuilder#projectFilters` and gets wired correctly regardless of whether the inner input fields resolve. Only the inner-input-field site exhibits the silent drop.

## Design fork (Spec to decide)

Two plausible directions, each with cost/blast-radius trade-offs:

**1. Reject loudly.** Treat `@condition` on a plain-input-field that does not resolve to a column as a hard classifier error. The schema compiles today only because the silent drop pretends the directive isn't there; turning the no-op into an error matches the rewrite's "fail loudly over silent successes" stance. Cheapest fix; aligned with how the rewrite handles retired directives (`@notGenerated`, `@lookupKey` on input fields) — the directive declaration stays, the classifier rejects use. Costs an authored migration for production schemas relying on the directive being functional here (`opptak-subgraph`'s `SakFilterV2Input`, `OpptakFilterInput`); those callers would have to lift `@condition` to the outer `filter:` argument and reshape the method signatures, which is not always semantically equivalent.

**2. Wire it up.** Introduce a `ConditionOnlyField` variant (or extend an existing one with a no-column carrier) and project it through `walkInputFieldConditions` → `argConditions`. The condition method receives the field's extracted value via `CallSiteExtraction.NestedInputField`, mirroring the working `@table`-input case that today only fires because the column resolves. Touches the `InputField` sealed permits set, the classifier, the walker, and every emitter that switches on `InputField`. The user-facing semantics match the directive's plain reading: "this input field is filtered by my method, no column inference needed."

The bug report leans toward direction (2) — the user has live schemas relying on the directive site being functional and reshaping them all is not free. Direction (1) is the minimum-cost stop-the-bleeding fix.

## Out of scope

- `@table` input fields whose column does resolve: that path already works (see `filmsWithInputFieldCondition` fixture).
- Argument-level `@condition` on the outer filter argument: that path already works (see `opptakstyper` working comparison).
- Updating `directives.graphqls` to drop `INPUT_FIELD_DEFINITION` from `@condition`'s `on` clause — only relevant under direction (1), and even there the retired-directive pattern in the rewrite leaves the location declared so the parser doesn't fail with "unknown directive location."

## Acceptance test sketch

A pipeline test that asserts the generated `QueryConditions.<field>Condition(...)` references the user-supplied condition method when (a) the input is plain and (b) the input field name does not match any column on the resolving table. Under direction (1) the equivalent is a classifier-rejection test asserting the build fails with a focused message naming the input type, the field, and the directive site. Direction (2) additionally exercises runtime behavior via the sakila example (replace the existing `languagesByPlainInput` "all languages" assertion with the wired-up filter).
