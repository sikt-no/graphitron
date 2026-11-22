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
    kontekststi: Path! @require(component: "startType", type: {implements: "Hendelse"})
    mottakersti: Path!
        @require(component: "startType", type: {implements: "Node"})
        @require(component: "endType", type: {is: "Soker"})
}

type AutomatiskUtsending implements Node & Utsending {
    hendelsestype: TypeName
    kontekststi: Path
    mottakersti: Path
}

type Mutation {
    opprettAutomatiskUtsending(input: OpprettAutomatiskUtsendingInput!): UtsendingPayload
        @routine(name: "opprett_automatisk_utsending", argMapping: """
            pHendelsestype: input.kontekststi.startType,
            pKontekststi: input.kontekststi.path,
            pKontekstStart: input.kontekststi.startType,
            pKontekstSlutt: input.kontekststi.endType,
            pMottakersti: input.mottakersti.path,
            pMottakerStart: input.mottakersti.startType,
            pMottakerSlutt: input.mottakersti.endType
        """)
}
```

The context path starts in the event type, so the event type is the path's start: with `kontekststi: "SoknadLevert.subjekt"` the function receives `'SoknadLevert'` as the event type and `'Soknad'` as the context type, and with `mottakersti: "Soknad.soker"` it receives `'Soknad'` and `'Soker'`. With `"Soknad.sokr"` the field answers `Soknad has no field sokr` at `input.mottakersti`, with `"Soknad.saksbehandler"` it answers that the path's end type `Saksbehandler` is not `Soker`, and with `kontekststi: "Sak.soker"` it answers that `Sak` does not implement `Hendelse`; in each case the function is not called, and an `@error` type on the payload receives the error. Rules relating two arguments (the context path ends where the recipient path starts) stay in the function, written over type names. On the read side, `TYPE_NAME` and `SCHEMA_PATH` fields return the names and paths stored in columns.

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
> The scalar's name is yours; `kind` says what it is. A scalar takes `@scalarType` or `@generatedScalar`, not both. On the wire every generated scalar is a string. Inside the generated code a value is decoded into a record of its components, and a call takes the components it needs by naming them in `argMapping`, as it takes a key column from a node id. A value reaches a call only as a component named in `argMapping`: binding it whole, by a parameter's name or type, through an input bean, as a column a `@mutation` writes, as a filter or as an `@outbox` key is an error, since the record is internal and only a component binding is checked, and so is an argument of one that no call takes at all. A null or absent value is not checked and hands every component as null. A list of these scalars is not supported yet.
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
> **Only this subgraph, as served.** A value can only name types this subgraph declares. A type owned by another subgraph is reachable only through a local stub of it. Values are checked against the schema the service actually serves, so a type or field added or removed by a schema customizer counts as served, and the federation and introspection types (names starting with `_`) are never a path's start.
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
> kontekststi: Path!
>     @require(component: "startType", type: {implements: "Hendelse"})
>     @require(component: "endType", type: {anyOf: [{is: "Sak"}, {is: "Soknad"}]})
> mottaker: Path! @require(component: "endType", type: {implements: "Utboksmottaker"})
> ```
>
> Graphitron checks when it generates that `component` is one of the value's components, that `is` names a type and that `implements` names an interface in this subgraph. A value that fails a requirement is answered like any wrong value, with a message naming the requirement: "the path's endType `Saksbehandler` is not `Soker`".

## Implementation

The checks run where the input is decoded, at the call boundary, against the schema the running service already serves (`DataFetchingEnvironment.getGraphQLSchema()`), and the requirements run in the same place right after the decode (Alf, 2026-11-22). No copy of the schema is generated, and the scalar's own coercion only accepts a string, so every rule about these values has one error surface: a `GraphitronClientException` at the argument, routable through `@error` like a node-id decode failure. The store-fed precedent for the shape is the node-id decode: `NodeIdDecodeQuery` and `NodeIdDecodeEmitter` over the `graphitron_occurrence_node_id_slot_*` relations, which decode once, raise before the call, and hand parts to parameters.

The wire value is a string, and the record is the decoded value inside the generated call. So the store says the scalar's class is `java.lang.String` (what graphql-java delivers, which is what every reader of `graphitron_scalar_class` means), and the record is the emitter's, derived from the kind. A record's name depends on the output package, which is generator configuration and not a store fact.

### Blockers, landed first

Two things on trunk stand in the way, and each is its own commit ahead of the feature.

1. **The trailing-name judgement moves out of the deprecated family.** `intent_argmapping_projection_defect` raises `UNDECLARED_NODE_ID` on any trailing name past an element that declares no `@nodeId`, so `input.kontekststi.startType` is refused today. The judgement moves onto the `argmapping-landings` stage, which already decides what a landing receives, and that arm of the deprecated view is retired. The deprecated family is not patched.
2. **The store-fed routine write reads its bindings from the store.** `RoutineWriteQuery.arguments` reads the raw `bound_to` off the pair entry, and `RoutineWriteEmitter.routineCall` reads it through the walk-era `RoutineCallEmitter.envArgumentRead` and `ProjectedKeyReads`, fed by the deprecated `intent_resolved_node_key_projection`. A component name would be walked as a map key. The routine write instead reads its fills from `graphitron_occurrence_call_parameter` (its `ROUTINE` rows exist) and their landings, as the service emitter does, with node-id projections read from the node-id slot relations.

### Model

- **Directives.** `@generatedScalar(kind: GeneratedScalar!)` on `SCALAR` with its enum, and `@require(component: String!, type: TypeCondition!)`, repeatable, on `ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`, with the `@oneOf` input `TypeCondition`, declared in `directives.graphqls`. New kinds are new enum values; new atoms are new `TypeCondition` fields.
- **Capture.** `@generatedScalar` is a plain directive entry. `@require` is keyed by its declaration's coordinate and its ordinal among the repeated applications, and its nested `TypeCondition` value is captured into positional entry tables of its own, one row per condition node with its parent, position, the field it sets and, for an atom, the name it gives, as the reference element and step entries are captured.
- **What a scalar is**, at type grain, in macro expansion beside `graphitron_scalar_class`: one row per scalar carrying `@generatedScalar`, keyed by the scalar, with its kind. Owner, grain sentence, mark and sweep as every stored relation owes.
- **Its components**, at kind grain: one row per kind and component, with the component's Java class (`String` for all four today), beside one row per kind naming its decoded record (its simple name, resolved into the output package by the emitter). Stated facts rather than knowledge in each reader, so a new kind adds rows, not rules.
- **Its Java class.** `graphitron_scalar_class_rule` gains a third source, `GENERATED`, beside the built-ins and `@scalarType`, with `java.lang.String` as the class, and a column recording which source answered. Every existing reader then reads it as a string, which it is on the wire: argument arrival, input types, the service by-type rule, and `SERVICE_RETURN_MISMATCH` for a `@service` returning a `String` into an output field of the scalar.
- **`@scalarType` beside `@generatedScalar`** is two claims on one scalar and draws `CLAIMS_CONFLICT`, not a code of its own.
- **Components are a landing.** `graphitron_argmapping_landing.receives` widens from what a node-id landing hands over to what any landing hands over, with a value for a generated scalar's component, matched from the trailing name against the kind's components. The new arm comes first in its `CASE`, since a bare descent already yields `COLUMN`, and joins the landing's scalar kind. The `trailing_name` comment, which says node ids only, and the CHECK are rewritten to the wider statement. No sibling relation.
- **What each parameter receives**, at call-parameter grain: a slot relation mirroring `graphitron_occurrence_node_id_slot_parameter`, one row per call and parameter position with its landing and the component it receives. The emitter reads it, and the type-disagreement defect is raised over it.
- **A requirement is a stored tree**, derived from its capture: one row per `@require` at its occurrence with its component, and one row per condition node beneath it with its operator (`IS`, `IMPLEMENTS`, `ANY_OF`, `ALL_OF`, `NOT`) and, for an atom, the type it names.
- **Registration from the store.** Which scalars are generated, and of what kind, is read from the store by the schema generator, which registers each as a string `GraphQLScalarType` under the author's name. The walk's scalar classification (`TypeBuilder.classifyScalarType`) stands aside for a scalar the store marks generated, reading that fact, the way the walk stands aside for a store-owned field; it gets no new arm of its own. `VoucherIssueQuery`, which throws for a scalar with no binding, reads the same fact.
- **Defects**, each a stored fact with a guidance fragment:
  - a generated scalar bound with no component, and a trailing name that is not one of its kind's components;
  - a generated scalar reaching a call or a write by any other route: a routine or service parameter matched by name or by type, an input bean or record, a column a `@mutation` writes, an implicit filter, or an `@outbox` payload key;
  - a list of a generated scalar bound at a call, as an `UNSUPPORTED` deferral;
  - an argument or input field of a generated scalar that no call decodes, including one on a field with no call at all, so the goal's promise holds for every input value and no `@require` is ever silently skipped;
  - a parameter receiving a component whose SQL or Java type does not take the component's class, over the slot relation (the counterpart of `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` for a key column), raised from the routine's or method's captured parameter types;
  - a `@require` whose component is not one of its scalar's components, or placed on an element whose type is not a generated scalar; an `is` naming no type in this subgraph; an `implements` naming no interface in it; a condition setting no field or more than one (the `@oneOf` rule, stated in the store as well, since the SDL is captured before any validation);
  - an output field of a generated scalar whose column is not a string;
  - a generated scalar bound at a call kind whose call is still rendered by the walk (today `@condition` and child `@routine` steps), as an `UNSUPPORTED` deferral naming the site, never a Java refusal.

### Emit

- **How a component reaches each query.** Services fill through `FillQuery` and `FillEmitter`, whose `Landing` carries the node-id decode; it gains the component a landing receives, read from the slot relation. After the second blocker the routine write reads the same stored fills, so it reaches components through the same `Landing` rather than a parameter row of its own. Either way a call has one decode prelude, holding its node-id decodes and its generated-scalar decodes together, not two.
- A sink in `emit`, beside the routine-write and service emitters, that decodes each bound generated-scalar argument once per call, ahead of the call and any transaction, into a local of its record type, checks the argument's requirements on it, and hands components to the parameter reads it is listed for in the slot relation. For a child `@service` the decode runs per parent before the loader dispatch, so a wrong value fails its own field. It is its own sink, not a widening of `render.ProjectedKeyReads`, which the walk's renderers still share.
- The records and their decoders are generated helpers in the output package. A `SCHEMA_PATH` decoder, given the served schema and a string, parses `Name(.Name)*`, resolves the start as an object or interface type whose name does not start with `_`, steps each field through the field's type with list and non-null wrappers removed, and returns the record or throws. A `TYPE_NAME` decoder checks one name, an object type, the same way. A null value decodes to a record of nulls without checking.
- A requirement is rendered as a Java predicate over the component's type name and the served schema, from its stored tree: `is` compares names, `implements` asks the schema's implementations of the interface (transitively through interfaces), and the combinators are `||`, `&&` and `!`.
- **The error.** `GraphitronClientException` carries a message, and the error router places it at the field's path, so the message itself names the argument and the failing step or requirement: "input.mottakersti: Soknad has no field sokr", "input.mottakersti: the path's endType `Saksbehandler` is not `Soker`".
- Call kinds: root and child `@service` calls and root `@routine` writes, all emitted from the store (Alf, 2026-11-22). A `@condition` or child `@routine` binding takes the deferral above until its call is emitted from the store.

## Tests

- Fact documents, with defect rows beside fact rows: the scalar's kind, its components and its `GENERATED` class row; a `startType` and an `endType` landing and their slot rows; a requirement tree with each operator; each defect above with a near miss (`x.startType` beside `x.startTyp`; `x.path` beside `x` bound whole, by name and by type; an input bean holding a generated scalar; a `@mutation` column, a filter and an `@outbox` key of one; a list of one; a `@require` nothing decodes; a string parameter beside an integer one; `implements: "Node"` beside `implements: "Sak"`; `is: "Soker"` beside `is: "Sokr"`; `component: "endType"` beside `component: "end"`; `@scalarType` with `@generatedScalar`). The first blocker's document shows `input.kontekststi.startType` drawing no `UNDECLARED_NODE_ID` beside a trailing name on a plain `String`, which still does.
- Execution tier in `graphitron-sakila-example`, with a small function added to the sakila database's `init.sql` that records what it receives:
  - a valid path reaches the function as path, start and end, and a `TYPE_NAME` as its name;
  - a path stepping through a connection's `nodes`, and one ending in an interface, handing over the interface's name;
  - a path naming a missing field, a missing type and a step past a union, each refused at the argument with nothing called;
  - each condition operator, accepting one value and refusing another, including `not` and a nested `anyOf` inside `allOf`;
  - a refusal routed into a payload through `@error`, with the argument named in the message;
  - a null path reaching the function as three nulls;
  - a root and a child `@service` taking components of a path, the child refusing one parent's value and serving the others;
  - an output field of the scalar read from a string column, and one answered by a `@service` returning a `String`;
  - the routine write's existing node-id projection cases, unchanged after the second blocker.
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
- **`@require` on plain strings**, either a `String` holding a type name or value conditions such as a pattern: rejected. A type name is what `TYPE_NAME` declares in the published schema, and value validation of strings is `graphql-java-extended-validation`'s directive set, which R98 adopts (Alf, 2026-11-22). `@require` keeps to conditions on the types a generated scalar's components name.
- **Checking in the scalar's coercion** against a generated table of types and fields: rejected, since a failure there fails the whole request where `@error` cannot catch it, the scalar cannot see which argument's requirements apply, and the table would be a second copy of the schema.
- **Checking in the database function**: the function would need the schema, which is what this item exists to avoid.
