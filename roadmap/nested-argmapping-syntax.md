---
id: R249
title: "Nested @argMapping syntax via GraphQLSelectionParser"
status: Backlog
bucket: feature
priority: 4
theme: classification-model
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Nested @argMapping syntax via GraphQLSelectionParser

Today's `@argMapping` parses as comma-separated `javaParam: <path>` entries where each right-hand side is a dot-path into the SDL args. To construct a Java record or JavaBean whose fields come from *scattered* SDL positions (rather than a single anchored input-object), authors have no syntactic recourse: they must either restructure the SDL to mirror their Java type, or carry the binding logic into the service method itself.

The needed shape is the nested object form:

```graphql
@service(
    className: "...",
    method: "...",
    argMapping: """
        request: { customerId: input.customer.id, productSku: input.product.sku, quantity: input.qty }
    """
)
```

The right-hand side is itself a `{ fieldName: <path>, ... }` mini-DSL. Each entry maps a Java record component (or JavaBean setter target) to its own SDL path. R238's carrier model already accommodates this: paths live on `ValueShape` leaves, not on `MappingEntry` entries, so scattered fields are first-class without further restructuring.

## What exists already

- **`graphql.schema.visitor.GraphQLSelectionParser`** in the `selection/` package already produces `ParsedValue.ObjectValue(List<ParsedArgument>)` for nested `{ key: value }` forms. The lexer and parser handle the syntax today; only the directive-side wiring is missing for `@argMapping`.
- **R69 (`experimental-construct-type`)** is the architectural sibling on the output side: `@experimental_constructType(selection: "...")` uses the same parser to project DB columns onto non-table-backed GraphQL fields. This item is the input-side counterpart.
- **R238 (`methodcall-walker-carrier`)** lands the carrier model and walker spine. Its `CompleteArgMapping` / `MappingEntry` / `ValueShape` taxonomy represents default and scattered mappings uniformly; only the walker's Java-driven descent path (placeholder in R238) needs the real implementation.

## What's missing

- **Parser dispatch**: `ArgBindingMap.parseArgMapping` today expects a flat comma-separated mini-DSL. It needs to dispatch on whether the right-hand side is a dot-path (current behaviour) or an `ObjectValue` (route through `GraphQLSelectionParser`).
- **Walker Java-driven descent**: when an entry resolves to `ObjectValue`, the walker iterates the Java target's record-components or JavaBean properties and resolves each leaf's SDL path independently. R238 sketches the algorithm; this item implements it.
- **Error arms**: nested-form-specific failures — a record component named in the nested form doesn't exist on the Java target, a nested form points at a non-record Java target, mixed dot-path + nested form on the same entry, etc.
- **LSP**: completions, hover, and goto-definition for nested-form entries (the existing `argMapping` LSP machinery treats the value as a flat string today).

## Out of scope for this item

- Output-side `@experimental_constructType` (R69; sibling).
- Removing the comma-separated dot-path form (kept as the common case; nested form is opt-in).
