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

An author can declare scalars that Graphitron itself knows how to generate, chosen from a closed list. The first two are `SCHEMA_PATH`, a path through the subgraph's own GraphQL schema written as a type name followed by field names (`Sak.soker`, `Soknad.saker.nodes`), and `TYPE_NAME`, the name of a type in that schema. A value of either arriving as input is checked against the schema before any call runs, together with the requirements the argument states on its types, so an invalid one is answered as an error on that argument and never reaches the routine or service. Graphitron decodes a valid value into an internal record of its components, and a call takes the components it needs through `argMapping`: a `@routine` (a mutation backed by a database function) binds the path's start type and end type to parameters of their own, so the function compares plain type names and needs no knowledge of the schema. FS opptak needs this for its outbound messages to applicants: a message names whom it goes to as a path from the context it is sent in (`mottakersti`), and an automatic message names the path from the event that triggers it to that context (`kontekststi`).

From opptak's design (`docs/kommunikasjon-med-soker-api.md` on fs-plattform's `arkitektur/kommunikasjon-utsending` branch), with the scalars under English names:

```graphql
scalar Path @generatedScalar(kind: SCHEMA_PATH)
scalar TypeName @generatedScalar(kind: TYPE_NAME)

input OpprettAutomatiskUtsendingInput {
    hendelsestype: TypeName! @require(component: "name", type: {implements: "Hendelse"})
    kontekststi: Path!
    mottakersti: Path!
        @require(component: "startType", type: {implements: "Node"})
        @require(component: "endType", type: {is: "Soker"})
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

With `mottakersti: "Sak.soker"` the function receives `'Sak.soker'`, `'Sak'` and `'Soker'`. With `"Sak.sokr"` the field answers `Sak has no field sokr` at `input.mottakersti`, with `"Sak.saksbehandler"` it answers that the path's end type `Saksbehandler` is not `Soker`, and with `hendelsestype: "Sak"` it answers that `Sak` does not implement `Hendelse`; in each case the function is not called, and an `@error` type on the payload receives the error. Rules relating two arguments (the context path ends where the recipient path starts) stay in the function, written over type names.

## User documentation (first-client check)

Draft for two new manual pages, `docs/manual/reference/directives/generatedScalar.adoc` beside `scalarType.adoc`, and `docs/manual/reference/directives/require.adoc`.

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
> **A path** follows the schema exactly as clients see it: each field must exist on the type the previous step reached, lists and non-null wrappers are stepped through, a connection is stepped through with `nodes` (or `edges.node`), and arguments are not part of a path. Its start type is its first name, an object or interface type. Its end type is the named type its last field returns, which may be an interface or a union, or the start type when there is no field.
>
> **When a value is wrong**, the field answers a client error at the argument's path, as a malformed node id does, and nothing is called. Route it to your payload with an `@error` type handling `GraphitronClientException`, as for node ids.
>
> **Only this subgraph.** A value can only name types this subgraph declares. A type owned by another subgraph is reachable only through a local stub of it.
>
> **As output**, a field of a generated scalar reads a string column and returns it as stored, without checking, since a stored value may predate a schema change.
>
> ### `@require`
>
> An argument or input field of a generated scalar can require things of the types its value names. Each `@require` names one component of the value and a condition its type must meet; repeat the directive and every one must hold.
>
> ```graphql
> directive @require(component: String!, type: TypeCondition!) repeatable
>     on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
>
> input TypeCondition @oneOf {
>     is: String
>     implements: String
>     anyOf: [TypeCondition!]
>     allOf: [TypeCondition!]
>     not: TypeCondition
> }
> ```
>
> A condition sets exactly one field:
>
> | Field | Holds when the type |
> |---|---|
> | `is` | is the named type (object, interface or union) |
> | `implements` | implements the named interface, directly or through another interface |
> | `anyOf` | meets at least one of the conditions |
> | `allOf` | meets every one of the conditions |
> | `not` | does not meet the condition |
>
> ```graphql
> mottakersti: Path!
>     @require(component: "startType", type: {implements: "Node"})
>     @require(component: "endType", type: {is: "Soker"})
> kontekststi: Path! @require(component: "endType", type: {anyOf: [{is: "Sak"}, {is: "Soknad"}]})
> hendelsestype: TypeName! @require(component: "name", type: {implements: "Hendelse"})
> ```
>
> Graphitron checks when it generates that `component` is one of the value's components, that `is` names a type and that `implements` names an interface in this subgraph. A value that fails a requirement is answered like any wrong value, with a message naming the requirement: "the path's endType `Saksbehandler` is not `Soker`".

## Implementation

The checks run where the input is decoded, at the call boundary, against the schema the running service already serves (`DataFetchingEnvironment.getGraphQLSchema()`), and the requirements run in the same place right after the decode (Alf, 2026-11-22). No copy of the schema is generated, and the scalar's own coercion only accepts a string, so every rule about these values has one error surface: a `GraphitronClientException` at the argument's path, routable through `@error` like a node-id decode failure. `ProjectedKeyReads` is the precedent for the shape: decode once, raise before the call, hand components to parameters.

### Model

- **Directives.** `@generatedScalar(kind: GeneratedScalar!)` on `SCALAR` with its enum, and `@require(component: String!, type: TypeCondition!)`, repeatable, on `ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`, with the `@oneOf` input `TypeCondition`, declared in `directives.graphqls` and captured like every directive application. New kinds are new enum values; new atoms are new `TypeCondition` fields.
- **What a scalar is**, at type grain: one row per scalar carrying `@generatedScalar`, keyed by the scalar, with its kind. The landing rule, the class rule, the requirement rules and the emitters join it. Owner, grain sentence, mark and sweep as every stored relation owes.
- **Its components**, at kind grain: one row per kind and component, with the component's Java class (`String` for all four today). A stated fact rather than knowledge in each reader, so a new kind adds rows, not rules.
- **Its Java class.** `graphitron_scalar_class_rule` gains a third source beside the built-ins and `@scalarType`: a generated scalar's class is its kind's internal record in the output package, with a column recording which source answered. The record is generated (Java 17, like all generated code) and internal: no consumer code names it, which is why calls take components rather than the record.
- **`@scalarType` beside `@generatedScalar`** is two claims on one scalar and draws `CLAIMS_CONFLICT`, not a code of its own.
- **Components are a landing.** `graphitron_argmapping_landing.receives` today says whether a node-id landing hands over the whole id or a key column. It widens to say what any landing hands over, with a value for a generated scalar's component, matched from the trailing name against the kind's components. Its CHECK and comment are rewritten to the wider statement. No sibling relation.
- **A requirement is a stored tree.** One row per `@require` at its occurrence (the argument or input field) with its component, and one row per condition node beneath it with its parent, position, operator (`IS`, `IMPLEMENTS`, `ANY_OF`, `ALL_OF`, `NOT`) and, for an atom, the type it names. Captured from the directive application as written; the tree's shape is its own, not a reading of the walk.
- **Defects**, each a stored fact with a guidance fragment:
  - a generated scalar bound with no component, and a trailing name that is not one of its kind's components;
  - a parameter bound to a component whose SQL or Java type does not take the component's class, as one rule over the landing (the counterpart of `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` for a key column), raised from the routine's or method's captured parameter types;
  - a `@require` whose component is not one of its scalar's components, or placed on an element whose type is not a generated scalar; an `is` naming no type in this subgraph; an `implements` naming no interface in it; a condition setting no field or more than one (the `@oneOf` rule, stated in the store as well, since the SDL is captured before any validation);
  - an output field of a generated scalar whose column is not a string;
  - a generated scalar bound at a call kind whose call is still rendered by the walk (today `@condition` and child `@routine` steps), as an `UNSUPPORTED` deferral naming the site, never a Java refusal.

### Emit

- A sink in `emit`, beside the routine-write and service emitters, that decodes each bound generated-scalar argument once per call, ahead of the call and any transaction, into a local of its record type, checks the argument's requirements on it, and hands components to the parameter reads. It is its own sink, not a widening of `render.ProjectedKeyReads`, which the walk's renderers still share.
- The records and their decoders are generated helpers in the output package. A `SCHEMA_PATH` decoder, given the served schema and a string, parses `Name(.Name)*`, resolves the start as an object or interface type (no `__` types), steps each field through the field's type with list and non-null wrappers removed, and returns the record or throws `GraphitronClientException` with the argument path and a message naming the failing step. A `TYPE_NAME` decoder checks one name, an object type, the same way.
- A requirement is rendered as a Java predicate over the component's type name and the served schema, from its stored tree: `is` compares names, `implements` asks the schema's implementations of the interface (transitively through interfaces), and the combinators are `||`, `&&` and `!`. A failure throws `GraphitronClientException` with a message naming the component, its type and the requirement.
- The scalar registration in `GraphitronSchema` is a generated string `GraphQLScalarType` under the author's scalar name, in place of a `@scalarType` constant; its `serialize` accepts the stored string.
- Call kinds: root and child `@service` calls and root `@routine` writes, all emitted from the store (Alf, 2026-11-22). A `@condition` or child `@routine` binding takes the deferral above until its call is emitted from the store.

## Tests

- Fact documents, with defect rows beside fact rows: the scalar's kind, its components and its Java class; a `startType` and an `endType` landing; a requirement tree with each operator; each defect above with a near miss (`x.startType` beside `x.startTyp`; `x` bound with no component beside `x.path`; a string parameter beside an integer one; `implements: "Node"` beside `implements: "Sak"`; `is: "Soker"` beside `is: "Sokr"`; `component: "endType"` beside `component: "end"`; `@scalarType` with `@generatedScalar`).
- Execution tier in `graphitron-sakila-example`, with a small function added to the sakila database's `init.sql` that records what it receives:
  - a valid path reaches the function as path, start and end, and a `TYPE_NAME` as its name;
  - a path stepping through a connection's `nodes`, and one ending in an interface, handing over the interface's name;
  - a path naming a missing field, a missing type and a step past a union, each refused at the argument with nothing called;
  - each condition operator, accepting one value and refusing another, including `not` and a nested `anyOf` inside `allOf`;
  - a refusal routed into a payload through `@error`;
  - a `@service` taking components of a path.
- No assertions on generated text.

## Decided

- Values and requirements are checked at the call boundary against the served schema, not in the scalar's coercion (Alf, 2026-11-22): one error surface, routable through `@error`, and no copy of the schema.
- A path's end type is the declared named type, interface and union included; a `TYPE_NAME` names an object type, as `__typename` gives it (Alf, 2026-11-22).
- Type requirements live at the use site, act on the internal record's components, and form a small algebra of atoms (`is`, `implements`) and combinators (`anyOf`, `allOf`, `not`) that grows by adding atoms (Alf, 2026-11-22).
- Both `@routine` writes and `@service` calls bind components in this item (Alf, 2026-11-22).

## Other solutions we've considered

- **One directive per kind** (`@schemaPath`, `@schemaTypeName`): rejected for one directive with a closed enum, so the list of scalars Graphitron can generate is in one place and grows by an enum value.
- **Reserved scalar names** (`Path` and `TypeName` recognised without a directive, as the federation scalars are): rejected, because a consumer may already declare a scalar of either name with its own `@scalarType`, and Graphitron maps explicitly rather than by name.
- **A plain `String` as the Java value**, with the start and end computed at each binding: rejected, because the components are the value's meaning and belong decoded once, in one record, with `argMapping` naming what each call needs.
- **Requirements on the scalar** (`@generatedScalar(kind: TYPE_NAME, implements: "Node")`): rejected, since one scalar is used at sites with different requirements, and a requirement on the start or end of a path is a statement about the argument, not the scalar.
- **Checking in the scalar's coercion** against a generated table of types and fields: rejected, since a failure there fails the whole request where `@error` cannot catch it, the scalar cannot see which argument's requirements apply, and the table would be a second copy of the schema.
- **Checking in the database function**: the function would need the schema, which is what this item exists to avoid.
