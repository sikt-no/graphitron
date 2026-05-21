---
id: R204
title: "Re-pin mixed DML/@service producer conflict diagnostic on the unified path"
status: Backlog
bucket: cleanup
priority: 10
theme: mutations-errors
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Re-pin mixed DML/@service producer conflict diagnostic on the unified path

R178 collapsed the parallel single-record carrier walk to the unified `SourceKey` + R96 reflection path and retired the carrier-walk's compare-then-write `carrier-data-field.single-producer-kind` invariant. The unified path observes the two payload-producer arms on independent memos in `RecordBindingResolver` (`dmlEmittedMemo`, `serviceEmittedMemo`); both grounding methods use `putIfAbsent` and never compare across memos. When a single payload SDL type is the return of both a DML `@mutation` and an `@service` mutation, neither side surfaces the conflict: the data-field arm in `FieldBuilder.classifyField` dispatches DML first and silently skips the `@service` branch, so the second producer's mutation field is classified through whichever fall-through arm it lands in rather than rejected with a producer-conflict diagnostic.

The two pinned pipeline-tier scenarios (`serviceProducer_mixedWithDml_dmlFirst_rejects`, `serviceProducer_mixedWithDml_serviceFirst_rejects` in `SingleRecordTableFieldServiceProducerPipelineTest`) are `@Disabled` with the message "R178 step 2: retire @service-carrier path and re-pin mixed-producer conflict diagnostic" against this gap. The R178 in-source comment captures the same observation. Both pins assert order-independence: whichever producer the schema declares first wins the data-field classification (and dictates the `SourceKey.Wrap` arm), the second is `UnclassifiedField` with a diagnostic that names both mutation fields and both `Wrap` arms.

## Plan

### Implementation

- `RecordBindingResolver.groundDmlMutationField` and `RecordBindingResolver.groundServicePayloadBinding`: before each `putIfAbsent`, if the *other* memo already holds an observation for the same `payloadSdl`, append a record to a new `mixedProducerConflicts` map. Each entry carries the payload SDL name, the first producer's coords + `Wrap` arm, the second producer's coords + `Wrap` arm. The first/second distinction is the declaration order the `resolveAll()` walk already follows.
- Expose `Optional<MixedProducerConflict> resolveMixedProducerConflict(String payloadSdl)` from `RecordBindingResolver`, mirrored on `TypeBuilder`.
- `FieldBuilder.classifyField` for the mutation field: when classifying a `MutationDmlRecordField` / `MutationBulkDmlRecordField` / `MutationServiceRecordField` for a payload that has a conflict, and `(parentTypeName, name)` matches the "second producer" coords, return `UnclassifiedField` with a `Rejection.structural(...)` whose message includes both mutation field names and both `Wrap` arm names (test expects `"runFilms", "createFilms", "Wrap.Record", "Wrap.TableRecord"`).
- `FieldBuilder.classifyField` for the payload data field: when both `dmlEmittedBinding` and `serviceEmittedBinding` resolve, consult the conflict record to pick the winner's arm rather than always preferring DML. The arm not chosen still runs to construct the `SingleRecordTableField`, just with the winner's `Wrap` shape and table.

### Tests

- Re-enable the two `@Disabled` cases in `SingleRecordTableFieldServiceProducerPipelineTest` (remove the `@Disabled` annotations and the leading explanatory comment block at line 178-186; keep the per-case Javadoc).
- Verify pipeline tier passes on both ordering directions.

### Roadmap entries

- This file deletes on Done; `changelog.md` gets a one-line R204 entry citing the landing commit.
