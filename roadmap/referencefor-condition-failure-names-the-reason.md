---
id: R1136
title: "A @referenceFor condition that cannot be resolved does not say why"
status: Spec
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A @referenceFor condition that cannot be resolved does not say why

## Goal

When a path-step `@condition` (the `condition:` element of a `@referenceFor` or `@reference` path, naming a Java method that joins or filters two tables) names a method graphitron cannot bind, the rejection says why, as the argument-level `@condition` already does. Today the path-step form says only that the method "could not be resolved", with the cause thrown away, and the message is malformed on top of that.

Before (kreg, a private helper `barnCondition(Field<Long>, Field<Long>)` beside nine public `barnCondition(VKoX parent, VKoY child)` overloads, on a field `KregFag.barn` whose multi-table interface return type has `@referenceFor(type: "KregFag", path: [{condition: {className: "...KompetanseoppnaelseConditions", method: "barnCondition"}}])`):

```
Field 'KregFag.barn': Field 'KregFag.barn': @referenceFor route for participant 'KregFag' could not
be resolved: condition method 'method 'barnCondition' in class
'no.sikt.nvb.graphql.conditions.KompetanseoppnaelseConditions'' could not be resolved.
```

After (the same typed overload explanation an argument `@condition` gets, one field prefix, no nested quotes):

```
Field 'KregFag.barn': @referenceFor route for participant 'KregFag' could not be resolved:
path-step @condition: method 'barnCondition' in class '...KompetanseoppnaelseConditions' has 10
declarations (...): parameter 0 is neither a jOOQ Table parameter in every declaration nor
identical in name and declared type across them. On a @condition the declarations of one name may
differ only in their jOOQ Table parameters, so make the rest of the signature identical across
them, or collapse the set to a single method taking org.jooq.Table<?>.
```

## Findings (verified against the code)

All three claims in the report hold, and the cause of the first is one level lower than the item implied.

* **Cause is lost in `BuildContext.resolveConditionRef`.** It calls `ServiceCatalog.reflectTableMethod`, which returns a `ServiceReflectionResult` whose `rejection()` already holds the typed `ReflectionError.AmbiguousMethod` (rendered by `ReflectionError.AmbiguousMethod#message`, the "has N declarations ... collapse the set" text). The method then maps `result.failed()` to the payload-free `ConditionResolution.Unresolved`, discarding it. The three `parsePathElement` arms (`key:`, `table:`, condition-only) each turn `Unresolved` into `"condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved"`. So the gap is not specific to `@referenceFor`: a `@reference` path with a `condition:` loses the reason identically, and every reflection failure is lost, not only overloads (class not on classpath, method not found, not static, return-type or parameter-names rejections).
* **Argument path reuses the rejection.** `ConditionResolver.resolveArg` and `resolveField` do `result.rejection().prefixedWith("argument '<name>' @condition: ")`. This is the behaviour to match.
* **Nested quoting** comes from `extractConditionQualifiedName`, which returns an already-quoted `method 'm' in class 'c'`, which the caller wraps in a second pair of quotes.
* **`@referenceFor` wrapper.** `FieldBuilder.classifyParticipantRoute` builds `fieldLabel + ": @referenceFor route for participant '" + type + "' could not be resolved: " + parsed.errorMessage() + "."`. `ParsedPath.errorMessage` is a plain `String` (`String.join("; ", errors)` in `BuildContext.parsePath` / `parseExplicitPath`), so the typed arm is not carried to this site; the message text is.
* **Doubled prefix** is not specific to this message. `FieldBuilder.resolveChildPolymorphicJoinPaths` and `classifyParticipantRoute` / `parentHoldsFkListReject` start every rejection with `fieldLabel` (`"Field '<Type>.<field>'"`), and the returned rejection becomes an `UnclassifiedField`, which `GraphitronSchemaValidator.validateUnclassifiedField` reports through `ValidationError.forField`, which prepends `"Field '<qname>': "` again. Every rejection built with `fieldLabel` in that path doubles (the `@reference`-not-supported, missing `type`, duplicate participant, non-table-bound participant, does-not-land, hop-0 filter, same-table, unsupported-shape and parent-holds-FK messages), so the fix removes the redundant label at the source rather than special-casing one message.
* **Existing tests** assert fragments only (`contains("not a table-bound participant")`, `contains("more than once")`, `contains("does not land on that participant's table")`, `contains("same table as the parent/hub ('film')")`, `contains("an explicit @reference is not supported on a multi-table interface/union child field")`) in `MultiTableChildReferenceForPipelineTest`, `MultiTableChildReferencePathRejectionPipelineTest` and `NodeIdParticipantRoutePipelineTest`. None pins the `Field '...': Field '...':` doubling or the "condition method '...' could not be resolved" wording, so removing the label and rewording break no existing assertion. No test pins the unresolved-condition wording at all (a gap this item closes).

## Implementation

1. `BuildContext.ConditionResolution`: give the `Unresolved` arm a payload, or replace it with `Failed` carrying the text. In `resolveConditionRef`, when `result.failed()`, return `Failed("path-step @condition: " + result.rejection().message())`, matching the `path-step @condition:` prefix the argMapping failures in the same method already use. Keep `Unresolved` only for the incomplete-reference case (`className`/`methodName` null or `svc == null`), and have it carry the unquoted description so the callers stop wrapping it.
2. `BuildContext.extractConditionQualifiedName` and its three call sites in `parsePathElement`: render `"condition " + qualifiedName + " could not be resolved"` once, from the `Unresolved` arm, so `method 'm' in class 'c'` is quoted exactly once. Collapse the three duplicated `Unresolved` arms to one helper.
3. `FieldBuilder.classifyParticipantRoute`, the `@referenceFor` `parsed.hasError()` arm: the appended `"."` after `parsed.errorMessage()` doubles up when the message already ends in a period (the overload guidance does). Drop the appended period, or append it only when absent.
4. `FieldBuilder.resolveChildPolymorphicJoinPaths` and `classifyParticipantRoute` / `parentHoldsFkListReject`: drop `fieldLabel` from every message and the parameter that threads it, since the validator adds the field prefix. Messages then begin `@referenceFor route for participant ...`, `an explicit @reference ...`, `participant '...' ...`.
5. Do not add a parallel explanation renderer: the overload text stays owned by `ReflectionError.AmbiguousMethod`.

## Tests

* Pipeline tier (`@PipelineTier`, SDL to classified model, no database), in `MultiTableChildReferenceForPipelineTest` (it already has `rejectionOf`): a multi-table interface child field whose `@referenceFor` route is `[{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "<overloaded>"}}]` against a new overload set in `TestConditionStub` that disagrees at parameter 0 (one `(Table<?>, Table<?>)` declaration, one `(Field<Long>, Field<Long>)` declaration, mirroring the kreg shape). Assert the `UnclassifiedField` rejection message contains `has 2 declarations`, both rendered signatures, and `collapse the set to a single method taking org.jooq.Table<?>`.
* Same test: assert the message contains exactly one `Field 'Parent.field':` and does not contain `''` or `'method '`, pinning the prefix and quoting fixes.
* A second case with a `@reference` path `condition:` naming the same overloaded method, asserting the same explanation, since the fix lives in the shared `parsePathElement` path.
* A third case with a method name that does not exist on the class, asserting the not-found reason now reaches the message (the non-overload causes were lost too).
* Update existing fragment assertions only if they happen to start from the old `Field '...': ` prefix (none found).

## Open question

`ParsedPath.errorMessage` is a string, so the typed `AmbiguousMethod` arm (and its `lspCode`) does not survive to the `@referenceFor` rejection, which stays `Structural`. This item restores the prose only. Carrying the typed rejection through `ParsedPath` would let the LSP show `graphitron.reflect.ambiguous-method` on path-step conditions, but widens the change to every `ParsedPath` consumer; filed as R1141.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
