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

An author can declare two scalars that Graphitron itself understands: `Path`, a path through the subgraph's own GraphQL schema written as a type name followed by field names (`Sak.soker`, `Soknad.saker.nodes`), and `TypeName`, the name of a type in that schema. A value of either arriving as input is checked against the schema before any call runs, so an invalid one is answered as an error on that argument and never reaches the routine or service. A `@routine` (a mutation backed by a database function) can bind the path's start type and end type to parameters of their own, so the function compares plain type names and needs no knowledge of the schema. FS opptak needs this for its outbound messages to applicants: a message names whom it goes to as a path from the context it is sent in (`mottakersti`), and an automatic message names the path from the event that triggers it to that context (`kontekststi`).

From opptak's design (`docs/kommunikasjon-med-soker-api.md` on fs-plattform's `arkitektur/kommunikasjon-utsending` branch), translated to the English scalar names:

```graphql
scalar Path @schemaPath
scalar TypeName @schemaTypeName

input OpprettAutomatiskUtsendingInput {
    hendelsestype: TypeName! @typeImplements(interface: "Hendelse")
    kontekststi: Path!
    mottakersti: Path! @typeImplements(interface: "Node")
}

type Mutation {
    opprettAutomatiskUtsending(input: OpprettAutomatiskUtsendingInput!): UtsendingPayload
        @routine(name: "opprett_automatisk_utsending", argMapping: """
            pHendelsestype: input.hendelsestype,
            pKontekststi: input.kontekststi,
            pKontekstStart: input.kontekststi.startType,
            pKontekstSlutt: input.kontekststi.endType,
            pMottakersti: input.mottakersti,
            pMottakerStart: input.mottakersti.startType,
            pMottakerSlutt: input.mottakersti.endType
        """)
}
```

With `mottakersti: "Sak.soker"` the function receives `'Sak.soker'`, `'Sak'` and `'Soker'`. With `"Sak.sokr"` the request answers `Sak has no field sokr` at `input.mottakersti` and the function is not called. With `hendelsestype: "Sak"` it answers that `Sak` does not implement `Hendelse`. The function's own rules (the recipient path ends in `Soker`, the context path ends where the recipient path starts) stay in the function, written over type names.

## User documentation (first-client check)

Draft for a new manual page, `docs/manual/reference/scalars/schema-path.adoc`, with the two directive pages beside `scalarType.adoc`.

> ### Paths and type names in the schema
>
> Some APIs take a reference to the schema itself as input: which kind of object a rule applies to, or how to get from one object to another. Declare a scalar with `@schemaPath` or `@schemaTypeName` and Graphitron checks every value against the schema it generates, before your routine or service is called.
>
> ```graphql
> scalar Path @schemaPath
> scalar TypeName @schemaTypeName
> ```
>
> The scalar's name is yours; the directive says what it means. Neither takes `@scalarType`, and putting both on one scalar is an error. On the wire and in Java both are strings.
>
> **A `TypeName`** is the name of an object type in this subgraph, as `__typename` gives it: `Sak`, `SoknadLevert`.
>
> **A `Path`** is a type name followed by zero or more field names, separated by dots: `Soknad`, `Sak.soker`, `Soknad.saker.nodes`. Each field must exist on the type the previous step reached, and the path follows the schema exactly as clients see it, so a connection is stepped through with `nodes` (or `edges.node`). Lists and non-null wrappers are stepped through; arguments are not part of a path. The path's *start type* is its first name; its *end type* is the type its last field returns, or the start type when there is no field.
>
> **Requiring an interface.** `@typeImplements(interface: "Node")` on an argument or input field requires a `TypeName` to name a type implementing that interface, or a `Path` to start in one.
>
> **Binding the parts.** In `argMapping`, `x.startType` and `x.endType` bind a path's start and end type names to parameters of their own; `x` alone binds the path as written. Every bound parameter must take a string.
>
> **When a value is wrong**, the field answers a client error at the argument's path, as a malformed node id does, and nothing is called. Route it to your payload with an `@error` type handling `GraphitronClientException`, as for node ids.
>
> **Only this subgraph.** A path can only name types this subgraph declares. A type owned by another subgraph is reachable only through a local stub of it.
>
> **As output**, a `Path` or `TypeName` read from a column is returned as stored, without checking, since a stored value may predate a schema change.

## Implementation

The checks run where the input is decoded, at the call boundary, against the schema the running service already serves (`DataFetchingEnvironment.getGraphQLSchema()`). No copy of the schema is generated, and the scalar's own coercion does no more than accept a string, so every rule about these values has one error surface: a `GraphitronClientException` at the argument's path, routable through `@error` like a node-id decode failure (`ProjectedKeyReads` is the precedent for the decode-once, raise-before-the-call shape).

### Model

- **Directives.** `@schemaPath` and `@schemaTypeName` on `SCALAR`, `@typeImplements(interface: String!)` on `ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`, declared in `directives.graphqls` and captured like every directive application.
- **What a scalar means**, at type grain: one row per scalar carrying either directive, keyed by the scalar, with its kind (`PATH` or `TYPE_NAME`). The landing rule and the emitters join it. Owner, grain sentence, mark and sweep as every stored relation owes.
- **Its Java class.** `graphitron_scalar_class_rule` gains a third source beside the built-ins and `@scalarType`: such a scalar is `java.lang.String`, with a column recording which source answered. Column reads, output and string parameter bindings then follow from the existing binding rules with no change.
- **`@scalarType` beside either directive** is two claims on one scalar and draws `CLAIMS_CONFLICT`, not a code of its own.
- **The parts are a landing.** `graphitron_argmapping_landing.receives` today says whether a node-id landing hands over the whole id or a key column. It widens to say what any landing hands over, with two new values for a path's start and end type, matched from the trailing names `startType` and `endType`. Its CHECK and comment are rewritten to the wider statement. No sibling relation.
- **Defects**, each a stored fact with a guidance fragment:
  - a trailing name on a `Path` other than `startType` or `endType`, and a trailing name on a `TypeName`;
  - a parameter bound to a path part, a `Path` or a `TypeName` whose SQL or Java type is not a string, as one rule over the landing (the counterpart of `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` for a key column), raised from the routine's or method's captured parameter types;
  - `@typeImplements` naming a type that is not an interface in this subgraph, or placed on an element whose type is not such a scalar;
  - a `Path` or `TypeName` bound at a call kind whose call is still rendered by the walk (today `@condition` and child `@routine` steps), as an `UNSUPPORTED` deferral naming the site, never a Java refusal.

### Emit

- A sink in `emit`, beside the routine-write and service emitters, that decodes each bound `Path` or `TypeName` once per call, ahead of the call and any transaction, into locals the parameter reads use. It is its own sink, not a widening of `render.ProjectedKeyReads`, which the walk's renderers still share.
- The decode itself is a small generated helper in the output package (Java 17, like all generated code): given the served schema and a string, it parses `Name(.Name)*`, resolves the start as an object or interface type (no `__` types), steps each field through the field's type with list and non-null wrappers removed, checks a required interface, and returns the start and end type names or throws `GraphitronClientException` with the argument path and a message naming the failing step.
- The scalar registration in `GraphitronSchema` is a generated string `GraphQLScalarType` under the author's scalar name, in place of the `@scalarType` constant.
- Call kinds: root and child `@service` calls and root `@routine` writes, all emitted from the store. A `@condition` or child `@routine` binding takes the deferral above until its call is emitted from the store.

## Tests

- Fact documents, with defect rows beside fact rows: the scalar's kind and Java class; a `startType` and an `endType` landing; each defect above with a near miss (`x.startType` on a `Path` beside `x.startTyp`; a string parameter beside an integer one; `@typeImplements(interface: "Node")` beside one naming an object type; `@scalarType` with `@schemaPath`).
- Execution tier in `graphitron-sakila-example`, with a small function added to the sakila database's `init.sql` that records what it receives: a valid path reaches the function as path, start and end; a path stepping through a connection's `nodes`; a path naming a missing field, a missing type and a union step, each refused at the argument with nothing called; a `TypeName` failing `@typeImplements`; and the same refusal routed into a payload through `@error`. A `@service` taking a `Path` and its parts gets one execution case. No assertions on generated text.

## Decisions for Alf

Each has a recommendation the plan above already follows; a different answer changes the named section only.

1. **What a value is checked against.** The schema the service serves, at the call boundary (recommended, one copy of the schema and one error surface), or a table generated from the store and checked in the scalar's coercion (an error there is a top-level request error that `@error` can never route, and the table is a second copy of the schema).
2. **A path ending in an interface or union.** If `Sak.soker` returned an interface, the end type would be the interface's name, which never equals a `__typename`. Recommended: allow it and hand over the declared name, since the function compares names it chose; the alternative refuses such a path. The same answer settles whether a `TypeName` may name an interface (recommended: object types only, as `__typename` gives them).
3. **Where an interface requirement lives.** On the use site, `@typeImplements` on the argument (recommended: opptak uses one type-name scalar for both the context type, which must implement `Node`, and the event type, which must implement `Hendelse`), or on the scalar itself (`scalar KontekstType @schemaTypeName(implements: "Node")`), which states the contract in the published type at the cost of one scalar per requirement.
4. **Call kinds in this item.** `@routine` writes and `@service` calls (recommended, both emitted from the store, so the service case costs a test), or `@routine` only.

## Other solutions we've considered

- **Reserved scalar names** (`Path` and `TypeName` recognised without a directive, as the federation scalars are): rejected, because a consumer may already declare a scalar of either name with its own `@scalarType`, and Graphitron maps explicitly rather than by name.
- **A structured value** (a Java record of path, start and end): rejected, because a routine parameter takes one value, columns store a string, and the node-id key projection already gives the binding grammar for parts.
- **Checking in the database function**: the function would need the schema, which is what this item exists to avoid.
