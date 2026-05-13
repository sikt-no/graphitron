---
id: R159
title: "Root-value sigil $source on @field(name:) for carrier-payload sourcing"
status: Spec
bucket: structural
priority: 2
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-14
---

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

The helper is called from the one site that admits `UpstreamRoot`: `BuildContext.classifyCarrierField` (the per-field arm of `tryResolveSingleRecordCarrier`'s walk), interposed before the existing forbidden-directive `HardReject` check at `BuildContext.java:622-633`. The carrier walk currently lists `DIR_FIELD` in the `HardReject` set unconditionally; R159 changes that arm to consult `argFieldNameRef` first. The behavioural table after R159:

- `@field(name: "$source")` on the carrier data field: admit (sigil parses to `UpstreamRoot`; the directive is treated as a no-op confirmation of the implicit binding the carrier walk already produces).
- `@field(name: "X")` non-sigil on the carrier data field: continues to `HardReject` with the existing message (bare-name `@field` on this site has never had a meaning).
- `@field(name: "$X")` unknown sigil on the carrier data field: rejects at parse time with the `argFieldNameRef` message, which fires before the `HardReject` (so the author sees "Unknown sigil" rather than "forbidden directive").

The other eight existing `argString(field, DIR_FIELD, ARG_NAME)` call sites (`EnumMappingResolver` x2, `TypeBuilder.328`, `FieldBuilder.1097`, `FieldBuilder.3280`, `FieldBuilder.3300`, `FieldBuilder.4331`, `BuildContext.1385`) are not changed. Those sites do not learn about sigils. A schema author who writes `@field(name: "$source")` at one of them surfaces today's existing "no such accessor / column / enum value" rejection unchanged, on the literal string `$source`. R159 does not add eight mechanical "I never accepted sigils" arms; the sites that never accepted sigils stay structurally identical to today, and the principle "Directives carry only what the SDL author needs to say" applies: the site already disambiguates whether sigils are meaningful.

### Classifier model output

The classifier emits no new model arm. `CarrierFieldRole.DataChannel(String fieldName, DataElement element)` keeps today's shape; the `DataElement.Table` / `DataElement.Record` split already carries the upstream element type the binding resolves to. `@field(name: "$source")` is a parse-time admit, not a model reshape: the directive at the carrier data field site is an explicit confirmation of the implicit binding the existing carrier walk already produces from the SDL element type.

R158's reader-variant work consumes the producer-kind axis (DML vs `@service`) it introduces on `SourceKey.Reader`, not a `$source`-vs-bare distinction. R159 does not preempt that split.

The earlier draft proposed a `Binding.{NamedAccessor | UpstreamRoot}` sub-permit on `DataChannel`; that was dropped because the carrier walk admits no bare-name accessor resolution today (`classifyCarrierField` matches the element by SDL type only), so `NamedAccessor` would have no producer. Per § "Sub-taxonomies for resolution outcomes": a sub-taxonomy whose siblings can't be distinguished by any current producer is a field, not a permit.

### Sites where `$source` is defined

The defined-at predicate is derived from R157's shipped `TypeBackingShape` projection on the field's parent, plus the carrier-payload data field admit.

`$source` is defined at a field `F` of parent `P` iff one of:

- `F` is a carrier-payload data field on a `@service`-backed producer (the producer's reflected return value binds at `F`'s site). This is the R158 admit.
- `P` classifies as `TypeBackingShape.RecordBacking` (`@record` parent: the bound domain object).
- `P` classifies as `TypeBackingShape.PojoBacking` (the bound POJO).
- `P` classifies as `TypeBackingShape.JooqRecordBacking` (sealed sub-taxonomy with permits `WithTable` and `Standalone`; both arms admit `$source`, since the binding takes the upstream Java value as a whole and needs no column metadata).
- `P` classifies as `TypeBackingShape.TableBacking` (`@table` parent: the bound jOOQ record).
- `P` is an interface whose implementing types all classify as one of the above (backed-interface admit, derived from the projection rather than enumerated). The Java upstream binds at the implementer-resolved type; `$source` is well-typed at the interface site by lub.

`$source` is **not** defined at any `P` classifying as `TypeBackingShape.NoBacking.*` (sealed sub-taxonomy with permits `Root`, `UnclassifiedInterface`, `UnbackedResult`, per `TypeBackingShape.java:108-134`):

- Query, Mutation, and Subscription root fields (`NoBacking.Root`).
- Plain (unbacked) interface members (`NoBacking.UnclassifiedInterface`).
- `@error` types, unions, plain unbacked objects, and any other unbacked result (`NoBacking.UnbackedResult` — the projection's catch-all for "no backing class"; see `CatalogBuilder.projectType` at `CatalogBuilder.java:140-169`).

`@enum` and `@scalar` types don't surface as parents in the projection at all; the carrier walk rejects them upstream.

A `@splitQuery` child resolves through the projection like any other child: the parent's `TypeBackingShape` determines admit. R159 does not special-case it.

`@field(name: "$source")` at an undefined site rejects at classify time with `"$source is not defined at this site"`.

### Type matching

`@field(name: "$source")` binds the upstream Java value to the SDL field. The classifier checks the source's Java type against the SDL field's element type by the following predicate:

- **`@table`-backed SDL element type**: require exact equality of the source type with the bound record class. List-wrapping is preserved on both sides (`List<XRecord>` source matches `[X!]` SDL element-of-list).
- **Domain-object SDL element type** (`@record` / POJO backing): require Java assignability of the source type to the backing class.
- **Interface SDL element type** with backed implementers: require Java assignability of the source type to the lub of the implementer backing classes.

This is a *new* classifier check, not a refactor of an existing one: today's carrier walk in `BuildContext.classifyCarrierField` matches the data element by SDL type only (`TableBackedType` or `ResultType.fqClassName != null` at `BuildContext.java:600-603`); the Java-type match against the `@service` producer's reflected return happens separately in `ServiceCatalog.reflectServiceMethod` and is consumed elsewhere. R159 adds the predicate as a callable classifier-side check colocated with the sigil admission, so the unknown-Java-type case surfaces as a typed `Rejection` at classify time rather than a runtime cast failure downstream. The predicate is callable from the validator mirror without duplication.

### Internal-representation invariant

`$source` binds the post-decode, pre-encode Java value of the upstream. Wire-format translators (`@nodeId` encode, enum textmap, custom scalar binding) run between the Java value and the GraphQL wire, transparent to the binding. The type-matching predicate compares Java types, not wire forms, by construction. This is the precondition the predicate's correctness rests on; it holds by construction today because no model value carries a wire-form translation, per `rewrite-design-principles.adoc § "Wire-format encoding is a boundary concern"`.

## Load-bearing classifier checks

R159 introduces no new load-bearing classifier checks. The three classifier rejections (unknown sigil, `$source` undefined at the site, `$source` type mismatch) are hygiene: they fail authors fast at parse / classify time but no emitter today branches on their *acceptance* to read narrower shapes. Per § "Classifier guarantees shape emitter assumptions": producers without consumers are allowed but should not be declared with `@LoadBearingClassifierCheck`; the annotation is reserved for shapes an emitter actually relies on.

The natural home for a `$source`-pinned load-bearing key is R158's reader-variant work, where an emitter casts to a specific upstream Java type. R158 owns the producer / consumer pair for the cast-shape guarantee it relies on (one key per reader variant, per R158's own spec direction). R159 keeps the surface narrow: no future-proofing keys without the consumer in trunk.

The existing `single-record-carrier-shape.roles-exhaustively-classified` key remains in place; R159 does not change `DataChannel`'s permit shape, so the existing audit covers the new `$source` admission unchanged.

## Validator mirror

`GraphitronSchemaValidator` mirrors each classifier rejection with a validate-time arm:

- Unknown-sigil rejection.
- `$source`-undefined-at-this-site rejection.
- `$source` type-mismatch rejection.

The mirror runs against the same `FieldNameRef` parse so producer and validator share the parser output. Validator-mirror coverage lives in a new `FieldSourceSigilValidationTest` under `graphitron/src/test/java/no/sikt/graphitron/rewrite/validation/`, following the per-aspect pattern of the existing tests in that package (`ServiceFieldValidationTest`, `RecordFieldAccessorValidationTest`, `ErrorTypeValidationTest`, …). The test runs each rejection's synthetic schema through both the classifier and the validator and asserts both surface the same message.

## LSP

`@field(name:)` autocomplete on sites where `$source` is defined suggests `$source` as a top-level value alongside the existing accessor / column suggestions. R157's shipped `typesByName` projection already supplies the site-classification signal the LSP needs: `TypeBackingShape.RecordBacking`, `PojoBacking`, `JooqRecordBacking` (both `WithTable` and `Standalone`), and `TableBacking` mark the defined sites; `NoBacking.{Root | UnclassifiedInterface | UnbackedResult}` marks the undefined sites. The new arm in `FieldCompletions.generate` adds the literal `$source` to the suggestion list on defined sites. `Diagnostics.validateFieldMember` mirrors the classifier rejections.

## Tests

Pipeline-tier:

- `$source` admit on an OpprettRegelverksamlingPayload-shaped fixture: assert the carrier walk produces `DataChannel(fieldName, DataElement.Record(...))` with the directive admitted, identical in shape to the same fixture without the directive.
- `$source` type-mismatch reject (source's Java type does not match the SDL element's backing class).
- `$source`-at-Query-root reject (`NoBacking.Root`; "not defined at this site").
- `$source`-at-unbacked-interface-member reject (`NoBacking.UnclassifiedInterface`).
- `$source`-at-error-type-field reject (`NoBacking.UnbackedResult`; ensures the `@error` case is covered even though it shares a permit with other unbacked results).
- `$source`-at-backed-interface admit (interface with uniformly backed implementers).
- Unknown-sigil reject (`@field(name: "$bogus")`): assert the message names `$source` as the only allowed sigil and fires before the existing `DIR_FIELD` `HardReject` so the author sees the parse-time message, not the forbidden-directive one.
- Bare-name `@field(name: "X")` on the carrier data field continues to `HardReject` with the existing forbidden-directive message: regression that the sigil-aware arm does not relax the long-standing rejection for non-sigil values at this site.
- Carrier walk model-shape regression: fixtures that go through today's implicit element-type binding (no `@field` directive) continue to produce `DataChannel(fieldName, DataElement.Record(...))` / `DataChannel(fieldName, DataElement.Table(...))` byte-identical to today, confirming R159 reshapes no existing fixture.

Execution-tier:

- End-to-end run of an OpprettRegelverksamlingPayload-shaped Sakila fixture with the `@field(name: "$source")` directive on the carrier data field. The test gates on R158 being in trunk (R159 by itself admits the directive but does not change runtime behaviour; R158 lands the consumer-side fetcher emission that resolves the runtime cast failure). The execution test exercises both halves; until R158 lands, R159's pipeline-tier coverage is the live signal.

LSP-tier:

- `$source` appears in `FieldCompletions` suggestions on defined sites, absent on undefined sites.
- `Diagnostics.validateFieldMember` produces the three rejection messages on synthetic schemas.

## Relationship to other roadmap items

- **R158** consumes the carrier-payload data field's binding via the producer-kind axis it introduces on `SourceKey.Reader`. R159 makes `$source` nameable at this site as the semantic contract R158 relies on, but produces no model-arm marker; R158's reader-variant discrimination is on producer kind (DML vs `@service`), classifier-pinned independent of `@field` syntax. R158 can ship in either order relative to R159; its execution-tier test gates on R159 being in trunk.
- **R12** owns error-channel binding. R159 does not extend it; a future name-decoupled error binding has its own item if a consumer arises.
- **R96** owns `@record` directive cleanup. R159 carries no defensive `@record`-on-payload rejection.
- **R157** ships the `TypeBackingShape` projection R159 consumes for both the defined-at predicate (classifier and validator) and LSP completion site classification.

## Out of scope

- `$errors`, `$context`, any other sigil. Filed if and when a consumer arises.
- Dotted paths in `@field(name:)`. Single-segment today; R159 does not lift.
- Multi-step / path-expression grammar in any form. Filed if and when a consumer arises.
- DML-producer carrier walk migration to a `$source` model. Different upstream shape; R75/R141 keep their PK-keyed-map path.
- `argMapping` extension for `$source` on the input side. Sibling item if a consumer arises.
- Path-form coverage of `@reference` use cases. Separate item; not overlapping today.
- R69, R84, R97 cross-references. R159's surface is intentionally smaller than these items; the convergence story (if one ever exists) belongs to whichever item proposes the merger.
