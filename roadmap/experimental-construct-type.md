---
id: R69
title: "Implement @experimental_constructType"
status: Backlog
bucket: feature
priority: 5
theme: classification-model
depends-on: []
---

# Implement @experimental_constructType

The `@experimental_constructType(selection: "...")` directive is declared in `directives.graphqls` and stripped from the emitted schema by `SchemaDirectiveRegistry`, but no classifier, model carrier, or emitter exists for it yet.

## What it does

Allows a schema field to be resolved by projecting raw database columns onto a non-table-backed GraphQL type, without requiring a Java backing class or a `@record` parent. The `selection` argument is a comma-separated `graphqlField: SQL_COLUMN` mapping string, e.g.:

```graphql
fullName: String @experimental_constructType(selection: "firstName: FIRST_NAME, lastName: LAST_NAME")
```

At generation time the generator parses the `selection` string and emits a `DSL.select(...)` projection that feeds the named columns into the type's fields.

## What exists

- Directive definition in `directives.graphqls:341`
- `SchemaDirectiveRegistry` already lists it as generator-only (it is stripped correctly)
- `selection/` package (`GraphQLSelectionParser`, `Lexer`, `Token`, `TokenKind`, `ParsedField`, `ParsedArgument`, `ParsedValue`) provides the generation-time parser for the `selection` string; this was confirmed as the correct home by the R30 audit

## Work needed

1. **Classifier**: read `@experimental_constructType` in `FieldBuilder` (or a new `ConstructTypeDirectiveResolver`); parse `selection` via `GraphQLSelectionParser`; produce a new `GraphitronField` variant (e.g. `ConstructedTypeField`) carrying the parsed field-to-column bindings
2. **Model**: sealed variant with column bindings; validate that every named SQL column exists in the parent table's jOOQ catalog
3. **Emitter**: emit a `DSL.select(col1.as("graphqlField1"), col2.as("graphqlField2"), ...)` projection in the generated `$fields` method body
4. **Tests**: unit-tier pipeline cases; execution-tier test against Sakila schema

## Documentation (withheld for v1 by R400)

R400 withholds `@experimental_constructType` from the first-release advertised surface (it has no
emitter yet, so v1 must not present it as available). When this item lands the implementation, also
restore its documentation. The reference page
`docs/manual/reference/directives/experimental_constructType.adoc` is removed by R400 Stage 2 but
stays in git history; recover it anchor-free (no hardcoded SHA):
`git log --oneline --diff-filter=D -- docs/manual/reference/directives/experimental_constructType.adoc`,
then `git checkout <that-commit>^ -- <path>`. Restore its index/category entries and remove
`experimental_constructType` from the `WITHHELD_FROM_V1` set in `DirectiveSupportReport` so it
reappears under "Supported directives". Unlike R404 (`@sourceRow`, already implemented), this
directive's doc reintroduction is gated on the implementation here, not on a promotion decision, so
it lives on this item rather than a separate reintroduction ticket.

## Out of scope for this item

Renaming the `@experimental_` prefix once the feature is stable (separate stabilisation step).

## Related

- **R249 (`nested-argmapping-syntax`)** is the input-side sibling: it wires `GraphQLSelectionParser` into `@argMapping`'s right-hand side so that `paramName: { fieldA: input.x, fieldB: input.other.y, ... }` parses through the same `ObjectValue` arm. Both items lean on the `selection/` package's parser; this item drives the output-side projection, R249 drives the input-side construction.
- **R238 (`methodcall-walker-carrier`)** lands the carrier model with paths on `ValueShape` leaves, which represents R249's nested-form construction uniformly with the default-mapping case.
