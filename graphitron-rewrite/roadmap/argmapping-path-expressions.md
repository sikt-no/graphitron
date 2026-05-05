---
id: R84
title: "Path expressions in argMapping"
status: Backlog
bucket: feature
priority: 5
theme: service
depends-on: []
---

# Path expressions in argMapping

R53 (`external-code-reference-arg-mapping`) shipped `argMapping` as a flat `javaParam: graphqlArg` mini-DSL on `@service`, `@tableMethod`, and every `@condition` site. The right-hand side names a single GraphQL slot at the directive's scope. This is enough when the Java method takes the wrapper input wholesale, but the very common "Relay-style mutation with a single `input:` argument carrying several typed sub-fields" pattern forces every service author to either accept the wrapper as a Java parameter and unpack it themselves, or write a thin façade method whose only job is to pluck and forward. Both paths are friction; the wrapper-acceptance path also leaks GraphQL-input shapes into service signatures that otherwise have no GraphQL knowledge.

Concrete trigger from the Sikt admissio→opptak migration:

```graphql
extend type Mutation {
    settKvotesporsmalAlgoritme(
        input: SettKvotesporsmalAlgoritmeInput!
    ): SettKvotesporsmalAlgoritmePayload!
        @service(service: {
            className: "...SettKvotesporsmalAlgoritmeService"
            method: "settKvotesporsmalAlgoritme"
        })
}

input SettKvotesporsmalAlgoritmeInput {
    kvotesporsmalId: ID! @nodeId(typeName: "Kvotesporsmal")
    kvotesporsmalAlgoritmeId: ID @nodeId(typeName: "KvotesporsmalAlgoritme")
}
```

The service signature the author wants is `settKvotesporsmalAlgoritme(KvotesporsmalRecord kvotesporsmal, KvotesporsmalAlgoritmeRecord algoritme)`. With today's argMapping there is no way to bind `kvotesporsmal` to `input.kvotesporsmalId` and `algoritme` to `input.kvotesporsmalAlgoritmeId`; the only available slot is the wrapper `input` itself.

## What the extension is

Allow the right-hand side of an `argMapping` entry to be a **dot-path expression** that walks into nested GraphQL input fields, e.g.:

```graphql
@service(service: {
    className: "..."
    method: "settKvotesporsmalAlgoritme"
    argMapping: """
        kvotesporsmal: input.kvotesporsmalId,
        algoritme:     input.kvotesporsmalAlgoritmeId
    """
})
```

Semantics, generation-time:

- The head segment names a slot at the directive's scope (a GraphQL argument for `@service` / `@tableMethod` / argument-level `@condition`; an input field for input-field-level `@condition`).
- Each subsequent segment names a field on the resolved input-object type at that depth. Walking through a scalar, enum, union, or interface is a structural rejection.
- **List segments lift naturally.** Each `[X]` segment along the path adds a `List<>` wrapper to the leaf type:
  ```graphql
  input A { a: String, b: [B] }
  input B { b: String,  c: [C] }
  input C { c: String }
  ```
  - path `a` → `String` (scalar)
  - path `b` → `List<B-leaf>`
  - path `b.c` → `List<List<C-leaf>>`
- **Null coalescing along the path.** Any intermediate-segment null short-circuits the whole leaf to null; no NPE, no per-element exception. If `b` is null on a given input, `b.c` is null for that input. List segments map element-wise; an element-level null inside a list propagates as a null entry in the resulting list (consistent with GraphQL's own input-list nullability).
- The leaf type is what binds to the Java parameter, subject to the same lifters that already apply to top-level slots: an `ID! @nodeId(typeName: "X")` leaf binds to `XRecord` (via the existing nodeId-decode lifter), a `@table` leaf binds to its row record, a plain scalar binds to its Java mapping. Lifters apply *under* the `List<>` wrapper, so `b.c` with a `@nodeId`-tagged `c` still produces `List<List<XRecord>>`.
- Two argMapping entries may resolve to the same leaf path (legal under the same "two overrides binding to the same slot" rule R53 already permits).

## Prior art: Apollo Connectors

Apollo Connectors' mapping language (`@connect(... selection: "...", body: """ $args.input { id quantity } """)`) is the closest existing design in the GraphQL ecosystem. Useful traits to borrow:

- **Dot-path with a scope head.** Apollo prefixes paths with `$args`, `$this`, `$config`. We have only one scope per directive site (the slot set), so the prefix is redundant; bare-name heads (matching today's R53 syntax) are sufficient and keep the migration trivial.
- **Array projection.** Apollo's `$args.filters.value` lifts to `[V]` when an intermediate segment is list-typed. We adopt the same rule, transposed onto our static type system: each `[X]` segment along the path adds a `List<>` wrapper to the resolved type (see "What the extension is" above). Apollo handles this at runtime against JSON; we resolve it at generation time against the GraphQL schema.
- **Subselection blocks.** Apollo lets `$args.input { id quantity }` pick multiple fields from a common parent in one go. Real ergonomic value for the wrapper-input case; out of scope for this item but a natural follow-up once the flat dot-path lands.
- **Methods (`->first`, `->match`, etc.).** Out of scope. We are binding typed parameters to typed GraphQL slots at generation time; runtime transforms have no place here.

What we explicitly do **not** take from Apollo: inline JSON literals and the `$.` shorthand. Neither is needed to unblock the trigger case.

## Parser

Take ownership of the existing `selection/` package (`GraphQLSelectionParser`, `Lexer`, `Token`, `TokenKind`, `ParsedField`, `ParsedArgument`, `ParsedValue`) and rewrite it to serve both `@experimental_constructType(selection: ...)` (R69) and `argMapping` path expressions. The two grammars are the same shape — comma-separated `key: <expression>` entries where the right-hand side resolves to a typed value (a column reference today, a path expression with this item) — and the lexer already handles the relevant tokens. One parser, two binders. If the grammars later diverge enough that the shared parser starts costing more than it saves, fork at that point; do not pre-emptively split.

The R53-era `parseArgMapping` in `ArgBindingMap` is too thin to host the path-walking logic and should be retired in favour of the rewritten `selection/` parser.

## Error-message extension

R59 sharpened `ServiceCatalog`'s parameter-mismatch messages with concrete `argMapping: "<javaParam>: <graphqlArg>"` suggestions. Extend the same machinery so the rejection mentions path expressions:

- **Floor:** every parameter-mismatch rejection that already prints an `argMapping` example also mentions that the right-hand side may be a dot-path into a nested input field, so an author who hits the error has a pointer to the capability without having to find it in the docs.
- **Stretch:** when the unmatched Java parameter's type matches a reachable nested path under one of the available slots **unambiguously**, pre-fill the path in the suggestion (e.g. `argMapping: "kvotesporsmal: input.kvotesporsmalId"`). If multiple paths reach a compatible leaf, fall back to the floor hint rather than guess.

## Out of scope for this item

- Subselection blocks (`input { id, name }`); revisit as a follow-up once the dot-path lands.
- Methods / runtime transforms (Apollo's `->first`, `->match`, etc.).
- Renaming or stabilising the directive surface.
