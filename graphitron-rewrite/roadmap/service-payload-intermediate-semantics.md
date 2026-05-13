---
id: R159
title: "Root-value sigil $source on @field(name:) for carrier-payload sourcing"
status: Spec
bucket: structural
priority: 2
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Root-value sigil `$source` on `@field(name:)` for carrier-payload sourcing

## What this ships

A single new accepted value for `@field(name:)`: the literal `$source`. When the directive carries this value, the SDL field binds to the upstream Java value at this field's site, taken as a whole. Bare-name `@field(name: "X")` is unchanged.

This resolves R158's `OpprettRegelverksamlingPayload` runtime cast failure by giving the schema author an explicit, name-decoupled way to route a carrier-payload data field to the producer's reflected return. Today's classifier reaches this binding by accident via bare-name's type-match fallback; R159 makes it nameable.

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

`List<RegelverksamlingRecord>` is the producer's reflected return; `[Regelverksamling!]` is the SDL field. Today's classifier admits this shape via bare-name's type-match fallback (the carrier walk finds one accessor on the producer return whose Java type matches the SDL element's bound record class), but the binding is implicit and the downstream `SingleRecordTableField/MANY` fetcher's `(Result<RecordN<...>>) env.getSource()` cast then fails at runtime because the carrier walk's downstream emitter assumed a DML producer. R158 owns the consumer-side fix; R159 gives R158 a stable reference to consume.

## The mechanism

### Parser

Sigil parsing is interposed at the one site that admits it, not at every directive read. A new helper `argFieldNameRef(field, DIR_FIELD, ARG_NAME)` returns `Optional<FieldNameRef>` where `FieldNameRef` is a sealed `{BareName(String value) | UpstreamRoot}`:

- `@field(name: "$source")` produces `FieldNameRef.UpstreamRoot`.
- Any non-`$`-prefixed value produces `FieldNameRef.BareName(value)`.
- Any `$`-prefixed value other than exactly `$source` rejects at parse time with `"Unknown sigil '$X'; allowed: $source"`.

The helper is called from the one site that admits `UpstreamRoot`: the carrier-payload data field classifier in `GraphitronSchemaBuilder.registerCarrierDataField` and the `BuildContext.tryResolveSingleRecordCarrier` walk.

The other eight existing `argString(field, DIR_FIELD, ARG_NAME)` call sites (`EnumMappingResolver` x2, `TypeBuilder.328`, `FieldBuilder.1097`, `FieldBuilder.3280`, `FieldBuilder.3300`, `FieldBuilder.4331`, `BuildContext.1385`) are not changed. Those sites do not learn about sigils. A schema author who writes `@field(name: "$source")` at one of them surfaces today's existing "no such accessor / column / enum value" rejection unchanged, on the literal string `$source`. R159 does not add eight mechanical "I never accepted sigils" arms; the sites that never accepted sigils stay structurally identical to today, and the principle "Directives carry only what the SDL author needs to say" applies: the site already disambiguates whether sigils are meaningful.

### Classifier model output

The classifier emits the same model arm for `@field(name: "$source")` and for the equivalent bare-name type-match fallback resolution that today's `tryResolveSingleRecordCarrier` performs implicitly. Downstream emitters do not branch on which syntax produced the binding; the model arm carries the bound upstream type and that is sufficient.

Concrete shape: `CarrierFieldRole.DataChannel` gains a sealed sub-permit `Binding.{NamedAccessor(String name, JavaType type) | UpstreamRoot(JavaType type)}`. The current bare-name name-match case produces `NamedAccessor`; the current bare-name type-match-fallback case and `@field(name: "$source")` both produce `UpstreamRoot`. R158's reader-variant work consumes `UpstreamRoot` directly.

**Bare-name fallback retro-classification.** Every site that today reaches `tryResolveSingleRecordCarrier`'s type-match fallback now classifies as `UpstreamRoot`, not `NamedAccessor`. The schema author's syntax is unchanged; the *classified model arm* under the existing bare-name resolution flips for those fixtures. Generator output and runtime behavior are unchanged because no emitter today branches on the `NamedAccessor` vs. `UpstreamRoot` distinction at this site (R158 is the first consumer). The pipeline regression test asserts the new classified shape, not "unchanged."

### Sites where `$source` is defined

The defined-at predicate is derived from R157's shipped `TypeBackingShape` projection on the field's parent, plus the carrier-payload data field admit.

`$source` is defined at a field `F` of parent `P` iff one of:

- `F` is a carrier-payload data field on a `@service`-backed producer (the producer's reflected return value binds at `F`'s site). This is the R158 admit.
- `P` classifies as `TypeBackingShape.RecordBacking` (`@record` parent: the bound domain object).
- `P` classifies as `TypeBackingShape.PojoBacking` (the bound POJO).
- `P` classifies as `TypeBackingShape.JooqRecordBacking` (a `@service` / `@tableMethod` reflected return that surfaces as a jOOQ record).
- `P` classifies as `TypeBackingShape.TableBacking` (`@table` parent: the bound jOOQ record).
- `P` is an interface whose implementing types all classify as one of the above (backed-interface admit, derived from the projection rather than enumerated). The Java upstream binds at the implementer-resolved type; `$source` is well-typed at the interface site by lub.

`$source` is **not** defined at any `P` classifying as `TypeBackingShape.NoBacking.*`, including:

- Query, Mutation, and Subscription root fields (`NoBacking.RootOperation`).
- Plain (unbacked) interface members (`NoBacking.UnbackedInterface`).
- `@error` type fields (`NoBacking.ErrorType`).
- `@enum` and `@scalar` types (these don't surface as parents in the projection but the carrier walk rejects them upstream).

A `@splitQuery` child resolves through the projection like any other child: the parent's `TypeBackingShape` determines admit. R159 does not special-case it.

`@field(name: "$source")` at an undefined site rejects at classify time with `"$source is not defined at this site"`. The undefined-site rejection is pinned by the `field-source-sigil.site-defined` load-bearing key.

### Type matching

`@field(name: "$source")` binds the upstream Java value to the SDL field. The classifier checks the source's Java type against the SDL field's element type by the following predicate (authoritative; the existing `tryResolveSingleRecordCarrier` type-match fallback is refactored to call this predicate, so the carrier walk and the explicit sigil share one implementation):

- **`@table`-backed SDL element type**: require exact equality of the source type with the bound record class. List-wrapping is preserved on both sides (`List<XRecord>` source matches `[X!]` SDL element-of-list).
- **Domain-object SDL element type** (`@record` / POJO backing): require Java assignability of the source type to the backing class.
- **Interface SDL element type** with backed implementers: require Java assignability of the source type to the lub of the implementer backing classes.

R159 lifts the predicate from an implicit step inside the carrier walk to a callable classifier check named by a load-bearing key.

### Internal-representation invariant

`$source` binds the post-decode, pre-encode Java value of the upstream. Wire-format translators (`@nodeId` encode, enum textmap, custom scalar binding) run between the Java value and the GraphQL wire, transparent to the binding. The type-matching predicate compares Java types, not wire forms, by construction. This is the precondition the predicate's correctness rests on; it holds by construction today because no model value carries a wire-form translation, per `rewrite-design-principles.adoc § "Wire-format encoding is a boundary concern"`.

## Load-bearing classifier checks

Two new keys, each pinning one guarantee so consumers can name the exact shape they consume:

**`field-source-sigil.site-defined`.**

- **Producer**: the classify site that resolves `@field(name: "$source")` against the defined-at predicate (R157's `TypeBackingShape` projection on the parent, plus the carrier-payload data field admit).
- **Pin**: "every `UpstreamRoot` binding occurs at a site where the parent classifies as one of the backed shapes (or at the carrier-payload data field admit); the consumer may assume the upstream Java value exists and has a known type."
- **Consumers**: any emitter that reads `UpstreamRoot` and dereferences the upstream Java value. R158's `SourceKey.Reader` consumer relies on this for the existence half of its cast.

**`field-source-sigil.upstream-type-match`.**

- **Producer**: the classify site that admits `@field(name: "$source")` and the refactored type-match path in `tryResolveSingleRecordCarrier` (both call the predicate from § "Type matching").
- **Pin**: "every `UpstreamRoot` binding carries a Java type that matches the SDL element type by the type-matching predicate; the carrier-side downstream may rely on this without re-checking."
- **Consumers**: R158's `SourceKey.Reader` consumer for the `@service`-backed reader variant, and any future fetcher emitter that reads `UpstreamRoot`. This is the typed-cast half of R158's emitter.

The unknown-sigil parse-time rejection (`"$bogus"`) is hygiene; no emitter relies on it, so it does not need a load-bearing key.

The existing `single-record-carrier-shape.roles-exhaustively-classified` key remains in place; R159's permit-shape change to `DataChannel` slots into the existing audit.

## Validator mirror

`GraphitronSchemaValidator` mirrors each classifier rejection with a validate-time arm:

- Unknown-sigil rejection.
- `$source`-undefined-at-this-site rejection.
- `$source` type-mismatch rejection.

The mirror runs against the same `FieldNameRef` parse so producer and validator share the parser output. Validator-mirror coverage is enforced by the existing audit infrastructure (`GraphitronSchemaValidatorTest`).

## LSP

`@field(name:)` autocomplete on sites where `$source` is defined suggests `$source` as a top-level value alongside the existing accessor / column suggestions. R157's shipped `typesByName` projection already supplies the site-classification signal the LSP needs (`TypeBackingShape.RecordBacking` / `PojoBacking` / `JooqRecordBacking` / `TableBacking` mark the defined sites; `NoBacking.*` marks the undefined sites). The new arm in `FieldCompletions.generate` adds the literal `$source` to the suggestion list on defined sites. `Diagnostics.validateFieldMember` mirrors the classifier rejections.

## Tests

Pipeline-tier:

- `$source` admit on an OpprettRegelverksamlingPayload-shaped fixture.
- `$source` type-mismatch reject (source's Java type does not match the SDL element's backing class).
- `$source`-at-Query-root reject ("not defined at this site").
- `$source`-at-unbacked-interface-member reject.
- `$source`-at-backed-interface admit (interface with uniformly backed implementers).
- Unknown-sigil reject (`@field(name: "$bogus")`).
- Bare-name name-match regression: bare-name fixtures whose accessor matches by name continue to classify as `NamedAccessor`, unchanged.
- Bare-name type-match-fallback retro-classification: bare-name fixtures whose accessor matches only by type now classify as `UpstreamRoot` (the new shape; generator output and runtime behavior unchanged).

Execution-tier:

- End-to-end run of an OpprettRegelverksamlingPayload-shaped Sakila fixture under the new model arm. R158's consumer-side fetcher emission ships in lockstep; the execution test exercises both halves.

LSP-tier:

- `$source` appears in `FieldCompletions` suggestions on defined sites, absent on undefined sites.
- `Diagnostics.validateFieldMember` produces the three rejection messages on synthetic schemas.

## Relationship to other roadmap items

- **R158** consumes `$source` at the carrier-payload data field site. R158 is unblocked the moment R159 ships and the two land in either order (R158 can be drafted against R159's `UpstreamRoot` permit before R159 lands; the execution-tier test in R158 gates on R159 being in trunk).
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
