---
id: R58
title: "Make the typed `Rejection` hierarchy load-bearing across producers"
status: In Review
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: []
---

# Make the typed `Rejection` hierarchy load-bearing across producers

> R58 Phases 0–D (shipped) replaced the flat `(RejectionKind, String)` pair on
> [`UnclassifiedField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java) /
> [`UnclassifiedType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java)
> with a sealed
> [`Rejection`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java)
> hierarchy, threaded the typed shape through every resolver's `Resolved.Rejected` arm, and
> walked the direct candidate-hint producer set onto typed `AuthorError.UnknownName` factories.
> What remains: (1) the deeper carrier widenings whose producers Phase D could not migrate without
> changing intermediate carriers (`ParsedPath.errorMessage`, `InputFieldResolution.Unresolved.reason`,
> `ArgumentRef.UnboundArg.reason`, the joined-string aggregation patterns in
> `EnumMappingResolver` and `TypeBuilder`); (2) the directive-conflict, capability, stub-key, and
> validator-side lifts (Phases E–I).

---

## Where we are

R58 Phases 0–D landed:

- **Phase 0** (commit `7c10226b`): dropped `RejectionKind.INTERNAL_INVARIANT`; the single producer
  site at `FieldBuilder.classifyChildFieldOnTableType`'s nested-fields fallthrough became an
  `AssertionError`.
- **Phase A** (commit `09541ed5`): introduced
  [`Rejection.java`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java)
  (top-level sealed `AuthorError | InvalidSchema | Deferred`, sub-sealed
  `AuthorError.{UnknownName | Structural}` and `InvalidSchema.{DirectiveConflict | Structural}`,
  `StubKey.{VariantClass | EmitBlock | None}`, `EmitBlockReason` enum, factory statics, self-contained
  `candidateHint` renderer mirroring `BuildContext.candidateHint`). `UnclassifiedField` carries
  `Rejection rejection` instead of `(RejectionKind kind, String reason)`; every rejection-producing
  resolver's `Resolved.Rejected` arm widened to carry `Rejection`.
- **Phase B** (commit `5d29a3d3`): mirrored the lift onto `UnclassifiedType`. The 24 construction
  sites (21 in `TypeBuilder`, 3 in `EntityResolutionBuilder`) migrated. Three table-resolution
  sites construct `AuthorError.UnknownName` via the new `Rejection.unknownTable` factory; the rest
  construct `Rejection.structural`.
- **Phase C** (commit `68a062c0`): `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` renamed to
  `STUBBED_VARIANTS` and widened from `Map<Class, String>` to `Map<Class, Rejection.Deferred>`;
  `SplitRowsMethodEmitter.unsupportedReason` overloads lifted from `Optional<String>` to
  `Optional<Rejection.Deferred>` keyed by `EmitBlockReason`. Validator gate has one path for
  "deferred" regardless of channel.
- **Phase D** (this commit): walked the direct candidate-hint producers onto typed
  `AuthorError.UnknownName` factories. Added factories `unknownTypeName`, `unknownEnumConstant`,
  `unknownNodeIdKeyColumn`, `unknownDmlKind` and the leaf-arm `prefixedWith(String)` instance
  method. Widened `ServiceCatalog.ServiceReflectionResult` from `(MethodRef, String failureReason)`
  to `(MethodRef, Rejection rejection)`; the four wrapper callers
  (`ServiceDirectiveResolver`, `TableMethodDirectiveResolver`, `ConditionResolver`,
  `ExternalFieldDirectiveResolver`) project the typed shape through `prefixedWith` so
  `UnknownName.candidates` survives the caller-specific prose prefix. Migrated direct producers:
  `BatchKeyLifterDirectiveResolver` (target column → `unknownColumn`; lifter method →
  `unknownLifterMethod`), `FieldBuilder` (column on FK-resolved table → `unknownColumn`; column on
  scalar field → `unknownColumn`; `@nodeId(typeName:)` → `unknownTypeName`;
  `DmlKindResult.Unknown` → `unknownDmlKind`), `ServiceCatalog` (three method-not-found sites →
  `unknownServiceMethod`). Audited `AttemptKind`: dropped `TABLE_METHOD`, `ARGUMENT_NAME`,
  `FIELD_NAME` (no producers post-migration). New tests: `R58TypedRejectionPipelineTest`
  exercises three migrated producers end-to-end (asserts typed `UnknownName` with non-empty
  `candidates()` survives), `RejectionRenderingTest` extended with eight cases for new factories
  + `prefixedWith` preservation across all four leaves.

## Where we should go

A post-Phase-D audit leaves five remaining items: the nested-rewrap loss (Phase E), the four
secondary lifts in the original audit (Phases F–I), and a Phase D-bis follow-up for the
candidate-hint producers whose carrier widening Phase D could not authorize. The diagnostic
([*Sub-taxonomies for resolution outcomes*](../docs/rewrite-design-principles.adoc)): a typed shape
that isn't constructed at the call site is a sub-taxonomy on paper only; the load-bearing value
lives at the producer end.

1. **The nested-rewrap site collapses `UnknownName` to `Structural`.** When
   `FieldBuilder.classifyChildFieldOnTableType` rewraps a nested `UnclassifiedField`, the switch on
   the inner rejection matches the broad `Rejection.AuthorError` arm and discards `attempt` /
   `candidates` / `attemptKind`. Nesting is exactly when an LSP fix-it would still want the typed
   suggestions.

2. **`InvalidSchema.DirectiveConflict` has zero producers.** The arm exists with `directives:
   List<String>` and a factory; no production site constructs one. Genuine directive-conflict sites
   (`@service` + `@mutation` mutually exclusive, `detectChildFieldConflict` /
   `detectQueryFieldConflict` results, `@asConnection` on inline TableField, `@notGenerated`
   removed) all use `Rejection.invalidSchema(prose)`.

3. **`validateVariantIsImplemented` runs a 4-arm `instanceof` chain mirroring a per-variant
   predicate.** `SplitRowsMethodEmitter.unsupportedReason` has four near-identical overloads, each
   running `JoinPathEmitter.hasConditionJoin(joinPath())` and returning a `Rejection.Deferred` keyed
   by an `EmitBlockReason` value matching variant identity. The variant *is* the discriminant;
   validator and emitter both re-run the dispatch. ([*Generation-thinking*](../docs/rewrite-design-principles.adoc):
   "the same multi-arm switch recurs across multiple consumers" is a sign the resolver is
   under-specified.)

4. **`StubKey.None` is a typed distinction with no consumer.** The arm's javadoc concedes that
   validator paths treat `None` and `VariantClass` identically; `None` exists only for hypothetical
   LSP-fix-its that want to jump to the roadmap entry, and no such consumer exists today. Plus four
   overloaded `Rejection.deferred(...)` factories produce a 2x2 matrix where only three combinations
   have producers. ([*Sub-taxonomies for resolution outcomes*](../docs/rewrite-design-principles.adoc):
   "audit at milestone boundaries which sub-taxonomies could collapse now that their forcing
   functions are visible.")

5. **Validator-side `ValidationError` construction bypasses `Rejection`.** 33 sites in
   [`GraphitronSchemaValidator`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)
   and two sites in `GraphitronSchemaBuilder.buildRecipeErrors` build `ValidationError(RejectionKind,
   ...)` directly. Two parallel taxonomies for the same concept; a near-miss check the validator
   adds tomorrow has no on-ramp to typed `UnknownName`.

6. **Phase D-bis: candidate-hint producers behind String-carrier intermediates.** Phase D migrated
   the producer sites whose immediate carrier is `Rejection`-bearing (or whose carrier the spec
   authorized widening, namely `ServiceReflectionResult`). The remaining candidate-hint sites
   produce *into* String-carrier intermediates that flatten the typed shape before it reaches a
   `Rejection` consumer:
   - [`BuildContext`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java)`:584`
     (FK SQL name) flows through `parsePathElement`'s `List<String> errors` accumulator into
     `ParsedPath.errorMessage: String`. Migrating to `unknownForeignKey` requires widening
     `ParsedPath.errorMessage` to `Rejection` and updating every `parsePath().hasError()` consumer.
   - `BuildContext:877, 1013` (typename in `@nodeId`, column in path leg) flow through
     `InputFieldResolution.Unresolved.reason: String`. Migrating to `unknownTypeName` /
     `unknownColumn` requires widening that field to `Rejection`.
   - [`FieldBuilder`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)`:853`
     (column on filter table) flows through `ArgumentRef.ScalarArg.UnboundArg.reason: String`.
     Already called out as out of scope for Phase D in the original spec; surfaces here as part of
     the same widening pattern.
   - [`EnumMappingResolver`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java)`:145, 156`
     joins multiple per-constant misses into a single `Mismatch` with prose. Migrating to
     `unknownEnumConstant` per miss requires widening `EnumValidation.Mismatch` to carry a
     `List<Rejection>` (or splitting the carrier shape).
   - [`TypeBuilder`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java)`:403, 704`
     accumulate per-key-column / per-input-field misses into `List<String>` then collapse to a
     single `Structural`. Migrating to `unknownNodeIdKeyColumn` / `unknownColumn` per miss requires
     the same shape-widening as the EnumMappingResolver case.

   The Phase D factories (`unknownForeignKey`, `unknownTypeName`, `unknownEnumConstant`,
   `unknownNodeIdKeyColumn`, `unknownColumn`) are already in place; Phase D-bis lifts the carriers
   so the typed values reach a `Rejection` consumer. Each carrier widening is independently
   shippable; pick whichever lights up the next downstream consumer first.

The shape of the work: where R58 Phases A/B lifted *the carrier*, this plan lifts *the producers*.
Each phase below is independently shippable; the byte-stable validator log surface holds at every
boundary.

---

## Phasing

### Phase D — lift the candidate-hint producers onto `UnknownName`

Shipped (this commit). Migrated the producer sites whose immediate carrier accepts a `Rejection`
or whose carrier widening Phase D authorized: `BatchKeyLifterDirectiveResolver:179, 237`,
`ServiceCatalog:185, 373, 469` (via widening `ServiceReflectionResult.failureReason: String` →
`rejection: Rejection`), `FieldBuilder:3064, 3108, 3138, 2357`. Added factories
`unknownTypeName`, `unknownEnumConstant`, `unknownNodeIdKeyColumn`, `unknownDmlKind` and the
leaf-arm `prefixedWith(String)` instance method on `Rejection` (used by the four wrapper sites
that thread caller-specific prose onto `ServiceReflectionResult.rejection`). Audited
`AttemptKind`; dropped `TABLE_METHOD`, `ARGUMENT_NAME`, `FIELD_NAME`. The remaining
candidate-hint producers (`BuildContext:584, 877, 1013`, `FieldBuilder:853`,
`EnumMappingResolver:145, 156`, `TypeBuilder:403, 704`) all funnel into String-carrier
intermediates whose widening was not in Phase D's scope; they are tracked as Phase D-bis above.

### Phase E — preserve `UnknownName` through the nested-rewrap site

`FieldBuilder.classifyChildFieldOnTableType` rewraps a nested `UnclassifiedField` by switching on
the broad sub-arm. On `AuthorError`, the rewrap collapses to `Rejection.structural(prefixed)` and
drops any inner `UnknownName.{attempt, candidates, attemptKind}`. Phase D added a leaf-arm
`prefixedWith(String)` instance method on `Rejection` that preserves typed components by
construction; Phase E rewrites the rewrap to use it. The current site builds a
`prefixed` summary as `parentPrefix + ": " + unc.reason()` (where the parent prefix names the
parent type plus nesting depth); after Phase E the same prefix prepends through `prefixedWith`:

```java
String parentPrefix = "in nested type '" + tableType.name() + "': ";
Rejection rewrapped = unc.rejection().prefixedWith(parentPrefix);
```

The rewrap preserves the inner variant's typed components and re-projects the user-facing prose
under the prefixed summary. The five-arm `switch` in the original spec compiles down to this
single line because `prefixedWith` already implements the leaf-arm projection.

Pipeline-tier coverage: a nested-field unknown-column rejection asserts the rewrapped
`UnclassifiedField`'s rejection still pattern-matches `Rejection.AuthorError.UnknownName` and the
`candidates()` list survives.

### Phase F — lift the directive-conflict producers onto `DirectiveConflict`

Migrate the directive-conflict producer sites onto `Rejection.directiveConflict(directives,
reason)`, threading the conflicting directive names as a typed `List<String>`. Producers:

- `FieldBuilder:2330` (`@service`, `@mutation` mutually exclusive) →
  `directiveConflict(List.of("service", "mutation"), …)`.
- `FieldBuilder.detectChildFieldConflict` / `detectQueryFieldConflict` results: both helpers
  already enumerate the conflicting directive names internally; lift their return type from
  `String` to `Optional<Rejection.InvalidSchema.DirectiveConflict>`.
- `FieldBuilder:1311` (`@notGenerated` is no longer supported) →
  `directiveConflict(List.of("notGenerated"), …)` (single-entry list because the conflict is
  structural-against-presence, not against another directive).
- `FieldBuilder:476` (`@asConnection` on inline non-`@splitQuery` TableField) →
  `directiveConflict(List.of("asConnection", "splitQuery"), …)`.
- `TypeBuilder:326` (`@table` / `@record` / `@error` pairwise mutually exclusive on the
  same type, via `detectTypeDirectiveConflict` at `TypeBuilder:962`) →
  `directiveConflict(present, …)` where `present` is the subset of `{"table", "record", "error"}`
  the conflict-detector enumerated. Lift the helper's return type from `String` to
  `Optional<Rejection.InvalidSchema.DirectiveConflict>` so the typed list reaches the call
  site without a re-parse.

After Phase F, audit remaining `Rejection.invalidSchema(prose)` call sites: any whose prose names
two or more directives is a candidate to migrate. If the audit turns up no producer for
`InvalidSchema.Structural`, collapse the seal and drop the `Structural` arm in the same commit.

### Phase G — emit-block predicate as a capability

The four `SplitRowsMethodEmitter.unsupportedReason(<variant>)` overloads test the same predicate
(`JoinPathEmitter.hasConditionJoin(joinPath())`) and return a `Rejection.Deferred` keyed by an
`EmitBlockReason` value matching variant identity. Validator and emitter both dispatch on that
identity to call the right overload. Lift onto a capability:

```java
public sealed interface ConditionJoinReportable extends GraphitronField
        permits ChildField.SplitTableField, ChildField.SplitLookupTableField,
                ChildField.RecordTableField, ChildField.RecordLookupTableField {

    List<JoinStep> joinPath();
    Rejection.EmitBlockReason emitBlockReason();

    default Optional<Rejection.Deferred> conditionJoinBlock() {
        if (!JoinPathEmitter.hasConditionJoin(joinPath())) return Optional.empty();
        return Optional.of(new Rejection.Deferred(
            getClass().getSimpleName() + " '" + qualifiedName() + "' with a condition-join step "
                + "cannot be emitted until classification-vocabulary item 5 resolves "
                + "condition-method target tables",
            "", new Rejection.StubKey.EmitBlock(emitBlockReason())));
    }
}
```

Each implementing leaf supplies its own `EmitBlockReason`. The four overloads in
`SplitRowsMethodEmitter` collapse to a single
`Optional<Rejection.Deferred> unsupportedReason(BatchKeyField bkf)` that delegates to the
capability:

```java
public static Optional<Rejection.Deferred> unsupportedReason(BatchKeyField bkf) {
    return bkf instanceof ConditionJoinReportable cjr ? cjr.conditionJoinBlock() : Optional.empty();
}
```

The validator's 4-arm `instanceof` chain at `validateVariantIsImplemented` collapses to:

```java
if (field instanceof ConditionJoinReportable cjr) {
    cjr.conditionJoinBlock().ifPresent(d -> emitDeferredError(field, d, errors));
}
```

After Phase G, the `EmitBlockReason` enum becomes derivable from variant identity. Decide
post-Phase-G whether to keep the enum (one value per arm; the typed key for `StubKey.EmitBlock`)
or drop it (variant identity is the natural key, surfaced via `field.getClass()`). Lean toward
keeping for now; re-evaluate when the enum grows past the four condition-join shapes.

### Phase H — collapse `StubKey.None`

`StubKey.None` exists for inline-`Deferred` sites with no variant-class anchor. The arm's javadoc
concedes consumers don't distinguish it from `VariantClass`. Three options for collapsing; decide
post-Phase-D when the producer set is stable:

1. *Nullable variant.* Collapse to `StubKey.{VariantClass(@Nullable Class) | EmitBlock}`. Smallest
   delta; mildly weakens the typed guarantee.
2. *Optional inside the variant.* Collapse to `StubKey.{Variant(Optional<Class<?>>) | EmitBlock}`.
   Same shape, optionalised explicitly.
3. *Force every site to anchor.* Drop the `Rejection.deferred(String)` overload by giving every
   inline-`Deferred` site an explicit anchor (the enclosing variant class plus a planSlug). Largest
   call-site delta; strongest guarantee.

Pick option (3) if the post-Phase-D inline-`Deferred` producer set has at most three or four sites;
option (1) otherwise. The four overloaded `Rejection.deferred(...)` factories collapse to two
regardless: `deferred(summary, planSlug, fieldClass)` and `deferred(summary, planSlug)` (the
`EmitBlock` form keeps its own factory).

### Phase I — extend the lift to validator-side `ValidationError` construction

~33 sites in `GraphitronSchemaValidator` and two sites in
`GraphitronSchemaBuilder.buildRecipeErrors` (lines 594, 597) build
`ValidationError(RejectionKind, ...)` directly. Lift them onto `Rejection`:

```java
// Today
errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR, qualifiedName,
    "Field '" + qualifiedName + "': paginated fields must have ordering …", location));

// After
errors.add(ValidationError.of(qualifiedName,
    Rejection.structural("paginated fields must have ordering …"), location));
```

`ValidationError` either gains a `Rejection`-keyed factory or its constructor widens; existing
direct-`RejectionKind` consumers project through `RejectionKind.of(rejection)`. Byte-stable
validator log surface holds.

The win: a near-miss check the validator adds tomorrow (e.g. PK column name typo on the
`@asConnection` parent guard) automatically gets the typed `UnknownName` shape with a `candidates`
list, by writing `Rejection.unknownColumn(…)` instead of constructing the prose by hand. Today the
validator has no on-ramp to do this.

The federation-recipe rewrap in `GraphitronSchemaBuilder.buildRecipeErrors` (`SchemaProblem` rewrap
into `ValidationError`; both branches at lines 594 and 597) follows the same pattern
(`Rejection.invalidSchema(rewrapped)`) and migrates in the same phase.

---

## Test surface

Existing tests (`RejectionRenderingTest`, `RejectionKindProjectionTest`,
`UnclassifiedFieldValidationTest`, `UnclassifiedTypeValidationTest`, the pipeline tests asserting
on rejection prose) hold by construction at every phase boundary; no rendering-prose drift is in
scope.

New per-phase coverage:

- **Phase D.** Shipped: `R58TypedRejectionPipelineTest` covers `unknownColumn` (FieldBuilder),
  `unknownTypeName` (FieldBuilder `@nodeId(typeName:)`), and `unknownServiceMethod` (the four-wrapper
  prefix-prose path through `prefixedWith`); each asserts the rejection pattern-matches
  `Rejection.AuthorError.UnknownName` with a non-empty `candidates()` list. `RejectionRenderingTest`
  extended with eight model-tier cases for the new factories and `prefixedWith` preservation across
  every leaf.
- **Phase E.** A nested-field unknown-column case (a column miss inside a `NestingField`'s inner
  classification) asserts the rewrapped rejection still pattern-matches `UnknownName` and the
  candidate list survives.
- **Phase F.** A directive-conflict case asserts the rejection pattern-matches
  `Rejection.InvalidSchema.DirectiveConflict` with the typed `directives()` list populated.
- **Phase G.** The capability test asserts `ConditionJoinReportable.conditionJoinBlock()` agrees
  with the emitter's `unsupportedReason` for every implementing variant. The validator's 4-arm
  chain test collapses to one capability test.
- **Phase H.** Whichever option lands gets a `StubKey`-shape test pinning the chosen sealed
  permits.
- **Phase I.** A validator-direct-rule case (e.g. `validatePaginationRequiresOrdering`) asserts the
  resulting `ValidationError`'s underlying `Rejection` is `Rejection.AuthorError.Structural`.

`mvn install -Plocal-db` clean against the existing fixtures suffices for compilation and execution
coverage; no new fixtures needed.

---

## Out of scope

- LSP fix-its consuming `AuthorError.UnknownName.candidates`. R18's plan wires the consumer; this
  plan supplies the typed shape.
- Threading nested rejection chains as a typed `Rejection.NestedReject(outerSite, inner)` arm.
  Phase E preserves the inner *variant* but flattens the chain to a single rejection with a prose
  prefix; full chain typing is heavier and not on any in-flight consumer's path. Defer until
  error-aggregation consumers (LSP, watch-mode) demand it.
- Splitting `Rejection.AuthorError.UnknownName` further by `AttemptKind`. Today every space carries
  `(attempt, candidates)`; if a kind grows arm-specific data later, it splits at that point.
- Watch-mode delta tracker re-keying onto `Rejection`. Falls out naturally once Phase I lands;
  tracked separately under the watch-mode formatter's roadmap surface.
- Lifting `ArgumentRef.UnclassifiedArg.reason` and
  `ArgumentRef.ScalarArg.UnboundArg(attemptedColumnName, reason)` onto `Rejection`. Both are
  rejection-carriers on the argument-classification axis; `UnboundArg` already carries a typed
  `attemptedColumnName` and a candidate set is recoverable from the catalog walk. Same pattern,
  separate axis. Could share the `Rejection` shape but doesn't have to; re-evaluate after Phase D
  lands.
- Lifting `BuildWarning.message` onto a sealed shape. Single production site; premature.
- Renaming `RejectionKind`. With three values and a derived projection role, the name reads
  cleanly enough.
