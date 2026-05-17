---
id: R96
title: "Derive backing-class binding from reflection; warn on redundant @record"
status: Spec
bucket: model-cleanup
priority: 6
theme: model-cleanup
depends-on: []
---

# Derive backing-class binding from reflection; warn on redundant @record

Graphitron classifies an SDL `OBJECT` or `INPUT_OBJECT` against a Java
backing class to drive accessor resolution and eight sealed variants:
four on the result axis (`JavaRecordType`, `JooqRecordType`,
`JooqTableRecordType`, `PojoResultType.Backed`) and four on the input
axis (`JavaRecordInputType`, `JooqRecordInputType`,
`JooqTableRecordInputType`, `PojoInputType`). Today the binding from
SDL type to backing class is sourced entirely from the `@record(record:
ExternalCodeReference)` directive's `className` argument: `TypeBuilder`
reads the directive at line 386 to dispatch into `buildResultType`
(line 696), populates `recordBackingClasses` from `dir.getArgument(
ARG_RECORD)` at lines 715 and 725, and the map is consumed by
`FieldBuilder.resolveRecordAccessor` (line 3808). Without `@record`,
result types fall through to `PlainObjectType` at line 391 and
field-level accessor resolution is skipped.

That class is already discoverable on the root-producer side: an
`@service` method's return type, a `@table`-resolved jOOQ record, or a
`@tableMethod` return all name the same class the directive restates.
R96 makes reflection the sole source of the SDL → backing-class
binding for every reachable type. The walk grounds at root producers
and extends recursively through parent accessor return types. The
`@record` directive's `className` argument no longer participates in
the binding: graphitron reads the directive only to surface a
"directive ignored; remove it" warning, and the existing
`recordBackingClasses` population from `dir.getArgument(ARG_RECORD)`
is removed. Retirement of the directive declaration at
`directives.graphqls:290` is then a pure author-cleanup path: drive
the directive-ignored warning count to zero, then the declaration can
land in a separate item.

## Scope

Three checkpoints across `TypeBuilder` and `GraphitronSchemaValidator`:

1. **Reflection-derived binding from root producers and parent
   accessors.** Replace the directive-driven `recordBackingClasses`
   population with reflection-driven resolution. For an SDL type T,
   binding resolution is a depth-first recursive walk with
   memoization:

   - **Root producers** ground the walk. If T has a `@service` method
     return, a `@table` resolution, or a `@tableMethod` return, the
     producer's reflected class is the binding. These are addressable
     at schema-build time independent of any other type's
     classification. For result types, this means `buildResultType`
     and the dispatch at line 386 are gated on the reflected
     producer's discoverability instead of on the `@record` directive;
     a type with a reflectable root producer classifies into the
     appropriate backed variant on the result axis (one of
     `JavaRecordType`, `JooqRecordType`, `JooqTableRecordType`,
     `PojoResultType.Backed`) without the directive. The input side
     does the same through `buildNonTableInputType` against the
     consuming `@service` parameter, producing one of
     `JavaRecordInputType`, `JooqRecordInputType`,
     `JooqTableRecordInputType`, `PojoInputType`.

   - **Parent accessor returns** carry the walk recursively. For an
     SDL type T with no root producer, the resolver finds a parent P
     with an SDL accessor field F returning T, recursively resolves
     P's binding, then reflects through P's class to find F's accessor
     method (the candidate-name walk: `getX` / `isX` / `x` /
     field-read). F's reflected return type is T's binding. Cycle
     protection: an in-progress set tracks types currently being
     resolved; if the descent re-enters T while T is still
     in-progress, T has no grounding in this branch and the resolver
     tries the next parent (or, if exhausted, returns no binding).

   - **Memoization** uses `recordBackingClasses` as a cache: the first
     resolution writes the result, subsequent calls return from cache.
     Termination is trivially finite (each call either returns from
     cache or completes a resolution; the SDL type set is finite).
     The substantive property is *completeness*: if any path through
     accessor edges from a root producer reaches T, the walk resolves
     T. The synthetic-graph unit test under Tests is the algorithmic
     witness; the corpus-level additive assertion is the integration
     witness.

   - **Reflection at the parse boundary.** The accessor-method probe
     this checkpoint introduces mirrors the disambiguation
     `ClassAccessorResolver` performs during field classification.
     R96 factors that probe into a dedicated `ClassAccessorResolver`
     entry-point that `TypeBuilder` calls during the recursive walk;
     the parse-boundary reflection roster gains no new file (the probe
     stays inside `ClassAccessorResolver`). The walk runs during
     `TypeBuilder.buildTypes()` before field classification, so it
     does not depend on `FieldBuilder.classifyField` having run.

   - **Multi-producer agreement.** An SDL type can be reached by more
     than one producer (two `@service` methods returning it, `@service`
     plus `@table`, or a root producer plus an inbound parent accessor
     that reflects to a different return type). The set of reflected
     `Class<?>` values for the SDL type must agree; disagreement is
     handled at checkpoint 2.

   Types unreached at the end of the walk are unreachable by
   construction (no root producer in their reverse-accessor cone) and
   classify as `PlainObjectType` per the existing baseline; their
   generated code is dead and not consulted at runtime, so any
   `@record` they carry is a silent no-op under R96.

2. **Multi-producer agreement check.** When the recursive walk at
   checkpoint 1 reaches the same SDL type through more than one
   producer, every reached `Class<?>` for that type must agree.
   Disagreement is a classifier-tier rejection: R96 adds a sealed
   `Rejection.RecordBindingMismatch` permit to the existing taxonomy
   with one arm, `MultiProducer(sdlType, List<ProducerBinding>
   bindings)`, where `ProducerBinding` is a sealed sub-taxonomy over
   `RootService(serviceClass, methodName)` /
   `RootTable(tableRefName)` / `RootTableMethod(holderClass,
   methodName)` / `ParentAccessor(parentTypeName, fieldName)`, each
   carrying source location and the reflected `Class<?>`. The
   diagnostic names every disagreeing site concretely. The rejection
   surfaces through `ValidationReport.errors()` so the build halts.

   The check carries the `@LoadBearingClassifierCheck` /
   `@DependsOnClassifierCheck` annotation pair under the key
   `record-binding.producer-agreement`. The producer site is the
   memoization write inside the recursive walk (in `buildResultType`
   at `TypeBuilder.java:696` and the new input-side population path
   R96 adds inside `buildNonTableInputType` at `TypeBuilder.java:887`,
   which currently has no `recordBackingClasses` population); the
   consumer site is `FieldBuilder.resolveRecordAccessor`
   (`FieldBuilder.java:3808`), which assumes the resolved `Class<?>`
   the binding produces is the class field accessors will be emitted
   against. `LoadBearingGuaranteeAuditTest` enforces the pair, so the
   spec lands the annotations together with the agreement check itself.

   Two pure-function commitments ride under the agreement check:

   - For `JooqTableRecordType`, the existing path derives the
     `TableRef` slot via `svc.resolveTableByRecordClass(cls)` at
     `TypeBuilder.java:719`. That resolution is a pure function of the
     class, so agreement on `cls` implies agreement on `TableRef`.
   - For `JavaRecordType` / `JavaRecordInputType`, the record's
     component list (the basis for accessor mapping) is a pure function
     of `cls`.

   If either resolution is ever generalised to take additional inputs,
   the corresponding commitment must be revisited as part of that
   change.

3. **Directive-ignored warning.** When `@record` is present on a
   reachable type, the validator emits a `ValidationReport.warnings()`
   entry telling the author the directive is ignored and should be
   removed. The directive's `className` is informational only; it does
   not participate in classification under R96, so the warning fires
   regardless of whether the directive agrees with reflection. Two
   message variants:

   - **Matches** (`className` equals the reflected class):
     > Type 'FilmReviewPayload' carries `@record(record: { className:
     > "com.example.FilmReviewPayloadRecord" })`. Graphitron derives
     > the same backing class from the producing field's reflected
     > return type. The directive is redundant; remove it.

   - **Disagrees** (`className` differs from the reflected class):
     > Type 'FilmReviewPayload' carries `@record(record: { className:
     > "com.example.FilmReviewPayloadRecord" })`. Graphitron derives a
     > different backing class (`com.example.OtherRecord`) from the
     > producing field's reflected return type and uses that; the
     > directive is ignored. Remove it.

   Both variants are warnings, not errors. Reflection is the sole
   source of truth; the directive's claim is informational only.

The `@table + @record` shadow rule at `TypeBuilder.java:820-826`
flips semantics under R96 while keeping its message intact. Today
`@record` overrides `@table` on the combination; under R96 the
directive does not write to `recordBackingClasses` at all, so
`@table`-driven reflection wins by default and the directive is
ignored like every other `@record` declaration. The shadow-rule
warning continues to fire as a cleanup signal; R96's directive-ignored
warning is suppressed on the shadow combination so the two messages
do not contradict each other.

The mutual-exclusion check at `TypeBuilder.java:1134-1141` (`@record`
incompatible with `@error`) continues to apply unchanged.

## Out of scope

- Retiring the directive declaration at `directives.graphqls:290`.
  R96 ignores the directive on every reachable type; once consumers
  have removed those declarations and the directive-ignored warning
  count drops to zero across the corpus, a later item retires the
  declaration itself.
- Retiring any of the eight backed model variants (the four result
  variants and the four input variants named in the preamble). These
  remain the produced classifications; R96 changes the source of the
  binding, not the destination.
- The `@service`-payload error-construction surface
  (`payloadFactoryLambda`, `ResultAssembly`, the `PayloadAccessor` arm
  of `Transport`). Separate concern; whatever drives those today drives
  them after R96.
- Retiring the `@table + @record` shadow rule at
  `TypeBuilder.java:820-826`. That rule's existence is a separate
  redundancy signal and is its own cleanup item.
- R94's input-record validation seam. R94 emits one
  graphitron-internal Java record per reachable SDL input type and
  attaches it to every classified input as a `recordShape` slot; R96
  binds the *external* backing class for accessor resolution. The two
  slots ride on orthogonal axes (graphitron-emitted validation record
  vs. author-supplied accessor target) and coexist on the same input
  type without conflict. R94 is currently in Spec, so this is a
  forward commitment on R96's side: whichever lands first, the
  input-side population path R96 adds inside `buildNonTableInputType`
  must not write to the `recordShape` slot.

## Tests

Validator-tier (`GraphitronSchemaValidator` test surface):

- Matches: `OBJECT` carrying `@record` whose `className` equals the
  reflected root-producer return type emits the directive-ignored
  warning with the "redundant; remove it" text naming the type and
  the class.
- Matches (input axis): `INPUT_OBJECT` carrying `@record` whose
  `className` equals the consuming `@service` parameter type emits
  the same warning shape.
- Matches (parent-accessor recursion): nested child `OBJECT` reached
  only through a parent accessor, carrying `@record` whose `className`
  equals the parent's accessor return type, emits the directive-
  ignored warning (exercises the recursive descent).
- Disagrees: `OBJECT` carrying `@record` whose `className` differs
  from the reflected producer emits the directive-ignored warning
  with the "graphitron derived X, directive ignored" text naming both
  classes. No error, no rejection; reflection wins, the directive's
  claim is informational.
- Negative: a reachable type without `@record` emits no warning.
- Error (multi-producer disagreement): an SDL type reached by two
  producers whose reflected classes disagree (two `@service` methods,
  `@service` plus `@table`, or root producer plus an inbound parent
  accessor that reflects to a different return type) surfaces
  `Rejection.RecordBindingMismatch.MultiProducer` on
  `ValidationReport.errors()`; the diagnostic names every disagreeing
  `ProducerBinding` site; the build halts.
- Shadow-rule precedence: an input carrying both `@table` and `@record`
  fires the existing shadow-rule warning, not the directive-ignored
  warning.

Pipeline-tier (classifier output), split across producer sources:

- `@service`-return path: an `OBJECT` reached as the return of an
  `@service` method classifies as the backed variant matching the
  reflected class, with or without `@record`. The directive does not
  participate in the classification; with a matching `@record`, the
  variant is unchanged; with a disagreeing `@record`, the variant
  matches the reflected class (not the directive).
- `@table` / `@tableMethod`-resolved path: an `OBJECT` reached through
  `@table` (or a `@tableMethod` return) classifies as
  `JooqTableRecordType` with both `cls` and `TableRef` populated.
  Directive-disagreement test: with `@record(className: SomethingElse)`
  on the same type, classification still resolves to
  `JooqTableRecordType` with the `@table`-derived `cls`; the directive
  is ignored.
- `INPUT_OBJECT` consumed by an `@service` method classifies
  symmetrically through `buildNonTableInputType`.
- Parent-accessor recursive descent: a nested child type reached only
  as a parent's accessor return type classifies as the backed variant
  matching the reflected return-type class, with or without `@record`.
  Without `@record`, this is the change R96 introduces; with a
  disagreeing `@record`, classification still matches reflection.
- A type with no producer anywhere in its reverse-accessor cone, with
  or without `@record`, continues to classify as `PlainObjectType`
  (unchanged baseline). `@record` on such an unreachable type is a
  silent no-op; the generated code is dead.
- Synthetic accessor-graph unit test: a hand-built graph exercising
  the descent on (a) a diamond (T reached through two parents that
  both resolve to the same class), (b) a deep chain (T reached only
  through a chain of length > 2 from a root producer), (c) a cycle
  with cycle-broken grounding (T → U → T where U has a root
  producer), and (d) an ungrounded cycle (T → U → T with no root
  producer in either) asserts the resolver returns the expected
  binding (a, b, c) and no binding (d). This pins the algorithmic
  completeness claim.
- Corpus-level additive assertion: across the sakila and fixture
  schemas, the set of reachable SDL types classifying as one of the
  eight backed variants is a superset of the with-directive baseline.
  Variant identity is *not* asserted: if a today's `@record` lied
  about its class, R96 may flip the specific variant (e.g.
  `JavaRecordType` → `JooqRecordType`) to match reflection, and that
  flip is the correct behaviour; only the "still backed, not
  demoted to `PlainObjectType`" property is asserted on reachable
  types.
- Drop-manifest assertion: the validator emits a log of every SDL
  type that today's directive-driven path classifies as backed but
  R96's reflection-driven path does not. Each entry names the type
  and the reason: unreachable (no producer in reverse-accessor
  cone), or the `@record` was the sole binding source. This is the
  paper trail for future schema-edit-induced reachability flips: if
  an author later adds a producer reaching one of these types, they
  can grep the manifest to find the classification flip the new
  reachability triggers.

No compile-tier or execution-tier coverage is needed: the produced
variants are unchanged on every reachable type whose directive was
truthful (the dominant case); for reachable types where the directive
lied, R96's reflection-driven classification is the correctness fix.
No reachable backed type is demoted to `PlainObjectType` (the
additive invariant).

## Risk

The substantive risk sits in checkpoint 1's recursive walk. The
load-bearing property is **completeness over reachable types**: if
any path through accessor edges from a root producer reaches SDL
type T, the walk resolves T. Termination is trivial (each call
returns from cache or completes a resolution; finite SDL type set).
The synthetic accessor-graph unit test under Tests is the
algorithmic witness; it exercises diamond, deep chain, grounded
cycle, and ungrounded cycle shapes against the resolver directly,
so a bug in the descent fails the unit test independent of corpus
contents. The corpus-level additive assertion is the integration
witness against the sakila and fixture schemas.

R96 names the reachability predicate as live code:
`TypeReachability.fromAnyProducer(sdlType)` returns true iff the
recursive walk produced a binding. Both the walk and the
additive/drop-manifest assertions consult this one predicate, so
the witness in Tests and the algorithm under test are pinned to the
same definition of "reachable". The drop manifest then names every
SDL type the predicate excludes that today's directive-driven path
classifies as backed; that log is the paper trail for future
schema-edit-induced reachability flips.

The multi-producer agreement check at checkpoint 2 is the
load-bearing classifier invariant during the transition. The
`@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair
under `record-binding.producer-agreement` makes it auditable. If
the check never fires on the existing schema corpus, every reachable
SDL type has a single coherent reflected binding and the migration
is safe; if it fires, the diagnostic names the disagreeing
`ProducerBinding` sites so the author can resolve the conflict.
The two pure-function commitments nested under checkpoint 2
(`TableRef` derivation and `JavaRecordType` component-list
derivation) are the assumptions the check carries unstated and must
be revisited if either underlying resolution is generalised.

The directive-disagreement case is not a risk surface under R96:
the directive does not participate in classification, so a lying
`@record` is silently corrected to the reflected class and the
author is told via the disagrees-variant warning. Migration safety
in that case rides entirely on the additive invariant ("no
reachable backed type is demoted to `PlainObjectType`"). Variant
identity may flip; that flip is the correctness fix.
