---
title: "Drop the assembled-schema rebuild in favour of per-variant graphql-java forms"
status: Backlog
bucket: cleanup
priority: 10
---

# Drop the assembled-schema rebuild in favour of per-variant graphql-java forms

Phase 5 of [firstclass-connection-types](firstclass-connection-types.md) rebuilds the assembled `GraphQLSchema` via `SchemaTransformer` so directive-driven `@asConnection` carriers carry their rewritten return type and pagination args. The rebuild only runs at generate time and is never seen by the runtime (which reconstructs its schema from emitted `<TypeName>Type.type()` calls in `GraphitronSchema.build()`).

Alternative: skip the rebuild; rebuild each carrier's parent `GraphQLObjectType` once via `parent.transform(b -> b.field(rewrittenField))` and stash it on the corresponding `GraphitronType` variant; emitters read per-variant `schemaType()` only. Already done for the synthesised types (Connection / Edge / PageInfo); this extends the pattern to rewritten parents.

*Saves* the two-step `additionalType + SchemaTransformer` dance (~80 lines of classifier code) and the bundle-coherence overhead. *Costs* the bundle's "every type reference resolves on `assembled.getType()`" invariant: any future build-time consumer that wants a coherent `GraphQLSchema` (SDL printer for client schemas, an introspection-based validator, federation manifest emitter) would have to be re-engineered.

Worth picking up when a concrete signal pushes the trade, e.g. an emitter explicitly preferring per-variant graphql-java forms over name-keyed `assembled.getType()` lookups, or schema-rebuild edge cases turning into recurring debugging cost. Until then, the rebuild is paying its rent and Phase 5 stays.
