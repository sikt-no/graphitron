---
id: R335
title: "Fold input/scalar/enum classification into the single classify-and-emit walk"
status: Spec
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Fold input/scalar/enum classification into the single classify-and-emit walk

Offshoot of R317 (the single classify-and-emit walk, now Done). R317 made one
`SchemaTraverser.depthFirst` over the *output* surface the sole classifier of object /
interface / union types, with field classification folded onto the same enter visit. But the
walk's child function (`SchemaReachability.childrenOf`) descends output edges only
(field-output, union-member, interface-implementor, object/interface `implements`), so it never
reaches the *input* surface: input objects, and the scalars / enums that sit only on argument and
input-field coordinates. Those leaf kinds are still classified in a separate pre-walk sweep,
`TypeBuilder.prepareForWalk` looping `classifyAndRegister` over `getAllTypesAsList()`
(`TypeBuilder.java:203-216`). The walk is "single" for outputs and two-pass for everything else.

This item makes the walk classify the whole reachable surface by extending the traverser's child
function to the input edges, so inputs / scalars / enums are classified by the visitor as the walk
reaches them, and the pre-walk leaf sweep is deleted. The result is one traversal that classifies
every kind, output and input alike.

## Why this is reachable now (the enabling fact)

The pre-walk sweep classifies leaves *before* the walk for a stated reason that R317 itself made
stale: that "field classification reads input / scalar / enum verdicts from `ctx.types` during the
walk" (the `prepareForWalk` javadoc). Every such read in `FieldBuilder` now goes through
`TypeBuilder.lookAheadVerdict(...)`, which recomputes the verdict registry-free from SDL +
reflection bindings + catalog. The marker at `FieldBuilder.java:5475` records "the last `ctx.types`
read in FieldBuilder; the read-free invariant now holds." So nothing forces a leaf's verdict to
exist in the registry before the field that references it is classified. That is exactly the slack
this item spends: a leaf can be registered *after* the field that reaches it, because the field's
read of the leaf never touches the registry.

The read-free visitor invariant (R317 / R325) is therefore preserved, not weakened: the visit still
only `register`s, never reads the registry under construction. Under the extended walk a field's
argument input type is a not-yet-visited child of the field's parent, classified on a later enter,
and the field's `lookAheadVerdict` read of it is registry-free, so order-independence holds for the
input surface for the same reason it holds for the output surface.

## Decisions settled (forks the author and reviewer have closed)

- **Pruning is in scope and is the point.** Today the leaf sweep runs over the unpruned, all-declared
  `getAllTypesAsList()`, so an input / scalar / enum that no field or argument reaches is still
  classified and lands in `GraphitronSchema.types()`. Moving classification onto the walk makes leaf
  classification reachability-driven: an unreached leaf is an orphan and is pruned, exactly as R279
  slice 6 made an unreached output object an orphan prune. This makes reachability uniform across all
  kinds (today: outputs pruned, leaves classified whether used or not), which is the simplification.
  This is a real model delta (a previously-classified unreachable leaf disappears from `types()`) and
  carries primary-gate coverage; see Acceptance.
- **Pruned-leaf warnings are out of scope.** Any "you declared an input / enum / scalar nothing
  reaches" diagnostic is a validation sweep over the schema, not a classification concern. R335 prunes
  silently (a pruned leaf is simply absent from `types()`), exactly as the output orphan prune does;
  surfacing it belongs with the existing `warn-on-pruned-unreachable-types` backlog item (scoped to
  output types today), which this item's prune widens the surface for. Cross-reference, do not absorb.

## The shape

Three moves, plus the deletions they enable:

1. **Extend `SchemaReachability.childrenOf`** with the input edges. For a `GraphQLObjectType` /
   `GraphQLInterfaceType`, descend each field's *argument* types (unwrapped) in addition to its
   output target. Add a `GraphQLInputObjectType` arm descending each input field's type (unwrapped).
   Scalars and enums stay leaves (`default -> List.of()`). The existing `expanded` identity-set
   already terminates recursive input types and dedups shared scalars, so no new cycle handling is
   needed. `reachableTypeNames` shares `childrenOf`; widening it means the observatory now reports
   reachable leaves too. Leave the observatory's recorded *set* output-only (do **not** widen
   `recordIfNamedType`, `SchemaReachability.java:135-142`) and let the *walk* (the classifier) own the
   new edges, so `SchemaReachabilityTest`'s `reachable ⊆ classified` invariant is not silently
   restated. **Update the class-level javadoc** at `SchemaReachability.java:54-57` as part of this
   move: it currently states arguments and input objects are "deliberately not descended" and that
   "no output type is reachable only through an argument position", which becomes false the moment the
   edges are added. That prose is load-bearing (it explains *why* the child function was output-only);
   leaving it makes it a false invariant no test pins. **No default-value descent is needed**: an enum
   or scalar is reachable through an argument or input-field *type* edge, and by the SDL conformance
   rule a default-value literal must conform to its declared type, so the type edges already subsume
   every leaf a default value could reference; there is no separate "default literal references a leaf
   the type edge misses" case.
2. **Add visitor callbacks** to `GraphitronSchemaBuilder.ClassifyingVisitor`:
   `visitGraphQLInputObjectType`, `visitGraphQLScalarType`, `visitGraphQLEnumType`, each calling
   `typeBuilder.classifyAndRegister(node)`. `classifyType` already handles all three kinds; the only
   change is the call site moving from the sweep to the visit. The new leaf arms are safe under the
   `null`-fieldBuilder types-only seam: unlike the object arm (which guards field classification behind
   `fieldBuilder != null`, `GraphitronSchemaBuilder.java:346`), the leaf arms only call
   `classifyAndRegister` and do no field work, so they need no `fieldBuilder` guard. The types-only test
   seam (`buildContextForTests`, `GraphitronSchemaBuilder.java:233-234`) drives the same
   `ClassifyingVisitor`, so under the extended edges it now descends the input surface and fires these
   leaf callbacks too, classifying leaves through the walk; its post-sweep expectations move with it.
3. **Delete the pre-walk leaf sweep** in `prepareForWalk` (the `getAllTypesAsList` loop calling
   `classifyAndRegister` on non-composite kinds). `prepareForWalk` keeps its other work; see below.

## What stays in `prepareForWalk` (and must be checked, not assumed)

These passes iterate `getAllTypesAsList` for reasons independent of the walk and do **not** move:

- `buildClassificationIndices` (node / table / error / participant) is deliberately a superset over
  all declared types (R317 slice 3d); untouched.
- `emitDirectiveIgnoredWarning` is an order-stable SDL-order pass reading only the reflection fixed
  point; untouched.
- `retainedSupportTypes()` carries the *same* all-declared dependency as the indices, less obviously:
  `classifyType`'s published-support-type arm (`SortDirection`) gates on `retainedSupportTypes()`
  (`TypeBuilder.java:770-773`, `871-887`), which scans `getAllTypesAsList()` for references to the
  published support types. It is registry-free, so the read-free invariant survives, but it is an
  all-declared *superset* scan, not reachability-pruned. The interaction with R335: when
  `SortDirection` moves from the sweep onto the walk, its verdict still depends on this all-declared
  reference scan, while whether it lands in `types()` is now decided by the (reachability-pruned) walk.
  A `SortDirection` referenced only from an unreachable coordinate is still "retained" by the scan yet
  never visited by the walk, so it is pruned from `types()` anyway. That is the correct outcome under
  the settled prune fork, but it is exactly where a prune-vs-retain mismatch would hide; the
  prune-proof acceptance case must include a published-support-type sub-case (see Acceptance).
- `surfaceMultiProducerRejections` pre-registers `UnclassifiedType` demotions for binding-rejected
  types, inputs included (`TypeBuilder.java:610`/`:615`/`:229`). This is the load-bearing interaction,
  and it is **not** automatically idempotent under `register`. Under R335 a *reachable* rejected input
  is also visited by the walk, which calls `classifyAndRegister`. If `classifyAndRegister` runs
  `classifyType` first, it returns a live `TableInputType` / `InputType` verdict; the registry already
  holds an `UnclassifiedType`, the classes differ, so `TypeRegistry.register`'s final arm
  (`TypeRegistry.java:94-102`) **re-demotes to a fresh generic-structural `UnclassifiedType`**, clobbering
  the typed `Rejection` payload (`RecordBindingMultiProducer`) the validator and candidate-hint path
  key on. The fix is mandatory, not conditional: make `classifyAndRegister` consult
  `bindings.rejection(name)` *first*, mirroring `lookAheadVerdict` (`TypeBuilder.java:356-359`), so the
  walk reconstructs the *same* `UnclassifiedType` payload and `register`'s `equals`-idempotent arm
  (`TypeRegistry.java:80-82`) fires instead of the demote arm. Idempotency then holds by construction
  (same payload), not by luck of reconciliation.

## Slicing

Per the anti-narrative rule (no slice may be structure-only), each slice changes observable behaviour
or is folded with one that does:

1. **Extend the child function + add the visitor callbacks + delete the sweep + the
   `bindings.rejection`-first guard in `classifyAndRegister`, in one slice.** This is the behaviour
   change (leaves become reachability-pruned) and cannot be split into a structure-only precursor:
   adding the edges without deleting the sweep does not merely double-classify wastefully, it
   *corrupts* the multi-producer rejection payload (the sweep registers the typed `UnclassifiedType`,
   then the walk's second `register` re-demotes to the generic structural one; see the
   `surfaceMultiProducerRejections` note above), so the two halves are payload-load-bearing on each
   other; deleting the sweep without the edges drops all leaves. The `bindings.rejection`-first guard
   is part of this slice, not a follow-on: the re-demote drift is deterministic for every reachable
   rejected input the moment the walk visits it, so there is no "land it whole, fix drift if it
   appears" branch; the guard ships with the edges. Gated by the primary-gate coverage below.

## Acceptance

- Primary gate (`GraphitronSchemaBuilderTest` truth table + sakila pipeline `TypeSpec` + Java-17
  `graphitron-sakila-example` compile + PostgreSQL execution tier) stays green.
- A new case proving the prune: an input / enum / scalar declared in SDL but reached by no field or
  argument is classified and present in `types()` before R335, absent after. This is the falsifiable
  model delta. Include a **published-support-type sub-case** (`SortDirection` referenced only from an
  unreachable coordinate): it is the one leaf whose verdict is itself a function of the all-declared
  `retainedSupportTypes()` scan, so it is where a prune-vs-retain mismatch would hide.
- A case proving order-independence on the input surface: a field whose argument is an input type
  classifies correctly when that input type is visited after the field (the read-free invariant on the
  input edges), mirroring R317's "no type registered before its discovering field is visited" test.
- A case proving a reachable multi-producer-rejected input still ends as `UnclassifiedType`,
  asserting on the **rejection variant** (`RecordBindingMultiProducer`), not merely on
  `UnclassifiedType`-ness: the generic-structural re-demote would pass a class-only assertion while
  silently swapping the payload, so the variant assertion is what guards the `bindings.rejection`-first
  guard.
- `SchemaReachabilityTest`'s `reachable ⊆ classified` invariant holds under the widened walk.

## Relationship to other items

- **R317** (Done): the parent. R335 extends R317's single-walk thesis from the output surface to the
  whole surface; it does not reopen any R317 mechanism.
- **R97** (`consumer-derived-input-tables`, which absorbed R327's field-relative input
  classification on 2026-06-22): orthogonal but adjacent, both touch input classification. R97 changes
  *how* an input's table-boundness is derived (consumer-derived, retiring the
  `findReturnTablesForInput` aggregate); R335 changes *where and when* an input is classified (on the
  walk, reachability-pruned, vs the pre-walk all-declared sweep). No hard ordering; whichever lands
  first, the other rebases onto the moved call site. Note the interplay in whichever ships second.
- **warn-on-pruned-unreachable-types** (Backlog): R335 widens the pruned surface from output types to
  all kinds; that item's warning should grow to cover the leaves R335 starts pruning. Out of scope here.
