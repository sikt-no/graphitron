---
id: R204
title: Validate uniform env.getSource() domain return type across producers on a payload SDL type
status: Spec
bucket: cleanup
priority: 10
theme: mutations-errors
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Validate uniform env.getSource() domain return type across producers on a payload SDL type

## Problem

Every field whose return type is a non-primitive SDL Object `P` is a *producer* of `P`: its emitted resolver puts a Java value at `env.getSource()` for `P`'s child datafetchers. The generator commits to one Java type per `(P, child-field-coord)` pair when it emits the child datafetcher; we deliberately do not generate dispatch fetchers that branch on runtime source type. So a schema in which two producers of `P` hand different Java types at `env.getSource()` is malformed: whichever producer the runtime invokes second feeds a datafetcher generated for the other's record shape, returning silently wrong data or NPEing on a column the fetcher expects.

R178 retired the carrier-walk's compare-then-write `carrier-data-field.single-producer-kind` invariant, which was the prior structural surface for this constraint. The unified path observes producer bindings on independent `putIfAbsent` memos in `RecordBindingResolver` (`dmlEmittedMemo`, `serviceEmittedMemo`) and never compares them; the only producer combination that currently exercises the disagreement is DML `@mutation` (sparse-Record source) + `@service`-on-`Mutation` (typed `TableRecord` source), but the constraint is general across any current or future producer permit. Two pipeline-tier pins in `SingleRecordTableFieldServiceProducerPipelineTest` are `@Disabled` against the gap.

## Design

The check is post-classification and structural; it does not live on `RecordBindingResolver`'s grounding methods or on per-field `FieldBuilder` arms. It runs once over the classified field set as a validator pass:

1. Every `GraphitronField` permit that can be a producer exposes a uniform `domainReturnType()` accessor (or equivalent canonical handle) returning the Java type its emitted resolver hands at `env.getSource()` to children of the field's SDL return type. Coverage must be complete across the sealed hierarchy — no fall-through case where a permit can't answer the question.
2. The validator walks the classified field registry, indexes producers by their SDL return-type name, and groups them. Each group with a single distinct `domainReturnType()` is fine.
3. A group with more than one distinct type is the conflict. Every producer field in the group is demoted to `UnclassifiedField` with an author-error `Rejection` naming the payload SDL type, the producer field coords, and each producer's domain return type. Demoting all involved fields (rather than picking a "winner") routes every conflict participant through the standard author-error surface so the LSP and the validate goal can point the author at every offending coord.

The data field on the conflict payload (`FilmListPayload.films` in the test) is left as it stands: its source-key is unreliable because no consistent producer exists, but it is not itself "in conflict" in the author-actionable sense — the fix is on the producer side, and the validate goal must surface the producers as the actionable coords. (If we later decide the data field also warrants demotion to suppress downstream noise from a dependent permit pointing at an UnclassifiedField producer, that's an additive change, not a structural one.)

## Implementation

- Add `domainReturnType()` (return type and canonical shape TBD during In Progress — likely `Class<?>` or a sealed handle that captures both the record class and the wrap arm) to every producer-capable `GraphitronField` permit. Each permit knows the answer from the data it already carries: DML mutation permits from their `(TableRef, DmlKind)` and cardinality; `MutationServiceRecordField` from the `@service` method's reflected return-element class; future producer permits inherit by implementing the accessor.
- New validator pass run after `TypeBuilder.buildTypes()` and the per-type field classification loop completes. The pass reads the classified field registry, groups producers by SDL return-type name, and rewrites the registry entry for every producer in a conflict group to an `UnclassifiedField` with a `Rejection.AuthorError` variant naming all conflict participants and their domain return types.
- Retire the in-place `mixedProducerConflicts` framing previously sketched on `RecordBindingResolver`; the resolver's job stays "observe per-producer bindings," not "compare across them."
- The R178 step 2 in-source comment block in `SingleRecordTableFieldServiceProducerPipelineTest` (lines 178-186) and the now-stale `@org.junit.jupiter.api.Disabled(...)` annotations come out together.

## Tests

- Re-enable both `@Disabled` cases. Keep them as separate tests; the order-independence pin is the point of having two. Update each test's assertions to:
  - Both producer mutations are `UnclassifiedField` (not just the "second").
  - Each producer's rejection message names the payload SDL type, both producer field coords, and both domain return types (Java class names; replace the previous `Wrap.Record` / `Wrap.TableRecord` permit-internal token assertions with the corresponding domain Java type names).
- Add unit-tier coverage for `domainReturnType()` on every producer-capable permit (or fold into existing permit unit tests where present), so the validator's structural premise is pinned independent of the conflict scenario.
- Verify pipeline tier passes on both ordering directions and that the conflict surface is symmetric.

## Roadmap entries

- This file deletes on Done; `changelog.md` gets a one-line R204 entry citing the landing commit.
