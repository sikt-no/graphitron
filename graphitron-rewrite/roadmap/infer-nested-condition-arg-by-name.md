---
id: R355
title: "Infer depth-1 nested @condition arg bindings by name without argMapping"
status: Spec
bucket: feature
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-22
last-updated: 2026-06-22
---

# Infer depth-1 nested @condition arg bindings by name without argMapping

A `@condition` directive whose slot is an input object with scalar fields requires an
explicit `argMapping` to bind the condition method's parameters to the nested fields, even
when the parameter names already match the nested field names exactly. The author who hit
this wants to drop the boilerplate: when a method parameter's name and Java type match a
single depth-1 field of the slot's input object, graphitron should walk one level in and
bind it automatically, exactly as the hand-written `argMapping: "p: slot.field"` would.

Real-world example:

```graphql
vektingstallIntervall: SokVerdiRange @condition(condition: {
  className: "...UtdanningsspesifikasjonCondition",
  method: "searchVektingstallRange",
  argMapping: "fraVerdi: vektingstallIntervall.fraVerdi, tilVerdi: vektingstallIntervall.tilVerdi"
}, override: true)

input SokVerdiRange { fraVerdi: BigDecimal  tilVerdi: BigDecimal }
```

```java
public static Condition searchVektingstallRange(
    Utdanningsspesifikasjon table, BigDecimal fraVerdi, BigDecimal tilVerdi)
```

The params `fraVerdi`/`tilVerdi` already match the depth-1 nested field names; the
`argMapping` restates what the names already say.

## Why it fails today

`ServiceCatalog.inferBindingsByType` is purely type-based. The slot `vektingstallIntervall`
(`SokVerdiRange`) has two `BigDecimal` fields, so type-based inference is ambiguous: two
`BigDecimal` params cannot be told apart from two `BigDecimal` fields by type alone. The
existing conservative design (`anyReachableNestedMatch` blocks the type-unique branch;
`unambiguousReachablePath` only emits an `argMapping` *suggestion* in the rejection message)
deliberately declines to auto-bind a nested path. The disambiguator the design does not
currently consult is the parameter **name**: param `fraVerdi` ↔ field `fraVerdi`.

## Proposed rule

Add a name-based depth-1 unpacking branch to `inferBindingsByType`, running on the residual
`unboundParams` after the existing arity-unique (`ServiceCatalog.java:1101`) and type-unique
(`:1115`) branches; identity binding is handled earlier in `ArgBindingMap.of`, so by this
point a parameter that matched a slot by its own name is already bound and out of scope.

For a still-unbound Java parameter `p`, scan every unclaimed slot whose unwrapped (non-null,
non-list) GraphQL type is a `GraphQLInputObjectType`; collect each direct field `f` where
`f.name().equals(p.name())` **and** `mapToJavaTypeName(f.getType())` is non-null and equals
`p`'s parameterized-type name. If exactly one such `(slot, field)` candidate exists across
all unclaimed slots, bind `p` to `PathExpr.step(PathExpr.head(slot), f.name(), liftsList)`
where `liftsList = isListShaped(f.getType())` (the identical computation `ArgBindingMap.of`
performs for an explicit `argMapping` step). Zero candidates or more than one leaves `p`
unbound, so the existing per-parameter rejection (with its `unambiguousReachablePath`
`argMapping` suggestion) still fires.

The binding is the *identical* `PathExpr` a hand-written `argMapping: "p: slot.field"`
produces, so downstream emission (`ConditionResolver.rewrapForNested` →
`CallSiteExtraction.NestedInputField`, and `ArgCallEmitter`'s segment walk) is byte-for-byte
unchanged. The rule only fills in the path the author would otherwise have written.

## Depth-1 is a boundary, not a cap awaiting a follow-up

The sealed `PathExpr.Step` is recursive, `ArgBindingMap.of` already resolves N-segment
overrides, `searchSlotForMatchingPath` already recurses to arbitrary depth for the
*suggestion*, and `ArgCallEmitter` already emits arbitrary-depth Map traversal with
intermediate-list streaming. So depth-N inference would be nearly free mechanically, and a
hard depth number invites the "why not depth 2" churn the R219 review (finding 2) already
called a discontinuity. This item nonetheless scopes inference to **depth 1 by design**, and
names depth-N as a deliberate non-goal rather than a deferred increment:

- Auto-binding silently rewrites the call the author wrote. A one-hop descent is exactly the
  case where the parameter name unambiguously names a *direct* field of the slot, so the
  binding stays legible at the call site. A multi-hop descent (`a.b.c.fraVerdi`) is where the
  parameter name alone no longer tells a reader where the value comes from without walking
  the type tree; that path should be **documented explicitly** via `argMapping`, which is
  what `argMapping` (and R249's richer nested syntax) exist for.
- Uniqueness, not depth, is the soundness boundary; the depth-1 scope is a *legibility*
  choice layered on top. The Spec records this so the Spec → Ready reviewer reads depth-1 as
  intentional, not arbitrary.

Considered and not taken: a uniqueness-bounded full-depth rule that reuses
`searchSlotForMatchingPath`'s recursion (drop the depth bound, add the leaf-name predicate).
It is a strictly localized change if a reviewer prefers it; the reason to hold the line at
depth 1 is the legibility argument above, not implementation cost.

## Name and type must both match (the removed-rejection obligation)

This branch converts a sub-case of an existing build-time *rejection* (the
`unambiguousReachablePath` arm at `ServiceCatalog.java:295-333`) into an *acceptance* that
emits code, so it carries the validator-mirrors-classifier obligation: the new acceptance
must be as safe to emit as the path it replaces.

- **Both name and `mapToJavaTypeName` must match.** Name-only would be the single inference
  path that binds across a Java type the emitter cannot vouch for; `rewrapForNested` would
  then hand the condition method a value of a type the schema does not guarantee (the
  "defensive cast that throws on a real request" the principles warn against). Name+type
  keeps the branch exactly as confident as its arity-unique / type-unique siblings, which
  also gate on `mapToJavaTypeName`.
- **`mapToJavaTypeName(field) != null` is the leaf-type predicate**, the same null-is-no-match
  discipline `unambiguousReachablePath` uses. It returns `null` for named-input-object, enum,
  and unclassified-scalar leaves, so R355 only ever binds **canonical scalar leaves** (the
  user's `BigDecimal` case). A depth-1 field that is itself an input object or an enum does
  not match and stays explicit. State this as a scope fact, not an accident.
- **`liftsList` must be computed, not hardcoded.** `rewrapForNested` preserves `arg.path()`
  and rewrites only the extraction, so a wrong `liftsList` on the synthesised `Step` emits a
  wrong traversal silently in the input-field-`@condition` case. Compute it with the same
  `isListShaped` (strip non-null, test for list) `ArgBindingMap.of` uses.

## Placement, slot set, and the R219 interaction

- **Range over the unclaimed slot set** (`unclaimedSlotNames`), the same set the type-unique
  branch and `anyReachableNestedMatch` already read, so R355 stays a peer of its siblings.
  Ranging over *all* slots would let it read a slot already head-bound to another parameter
  field-wise while that parameter reads it whole; sound at emit time but a surprising shape.
  The unclaimed restriction sidesteps it and is what makes an ambiguous name on a multi-arg
  field-level `@condition` fall through to the suggestion as intended. The
  input-field-`@condition` case (single slot) is unaffected by the choice.
- **R219 interaction (state as a pinned invariant).** R219 will fold arity-unique and
  type-unique into one `JavaTypeKey`-counted rule; R355's disambiguator is the parameter
  *name*, an orthogonal axis, so it lands as a **distinct, name-keyed branch** (not spliced
  into the count rule). The coordination point is the claimed-slot set: R355 runs on
  still-unbound params after the count rule, and a head-level claim of slot `S` removes `S`
  from R355's unclaimed set. R355 is the *only* path that reaches scalar fields *inside* a
  named-input slot (the count rules drop those slots because `mapToJavaTypeName` returns
  `null` for them), so R219's unification must not claim such a slot at head level in a way
  that suppresses R355's descent.
- **Reaches every `@condition` scope.** Arg/field-level (`ConditionResolver.resolveArg` /
  `resolveField`) and input-field-level (`BuildContext.buildInputFieldCondition`) all pass
  `slotTypes` into `reflectTableMethod`, so the one inference change covers all three.

## Tests

- **Unit** (`ArgBindingMapTest` or a `ServiceCatalog` inference test): the depth-1 search
  helper returns the single candidate for a one-match input object, `null` for a
  name-with-wrong-type field, and `null` for two slots that each carry a matching name.
- **Pipeline (primary tier).** A `GraphitronSchemaBuilderTest` case mirroring
  `TABLE_INPUT_FIELD_CONDITION_ARGMAPPING` (`:3963`) but with the `argMapping` removed and a
  `SokVerdiRange`-shaped two-`BigDecimal` input field; assert the inferred `ConditionFilter`
  params carry the *same* `PathExpr` chain the explicit-`argMapping` sibling produces
  (classifier-output equality, not emitted body strings, per the body-string ban). A second
  case: an ambiguous name across two input-object args falls through to the existing
  rejection / suggestion.
- **Execution.** A round-trip on a two-`BigDecimal`-field fixture (alongside
  `InputFieldConditionFixtures`) confirming the no-`argMapping` form filters identically to
  the explicit-`argMapping` form. This is the test that proves the narrowed acceptance is
  correct, not merely non-erroring.

## Files in play

`ServiceCatalog.java`: a new name-keyed depth-1 search helper plus the residual-param branch
at the tail of `inferBindingsByType`. No emit-side change is expected (the `PathExpr` is
identical to the explicit-`argMapping` one); the execution test guards that expectation.

## Related

- R249 (nested `argMapping` *syntax* via `GraphQLSelectionParser`): a different axis. That
  item enriches what an author can *write* for multi-hop and scattered descent; this item
  infers what they can *omit* for the one-hop name match.
- R219 (unify arity-unique and type-unique inference under one `JavaTypeKey` rule): the
  type-based inference this branch sits beside. This Spec decides R355 lands as a distinct,
  name-keyed branch (the name axis is orthogonal to R219's count-of-key axis) and pins the
  claimed-slot ordering invariant the two rules coordinate on.
- R214 (shipped the layered type-based inference this branch follows).
