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
backing class to drive accessor resolution and the `JavaRecordType`,
`JooqRecordType`, `JooqTableRecordType`, `PojoResultType.Backed`, and
`PojoInputType` variants. Today the binding from SDL type to backing
class is sourced entirely from the `@record(record:
ExternalCodeReference)` directive's `className` argument: `TypeBuilder`
reads the directive at line 386 to dispatch into `buildResultType`
(line 696), populates `recordBackingClasses` from `dir.getArgument(
ARG_RECORD)` at lines 715 and 725, and the map is consumed by
`FieldBuilder.resolveRecordAccessor` (line 3808). Without `@record`,
result types fall through to `PlainObjectType` at line 391 and
field-level accessor resolution is skipped.

That class is already discoverable from the producer side: an `@service`
method's return type, a `@table`-resolved jOOQ record, or a parent
field's accessor return type all name the same class the directive
restates. R96 makes reflection the source of the SDL → backing-class
binding, keeps the directive as an override-with-equality-check during
the transition, and once the two agree the directive's only signal is
"this is redundant", which the validator surfaces as a warning.

## Scope

Three checkpoints in `TypeBuilder` and one in `GraphitronSchemaValidator`:

1. **Reflection-derived binding.** Extend `recordBackingClasses`
   population so the map is filled from the reflected producer class
   whenever a producer is identifiable for an SDL type (`@service` /
   `@table` root, parent-accessor return), independent of `@record`.
   For result types, this means `buildResultType` and the dispatch at
   line 386 stop being directive-gated for the classification decision:
   a type with a reflectable producer classifies into the appropriate
   backed variant (`JavaRecordType` / `JooqRecordType` /
   `JooqTableRecordType` / `PojoResultType.Backed`) without the
   directive. Types with no reflectable producer still fall through to
   `PlainObjectType`. The input side does the same through
   `buildNonTableInputType` against the consuming `@service` parameter.

2. **Load-bearing equality check.** When `@record` is present and a
   reflected class is also discoverable for the same type, the two must
   resolve to the same class. Mismatch is a classifier-tier error with
   a message naming the SDL type, the directive's `className`, and the
   reflected class, surfaced through `ValidationReport.errors()` so the
   build halts. This is the `@LoadBearingClassifierCheck` discipline:
   it expresses an invariant the classifier guarantees for downstream
   consumers, and any violation is a contradiction the author must
   resolve.

3. **Redundancy warning.** When `@record` is present, a reflected class
   is discoverable, and the two agree, the validator emits a
   `ValidationReport.warnings()` entry telling the author the directive
   restates information graphitron already derived and can be removed.
   Example:

   > Type 'FilmReviewPayload' carries `@record(record: { className:
   > "com.example.FilmReviewPayloadRecord" })`. Graphitron derives the
   > same backing class from the producing field's reflected return
   > type. The directive is redundant; remove it.

4. **Directive-only fallback.** When `@record` is present and no
   reflected class is discoverable (e.g. an isolated payload type with
   no `@service` producer reachable at schema-build time), the
   directive remains the source of the binding. No warning fires; the
   directive is doing real work. This branch exists to keep R96
   additive: nothing that classifies as backed today loses its
   classification.

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
- Retiring the model variants `JavaRecordType`, `JooqRecordType`,
  `JooqTableRecordType`, `PojoResultType.Backed`, `PojoInputType`.
  These remain the produced classifications; R96 changes the source of
  the binding, not the destination.
- The `@service`-payload error-construction surface
  (`payloadFactoryLambda`, `ResultAssembly`, the `PayloadAccessor` arm
  of `Transport`). Separate concern; whatever drives those today drives
  them after R96.
- Retiring the `@table + @record` shadow rule at
  `TypeBuilder.java:820-826`. That rule's existence is a separate
  redundancy signal and is its own cleanup item.
- Anything R94 owns on the input-side classifier rework.

## Tests

Validator-tier (`GraphitronSchemaValidator` test surface):

- Positive: `OBJECT` carrying `@record` whose `className` matches the
  reflected producer return type emits the redundancy warning naming
  the type and both classes.
- Positive: `INPUT_OBJECT` carrying `@record` whose `className` matches
  the consuming `@service` parameter type emits the same warning
  shape.
- Negative: a type with `@record` and no discoverable producer emits no
  warning (directive-only fallback). A type without `@record` and with
  a discoverable producer emits no warning.
- Error: `OBJECT` carrying `@record` whose `className` disagrees with
  the reflected producer surfaces a classifier error on
  `ValidationReport.errors()`; the build halts.
- Shadow-rule precedence: an input carrying both `@table` and `@record`
  fires the existing shadow-rule warning, not the redundancy warning.

Pipeline-tier (classifier output):

- An `OBJECT` reached as the return of an `@service` method classifies
  as the backed variant matching the reflected class, with or without
  `@record`. Without `@record`, this is the change R96 introduces;
  with `@record`, the classification must match the with-directive
  baseline.
- An `INPUT_OBJECT` consumed by an `@service` method classifies
  symmetrically through `buildNonTableInputType`.
- A type with no discoverable producer and no `@record` continues to
  classify as `PlainObjectType` (unchanged baseline).

No compile-tier or execution-tier coverage is needed: the produced
variants are unchanged, so generated code shape and runtime behaviour
are unchanged.

## Risk

The substantive risk sits in checkpoint 1: making reflection the source
on the input side and the result side without `@record` requires that
producer discovery (`@service` / `@table` / parent-accessor) is
total enough at schema-build time to cover every type that today
classifies as backed via the directive. If a type that today is backed
loses its classification because its producer is not discoverable in
the new path, downstream emitters that assume a backed variant will
fail at pipeline tier. The directive-only fallback at checkpoint 4
exists to bound this: any type the new path fails to reach but the
directive does reach continues to classify exactly as today, and the
absence of the redundancy warning is the signal that producer
discovery did not cover it.

The equality check at checkpoint 2 is the load-bearing classifier
invariant. If it never fires on the existing schema corpus, the
directive has been carrying truth and the migration is safe; if it
fires, the surfaced mismatch is the cleanup item authors must address
before the directive can be retired.
