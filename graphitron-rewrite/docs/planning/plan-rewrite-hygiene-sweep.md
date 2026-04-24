# Plan: Rewrite emitter + classifier hygiene sweep

> **Status:** Spec
>
> Three independent cleanups surfaced during the split-query-connection
> work (shipped at `3821842` §1 + `62b51c3` §2). Each phase is shippable on its own; the plan groups
> them because the pressure came from the same observation pass and because
> they share a "make the seam explicit before the next variant copies the
> seamless version" motivation.

## Problem

Implementing `@asConnection` on `@splitQuery` produced three code-shape
observations that are hygiene, not blockers:

1. `SplitRowsMethodEmitter` now has three 150-line siblings (`buildListMethod`,
   `buildSingleMethod`, `buildConnectionMethod`) that share a VALUES + FK-chain
   prelude and diverge at projection + outer wrap. `buildConnectionMethod`
   was written by copying `buildListMethod` and mutating, which will rot the
   next time a Split variant needs a fourth copy.
2. Emitted helpers that reference a specific aliased jOOQ Table disagree on
   whether the Table is a parameter or a local declaration. `<fieldName>Condition`
   takes it as a parameter (post fetcher-quality §2); `<fieldName>OrderBy` takes
   it as a parameter now (post split-query-connection §2); `*InputRows`
   (`LookupValuesJoinEmitter`) has the question unaudited. A Split-shaped
   caller that wants to reuse `*InputRows` tomorrow will hit the same alias
   mismatch that blocked dynamic `@orderBy` on Split+Connection for a release.
3. Classifier/validator rejection messages collapse four distinct categories
   into free-form strings. "`@asConnection` on `@splitQuery`" was labeled
   "not yet supported; plan §2" until a reviewer flagged that it is actually
   permanently invalid. Downstream consumers (the Maven plugin, error-aggregation
   tooling, schema-author-facing docs) have no machine-readable signal to
   distinguish "generator deferred" from "schema modeling error" from
   "internal invariant".

## Phase 1: deduplicate `SplitRowsMethodEmitter` siblings

**Scope.** Extract the shared prelude from `buildListMethod` /
`buildSingleMethod` / `buildConnectionMethod` into named helpers so each
sibling reduces to a body-only body.

**Touch points.**
- New private static `PreludeBlocks buildParentInputPrelude(BatchKey, TableRef,
  List<JoinStep>, String fieldName)` returning a record of
  `(CodeBlock parentInputValues, CodeBlock fkChainAliases, List<String> aliases)`.
  Handles the `keys.isEmpty()` short-circuit, the `@SuppressWarnings` cast +
  typed `Row<N+1>` VALUES table, and the FK-chain `<TableClass> alias_i =
  Tables.X.as("field_alias_i")` declarations.
- `buildListMethod`, `buildSingleMethod`, `buildConnectionMethod` each call
  the prelude helper, then emit their own projection / join composition /
  outer wrap.
- Expectation: each sibling lands under ~50 lines. New variant emission
  becomes a body-only exercise, not a copy-paste.

**Test coverage.** Existing pipeline tests in `SplitTableFieldPipelineTest`
assert the emitted method signatures and shape invariants (idx column,
scatter helper gates). No new tests needed; the refactor passes iff the
existing tests stay green. One review-level check: skim the generated
sources in `graphitron-rewrite/graphitron-rewrite-test/target/generated-sources` before
and after, confirm identical output.

**Risk.** Low. The extraction mechanical; the prelude really is identical
across the three siblings. One nuance: `buildSingleMethod` uses single-hop
path (`JoinStep.FkJoin firstHop = (JoinStep.FkJoin) joinPath.get(0)`) while
list/connection walk the full path. The prelude helper needs to take the
full path; single-cardinality callers ignore bridging aliases.

## Phase 2: audit emitted-helper Table-parameter convention

**Scope.** Sweep every helper emitter that references a specific aliased
jOOQ Table. Establish the rule: **helpers that bind column refs to a jOOQ
Table instance always take the Table as a parameter, never declare their
own.** Land the rule as a comment near the top of each emitter; fix any
violations.

**Audit targets.**
- `TypeFetcherGenerator.buildOrderByHelperMethod`: fixed in the
  split-query-connection §2 work at commit `62b51c3`. Already compliant.
- `QueryConditionsGenerator.conditionMethodName` / the per-RootType
  `QueryConditions` class: takes Table as parameter per fetcher-quality §2.
  Compliant; skim to confirm.
- `LookupValuesJoinEmitter.*InputRows`: unaudited. Open the emitted code
  for `filmActorsByKeyInputRows` and check whether the helper's column
  refs bind to a passed Table or an internally-declared one.
- `InlineLookupTableFieldEmitter` helpers (the `sfName`-threaded ones): the
  sfName parameter already threads the nested alias, but verify the pattern
  is explicit (parameter, not local).
- `SplitRowsMethodEmitter`: not a helper emitter itself, but its emitted
  rows methods pass tables to other helpers; confirm the pass-through shape.
- `ConnectionHelperClassGenerator.pageRequest` / `encodeCursor` /
  `decodeCursor`: these don't bind to a specific table (they operate on
  `Field<?>` / `List<SortField<?>>`), so the rule doesn't apply.

**Deliverable.** A short rule in `docs/rewrite-design-principles.md` citing
the convention, plus any fixes for violations found. If every emitter is
already compliant, ship the rule anyway so the next emitter author has
the contract in front of them.

**Test coverage.** Existing pipeline tests assert the helper signatures
(`TypeFetcherGeneratorTest.orderByArg_helperMethod_takesEnvAndAliasedTableParameters`,
new `splitQueryConnection_withDynamicOrderByArg_emitsOrderByHelperAcceptingAliasedTable`).
Any helper that moves from "declares own Table" to "takes Table parameter"
needs a parallel signature test.

**Risk.** Low. Most helpers already comply; the audit is the point, not
the refactor.

## Phase 3: rejection-message taxonomy

**Scope.** Introduce a machine-readable category on `UnclassifiedField` /
`ValidationError` rejections. The four categories observed:

- **`INVALID_SCHEMA`**: the combination is permanently incompatible. Author
  fix is to drop a directive or restructure the type. Example:
  `@asConnection` + `@lookupKey`.
- **`AUTHOR_ERROR`**: the schema references something the jOOQ catalog or
  SDL registry doesn't know about. Author fix is to correct the reference.
  Example: unresolvable `@lookupKey` argument, missing FK between tables,
  unknown column in `@field(name:)`.
- **`DEFERRED`**: the generator doesn't support this shape yet but plans to.
  Example pre-§1: single-cardinality `@splitQuery` with multi-hop path.
- **`INTERNAL_INVARIANT`**: a classifier-level contract was violated in a
  way that should not be reachable from any valid user schema. Example:
  empty `joinPath` reaching an emitter that requires a non-empty path.

**Touch points.**
- New enum `GraphitronField.UnclassifiedField.Kind` with the four values.
- `UnclassifiedField` record gains a `Kind kind` field (alongside existing
  `reason`). Constructor-arity break; every call site in `FieldBuilder` +
  `GraphitronSchemaValidator` updates to pass a `Kind`.
- `ValidationError` similarly gains a `Kind` enum (same four values, or
  narrower if some don't apply).
- `GraphQLRewriteGenerator.generate` logging can format differently per
  kind: `AUTHOR_ERROR` reads as `error: ...`, `DEFERRED` reads as
  `warning: <feature> not yet supported` (or still an error, but with a
  plan pointer), `INVALID_SCHEMA` reads as `error: invalid schema`,
  `INTERNAL_INVARIANT` reads as a compiler-bug-style message.
- Existing rejection messages categorized during the same commit: the
  audit groups them into the four kinds and updates the constructor calls.

**Test coverage.** Existing pipeline tests assert rejection messages via
`.contains("...")` predicates. The new `Kind` field adds a cheap
`.hasKind(...)` assertion to the validator test helpers; switch existing
tests over as they're visited.

**Risk.** Medium. Widespread call-site touch (every classifier rejection
and every validator error), but the changes are mechanical once the enum
exists. Might surface genuine misclassifications in existing messages
(things labeled "not supported" that are actually `INVALID_SCHEMA`, as
`@lookupKey + Connection` was). Treat those as bugs fixed in the same
commit.

## Order + gating

Phases are independent and can ship in any order. Suggested sequence:

1. **Phase 1** first (smallest, highest local blast radius in
   `SplitRowsMethodEmitter`, lowest risk).
2. **Phase 2** second (audit only; fixes are minor if any).
3. **Phase 3** last (widest touch, lands the structural benefit).

If a Phase 3 implementer wants to split it, a smaller first cut is "add
the enum + categorize the 30 rejections in `FieldBuilder` only, leave
validator call sites as-is". That ships the shape without forcing a
whole-codebase sweep.

## Non-goals

- Collapsing `scatterByIdx` / `scatterSingleByIdx` / `scatterConnectionByIdx`
  into one generic helper. They have different return types
  (`List<List<Record>>` vs `List<Record>` vs `List<ConnectionResult>`) and
  genuinely different per-idx logic. The gate predicates in
  `TypeFetcherGenerator.generateTypeSpec` read as four `stream().anyMatch`
  calls today; that's fine until a fifth helper arrives.
- Reshaping `ConnectionResult`. The `List<Record>` narrowing landed in
  split-query-connection §1; further changes belong to whichever plan
  motivates them.
- Adding a new machine-readable message format on top of kind. The kind
  enum is sufficient; format is a downstream tooling concern.

## Open questions

1. **Phase 3 scope**: should the kind enum also apply to `GraphitronField`
   fields that classify successfully but carry deferred-feature warnings
   (e.g. condition-join stubs emit a runtime-throwing body)? Today those
   are `BatchKeyField` with a non-empty `unsupportedReason`, not an
   `UnclassifiedField`. If yes, the enum lives at a higher level than
   `UnclassifiedField`; if no, it stays scoped to rejections.
2. **Phase 2 rule location**: put the convention comment in
   `docs/rewrite-design-principles.md` or inline on each emitter class?
   Inline is harder to ignore; design-principles is easier to cite.
   Recommend both: a one-liner on each emitter linking to the
   design-principles section.
