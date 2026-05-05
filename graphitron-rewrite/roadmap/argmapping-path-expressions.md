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

R53 shipped `argMapping` as a flat `javaParam: graphqlArg` mini-DSL on `@service`, `@tableMethod`, and every `@condition` site; the right-hand side names a single GraphQL slot at the directive's scope. This works when the Java method takes the wrapper input wholesale, but the Relay-style mutation pattern (a single `input:` argument carrying several typed sub-fields) leaves the author with two bad choices: accept the GraphQL wrapper into the service signature and unpack it there (leaking GraphQL-input shapes into a service that otherwise has no GraphQL knowledge), or add a thin façade method whose only job is to pluck and forward.

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
- **List segments lift naturally.** Each `[X]` segment along the path adds a `List<>` wrapper to the leaf type. Given:
  ```graphql
  someField(in: A): ...

  input A { a: String, b: [B] }
  input B { b: String,  c: [C] }
  input C { c: String }
  ```
  - path `in.a` → `String`
  - path `in.b` → `List<B>` (i.e. the Java type that today's R53 binding uses for an arg of type `B`)
  - path `in.b.c` → `List<List<C>>`
- **Null coalescing along the path.** Any intermediate-segment null short-circuits the whole leaf to null; no NPE, no per-element exception. List segments map element-wise; an element-level null inside a list propagates as a null entry in the resulting list (consistent with GraphQL's own input-list nullability).
- The leaf type binds to the Java parameter through the same lifters that apply to top-level slots: an `ID! @nodeId(typeName: "X")` leaf becomes `XRecord`, a `@table` leaf becomes its row record, a plain scalar becomes its Java mapping. Lifters apply *under* any `List<>` wrappers, so `in.b.c` with a `@nodeId`-tagged `c` produces `List<List<XRecord>>`.
- Two argMapping entries may resolve to the same leaf path (legal under the same "two overrides binding to the same slot" rule R53 already permits).

## Prior art: Apollo Connectors

Apollo Connectors' mapping language (`@connect(... selection: "...", body: """ $args.input { id quantity } """)`) is the closest existing design in the GraphQL ecosystem. What we borrow and what we don't:

- **Dot-path heads.** Apollo prefixes paths with `$args`, `$this`, `$config`. We have only one scope per directive site, so the prefix is redundant; bare-name heads matching today's R53 syntax are sufficient.
- **Array projection.** Apollo's `$args.filters.value` lifts to `[V]` when an intermediate segment is list-typed. We adopt the same rule, transposed to our static type system: each `[X]` segment adds a `List<>` wrapper. Apollo resolves this at runtime against JSON; we resolve it at generation time against the GraphQL schema.
- **Subselection blocks** (`$args.input { id quantity }`) and **methods** (`->first`, `->match`, etc.) are not adopted; subselection is a natural follow-up once the flat dot-path lands, methods are a runtime-transform language and have no place in a generation-time parameter binder.

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
