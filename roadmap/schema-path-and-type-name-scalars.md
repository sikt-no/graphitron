---
id: R1204
title: "A Path or TypeName argument is checked against the subgraph schema, and a routine binds the path's start and end types"
status: In Progress
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
    kontekststi: Path! @assertType(component: "startType", type: {implements: "Hendelse"})
    mottakersti: Path!
        @assertType(component: "startType", type: {implements: "Node"})
        @assertType(component: "endType", type: {is: "Soker"})
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
            pKontekststi: input.kontekststi.text,
            pKontekstStart: input.kontekststi.startType,
            pKontekstSlutt: input.kontekststi.endType,
            pMottakersti: input.mottakersti.text,
            pMottakerStart: input.mottakersti.startType,
            pMottakerSlutt: input.mottakersti.endType
        """)
}
```

The context path starts in the event type, so the event type is the path's start: with `kontekststi: "SoknadLevert.subjekt"` the function receives `'SoknadLevert'` as the event type and `'Soknad'` as the context type, and with `mottakersti: "Soknad.soker"` it receives `'Soknad'` and `'Soker'`. With `"Soknad.sokr"` the field answers `Soknad has no field sokr` at `input.mottakersti`, with `"Soknad.saksbehandler"` it answers that the path's end type `Saksbehandler` is not `Soker`, and with `kontekststi: "Sak.soker"` it answers that `Sak` does not implement `Hendelse`; in each case the function is not called, and an `@error` type on the payload receives the error. Rules relating two arguments (the context path ends where the recipient path starts) stay in the function, written over type names. On the read side, `TYPE_NAME` and `SCHEMA_PATH` fields return the names and paths stored in columns.

## User documentation (first-client check)

Draft for two new manual pages, `docs/manual/reference/directives/generatedScalar.adoc` beside `scalarType.adoc`, and `docs/manual/reference/directives/assertType.adoc`. Two existing pages change with them: `service.adoc` § arg-mapping, which says a dot opens an input object or a node id and nothing else opens, and `routine.adoc`'s `argMapping` row each say that a generated scalar opens into its components. Every new code gets a guidance fragment (principles architect at pickup, 2026-11-22).

> ### `@generatedScalar`
>
> `@scalarType` binds a scalar to a constant you write. `@generatedScalar` asks Graphitron to generate the scalar itself, for a kind it knows:
>
> ```graphql
> directive @generatedScalar(kind: GeneratedScalarKind!) on SCALAR
> enum GeneratedScalarKind { SCHEMA_PATH TYPE_NAME }
> ```
>
> The scalar's name is yours; `kind` says what it is. A scalar takes `@scalarType` or `@generatedScalar`, not both. On the wire every generated scalar is a string. Inside the generated code a value is decoded into a record of its components, and a call takes the components it needs by naming them in `argMapping`, as it takes a key column from a node id. A value reaches a call only as a component named in `argMapping`: binding it whole, by a parameter's name or type, through an input bean, as a column a `@mutation` writes, as a filter or as an `@outbox` key is an error, since the record is internal and only a component binding is checked, and so is an argument of one that no call takes at all. A null or absent value is not checked and hands every component as null. A list of these scalars is not supported yet.
>
> | Kind | A value is | Components |
> |---|---|---|
> | `TYPE_NAME` | the name of an object type in this subgraph, as `__typename` gives it: `Sak`, `SoknadLevert` | `text`, `name` |
> | `SCHEMA_PATH` | a type name followed by zero or more field names, separated by dots: `Soknad`, `Sak.soker`, `Soknad.saker.nodes` | `text`, `startType`, `path`, `endType` |
>
> Every kind has `text`, the value as it was provided. A path's components, for three values:
>
> | Value | `text` | `startType` | `path` | `endType` |
> |---|---|---|---|---|
> | `Sak.soker` | `Sak.soker` | `Sak` | `soker` | `Soker` |
> | `Soknad.saker.nodes` | `Soknad.saker.nodes` | `Soknad` | `saker.nodes` | `Sak` |
> | `Soknad` | `Soknad` | `Soknad` | the empty string | `Soknad` |
>
> **A path** follows the schema exactly as clients see it: each field must exist on the type the previous step reached, lists and non-null wrappers are stepped through, a connection is stepped through with `nodes` (or `edges.node`), and arguments are not part of a path. Its start type is its first name, an object or interface type. Its end type is the named type its last field returns, which may be an interface or a union, or the start type when there is no field.
>
> **When a value is wrong**, the field answers a client error at the argument's path, as a malformed node id does, and nothing is called. Route it to your payload with an `@error` type handling `GraphitronClientException`, as for node ids.
>
> **Only this subgraph, as served.** A value can only name types this subgraph declares. A type owned by another subgraph is reachable only through a local stub of it. Values are checked against the schema the service actually serves, so a type or field added or removed by a schema customizer counts as served, and the federation and introspection types (names starting with `_`) are never a path's start.
>
> **As output**, a field of a generated scalar reads a string column and returns it as stored, without checking, since a stored value may predate a schema change.
>
> ### `@assertType`
>
> An argument or input field of a generated scalar can require things of the types its value names. Each `@assertType` names one component of the value and a condition its type must meet; repeat the directive and every one must hold.
>
> ```graphql
> directive @assertType(component: String!, type: TypeCondition!) repeatable
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
>     @assertType(component: "startType", type: {implements: "Node"})
>     @assertType(component: "endType", type: {is: "Soker"})
> kontekststi: Path!
>     @assertType(component: "startType", type: {implements: "Hendelse"})
>     @assertType(component: "endType", type: {anyOf: [{is: "Sak"}, {is: "Soknad"}]})
> mottaker: Path! @assertType(component: "endType", type: {implements: "Utboksmottaker"})
> ```
>
> Graphitron checks when it generates that `component` is one of the value's components, that `is` names a type and that `implements` names an interface in this subgraph. A value that fails a requirement is answered like any wrong value, with a message naming the requirement: "the path's endType `Saksbehandler` is not `Soker`".

## Implementation

The checks run where the input is decoded, at the call boundary, against the schema the running service already serves (`DataFetchingEnvironment.getGraphQLSchema()`), and the requirements run in the same place right after the decode (Alf, 2026-11-22). No copy of the schema is generated, and the scalar's own coercion only accepts a string, so every rule about these values has one error surface: a `GraphitronClientException` at the argument, routable through `@error` like a node-id decode failure. The store-fed precedent for the shape is the node-id decode: `NodeIdDecodeQuery` and `NodeIdDecodeEmitter` over the `graphitron_occurrence_node_id_slot_*` relations, which decode once, raise before the call, and hand parts to parameters.

The wire value is a string, and the record is the decoded value inside the generated call. So the store says the scalar's class is `java.lang.String` (what graphql-java delivers, which is what every reader of `graphitron_scalar_class` means), and the record is the emitter's, derived from the kind. A record's name depends on the output package, which is generator configuration and not a store fact.

### Blockers, landed first

Two things on trunk stand in the way, and each is its own commit ahead of the feature.

1. **A trailing name past an element that opens nothing is an absence.** `intent_argmapping_projection_defect` raises `UNDECLARED_NODE_ID` on any trailing name past an element that declares no `@nodeId`, so `input.kontekststi.startType` is refused today. The judgement moves onto the `argmapping-landings` stage, which already decides what a landing receives, as the absence of the stored relation (defects-as-facts § Ownership, Alf 2026-11-18): `graphitron_argmapping_landing_rule` writes no landing for a path with a trailing name past an element that is not a node id, read off its `NODE_ID` binding claim (the `binding-claims` stage runs before `argmapping-landings`), and the stage's existing anti-join raises `ARGMAPPING_PATH_UNRESOLVED` at it. No new code; the hint that an `ID` opens only where it declares `@nodeId(typeName:)` goes into `ARGMAPPING_PATH_UNRESOLVED`'s guidance fragment. An `@outbox` `$result` landing is under the same absence. The `UNDECLARED_NODE_ID` arm of the deprecated view retires with it; the deprecated family is not patched further. The rest of `intent_argmapping_projection_defect` does not retire at the end of blocker 2: only the Mutation-root routine write's consumer moves off it, the write reading its parameters from the store's fills. The view's retirement belongs to the graph node "The argMapping readers read the landing, and the candidate and match go" in `roadmap/derived-read-cost-is-a-shape-problem.md`, which lists the store counterparts still missing. The feature proper later adds a landing arm for a generated scalar's named component, so a component name is a landing and any other name past one stays an absence (principles architect at pickup, 2026-11-22).
2. **The store-fed routine write reads its bindings from the store.** `RoutineWriteQuery.arguments` reads the raw `bound_to` off the pair entry, and `RoutineWriteEmitter.routineCall` reads it through the walk-era `RoutineCallEmitter.envArgumentRead` and `ProjectedKeyReads`, fed by the deprecated `intent_resolved_node_key_projection`. A component name would be walked as a map key. The routine write instead reads its fills from `graphitron_occurrence_call_parameter` (its `ROUTINE` rows exist) and what each receives through `graphitron_occurrence_call_parameter_pair` → `graphitron_argmapping_landing` → `graphitron_argmapping_landing_key_column`, in a statement `RoutineWriteQuery` owns. Only the decode sink is shared with the service emitter, not `FillQuery`, which is `SERVICE`-only. The node-id slot rule gains a `ROUTINE` arm, its parameter's type read from the routine parameter's binding type (`sql_routine_parameter`), so a routine's node-id projections and their type check sit in the store and `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` speaks for routine parameters too (principles architect at pickup, 2026-11-22). This overlaps the graph node "Routine writes read only the store" (E's) in `roadmap/derived-read-cost-is-a-shape-problem.md`, which takes the write's tenancy off the walk; the two land in either order and neither restates the other.

### Model

- **Directives.** `@generatedScalar(kind: GeneratedScalarKind!)` on `SCALAR` with its enum, and `@assertType(component: String!, type: TypeCondition!)`, repeatable, on `ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`, with the `@oneOf` input `TypeCondition`, declared in `directives.graphqls`. New kinds are new enum values; new atoms are new `TypeCondition` fields.
- **Capture.** `@generatedScalar` is a plain directive entry, `graphitron_ast_generated_scalar_entry`, holding the kind at the application's position as `graphitron_ast_scalar_type_entry` holds `@scalarType`'s constant. `@assertType`'s nested `TypeCondition` value is already captured: `graphql_ast_value_entry` holds every written value as a self-referencing tree, one row per node with its parent, position and the object field it sets. No positional entry tables of its own are added (fact-model § "A value is one relation"; principles architect at pickup, 2026-11-22).
- **No type-grain relation of its own.** A reader finds a generated scalar by `graphitron_scalar_class.source = 'GENERATED'` and its kind from the captured `@generatedScalar` application's entry row, the kind's one home: no other relation, the landing included, carries a kind column; a relation at type grain is added only if something keys on it that those two cannot answer (principles architect at pickup, 2026-11-22).
- **Its components**, at kind grain: one row per kind and component, with the component's Java class (`String` for all six today). Stated facts rather than knowledge in each reader, so a new kind adds rows, not rules. The model names no decoded record class: which record a kind decodes into is the emitter's mapping, since a record's name depends on the output package (fact-model ownership ¶, Alf 2026-11-17; principles architect at pickup, 2026-11-22).
- **Its Java class.** `graphitron_scalar_class_rule` gains a third source, `GENERATED`, beside the built-ins and `@scalarType`, with `java.lang.String` as the class, and a column recording which source answered. Every existing reader then reads it as a string, which it is on the wire: argument arrival, input types, the service by-type rule, and `SERVICE_RETURN_MISMATCH` for a `@service` returning a `String` into an output field of the scalar.
- **`@scalarType` beside `@generatedScalar`** is two claims on one scalar and draws `CLAIMS_CONFLICT`, not a code of its own. `graphitron_scalar_class_rule` states what it writes when both answer: no row, the conflict being the defect, never a silent pick of one source (principles architect at pickup, 2026-11-22).
- **Names.** No new relation is named with a bare "condition", which already means `@condition` throughout the store; the requirement tree's relations are named after `@assertType`.
- **Components are a landing.** `graphitron_argmapping_landing.receives` widens from what a node-id landing hands over to what any landing hands over, with a value for a generated scalar's component, matched from the trailing name against the kind's components. The new arm comes first in its `CASE`, since a bare descent already yields `COLUMN`, and reaches the kind through the scalar's entry row; the landing gains no kind column. The `trailing_name` comment, which says node ids only, and the CHECK are rewritten to the wider statement. No sibling relation. A trailing name that is not one of the kind's components writes no landing, under blocker 1's absence, and so draws `ARGMAPPING_PATH_UNRESOLVED`.
- **What each parameter receives** needs no relation of its own: `graphitron_occurrence_call_parameter_pair` → `graphitron_argmapping_landing` already states it, the landing's `receives` and `trailing_name` naming the component. The emitter reads that join, and the type-disagreement defect is raised over it (plain-join ruling, Alf 2026-11-15; principles architect at pickup, 2026-11-22).
- **A requirement is a stored tree**, derived from its capture and keyed on the declaration, not per occurrence: one row per `@assertType` at its element coordinate and ordinal with its component, and one row per condition node beneath it with its operator (`IS`, `IMPLEMENTS`, `ANY_OF`, `ALL_OF`, `NOT`) and, for an atom, the type it names, decoded from `graphql_ast_value_entry`'s tree (fact-model § grain, Alf 2026-11-13; principles architect at pickup, 2026-11-22).
- **Registration from the store.** Which scalars are generated, and of what kind, is read from the store into a store-rendered unit beside the voucher precedent (`FixedRuntimeEmitter`, `VoucherIssueQuery`), which registers each as a string `GraphQLScalarType` under the author's name. The walk's schema class only references that unit, and `TypeBuilder.classifyScalarType` is handed the scalar's `graphitron_scalar_class` row rather than deciding it, the way the walk stands aside for a store-owned field; it gets no new arm of its own. `VoucherIssueQuery`, which throws for a scalar with no binding, reads the same fact (principles architect at pickup, 2026-11-22).
- **Defects**, each a stored fact with a guidance fragment:
  - one positive rule, not a list of bypass routes: an argument or input-field occurrence of a generated scalar may be bound only as a call parameter whose fill's pair lands on one of its kind's components. Any other binding of one (bound whole, a parameter matched by name or by type, an input bean or record, a column a `@mutation` writes, an implicit filter, an `@outbox` payload key) is `GENERATED_SCALAR_BOUND_WHOLE`; an occurrence with no component binding at all, including one on a field with no call and one carrying an `@assertType` nothing decodes, is `GENERATED_SCALAR_UNDECODED`, so the goal's promise holds for every input value and no `@assertType` is ever silently skipped. `GENERATED_SCALAR_UNDECODED` fires only where nothing else judges the occurrence, standing aside where `ARGUMENT_UNBOUND`, `COLUMN_UNRESOLVED` or `MEMBER_UNKNOWN` already speaks for it or a step above it, one cause drawing one code, and both stand aside where a deferral below (`@condition`, child `@routine`) names the site, the deferral winning over the positive rule. Both are raised by the last stage whose bindings they read (defects-as-facts § supertype, Alf 2026-11-09; principles architect at pickup, 2026-11-22);
  - a trailing name that is not one of its kind's components: no landing, so `ARGMAPPING_PATH_UNRESOLVED`, as above;
  - a list of a generated scalar bound at a call, as an `UNSUPPORTED` deferral, `GENERATED_SCALAR_LIST_UNSUPPORTED`; a list of input objects is a list too, so a scalar reached through one, its own type single, defers under the same code;
  - a parameter receiving a component whose SQL or Java type does not take the component's class, over the pair → landing join (the counterpart of `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` for a key column), raised from the routine's or method's captured parameter types as `PARAMETER_MISMATCH`, widened to say a component's class, not a code of its own; the new routine parameter type check is confined to component bindings, so no routine parameter bound otherwise draws a new refusal;
  - a `@assertType` whose component is not one of its scalar's components (`ASSERT_TYPE_COMPONENT_UNKNOWN`), or placed on an element whose type is not a generated scalar (`ASSERT_TYPE_ELEMENT_NOT_GENERATED`); an `is` naming no object, interface or union in this subgraph (`ASSERT_TYPE_IS_UNKNOWN`); an `implements` naming no interface in it (`ASSERT_TYPE_IMPLEMENTS_UNKNOWN`), all raised by the `assert-types` stage, which stores the requirement trees right after the federation vocabulary completes the types; a condition setting no field or more than one. For the last, graphql-java refuses a `@oneOf` literal setting no field or two, but at schema validation as an `InvalidSchemaException`, which is no `GraphQLError` and crashed capture until assembly recorded every validation refusal as an `ASSEMBLY` row of `graphql_schema_problem`; that row is the defect and the store states no rule of its own. `{is: null}` passes graphql-java, so the store keeps its own null-atom rule: an atom naming nothing is refused under the code for an `is` naming no type;
  - an output field of a generated scalar whose column is not a string;
  - a generated scalar bound at a call kind whose call is still rendered by the walk, as an `UNSUPPORTED` deferral naming the site, `GENERATED_SCALAR_SITE_UNSUPPORTED`, never a Java refusal: today a `@condition`, a child `@routine` step, and a `@routine` on the Query root, which reads rather than writes and is walk-rendered, so a component bound there defers alongside the other two.

### Emit

- **How a component reaches each query.** Services fill through `FillQuery` and `FillEmitter`, whose `Landing` carries the node-id decode; it gains the component a landing receives, read through the pair → landing join. After the second blocker the routine write reads its own stored fills in `RoutineWriteQuery`'s own statement, its node-id projections through the node-id slot family (`graphitron_occurrence_node_id_slot_parameter`, `_shape` and `_projection`), and the component read is a pair → landing join there; the two queries share the decode sink, not a query. There is no shared decode prelude: node-id decodes stay one helper per parameter, and the generated-scalar sink decodes once per argument per call, grouping the fills by the landing they read.
- A sink in `emit`, beside the routine-write and service emitters, that decodes each bound generated-scalar argument once per call, ahead of the call and any transaction, into a local of its record type, checks the argument's requirements on it, and hands components to the parameter reads whose landings name them. For a child `@service` the decode runs in the fetcher before `loader.load`, so a wrong value fails the field it was given on. It is its own sink, not a widening of `render.ProjectedKeyReads`, which the walk's renderers still share.
- The records and their decoders are generated helpers in the output package. A `SCHEMA_PATH` decoder, given the served schema and a string, parses `Name(.Name)*`, resolves the start as an object or interface type whose name does not start with `_`, steps each field through the field's type with list and non-null wrappers removed, and returns the record or throws. A `TYPE_NAME` decoder checks one name, an object type, the same way. A null value decodes to a record of nulls without checking.
- A requirement is rendered as a Java predicate over the component's type name and the served schema, from its stored tree: `is` compares names, `implements` asks the schema's implementations of the interface (transitively through interfaces), and the combinators are `||`, `&&` and `!`.
- **The error.** `GraphitronClientException` carries a message, and the error router places it at the field's path, so the message itself names the argument and the failing step or requirement: "input.mottakersti: Soknad has no field sokr", "input.mottakersti: the path's endType `Saksbehandler` is not `Soker`".
- Call kinds: root and child `@service` calls and Mutation-root `@routine` writes, all emitted from the store (Alf, 2026-11-22). A `@condition`, a child `@routine` step or a Query-root `@routine` binding takes the deferral above until its call is emitted from the store.
- **A child call decodes twice.** A child `@service` decodes its generated scalar arguments in the fetcher before `loader.load`, so a wrong value fails the field it was given on, and its batch body decodes them again off the batch's first environment where it hands the components over. Handing the fetcher's record to the body instead would cross the loader seam: the key is the parent's typed key record and carries no argument data, and the key context every child body reads is the `DataFetchingEnvironment`, so the record would need a wrapper context or an extra body parameter shared by every child frame. The second decode is cheap, a split of the string and a lookup per step in the served schema, and equivalent: the loader is named by the field's path, so every environment in a batch carries the same field and the same argument values, and a value the batch decodes has already passed the fetcher's check.

## Tests

- Fact documents, with defect rows beside fact rows: the scalar's kind, its components and its `GENERATED` class row; a `startType` and an `endType` landing and the call parameters whose pairs land there; a requirement tree with each operator; each defect above with a near miss (`x.startType` beside `x.startTyp`; `x.text` beside `x` bound whole, by name and by type; an input bean holding a generated scalar; a `@mutation` column, a filter and an `@outbox` key of one; a list of one; a `@assertType` nothing decodes; a string parameter beside an integer one; `implements: "Node"` beside `implements: "Sak"`; `is: "Soker"` beside `is: "Sokr"`; `component: "endType"` beside `component: "end"`; `@scalarType` with `@generatedScalar`). The first blocker's document is about trailing names alone, since generated scalars do not exist in its commit: a name past a plain `String`, a plain `ID` and an input object that has no field of that name each write no landing and draw `ARGMAPPING_PATH_UNRESOLVED`, and a key column named past a `@nodeId(typeName:)` lands, the near miss.
- Execution tier mostly in `graphitron`, as scenarios generated through `GenerateTestSchemas` and run against the shared database (`ChildOutcomeExecutionTest` is the canonical shape), with a small function added to the sakila database's `init.sql` that records what it receives. Only the `@error` routing end to end stays in `graphitron-sakila-example` (principles architect at pickup, 2026-11-22):
  - a valid path reaches the function as its text, start type, field steps and end type, a zero-step path's steps as the empty string, and a `TYPE_NAME` as its name;
  - a path stepping through a connection's `nodes`, and one ending in an interface, handing over the interface's name;
  - a path naming a missing field, a missing type and a step past a union, each refused at the argument with nothing called;
  - each condition operator, accepting one value and refusing another, including `not` and a nested `anyOf` inside `allOf`;
  - a refusal routed into a payload through `@error`, with the argument named in the message;
  - a null path reaching the function as nulls in every component;
  - a root and a child `@service` taking components of a path, the child as two aliased fields with different argument values, one refused and one served: the decode runs in the fetcher before `loader.load`, so a failure lands on that field alone;
  - an output field of the scalar read from a string column, and one answered by a `@service` returning a `String`;
  - the routine write's existing node-id projection cases, unchanged after the second blocker.
- No assertions on generated text.

## Decided

- Values and requirements are checked at the call boundary against the served schema, not in the scalar's coercion (Alf, 2026-11-22): one error surface, routable through `@error`, and no copy of the schema.
- A path's end type is the declared named type, interface and union included; a `TYPE_NAME` names an object type, as `__typename` gives it (Alf, 2026-11-22).
- Type requirements live at the use site, act on the internal record's components, and form a small algebra of atoms (`is`, `implements`) and combinators (`anyOf`, `allOf`, `not`) that grows by adding atoms (Alf, 2026-11-22).
- Both `@routine` writes and `@service` calls bind components in this item (Alf, 2026-11-22).
- The requirement directive is `@assertType`, clear of Federation's `@requires`; every kind has a `text` component holding the value as provided, and a path's `path` component is its field steps alone, without the start type (Alf, 2026-11-22).

## Retired vocabulary

* `UNDECLARED_NODE_ID` (the verdict), blocker 1
* `RoutineWriteQuery.Argument.path`, `RoutineWriteQuery.Argument.segments` and `RoutineWriteQuery`'s read of the raw `bound_to`, blocker 2
* `RoutineCallEmitter.envArgumentRead`, blocker 2
* `ProjectedKeyReads` and `ArgPathHelperRegistry` in `RoutineWriteEmitter`, blocker 2
* the Mutation-root routine write's consumer of `KeyProjectionCommands` and `intent_resolved_node_key_projection`, blocker 2
* the walk's two hand-offs, each deleted when its tier flips: `TypeBuilder.classifyScalarType` handed the scalar's row (`BuildContext.generatedScalars`, read off `GeneratedScalarQuery`, and the `Synthesised` string it classifies one as), and the walk's schema class referencing the store-rendered registration unit (`GraphitronSchemaClassGenerator`'s skip of a generated scalar in its scalar loop and its `GeneratedScalars.register` call, with `rejectUnregisteredScalarReferences` counting the generated scalars registered)

## Other solutions we've considered

- **One directive per kind** (`@schemaPath`, `@schemaTypeName`): rejected for one directive with a closed enum, so the list of scalars Graphitron can generate is in one place and grows by an enum value.
- **Reserved scalar names** (`Path` and `TypeName` recognised without a directive, as the federation scalars are): rejected, because a consumer may already declare a scalar of either name with its own `@scalarType`, and Graphitron maps explicitly rather than by name.
- **A plain `String` as the Java value**, with the start and end computed at each binding: rejected, because the components are the value's meaning and belong decoded once, in one record, with `argMapping` naming what each call needs.
- **Requirements on the scalar** (`@generatedScalar(kind: TYPE_NAME, implements: "Node")`): rejected, since one scalar is used at sites with different requirements, and a requirement on the start or end of a path is a statement about the argument, not the scalar.
- **`@assertType` on plain strings**, either a `String` holding a type name or value conditions such as a pattern: rejected. A type name is what `TYPE_NAME` declares in the published schema, and value validation of strings is `graphql-java-extended-validation`'s directive set, which R98 adopts (Alf, 2026-11-22). `@assertType` keeps to conditions on the types a generated scalar's components name.
- **Checking in the scalar's coercion** against a generated table of types and fields: rejected, since a failure there fails the whole request where `@error` cannot catch it, the scalar cannot see which argument's requirements apply, and the table would be a second copy of the schema.
- **Checking in the database function**: the function would need the schema, which is what this item exists to avoid.
