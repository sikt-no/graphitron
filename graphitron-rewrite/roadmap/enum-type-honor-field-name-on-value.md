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

### Stopgap arm (if the model edit is judged too wide)

If the reviewer decides the model edit is out of scope for a single-symptom
bug fix, the narrower landing is a package-private helper on `BuildContext`
that both consumers delegate to:

```java
// BuildContext.java
static String enumValueRuntimeString(GraphQLEnumValueDefinition value) {
    return argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName());
}
```

`EnumMappingResolver.buildTextEnumMapping` switches to call it;
`EnumTypeGenerator.buildValueDefinition` calls it for the `.value(...)`
argument. Both consumers now name the same function rather than
constructing the same expression twice. This closes the *function*-sharing
gap; it does not close the *value*-sharing gap (each caller still resolves
at its own call time, so a future generator can still construct its own
read). The follow-up to migrate to the primary shape is filed as a separate
Backlog item before this lands.

The Open Question below frames the choice between the two arms.

### Wire boundary: input parsing reads `.name(...)`, output serialization reads `.value(...)`

Per *Wire-format encoding is a boundary concern*
(`rewrite-design-principles.adoc` § Wire-format…), the SDL identifier and
the runtime representation are two sides of one boundary. The directive
rewires only one of them.

Input enums arrive as SDL identifiers at the wire boundary (graphql-java's
parser reads the AST `EnumValue` and matches by `.name(...)`); output
serialization compares the runtime object against the registered
`.value(...)`. `@field(name:)` rewires the *runtime* representation only;
the wire-side identifier is the SDL name on both directions. The fix
touches `.value(...)` for that reason, not because input is "out of
scope". The `enumInputStillByName` test below pins the asymmetry.

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
- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`:
  existing `EnumType`-projection test rigs (line 8796 onwards) update to
  the new component shape.

### Stopgap arm (if reviewer opts for narrower landing)

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`:
  new package-private static helper `enumValueRuntimeString` wrapping
  `argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName())`.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/EnumTypeGenerator.java`:
  `buildValueDefinition` calls the helper for the `.value(...)` argument.
  (Needs visibility hop: either change helper to public on `BuildContext`,
  or move the helper to a package-visible utility class both packages can
  reach.)
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java`:
  `buildTextEnumMapping` switches to the helper.
- A separate Backlog item filed for the pre-resolution migration before
  this stopgap lands.

---

## Tests

Pipeline-tier is the primary behavioural tier per the rewrite test guide,
but the behaviour this item delivers ("graphql-java serializes the runtime
string to the SDL identifier") sits at the execution tier; the pipeline
test that pins emit shape is structural support only.

- `EnumSerializationExecutionTest.directiveValueRoundTripsThroughCoercing`
  (execution; primary): build a schema with an enum whose value declares
  `FODSELSNUMMER @field(name: "FØDSELSNUMMER")`, wire a fetcher that
  returns `"FØDSELSNUMMER"` (the runtime form), execute a query selecting
  the field, assert the response contains `"FODSELSNUMMER"` (the SDL form)
  with no `errors` entry. This is the test that would catch the reported
  regression before it reached a consumer.
- `EnumSerializationExecutionTest.enumInputStillByName` (execution): a
  mutation argument of the same enum type accepts the SDL identifier
  `FODSELSNUMMER` as input (not `"FØDSELSNUMMER"`). Pins the wire-boundary
  asymmetry the Design section names.
- *Primary arm only*:
  `EnumTypeClassificationTest.directiveValueLandsOnEnumValueSpec`
  (pipeline): SDL with `@field(name:)`-marked enum values goes through
  `GraphitronSchemaBuilder`; assert `EnumType.values()` carries entries
  whose `sdlName` is the GraphQL identifier and whose `runtimeValue` is
  the directive string (or the SDL name when absent). This is the
  classifier-side pin that emit and filter axes both rely on.
- *Stopgap arm only*: no additional pipeline test. The structural
  property ("emitter routes the directive string into `.value(...)`") is
  not load-bearing in a way the execution test doesn't already cover,
  and a Roaster walk that extracts the `.value(...)` literal would be a
  code-string assertion in parser-shaped clothing.

`EnumMappingResolver.buildTextEnumMapping`'s existing pipeline coverage
catches regressions on the filter-axis side. Compilation-tier coverage
rides on the existing `graphitron-sakila-example` compile.

---

## Phasing

Single landing in either arm. The behaviour delivered is identical: enum
values without `@field(name:)` continue to emit `.name(X).value(X)` exactly
as today; only the directive-bearing values change their `.value(...)`
argument from the SDL identifier to the directive's runtime string. Risk
of collateral breakage on existing schemas is bounded to the (small)
population of enum values that currently declare `@field(name:)`; for
those, the runtime mapping shifts to "matches the upstream representation",
which is the *intended* semantics and the only safe choice given that the
directive value is the upstream string.

The primary arm touches more files (model record, classify site, two
consumers, projection test rig) but eliminates the duplicated directive
read at the source. The stopgap arm touches three files and closes the
function-sharing gap only, leaving the value-sharing gap for a follow-up
Backlog item.

---

## Open question for the reviewer

*Primary (pre-resolution) or stopgap (shared helper)?* The principles
favour pre-resolution: it eliminates the duplicated directive read at the
source rather than narrowing it, and the bug returned in the first place
because the model shape invited the drift. The primary edit is bounded:
one new record, one component on `EnumType`, the classify-site walk at
`TypeBuilder.java:530`, the schema emitter and the filter-axis resolver
consuming the new shape, plus updates to the existing `EnumType`
projection rig in `GraphitronSchemaBuilderTest`. The stopgap is smaller
(three files, no model change) but leaves the structural cause in place.

Reviewer call. If primary lands here, the spec stays as written. If
stopgap lands, file a follow-up Backlog item for the pre-resolution
migration before the stopgap commits so the structural debt is tracked.
