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

> R58 Phases 0–I (all shipped) replaced the flat `(RejectionKind, String)` pair on
> [`UnclassifiedField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java) /
> [`UnclassifiedType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java)
> /
> [`ValidationError`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ValidationError.java)
> with a sealed
> [`Rejection`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java)
> hierarchy and threaded the typed shape from every producer site through every consumer. The
> deeper carrier widenings that surfaced during Phase D's execution (`ParsedPath.errorMessage`,
> `InputFieldResolution.Unresolved.reason`, `ArgumentRef.UnboundArg.reason`, the joined-string
> aggregation patterns in `EnumMappingResolver` and `TypeBuilder`) are tracked separately as
> [R66](rejection-string-carrier-widening.md): the producer factories already exist; the carrier
> widenings just need their own cycle.

---

## Phases (all shipped)

- **Phase 0** (commit `7c10226b`): dropped `RejectionKind.INTERNAL_INVARIANT`; the single producer
  site at `FieldBuilder.classifyChildFieldOnTableType`'s nested-fields fallthrough became an
  `AssertionError`.
- **Phase A** (commit `09541ed5`): introduced the sealed
  [`Rejection`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java)
  hierarchy (top-level `AuthorError | InvalidSchema | Deferred`, sub-sealed
  `AuthorError.{UnknownName | Structural}` and `InvalidSchema.{DirectiveConflict | Structural}`,
  `StubKey.{VariantClass | EmitBlock | None}`, `EmitBlockReason` enum, factory statics,
  self-contained `candidateHint` renderer). `UnclassifiedField` carries `Rejection rejection`
  instead of `(RejectionKind kind, String reason)`; every rejection-producing resolver's
  `Resolved.Rejected` arm widened to carry `Rejection`.
- **Phase B** (commit `5d29a3d3`): mirrored the lift onto `UnclassifiedType`. The 24 construction
  sites (21 in `TypeBuilder`, 3 in `EntityResolutionBuilder`) migrated; three table-resolution
  sites construct `AuthorError.UnknownName` via `Rejection.unknownTable`.
- **Phase C** (commit `68a062c0`): `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` renamed to
  `STUBBED_VARIANTS` and widened to `Map<Class, Rejection.Deferred>`;
  `SplitRowsMethodEmitter.unsupportedReason` overloads lifted to `Optional<Rejection.Deferred>`
  keyed by `EmitBlockReason`.
- **Phase D**: walked the direct candidate-hint producers onto typed `AuthorError.UnknownName`
  factories (`BatchKeyLifterDirectiveResolver`, `ServiceCatalog` via widening
  `ServiceReflectionResult.failureReason: String → rejection: Rejection`, `FieldBuilder` for
  `@nodeId(typeName:)` / column-on-FK-resolved-table / scalar-column-miss /
  `DmlKindResult.Unknown`). Added factories `unknownTypeName`, `unknownEnumConstant`,
  `unknownNodeIdKeyColumn`, `unknownDmlKind` and the leaf-arm `prefixedWith(String)` instance
  method (used by the four wrapper sites that thread caller-specific prose onto
  `ServiceReflectionResult.rejection`). Audited `AttemptKind`; dropped `TABLE_METHOD`,
  `ARGUMENT_NAME`, `FIELD_NAME`.
- **Phase E**: replaced the nested-rewrap switch in `FieldBuilder.classifyChildFieldOnTableType`
  with a single `unc.rejection().prefixedWith(parentPrefix)` call; the inner variant's typed
  components survive the rewrap rather than collapsing to `Structural`.
- **Phase F**: lifted `detectChildFieldConflict`, `detectQueryFieldConflict`, and
  `detectTypeDirectiveConflict` from `String` to `Rejection.InvalidSchema.DirectiveConflict`;
  migrated explicit conflict sites (`@service`+`@mutation`, `@notGenerated`,
  `@asConnection`+`@splitQuery`, `@asConnection`+`@lookupKey` at `LookupKeyDirectiveResolver`).
  `InvalidSchema.Structural` retains 5 producers (root invariants, Connection-at-root for
  `@tableMethod`, single-cardinality `@lookupKey`, circular type, `@error` field shape) so the
  seal stays.
- **Phase G**: introduced the
  [`ConditionJoinReportable`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ConditionJoinReportable.java)
  capability (unsealed, mirrors `BatchKeyField`'s shape). The four `ChildField` variants that
  share the condition-join predicate (`SplitTableField`, `SplitLookupTableField`,
  `RecordTableField`, `RecordLookupTableField`) implement it with their per-variant
  `EmitBlockReason` and `displayLabel()`. The four `unsupportedReason` overloads collapsed to one
  capability dispatch; the validator's 4-arm `instanceof` chain collapsed to a single
  `instanceof ConditionJoinReportable` check.
- **Phase H**: collapsed `StubKey.None` (option 1, *Nullable variant*) since the post-Phase-D
  inline-`Deferred` producer set is exactly 3 sites without natural variant-class anchors. The
  seal is now `StubKey.{VariantClass(@Nullable Class) | EmitBlock}`; the four overloaded
  `Rejection.deferred(...)` factories collapsed to two: `deferred(summary, planSlug, fieldClass)`
  and `deferred(summary, planSlug)`.
- **Phase I**: lifted `ValidationError` from `(RejectionKind kind, String coordinate, String
  message, SourceLocation location)` to `(String coordinate, Rejection rejection,
  SourceLocation location)` with `kind()` and `message()` projecting from the rejection. All 33
  sites in `GraphitronSchemaValidator`, the 2 sites in `GraphitronSchemaBuilder.buildRecipeErrors`,
  and the watch-mode test fixture migrated. `validateUnclassifiedField` /
  `validateUnclassifiedType` / `emitDeferredError` use `prefixedWith` to preserve the typed
  variant under the validator's per-site prose prefix.

## Tests

`R58TypedRejectionPipelineTest` (8 cases) covers the migrated producers end-to-end:
`unknownColumn` (direct + nested-rewrap survival), `unknownTypeName`, the
`unknownServiceMethod` four-wrapper prefix path, the directive-conflict cases (`@service`+
`@mutation` and `@table`+`@record`), the `ConditionJoinReportable` capability seal, and the
validator-side `UnknownName` survival through `prefixedWith` onto `ValidationError`.
`RejectionRenderingTest` extended with eight model-tier cases for the new factories and
`prefixedWith` preservation across every sealed leaf. Existing tests
(`RejectionKindProjectionTest`, the pipeline tests asserting on rejection prose) hold by
construction at every phase boundary.

## Follow-up

[R66 — Widen string-carrier intermediates onto Rejection](rejection-string-carrier-widening.md)
tracks the deeper carrier widenings whose producers Phase D could not migrate without changing
intermediate carriers (`ParsedPath.errorMessage`, `InputFieldResolution.Unresolved.reason`,
`ArgumentRef.UnboundArg.reason`, the joined-string aggregation patterns in `EnumMappingResolver`
and `TypeBuilder`). The Phase D factories (`unknownForeignKey`, `unknownTypeName`,
`unknownEnumConstant`, `unknownNodeIdKeyColumn`, `unknownColumn`) are already in place; R66
just lifts the carriers so the typed values reach a `Rejection` consumer.

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
