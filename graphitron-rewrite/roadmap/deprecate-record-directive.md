---
id: R96
title: "Warn that @record is ignored"
status: Spec
bucket: cleanup
priority: 6
theme: model-cleanup
depends-on: []
---

# Warn that @record is ignored

Graphitron derives the Java class backing an SDL type from
reflection on the producer (`@service` method return, `@table`
resolution, parent-accessor return type). The `@record` directive
(declared at `directives.graphqls:290` as `directive @record(record:
ExternalCodeReference) on OBJECT | INPUT_OBJECT`) restates that
information; its presence or absence is irrelevant to what
graphitron generates. Two declarations carrying the same fact, one
in SDL and one in the Java signature reflection already reads, is a
drift class waiting to surface as a misleading author signal.

R96 closes that surface by warning at schema-build time whenever
`@record` is declared, telling the author the directive is ignored
and should be removed.

## Scope

A single validator-tier check that fires once per SDL `OBJECT` or
`INPUT_OBJECT` declaration carrying `@record`. The check emits a
`ValidationReport` warning (not an error): generated code is
unchanged whether the directive is present or absent, so the build
continues.

The warning message names the directive, the affected type, and
tells the author to remove it. Example:

> Type 'FilmReviewPayload' carries @record(record: {...}); this
> directive is ignored. Graphitron derives the backing class from
> the producing field's reflected return type. Remove the directive.

That is the full scope.

## Out of scope

- Retiring the directive declaration at `directives.graphqls:290`.
  R96 makes the directive's presence loud; retirement is a
  downstream item once consumers have removed declarations.
- Retiring model variants `JavaRecordType`, `JooqRecordType`,
  `JooqTableRecordType`, `PojoResultType.Backed`. Whatever drives
  them today drives them after R96.
- The `@service`-payload error-construction surface
  (`payloadFactoryLambda`, `ResultAssembly`, the `PayloadAccessor`
  arm of `Transport`). Separate concern, unrelated to `@record`.
- Anything R94 owns on the input-side classifier rework. R96's
  warning is orthogonal and ships whether R94 has shipped or not.

## Tests

Validator-tier (`GraphitronSchemaValidator` test surface):

- Positive: `OBJECT` carrying `@record` emits the warning with the
  type name.
- Positive: `INPUT_OBJECT` carrying `@record` emits the same
  warning shape.
- Negative: a type without `@record` emits no warning.
- The warning surfaces on `ValidationReport.warnings()`, not
  `errors()`; the build proceeds.

No pipeline-tier, compile-tier, or execution-tier coverage is
needed: generated code does not change.

## Risk

Additive and behaviour-preserving. If a current `DIR_RECORD` read
elsewhere is actually load-bearing (contradicting the premise that
`@record` is irrelevant), the warning is misleading on those types.
That surfaces as its own cleanup item, not as a regression here.
