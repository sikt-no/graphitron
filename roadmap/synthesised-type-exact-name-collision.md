---
id: R931
title: "Reject a synthesised Connection or Edge name that exactly collides with another registered type"
status: Backlog
bucket: bug
priority: 4
theme: diagnostics
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# Reject a synthesised Connection or Edge name that exactly collides with another registered type

## Goal

Make an exact name collision between a synthesised type and any other registered type a typed rejection at the carrier, instead of a silent last-registration-wins in `ConnectionPromoter.registerSynthesised` on the schema-transform side and a silently misrouted mint on the capture side. A synthesised type is one `@asConnection` mints for a carrier field (the field that carries the directive): the `<ConnectionName>` Connection and `<ConnectionName>Edge` Edge types.

Today the only collision detector is `GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions`, which groups registry entries by their case-folded name; an exact collision produces one entry, so its group has size 1 and it is invisible to that detector. Two shapes reach it. A `connectionName:` override on one carrier that spells another carrier's derived edge name (`connectionName: "QueryFooConnectionEdge"` beside a plain `Query.foo: [Foo!]! @asConnection`) registers a `ConnectionType` and an `EdgeType` under one name; `TypeRegistry.register` demotes the coordinate to `UnclassifiedType` with a "classified incompatibly" message that names neither carrier. An override spelling an author-declared type's name registers over it. On the capture side (`MacroCapture`) the same shapes do not collide but misroute: `mintType` yields when the coordinate is already claimed, yet the minted fields still land, so `cursor` and `node` are written onto whichever type already owns the name.

The intra-carrier instance (an override with no `Connection` substring, whose edge name equalled its connection name under the substitute formula) is made unreachable by the append formula that `ConnectionNaming.defaultEdgeName` introduces; this item covers the cross-carrier and synthesised-versus-declared instances that no formula can rule out. The fix wants `registerSynthesised` to refuse a name already registered under a different `GraphitronType` arm with a `Rejection` naming both parties and the carrier coordinate, mirrored by a capture-side detection row so the fact store carries the same verdict, and a validator mirror per the typed-rejection contract in `docs/architecture/explanation/typed-rejection.adoc`.
