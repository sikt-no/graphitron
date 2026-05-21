---
id: R221
title: "Validator walks PlainInputArg.fields() for UnboundField rejection"
status: Backlog
bucket: architecture
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Validator walks PlainInputArg.fields() for UnboundField rejection

R215's validator-side check on `InputField.UnboundField` (per the spec's §Validator rules: `condition.isPresent() && !condition.get().override()` → reject at the directive's source location) only walks `GraphitronType.TableInputType.inputFields()` via `GraphitronSchemaValidator.validateTableInputType` + `validateInputFieldRecursive`. Truly plain input types (`GraphitronType.InputType` permits — `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType`) carry no classified `InputField` records on the type; their fields classify at consumer time on `ArgumentRef.InputTypeArg.PlainInputArg.fields()`. The validator's whole-schema walk has no view into `PlainInputArg`, so plain-input `UnboundField + @condition(override:false)` shapes escape the validator-mirrors-classifier rule. R215's acceptance test #5 (`r215_validatorRejectsOverrideFalseOnNonBindingField`) was written against a `@table` input (which routes through the existing walker) so the test passes, but the literal spec phrasing "non-binding **plain** input field" is structurally unreachable today.

The symptom that surfaces this: a plain input field with `@condition(override:false)` inside an `@condition(override:true)` cascade silently fires the explicit `@condition` method (the consumer-arm patch shipped in R215's late round restored the doc-promised "every @condition you write produces SQL" contract for the cascade case) but the no-column shape is still malformed and the developer doesn't get a build-time error. The validator's job is to catch that regardless of where the field lives.

Implementation: walk `PlainInputArg.fields()` from each `QueryField` / `MutationField` at validate time, reusing `GraphitronSchemaValidator.validateInputFieldRecursive` (already recurses through `InputField.NestingField`). The walk surface is reachable via `SqlGeneratingField.argumentRefs()` or the equivalent per-variant accessor; the per-arg switch picks `PlainInputArg` and feeds its fields through the same recursion the @table walker uses. No new validator method required; just a second entry point that fans out by argument shape rather than by type shape.

Surfaced by the R215 self-review: alf flagged that the architect's brief glossed the cascade contract as "outer override:true drops inner @condition" when the documented rewrite reading is "outer override:true suppresses only implicit predicates; inner @condition annotations always fire." The shipped consumer-arm patch closed the doc-contract bug; this item closes the validator gap that the consumer-arm patch was implicitly relying on the validator to cover.

## References

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java:302-332` — existing `validateTableInputType` + `validateInputFieldRecursive` walker to mirror.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ArgumentRef.java` — `PlainInputArg.fields()` accessor.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1631-1655` — consumer-arm UnboundField switch; the silent-fire-the-condition case is the reason validator coverage matters.
- `docs/manual/how-to/migrating-from-legacy.adoc:179-191` — the cascade-divergence doc the validator gap currently lets schemas violate at runtime.
- R215 (`column-binding-at-classification-not-usage.md`) — the parent work; the validator rule lives there but is reachable only for `@table` inputs.
