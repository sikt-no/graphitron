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

## Architectural violation, not a feature gap

The fix is to **reject loudly**. The decision lands here rather than on a "wire it up" detour because this is the rewrite's sealed-hierarchy promise being broken in plain sight, and that's the bigger fish than the directive surface.

The relevant audit: `InputFieldResolution` is a sealed result type with `Resolved` and `Unresolved` arms; `Unresolved` carries a named `reason()` field whose entire purpose is to carry the diagnostic up to the caller. The architectural invariant — documented at `FieldRegistry.java:103-110` — is that `Unresolved` "is a transient resolution outcome consumed by the caller." Two consumers exist; one honors the contract, one violates it:

- `TypeBuilder.buildInputType` (`TypeBuilder.java:1035-1043`) collects `Unresolved` results into a `failures` list and rejects the containing type with a focused diagnostic. ✅
- `InputFieldResolver.resolve` (`InputFieldResolver.java:57-71`) silently filters them out:
  ```java
  for (var f : iot.getFieldDefinitions()) {
      var res = ctx.classifyInputField(f, typeName, rt, new LinkedHashSet<>(), condErrors);
      if (res instanceof InputFieldResolution.Resolved r) {
          classified.add(r.field());
      }
      // Unresolved arm: dropped, reason() never read
  }
  ```
  The method's javadoc rationalises this: *"Silently skips fields that fail column resolution — their conditions cannot be built, and the caller's accumulating errors list will surface the structural problem via the outer-argument classification path."* The premise is false in exactly the bug scenario: the outer-argument classification *succeeds* (it produces a valid `PlainInputArg`), so the "outer path will surface it" promise never fires. The result is a sealed hierarchy with a named-rejection arm whose diagnostic evaporates at one consumer, no caller-side rescue, no warn, no error.

The whole point of the sealed switch architecture is to detect this kind of thing early and fail rather than pretend things are fine. Restoring that contract IS the fix.

## Where the drop happens

The classify path lives in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java#classifyInputFieldInternal`. The terminal arm is column lookup against the resolving table:

```java
var colEntry = catalog.findColumn(tableName, columnName);
if (colEntry.isPresent()) {
    // … build ColumnField (with @condition attached via buildInputFieldCondition)
}
// otherwise → InputFieldResolution.Unresolved("no column 'name' found in table '…'")
```

`InputFieldResolver.resolve` then iterates the resolutions and silently drops every `Unresolved` arm. The `@condition` directive's parsed reference is only built (`buildInputFieldCondition`) on the `Resolved` arms above, so any `@condition` on a column-less plain-input field is collateral damage. The `Unresolved.reason()` payload — accurate, focused, ready to surface — is read by nothing.

`argument-level @condition` on the outer filter argument (`filter: T @condition(...)`) routes through `pia.argCondition()` in `FieldBuilder#projectFilters` and gets wired correctly regardless of whether the inner input fields resolve. Only the inner-input-field site exhibits the silent drop.

## Direction: reject loudly

Append every `Unresolved.reason()` from `InputFieldResolver.resolve` to the caller's `errors` list (prefixed `"plain input type '<typeName>': "` to match the existing `condErrors` forwarding). `FieldBuilder#projectFilters` already feeds those errors into the surrounding `QueryField`'s classification, so the failure becomes an `UnclassifiedField` rejection that shows up at the schema validator surface alongside every other classifier-detected schema mistake.

Rationale:

- It restores the sealed-hierarchy contract directly at the offending consumer; no new sealed variant, no emitter ripple. Minimum code change for maximum architectural alignment.
- It matches the rewrite's existing posture on every adjacent surface: retired directives (`@notGenerated`, `@lookupKey` on input fields) reject loudly with focused messages; FK-target translation failures reject loudly with a deferred-marker reason; classifier column-misses on `@table` inputs already surface as `UnclassifiedType`. A `@condition` on a column-less plain-input field landing as a no-op was the architectural outlier.
- Schema authors who today *think* their `@condition` is wired up get a clear build error pointing at the exact directive site, instead of discovering at runtime that their filter returns "successful" responses that ignore the filter argument. That's strictly better than the current behaviour where the failure surfaces only when a human notices result-set drift.

Migration path for affected schemas (`opptak-subgraph`'s `SakFilterV2Input`, `OpptakFilterInput`, anyone else relying on the directive-site silent-no-op): the build break message names the input type and the field, and the fix options are either (a) add `@field(name:)` so the column does resolve, or (b) lift `@condition` to the outer `filter:` argument and reshape the method to take the whole filter input as one parameter. Neither is free, but both are well-trodden and the build error tells the author exactly where to look.

## Out of scope

- `@table` input fields whose column does resolve: that path already works (see `filmsWithInputFieldCondition` fixture).
- Argument-level `@condition` on the outer filter argument: that path already works (see `opptakstyper` working comparison).
- Updating `directives.graphqls` to drop `INPUT_FIELD_DEFINITION` from `@condition`'s `on` clause: the retired-directive pattern in the rewrite leaves locations declared so the parser doesn't fail with "unknown directive location"; we keep the location declared and reject use at the classifier.
- A separate "wire it up" variant that gives `@condition` on column-less plain-input fields runtime semantics: if a real schema needs that surface it should be filed as its own item with explicit emitter and runtime requirements; this item closes the silent-drop wound, it doesn't open a new feature.

## Acceptance test sketch

A pipeline test that asserts the build fails (the query field classifies as `UnclassifiedField`) when a plain input filter has an `@condition`-annotated field whose name doesn't resolve to a column, with the error message naming the input type, the field, and the underlying column-miss reason. A second test pins that the equivalent shape *with* a resolving column (e.g. via `@field(name:)`) still classifies and wires the condition method, so the loud rejection doesn't over-reach. The existing `languagesByPlainInput` fixture must change: it currently encodes the silent-drop behaviour as expected, so under this direction it needs to either resolve `filmId` against the Language table (won't, it doesn't have one) or be removed in favour of a focused rejection-test fixture.

## Tangential cleanup worth picking up here

The `InputFieldResolver.resolve` javadoc paragraph rationalising the silent skip ("Silently skips fields that fail column resolution — their conditions cannot be built, and the caller's accumulating errors list will surface the structural problem via the outer-argument classification path") needs to go in the same change — leaving it would re-license the violation for the next contributor.
