---
id: R69
title: "Implement @experimental_constructType"
status: Backlog
bucket: feature
priority: 5
theme: structural-refactor
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

## Out of scope for this item

Renaming the `@experimental_` prefix once the feature is stable (separate stabilisation step).
