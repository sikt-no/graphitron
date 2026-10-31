---
id: R1137
title: "A rejected @error type cascades into misleading diagnostics on the types around it"
status: Ready
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-10-30
last-updated: 2026-10-31
---

# A rejected @error type cascades into misleading diagnostics on the types around it

## Goal

When a type is rejected, the build reports that one rejection and nothing whose only cause is it.
A diagnostic that exists only because another type was already rejected is dropped from the
validator's output, but only when the rejection it depends on is itself reported, so a root cause
can never be swallowed.

A *rejected type* here is a type the classifier demoted to `UnclassifiedType`: it appears in the
type registry with a `Rejection` instead of a classified variant, and the validator reports it
once, as `Type '<name>': <rejection>`.

Today an `@error` type whose handler names a class the module does not declare produces three
independent-looking errors from one fault:

```graphql
interface Error { path: [String!]!  message: String! }

type KregUgyldigInput implements Error
    @error(handlers: [{handler: GENERIC, className: "jakarta.ws.rs.ForbiddenException"}]) {
  path: [String!]!
  message: String!
}
```

Before (three errors; the second points at the stable `Error` interface and suggests `@table` on an
error type, the third is noise):

```
Type 'KregUgyldigInput': @error type rejected: @error handler {handler: GENERIC, className: "jakarta.ws.rs.ForbiddenException"}: class '...' is in jakarta.ws.rs:jakarta.ws.rs-api, which this module does not declare: ...
Type 'Error': implementing type 'KregUgyldigInput' is not table-bound (missing @table directive)
Field 'KregUgyldigInput.path': parent type is unclassified
Field 'KregUgyldigInput.message': parent type is unclassified
```

After (one error, the root cause):

```
Type 'KregUgyldigInput': @error type rejected: @error handler {handler: GENERIC, className: "jakarta.ws.rs.ForbiddenException"}: class '...' is in jakarta.ws.rs:jakarta.ws.rs-api, which this module does not declare: ...
```

All three disappear today once the dependency is declared, and still would after this change; the
change only stops them being reported while the root cause stands.

## What the code does today (verified)

* The `@error` rejection: `TypeBuilder.buildErrorType` collects `rejectReasons` and returns
  `UnclassifiedType` with `"@error type rejected: ..."`. The undeclared dependency reason comes from
  `TypeBuilder.validateExceptionClass`, which asks `ClasspathNameability.verdictFor`; the
  "which this module does not declare" text is `ClasspathNameability`'s. The item's quoted message
  omits the `@error type rejected: ` prefix; otherwise accurate.
* Interface follow-on: `TypeBuilder.buildParticipantList` calls `participantClassification` for
  each implementor. A member that is `UnclassifiedType` fails the `!(gt instanceof UnclassifiedType)`
  test and falls to the final `else`, which adds `"implementing type 'X' is not table-bound (missing
  @table directive)"`. That `else` conflates two cases: a genuinely directiveless/non-table member
  (the hint is right) and a member that was already rejected (the hint is wrong). The caller turns
  the joined errors into an `UnclassifiedType` for the interface (plain interface arm in
  `classifyType`, the union arm, and the `@table @discriminate` interface arm via
  `buildTableInterfaceType`). The same wrong message therefore also hits unions with a rejected
  member. The rule is not `@error`-specific: any rejected `@table`, `@record` or `@error` member
  triggers it.
* Field follow-on: `FieldBuilder.classifyFieldInner` ends with
  `UnclassifiedField(..., Rejection.structural("parent type is unclassified"))` for any parent that
  is not a root, table-backed, result or error type, which is every `UnclassifiedType` parent. It
  fires for every field of a rejected type, not just `path` and `message`. Earlier branches
  (`@routine` chains and the like) can still reject such a field for their own, independent reason.
* Validator: `GraphitronSchemaValidator.validateUnclassifiedType` and `validateUnclassifiedField`
  each emit one `ValidationError` per verdict (`ValidationError.forType` / `forField`). There is no
  cause-aware dedup and no existing "because X was rejected" pattern for type verdicts. The nearest
  precedent is `GraphitronSchemaBuilder.rejectDanglingTypeReferences`, which appends the reason
  the type did not classify (attribution, not suppression), and `Rejection` already distinguishes
  typed arms from `Structural` prose.
* Not in the item: a field that *returns* a rejected type (`Query.x: KregUgyldigInput`) is a third
  possible cascade. It is not part of the reported fault and is left out of scope here.
* No existing test pins either follow-on message (searched `graphitron/src/test` for both strings;
  only comments in `EntityResolutionBuilderTest` mention a "missing @table" hint for a different,
  federation message). `UnclassifiedTypeValidationTest` pins only that each `UnclassifiedType`
  yields one error.

## Decision

* **General, not `@error`-only.** Both follow-on sites key on `instanceof UnclassifiedType`, not on
  `@error`; an `@error`-only rule would have to rediscover the cause at a site that does not know
  it, and a rejected `@table` type (unknown table) cascades identically today.
* **Suppress in the validator, attribute in the model.** Keep the model total and unchanged: the
  interface stays an `UnclassifiedType` and the field stays an `UnclassifiedField`, so no emitter or
  registry invariant moves (dropping a member from the participant list, or not producing the
  field, would). What changes is that the rejection each carries is a new typed arm naming its
  cause, and the validator drops it from the error stream only when the cause is itself reported.
  Plain suppression keyed on message text is rejected; so is "attribute only" (still two to four
  lines for one fault).

## Implementation

* `Rejection.AuthorError`: add a record arm `CascadedFrom(List<String> rejectedTypeNames,
  String reason)` whose `message()` reads as a consequence, for any consumer that reads the model
  rather than the validator stream (for example "implementing type 'X' was rejected, see Type 'X'"
  and "parent type 'X' was rejected, see Type 'X'"). Extend the exhaustive switches the compiler
  flags (`Rejection.prefixedWith`, the diagnostic-facts mapping covered by `DiagnosticFactsTest`);
  `RejectionKind.of` already maps the whole `AuthorError` family to `AUTHOR_ERROR`.
* `TypeBuilder.buildParticipantList`: when `gt instanceof UnclassifiedType`, record the member as a
  cascaded cause instead of adding the "not table-bound" string. Return a cascade rejection only
  when no genuine error was collected; when both occur, report the genuine ones and omit the
  cascaded names (the rejected member has its own diagnostic). `ParticipantListResult` carries the
  cascade as a typed value rather than prose so the three callers (plain interface, union,
  `@table @discriminate` interface) build `UnclassifiedType(name, location, CascadedFrom(...))`.
* `FieldBuilder.classifyFieldInner`, final return: when `parentType instanceof UnclassifiedType`,
  return `UnclassifiedField` carrying `CascadedFrom(List.of(parentTypeName), ...)`; keep the
  existing structural message for the remaining case (parent absent from the registry).
* `GraphitronSchemaValidator`: in `validateUnclassifiedType` and `validateUnclassifiedField`, skip
  the error when the rejection is `CascadedFrom` and every named cause is, in the registry, an
  `UnclassifiedType` whose own rejection is not `CascadedFrom` (a root cause). If any cause is
  missing or itself cascaded, emit the error as today, so a cascade can never hide the only
  report. Cycles are impossible because a cascade is only created from a root or already-cascaded
  rejection and the root check is one hop.
* Javadoc on the new arm states the invariant (reported cause or reported itself), per the repo's
  javadoc conventions; no roadmap ids in comments.

## Tests

* Pipeline tier, `GraphitronSchemaBuilderTest`-style or a new `*PipelineTest`
  (`docs/architecture/how-to/testing.adoc`): build the SDL above with a handler `className` the test
  classpath names but the nameability gate rejects (reuse the fixture the existing
  `ClasspathNameabilityTest` / `@error` handler cases use), run `GraphitronSchemaValidator` and
  assert exactly one error, for `KregUgyldigInput`, and none for `Error` or its fields. Also assert
  the model still holds `UnclassifiedType` for `Error` and `UnclassifiedField` for both fields, so
  the model-unchanged claim is pinned.
* Same shape for a union with a rejected `@error` member, and for a rejected `@table` type with an
  unknown table implementing a plain interface (pins the general rule).
* Guard cases: an interface with one rejected member and one genuinely directiveless member still
  reports the directiveless one; a `CascadedFrom` whose cause is absent from the registry is still
  reported (unit tier, `UnclassifiedTypeValidationTest`).
* Existing field-level `UnclassifiedField` cases in `GraphitronSchemaBuilderTest` (unknown column,
  unknown reference) are unaffected: their parents classify.

## Open question

Should a field that returns a rejected type (`Query.x: KregUgyldigInput`) be folded into the same
rule? Recommended: no, separate item, since it is a different edge and not part of the reported
fault.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
