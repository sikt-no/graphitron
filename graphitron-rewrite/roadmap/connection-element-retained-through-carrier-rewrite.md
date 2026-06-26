---
id: R390
title: "Connection carrier rewrite drops the element type subgraph from the rebuilt assembled schema"
status: Backlog
bucket: bug
theme: pagination
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Connection carrier rewrite drops the element type subgraph from the rebuilt assembled schema

A `@table` type reachable only through a directive-driven `@asConnection` carrier field has its
schema-class emission silently dropped, so generated `GraphitronSchema.java` names a `<Type>Type`
class that was never written and `javac` fails. Reported against RC20 (regression from the prior
version) for a two-level shape: a `@splitQuery @asConnection @reference` list field whose element
type then exposes a nested `@reference` list to a third type; the nested type's record and fetcher
emit, but its schema class does not.

Root cause is in `ConnectionPromoter.rebuildAssembledForConnections`. After the carrier rewrite
retypes the bare-list carrier to name its synthesised Connection, the element type is referenced
only through the Connection's `nodes` / Edge's `node` `GraphQLTypeReference`s.
`graphql.schema.SchemaTransformer` rebuilds its type map from the concretely-traversed graph
(type references are leaves, never followed), so an element type reachable in the original schema
only through that one carrier — together with its whole transitive subgraph — is pruned. Two
surface symptoms, same cause: when a surviving `typeRef` still points at the pruned type the rebuild
throws an NPE inside `GraphQLTypeResolvingVisitor`; otherwise the type is dropped silently and its
`<Type>Type` schema class is never emitted (the consumer's `cannot find symbol` build failure).
Because the element type is not concretely traversed, `SchemaTransformer` also never visits its
fields, so a *nested* carrier's own `@asConnection` rewrite is skipped: the field stays a bare list
while its fetcher is connection-shaped (the consumer's "variant 3" field/fetcher mismatch). The
fix pins each rewritten carrier's element type as a `GraphQLSchema.additionalType` before the
transform so the element (and its subgraph) stays concretely reachable and every nested carrier
rewrite applies. The example schema avoids the bug only because its connection element types happen
to be reachable elsewhere; a nested-only chain is the trigger.
