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
- Each subsequent segment names a field on the resolved type at that depth. Walking through a non-input-object type (scalar, enum, list, union, interface) is a structural rejection.
- The leaf type is what binds to the Java parameter, subject to the same lifters that already apply to top-level slots: an `ID! @nodeId(typeName: "X")` leaf binds to `XRecord` (via the existing nodeId-decode lifter), a `@table` leaf binds to its row record, a plain scalar binds to its Java mapping, and so on.
- Two argMapping entries may resolve to the same leaf path (legal under the same "two overrides binding to the same slot" rule R53 already permits).

## Prior art: Apollo Connectors

Apollo Connectors' mapping language (`@connect(... selection: "...", body: """ $args.input { id quantity } """)`) is the closest existing design in the GraphQL ecosystem. Useful traits to borrow:

- **Dot-path with a scope head.** Apollo prefixes paths with `$args`, `$this`, `$config`. We have only one scope per directive site (the slot set), so the prefix is redundant; bare-name heads (matching today's R53 syntax) are sufficient and keep the migration trivial.
- **Subselection blocks.** Apollo lets `$args.input { id quantity }` pick multiple fields from a common parent in one go. This is real ergonomic value for the wrapper-input case; worth folding into the Spec discussion as an optional second iteration once the flat dot-path lands.
- **Methods (`->first`, `->match`, etc.).** Out of scope. We are binding typed parameters to typed GraphQL slots at generation time; runtime transforms have no place here.

What we explicitly do **not** take from Apollo: array projection (`$args.filters.value` → `[V, V, V]`), inline JSON literals, and the `$.` shorthand. Each is a distinct can of worms (list-input semantics, identity rules) and none of them is needed to unblock the trigger case.

## Open questions for Spec

1. **List inputs.** What does `argMapping: "id: ids.itemId"` mean if `ids` is `[InnerInput!]!`? Reject as structural? Lift to `List<X>`? Apollo lifts; we likely reject in v1 and revisit alongside the bulk-mutation work tracked separately.
2. **Nullability propagation.** If an intermediate path segment is nullable (e.g. `input.optionalSubInput.field`), should the Java parameter type widen to nullable, or should we reject the path and require the author to flatten the input? Lean toward the latter: the lifter set is keyed on leaf type, and "nullable along the path" is a separate axis we don't model today.
3. **Parser home.** The `selection/` package already houses the `@experimental_constructType(selection: ...)` parser (R30 audit, R69). Path expressions could live in a sibling parser or share lexer infrastructure. Decide during Spec; the existing `parseArgMapping` in `ArgBindingMap` is too thin to host this without a rewrite.
4. **Error-message shape.** R59 sharpened `ServiceCatalog`'s parameter-mismatch messages with concrete `argMapping: "<javaParam>: <graphqlArg>"` suggestions. Extend the same machinery: when the Java parameter's type does not match any flat slot **but does** match a reachable nested path, suggest the dot-path remediation in the rejection.
5. **Wire-through inventory.** R53 enumerated seven reflect call sites (`resolveServiceField`, two `@tableMethod` arms, `buildArgCondition`, `buildFieldCondition`, `BuildContext.resolveConditionRef`, `buildInputFieldCondition`). Each must learn to walk a path, not just look up a slot. Path-step `@condition` keeps its empty-slot guard.

## Out of scope for this item

- Subselection blocks (`input { id, name }`); revisit as a follow-up once the dot-path lands.
- Methods / runtime transforms.
- List/array projection across path segments.
- Renaming or stabilising the directive surface.
