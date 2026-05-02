---
id: R58
title: "Make the typed `Rejection` hierarchy load-bearing across producers"
status: Spec
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: []
---

# Make the typed `Rejection` hierarchy load-bearing across producers

> R58 Phases 0–C (shipped) replaced the flat `(RejectionKind, String)` pair on
> [`UnclassifiedField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java) /
> [`UnclassifiedType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java)
> with a sealed
> [`Rejection`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java)
> hierarchy and threaded the typed shape through every resolver's `Resolved.Rejected` arm. The
> structural seam is in place; what is missing is producer-side discipline. Most rejection sites
> still construct `Rejection.structural(prose)` even when the typed shape is present at the call
> site, and a parallel taxonomy in the validator bypasses `Rejection` entirely. This plan tracks
> the walk through the producer set so the typed value is actually typed at the call site, not
> flattened back into prose.

---

## Where we are

R58 Phases 0–C landed:

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

## Where we should go

A post-Phase-C audit found six places where the typed shape exists but the producer doesn't
construct it, or where the typed shape over-promises. The diagnostic
([*Sub-taxonomies for resolution outcomes*](../docs/rewrite-design-principles.adoc)): a typed shape
that isn't constructed at the call site is a sub-taxonomy on paper only; the load-bearing value
lives at the producer end.

1. **Most "unknown name" rejections still ship prose.** `AttemptKind` declares twelve values; the
   `Rejection.unknownColumn` / `unknownServiceMethod` / `unknownLifterMethod` factories exist; only
   `unknownTable` has multiple producers. Every other near-miss site
   ([`BatchKeyLifterDirectiveResolver`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/BatchKeyLifterDirectiveResolver.java),
   [`ServiceCatalog`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java),
   [`FieldBuilder`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java),
   [`EnumMappingResolver`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java),
   [`BuildContext`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java),
   [`TypeBuilder`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java)) calls
   `BuildContext.candidateHint(...)`, bakes the `"; did you mean: …"` suffix into a string, then
   wraps as `Rejection.structural(prose)`. Downstream consumers cannot read `attempt` /
   `candidates` because the producers re-stringify upstream.

2. **The nested-rewrap site collapses `UnknownName` to `Structural`.** When
   `FieldBuilder.classifyChildFieldOnTableType` rewraps a nested `UnclassifiedField`, the switch on
   the inner rejection matches the broad `Rejection.AuthorError` arm and discards `attempt` /
   `candidates` / `attemptKind`. Nesting is exactly when an LSP fix-it would still want the typed
   suggestions.

3. **`InvalidSchema.DirectiveConflict` has zero producers.** The arm exists with `directives:
   List<String>` and a factory; no production site constructs one. Genuine directive-conflict sites
   (`@service` + `@mutation` mutually exclusive, `detectChildFieldConflict` /
   `detectQueryFieldConflict` results, `@asConnection` on inline TableField, `@notGenerated`
   removed) all use `Rejection.invalidSchema(prose)`.

4. **`validateVariantIsImplemented` runs a 4-arm `instanceof` chain mirroring a per-variant
   predicate.** `SplitRowsMethodEmitter.unsupportedReason` has four near-identical overloads, each
   running `JoinPathEmitter.hasConditionJoin(joinPath())` and returning a `Rejection.Deferred` keyed
   by an `EmitBlockReason` value matching variant identity. The variant *is* the discriminant;
   validator and emitter both re-run the dispatch. ([*Generation-thinking*](../docs/rewrite-design-principles.adoc):
   "the same multi-arm switch recurs across multiple consumers" is a sign the resolver is
   under-specified.)

5. **`StubKey.None` is a typed distinction with no consumer.** The arm's javadoc concedes that
   validator paths treat `None` and `VariantClass` identically; `None` exists only for hypothetical
   LSP-fix-its that want to jump to the roadmap entry, and no such consumer exists today. Plus four
   overloaded `Rejection.deferred(...)` factories produce a 2x2 matrix where only three combinations
   have producers. ([*Sub-taxonomies for resolution outcomes*](../docs/rewrite-design-principles.adoc):
   "audit at milestone boundaries which sub-taxonomies could collapse now that their forcing
   functions are visible.")

6. **Validator-side `ValidationError` construction bypasses `Rejection`.** ~30 sites in
   [`GraphitronSchemaValidator`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)
   and one site in `GraphitronSchemaBuilder.buildRecipeErrors` build `ValidationError(RejectionKind,
   ...)` directly. Two parallel taxonomies for the same concept; a near-miss check the validator
   adds tomorrow has no on-ramp to typed `UnknownName`.

The shape of the work: where R58 Phases A/B lifted *the carrier*, this plan lifts *the producers*.
Each phase below is independently shippable; the byte-stable validator log surface holds at every
boundary.

---

## Phasing

### Phase D — lift the candidate-hint producers onto `UnknownName`

Move every `BuildContext.candidateHint(attempt, candidates)`-using producer onto a typed
`AuthorError.UnknownName` factory. The Levenshtein sort and `"; did you mean: …"` formatting
already live on `Rejection`'s private renderer (mirrored from `BuildContext.candidateHint` for
byte-stability); the migration replaces the prose-with-baked-in-suffix call with a factory call.

Producers to migrate (each carries `(attempt, candidates)` typed at the construction site today):

- `BatchKeyLifterDirectiveResolver:179` (target column not on table) → `Rejection.unknownColumn`.
- `BatchKeyLifterDirectiveResolver:237` (lifter method not on class) → `Rejection.unknownLifterMethod`.
- `ServiceCatalog:185, 373, 469` (service method not on class) → `Rejection.unknownServiceMethod`.
- `FieldBuilder:853` (column on filter table) → `Rejection.unknownColumn`.
- `FieldBuilder:2799` (typename in `@nodeId`) → new `Rejection.unknownTypeName` factory.
- `FieldBuilder:2843, 2873` (column on FK-resolved table) → `Rejection.unknownColumn`.
- `EnumMappingResolver:156` (enum constant not on Java class) → new
  `Rejection.unknownEnumConstant` factory.
- `BuildContext:584` (FK SQL name) → `Rejection.unknownForeignKey`.
- `BuildContext:877` (typename in `@nodeId`) → `Rejection.unknownTypeName`.
- `BuildContext:1013` (column in path leg) → `Rejection.unknownColumn`.
- `TypeBuilder:403` (key column in `@node`) → new `Rejection.unknownNodeIdKeyColumn` factory.
- `TypeBuilder:704` (input field column) → `Rejection.unknownColumn`.
- `MutationInputResolver.DmlKindResult.Unknown` (raw `@mutation(typeName:)` value) → new
  `Rejection.unknownDmlKind` factory; the candidate set is the `DmlKind` enum's values.

`BuildContext:594` (path source-table name resolved against a 2-element list) is technically a
`candidateHint` site but the candidate set is hand-rolled, not cataloged; treat as `Structural`,
not `UnknownName`. Document the exception as a comment at the call site.

Add factories: `unknownTypeName`, `unknownEnumConstant`, `unknownNodeIdKeyColumn`, `unknownDmlKind`.
After migration, audit `AttemptKind`: drop values without a producer. Today's unused candidates
are `TABLE_METHOD`, `ARGUMENT_NAME`, `FIELD_NAME` unless a producer surfaces during migration; the
post-migration enum is the closed set of lookup spaces the catalog actually serves.

The point of Phase D is not the rendered prose (byte-stable by construction) but threading typed
candidate lists out of the producer site. Phase D unlocks the LSP fix-it shape R18 plans to consume
without R18 having to re-derive candidates by re-running the classifier.

### Phase E — preserve `UnknownName` through the nested-rewrap site

`FieldBuilder.classifyChildFieldOnTableType` rewraps a nested `UnclassifiedField` by switching on
the broad sub-arm. On `AuthorError`, the rewrap collapses to `Rejection.structural(prefixed)` and
drops any inner `UnknownName.{attempt, candidates, attemptKind}`. Lift the rewrap to the leaf-arm
switch:

```java
Rejection rewrapped = switch (unc.rejection()) {
    case Rejection.AuthorError.UnknownName u ->
        new Rejection.AuthorError.UnknownName(prefixed, u.attemptKind(), u.attempt(), u.candidates());
    case Rejection.AuthorError.Structural ignored      -> Rejection.structural(prefixed);
    case Rejection.InvalidSchema.DirectiveConflict d   ->
        new Rejection.InvalidSchema.DirectiveConflict(d.directives(), prefixed);
    case Rejection.InvalidSchema.Structural ignored    -> Rejection.invalidSchema(prefixed);
    case Rejection.Deferred d -> new Rejection.Deferred(prefixed, d.planSlug(), d.stubKey());
};
```

The rewrap preserves the inner variant's typed components and re-projects the user-facing prose
under the prefixed summary. Same shape as Phase A's existing handling for `Deferred` (planSlug +
stubKey already preserved); Phase E generalises that pattern to the other two top-level arms.

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

~30 sites in `GraphitronSchemaValidator` and one site in `GraphitronSchemaBuilder.buildRecipeErrors`
build `ValidationError(RejectionKind, ...)` directly. Lift them onto `Rejection`:

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
into `ValidationError`) follows the same pattern (`Rejection.invalidSchema(rewrapped)`) and migrates
in the same phase.

---

## Test surface

Existing tests (`RejectionRenderingTest`, `RejectionKindProjectionTest`,
`UnclassifiedFieldValidationTest`, `UnclassifiedTypeValidationTest`, the pipeline tests asserting
on rejection prose) hold by construction at every phase boundary; no rendering-prose drift is in
scope.

New per-phase coverage:

- **Phase D.** A pipeline test per migrated factory class asserts that an unknown-name rejection
  on a representative SDL site carries `Rejection.AuthorError.UnknownName` with a non-empty
  `candidates()` list, not just the rendered hint in prose.
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
