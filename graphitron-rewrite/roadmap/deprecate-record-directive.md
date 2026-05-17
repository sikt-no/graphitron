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
types reachable from a root producer, keeps the directive as an
override-with-equality-check during the transition, and once the two
agree the directive's only signal is "this is redundant", which the
validator surfaces as a warning. Types reachable only as nested
children classify via a directive-only fallback that R96 instruments
with a tree-wide info signal so the directive declaration's eventual
retirement has a measured surface to chase.

## Scope

Four checkpoints across `TypeBuilder` and `GraphitronSchemaValidator`:

1. **Reflection-derived binding from root producers.** Extend
   `recordBackingClasses` population so the map is filled from the
   reflected producer class whenever a root producer is identifiable
   for an SDL type. Root producers are `@service` method returns,
   `@table` resolution, and `@tableMethod` returns: these are
   addressable at schema-build time independent of any other type's
   classification. For result types, this means `buildResultType` and
   the dispatch at line 386 stop being directive-gated for the
   classification decision; a type with a reflectable root producer
   classifies into the appropriate backed variant on the result axis
   (one of `JavaRecordType`, `JooqRecordType`, `JooqTableRecordType`,
   `PojoResultType.Backed`) without the directive. The input side does
   the same through `buildNonTableInputType` against the consuming
   `@service` parameter, producing one of `JavaRecordInputType`,
   `JooqRecordInputType`, `JooqTableRecordInputType`, `PojoInputType`.

   Parent-accessor return types are *not* a reflection source in R96.
   Resolving a child type's binding from the parent's accessor return
   would depend on the parent's own binding already being resolved, an
   ordering hazard with no termination argument in the current writer
   path. Types reachable only as nested children classify via the
   directive-only fallback at checkpoint 4. A separate item can replace
   that fallback with an explicit fixpoint once a termination argument
   is in hand.

2. **Load-bearing equality check.** When `@record` is present and a
   reflected class is also discoverable for the same type from a root
   producer, the two must resolve to the same class. Mismatch is a
   classifier-tier error with a message naming the SDL type, the
   directive's `className`, and the reflected class, surfaced through
   `ValidationReport.errors()` so the build halts.

   The check carries the `@LoadBearingClassifierCheck` /
   `@DependsOnClassifierCheck` annotation pair under the key
   `record-binding.directive-matches-reflection`. The producer site is
   the population path inside `buildResultType` (`TypeBuilder.java:696`)
   and `buildNonTableInputType` (`TypeBuilder.java:887`); the consumer
   site is `FieldBuilder.resolveRecordAccessor`
   (`FieldBuilder.java:3808`), which assumes the resolved `Class<?>`
   the binding produces is the class field accessors will be emitted
   against. `LoadBearingGuaranteeAuditTest` enforces the pair, so the
   spec lands the annotations together with the equality check itself.

   The equality compares the resolved `Class<?>` only. For
   `JooqTableRecordType`, the existing path derives the `TableRef` slot
   via `svc.resolveTableByRecordClass(cls)` at
   `TypeBuilder.java:719`; that resolution is a pure function of the
   class, so agreement on `cls` implies agreement on `TableRef` and no
   separate equality on the `TableRef` slot is required. If
   `resolveTableByRecordClass` is ever generalised to take additional
   inputs, this commitment must be revisited as part of that change.

3. **Redundancy warning.** When `@record` is present, a reflected class
   is discoverable from a root producer, and the two agree, the
   validator emits a `ValidationReport.warnings()` entry telling the
   author the directive restates information graphitron already
   derived and can be removed. Example:

   > Type 'FilmReviewPayload' carries `@record(record: { className:
   > "com.example.FilmReviewPayloadRecord" })`. Graphitron derives the
   > same backing class from the producing field's reflected return
   > type. The directive is redundant; remove it.

4. **Directive-only fallback with a tree-wide info signal.** When
   `@record` is present and no reflected class is discoverable from a
   root producer (e.g. an isolated payload type with no `@service`
   producer reachable at schema-build time, or a nested child reached
   only through a parent accessor), the directive remains the source
   of the binding. No warning fires; the directive is doing real work.
   This branch exists to keep R96 additive: nothing that classifies as
   backed today loses its classification.

   The validator emits a `ValidationReport.info()` entry at each
   directive-only-fallback site so the retirement of the directive
   declaration (a downstream item) has a tree-wide signal to chase.
   The retirement item lands when the info-entry set is empty across
   the test schema corpus; until then, the fallback is a measured
   surface, not an unbounded escape hatch.

The `@table + @record` shadow rule at `TypeBuilder.java:820-826`
continues to apply unchanged: when both are present on the same
declaration, the `@record` reading wins and the existing shadow-rule
warning fires. R96's redundancy warning is suppressed on the shadow
combination so the two messages do not contradict each other.

The mutual-exclusion check at `TypeBuilder.java:1134-1141` (`@record`
incompatible with `@error`) continues to apply unchanged.

## Out of scope

- Retiring the directive declaration at `directives.graphqls:290`.
  R96 makes the directive redundant on every type with a discoverable
  producer; once consumers have removed those declarations and the
  directive-only fallback ceases to fire in tree-wide tests, a later
  item retires the declaration itself.
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
  type without conflict.

## Tests

Validator-tier (`GraphitronSchemaValidator` test surface):

- Positive: `OBJECT` carrying `@record` whose `className` matches the
  reflected root-producer return type emits the redundancy warning
  naming the type and both classes.
- Positive: `INPUT_OBJECT` carrying `@record` whose `className`
  matches the consuming `@service` parameter type emits the same
  warning shape.
- Negative: a type without `@record` and with a discoverable root
  producer emits no warning.
- Fallback signal: a type with `@record` and no discoverable root
  producer (isolated payload or nested-child-only reachable) emits a
  `ValidationReport.info()` entry naming the type and the directive,
  and no warning. The info entry is the forcing function the
  retirement item chases.
- Error: `OBJECT` carrying `@record` whose `className` disagrees with
  the reflected root producer surfaces a classifier error on
  `ValidationReport.errors()`; the build halts.
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
- Parent-accessor fallback: a nested child type reached only as a
  parent's accessor return type, carrying `@record`, classifies via
  the directive-only fallback (binding sourced from the directive,
  info entry recorded at the validator). Without `@record`, the same
  type classifies as `PlainObjectType` (unchanged baseline; R96 does
  not promote child types reachable only through parent accessors).
- A type with no root producer and no `@record` continues to classify
  as `PlainObjectType` (unchanged baseline).

No compile-tier or execution-tier coverage is needed: the produced
variants are unchanged, so generated code shape and runtime behaviour
are unchanged.

## Risk

The substantive risk sits in checkpoint 1: making reflection the source
on the result and input sides without `@record` requires that
root-producer discovery (`@service` / `@table` / `@tableMethod`) is
total enough at schema-build time to cover every type that today
classifies as backed via the directive *and* is reachable from a root
producer. The directive-only fallback at checkpoint 4 bounds this on
both ends: any type the new path fails to reach but the directive does
reach continues to classify exactly as today, and the
`ValidationReport.info()` entry at each fallback site is the tree-wide
signal that producer discovery did not cover it. The retirement of
the directive declaration depends on driving that info-entry set to
empty across the corpus; until it is empty, the fallback remains a
measured surface.

The equality check at checkpoint 2 is the load-bearing classifier
invariant. The `@LoadBearingClassifierCheck` /
`@DependsOnClassifierCheck` pair under
`record-binding.directive-matches-reflection` makes it auditable; if
the check never fires on the existing schema corpus, the directive has
been carrying truth and the migration is safe; if it fires, the
surfaced mismatch is the cleanup item authors must address before the
directive can be retired. The `TableRef` pure-function commitment
nested under checkpoint 2 is the one assumption the check carries
unstated and must be revisited if `svc.resolveTableByRecordClass` is
ever generalised.
