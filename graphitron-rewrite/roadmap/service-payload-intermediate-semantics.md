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

A new sealed `FieldNameRef.{BareName | UpstreamRoot}` carries the resolved directive value. The parser arm:

- `@field(name: "$source")` parses to `FieldNameRef.UpstreamRoot`.
- Any other value parses to `FieldNameRef.BareName(value)`.
- Any `$`-prefixed value other than exactly `$source` rejects at parse time with `"Unknown sigil '$X'; allowed: $source"`.

The parser lives at the existing `argString(field, DIR_FIELD, ARG_NAME)` read sites in `BuildContext`, `FieldBuilder`, `TypeBuilder`, `EnumMappingResolver`, and `GraphitronSchemaBuilder`. Today nine call sites consume the raw String; they migrate to consume the sealed `FieldNameRef`. Six of those sites (`EnumMappingResolver` x2, `TypeBuilder.328`, `FieldBuilder.1097`, `FieldBuilder.3280`, `FieldBuilder.3300`, `FieldBuilder.4331`, `BuildContext.1385`) are bare-name-only and reject `UpstreamRoot` with `"$source is not defined at this site"`. The classifier site for carrier-payload data fields (in `GraphitronSchemaBuilder.registerCarrierDataField` and the `BuildContext.tryResolveSingleRecordCarrier` walk) is the one that admits `UpstreamRoot`.

### Classifier model output

The classifier emits the same model arm for `@field(name: "$source")` and for the equivalent bare-name type-match fallback resolution that today's `tryResolveSingleRecordCarrier` performs implicitly. Downstream emitters do not branch on which syntax produced the binding; the model arm carries the bound upstream type and that is sufficient.

Concrete shape: `CarrierFieldRole.DataChannel` gains a sealed sub-permit `Binding.{NamedAccessor(String name, JavaType type) | UpstreamRoot(JavaType type)}`. The current bare-name name-match case produces `NamedAccessor`; the current bare-name type-match-fallback case and `@field(name: "$source")` both produce `UpstreamRoot`. R158's reader-variant work consumes `UpstreamRoot` directly.

### Sites where `$source` is defined

`$source` is defined at:

- A carrier-payload data field on a `@service`-backed producer (the producer's reflected return value).
- A child field of a `@table`-backed parent (the bound jOOQ record).
- A child field of a `@record`-backed parent (the bound domain object).
- A child field of a `@service` or `@tableMethod` parent (the method's reflected return).

`$source` is **not** defined at:

- Query, Mutation, and Subscription root fields.
- Plain (unbacked) interface members.
- `@error` type fields.
- `@enum` and `@scalar` types.

`@field(name: "$source")` at a site not in the defined list rejects at classify time with `"$source is not defined at this site"`. The undefined-site rejection is the same load-bearing classifier check (`field-source-sigil.upstream-type-match`) producing a typed `Rejection` rather than an admit.

### Type matching

`@field(name: "$source")` binds the upstream Java value to the SDL field. The classifier checks the source's Java type against the SDL field's element type:

- **`@table`-backed SDL element type**: require exact equality of the source type with the bound record class. List-wrapping is preserved on both sides (`List<XRecord>` source matches `[X!]` SDL element-of-list).
- **Domain-object SDL element type**: require Java assignability of the source type to the backing class.

This is the same predicate today's type-match fallback applies in `tryResolveSingleRecordCarrier`. R159 lifts it from an implicit step inside the carrier walk to a callable classifier check with a load-bearing key.

### Internal-representation invariant

`$source` binds the post-decode, pre-encode Java value of the upstream. Wire-format translators (`@nodeId` encode, enum textmap, custom scalar binding) run between the Java value and the GraphQL wire, transparent to the binding. The type-matching predicate compares Java types, not wire forms, by construction. This is the precondition the predicate's correctness rests on; it holds by construction today because no model value carries a wire-form translation, per `rewrite-design-principles.adoc § "Wire-format encoding is a boundary concern"`.

## Load-bearing classifier checks

One new key: `field-source-sigil.upstream-type-match`.

- **Producer**: the classify site that admits `@field(name: "$source")` and the implicit type-match-fallback path in `tryResolveSingleRecordCarrier`. The key pins "every `UpstreamRoot` binding carries a Java type that matches the SDL element type by the predicate above; the carrier-side downstream may rely on this without re-checking."
- **Consumers**: R158's `SourceKey.Reader` sub-taxonomy consumer for the `@service`-backed reader variant, and any future fetcher emitter that reads `UpstreamRoot`.

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
- Unknown-sigil reject (`@field(name: "$bogus")`).
- Bare-name regression: every existing bare-name fixture continues to classify unchanged.

Execution-tier:

- End-to-end run of an OpprettRegelverksamlingPayload-shaped Sakila fixture under the new model arm. R158's consumer-side fetcher emission ships in lockstep; the execution test exercises both halves.

LSP-tier:

- `$source` appears in `FieldCompletions` suggestions on defined sites, absent on undefined sites.
- `Diagnostics.validateFieldMember` produces the three rejection messages on synthetic schemas.

## Relationship to other roadmap items

- **R158** consumes `$source` at the carrier-payload data field site. R158 is unblocked the moment R159 ships and the two land in either order (R158 can be drafted against R159's `UpstreamRoot` permit before R159 lands; the execution-tier test in R158 gates on R159 being in trunk).
- **R12** owns error-channel binding. R159 does not extend it; a future name-decoupled error binding has its own item if a consumer arises.
- **R96** owns `@record` directive cleanup. R159 carries no defensive `@record`-on-payload rejection.
- **R157** ships the LSP `typesByName` projection R159 consumes for completion site classification.

## Out of scope

- `$errors`, `$context`, any other sigil. Filed if and when a consumer arises.
- Dotted paths in `@field(name:)`. Single-segment today; R159 does not lift.
- Multi-step / path-expression grammar in any form. Filed if and when a consumer arises.
- DML-producer carrier walk migration to a `$source` model. Different upstream shape; R75/R141 keep their PK-keyed-map path.
- `argMapping` extension for `$source` on the input side. Sibling item if a consumer arises.
- Path-form coverage of `@reference` use cases. Separate item; not overlapping today.
- R69, R84, R97 cross-references. R159's surface is intentionally smaller than these items; the convergence story (if one ever exists) belongs to whichever item proposes the merger.
