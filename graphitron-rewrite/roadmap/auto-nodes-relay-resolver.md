---
title: "Auto-emit Relay `nodes` when `node` exists"
status: Backlog
bucket: architecture
priority: 7
---

# Auto-emit Relay `nodes` when `node` exists

The Relay `nodes(ids: [ID!]!): [Node]!` resolver is mechanical composition
over the `node(id: ID!): Node` resolver: call `node` per id, `allOf` the
results. It's not a `@service` extension because the service would have to
know about `DataFetcher` to express it; it's resolver-level composition.

Today this is hand-written with `notGenerated` (one example in a downstream
project; pattern is the same wherever `nodes` is exposed). The hand-written
shape:

```java
public DataFetcher<CompletableFuture<List<Node>>> nodes(NodeIdHandler nodeIdHandler) {
    return env -> {
        List<String> ids = env.getArgument("ids");
        var nodeFetcher = node(nodeIdHandler);
        var cfs = ids.stream()
            .map(id -> nodeFetcher.get(DataFetchingEnvironmentImpl
                .newDataFetchingEnvironment(env)
                .arguments(Map.of("id", id))
                .build()))
            .toList();
        return CompletableFutureKit.allOf(cfs);
    };
}
```

Direction: when the rewrite emits a `node` resolver and the schema also
declares `nodes: [Node]` with the standard Relay shape, emit the fan-out
resolver automatically. Schema authors get nothing to declare; either the
shape is detected structurally or `@nodeIdHandler` (or whatever the rewrite
calls the carrier of `node`) carries it.

Likely smaller than it looks: a single emitter addition adjacent to the
existing `node` emission, no new directives, no service-level changes.

Related: [`service-context-value-registry.md`](service-context-value-registry.md)
covers the multi-tenant fan-out case for `@service`; this is the
resolver-composition cousin.
