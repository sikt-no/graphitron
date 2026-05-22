---
id: R229
title: "EnumTypeGenerator: honor @field(name:) as runtime value"
status: In Progress
bucket: bug
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# EnumTypeGenerator: honor @field(name:) as runtime value

> The emitted `<EnumName>Type` class registers each enum value with
> `.name(value.getName()).value(value.getName())`, ignoring the value's
> `@field(name:)` directive. When the upstream service or jOOQ record returns
> the directive-overridden string (e.g. a non-ASCII form like `"FØDSELSNUMMER"`
> for the SDL value `FODSELSNUMMER`), graphql-java's Coercing layer has no
> registered `.value(...)` matching the runtime object and fails the field with
> `Can't serialize value ... Unknown value 'FØDSELSNUMMER'`. The presenting bug
> is one-sided: the schema emitter ignores the directive while
> `EnumMappingResolver.buildTextEnumMapping` honours it. The root cause is
> structural: both consumers re-resolve `@field(name:)` off the raw
> `GraphQLEnumType` carried on `GraphitronType.EnumType`, with nothing keeping
> them aligned. R263 added `.value(name)` to the schema emit without going
> through the existing resolver lookup, which is exactly the drift the model
> shape invites. The fix lifts the resolution into the classifier so the
> model carries the runtime string and both consumers read the same record
> component.

---

## Motivation

The `@field(name:)` directive on enum values is graphitron's documented
convention for "the runtime / DB / upstream string differs from the SDL
identifier". `EnumMappingResolver.buildTextEnumMapping`
(`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java:117-128`)
reads it as the DB-side string for query-time mapping, defaulting to the
value's own name. `EnumMappingResolver.validateEnumFilter` (`:135-167`)
applies the same lookup on the filter axis.

The schema-side emit at
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/EnumTypeGenerator.java:71-91`
does not. It echoes `value.getName()` into both `.name(...)` and
`.value(...)`, so graphql-java's serialization path only matches when the
runtime object equals the SDL identifier verbatim. The R263 changelog entry
that introduced the explicit `.value(name)` call notes this was added so
the Coercing layer wouldn't reject "string-matching-enum-name
serializations"; the directive case was not in scope at that landing and
fell through.

Concrete failure mode reported by a consumer (federated graph; `Person.identifikasjon`
declared as `@override(from: "admissio")`, `PersonIdentifikasjonType` has
`FODSELSNUMMER @field(name: "FØDSELSNUMMER")`):

```
"message": "Can't serialize value (.../identifikasjon[0]/type) :
            Invalid input for enum 'PersonIdentifikasjonType'.
            Unknown value 'FØDSELSNUMMER'"
```

The upstream `admissio` service emits `"FØDSELSNUMMER"` (the directive value)
as the runtime string; the local `opptak` graphitron schema's enum has
`.value("FODSELSNUMMER")`, so no entry matches and the field fails
serialization.

GraphQL spec constraint: enum value names must match
`/[_A-Za-z][_0-9A-Za-z]*/`, so `FØDSELSNUMMER` with `Ø` is never a valid
`.name(...)`; the directive value is, by definition, the *runtime*
representation, not an alternate SDL name. This is what `.value(...)` is for
in graphql-java's API.

---

## Design

Two consumers already evaluate the same predicate over `@field(name:)` on
enum values: the schema emitter at
`EnumTypeGenerator.buildValueDefinition` (currently broken) and the filter
axis at `EnumMappingResolver.buildTextEnumMapping` (correct). Both reach
through the raw `GraphQLEnumType schemaType` on
`GraphitronType.EnumType` (`model/GraphitronType.java:434-438`) and
re-resolve the directive at consumer time. Per *Generation-thinking*
(`rewrite-design-principles.adoc` § Generation-thinking; "if two consumers
evaluate the same predicate over a model field, the branch belongs in the
model"), the branch belongs on the classifier output, not at two consumer
sites. The bug returned at R263 because the model shape invites that drift.

### Primary: pre-resolve at classify time

Lift the directive read into the classifier and carry the resolved runtime
string on the model. Both consumers read a record component.

```java
// new in no.sikt.graphitron.rewrite.model
public record EnumValueSpec(
    String sdlName,                          // wire identifier; GraphQL Name lex rule applies
    String runtimeValue,                     // @field(name:); falls back to sdlName when absent
    String description,                      // null when absent
    String deprecationReason,                // null when not deprecated
    GraphQLEnumValueDefinition source        // retained for AppliedDirectiveEmitter
) {}

// model/GraphitronType.java
record EnumType(
    String name,
    SourceLocation location,
    List<EnumValueSpec> values,              // pre-resolved at classify time
    GraphQLEnumType schemaType               // retained for AppliedDirectiveEmitter
) implements GraphitronType, EmitsPerTypeFile {}
```

Classify-site (`TypeBuilder.java:530`) walks `enumType.getValues()` once
and builds the `List<EnumValueSpec>` using the same
`argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName())` resolution
already in `EnumMappingResolver.buildTextEnumMapping`. The schema emitter
consumes `et.values()` directly; the filter-axis classifier consumes the
same list. There is no second directive read, and no place for a future
generator to skip resolution and reintroduce the drift.

`schemaType` stays on the record because `AppliedDirectiveEmitter` still
walks the raw `GraphQLEnumValueDefinition` for applied-directive emission
on the value itself (line 86-88 of `EnumTypeGenerator`). `EnumValueSpec.source()`
preserves the per-value handle for that walk; consolidating the
applied-directive emission against the model spec is a separate concern
and out of scope here.

### Wire boundary: `.value()` is the wire-form ↔ runtime-form translation slot

Per *Wire-format encoding is a boundary concern*
(`rewrite-design-principles.adoc` § Wire-format…), `.name()` and `.value()`
on `GraphQLEnumValueDefinition` are two sides of one boundary, and
graphql-java already maintains that boundary at the wire layer:

- `.name(String)` is the SDL identifier. Graphql-java's parser matches AST
  literals against it, and serialization returns it to the client. This is
  the wire-form on both directions.
- `.value(Object)` is the *runtime backing* for that name. Both
  `parseLiteral` / `parseValue` return `enumValueDefinition.getValue()` to
  the resolver; serialization compares the runtime object against
  `.value()` and returns the matching `.name()`. This is the wire ↔ runtime
  translation point. (See `GraphQLEnumType.parseLiteralImpl` and
  `getNameByValue` in graphql-java v25.)

Pre-R229 the schema emitter set both slots to the SDL name. That left the
runtime translation undone at the boundary, so the generator open-coded it
as `CallSiteExtraction.TextMapLookup`: a static `MAP = {"NC_17": "NC-17",
…}` referenced from generated condition / service-method code as
`MAP.get(env.getArgument("textRating"))`. Two-sided structural debt: the
`.value()` slot was wasted as an echo, and the translation it was meant to
do was reinvented in a parallel Java map.

Lifting `@field(name:)` into `.value()` puts the translation back at the
boundary where graphql-java already maintains it. Both directions cross
the wire ↔ runtime boundary at `.value()`:

|direction | graphql-java does | resolver sees|
|---|---|---|
|input (`textRating: NC_17`) | matches `.name="NC_17"`, returns `.value` | `"NC-17"`|
|output (raw column `"NC-17"`) | matches `.value="NC-17"`, returns `.name` | client sees `"NC_17"`|

The Java-side `TextMapLookup` map performs an identity lookup at this
point; graphql-java has already done its job. So it collapses into
`Direct`:

- `EnumMappingResolver.deriveExtraction` drops the `TextMapLookup` branch;
  text-mapped enums fall through to `Direct`.
- `EnumMappingResolver.enrichArgExtractions` retires entirely. Its
  post-hoc String → TextMapLookup rewrite was a workaround for
  `ServiceCatalog` emitting `Direct` while the value was still wire-form;
  graphql-java now delivers the runtime form directly.
- `TypeConditionsGenerator.buildTextEnumMapField` and its `*_MAP` emit
  path go away; condition classes stop carrying static enum maps.
- `CallSiteExtraction.TextMapLookup` sealed permit is deleted (unused
  permits rot).

The `enumInputStillByName` test below pins what stays asymmetric: the
*wire-form* identifier is still the SDL name on both directions (the user
types `NC_17` in queries, the user sees `NC_17` in responses). Only the
*runtime* form changes, and only as far as the resolver-internal contract.

`AppliedDirectiveEmitter.applicationsFor(value)` continues to emit the
full directive application onto the enum value, so consumers can still
read `@field(name: "FØDSELSNUMMER")` via introspection or applied
directives; the runtime-value lift is additive and does not strip the
directive itself.

The legacy `graphitron-codegen-parent` generator is out of scope per
`CLAUDE.md`. Any analogous bug there is the legacy maintainers' to
reproduce.

---

## Implementation sites

### Primary (pre-resolution)

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/EnumValueSpec.java`:
  new record (fields per Design above).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`:
  add `List<EnumValueSpec> values` component to `EnumType` (lines 434-438).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`
  (line 530): walk `enumType.getValues()` and populate the spec list with
  the directive-resolved runtime string, description, deprecation, and
  the per-value `GraphQLEnumValueDefinition` source handle.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/EnumTypeGenerator.java`:
  `buildValueDefinition` consumes `EnumValueSpec` instead of the raw
  `GraphQLEnumValueDefinition`; reads `runtimeValue()` for `.value(...)`
  and `source()` for the applied-directive emission pass.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java`:
  `buildTextEnumMapping` and `validateEnumFilter` consume the model's
  `List<EnumValueSpec>` instead of `graphqlEnum.getValues()` plus an
  `argString` call. Lookup by `sdlName`, read `runtimeValue`.
  `deriveExtraction` drops the `TextMapLookup` branch (text-mapped enums
  fall through to `Direct`); `enrichArgExtractions` is removed entirely
  and its callers stop invoking it.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/CallSiteExtraction.java`:
  drop the `TextMapLookup` sealed permit (no remaining producers).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeConditionsGenerator.java`:
  drop `buildTextEnumMapField` and the static `*_MAP` emit path; condition
  classes stop carrying enum maps.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ArgCallEmitter.java`:
  drop the `TextMapLookup` arm from the extraction switch (mirrors the
  sealed-permit deletion).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`:
  drop the `TextMapLookup` arm from the param-type switch (mirrors the
  sealed-permit deletion).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceDirectiveResolver.java`,
  `TableMethodDirectiveResolver.java`: drop the `enumMapping.enrichArgExtractions`
  call sites.
- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`:
  existing `EnumType`-projection test rigs (line 8796 onwards) update to
  the new component shape. The R53 regression test
  `SERVICE_MUTATION_FIELD_NAME_OVERRIDE_TEXT_ENUM` (line 6345) flips its
  assertion from `TextMapLookup` to `Direct`: the same enum-override
  scenario still works, just through the boundary rather than the map.

---

## Tests

Pipeline-tier is the primary behavioural tier per the rewrite test guide,
but the behaviour this item delivers ("graphql-java serializes the runtime
string to the SDL identifier") sits at the execution tier; the pipeline
test that pins emit shape is structural support only.

- Execution: `EnumSerializationExecutionTest.directiveValueRoundTripsThroughCoercing`
  builds a focused schema (enum `PersonIdentifikasjon` with
  `FODSELSNUMMER @field(name: "FØDSELSNUMMER")`, query field
  `type: PersonIdentifikasjon` returning the runtime form), executes
  `{ type }`, and asserts the response contains `"FODSELSNUMMER"` (SDL
  form) with no `errors` entry. Pre-R229 this would fail with
  `Can't serialize value ... Unknown value 'FØDSELSNUMMER'`. This is the
  boundary contract the lift relies on.
- Execution: `EnumSerializationExecutionTest.enumInputStillByName` —
  client sends `echo(input: FODSELSNUMMER)`; the fetcher captures the
  resolver-side argument. Asserts the captured value is `"FØDSELSNUMMER"`
  (runtime form, what graphql-java now delivers post-lift) and the
  response round-trips back to `"FODSELSNUMMER"`. Pins the wire ↔ runtime
  translation on the input side — the same mechanism that lets graphitron
  retire the Java-side `TextMapLookup`.
- Execution: `EnumSerializationExecutionTest.simpleValueRoundTripsWithoutDirective`
  pins the directive-absent fallback (values without `@field(name:)`
  continue to use the SDL identifier on both slots).
- Execution: `GraphQLQueryTest.films_filteredByTextRating` and
  `films_filteredByTextRating_simpleValue` (pre-existing, Sakila): pin
  that the input wire-form stays the SDL identifier — `films(textRating: NC_17)`
  still filters to `ADAPTATION HOLES`. These exercise the input-side
  effect of dropping `TextMapLookup`: the runtime form flows straight
  through `Direct` extraction into the SQL WHERE clause.
- *Out of scope for R229's execution coverage*: end-to-end via Sakila on
  the *output* side. Graphitron currently lowers text-mapped-enum fields
  (e.g. `textRating: TextRating`) to GraphQL type `String` at emit time,
  so graphql-java's Coercing is never engaged and R229's `.value()` lift
  is invisible to clients on that path. The reported consumer bug
  reproduces only when the field is typed as the enum (the consumer's
  schema is). The structural fix — emit text-mapped-enum fields as the
  enum type — is filed as R230.
- Pipeline: `GraphitronSchemaBuilderTest.EnumTypeCase.ENUM_WITH_FIELD_NAME_DIRECTIVE`
  pins the classifier output: SDL with `@field(name:)`-marked enum values
  goes through `GraphitronSchemaBuilder`; assert `EnumType.values()`
  carries entries whose `sdlName` is the GraphQL identifier and whose
  `runtimeValue` is the directive string (or the SDL name when absent).
  This is the classifier-side pin that emit and filter axes both rely on.
- Unit: `EnumTypeGeneratorTest.typeMethod_routesFieldNameDirectiveIntoRuntimeValue`
  pins the schema emit: `.name(SDL)` and `.value(runtime)` land on the
  generated `<Name>Type.type()` body.
- The R53 regression test
  `GraphitronSchemaBuilderTest.MutationFieldCase.SERVICE_MUTATION_FIELD_NAME_OVERRIDE_TEXT_ENUM`
  flips its assertion: `TextMapLookup` → `Direct`. The scenario
  (`argMapping` + text-mapped enum arg on a `@service` mutation) still
  works; the conversion has moved to the wire boundary.

Compilation-tier coverage rides on the existing `graphitron-sakila-example`
compile.

---

## Phasing

Single landing. Risk of collateral breakage is bounded to two surfaces:

1. *Output serialization of `@field(name:)`-marked enum values*: the runtime
   mapping shifts from "matches the SDL identifier" to "matches the upstream
   representation", which is the *intended* semantics — the directive value
   is, by definition, the upstream string.
2. *Input handling of text-mapped enum args*: graphql-java now delivers the
   runtime form to the resolver, and the Java-side `TextMapLookup` map
   collapses into `Direct`. The generated condition / service-method code
   stops doing a redundant `MAP.get(...)` lookup; the value flows straight
   through. Existing input filtering and service-method calls keep working
   because the runtime form is what the DB and the developer methods
   wanted in the first place.

Enum values without `@field(name:)` continue to emit `.name(X).value(X)`
exactly as today; nothing changes for that population.
