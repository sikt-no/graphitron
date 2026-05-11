---
id: R129
title: "Stub: ConditionJoin path in @reference on ColumnReferenceField"
status: Backlog
bucket: stubs
priority: 5
theme: model-cleanup
depends-on: []
---

# Stub: ConditionJoin path in @reference on ColumnReferenceField

When R42 lifts `ChildField.ColumnReferenceField` for `CallSiteCompaction.Direct` with FK-only `joinPath`, schemas whose `@reference` resolves through a developer-supplied `@condition` method (a `JoinStep.ConditionJoin` step in the path) remain unsupported. R42's validator emits `Rejection.Deferred` keyed to this slug for that case, so the SDL author sees a build-time error rather than a runtime stub.

Lifting this shape is blocked on the same upstream as the parallel `TableField` deferral at `InlineTableFieldEmitter.java:53-60`: R3 (`classification-vocabulary-followups`) item 5 resolves the target table of a `@condition` method, which the emitter needs to alias on the inner SELECT's `FROM`. When R3 ships that resolution, six variants lift in one pass — `ChildField.TableField`, `ChildField.LookupTableField`, the four `ConditionJoinReportable` variants (`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`), and the `ColumnReferenceField` case this stub records. Re-spec at that point; the natural home may be to fold this slug into the R3 implementation rather than a separate item.
