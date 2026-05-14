---
id: R159
title: Root-value sigil $source on @field(name:) for carrier-payload sourcing
status: In Review
bucket: structural
priority: 2
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-14
---

## What shipped (Implementation pass, 2026-05-14; revised after self-review)

- `FieldSourceSigil` utility (`graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldSourceSigil.java`): sealed `FieldNameRef = BareName | UpstreamRoot`, `ParseResult = Absent | Ok | UnknownSigil`, `SiteContext = CarrierDataField | Other`, the three sigil-aware canonical message accessors (`unknownSigilMessage`, `sourceSigilNotDefinedHereMessage`, `typeMismatchMessage`), the `sourceSigilDefinedAt(SiteContext)` predicate, and the `sourceSigilTypeMatches` type-match predicate. The utility owns the three sigil-aware rejection messages; classifier, LSP completion, and LSP diagnostic all route through it. (Scope note: bare-name `@field(name: "X")` at the carrier site keeps the long-standing forbidden-directive text in `classifyCarrierField` and is outside this utility, since it is not sigil-aware and serves authors writing pre-R159 bare-name `@field` at this site.)
- `CarrierFieldRole.DataChannel` gains a `boolean sourceSigil` component carrying the carrier walk's parse decision into the model. The downstream type-match consumer reads the bit instead of re-parsing the SDL directive (single-source-of-truth at parse time per § "Classification belongs at the parse boundary").
- `BuildContext.classifyCarrierField` parses `@field(name:)` via `FieldSourceSigil.parseArgFieldNameRef` before the forbidden-directive loop. `UpstreamRoot` lifts `@field` off the forbidden list for this iteration (admitting the directive) and threads `sourceSigil = true` into the emitted `DataChannel`; `UnknownSigil` HardRejects with `FieldSourceSigil.unknownSigilMessage`; `BareName` / `Absent` flow into the existing forbidden-directive loop unchanged.
- `FieldBuilder.classifyMutationField` (the `@service` `Resolved.Result` arm) runs `checkSourceSigilTypeMatch` against the producer's `MethodRef.returnType()`. The check fires here, not in `classifyCarrierField`, because the carrier walk is shape-only and doesn't have the producer's `MethodRef` in scope; the colocation principle is preserved by the shared `FieldSourceSigil.sourceSigilTypeMatches` callable. The consumer reads `DataChannel.sourceSigil()` (no reparse). Rejection flows through `UnclassifiedField` → `ValidationError` per the documented path. (The `@mutation`-DML carrier paths route through the existing `MutationInputResolver` envelope unchanged.)
- LSP snapshot extension: `LspSchemaSnapshot.Built.carrierDataFieldByType: Map<String, String>` (carrier-type-name → data-field-name) is the underlying data for `Built.siteContext(typeName, fieldName) -> FieldSourceSigil.SiteContext`. LSP consumers (FieldCompletions admit, Diagnostics not-defined-here overlay) route through `siteContext()` + `FieldSourceSigil.sourceSigilDefinedAt(...)` rather than reading the underlying map themselves, so the per-site predicate has one source of truth that broadens with one sealed-method edit. `CatalogBuilder.projectCarrierDataFields` populates the map by walking `GraphitronSchema.fields()` for `ChildField.SingleRecord*` permits.
- LSP `FieldCompletions.completionsFor` admits `$source` as a top-level completion when `built.siteContext(typeName, fieldName)` returns `CarrierDataField`. At every other site, completions stay as today.
- LSP `Diagnostics.validateFieldMember` emits `FieldSourceSigil.sourceSigilNotDefinedHereMessage` when `@field(name: "$source")` is at a site whose `siteContext()` is `Other` AND the parent's TypeBackingShape is known. Snapshot-uncertainty: when the parent has no entry in `typesByName`, the diagnostic stays silent (the LSP defers to the build).
- Tests:
  - `FieldSourceSigilPipelineTest`: pipeline-tier coverage for the classifier-side fixtures (admit / type-mismatch / unknown-sigil / bare-name regression / model-shape regression / non-carrier-site regression) and the same fixtures' validator-surfaced messages (one assertion per case: `ValidationReport.errors()` contains the canonical `FieldSourceSigil` text). The previously-separate `FieldSourceSigilValidationTest` was merged in to avoid duplicate fixtures across two pipeline-tier files.
  - `FieldCompletionsTest` (3 R159 cases): completion at carrier data field / absent at non-carrier site / snapshot-uncertainty silent.
  - `DiagnosticsTest` (3 R159 cases): admitted-site silent / non-carrier-site emits canonical message / snapshot-uncertainty silent.
- Full build green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`.

### Deviation notes from spec

The spec's Cross-surface invariant § step 1 wording "`BuildContext.classifyCarrierField` invokes `argFieldNameRef` and `sourceSigilTypeMatches`" was aspirational — the type-match check cannot fire in `classifyCarrierField` because the producer's `MethodRef` is bound at `FieldBuilder.classifyMutationField` time, not at the carrier walk's TypeBuilder / MutationInputResolver / GraphitronSchemaBuilder invocations (which are shape-only). The shipped split:

1. `classifyCarrierField` runs `argFieldNameRef` to admit the directive and threads the parse decision into `DataChannel.sourceSigil`.
2. `FieldBuilder.checkSourceSigilTypeMatch` runs `sourceSigilTypeMatches` at the consumer site (where the producer is known), reading `DataChannel.sourceSigil` rather than reparsing.

The integrity property the spec calls out (shared callable owns the canonical messages) holds: both sites route through `FieldSourceSigil`. The execution-tier test (gated on R158) will exercise the producer-side end-to-end.

The spec's note on the LSP carrier-data-field detection "may live in the snapshot or in a sibling helper" landed as a snapshot-method (`Built.siteContext`) backed by a `Map<String, String> carrierDataFieldByType` projection, populated by walking the classifier's `ChildField.SingleRecord*` outputs. LSP consumers route through `siteContext()` + `sourceSigilDefinedAt(...)` rather than reading the underlying map; the per-site predicate has one source of truth that broadens with one sealed-method edit. No new sealed permit on `TypeBackingShape`.

# Root-value sigil `$source` on `@field(name:)` for carrier-payload sourcing

## What this ships

A single new accepted value for `@field(name:)`: the literal `$source`. When the directive carries this value, the SDL field binds to the upstream Java value at this field's site, taken as a whole. Bare-name `@field(name: "X")` is unchanged.

This resolves R158's `OpprettRegelverksamlingPayload` runtime cast failure by giving the schema author an explicit, name-decoupled way to route a carrier-payload data field to the producer's reflected return. Today's classifier reaches this binding implicitly — the carrier walk produces a `DataChannel(fieldName, DataElement)` arm from the SDL element type alone, and the producer's reflected return rides through without an explicit `@field` annotation. R159 makes the binding nameable so R158 has a contract to consume.

R159 ships nothing else. No `$errors` (R12's error-channel binding is structural + positional + accessor-by-SDL-name; no current consumer needs a name-decoupled error binding). No `$context` (no consumer item). No dotted paths (`@field(name:)` is single-segment today and R159 does not lift the restriction). No multi-step paths in any form.

## Motivating gap

```graphql
extend type Mutation {
    opprettRegelverksamling(input: [OpprettRegelverksamlingInput!]!): OpprettRegelverksamlingPayload! @service(...)
}

type OpprettRegelverksamlingPayload {
    regelverksamling: [Regelverksamling!]
}
```

```java
public List<RegelverksamlingRecord> opprettRegelverksamling(List<OpprettRegelverksamlingInput> inputs);
```

`List<RegelverksamlingRecord>` is the producer's reflected return; `[Regelverksamling!]` is the SDL field. Today's classifier admits this shape via the carrier walk's element-type match: `Regelverksamling` resolves to a `TableBackedType` so the data field gets `DataChannel(regelverksamling, DataElement.Table(...))`, with the `@service` producer's reflected return flowing through `ServiceCatalog.reflectServiceMethod` separately. The binding is implicit and the downstream `SingleRecordTableField/MANY` fetcher's `(Result<RecordN<...>>) env.getSource()` cast then fails at runtime because the carrier walk's downstream emitter assumed a DML producer. R158 owns the consumer-side fix; R159 gives R158 a stable reference to consume.

## The mechanism

### Parser

Sigil parsing is interposed at the one site that admits it, not at every directive read. A new helper `argFieldNameRef(field, DIR_FIELD, ARG_NAME)` returns `Optional<FieldNameRef>` where `FieldNameRef` is a sealed `{BareName(String value) | UpstreamRoot}`:

- `@field(name: "$source")` produces `FieldNameRef.UpstreamRoot`.
- Any non-`$`-prefixed value produces `FieldNameRef.BareName(value)`.
- Any `$`-prefixed value other than exactly `$source` rejects at parse time with `"Unknown sigil '$X'; allowed: $source"`.

The helper is called from the one site that admits `UpstreamRoot`: `BuildContext.classifyCarrierField` (the per-field arm of `tryResolveSingleRecordCarrier`'s walk), interposed before the existing forbidden-directive `HardReject` check at `BuildContext.java:622-633`. The carrier walk currently lists `DIR_FIELD` in the `HardReject` set unconditionally; R159 changes that arm to consult `argFieldNameRef` first. When the helper returns `UpstreamRoot`, the carrier walk runs `sourceSigilTypeMatches` (§ Type matching) against the producer's `MethodRef` and either admits or emits the type-mismatch `HardReject`. The behavioural table after R159:

- `@field(name: "$source")` on the carrier data field, type matches: admit (sigil parses to `UpstreamRoot`; the directive is a no-op confirmation of the implicit binding the carrier walk already produces).
- `@field(name: "$source")` on the carrier data field, type mismatches: `HardReject` with the canonical `FieldSourceSigil` type-mismatch message.
- `@field(name: "X")` non-sigil on the carrier data field: continues to `HardReject` with the existing forbidden-directive message (bare-name `@field` on this site has never had a meaning).
- `@field(name: "$X")` unknown sigil on the carrier data field: rejects at parse time with the `argFieldNameRef` message, which fires before the forbidden-directive `HardReject` (so the author sees "Unknown sigil" rather than "forbidden directive").

The other eight existing `argString(field, DIR_FIELD, ARG_NAME)` call sites (`EnumMappingResolver` x2, `TypeBuilder.328`, `FieldBuilder.1097`, `FieldBuilder.3280`, `FieldBuilder.3300`, `FieldBuilder.4331`, `BuildContext.1385`) are not changed. Those sites continue to call `argString`, which returns the raw string including any `$`-prefixed text without parsing it; the new `argFieldNameRef` helper is a sibling, not a replacement, so a future refactor that rewrites `argString` to delegate through `argFieldNameRef` (and thereby silently rewire all eight sites to learn about sigils) is incorrect by construction of the two-helper split. A schema author who writes `@field(name: "$source")` at one of the eight sites surfaces today's existing "no such accessor / column / enum value" rejection unchanged, on the literal string `$source`. R159 does not add eight mechanical "I never accepted sigils" arms; the sites that never accepted sigils stay structurally identical to today, and the principle "Directives carry only what the SDL author needs to say" applies: the site already disambiguates whether sigils are meaningful.

### Classifier model output

The classifier emits no new model arm. `CarrierFieldRole.DataChannel(String fieldName, DataElement element)` keeps today's shape; the `DataElement.Table` / `DataElement.Record` split already carries the upstream element type the binding resolves to. `@field(name: "$source")` is a parse-time admit, not a model reshape: the directive at the carrier data field site is an explicit confirmation of the implicit binding the existing carrier walk already produces from the SDL element type.

R158's reader-variant work consumes the producer-kind axis (DML vs `@service`) it introduces on `SourceKey.Reader`, not a `$source`-vs-bare distinction. R159 does not preempt that split.

The earlier draft proposed a `Binding.{NamedAccessor | UpstreamRoot}` sub-permit on `DataChannel`; that was dropped because the carrier walk admits no bare-name accessor resolution today (`classifyCarrierField` matches the element by SDL type only), so `NamedAccessor` would have no producer. Per § "Sub-taxonomies for resolution outcomes": a sub-taxonomy whose siblings can't be distinguished by any current producer is a field, not a permit.

### Sites where `$source` is defined

R159 admits `$source` at exactly one site: the carrier-payload data field on a `@service`-backed producer (the R158 admit). At every other site — including any field whose parent classifies as `TypeBackingShape.RecordBacking`, `PojoBacking`, `JooqRecordBacking.{WithTable | Standalone}`, `TableBacking`, or any sub-permit of `NoBacking` (`Root`, `UnclassifiedInterface`, `UnbackedResult` per `TypeBackingShape.java:108-134`) — the classifier does not admit the sigil.

The `sourceSigilDefinedAt(SiteContext)` predicate (§ Cross-surface invariant) returns `true` only for the one admitted site today. Consumers (classifier admit, LSP completion, LSP diagnostic) ask one question and get one answer; broadening admit in a future item is a single-return-value change.

`@field(name: "$source")` at any non-admitted site continues to surface today's existing classification rejection on the literal string `$source`: `Rejection.unknownColumn("$source")` at the table-backed paths (`FieldBuilder.java:4364-4386`) or `Rejection.accessorMismatch("...$source...")` at the record-backed paths (`FieldBuilder.java:3283-3286`). The build's rejection at those sites stays generic; the LSP overlays the canonical "`$source` is not defined at this site" diagnostic via the AST-level arm in § LSP, but the classifier does not rewire those paths in R159.

`@enum` and `@scalar` types don't surface as parents in the projection at all; the carrier walk rejects them upstream.

A `@splitQuery` child resolves through the projection like any other child; R159 does not special-case it.

### Type matching

`@field(name: "$source")` binds the upstream Java value to the SDL field. At admission (the one site: the carrier-payload data field), the classifier checks the producer's reflected return type against the SDL field's element type by the following predicate:

- **`@table`-backed SDL element type**: require exact equality of the source type with the bound record class. List-wrapping is preserved on both sides (`List<XRecord>` source matches `[X!]` SDL element-of-list).
- **Domain-object SDL element type** (`@record` / POJO backing): require Java assignability of the source type to the backing class.

Interface element types are out of scope; the lub-of-implementers case is filed alongside the other future admit sites in § Out of scope.

This is a *new* classifier check, not a refactor of an existing one: today's carrier walk in `BuildContext.classifyCarrierField` matches the data element by SDL type only (`TableBackedType` or `ResultType.fqClassName != null` at `BuildContext.java:600-603`); the Java-type match against the `@service` producer's reflected return happens separately in `ServiceCatalog.reflectServiceMethod` and is consumed elsewhere. R159 adds the predicate as a callable classifier-side check colocated with the sigil admission, so the unknown-Java-type case surfaces as a typed `Rejection` at classify time rather than a runtime cast failure downstream.

**Data-flow boundary**: the predicate reads `ServiceCatalog`'s already-published `MethodRef` (the structural Java return shape `ServiceCatalog.reflectServiceMethod` reflected once at parse time per § "Classification belongs at the parse boundary"), *not* raw `java.lang.reflect.Type`. R159 introduces no new reflection read; it consumes the catalog's pre-classified return type.

### Internal-representation invariant

`$source` binds the post-decode, pre-encode Java value of the upstream. Wire-format translators (`@nodeId` encode, enum textmap, custom scalar binding) run between the Java value and the GraphQL wire, transparent to the binding. The type-matching predicate compares Java types, not wire forms, by construction. This is the precondition the predicate's correctness rests on; it holds by construction today because no model value carries a wire-form translation, per `rewrite-design-principles.adoc § "Wire-format encoding is a boundary concern"`.

## Load-bearing classifier checks

R159 introduces no new load-bearing classifier checks. The classifier's two new rejections at the carrier data field (unknown sigil, `$source` type mismatch) and the LSP's one new AST diagnostic at other sites (`$source` not defined here) are hygiene: they fail authors fast but no emitter today branches on their *acceptance* to read narrower shapes. Per § "Classifier guarantees shape emitter assumptions": producers without consumers are allowed but should not be declared with `@LoadBearingClassifierCheck`; the annotation is reserved for shapes an emitter actually relies on.

The natural home for a `$source`-pinned load-bearing key is R158's reader-variant work, where an emitter casts to a specific upstream Java type. R158 owns the producer / consumer pair for the cast-shape guarantee it relies on (one key per reader variant, per R158's own spec direction). R159 keeps the surface narrow: no future-proofing keys without the consumer in trunk.

The existing `single-record-carrier-shape.roles-exhaustively-classified` key remains in place; R159 does not change `DataChannel`'s permit shape, so its existing producer (`BuildContext.tryResolveSingleRecordCarrier`, `BuildContext.java:497`), its existing consumers (`GraphitronSchemaBuilder.registerCarrierDataField` at line 274 and `TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher` at line 3874), and the live audit test `CarrierFieldRoleCoverageTest` cover the new `$source` admission unchanged.

## Cross-surface invariant

The original "validator mirror" framing (one classifier rejection ↔ one validator dispatch arm) does not apply here: R159 admits no new dispatch set, so the validator has nothing to mirror in the dispatch-arm sense. The rejections emerge from the classifier's carrier walk and reach the validator's output (and through it the LSP) over the existing `UnclassifiedField` route. No new arm is added to `GraphitronSchemaValidator`.

The mirror this item satisfies is the *rejection-text mirror*: the three sigil-aware rejection messages (unknown sigil, not-defined-here, type mismatch) live on the shared `FieldSourceSigil` utility, and every consumer (classifier HardReject, LSP diagnostic, LSP completion's keep-out predicate) routes through it. A message tweak in one consumer updates all three surfaces by construction.

The path:

1. `BuildContext.classifyCarrierField` invokes `argFieldNameRef` and `sourceSigilTypeMatches`. If either fails, the carrier walk emits `CarrierFieldClassification.HardReject` carrying the canonical message from `FieldSourceSigil` (replacing the existing forbidden-directive `HardReject` only when the value is `$source`; see `BuildContext.java:622-633`).
2. `tryResolveSingleRecordCarrier` packages the `HardReject` into `SingleRecordCarrierResolution.Rejected` (`BuildContext.java:543-544`).
3. `MutationInputResolver` wraps the rejection text with the `"@mutation(typeName: X) return type 'Y': <reason>; or author a carrier with @record(...)"` envelope (`MutationInputResolver.java:178-181, 209-214`).
4. `FieldBuilder` converts the wrapped rejection into an `UnclassifiedField` carrying `Rejection.structural(message)` (`FieldBuilder.java:2930-2931`).
5. `GraphitronSchemaValidator.validateUnclassifiedField` surfaces the stored `Rejection` as a `ValidationError` on `ValidationReport.errors()` (`GraphitronSchemaValidator.java:893-898`).
6. The LSP's `Diagnostics.compute` calls `validatorDiagnostics(report)`, which iterates `report.errors()` (`Diagnostics.java:159, 195-211`) and turns each into an LSP diagnostic at the artifact's coordinate. No LSP-side rewiring needed for the validator-surfaced cases.

The integrity property is preserved by the shared `FieldSourceSigil` utility: the three sigil-aware rejection messages — unknown sigil, `$source` not defined here, `$source` type mismatch — live there, so a refactor that changes a message in one consumer (say, the LSP's AST diagnostic) silently changes it at every other (the validator's surfaced text, the classifier's HardReject). The bare-name `@field(name: "X")` rejection at the carrier site is not sigil-aware and continues to use the existing forbidden-directive text in `classifyCarrierField` (outside this utility). The three shared callables are:

- `argFieldNameRef(field, DIR_FIELD, ARG_NAME) -> Optional<FieldNameRef>` — parse helper, producer of the unknown-sigil rejection text.
- `sourceSigilDefinedAt(SiteContext) -> boolean` — predicate over a R159-narrow site set (carrier data field today; one return value to change when a future item broadens admit). Producer of the canonical "not defined here" rejection text via a sibling accessor `sourceSigilNotDefinedHereMessage()`.
- `sourceSigilTypeMatches(JavaType sourceType, SdlElementType sdlElement) -> Optional<Rejection>` — type-matching predicate at admission. Producer of the type-mismatch rejection text.

`SiteContext` encapsulates whatever the predicate inspects today (the boolean "is this a carrier-payload data field on a `@service`-backed producer"); R159 keeps it abstract so future items broaden the predicate by adding inputs rather than reshaping the call sites. Implementation pinned the LSP-side carrier-data-field detection to the snapshot: `LspSchemaSnapshot.Built.siteContext(typeName, fieldName) -> SiteContext` is the one entry point LSP consumers use, backed by a `Map<String, String> carrierDataFieldByType` projection populated from the classifier's `ChildField.SingleRecord*` outputs.

Shared-callables coverage lives in `FieldSourceSigilPipelineTest`: each rejection case asserts both the classifier-side `UnclassifiedField.rejection().message()` and the validator-surface `ValidationReport.errors()` from the same fixture, covering classifier production + `UnclassifiedField` propagation + validator surfacing without duplicating SDL across two pipeline-tier files.

## LSP

`Diagnostics` and `FieldCompletions` consume the same `FieldSourceSigil` callables the classifier uses.

`FieldCompletions.generate` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/FieldCompletions.java`, lines 71-87) gets a new arm: when `sourceSigilDefinedAt(siteContext)` returns true for the enclosing field, the suggestion list prepends `$source` as a top-level value. With R159's narrow predicate, the only site where this fires is a carrier-payload data field on a `@service`-backed producer. At every other site, completions stay as today (column / accessor names from the parent's `TypeBackingShape`, or empty for `NoBacking` and `JooqRecordBacking.Standalone`).

`Diagnostics.validateFieldMember` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java`, lines 502-527) gets a new arm: when `@field(name:)`'s argument is the literal `$source` and `sourceSigilDefinedAt(siteContext)` returns false, the LSP emits the canonical `FieldSourceSigil.sourceSigilNotDefinedHereMessage()` diagnostic at the directive's coordinate. This overlays a clearer message at the LSP layer than the build's eventual `accessorMismatch("$source")` / `unknownColumn("$source")` rejection at those sites; the build error is unchanged. The unknown-sigil case (`@field(name: "$bogus")` at the carrier data field) and the type-mismatch case are covered by validator-surfacing through `Diagnostics.validatorDiagnostics`; the LSP forwards them without re-deriving.

**Snapshot-uncertainty semantics.** The LSP's `typesByName` projection from R157 is built per snapshot and includes only types whose parent has been classified. For mid-edit snapshots where the parent SDL type has just been renamed and the carrier-data-field detection cannot be resolved, the LSP arm reads this absence as "shape unknown" and is silent: it neither offers `$source` as a completion nor emits the undefined-at-site diagnostic. "Shape unknown" is a fourth observable outcome alongside the predicate's two and the missing-directive case, surfacing only on the LSP side where classification can lag the parse.

## Tests

Pipeline-tier (`BuildContext` end-to-end through `ValidationReport`):

- `$source` admit on an OpprettRegelverksamlingPayload-shaped fixture: assert the carrier walk produces `DataChannel(fieldName, DataElement.Record(...))` with the directive admitted, identical in shape to the same fixture without the directive.
- `$source` type-mismatch reject (the `@service` producer's reflected return type doesn't match the SDL element's backing class): assert `ValidationReport.errors()` contains the canonical `FieldSourceSigil` type-mismatch message.
- Unknown-sigil reject (`@field(name: "$bogus")` at the carrier data field): assert the message names `$source` as the only allowed sigil and that the new parse-time arm fires before the existing `DIR_FIELD` `HardReject` (so the author sees the parse-time message, not the forbidden-directive one).
- Bare-name `@field(name: "X")` on the carrier data field continues to `HardReject` with the existing forbidden-directive message: regression that the sigil-aware arm does not relax the long-standing rejection for non-sigil values at this site.
- Carrier walk model-shape regression: fixtures that go through today's implicit element-type binding (no `@field` directive) continue to produce `DataChannel(fieldName, DataElement.Record(...))` / `DataChannel(fieldName, DataElement.Table(...))` byte-identical to today, confirming R159 reshapes no existing fixture.
- Non-carrier-site behaviour regression: `@field(name: "$source")` on a `@record`-backed field and on a `@table`-backed field continues to surface today's `Rejection.accessorMismatch("$source")` / `Rejection.unknownColumn("$source")` unchanged — R159 does not silently rewire the non-carrier paths to learn about sigils.

Validator-surface coverage lives in `FieldSourceSigilPipelineTest` rather than a sibling per-aspect file: each of the three rejection cases (unknown sigil, type mismatch, non-carrier-site accessor-mismatch) carries a `validate(schema)` assertion alongside its classifier-side assertion, reusing one fixture per case. The per-aspect-validation framing is preserved (one canonical message, one validator-surface assertion) without duplicate fixtures across two pipeline-tier files.

LSP-tier:

- `$source` appears in `FieldCompletions` suggestions on the carrier data field site; absent everywhere else (record-backed, table-backed, pojo-backed, root, unbacked-interface, error-type, jOOQ-standalone, all the same: no `$source` suggestion).
- `Diagnostics.validateFieldMember` produces the canonical `sourceSigilNotDefinedHereMessage` diagnostic when `@field(name: "$source")` is written at any non-carrier-data-field site (covering each `TypeBackingShape` variant in turn).
- Snapshot-uncertainty regression: when the snapshot has no entry for the parent (mid-edit rename), the LSP arm is silent — no completion, no diagnostic.

Execution-tier:

- End-to-end run of an OpprettRegelverksamlingPayload-shaped Sakila fixture with the `@field(name: "$source")` directive on the carrier data field. The test gates on R158 being in trunk (R159 by itself admits the directive but does not change runtime behaviour; R158 lands the consumer-side fetcher emission that resolves the runtime cast failure). The execution test exercises both halves; until R158 lands, R159's pipeline-tier coverage is the live signal.

## Relationship to other roadmap items

- **R158** consumes the carrier-payload data field's binding via the producer-kind axis it introduces on `SourceKey.Reader`. R159 makes `$source` nameable at this site as the semantic contract R158 relies on, but produces no model-arm marker; R158's reader-variant discrimination is on producer kind (DML vs `@service`), classifier-pinned independent of `@field` syntax. Producer-kind is the right discriminator for R158 because it is *invariant under* the author's choice to write or omit `@field(name: "$source")`: the upstream Java cast that R158's emitter arm needs (`Result<RecordN<PK>>` vs `List<XRecord>`) is determined by which mutation classifier produced the carrier, not by the SDL author's directive choice; the `$source` directive is a no-op confirmation of an admit the carrier walk already makes from the SDL shape. Two consumers (R158's emitter, R159's defined-at predicate) evaluate *different* predicates over the model and stay independently auditable. R158 can ship in either order relative to R159; its execution-tier test gates on R159 being in trunk.
- **R12** owns error-channel binding. R159 does not extend it; a future name-decoupled error binding has its own item if a consumer arises.
- **R96** owns `@record` directive cleanup. R159 carries no defensive `@record`-on-payload rejection.
- **R157** ships the `TypeBackingShape` projection. R159 consumes it lightly: the predicate `sourceSigilDefinedAt` is narrow today (carrier data field only), but its `SiteContext` shape leaves room for a future item to broaden admit to specific `TypeBackingShape` permits without reshaping consumers. The LSP also reads the projection for non-sigil completions and diagnostics it already runs; R159 adds no new dependency on the projection beyond the existing site walk.

## Out of scope

- Admission of `$source` at sites other than the carrier-payload data field. The original spec sketched `$source` at `@record`-backed, `@table`-backed, POJO-backed, jOOQ-record-backed, and backed-interface parents as a single uniform predicate; R159 narrows admit to one site because that is the only motivated consumer (R158). Future items can broaden `sourceSigilDefinedAt` to additional `TypeBackingShape` permits; each broadening pulls along the LSP completion and diagnostic arms automatically through the shared predicate. The interface-element lub case (`$source` at an interface field with backed implementers, with the non-`Object` common-upper-bound restriction) is its own filing because the lub algorithm is a sibling design.
- `$errors`, `$context`, any other sigil. Filed if and when a consumer arises.
- Dotted paths in `@field(name:)`. Single-segment today; R159 does not lift.
- Multi-step / path-expression grammar in any form. Filed if and when a consumer arises.
- DML-producer carrier walk migration to a `$source` model. Different upstream shape; R75/R141 keep their PK-keyed-map path.
- `argMapping` extension for `$source` on the input side. Sibling item if a consumer arises.
- Path-form coverage of `@reference` use cases. Separate item; not overlapping today.
- R69, R84, R97 cross-references. R159's surface is intentionally smaller than these items; the convergence story (if one ever exists) belongs to whichever item proposes the merger.
