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
R96 makes reflection the source of the SDL → backing-class binding for
every reachable type, walking root producers and parent accessor return
types together. The directive stays as an override-with-equality-check
during the transition; once reflection and the directive agree on every
reachable type, the directive's only signal is "this is redundant",
which the validator surfaces as a warning. Retirement of the directive
declaration is then a pure author-cleanup path: drive the redundancy
warning count to zero, then the declaration at
`directives.graphqls:290` can land in a separate item.

## Scope

Three checkpoints across `TypeBuilder` and `GraphitronSchemaValidator`:

1. **Reflection-derived binding from root producers and parent
   accessors.** Extend `recordBackingClasses` population so the map is
   filled from the reflected producer class for every reachable SDL
   type. The walk has two grounding sources, one inductive step, and
   one cross-source agreement rule:

   - **Root producers** ground the walk. An SDL type with a `@service`
     method return, a `@table` resolution, or a `@tableMethod` return
     resolves directly: the producer's reflected class is the binding.
     These are addressable at schema-build time independent of any
     other type's classification. For result types, this means
     `buildResultType` and the dispatch at line 386 are now gated on
     the reflected producer's discoverability instead of on the
     `@record` directive; a type with a reflectable root producer
     classifies into the appropriate backed variant on the result axis
     (one of `JavaRecordType`, `JooqRecordType`, `JooqTableRecordType`,
     `PojoResultType.Backed`) without the directive. The input side
     does the same through `buildNonTableInputType` against the
     consuming `@service` parameter, producing one of
     `JavaRecordInputType`, `JooqRecordInputType`,
     `JooqTableRecordInputType`, `PojoInputType`.

   - **Parent accessor returns** carry the walk inductively. For an
     SDL type T with no root producer, if a parent P with an accessor
     field F returning T is already resolved, T's binding is the
     reflected return type of P's accessor method. The walk is a
     fixpoint over `recordBackingClasses`: seed with root-producer
     bindings, then iterate parent accessors. Each iteration resolves
     at least one previously-unresolved type or terminates. Termination
     is finite because the SDL type set is finite. Types unreached at
     the fixpoint are unreachable by construction (no root producer in
     their reverse-accessor cone) and classify as `PlainObjectType`
     per the existing baseline; their generated code is dead and not
     consulted at runtime, so any `@record` they carry is a silent
     no-op under R96.

   - **Multi-producer agreement.** An SDL type can be reached by more
     than one producer (two `@service` methods returning it, `@service`
     plus `@table`, or a root producer plus an inbound parent accessor
     that reflects). The set of reflected `Class<?>` values for the
     SDL type must agree; disagreement is the same classifier-tier
     rejection as the directive-vs-reflection check at checkpoint 2,
     naming the SDL type and the disagreeing producer-class pairs.

2. **Load-bearing equality check.** When `@record` is present and a
   reflected class is also discoverable for the same type (root
   producer or parent accessor), the two must resolve to the same
   class. Mismatch is a classifier-tier rejection: R96 adds a
   `Rejection.recordBindingMismatch(sdlType, directiveClass,
   reflectedClass)` variant to the existing sealed taxonomy, surfaced
   through `ValidationReport.errors()` so the build halts. The
   multi-producer agreement check at checkpoint 1 produces the same
   rejection shape with the disagreeing reflected-class pair.

   The check carries the `@LoadBearingClassifierCheck` /
   `@DependsOnClassifierCheck` annotation pair under the key
   `record-binding.directive-matches-reflection`. The producer site is
   the population path inside `buildResultType` (`TypeBuilder.java:696`)
   plus the population path R96 adds inside `buildNonTableInputType`
   (`TypeBuilder.java:887`, currently has no `recordBackingClasses`
   population); the consumer site is
   `FieldBuilder.resolveRecordAccessor` (`FieldBuilder.java:3808`),
   which assumes the resolved `Class<?>` the binding produces is the
   class field accessors will be emitted against.
   `LoadBearingGuaranteeAuditTest` enforces the pair, so the spec lands
   the annotations together with the equality check itself.

   The equality compares the resolved `Class<?>` only. Two pure-function
   commitments ride under it:

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

3. **Redundancy warning.** When `@record` is present, reflection
   resolves the same class (root producer or parent-accessor walk),
   and the two agree, the validator emits a
   `ValidationReport.warnings()` entry telling the author the directive
   restates information graphitron already derived and can be removed.
   Because reflection covers every reachable type after checkpoint 1,
   this warning fires on every reachable `@record` that survives the
   equality check. Example:

   > Type 'FilmReviewPayload' carries `@record(record: { className:
   > "com.example.FilmReviewPayloadRecord" })`. Graphitron derives the
   > same backing class from the producing field's reflected return
   > type. The directive is redundant; remove it.

The `@table + @record` shadow rule at `TypeBuilder.java:820-826`
continues to apply unchanged: when both are present on the same
declaration, the `@record` reading wins and the existing shadow-rule
warning fires. R96's redundancy warning is suppressed on the shadow
combination so the two messages do not contradict each other.

The mutual-exclusion check at `TypeBuilder.java:1134-1141` (`@record`
incompatible with `@error`) continues to apply unchanged.

## Out of scope

- Retiring the directive declaration at `directives.graphqls:290`.
  R96 makes the directive redundant on every reachable type; once
  consumers have removed those declarations and the redundancy
  warning count drops to zero across the corpus, a later item retires
  the declaration itself.
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

- Positive: `OBJECT` carrying `@record` whose `className` matches the
  reflected root-producer return type emits the redundancy warning
  naming the type and both classes.
- Positive: `INPUT_OBJECT` carrying `@record` whose `className`
  matches the consuming `@service` parameter type emits the same
  warning shape.
- Positive: nested child `OBJECT` reached only through a parent
  accessor, carrying `@record` whose `className` matches the parent's
  accessor return type, emits the redundancy warning (exercises the
  parent-accessor inductive step).
- Negative: a type without `@record` and with a discoverable producer
  (root or parent accessor) emits no warning.
- Error (directive vs reflection): `OBJECT` carrying `@record` whose
  `className` disagrees with the reflected producer surfaces a
  `Rejection.recordBindingMismatch` on `ValidationReport.errors()`;
  the build halts.
- Error (multi-producer disagreement): an SDL type reached by two
  producers whose reflected classes disagree (e.g. two `@service`
  methods returning different classes for the same SDL type) surfaces
  the same rejection variant with the disagreeing class pair; the
  build halts.
- Shadow-rule precedence: an input carrying both `@table` and `@record`
  fires the existing shadow-rule warning, not the redundancy warning.

Pipeline-tier (classifier output), split across producer sources:

- `@service`-return path: an `OBJECT` reached as the return of an
  `@service` method classifies as the backed variant matching the
  reflected class, with or without `@record`. Without `@record`, this
  is the change R96 introduces; with `@record`, the classification
  must match the with-directive baseline.
- `@table` / `@tableMethod`-resolved path: an `OBJECT` reached through
  `@table` (or a `@tableMethod` return) classifies as
  `JooqTableRecordType` with both `cls` and `TableRef` populated,
  without `@record`. With `@record`, classification matches the
  with-directive baseline; the `TableRef` slot is identical, exercising
  the pure-function commitment at checkpoint 2.
- `INPUT_OBJECT` consumed by an `@service` method classifies
  symmetrically through `buildNonTableInputType`.
- Parent-accessor inductive step: a nested child type reached only as
  a parent's accessor return type classifies as the backed variant
  matching the reflected return-type class, with or without `@record`.
  Without `@record`, this is the change R96 introduces; with
  `@record`, the classification must match the with-directive
  baseline. This replaces the pre-R96 PlainObjectType baseline for
  nested-child-only types carrying `@record`.
- A type with no producer anywhere in its reverse-accessor cone, with
  or without `@record`, continues to classify as `PlainObjectType`
  (unchanged baseline). `@record` on such an unreachable type is a
  silent no-op; the generated code is dead.
- Corpus-level additive assertion: across the sakila and fixture
  schemas, the multiset of `(reachable SDL type → backed variant)`
  bindings is a superset of the with-directive baseline. The
  invariant scope is reachable types only; unreachable types whose
  backed classification depends solely on the directive (no producer
  anywhere in the reverse-accessor cone) lose their backed
  classification under R96 by design, but their generated code is
  dead, so no runtime path is affected.

No compile-tier or execution-tier coverage is needed: the produced
variants are unchanged on every reachable type, so generated code
shape and runtime behaviour are unchanged where any code path can run.

## Risk

The substantive risk sits in checkpoint 1's fixpoint walk. The walk
is correct iff every reachable SDL type ends up with exactly one
resolved class, which decomposes into three sub-claims: (a) the walk
terminates (finite SDL type set, each iteration resolves at least one
previously-unresolved type or halts); (b) the walk reaches every type
with a producer somewhere in its reverse-accessor cone (no
premature termination, no missed parent edge); (c) the
multi-producer agreement rule pins disagreement as an error rather
than letting one producer silently win. The corpus-level additive
assertion in Tests is the witness for (b): if the with-directive
baseline carries a reachable backed type the new walk misses, the
assertion fails. Disagreement under (c) is auditable through the
`Rejection.recordBindingMismatch` variant.

The equality check at checkpoint 2 is the load-bearing classifier
invariant. The `@LoadBearingClassifierCheck` /
`@DependsOnClassifierCheck` pair under
`record-binding.directive-matches-reflection` makes it auditable; if
the check never fires on the existing schema corpus, the directive has
been carrying truth and the migration is safe; if it fires, the
surfaced mismatch is the cleanup item authors must address before the
directive can be retired. The two pure-function commitments nested
under checkpoint 2 (`TableRef` derivation and `JavaRecordType`
component-list derivation) are the assumptions the check carries
unstated and must be revisited if either underlying resolution is
generalised.
