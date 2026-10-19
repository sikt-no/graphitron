---
id: R1065
title: "An interface's own implements clause is dropped from the runtime schema"
status: In Review
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# An interface's own implements clause is dropped from the runtime schema

## Goal

An interface that implements another interface in the source SDL keeps that `implements` clause
in the schema a running graphitron service serves, so introspection and the federation
`_service { sdl }` show the interface as the author wrote it. The sis schema, a real consumer
schema, declares:

```graphql
interface Node { id: ID! }

interface Personrolle implements Node {
  id: ID!
  # ...
}
```

and a running service today answers with `interface Personrolle { ... }`, the `implements Node`
gone. A gateway or client generator reading the served schema then no longer knows that every
`Personrolle` is a `Node`. Object types already keep their clauses; only an interface's own clause
is lost. Dispatch is unaffected (`node(id:)` still resolves the implementations), so this is
schema fidelity alone.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19): the runtime SDL and
introspection lose `implements Node` on the interfaces `Personrolle` and `Studentvurdering`.

## Cause

The schema a service serves is not the parsed SDL. Graphitron emits one `<Name>Type` class per
object, interface and union, whose `type()` method rebuilds that type with graphql-java's builders,
and `Graphitron.buildSchema` assembles the *runtime schema* (the `GraphQLSchema` a consumer
executes against and federation prints `_service { sdl }` from) out of those classes.

`ObjectTypeGenerator.buildObjectTypeSpec` emits one `b.withInterface(GraphQLTypeReference.typeRef(...))`
statement per entry of `GraphQLObjectType.getInterfaces()`. `ObjectTypeGenerator.buildInterfaceTypeSpec`
has no counterpart, so `GraphQLInterfaceType.getInterfaces()` never reaches the rebuilt interface.

Upstream of the emitter the clause is held: capture records it
(`SdlFactCapture.captureImplements`, fed `InterfaceTypeDefinition.getImplements()`), `EmittedRegistry`
rewrites interface definitions through `transform`, which keeps it, and `SchemaReachability` already
walks `GraphQLInterfaceType.getInterfaces()`. So the assembled schema the emitter reads carries the
clause, and the emitter alone drops it. The Tests section's first step confirms this on the tree
rather than taking it from this reading.

## Implementation

`ObjectTypeGenerator.buildInterfaceTypeSpec`: after the description statement and before the
fields, the loop `buildObjectTypeSpec` already has, emitting
`b.withInterface(GraphQLTypeReference.typeRef("<name>"))` for each entry of
`interfaceType.getInterfaces()` in that order. `GraphQLInterfaceType.Builder.withInterface(GraphQLTypeReference)`
exists in graphql-java 25.0, and the reference is replaced at schema build exactly as on the object
arm, so the class javadoc's "emission order is irrelevant" holds unchanged.

No classifier, capture or model change. No user-manual change: the manual never said the clause
was dropped, and after this it is simply true that the served schema matches the source.

## Tests

Code-string matching on emitted bodies is banned (`docs/architecture/how-to/testing.adoc`), so the
acceptance evidence sits in `graphitron-sakila-example`'s pipeline tier, against the runtime schema
a consumer actually builds. It uses the federated fixture (`federated-schema.graphqls`, output
package `no.sikt.graphitron.generated.federated`) because that is where
`FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` compares the emitted `schema.graphqls`
file against the runtime schema, and where `_service { sdl }`, the surface the bug was reported on,
is served.

1. **Fixture.** Add to `federated-schema.graphqls`

   ```graphql
   interface Locatable implements Node { id: ID! }
   ```

   and change `Customer` and `Address` to `implements Node & Locatable`. `Locatable` has `Node`'s
   exact shape, and `Node` in this fixture is likewise returned by no field and reachable only
   through its implementers' clauses, so the new interface classifies along a path the fixture
   already exercises. With the fixture in and the fix not yet applied, run the module's tests and
   record which of the two tests below go red. Both should.

2. **Dedicated pin.** A test in `FederationBuildSmokeTest` that builds
   `Graphitron.buildSchema(b -> {}, fed -> {})`, executes
   `{ _service { sdl } __type(name: "Locatable") { interfaces { name } } }`, and asserts no errors,
   that the served SDL contains `interface Locatable implements Node`, and that introspection lists
   exactly `Node` as `Locatable`'s interface. One query covers both surfaces the evidence names.

3. **Parity gate.** `FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` needs no change: with
   the fixture in, the emitted file carries the clause and the runtime schema does not, which
   `SchemaDiffing` should report as an edit. It is the standing guard that catches this class of
   drift for the next builder method someone forgets.

What the first step's red run shows decides two edge cases. If the parity gate stays green before
the fix, `SchemaDiffing` does not see interface-to-interface edges; say so in the dedicated pin's
javadoc, the way `SynthesisedConnectionRuntimeDescriptionTest` records the same blind spot for
descriptions, so nobody later deletes the pin as redundant. If the emitted `schema.graphqls` lacks
the clause too, the cause above is wrong about upstream, and the fix extends to wherever the
assembled schema loses it before this item leaves In Progress, since the goal is the served schema
matching the source.

Done is demonstrated by the dedicated pin going from red to green on the fixture, with the parity
gate green.

## Implementation notes

The red run confirmed the Cause and the parity gate's coverage. With the fixture in and the fix
not applied, both `FederationBuildSmokeTest.interfaceKeepsItsImplementsClause` and
`emittedSdlMatchesRuntimeSchema` failed; the parity gate reported a single `DELETE_EDGE`
`Locatable -> Node` (label `implements Node`) going from the emitted file to the runtime schema, so
the emitted `schema.graphqls` carries the clause and `SchemaDiffing` does see interface-to-interface
edges. Neither edge case applies: no blind-spot javadoc on the pin, no fix upstream of the emitter.
With the `withInterface` loop in `ObjectTypeGenerator.buildInterfaceTypeSpec`, all fifteen tests in
the class pass.
