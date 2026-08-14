---
id: R668
title: "Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection"
status: Spec
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection

A `@nodeId` field carries a base64-encoded node identity on the wire, not the primary key it
encodes. Graphitron already knows how to turn that wire form back into typed key values: the
decode side ships for lookups and filters, where an argument or input field annotated
`@nodeId(typeName: T)` is decoded into `T`'s key columns and fed to an `IN` / `VALUES` join.
That decode is not wired into the `@routine` parameter binding. `argMapping` hands a routine
IN parameter the *raw* value at the path it names, so a `@nodeId`-carrying input field delivers
the base64 string.

The concrete case, an access-control mutation whose routine takes the organisation's integer
key:

```graphql
input OpprettFeideApplikasjonInput {
  navn: String!
  organisasjonId: ID! @nodeId(typeName: "Organisasjon")   # encodes organisasjon.organisasjonskode (INTEGER)
  serviceId: String!
  beskrivelse: String
}

type Mutation {
  opprettFeideApplikasjon(input: OpprettFeideApplikasjonInput!): OpprettFeideApplikasjonPayload
    @routine(
      name:       "opprett_feide_applikasjon"   # (p_navn TEXT, p_organisasjonskode INTEGER, p_service_id TEXT, p_beskrivelse TEXT)
      argMapping: "pNavn: input.navn, pOrganisasjonskode: input.organisasjonId, pServiceId: input.serviceId, pBeskrivelse: input.beskrivelse"
    )
}
```

This item makes the node type's key columns nameable as a trailing path segment, so the binding
reads:

```
argMapping: "..., pOrganisasjonskode: input.organisasjonId.organisasjonskode, ..."
```

`organisasjonskode` is not a field of any SDL type. It is a *key column of the node type the
`@nodeId` names*, and the segment means "decode this node id and project that column out of the
decoded key tuple".

## What happens today

Three outcomes, measured against the sakila test catalog by classifying a `@nodeId` input field
into `rent_film(p_inventory_id INTEGER, ...)` and into `create_secure_note(p_owner TEXT, ...)`.
None of them is the one the author wants, and the worst of them is silent.

* **`ID` into an `INTEGER` parameter: a rejection that never says `@nodeId`.**
  `pInventoryId: input.inventoryId` resolves the leaf to the `ID` scalar.
  `RoutineDirectiveResolver.leafTypeGate` runs the shared coercion gate
  (`ServiceCatalog.argExtraction` → `WireCoercionResolver.checkScalar`), which compares `ID`'s
  graphql-java coercion output against the parameter's Java type and rejects with
  `Assignability[sdlLeafType=ID!, coercionOutputType=java.lang.String,
  declaredType=java.lang.Integer, site=@routine parameter 'pInventoryId']`. The message reads as
  a type mistake, not as a missing decode.
* **`ID` into a `TEXT` parameter: no rejection at all, and the wrong value ships.** The same
  binding against `create_secure_note`'s `p_owner TEXT` classifies clean, as
  `ArgBinding[routineParamName=pOwner, paramType=java.lang.String, source=Arg[extraction=Direct[],
  path=…owner]]`. The base64 node id goes to the database verbatim. This is the sharper half of
  the problem: silently wrong data rather than a build failure.
* **The proposed segment is rejected by the shared path resolver.**
  `pInventoryId: input.inventoryId.inventory_id` never reaches the routine resolver;
  `ArgBindingMap.of` rejects it first with `@routine argMapping entry 'pInventoryId:
  input.inventoryId.inventory_id' walks through scalar 'ID' at segment 'inventoryId'; only
  input-object types may be traversed`. The traversal rule is right for every path segment that
  exists today; the proposed segment is a new kind of segment it has no concept of.

## Design

### The rule: a dot opens the thing at that position

The grammar does not gain a second form. It gains a second *openable thing*, under a rule the
existing form was already a case of:

> A dot opens the thing at that position. What it opens depends on what the thing is.

An input object opens into its fields. A node id opens into the key columns of the type it
refers to. Nothing else opens, and a segment on something that does not open is the same
rejection it is today, restated at this grain: "this thing has nothing to open" rather than
"only input-object types may be traversed". So the rejection stays permanent rather than
becoming conditional on a lookup, which is the property that matters about it.

This is worth stating in the item because the alternative reading, that the dot separator now
carries two vocabularies (GraphQL field names and SQL column names) sharing one separator, is
the reading that makes the form look expensive. It is the wrong reading. There is one
vocabulary, "what can I open here", and the answer has always depended on the thing being
opened; today only one kind of thing opens, so the dependence has never been visible. The rule
also extends: whatever the next openable thing is, it slots in without a new separator, and
`roadmap/nested-argmapping-syntax.md` (R249) composes with the rule rather than negotiating
against it.

The LSP inherits this directly, and favourably. Completion after a dot asks one question, "what
does the thing at this position open into", and answers it per kind: input-object fields, or the
node type's key columns. That is a uniform trigger rather than a special case, and the node-id
arm is answerable *today* without the nested-input-field projection the general arm is waiting
on (see "Relationship to other items").

A lexically disjoint form was considered and rejected: a sigil, or a call form such as
`key(input.organisasjonId, organisasjonskode)`. It was proposed in order to keep the two
vocabularies separable at the lexer, and it is unnecessary once the rule above is stated, because
there are not two vocabularies to separate. It would also cost the property it was meant to buy:
a separate form gives the LSP a second trigger to implement and an author a second syntax to
learn, for the thing the dot already means.

### What the key-column segment names, and why `typeName:` must be explicit

The segment names a column of the referenced type's **node key**: what
`@node(keyColumns: [...])` declares when the author pinned it, and the catalog's key metadata
otherwise. The authority is the `@node` declaration on the type the `@nodeId` refers to, which is
the same place an author already looks to know what a node id encodes.

Both halves of that are captured facts, which is what makes this item a store item rather than a
resolver item: `graphitron_node_key_column (graph_name, type_name, position, column_ref)` holds the
pinned list in written order, and `sql_primary_key` holds the catalog fallback, reachable from the
node type through `intent_bound_table`. The reconciliation of the two is a view, and it is the
item's spine (see "The resolution is a view").

The spelling is the SQL column name, because that is what `@node(keyColumns:)` itself is a list
of. Case-insensitive matching is the intent; check at pickup whether that is inherited from a
settled convention in the neighbouring `ColumnRef.sqlName` comparisons or is a new rule this item
introduces, and say which in the docs. The answer changes nothing about the design and everything
about whether the rule needs stating.

`@nodeId` without `typeName:` is rejected at this position. `NodeIdLeafResolver.inferTypeName`
infers a bare `@nodeId`'s target from the *containing table*, and a routine parameter has no
containing table; there is nothing to infer from. `NodeIdLeafResolver` already carries two permanent
messages for this same underlying fact ("cannot infer a node type here"), though they end
differently ("Add typeName: explicitly." and "Specify typeName: explicitly."). The projection's
rejection is derived elsewhere (see "The bare form becomes a rejection"), so what carries over is
the *wording*, not the site: an author meets one vocabulary for one condition, and the existing two
should converge on one phrasing as this third one is written.

### The resolution is a view, not a resolver

Every input this projection needs is already captured, and that is the finding that decides the
item's shape. The store holds `graphitron_routine_arg_mapping_pair.argument_path` (the right side
as written, per-pair, keyed at the application's coordinate), `graphitron_field_node_id
(type_name, field_name, node_type_ref)` for the `@nodeId` on an input field, `graphitron_node` and
`graphitron_node_key_column` for the node identity and its pinned key list, `graphql_field` for the
input-object fields the path walks through, and `sql_column` / `sql_primary_key` reachable through
`intent_bound_table` for the catalog side. Nothing about resolving the motivating path needs a fact
the store lacks on the SDL side.

So the projection is derived by **querying those relations**, not by a Java resolver walking
graphql-java objects. Concretely, three views in the shipped `intent_` mould:

* **`intent_node_key_column_resolved (graph_name, type_name, position, column_ref, source)`.** The
  reconciliation the item's authoring rule depends on: the pinned `graphitron_node_key_column` list
  where the author wrote one, the catalog primary key through `intent_bound_table` and
  `sql_primary_key` where they did not, with `source` naming which arm won. This is the relational
  form of what `BuildContext.resolveTargetKeys` does in Java, and it is worth having on its own
  terms: the LSP wants exactly this list for completion, and `NodeType.nodeKeyColumns` cannot serve
  it (that list is empty whenever the author did not pin one, and an empty completion list is worse
  than none). A second reader is what turns a derivation into a named relation, and this one has
  three.
* **`intent_argmapping_binding_leaf`.** Each `*_arg_mapping_pair` row's path resolved against
  `graphql_field`, segment by segment, down to the leaf it names, plus whether one trailing segment
  remains unconsumed. Path traversal in SQL is the part to prototype first (see "Risks").
* **`intent_argmapping_node_key_projection (graph_name, site, type_name, field_name, ordinal,
  position, param_name, node_type, table_schema, table_name, column_name)`.** The reduction: a
  binding whose leaf carries `@nodeId` and whose trailing segment names one of that node type's
  resolved key columns. One row per projected binding, keyed by the pair's own natural key.

The property that matters is the same one the demand stratum has: **every arm is additive**, and
uniformity across `@routine`, `@service` and `@condition` costs nothing rather than being
maintained. The six `*_arg_mapping_pair` relations are the same shape, so the binding view is a
`UNION` with a `site` literal per arm, and a projection is resolved identically at every site
because it is resolved once. That is a stronger guarantee than the previous draft's "one shared
resolver called by three directive resolvers", which was three call sites agreeing by discipline.

The typed product is `AuthoredClaimConflicts`' shape exactly: a class in `rewrite/derive` reading
the view through the `DSLContext` inside the capture transaction, returning records built from
query rows. Those records are the item's new types, and they are the only new types it introduces.

### What this item does not add

**No new `CallSiteExtraction` arm, no `NodeIdRecordColumn`, no `BoundPath`, no new sealed variant
anywhere, no walk-side registry.** The sealed leaf model is the strangler migration's transitional
producer surface, drained rather than extended, and a capability is added by adding a fact
relation. An earlier draft of this item did the opposite: it proposed a new top-level
`CallSiteExtraction` arm wrapping `NodeIdDecodeRecord`, a `BoundPath` type in `ArgBindingMap`, a
widened `resolvePathLeaf` producer, and a new render-side registry. Every one of those is
walk-side, and the reasoning that produced them, that the arm's compile errors in each exhaustive
switch would be a useful work list, is an argument for a well-shaped leaf zoo, not an argument for
extending one that is being drained. The fact model names this failure directly: duties welded onto
a leaf that functionally depend on another coordinate's query.

The projection *is* another coordinate's query. It is a functional dependency of the pair's key
(graph, type, field, ordinal, position), resolved against the node type's key columns, and it has
no business on a leaf minted while walking a different coordinate.

What the walk does instead is **stop rejecting**. Two removals, both narrowings rather than
extensions:

* `ArgBindingMap.of` must stop rejecting a trailing segment after an `ID`-typed leaf. Today it
  rejects with "walks through scalar 'ID' at segment ...; only input-object types may be
  traversed", and that rejection is what makes the spelling unwritable. It admits the extra segment
  and carries it in the `PathExpr` without interpreting it.
* `RoutineDirectiveResolver.leafTypeGate` must stop rejecting the `ID`-into-`INTEGER` binding whose
  path has that trailing segment, since the value reaching the parameter is no longer the `ID`'s
  coercion output. The gate keeps every other verdict.

Both are deletions from the drained surface. Neither adds a fact to it.

### Where the resolved projection is consumed

Two consumers, and the pipeline order (walk, then capture, then validate, then plan, then render)
decides what each can do.

* **Validation reads it as violations, and this is the shipped pattern.** `FactCapture` already
  runs `AuthoredClaimConflicts` over freshly captured rows inside the transaction and returns a
  typed `Detection` the caller folds into the error stream. The rejections this item needs (the
  bare form, a segment naming no key column, `@nodeId` without `typeName:`, a `@nodeId` on the
  argument itself) are detection views in the `intent_authored_claim_conflict` mould, decoded into
  located `ValidationError`s by a sibling of that class. Rejection stays a typed value; what
  changes is that the rule lives in SQL and the Java decodes a closed verdict vocabulary.
* **Planning joins it onto the command row.** `EmitPlan.produce` runs after capture, so the
  resolved projection is available to it, and commands are complete rows: a renderer that had to
  query the store would break the completeness law the render fold enforces. So the routine-call
  command carries the resolved projection per binding, and `RoutineCallEmitter` emits from the row
  it is handed.

**The open seam is exactly one question: what the command row carries.** `RoutineRef.ArgBinding`
holds a `ParamSource.Arg(CallSiteExtraction, PathExpr)` today, and this item may not add an arm to
that. Two candidate shapes, to settle at the Ready gate rather than during implementation:

* a projection map on the routine-call command, keyed by parameter name, carrying the resolved
  `(node type, table, column, wire facts)` rows the view produced, with `ParamSource.Arg` untouched
  and still `Direct`; or
* the same rows reaching the renderer as their own committed command relation, joined by the
  binding's key, which is closer to where the architecture is going and further from what the
  routine path looks like today.

The first is smaller and does not touch the sealed model at all. Prefer it unless the reviewer sees
the second as the shape that stops this from being re-done later.

### The `sql_` family gains routine parameters

The type gate needs the routine parameter's Java type, and the store does not hold it. That is a
capture gap, so it closes in capture: the facts are extended where the fact-finding code can most
easily reach them, which is the jOOQ catalog walk that is already standing on the objects that
carry them.

**A table-returning routine is exposed by jOOQ as a table**, and that decides the shape. It arrives
in the catalog as a `Table` whose `getTableType()` is `FUNCTION`, which is exactly how
`JooqCatalog.resolveTableValuedFunction` finds it: `findTable(routineName)` first, then the
`getOptions().type().isFunction()` check. So the walk that writes `sql_table` has already visited
every routine `@routine` can name; what it does not write is that the row is a function, or what
parameters it takes. The `routines/` sub-package probe in `JooqCatalog.findNonTableValuedRoutine`
is *not* part of this picture: it is the fallback for a routine that is **not** table-valued, which
`@routine` rejects outright with `RoutineResolution.NonTableValuedRoutine`.

`sql_column` is the model, down to the rationale. Its own comment argues the shape: "A column
carries two types, not one: the SQL type the database declares and the Java type jOOQ binds it to.
Both are facts about the column and neither derives from the other by any rule the store could
apply, since the mapping is the generator's configured binding." A routine parameter is the same
subject with the same two types, and `binding_type` is exactly what the gate compares against.

Two additions to `CatalogFactCapture.captureCatalog`, both inside the loop it already runs over
`jooq.allTableEntries()`:

* **`sql_table.table_type`**, one column, from `Table.getTableType()`. jOOQ's
  `TableOptions.TableType` distinguishes `TABLE`, `VIEW`, `MATERIALIZED_VIEW`, `FUNCTION` and the
  rest; `sql_table` records none of it, so the store cannot currently tell a table-valued function
  from a table. The walk is already holding the `Table<?>`; this is the cheapest true fact in the
  item, and it is what makes "which of these rows is a routine" a query.
* **`sql_routine_parameter (source_name, table_schema, table_name, position, parameter_name,
  jooq_name, sql_type, binding_type, ...)`**, hanging off the function-typed `sql_table` row by
  foreign key exactly as `sql_column` hangs off a table. A function's parameters are to it what its
  columns are to a table, and keying them the same way means no new anchor and no new source
  vocabulary.

**Which reader gives which half.** The parameters live on the table object as `Field<?>` values, but
`TableImpl.parameters` is `protected`, so the public `Table` interface does not expose them. The
publicly reachable reader is the generated `Routines` convenience method, which is what
`JooqCatalog.resolveTableValuedFunction` reads today (`Class.forName(schemaPackage + ".Routines")`,
then the table-form overload picked by return type and by taking no `org.jooq.Field` parameters).
That gives the **Java** parameter name and Java type, which is what this item's join and gate both
need:

* `jooq_name` is the join key, because `graphitron_routine_arg_mapping_pair.param_name` holds what
  the author wrote (`pInventoryId`) and `RoutineDirectiveResolver` matches it with
  `p.name().equals(claimed)` against that same reflected method parameter.
* `binding_type` is the gate's left-hand side, against `sql_column.binding_type` on the right.

The **SQL** half (the database's own parameter name and type) is on those `Field<?>` values and is
therefore reachable only by reading a protected member. Decide at pickup rather than in the DDL:
capture it for parity with `sql_column` if the read is clean, or omit the two columns and say in
the relation's comment that the parameter's SQL vocabulary is not publicly exposed by jOOQ. Do not
write columns that will always be null.

**One dependency the join key inherits, which is silent today.** A reflected parameter name is
`arg0` unless the consumer compiled their jOOQ output with `-parameters`. The generator already
depends on this in the name matching above, and this repo's own test tree compiles with
`-parameters` for a related reason. Capturing the name makes the dependency visible instead of
creating it, and the column's comment is where it belongs. If the flag is absent the row is still
faithful (it records what the class actually says); what degrades is the join, which is the honest
place for that to surface.

**What this buys beyond this item.** The type gate becomes a join between two `binding_type`
columns. `@routine`'s "no such parameter" rejection, which today reads
`fn.params().stream().noneMatch(...)` in the resolver, becomes an anti-join against a captured
population. The routine half of the LSP's hover and completion stops being reachable only through a
live codegen classloader. None of that is this item's job to deliver, but all of it is unreachable
until these rows exist, which is the argument for capturing them properly rather than working
around the gap.

### The bare form becomes a rejection

Binding a `@nodeId` leaf with *no* key-column segment is rejected, naming the node type and listing
its resolved key columns. This is the change that closes the silent `TEXT`-parameter hole, and it
is worth landing even if everything else here slipped: today that spelling writes a base64 string
into a database column and nothing in the build says a word.

As a detection view it is an anti-join: pair rows whose leaf carries `@nodeId` and whose path
consumed every segment. That is a positive statement about a captured population, not a negative
space maintained by hand, which is what makes it additive in the same way the demand stratum's
exemption arms are.

The counter-proposal, implicit decode for single-key node types, is rejected. It would make the
same spelling mean two different things depending on a fact (the node's key arity) that is not
visible at the `argMapping` site, and it would leave composite-key node types needing the explicit
segment anyway. One spelling, always explicit.

The rejection is universal, not target-driven. An earlier draft argued that binding the *whole*
decoded record to a `@service` parameter typed as the generated `*Record` should stay legal; that
was checked and it is not reachable today (`ParamRole.ArgBound` routes through `argExtraction`,
which rejects an `ID` leaf against a record type, and `NodeIdDecodeRecord` is minted only for
jOOQ-record-typed input-bean members). Enabling it would be a second capability, so it is out.

Three properties to settle here rather than let fall out of implementation:

* **Verdict class.** `Rejection.structural`. There is no future in which the raw base64 was
  intended, so it holds even if the rejection ships ahead of the projection (see "Scope").
* **Location.** The view carries it, the way `intent_authored_claim_conflict` does: the pair row's
  own application position, so the message points at the `argMapping` the author wrote rather than
  at the input type's declaration.
* **Keying axis.** The rejection is *use-keyed*. One input type can be consumed by a `@routine`
  mutation (no containing table, projection required) and by a table-bound `@service` mutation
  (inference works). An author who reads "add `typeName:`" and edits the shared input type is
  editing a definition-keyed fact to satisfy a use-site constraint, so the message must name the
  consuming coordinate that is asking. The view's key already carries it.

`@nodeId` without `typeName:` is rejected at this position for the reason the authoring section
gives, and as a view arm it is simply `graphitron_field_node_id.node_type_ref IS NULL` on a leaf a
projection binds. The message vocabulary should still converge with
`NodeIdLeafResolver`'s two existing "cannot infer a node type here" messages, which today end
differently ("Add typeName: explicitly." and "Specify typeName: explicitly."), since the whole
argument for a shared vocabulary is that authors meet one wording for one condition.

### Emission

The emitted expression is what the existing `@nodeId` machinery already produces one level up:
decode the node id into the target `TableRecord` once, then read the named column off it. That is
the same body `InputBeanInstantiationEmitter.buildRecordDecodeHelper` emits for a jOOQ-record-typed
input-bean member, so the decode is reused rather than rebuilt. Naming the column rather than
indexing a tuple is what makes a transposed composite-key projection unconstructable, and it comes
free from a view whose row *is* a column name.

What changes is where the facts come from: the command row, not a `CallSiteExtraction` arm. Three
emission facts have to be on it, and each has a store answer to confirm at pickup: the node type's
`type_id` (`graphitron_node.type_id`, with the type-name fallback that column's comment defers to),
the target table (`intent_bound_table`), and the column (the projection view). The encoder class is
a generator-configuration fact rather than a captured one; locate it at pickup and say which side
of the line it sits on.

Two mechanical facts about the emitters survive the redesign and still bind:

* **`RoutineCallEmitter` cannot import `FetchersHelperNames`.** It lives in
  `no.sikt.graphitron.render`, and `PackageImportDirectionTest`'s render leg rejects
  `no.sikt.graphitron.rewrite` imports that are not on the borrow dial. Whatever carries the helper
  name has to be render-side or on the command row.
* **The decode helper body is hosted per generated class.** `decode<RecordType>Record` is drained
  onto `<Type>Fetchers` from a walk over that class's input-bean carriers, and
  `ConditionGlueRenderer` builds separate conditions classes with their own registry that cannot
  call it. So a projected read at `@condition` needs the body emitted onto the conditions class:
  `@condition` is the expensive emit site, not the cheap one. Watch for two bodies landing on
  `<Type>Fetchers` when one class hosts both an input-bean decode and a projection.

**Materialise once.** Two projections off one node id must share one decode: one materialisation
and one failure site, not two identical throw points for one bad id. That needs a hoisted local,
which means `emitCall` yields pre-statements alongside its expression. Three of its four call sites
have statement context to hand; `PathFragments.emitTableExpression` returns a bare expression
consumed inside alias-declaration loops, so it either propagates the same signature change to its
own callers or keeps a per-read call with a comment saying why. That is a signature question one
level up, not an implementation detail.

### Fact capture

The store grows on both strata, and the two grow for different reasons.

**Base relations, from the catalog walk:** `sql_routine_parameter` and the `sql_table.table_type`
column, per "The `sql_` family gains routine parameters". These carry the usual obligations: dense
positions on the parameter list, total comment coverage, a `FOREIGN KEY` to `sql_table`,
transcription-twin agreement for the decode, and registration in `FactCaptureAgreementTest` so a
new relation cannot arrive unchecked.

**Derived relations:** the three resolution views plus the detection views, all `intent_`, all
registered under the derived arm.

**No new column on the SDL side.** `graphitron_routine_arg_mapping_pair.argument_path` holds the
right side as written, it is still a dotted path, and capture still records it verbatim. What
changes is that its comment enumerates what a segment can name ("a GraphQL argument name or dotted
input path") and now enumerates it incompletely; restate it at the rule's grain on the `@routine`
pair relation and on its five siblings.

The `sql_` half is the part that makes this item bigger than a view stack, and it is worth it on
its own terms: the parameter facts are unreachable outside the codegen classloader, so a run that
does not capture them cannot answer a routine question later, and every consumer that wants one is
forced back through a live reflective walk. That is the same argument `sql_column.binding_type`'s
comment already makes for columns ("read off the live `Field` during the catalog walk and
unrecoverable afterwards").

## Implementation

* **`graphitron-model.sql`, base relations**: the `sql_table.table_type` column, and
  `sql_routine_parameter` in `sql_column`'s mould (dense positions, FK to `sql_table`,
  `binding_type` and `jooq_name` carrying the Java vocabulary the join and the gate read).
* **`CatalogFactCapture`**: write `table_type` in the existing `sql_table` loop, and add
  `captureRoutineParameters` beside `captureColumns` / `captureConstraints` / `captureIndexes`,
  guarded on the function arm. It reads the generated `Routines` convenience method, which
  `JooqCatalog` already resolves; capture should go through that owner rather than re-deriving the
  lookup.
* **Views**, in `graphitron-model.sql`, house style per `intent_bound_table` (declared column list,
  full comment coverage, closed vocabularies as `CHECK` or as stated column comments):
  `intent_node_key_column_resolved`, `intent_argmapping_binding_leaf`,
  `intent_argmapping_node_key_projection`, and the detection views for the four rejections.
* **Typed products** in `rewrite/derive`, in `AuthoredClaimConflicts`' shape: one class reading the
  projection view and one reading the detections, returning records built from query rows and
  decoding the closed verdict vocabulary into `Rejection` arms. These records are the only new
  Java types the item introduces.
* **`FactCapture`**: run the detection over the freshly captured rows inside the existing
  transaction and return it in the typed product the caller already folds into the error stream.
* **`GraphitronSchemaValidator`**: fuse the new violations the way the claim conflicts are fused.
* **`ArgBindingMap.of`**: delete the traversal rejection for one trailing segment after an
  `ID`-typed leaf; carry the segment in the `PathExpr`. No `@nodeId` check (it holds no directive
  container for a head-segment leaf), no `Site` parameter, no new type.
* **`RoutineDirectiveResolver.leafTypeGate`**: stop rejecting an `ID` leaf whose path carries the
  trailing segment. Every other verdict stands, including the ordering fact that the gate's
  `resolvePathLeafType == null` pass-through sits between the list-shape scan and `argExtraction`.
* **`EmitPlan`**: join the projection view onto the routine-call command, per the open seam above.
* **`RoutineCallEmitter`**: emit the decode-and-read from the command row; `emitCall` yields
  pre-statements alongside its expression and the four call sites add them.
* **`ConditionGlueRenderer`** and the `@service` pair: the same read, with the decode helper body
  hosted on the conditions class for the `@condition` site.
* **Comments**: restate `argument_path` on all six `*_arg_mapping_pair` relations.

## Tests

* **Capture tests for the new base relations**, in the shape the `sql_` family already uses: the
  transcription twin proving the rows agree with what the catalog walk read, dense positions on the
  parameter list, and `FactSchemaGateTest` for comment coverage. The sakila catalog carries
  `rent_film` and `create_secure_note` as table-valued functions with parameters, so the fixtures
  exist; pin the parameter rows each produces, and pin that an ordinary table produces none.
  `table_type` wants a case per arm it can carry, at minimum `TABLE` and `FUNCTION`.
* **A test that pins the `-parameters` dependency**, since `jooq_name` is the join key and is
  `arg0` without it. This repo already compiles one test package deliberately without
  `-parameters` to cover the `@field(name:)` case, so the precedent for testing both sides exists.
* **View-level tests** in the `ColumnMatchClaimTest` / `DemandShadowTest` mould, one per view,
  pinning each derivation against hand-written expectations the view cannot produce by
  construction. `intent_node_key_column_resolved` needs both arms populated: a node type with
  pinned `@node(keyColumns:)` and one falling back to the catalog primary key, plus a composite-key
  type (`bar` in the `nodeidfixture` catalog is the one `NodeIdPipelineTest` already uses).
* **Registration**: `FactCaptureAgreementTest` under the derived arm for every new view, which is
  what stops a view arriving unchecked; `FactSchemaGateTest` for comment coverage.
* **Corpus population per arm.** A view arm no fixture reaches is a vacuous pin. Each rejection arm
  and each key-column source arm needs a coordinate that reaches it.
* **Cross-site parity is now structural rather than tested**, since one view resolves all six pair
  relations. Keep one test that asserts it anyway, over `@routine`, `@service` and `@condition`:
  the `UNION` arms are still six hand-written `SELECT`s and a typo in one is exactly the drift the
  test catches.
* **Pipeline tier**: the `rent_film` fixture binding `pInventoryId` from
  `ID! @nodeId(typeName: "Inventory")` through the projected key column, asserting the emitted call
  materialises the record once and reads the column off it, plus the four rejection cases.
* **Validate-time tests** that the build fails, not only that a `Rejection` is produced. "Validator
  mirrors classifier invariants" is the rule, and a rejection that derives but does not fail the
  build is the failure mode it guards against.
* **`@condition` emission** needs a compilation-tier assertion about *which class* hosts the decode
  helper body, since that is the half the pipeline tier cannot see.
* **Execution tier** (`graphitron-sakila-example`): one round trip proving the decoded key reaches
  the database as a key rather than a base64 string, alongside the existing
  `NodeIdValueAgreementExecutionTest`.

## Risks

* **Path traversal in SQL is the piece to prototype before committing the item.** Resolving a
  dotted path against `graphql_field` means an iterative walk, and H2's view vocabulary is what
  decides whether that is a recursive CTE inside the view, a bounded join chain for a stated
  maximum depth, or a capture-cadence materialization like the two `InputOccurrencePaths` and
  `ReachabilityRows` already are ("H2 cannot state them as safe views"). That precedent exists
  precisely for derivations a view cannot express, so the fallback is house style rather than a
  concession, but which of the three applies changes the item's size and should be answered before
  Ready.
* **Whether the parameters' SQL-side vocabulary is worth reaching.** They are `Field<?>` values on
  `TableImpl.parameters`, which is `protected`, so capturing the database's own parameter names and
  types means reading a non-public member. The Java side, which is what this item's join and gate
  need, comes off the generated `Routines` method with no such problem. Settle before the DDL is
  written, and omit the columns rather than ship ones that are always null.
* **The `-parameters` dependency** the join key inherits: real, pre-existing, and now explicit.
* **The command-row seam** above: what carries the resolved projection to the renderer.

## User documentation (first-client check)

The user surface is a new spelling on an existing directive argument, so the docs change is
small and lands in three places:

* `docs/manual/reference/directives/service.adoc#arg-mapping` is the shared home of the
  right-hand-side path form, cross-referenced by `@service`, `@condition`, `@routine` and
  `@tableMethod`. Its rule list currently reads "each subsequent segment must name a field on the
  input-object type at that depth", which is the openability rule stated for the only kind that
  existed. Generalise that bullet rather than appending a special case: a segment opens the thing
  at that position, an input object opens into its fields, a `@nodeId` leaf opens into the key
  columns of the type it refers to. Because the form works at every directive that accepts an
  `argMapping`, the shared section needs no per-directive caveat: that uniformity is the point of
  the section already existing.
* `docs/manual/reference/directives/routine.adoc`: a short subsection after the existing
  wrapper-input example, showing the `@nodeId` input field and the projected binding. The
  Constraints list gains the bare-form rejection and the explicit-`typeName:` requirement.
* `docs/manual/reference/directives/nodeId.adoc`: a cross-reference from the decode side, so an
  author reading about `@nodeId` finds the routine binding without going through `@routine`.

Draft of the `routine.adoc` subsection:

> **Binding a routine parameter from a node id**
>
> When the input field carries `@nodeId`, its wire value is an opaque base64 id, not the key it
> encodes. Name the key column after the field to bind the decoded key instead:
>
> ```graphql
> input RentFilmInput {
>     inventoryId: ID! @nodeId(typeName: "Inventory")
>     customerId:  Int!
> }
>
> type Mutation {
>     rentFilm(input: RentFilmInput!): RentFilmPayload
>         @routine(
>             name:       "rent_film"
>             argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId"
>         )
> }
> ```
>
> A dot opens the thing to its left. An input object opens into its fields, which is what
> `input.customerId` does; a node id opens into the key columns of the type it refers to, which
> is what `input.inventoryId.inventory_id` does. `inventory_id` is a key column of `Inventory`,
> spelled the way `@node(keyColumns:)` spells it. A node type with a composite key opens into
> each of its key columns, so two parameters can be bound from one id.
>
> A malformed id, or a well-formed id of the wrong type, fails the field with a client error;
> it is never passed through. Binding a `@nodeId` field without naming a key column is a build
> error listing the columns available, and `@nodeId` at this position requires an explicit
> `typeName:` because there is no containing table to infer the node type from.

## Relationship to other items

* `roadmap/routine-coercing-arg-extractions.md` (R625) makes the routine emitter honour
  non-`Direct` extraction arms (`EnumValueOf`, `JooqConvert`). An earlier draft of this item made
  the relationship directional by riding the extraction slot; under the store-derived design it is
  **independent again**, because this item adds no extraction arm and leaves `ParamSource.Arg`
  carrying `Direct`. The two touch the same emitter and nothing else, so the only coordination left
  is the ordinary one about editing `argExpression` at the same time. Worth noting for R625's own
  reviewer: this item's shape is an argument that R625's capability may also belong in the store
  rather than in a wider switch on the drained surface.
* `roadmap/delivery-verdict-derives-from-the-store.md` (R666) is the nearest structural sibling and
  the model this item now follows: a verdict computed by a walk-side switch, restated as an
  `intent_` view over captured base relations, landed in shadow with residues before any consumer
  flips. Read it before picking this one up. If both are in flight, they should agree on the
  shadow-versus-flip discipline rather than inventing two.
* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333) owns the leaf zoo's dissolution.
  This item must not anticipate it or depend on it: it neither extends the sealed model nor
  retires any of it, which is what lets the two proceed without a joint decision.
* `roadmap/lsp-argmapping-routine-coordinate.md` (R626) gives `@routine(argMapping:)` completions
  and diagnostics at all. R626 explicitly leaves dot-path expansion unmodelled ("offer nothing
  rather than a misleading flat list") because the LSP snapshot carries no nested-input-field
  projection. Under the openability rule that limitation splits by kind rather than being uniform:
  the input-object arm still waits on the snapshot projection, while the node-id arm is answerable
  from the node type's key columns, which the snapshot can carry cheaply. Under this design it is
  answerable from a *relation*: `intent_node_key_column_resolved` is exactly the completion list,
  including the catalog primary-key fallback that `NodeType.nodeKeyColumns` cannot serve (that list
  is empty whenever the author did not pin `@node(keyColumns:)`, and an empty completion list is
  worse than none). That the editor is a second reader of the same view is the argument for naming
  it rather than leaving it a CTE. So key-column completion is reachable *ahead* of the general
  case rather than after it. It is still its own item and must
  not ride this one, but R626's "offer nothing" note should be narrowed to the input-object arm
  when either item lands, so it does not read as a blanket bar on a case that is no longer blocked.
* `roadmap/nested-argmapping-syntax.md` (R249) extends the right-hand side with a nested object
  form. It varies the same grammar from the other end and composes with the openability rule
  rather than negotiating against it, so the two no longer need a joint decision on the
  separator. They still share an owner, so coordinate on edits to
  `ArgBindingMap.parseArgMapping` plus `ArgBindingMap.of`.

## Scope: one item or two

The bare-form rejection is separable from the projection, and separating it is worth considering at
the sign-off gate rather than after implementation starts.

Landed alone it needs the binding-leaf view and one detection view, the typed product that decodes
them, and the fusion into the validator. It needs no grammar change, no planning join, no command
row, no emitter work at any site and no answer to the capture gap. That closes the silent-base64
hole, which this item itself calls the sharper half of the problem and "worth landing even if
everything else here slipped".

The store direction makes the split cheaper than it was under the previous design, because the
first half is now the half that ships in shadow anyway: a detection view plus its agreement test is
the shape R666 argues should land before any consumer flips. The projection half is then the flip
plus the emit.

Splitting does not reintroduce a per-directive deferral. Rejecting the bare form at all sites *is*
uniform behaviour, and under one view it is uniform by construction. Nor does the message have to
promise the segment: "bind the decoded key by naming a key column" is a `structural` statement
about what the author must write, and it becomes constructive when the projection lands rather than
retroactively true. That is what keeps the interim verdict `structural` rather than `deferred`.

The argument against splitting is that a rejection with no fix available yet is a worse author
experience than either state alone. That is real, and it is why this is a judgment call for the
sign-off rather than a decision recorded here.

## Open questions

* **Whether a dotted path can be resolved against `graphql_field` inside a view**, or needs the
  capture-cadence materialization the two existing exceptions use. This is the first thing to
  prototype; it decides the item's size (see "Risks").
* **What the routine-call command row carries** to get the resolved projection to the renderer
  without touching `ParamSource` (see "Where the resolved projection is consumed"). Settle at the
  Ready gate.
* Whether `PathFragments.emitTableExpression` takes the pre-statement change (which propagates to
  its callers, since it returns a bare expression consumed inside alias-declaration loops) or keeps
  a per-read call as a documented site-local exception. Answered far enough to size: it is a
  signature question one level up, not an implementation detail.
* Whether a `@nodeId` input field that nothing consumes should warn. Today it is silently
  ignored wherever no consumer reads it, which is how the `TEXT` case above stays invisible;
  the bare-form rejection closes it at the `@routine` site only. A general "declared and
  unconsumed" warning is a larger question and belongs in its own item if anyone wants it.
