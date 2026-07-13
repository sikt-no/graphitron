---
id: R472
title: "Generated condition filters on nested fields reference a conditions method that is never emitted"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-13
last-updated: 2026-07-13
---

# Generated condition filters on nested fields reference a conditions method that is never emitted

## Problem

`TypeConditionsGenerator.generate` collects `GeneratedConditionFilter`s by iterating
`schema.types()` × `schema.fieldsOf(type)`, which structurally cannot see fields nested inside a
`ChildField.NestingField` (a coordinate-less nesting type has no `schema.types()` entry and an empty
`fieldsOf`). Yet the classifier attaches a `GeneratedConditionFilter` to a nested `SplitTableField` /
`SplitLookupTableField` / inline `TableField` / `LookupTableField` with filterable args exactly as it
does at top level, and the emit sites call the filter's method unconditionally
(`FkTargetConditionEmitter.emitTerm` in the split rows method; `InlineTableFieldEmitter` /
`InlineLookupTableFieldEmitter` in the outer type class). Result: valid SDL (a filterable-arg field on
a plain-object nesting type, admitted by `NESTED_WIREABLE_LEAVES`) produces generated code that calls a
`<ReturnTypeName>Conditions` method the pipeline never generates, failing at *consumer javac* instead
of at build time. The method only exists by coincidence when some top-level field independently
produces an identically-named filter for the same return type.

This is a validator-mirror gap as much as an emit gap: a classifier decision (`GeneratedConditionFilter`
on a nested field) implies an emit branch (`TypeConditionsGenerator`) that cannot run, and no
validate-time rejection mirrors that. The fix is either (a) teach `TypeConditionsGenerator` (and the
`QueryConditionsGenerator`-style extraction, if applicable) to walk `NestingField.nestedFields()` the
way `TypeFetcherGenerator.collectNestedFetcherClasses` does, or (b) reject the shape at validate time
with a typed `Deferred` until (a) is implemented; (b) is the floor either way so the invariant has a
build-time enforcer.

## Notes

Found while speccing R462 (`nested-fetcher-outgoing-field-edges`), whose harness fixture deliberately
gives its nested `@splitQuery` field no filterable args to avoid tripping this. R462 models the
`fetcher → gcf.className()` edge for nested filtered fields anyway (unit tier); that modeling becomes
live end-to-end once this item lands. The R459-named collapse target (a derived nested-type view on
`GraphitronSchema` so all nested-tree consumers project off one seam) would give option (a) its
natural shape.
