---
id: R230
title: Downgrade BodyParam.nonNull for nested input fields under a nullable enclosing arg
status: Spec
bucket: bug
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Downgrade BodyParam.nonNull for nested input fields under a nullable enclosing arg

`InputField`'s `nonNull` slot is populated from `type instanceof GraphQLNonNull` on the input field's own GraphQL type (`BuildContext.java:1603`). It is propagated into `BodyParam.{Eq,In,RowEq,RowIn}.nonNull` by `FieldBuilder.walkInputFieldConditions` and consumed by `TypeConditionsGenerator.buildConditionMethod` (`TypeConditionsGenerator.java:107-154`), which picks between an unguarded `condition = condition.and(...)` (when `nonNull == true`) and a guarded `if (arg != null) condition = condition.and(...)` (when `nonNull == false`). The flag's documented semantic on `BodyParam` (`BodyParam.java:18-19`) is the runtime guarantee at the call site:

> When `true`, the null guard is omitted and the condition is always applied; when `false`, the condition is wrapped in a null check.

The bug: when the enclosing argument — or any intermediate `InputField.NestingField` — is nullable, the inner field's own `nonNull` declaration does not translate to a runtime non-null guarantee. graphql-java does not enforce nested non-null when an optional ancestor is absent. The call-site extraction emitted for `NestedInputField` parameters cascades through `instanceof` checks and returns `null` whenever any level is missing:

```java
(env.getArgument("filter") instanceof Map<?, ?> _m1 ? _m1.get("soknadId") : null) instanceof List<?> _nl ? ... : null
```

The generated condition method then calls e.g. `DSL.row(...).in(null)`, which jOOQ renders as the literal `false`. The user-visible failure observed in the wild: a field with `soknader(filter: HentSoknadInput): [Soknad!]` and `HentSoknadInput.soknadId: [ID!]!` returns an empty list when the consumer omits `filter`, because the generated WHERE accumulates `and false` once per non-null-declared composite-PK input field. Silent wrong-answer; no exception, no compile error, no validation message.

## Proposed shape

Compute the BodyParam's `nonNull` from the conjunction of (a) the input field's own declared nullability and (b) the enclosing chain's runtime nullability, in the consumer (`walkInputFieldConditions`). The `InputField`'s carried `nonNull` keeps its existing meaning ("this field's own SDL declared nullability"); the `BodyParam`'s flag's meaning gets a one-line tightening to make the contract explicit (see "Witness on `BodyParam.nonNull`" below); the lossy step that conflates them moves from constructor to consumer, where the ancestor chain is in scope.

Threading shape (parallel to the existing `enclosingOverride` pattern in the same method):

1. `projectFilters` passes the enclosing arg's effective nullability into `walkInputFieldConditions` for both `TableInputArg` and `PlainInputArg`. The arg's own `nonNull` is already on `ArgumentRef.InputTypeArg.{TableInputArg,PlainInputArg}` — read it there.
2. `walkInputFieldConditions` carries an `effectiveNonNull` boolean (`true` only when every ancestor link, top-level arg and every `NestingField` on the path, is non-null). When recursing into `NestingField` (`InputField.java:197-206`; `nonNull` slot confirmed present), AND the field's own `nonNull` into the inherited flag.
3. At `implicitBodyParam` / `compositeImplicitBodyParam` callsites, pass `effectiveNonNull && field.nonNull()` as the BodyParam's `nonNull` argument instead of `field.nonNull()` directly.

The model record (`BodyParam`) is structurally unchanged; only the value flowing into it changes, and the javadoc on `nonNull` tightens. `TypeConditionsGenerator` is unchanged.

The change does not affect top-level scalar args (`ColumnArg`, `CompositeColumnArg`, `ColumnReferenceArg`, `CompositeColumnReferenceArg`) — those flow through their own arms in `projectFilters` and read `ca.nonNull()` / `cca.nonNull()` etc. directly, where the flag already reflects the arg-level type. Today's code is correct for those.

### Witness on `BodyParam.nonNull`

Tighten the javadoc on `BodyParam.nonNull` (the interface-level list-item at `BodyParam.java:18-19`, plus the accessor's one-line summary at `BodyParam.java:43`) from

> When `true`, the null guard is omitted and the condition is always applied; when `false`, the condition is wrapped in a null check.

(at lines 18-19) and

> Whether a runtime null guard is needed.

(at line 43) to make the producer's obligation explicit. Put the full text below on the interface-level list-item (the source of truth); reduce the accessor's one-liner to a forward-pointer (`See {@link BodyParam} for the producer / emitter contract.`) so the prose lives in one place:

> Effective runtime nullability at the call site — the AND of the binding source's own declared nullability and every enclosing link's nullability (top-level argument plus each intermediate `InputField.NestingField`). The producer (`FieldBuilder.projectFilters` for top-level scalar args; `FieldBuilder.walkInputFieldConditions` for nested input fields) is responsible for computing the conjunction; `TypeConditionsGenerator` is then allowed to assume non-null when the flag is true. The flag is NOT the binding's own SDL-declared nullability — for that, read `InputField.nonNull()` directly.

This is the load-bearing witness: it pins what the producer must compute and what the emitter may assume, so the next reader does not reach for the wrong source.

## Scope notes for Spec

- **Where the AND happens.** Pushing it into `walkInputFieldConditions` keeps the conjunction local to the consumer and avoids touching the InputField records. The alternative (carry `effectiveNonNull` on each InputField at construction time in BuildContext) would require threading parent nullability through `inputFieldFromNodeIdResolved` and the `@reference` arm, both of which currently see only the field's own type. Consumer-side is simpler and matches the existing `enclosingOverride` precedent.

- **NestingField wrapper nullability.** `InputField.NestingField` carries its own `nonNull` slot (`InputField.java:202`). When walking into a `NestingField`, the recursion's effective nullability is `parentEffectiveNonNull && nestingField.nonNull()`. No prerequisite micro-edit needed.

- **Empty-list semantics for composite-PK row-IN are out of scope.** When `filter` is present and `soknadId: []` is sent, the post-decode list is empty, the null-guard short-circuit does not fire, and `DSL.row(...).in(emptyList())` still renders as `false`. That matches the documented "present-but-empty = falseCondition()" semantics in `CallSiteExtraction.java:138-143` for column-equality, and is the deliberate counterpart to "absent = unconstrained": two distinct user intents deserve two distinct surface behaviours. Authors who want "absent-or-empty = unconstrained" wrap their own `@condition` method (see `matchSoknadskoder` in user-side code). Re-litigating the empty-list semantic is a separate Backlog item with its own classification (probably a new `InputField` sub-variant or a `@condition` flag), not a rider on this null-guard fix.

- **Top-level non-null input arg + non-null inner field stays unguarded.** When `filter: HentSoknadInput!` (non-null arg) AND the inner field is non-null, the effective flag is `true` and the unguarded emission stays. Pipeline test should pin this so the fix doesn't accidentally introduce dead null-checks for genuinely non-null bindings.

- **`@condition` directive methods are unaffected.** User-authored `ConditionFilter` flows through a separate path (`ArgCallEmitter.buildCallArgs` at the call site, user-controlled method body); this change only touches `GeneratedConditionFilter` body emission via `BodyParam.nonNull`.

## Tests

Pipeline owns the regression assertion; execution carries distinct signal that pipeline cannot reach; compilation rides along on any `-Plocal-db` build and needs no dedicated test of its own.

- **Pipeline (codegen-level, primary).** Add a fixture under `graphitron-rewrite/graphitron/src/test/resources` and assert against the classified `BodyParam.nonNull` slot on the projected `GeneratedConditionFilter` (or against the emitted method's `TypeSpec` structural shape — presence/absence of an `if` guard around the `condition.and(...)` statement). Three cases, covering the three transitions in the proposed AND:

    1. **Nullable enclosing arg, non-null inner field** — `field(filter: InputType): [T!]` with `InputType.idList: [ID!]!` carrying `@nodeId`. `BodyParam.nonNull` must be `false`. The primary fix case; without it the generator emits an unguarded `DSL.row(...).in(null)` cascade.
    2. **Non-null enclosing arg, non-null inner field** — `field(filter: InputType!): [T!]` with `InputType.idList: [ID!]!`. `BodyParam.nonNull` must stay `true`; no `if`-guard regression. Pins the fix against over-correction.
    3. **Non-null enclosing arg, nullable intermediate `NestingField`, non-null inner field** — `field(filter: InputType!): [T!]` with `InputType.wrapper: WrapperInput` (nullable nested input object, no `@table`) and `WrapperInput.idList: [ID!]!`. `BodyParam.nonNull` must be `false`. This is the case that exercises the recursive AND on the `NestingField` wrapper introduced in "Threading shape" item (2); without it both case-1 and case-2 still pass while a buggy implementation that skips the wrapper-level AND leaves the inner field unguarded.

    Do NOT assert on a rendered code string of the method body — that's banned at every tier per `graphitron-rewrite/docs/rewrite-design-principles.adoc`.

- **Execution (only tier that catches the runtime `.in(null) → false` rendering).** Sakila execution test exercising a list-shaped optional filter input through real Postgres: omit the filter, assert the row count matches the unfiltered baseline rather than zero. Wire through `graphitron-sakila-service` `@condition` fixtures (cf. `InputFieldConditionFixtures` for the established shape). Pipeline cannot observe jOOQ's `.in(null)` rendering decision; execution must.

- **Compilation (guard, not a new test).** Generated source must compile against jOOQ types — already covered by `graphitron-sakila-example`'s default compile step. The guard catches any mismatch between the `if`-guard's parameter type and the row-in's typed columns. Call it out as a guard the change must not break; no new test artefact.

## Acceptance

- A schema with `field(filter: T): [X!]`, `T.k: [ID!]!` (or any non-null nested input field), without a `filter` argument at runtime, produces a WHERE clause with no `false`-literal contribution from the generated condition method.
- Top-level non-null + nested non-null stays unguarded — verified by the mirrored pipeline assertion.
- Pipeline + compilation + execution tiers pass under `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`.
