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
reads the directive at line 392 to dispatch into `buildResultType`
(line 702), populates `recordBackingClasses` from `dir.getArgument(
ARG_RECORD)` at lines 721 and 731, and the map is consumed by
`FieldBuilder.resolveRecordAccessor` (line 3808). Without `@record`,
result types fall through to `PlainObjectType` at line 397 and
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
     and its dispatch gate (`hasAppliedDirective(DIR_RECORD)` at
     `TypeBuilder.java:392`, dispatching at line 393) are recast to
     trigger on reflected-producer discoverability instead of on the
     `@record` directive; a type with a reflectable root producer
     classifies into the appropriate backed variant on the result axis
     (one of `JavaRecordType`, `JooqRecordType`, `JooqTableRecordType`,
     `PojoResultType.Backed`) without the directive. The input side
     does the same through `buildNonTableInputType` against the
     consuming `@service` parameter, producing one of
     `JavaRecordInputType`, `JooqRecordInputType`,
     `JooqTableRecordInputType`, `PojoInputType`.

   - **Parent accessor returns** extend the walk. For every SDL
     parent P with an SDL accessor field F whose declared return type
     is T, the resolver recursively resolves P's binding, then
     reflects through P's class to find F's accessor method (the
     candidate-name walk: `getX` / `isX` / `x` / field-read). F's
     reflected return type contributes a binding for T. The walk
     enumerates every such parent in-edge; it does not stop at the
     first parent that grounds T, because multi-producer disagreement
     detection at checkpoint 2 requires every in-edge that can reach
     T to be observed.

     Cycle protection: an in-progress set scoped per descent chain
     blocks the chain from re-entering a type already being resolved
     on the same chain. The in-progress set bounds termination only;
     it does not stop sibling enumeration. A descent chain that
     re-enters T contributes nothing through that path; sibling
     in-edges and root producers still contribute their bindings to
     T's collection set. The set must be per-chain rather than global:
     a global "currently resolving" set would let the order of
     in-edge enumeration determine which siblings contribute, so a
     diamond `P1 → T` / `P2 → T` could observe only one in-edge if
     the visit order placed the second arrival inside the first's
     resolution. The synthetic diamond test under Tests is the
     witness for this property; under a global set it would surface
     only one of the two parent bindings and fail the per-type
     collection-set claim.

   - **Per-type collection and post-fold memoization.** The walk
     accumulates every observed binding for T into a per-type
     collection set keyed by SDL type (a multimap during the walk,
     not a first-write-wins cache). Root producers and parent-accessor
     in-edges both write into this set. After T's walk completes, the
     set is folded: empty resolves to no binding (T classifies as
     `PlainObjectType`); singleton writes the agreed `Class<?>` to
     `recordBackingClasses` and classifies T into the appropriate
     backed variant; two or more distinct classes surface
     `Rejection.AuthorError.RecordBindingMismatch.MultiProducer` per
     checkpoint 2 with every disagreeing site listed.
     `recordBackingClasses` is the *post-agreement* slot: subsequent
     callers asking for T's binding read directly from it without
     re-walking. Termination is trivially finite (the per-descent-chain
     in-progress set blocks re-entry; the chain is finite; the SDL
     type set is finite). The substantive property is *completeness*:
     every reachable in-edge contributes to T's collection set, so a
     disagreement on any in-edge is observable. The synthetic
     accessor-graph unit test under Tests pins the algorithmic claim;
     the corpus-level additive assertion is the integration witness.

   - **Reflection at the parse boundary.** The accessor-method probe
     this checkpoint introduces mirrors the disambiguation
     `ClassAccessorResolver` performs during field classification.
     R96 factors that probe into a dedicated `ClassAccessorResolver`
     entry-point that `TypeBuilder` calls during the recursive walk;
     the parse-boundary reflection roster gains no new file (the probe
     stays inside `ClassAccessorResolver`). The walk runs during
     `TypeBuilder.buildTypes()` before field classification, so it
     does not depend on `FieldBuilder.classifyField` having run.

   - **Multi-producer reach is expected and observed.** An SDL type
     can legitimately be reached by more than one producer (two
     `@service` methods returning it, `@service` plus `@table`, or a
     root producer plus an inbound parent accessor). The per-type
     collection set above accumulates every observed `Class<?>`;
     agreement is folded at the end of T's walk and disagreement is
     handled at checkpoint 2.

   Types unreached at the end of the walk are unreachable by
   construction (no root producer in their reverse-accessor cone) and
   classify as `PlainObjectType` per the existing baseline; their
   generated code is dead and not consulted at runtime, so any
   `@record` they carry is a silent no-op under R96.

2. **Multi-producer agreement check.** When the recursive walk at
   checkpoint 1 reaches the same SDL type through more than one
   producer, every reached `Class<?>` for that type must agree.
   Disagreement is a classifier-tier rejection: R96 adds a
   `RecordBindingMismatch` permit to the existing sealed
   `Rejection.AuthorError` taxonomy with one arm,
   `MultiProducer(sdlType, List<ProducerBinding> bindings)`, where
   `ProducerBinding` is a sealed sub-taxonomy over
   `RootService(serviceClass, methodName)` /
   `RootTable(tableRefName)` / `RootTableMethod(holderClass,
   methodName)` / `ParentAccessor(parentTypeName, fieldName)`, each
   carrying source location and the reflected `Class<?>`. The
   diagnostic names every disagreeing site concretely. The rejection
   surfaces through `ValidationReport.errors()` so the build halts.
   Placement under `AuthorError` (rather than a new top-level
   `Rejection` permit) reflects that the diagnostic is
   author-correctable through the same rename / retype / split
   toolbox as the existing `AuthorError` arms; the typed
   `List<ProducerBinding>` payload follows the existing pattern of
   structured sub-data on `AuthorError`.

   The check carries the `@LoadBearingClassifierCheck` /
   `@DependsOnClassifierCheck` annotation pair under the key
   `record-binding.producer-agreement`. The producer site is the
   post-fold write to `recordBackingClasses` at the end of T's walk
   (the write happens once per SDL type, after the per-type
   collection set has been folded to a single agreed `Class<?>`; the
   write occurs in `buildResultType` at `TypeBuilder.java:702` for
   result types and in the new input-side population path R96 adds
   inside `buildNonTableInputType` at `TypeBuilder.java:898`, which
   currently has no `recordBackingClasses` population); the consumer
   site is `FieldBuilder.resolveRecordAccessor`
   (`FieldBuilder.java:3808`), which assumes the resolved `Class<?>`
   the binding produces is the class field accessors will be emitted
   against. `LoadBearingGuaranteeAuditTest` enforces the pair, so the
   spec lands the annotations together with the agreement check
   itself. The annotation's `description` argument enumerates the two
   pure-function commitments below inline (`TableRef` derivation,
   `JavaRecord` component list) so a future relaxation of either
   surfaces in find-usages on the annotation key.

   Two pure-function commitments ride under the agreement check:

   - For `JooqTableRecordType` (and `JooqTableRecordInputType` on the
     input axis), the existing path derives the `TableRef` slot via
     `svc.resolveTableByRecordClass(cls)` at `TypeBuilder.java:725`
     (result-axis) and `TypeBuilder.java:925` (input-axis); the same
     resolver call rides both axes. That resolution is a pure function
     of the class, so agreement on `cls` implies agreement on `TableRef`
     on either axis.
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
   regardless of whether the directive agrees with reflection. Three
   message variants, selected by context at the single emission site:

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

   - **Shadowed by `@table`** (the type also carries `@table`, so the
     binding comes from `@table`-driven reflection rather than the
     parent-accessor or root-producer chain that the prior two
     variants describe). This variant fires only on `INPUT_OBJECT`
     types: on `OBJECT` types, `detectTypeDirectiveConflict`
     (`TypeBuilder.java:1260-1275`, called from line 382 on the OBJECT
     branch only) already rejects the `@table` + `@record` combination
     as `Rejection.InvalidSchema.DirectiveConflict` before
     classification reaches the `@record` arm, so the variant is
     unreachable on result types. R96 does not relax that check; an
     OBJECT-side author with both directives is still asked to remove
     one before the build proceeds (and dropping `@record` is the
     correct resolution under R96):
     > Input type 'FilmFilterInput' carries both `@table` and
     > `@record(record: { className: "com.example.X" })`. Graphitron
     > derives the backing class from `@table`; the `@record` directive
     > is ignored. Remove it.

   The three variants carry different data (the `Matches` variant
   has no extra class; the `Disagrees` variant carries the reflected
   class graphitron actually used; the `Shadowed by @table` variant
   carries no extra class because the message is keyed on the
   `@table` co-occurrence, not on a specific reflected class). R96
   emits all three through the existing `BuildWarning` prose surface
   and interpolates the reflected class name into the disagrees
   message rather than carrying a typed slot. Lifting `BuildWarning`
   into a sealed sub-taxonomy on the strength of R96 alone is
   premature; a later refactor can do so once a second case forces
   the abstraction.

   All three variants are warnings, not errors. Reflection is the
   sole source of truth; the directive's claim is informational only.

The `@table` + `@record` shadow rule on `INPUT_OBJECT` types at
`TypeBuilder.java:818-832` (warning emission at lines 826-831, inside
`buildInputType`) flips semantics under R96 while folding its emission
into R96's single directive-ignored warning. Today on input types
`@record` overrides `@table` on the combination (the legacy comment at
lines 821-825 spells out the routing: warn, then route through
`buildNonTableInputType`), and that emission surfaces the redundancy
as its own warning. Under R96 the directive does not write to
`recordBackingClasses` at all, so `@table`-driven reflection wins by
default and the directive is ignored like every other `@record`
declaration; the legacy emission at `TypeBuilder.java:826-831` is
removed as part of R96, and the redundancy signal is carried by the
`Shadowed by @table` variant of the directive-ignored warning above.
One emission point, three message variants, context-derived
selection: the shadow case is no longer a separate warning that has
to be suppressed against R96's emission, it is the same emission
with a different message because the directive's effect is
shadowed by `@table` rather than by the reflection chain. The
INPUT_OBJECT restriction noted on the variant above carries through:
the only path where `@table` + `@record` survives long enough to
classify is the INPUT_OBJECT path, so `Shadowed by @table` fires
exclusively there.

**Variant precedence at the single emission site.** When more than
one variant predicate matches the same type, `Shadowed by @table`
takes precedence over `Matches` and `Disagrees`: the `@table`
co-occurrence is the proximate cause of the directive being ignored
and supersedes any in-edge-derived comparison. Between `Matches` and
`Disagrees`, the comparison is exact `Class<?>` equality against the
single agreed reflected class (post-fold from checkpoint 1). A type
that reaches a `MultiProducer` rejection at checkpoint 2 does not
emit a directive-ignored warning at all; the error supersedes the
warning at the same site.

The mutual-exclusion check `detectTypeDirectiveConflict` at
`TypeBuilder.java:1260-1275` (the pairwise rejection of `@table` /
`@record` / `@error` on `OBJECT` types, called from line 382)
continues to apply unchanged.

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
  `Rejection.AuthorError.RecordBindingMismatch.MultiProducer` on
  `ValidationReport.errors()`; the diagnostic names every disagreeing
  `ProducerBinding` site; the build halts.
- Shadowed-by-table: an `INPUT_OBJECT` carrying both `@table` and
  `@record` emits the directive-ignored warning's `Shadowed by
  @table` variant (one emission, message keyed on the `@table`
  co-occurrence). The legacy emission at
  `TypeBuilder.java:826-831` is removed; no separate shadow-rule
  warning fires. Companion negative: an `OBJECT` carrying both
  directives still produces the existing `InvalidSchema.DirectiveConflict`
  rejection from `detectTypeDirectiveConflict`; no directive-ignored
  warning is emitted in that case (error supersedes warning).

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
- Drop-manifest assertion (pipeline-tier, golden-file): the test
  computes the set of SDL types that today's directive-driven
  baseline classifies as backed but R96's reflection-driven path
  does not, across the sakila and fixture schemas. The diff is
  asserted against a checked-in golden file. Each entry names the
  type and the reason: unreachable (no producer in reverse-accessor
  cone), or the `@record` was the sole binding source. The golden
  file is the paper trail for future schema-edit-induced
  reachability flips: if an author later adds a producer reaching
  one of these types, the test's diff diverges from the golden,
  surfacing the classification flip. No validator runtime artefact
  is involved; this is a build-time invariant captured in the test
  corpus.

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
type T, the walk resolves T. Termination is trivial (the
per-descent-chain in-progress set blocks re-entry; the chain is
finite; the SDL type set is finite).
The synthetic accessor-graph unit test under Tests is the
algorithmic witness; it exercises diamond, deep chain, grounded
cycle, and ungrounded cycle shapes against the resolver directly,
so a bug in the descent fails the unit test independent of corpus
contents. The corpus-level additive assertion is the integration
witness against the sakila and fixture schemas.

R96 introduces the reachability predicate as live code: checkpoint
1 adds `TypeReachability.fromAnyProducer(sdlType)` (returns true iff
the recursive walk produced a binding) as a package-private
predicate exposed by the recursive walk's host. Both the walk and
the additive / drop-manifest assertions consult this one predicate,
so the witness in Tests and the algorithm under test are pinned to
the same definition of "reachable". The drop manifest's golden-file
diff then captures every SDL type the predicate excludes that
today's directive-driven path classifies as backed; that golden is
the paper trail for future schema-edit-induced reachability flips.

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
derivation) are the assumptions the check carries; they sit inline
in the `@LoadBearingClassifierCheck` annotation's `description`
argument so a future relaxation of either resolution surfaces in
find-usages on the annotation key.

The directive-disagreement case is not a risk surface under R96:
the directive does not participate in classification, so a lying
`@record` is silently corrected to the reflected class and the
author is told via the disagrees-variant warning. Migration safety
in that case rides entirely on the additive invariant ("no
reachable backed type is demoted to `PlainObjectType`"). Variant
identity may flip; that flip is the correctness fix.
