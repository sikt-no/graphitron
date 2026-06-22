---
id: R355
title: "Infer depth-1 nested @condition arg bindings by name without argMapping"
status: In Review
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

## Why depth 1, and why that is a boundary rather than a cap awaiting a follow-up

Mechanically, depth-N inference would be nearly free: `PathExpr.Step` is recursive,
`ArgBindingMap.of` already resolves N-segment overrides, `searchSlotForMatchingPath` already
recurses to arbitrary depth for the *suggestion*, and `ArgCallEmitter` already emits
arbitrary-depth Map traversal. So the answer to "why not depth 2" is not implementation cost.
It is that depth-1 is the smallest scope that covers the real cases while keeping the
inference's central question, "is this binding unambiguous?", trivial to answer:

- **Deeper descent drags in complex and recursive input types.** Past one hop the search has
  to walk a tree that can be self-referential (`input Filter { and: [Filter], field: String }`),
  so it needs cycle-guarding (the `visited` set `searchSlotForMatchingPath` already carries),
  and "is there exactly one field named `p` of type `T`?" becomes *path-dependent*: the same
  leaf can be reachable by several routes, and uniqueness now means uniqueness across a graph,
  not a list. That is exactly the kind of judgement that is hard for both the tool and a
  human reviewer to trust when the result is an auto-bound, silently-rewritten call. At depth
  1 the question collapses to "is there one direct field of the slot named `p` with type `T`?"
  No recursion, no cycle guard, no multi-path uniqueness.
- **Depth 1 covers the overwhelming majority of real cases.** The motivating shape, a
  condition parameter pulling a direct field out of its filter input (`SokVerdiRange.fraVerdi`),
  is a one-hop descent. Any fixed depth is somewhat arbitrary, but 1 is the smallest that
  covers the common case, so it is the right place to stop until a concrete deeper case
  actually appears.
- **Supporting: legibility.** A one-hop descent is also where the parameter name still
  plainly names its source field at the call site; a multi-hop path is where the name alone
  no longer tells a reader where the value came from.

Depth ≥ 2 is therefore handled by **explicit `argMapping`** (and R249's richer nested syntax)
*by design*, not by a future inference increment: making the descent explicit is the feature,
because that is where the schema should document the path. The bound is a single guard in the
search, so if a real deep-nesting case ever justifies revisiting, lifting it is localized;
but the default answer for depth ≥ 2 is and remains explicit `argMapping`.

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
  and unclassified-scalar leaves, so R355 only ever binds **canonical-scalar-typed leaves** (the
  user's `BigDecimal` case, or a list thereof: `mapToJavaTypeName` preserves list depth, so a
  `[BigDecimal]` field binds a `List<BigDecimal>` param with `liftsList=true`, identical to the
  explicit path). A depth-1 field that is itself a named input object or an enum does not match
  and stays explicit. State this as a scope fact, not an accident.
- **`liftsList` must be computed, not hardcoded** — so the synthesised `Step` is *byte-identical*
  to the explicit-`argMapping` one. Compute it with the same `isListShaped` (strip non-null, test
  for list) `ArgBindingMap.of` uses; the pipeline PathExpr-equality test below is what pins it (a
  flag that diverged from `isListShaped` surfaces there as a `PathExpr` mismatch). The flag is
  *not* load-bearing at emit for the depth-1 leaf R355 produces: `ArgCallEmitter.extractionForArg`
  carries only the segment *name* into `NestedInputField` and `hasIntermediateListSegment` skips
  the terminal segment, so a wrong leaf `liftsList` is a classified-model divergence, never a
  silent wrong traversal. The obligation is therefore model-equality, not an emit consequence —
  which is why the pin must be a list-shaped depth-1 field: a scalar field's `liftsList` is
  `false` either way, so only a list field distinguishes a computed flag from a hardcoded one.

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
  `slotTypes` into `reflectTableMethod`, so the one inference change covers all three. The named
  tests exercise the input-field-level scope only; that suffices because the inference branches
  on `slotTypes` and parameter shape, never on the `@condition` coordinate — the scopes differ
  solely in upstream `slotTypes` assembly, which this item does not touch. (The path-step
  `@condition` in `resolveConditionRef` carries no `slotTypes` and is unaffected, as with R214.)

## Tests

- **Unit** (`ArgBindingMapTest` or a `ServiceCatalog` inference test): the depth-1 search
  helper returns the single candidate for a one-match input object, `null` for a
  name-with-wrong-type field, and `null` for two slots that each carry a matching name.
- **Pipeline (primary tier).** A `GraphitronSchemaBuilderTest` case mirroring
  `TABLE_INPUT_FIELD_CONDITION_ARGMAPPING` (`:3963`) but with the `argMapping` removed and a
  `SokVerdiRange`-shaped two-`BigDecimal` input field; assert the inferred `ConditionFilter`
  params carry the *same* `PathExpr` chain the explicit-`argMapping` sibling produces
  (classifier-output equality, not emitted body strings, per the body-string ban). A second
  case pins the computed `liftsList`: a depth-1 **list** field (`[BigDecimal]` bound to a
  `List<BigDecimal>` param) whose inferred `Step` must carry `liftsList=true`, again equal to
  its explicit-`argMapping` sibling — the scalar case above leaves `liftsList=false` either way,
  so only this case separates a computed flag from a hardcoded one. A third case: an ambiguous
  name across two input-object args falls through to the existing rejection / suggestion.
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
