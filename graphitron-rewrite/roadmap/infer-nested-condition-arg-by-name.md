---
id: R355
title: "Infer depth-1 nested @condition arg bindings by name without argMapping"
status: Backlog
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

Add a name-based depth-1 unpacking branch to `inferBindingsByType`, running after the
head-level identity / arity-unique / type-unique rules leave a parameter unbound. For a
still-unbound Java parameter `p`, search every unclaimed slot whose unwrapped GraphQL type
is an input object; if exactly one `(slot, depth-1 field)` pair across all slots has a field
whose name equals `p.name()` **and** whose `mapToJavaTypeName` equals `p`'s parameterized
type, bind `p` to `PathExpr.step(head(slot), fieldName, liftsList)`. This produces the
identical `PathExpr` that `argMapping: "p: slot.field"` would, so downstream emission
(`ConditionResolver.rewrapForNested` → `CallSiteExtraction.NestedInputField`) is unchanged;
the rule only fills in the path the author would have hand-written.

## Scope / design notes for Spec

- **Depth limited to 1** (direct fields of the slot's input object), per the author's
  request; do not recurse deeper.
- **Name and type must both match.** Name alone could bind a param to a field of the wrong
  Java type; the type gate keeps the inference as confident as the existing rules.
- **Uniqueness is across all unclaimed slots.** The input-field-`@condition` case has a
  single slot, so the match is unique by construction; field-level `@condition` with
  multiple args needs the cross-slot uniqueness guard so an ambiguous name still falls
  through to the existing `argMapping` suggestion rather than guessing.
- **Runs only on still-unbound params,** so a top-level slot name match still wins over a
  nested one.
- **Reaches every `@condition` scope.** Arg/field-level (`ConditionResolver.resolveArg` /
  `resolveField`) and input-field-level (`BuildContext.buildInputFieldCondition`) all pass
  `slotTypes` into `reflectTableMethod`, so the one inference change covers all three.
- **Tension to weigh in Spec.** The current design intentionally prefers *suggesting*
  `argMapping` over auto-binding nested paths (R214/R219 history). This item narrows that
  conservatism for the unambiguous name+type depth-1 case only; the suggestion path stays
  for everything ambiguous.
- **Files in play:** `ServiceCatalog.java` (`inferBindingsByType` plus a name-keyed depth-1
  search helper mirroring `searchSlotForMatchingPath`). Likely no emit-side changes.

## Related

- R249 (nested `argMapping` *syntax* via `GraphQLSelectionParser`) — a different axis: that
  item enriches what an author can *write*; this item infers what they can *omit*.
- R219 (unify arity-unique and type-unique inference under one `JavaTypeKey` rule) — the
  type-based inference this branch sits beside; a Spec should decide whether the name-based
  branch folds into that unification or stays a distinct, name-keyed rule.
- R214 (shipped the layered type-based inference).
