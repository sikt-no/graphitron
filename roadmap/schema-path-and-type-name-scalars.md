---
id: R1204
title: "A Path or TypeName argument is checked against the subgraph schema, and a routine binds the path's start and end types"
status: Spec
bucket: feature
priority: 1
theme: mutation-write
depends-on: []
created: 2026-11-22
last-updated: 2026-11-22
---

# A Path or TypeName argument is checked against the subgraph schema, and a routine binds the path's start and end types

## Goal

An author can declare scalars that Graphitron itself knows how to generate, chosen from a closed list. The first two are `SCHEMA_PATH`, a path through the subgraph's own GraphQL schema written as a type name followed by field names (`Sak.soker`, `Soknad.saker.nodes`), and `TYPE_NAME`, the name of a type in that schema. A value of either arriving as input is checked against the schema before any call runs, so an invalid one is answered as an error on that argument and never reaches the routine or service. Graphitron decodes a valid value into an internal record of its components, and a call takes the components it needs through `argMapping`: a `@routine` (a mutation backed by a database function) binds the path's start type and end type to parameters of their own, so the function compares plain type names and needs no knowledge of the schema. FS opptak needs this for its outbound messages to applicants: a message names whom it goes to as a path from the context it is sent in (`mottakersti`), and an automatic message names the path from the event that triggers it to that context (`kontekststi`).

From opptak's design (`docs/kommunikasjon-med-soker-api.md` on fs-plattform's `arkitektur/kommunikasjon-utsending` branch), with the scalars under English names:

```graphql
scalar Path @generatedScalar(kind: SCHEMA_PATH)
scalar TypeName @generatedScalar(kind: TYPE_NAME)

input OpprettAutomatiskUtsendingInput {
    hendelsestype: TypeName! @typeImplements(interface: "Hendelse")
    kontekststi: Path!
    mottakersti: Path! @typeImplements(interface: "Node")
}

type Mutation {
    opprettAutomatiskUtsending(input: OpprettAutomatiskUtsendingInput!): UtsendingPayload
        @routine(name: "opprett_automatisk_utsending", argMapping: """
            pHendelsestype: input.hendelsestype.name,
            pKontekststi: input.kontekststi.path,
            pKontekstStart: input.kontekststi.startType,
            pKontekstSlutt: input.kontekststi.endType,
            pMottakersti: input.mottakersti.path,
            pMottakerStart: input.mottakersti.startType,
            pMottakerSlutt: input.mottakersti.endType
        """)
}
```

With `mottakersti: "Sak.soker"` the function receives `'Sak.soker'`, `'Sak'` and `'Soker'`. With `"Sak.sokr"` the request answers `Sak has no field sokr` at `input.mottakersti` and the function is not called. With `hendelsestype: "Sak"` it answers that `Sak` does not implement `Hendelse`. The function's own rules (the recipient path ends in `Soker`, the context path ends where the recipient path starts) stay in the function, written over type names.

## User documentation (first-client check)

Draft for a new manual page, `docs/manual/reference/directives/generatedScalar.adoc`, beside `scalarType.adoc`.

> ### `@generatedScalar`
>
> `@scalarType` binds a scalar to a constant you write. `@generatedScalar` asks Graphitron to generate the scalar itself, for a kind it knows:
>
> ```graphql
> directive @generatedScalar(kind: GeneratedScalar!) on SCALAR
> enum GeneratedScalar { SCHEMA_PATH TYPE_NAME }
> ```
>
> The scalar's name is yours; `kind` says what it is. A scalar takes `@scalarType` or `@generatedScalar`, not both. On the wire every generated scalar is a string. Inside the generated code a value is decoded into a record of its components, and a call takes the components it needs by naming them in `argMapping`, as it takes a key column from a node id. A binding that names no component is an error, since the record itself is internal.
>
> | Kind | A value is | Components |
> |---|---|---|
> | `TYPE_NAME` | the name of an object type in this subgraph, as `__typename` gives it: `Sak`, `SoknadLevert` | `name` |
> | `SCHEMA_PATH` | a type name followed by zero or more field names, separated by dots: `Soknad`, `Sak.soker`, `Soknad.saker.nodes` | `path`, `startType`, `endType` |
>
> **A path** follows the schema exactly as clients see it: each field must exist on the type the previous step reached, lists and non-null wrappers are stepped through, a connection is stepped through with `nodes` (or `edges.node`), and arguments are not part of a path. Its start type is its first name, an object or interface type; its end type is the type its last field returns, or the start type when there is no field.
>
> **Requiring an interface.** `@typeImplements(interface: "Node")` on an argument or input field requires a `TYPE_NAME` value to name a type implementing that interface, or a `SCHEMA_PATH` value to start in one.
>
> **When a value is wrong**, the field answers a client error at the argument's path, as a malformed node id does, and nothing is called. Route it to your payload with an `@error` type handling `GraphitronClientException`, as for node ids.
>
> **Only this subgraph.** A value can only name types this subgraph declares. A type owned by another subgraph is reachable only through a local stub of it.
>
> **As output**, a field of a generated scalar reads a string column and returns it as stored, without checking, since a stored value may predate a schema change.

## Implementation

The checks run where the input is decoded, at the call boundary, against the schema the running service already serves (`DataFetchingEnvironment.getGraphQLSchema()`). No copy of the schema is generated, and the scalar's own coercion only accepts a string, so every rule about these values has one error surface: a `GraphitronClientException` at the argument's path, routable through `@error` like a node-id decode failure. `ProjectedKeyReads` is the precedent for the shape: decode once, raise before the call, hand components to parameters.

### Model

- **Directives.** `@generatedScalar(kind: GeneratedScalar!)` on `SCALAR` with its enum, and `@typeImplements(interface: String!)` on `ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`, declared in `directives.graphqls` and captured like every directive application. New kinds are new enum values.
- **What a scalar is**, at type grain: one row per scalar carrying `@generatedScalar`, keyed by the scalar, with its kind. The landing rule, the class rule and the emitters join it. Owner, grain sentence, mark and sweep as every stored relation owes.
- **Its components**, at kind grain: one row per kind and component, with the component's Java class (`String` for all four today). A stated fact rather than knowledge in each reader, so a new kind adds rows, not rules.
- **Its Java class.** `graphitron_scalar_class_rule` gains a third source beside the built-ins and `@scalarType`: a generated scalar's class is its kind's internal record in the output package, with a column recording which source answered. The record is generated (Java 17, like all generated code) and internal: no consumer code names it, which is why calls take components rather than the record.
- **`@scalarType` beside `@generatedScalar`** is two claims on one scalar and draws `CLAIMS_CONFLICT`, not a code of its own.
- **Components are a landing.** `graphitron_argmapping_landing.receives` today says whether a node-id landing hands over the whole id or a key column. It widens to say what any landing hands over, with a value for a generated scalar's component, matched from the trailing name against the kind's components. Its CHECK and comment are rewritten to the wider statement. No sibling relation.
- **Defects**, each a stored fact with a guidance fragment:
  - a generated scalar bound with no component, and a trailing name that is not one of its kind's components;
  - a parameter bound to a component whose SQL or Java type does not take the component's class, as one rule over the landing (the counterpart of `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` for a key column), raised from the routine's or method's captured parameter types;
  - `@typeImplements` naming a type that is not an interface in this subgraph, or placed on an element whose type is not a `TYPE_NAME` or `SCHEMA_PATH` scalar;
  - an output field of a generated scalar whose column is not a string;
  - a generated scalar bound at a call kind whose call is still rendered by the walk (today `@condition` and child `@routine` steps), as an `UNSUPPORTED` deferral naming the site, never a Java refusal.

### Emit

- A sink in `emit`, beside the routine-write and service emitters, that decodes each bound generated-scalar argument once per call, ahead of the call and any transaction, into a local of its record type that the parameter reads take components from. It is its own sink, not a widening of `render.ProjectedKeyReads`, which the walk's renderers still share.
- The records and their decoders are generated helpers in the output package. A `SCHEMA_PATH` decoder, given the served schema and a string, parses `Name(.Name)*`, resolves the start as an object or interface type (no `__` types), steps each field through the field's type with list and non-null wrappers removed, checks a required interface, and returns the record or throws `GraphitronClientException` with the argument path and a message naming the failing step. A `TYPE_NAME` decoder checks one name the same way.
- The scalar registration in `GraphitronSchema` is a generated string `GraphQLScalarType` under the author's scalar name, in place of a `@scalarType` constant; its `serialize` accepts the stored string.
- Call kinds: root and child `@service` calls and root `@routine` writes, all emitted from the store. A `@condition` or child `@routine` binding takes the deferral above until its call is emitted from the store.

## Tests

- Fact documents, with defect rows beside fact rows: the scalar's kind, its components and its Java class; a `startType` and an `endType` landing; each defect above with a near miss (`x.startType` beside `x.startTyp`; `x` bound with no component beside `x.path`; a string parameter beside an integer one; `@typeImplements(interface: "Node")` beside one naming an object type; `@scalarType` with `@generatedScalar`).
- Execution tier in `graphitron-sakila-example`, with a small function added to the sakila database's `init.sql` that records what it receives: a valid path reaches the function as path, start and end; a path stepping through a connection's `nodes`; a path naming a missing field, a missing type and a union step, each refused at the argument with nothing called; a `TYPE_NAME` failing `@typeImplements`; and the same refusal routed into a payload through `@error`. A `@service` taking components of a path gets one execution case. No assertions on generated text.

## Decisions for Alf

Each has a recommendation the plan above already follows; a different answer changes the named section only.

1. **What a value is checked against.** The schema the service serves, at the call boundary (recommended, one copy of the schema and one error surface), or a table generated from the store and checked in the scalar's coercion (an error there is a top-level request error that `@error` can never route, and the table is a second copy of the schema).
2. **A path ending in an interface or union.** If `Sak.soker` returned an interface, the end type would be the interface's name, which never equals a `__typename`. Recommended: allow it and hand over the declared name, since the function compares names it chose; the alternative refuses such a path. The same answer settles whether a `TYPE_NAME` may name an interface (recommended: object types only, as `__typename` gives them).
3. **Where an interface requirement lives.** On the use site, `@typeImplements` on the argument (recommended: opptak uses one type-name scalar for both the context type, which must implement `Node`, and the event type, which must implement `Hendelse`), or as an argument of `@generatedScalar` on the scalar, which states the contract in the published type at the cost of one scalar per requirement.
4. **Call kinds in this item.** `@routine` writes and `@service` calls (recommended, both emitted from the store, so the service case costs a test), or `@routine` only.

## Other solutions we've considered

- **One directive per kind** (`@schemaPath`, `@schemaTypeName`): rejected for one directive with a closed enum, so the list of scalars Graphitron can generate is in one place and grows by an enum value.
- **Reserved scalar names** (`Path` and `TypeName` recognised without a directive, as the federation scalars are): rejected, because a consumer may already declare a scalar of either name with its own `@scalarType`, and Graphitron maps explicitly rather than by name.
- **A plain `String` as the Java value**, with the start and end computed at each binding: rejected, because the components are the value's meaning and belong decoded once, in one record, with `argMapping` naming what each call needs.
- **Checking in the database function**: the function would need the schema, which is what this item exists to avoid.
