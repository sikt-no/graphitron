---
id: R229
title: "EnumTypeGenerator: honor @field(name:) as runtime value"
status: Spec
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
> `Can't serialize value ... Unknown value 'FØDSELSNUMMER'`. The fix reads
> `@field(name:)` on each enum value and uses the directive value (defaulting
> to `value.getName()`) as the `.value(...)` argument, leaving `.name(...)` as
> the SDL identifier. The runtime mapping for enum values already lives in
> `EnumMappingResolver.buildTextEnumMapping`; this item is the schema-side
> twin of that classifier output.

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
`.name(...)` — the directive value is, by definition, the *runtime*
representation, not an alternate SDL name. This is what `.value(...)` is for
in graphql-java's API.

---

## Design

Single-file change in `EnumTypeGenerator.buildValueDefinition`. Read
`@field(name:)` off the `GraphQLEnumValueDefinition` exactly as
`EnumMappingResolver.buildTextEnumMapping` does, fall back to
`value.getName()` when absent. Use the directive value as the `.value(...)`
argument; keep `.name(...)` as the SDL identifier unchanged.

The `argString(value, DIR_FIELD, ARG_NAME)` helper and the `DIR_FIELD` /
`ARG_NAME` constants on `BuildContext` are the existing API for reading this
directive value; `EnumTypeGenerator` already lives in the same module and
can import them the same way `EnumMappingResolver` does.

Shape after the change:

```java
private static CodeBlock buildValueDefinition(GraphQLEnumValueDefinition value) {
    String runtimeValue = BuildContext.argString(value, BuildContext.DIR_FIELD,
                                                  BuildContext.ARG_NAME)
                                       .orElse(value.getName());
    var block = CodeBlock.builder()
        .add("$T.newEnumValueDefinition()", ENUM_VALUE)
        .add(".name($S)", value.getName())
        .add(".value($S)", runtimeValue);
    // ...description, deprecation, applied directives unchanged...
}
```

Note on visibility: `DIR_FIELD` / `ARG_NAME` / `argString` are
package-private in `BuildContext`. `EnumTypeGenerator` lives under
`no.sikt.graphitron.rewrite.generators.schema`, a different package. Two
options:

1. Promote the three names to public on `BuildContext`. Mechanically
   smallest; widens the API surface of a class that is internal already.
2. Add a narrow public static helper to `BuildContext` (or a new
   package-visible utility) named for the use case, e.g.
   `BuildContext.enumValueRuntimeString(GraphQLEnumValueDefinition)`,
   returning the resolved string. Encapsulates the directive lookup so the
   classifier and the schema emitter call the same function.

**Recommendation:** option 2. `EnumMappingResolver.buildTextEnumMapping`
already implements the same lookup; lifting it onto a shared helper makes
the two call sites use one function rather than two parallel ones. The
helper is the contract; the schema emitter and the filter classifier
become consumers.

`AppliedDirectiveEmitter.applicationsFor(value)` (line 86–88) continues to
emit the full directive application onto the enum value so consumers can
still read `@field(name: "FØDSELSNUMMER")` via introspection or applied
directives; the runtime-value lift is additive and does not strip the
directive itself.

### Where the change does not propagate

- `parseValue` (input enum coercion) is unaffected. The directive is a
  serialization-side concern for the cases this item addresses; input enums
  arrive as GraphQL value identifiers and continue to match by name. Pinned
  by the `enum-input-still-by-name` test below.
- The legacy `graphitron-codegen-parent` generator is out of scope per
  `CLAUDE.md`. Any analogous bug there is the legacy maintainers' to
  reproduce.
- `EnumMappingResolver`'s callers consume the same mapping for DB-side
  filter values and `TextMapLookup` extractions; behaviour there is
  unchanged because they already read the directive. This item brings the
  schema emit into line with that existing behaviour.

---

## Implementation sites

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/EnumTypeGenerator.java`:
  `buildValueDefinition` reads the directive value and uses it for
  `.value(...)`.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`:
  add a small public helper (`enumValueRuntimeString` or similar) wrapping
  the existing `argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName())`
  pattern. Optional but recommended (option 2 in Design above).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java`:
  switch `buildTextEnumMapping` to call the new helper, so the lookup lives
  in one place.

---

## Tests

Pipeline-tier is the primary behavioural tier per the rewrite test guide.
Three cases:

- `EnumTypeGeneratorTest.fieldNameDirectiveBecomesRuntimeValue` (pipeline):
  SDL declares `FODSELSNUMMER @field(name: "FØDSELSNUMMER")`. Run through
  `GraphitronSchemaBuilder` (or directly through `EnumTypeGenerator`,
  whichever the existing test setup uses for this generator); assert the
  emitted `TypeSpec` contains `.name("FODSELSNUMMER").value("FØDSELSNUMMER")`.
  Inspection through `JavaFile.toJavaFileObject()` + `javac` / Roaster per
  the code-string-assertion ban, not raw `.toString()` matching.
- `EnumTypeGeneratorTest.unmarkedValueDefaultsToOwnName` (pipeline):
  Existing default case. SDL value with no `@field(name:)`; assert
  `.name("FOO").value("FOO")` is preserved. Pins the fallback.
- `EnumSerializationExecutionTest.directiveValueRoundTripsThroughCoercing`
  (execution): build a schema with `EnumWithFieldName` containing
  `FODSELSNUMMER @field(name: "FØDSELSNUMMER")`, wire a fetcher that
  returns `"FØDSELSNUMMER"` (the runtime form), execute a query selecting
  the field, assert the response contains `"FODSELSNUMMER"` (the SDL form)
  with no `errors` entry. This is the test that would catch the reported
  regression before it reached a consumer.
- `EnumSerializationExecutionTest.enumInputStillByName` (execution): a
  mutation argument of the same enum type accepts the SDL identifier
  `FODSELSNUMMER` as input (not `"FØDSELSNUMMER"`). Pins that the directive
  doesn't accidentally rewire input coercion.

No unit-tier coverage needed: the directive-read helper is a one-liner over
existing `argString` plumbing, and `EnumMappingResolver.buildTextEnumMapping`
is already pipeline-tier covered (any regression there would surface in
existing tests). Compilation-tier coverage rides on the existing
`graphitron-sakila-example` compile.

---

## Phasing

Single landing. The change is one file (plus the optional helper extraction)
and is purely additive on the schema emit side: enum values without
`@field(name:)` continue to emit `.name(X).value(X)` exactly as today; only
the directive-bearing values change their `.value(...)` argument. Risk of
collateral breakage on existing schemas is bounded to the (small) population
of enum values that currently declare `@field(name:)`; for those, the runtime
mapping shifts from "matches SDL identifier" to "matches the directive's
runtime string", which is the *intended* semantics and the only safe choice
given that the directive value is the upstream representation.

---

## Open question for the reviewer

*Helper placement.* `BuildContext.enumValueRuntimeString(...)` vs. a method
on `EnumMappingResolver` vs. inline duplication of the
`argString(...).orElse(value.getName())` line at the emit site.
Recommendation is the `BuildContext` helper (option 2 in Design), but the
emitter→classifier dependency direction is the place to relitigate if
that's the wrong layering. Reviewer input wanted before implementation
starts; the choice doesn't affect the behaviour the fix delivers.
