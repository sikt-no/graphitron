---
id: R123
title: "Parent-context-aware schema coordinates for per-directive Behavior policy"
status: Backlog
bucket: architecture
priority: 6
theme: lsp
depends-on: []
last-updated: 2026-07-15
---

# Parent-context-aware schema coordinates for per-directive Behavior policy

> **Premise partly overtaken by R347 Slice 2 (noted 2026-07-15).** R347
> (`lsp-structural-consolidation`) Slice 2 shipped a standalone `DirectivePolicy`
> (`graphitron-lsp/.../parsing/DirectivePolicy.java`): `bindsLiveMethod(name)` now owns the former
> `METHOD_VALIDATING_DIRECTIVES` set, and the five copy-pasted `@record` carve-outs plus the
> privately-owned method-validating set route through it. That is a *third* shape R123's A/B forks
> never considered, and it already resolves the set-duplication half of the smell below (step 4). It
> did not, however, make the per-directive discrimination stop being a name lookup: Diagnostics still
> calls `DirectivePolicy.bindsLiveMethod(directiveName)` at validation time. R123's surviving value is
> exactly fork A, the parent-context coordinate that carries the policy on the model so no consumer
> re-derives it by directive name. Rework this item onto the shipped `DirectivePolicy` baseline before
> implementing; do not implement the stale step 4 (the set it names is already centralized).

## Problem

R119 keyed the LSP's directive vocabulary on GraphQL-spec schema coordinates: `Directive`, `DirectiveArg`, `InputType`, `InputField`. The granularity is correct per the GraphQL spec, but it under-specifies one axis the LSP needs.

`InputField("ExternalCodeReference", "method")` is one coordinate, shared across every directive that carries an ECR slot:

- `@service`, `@condition`, `@externalField`, `@tableMethod`, `@reference(path:[{condition:}])` — the `method:` value is a method invocation. Validate as a method on the sibling `className`.
- `@record`, `@enum` — the `method:` value wraps a type, not a method invocation. Skip method validation.

The canonical overlay binds the shared coordinate to one `MethodNameBinding(classNameCoord)` arm. Diagnostics' validator then re-discriminates by the enclosing directive name; since R347 Slice 2 that discrimination is `DirectivePolicy.bindsLiveMethod(directiveName)` (`graphitron-lsp/.../parsing/DirectivePolicy.java`, called from `Diagnostics.java:734` at the time of writing), which centralizes the former hand-coded `METHOD_VALIDATING_DIRECTIVES` set but still keys on the directive name at validation time rather than on the coordinate.

That set is exactly the smell `development-principles.adoc` calls "two consumers evaluate the same predicate over a model field" — the Behavior overlay says "this is a method slot", the validator says "but only for these directives". The classifier knows the per-directive policy at parse time; collapsing both onto one arm and re-deriving via `Set.contains(directiveName)` in Diagnostics is the smell.

The same shape would surface for any future Behavior arm whose semantics differ by enclosing directive (the spec's "structurally inert on @externalField / @enum / @record" rule for `argMapping` is the next one queued).

## Two design forks

**A. Parent-context-aware `SchemaCoordinate`.** Extend the sealed hierarchy so the same input-field can carry distinct coordinates depending on its enclosing directive:

```java
public sealed interface SchemaCoordinate {
    record Directive(String name) implements SchemaCoordinate {}
    record DirectiveArg(String directive, String arg) implements SchemaCoordinate {}
    record InputType(String name) implements SchemaCoordinate {}
    record InputField(String type, String field) implements SchemaCoordinate {}
    record DirectiveArgInputField(String directive, String arg, String inputType, String field)
        implements SchemaCoordinate {}  // the new arm
}
```

The canonical overlay binds the new arm per (directive, arg, type, field) combination instead of (type, field). Diagnostics' `METHOD_VALIDATING_DIRECTIVES` set retires; the overlay carries the per-directive-context decision once.

**B. Per-policy axis on `Behavior`.** Keep the coordinate granularity; widen the arm:

```java
public sealed interface Behavior {
    record MethodNameBinding(SchemaCoordinate classNameCoord, ValidateAs validate)
        implements Behavior {}

    enum ValidateAs { METHOD_INVOCATION, TYPE_WRAPPER }
}
```

The canonical overlay still has one binding for `ExternalCodeReference.method`, but it must now describe both meanings simultaneously — which it can't, because the same coordinate can mean either depending on context. So this fork only works if (A) is also applied: distinct coordinates carry distinct `ValidateAs` values.

In other words, B is a follower of A; A alone solves the problem.

## Recommended shape

Pick A. The new arm is purely additive: existing consumers continue to handle the four GraphQL-spec coordinates; the new `DirectiveArgInputField` arm fires for the parent-context-aware overlay entries.

The migration:

1. Add `DirectiveArgInputField` to `SchemaCoordinate`.
2. Update `LspVocabulary.coordinateAt` and `leafCoordinates` to emit the new arm when descending from a directive arg into an input type's field. Today's `InputField(type, field)` becomes `DirectiveArgInputField(directive, arg, type, field)`. Unconditional — the cursor is always inside *some* directive's argument tree, so the parent context always exists.
3. Update `CanonicalOverlay` to bind the seven method-validating directives to `MethodNameBinding` and not bind `@record`/`@enum`'s method.
4. (Largely done by R347 Slice 2.) The `METHOD_VALIDATING_DIRECTIVES` set is already centralized into `DirectivePolicy.bindsLiveMethod`; what remains for fork A is to make the coordinate carry the policy so `Diagnostics` and `MethodCompletions.generate` stop calling `bindsLiveMethod(directiveName)` and read the decision off the `DirectivePolicy` binding directly. Rework this step against the shipped predicate rather than the retired set.
5. The structural startup invariant continues to fire on every overlay coordinate.

Step 2 is the load-bearing change. The 4-arm `SchemaCoordinate` is replaced wholesale by a 5-arm hierarchy with `InputField(type, field)` retained for any future "type-keyed coordinate" use case (none today inside the LSP, but it's the more-general arm).

Alternative shape worth considering: drop `InputField(type, field)` entirely and have `DirectiveArgInputField` always carry the parent-context. Smaller hierarchy, but it would prevent future use cases that key purely on input-type position. Defer the choice to the implementer; the spec is the right place to decide.

## Out of scope

- The `argMapping` content-syntax inertness rule R119 spec body line 286-288 names. That rule is also a per-enclosing-directive policy ("structurally inert on @externalField / @enum / @record"), and would benefit from the same parent-context-aware coordinate split. R119 ships with `ArgMappingBinding` as a marker; this item's resolution unlocks the parent-context split for `argMapping` too. Filing the content-syntax validator as a separate item is correct; the policy axis lands here.

## Surfaced from

R119 phase 3 self-review (architect agent pass on commit `4ae827d`). Findings #1 and #2 from that review fold into this single item. The architect's stronger-shape recommendation: option A (extend `SchemaCoordinate`).
