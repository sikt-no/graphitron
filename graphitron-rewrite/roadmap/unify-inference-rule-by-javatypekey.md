---
id: R219
title: "Unify arity-unique and type-unique inference under a single JavaTypeKey-counted rule"
status: Backlog
bucket: architecture
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Unify arity-unique and type-unique inference under a single JavaTypeKey-counted rule

R214's `inferBindingsByType` shipped with two sibling rules (arity-unique and type-unique) sequenced as a "fallback ladder": arity-unique returns early when applicable; type-unique handles the residual case. The principles-architect review (round 1, finding 2) flagged this as a discontinuity: a working schema `(input: SomeInput) → (SomeInput payload)` binds via arity-unique today; the moment an SDL author adds a second argument of an unrelated type, the same payload now needs the type-unique branch, which by construction can't see named-input-object slots (`mapToJavaTypeName` returns `null` for them and they're dropped from `slotsByType`). The binding silently disappears. The user's stated rule was "one and only one possible mapping" — a second argument of a different type doesn't introduce a second mapping for the existing pair, it just adds a sibling.

Direction: collapse both branches under a single rule keyed on a richer `JavaTypeKey`. For each (param, slot) pair, derive a key that is either the canonical Java FQN (when `mapToJavaTypeName` returns non-null) or a fresh sentinel keyed on the slot's GraphQL type name (when it doesn't — named input object, enum). Bind exactly when both the param's key-count and the slot's key-count are 1 AND `anyReachableNestedMatch` returns false for that key (the existing ambiguity guard already extended in round 2 to both branches). The arity-unique branch drops out as the special case where the sentinel is unique by construction. The scalar-vs-named-input floor case is enforced not by branch separation but by the canonical-scalar key not matching the named-input sentinel.

Scope notes for Spec:

- **JavaTypeKey shape.** Sealed `JavaTypeKey { CanonicalScalar(String fqn), NamedInputSentinel(String graphqlTypeName), EnumSentinel(String graphqlTypeName), ListOfCanonical(JavaTypeKey element), ... }`. The walker building keys from a slot mirrors `mapToJavaTypeName` but doesn't lose information when the type is non-scalar.
- **Param side.** Java parameters need the same key derivation. For a canonical scalar param, the key matches the slot's canonical scalar key directly. For a non-canonical param (record / list-of-record), the key needs to encode "I'm a custom Java type" — and equality between param-side and slot-side keys becomes "this Java type is the InputBeanResolver-resolution of that GraphQL input type." That's a stronger collaboration with InputBeanResolver than R214 currently has.
- **Soft fork: InputBeanResolver collaboration.** Today the inference runs before bean enrichment. To compare named-input slot to non-canonical Java param structurally, the inference would either pre-resolve the bean mapping (adds a phase dependency) or stay name-only on that axis (current behavior). Pick one in Spec.
- **Strictness.** Today's arity-unique branch implicitly allows "shape matches because there's only one possible mapping". A strict JavaTypeKey rule could refuse to bind when the param's key and the slot's key are different sentinels (param=`NamedInputSentinel("FilmInputBean")` vs slot=`NamedInputSentinel("FilmGraphQLInput")`). That's safer but more conservative; trade-off needs a decision.

Files in play: `ServiceCatalog.java` (the entire `inferBindingsByType` body collapses to one loop), `model/ParamSource.java` only if provenance lands first (R218 sibling), `InputBeanResolver.java` if the structural-collaboration path is taken.

Related: R214 (sibling, shipped with the layered rule; this item is the principled unification), R218 (provenance — useful prerequisite for distinguishing the cases visibly), R215 (parallel example of classifier rules being lifted into typed structure rather than sequenced conditionals).
