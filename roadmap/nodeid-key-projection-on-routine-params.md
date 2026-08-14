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

"Otherwise" hides two distinct sources rather than one, and the resolution has three tiers rather
than two: the reconciled `NodeType`, the table's own generator metadata, then `@node` plus the
catalog primary key. `graphitron_node_key_column (graph_name, type_name, position, column_ref)`
holds the pinned list in written order; the primary-key fallback is `sql_primary_key` joined
through `sql_constraint_column` for the ordered columns, reached from the node type through
`intent_bound_table`; and the middle tier is not captured at all yet. The reconciliation is a view
and it is the item's spine (see "The resolution is a view over facts").

The spelling is the SQL column name, because that is what `@node(keyColumns:)` itself is a list of,
and matching is case-insensitive. That is inherited rather than introduced: `intent_spelled_table`
and `intent_column_match_claim` both compare under `UPPER()`, and `JooqCatalog.resolveColumn` uses
`equalsIgnoreCase`, so the docs can state the behaviour without announcing a rule.

`@nodeId` without `typeName:` is rejected at this position. `NodeIdLeafResolver.inferTypeName`
infers a bare `@nodeId`'s target from the *containing table*, and a routine parameter has no
containing table; there is nothing to infer from. The message to converge with is
`InputBeanResolver`'s, which states this same condition for a jOOQ-record-typed input-bean member
("@nodeId on a jOOQ-record-typed member must specify typeName: explicitly, the record type alone
does not name the NodeType to decode against"), and not `NodeIdLeafResolver`'s pair: those two say
*zero candidates* and *ambiguous candidates*, which are different facts that happen to share a
remedy. The projection's rejection is derived elsewhere (see "The bare form becomes a rejection"),
so what carries over is the wording, not the site.

### The resolution is a view over facts, one of which needs capturing first

Most of what this projection needs is already captured, and one thing is not. The SDL side is
complete: `graphitron_routine_arg_mapping_pair.argument_path` (the right side as written, keyed at
the application's coordinate), `graphitron_field_node_id (type_name, field_name, node_type_ref)`
for the `@nodeId` on an input field and `graphitron_argument_node_id` for the one on an argument,
`graphitron_node` and `graphitron_node_key_column` for the node identity and its pinned key list,
and `sql_table` / `sql_constraint` / `sql_constraint_column` / `sql_primary_key` through
`intent_bound_table` for the catalog fallback. The catalog side has a gap, covered two sections
down.

**The dotted path is already walked, and this is the finding that removes the item's biggest
unknown.** `intent_input_occurrence_path` is a capture-cadence derivation whose rows are exactly
this: an argument whose named type is an input object, or a nested input field reached from one,
keyed by the serialized path `<root type>.<root field>(<argument>)[/<input field>...]`, with every
prefix present as its own row and an `intent_input_occurrence_path_step` child carrying the same
data relationally "so no consumer parses the key". It exists because cyclic input nesting has no
safe recursive H2 view form, which is the question this item would otherwise have had to answer for
itself.

So the binding-leaf resolution is a **keying over that relation, not a second walk**, which is the
move `intent_bound_table`'s own comment records for the spelling view ("a keying over the spelling
view rather than a second copy of it"). An `argMapping` path maps to an occurrence path by a string
expression over the pair's own key, in the `POSITION` / `SUBSTRING` vocabulary the stratum already
uses; the leaf is that row, and "one trailing segment unconsumed" is a right-trim onto the prefix
row rather than a traversal. Writing a second traversal instead is the drift the fact model names:
two spellings of one resolution that agree until one of them changes.

Three caveats the view's comment must own, each narrow and each a stated absence rather than a
silent one:

* The occurrence seed covers arguments whose named type is an input object, so a head segment that
  is a bare scalar argument (`pCustomerId: customerId`) needs a second arm joining
  `graphql_argument` directly.
* The expansion stops at a type already visited on the path (the classification walk's own
  first-visit guard, restated), so a cyclic re-entry has no row. That absence is load-bearing and
  owes a sentence.
* The trailing-segment count is a **column, not a flag**. One unconsumed segment is this item; two
  is a typo or R249's nested form, and the rejection messages must tell them apart. The stratum
  already states arity as a column rather than leaving each reader to count (`intent_spelled_table.
  candidates`).

**The key-column resolution has three arms, not two.** `BuildContext.resolveTargetKeys` prefers the
`NodeIndex` entry (which `TypeBuilder` already reconciled against the table's metadata, SDL winning
on `typeId` outright and on `keyColumns` order), falls back to the table's own
`KjerneJooqGenerator` metadata read through `JooqCatalog.nodeIdMetadata`, and only then to `@node`
plus the catalog primary key. A two-arm view would answer differently from the generator for every
type in the middle arm's population, which is the metadata-carrying table with no matching
`NodeType`. `graphitron_node`'s comment already anticipates this: "the SDL-versus-jOOQ-metadata
precedence rules are detections". So the reduction is three arms with a `tier` column naming which
one answered, in `intent_resolved_field_claim`'s shape, and the middle arm needs a fact that does
not exist yet.

The views, in the stratum's naming (`intent_resolved_*` for a reduction, the suffix at the front):

* **`intent_resolved_node_key_column (graph_name, type_name, position, column_name, tier)`.** The
  three-arm reduction above. Worth naming on its own terms, and not only for this item: the LSP
  wants exactly this list for completion, and an editor reading the store is the second reader that
  turns a derivation into a relation.
* **`intent_argmapping_binding_leaf`.** The keying over `intent_input_occurrence_path` described
  above, unioned across the six `*_arg_mapping_pair` relations with a `site` literal per arm, plus
  the unconsumed-segment count.
* **`intent_argmapping_node_key_projection`.** The reduction: a binding whose leaf carries `@nodeId`
  and whose single trailing segment names one of that node type's resolved key columns.

Uniformity across `@routine`, `@service` and `@condition` is then structural: the six pair
relations are one shape, so the binding view is a `UNION` with a `site` literal and a projection is
resolved identically everywhere because it is resolved once. That is a stronger guarantee than the
previous draft's "one shared resolver called by three directive resolvers", which was three call
sites agreeing by discipline.

Case-insensitive column matching is a settled convention rather than a new rule, so the docs need
not introduce it: `intent_spelled_table` and `intent_column_match_claim` both compare under
`UPPER()`, and `JooqCatalog.resolveColumn` uses `equalsIgnoreCase`.

The typed product is `AuthoredClaimConflicts`' shape exactly: a class in `rewrite/derive` reading
the views through the `DSLContext` inside the capture transaction, returning records built from
query rows. Those records are the item's new Java types, and they are the only ones it introduces.

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

**The walk's own contribution is one widening, and it is not free.** An earlier draft called it
"two deletions"; measured against the code it is one deletion with a blast radius and one
non-event:

* `ArgBindingMap.of` must stop rejecting a trailing segment after an `ID`-typed leaf ("walks
  through scalar 'ID' at segment ...; only input-object types may be traversed"), which is what
  makes the spelling unwritable today. But `of` has **five** callers (`RoutineDirectiveResolver`,
  `ServiceDirectiveResolver`, `ConditionResolver` twice, `BuildContext` twice), and the widened
  `PathExpr` is *consumed*: `RoutineCallEmitter.nestedSlotRead` registers a descent helper that
  walks every tail segment over the raw argument map and casts at the leaf. An admitted but
  uninterpreted key-column segment therefore emits `get("organisasjonskode")` against a `String`,
  which is this item's own failure mode relocated. So the widening must be gated on the projection
  resolving, or every consumer of the widened shape must be taught in the same commit. "Admits the
  segment and carries it without interpreting it" is not a safe halfway house.
* `RoutineDirectiveResolver.leafTypeGate` needs **no** change. Trace the motivating path:
  `ServiceCatalog.resolvePathLeafType` returns `null` as soon as a segment descends through a
  non-input-object, so the gate hits its `if (leafType == null) return null; // unresolvable leaf:
  pass through` arm and rejects nothing. That is a better fact for this item than a deletion would
  be: the gate is *already silent* on this shape, and it is equally silent on a typo'd key column,
  which is precisely why the store-side detection is load-bearing rather than a nicety.

### Where the resolved projection is consumed

Two consumers, and the pipeline order (walk, then capture, then validate, then plan, then render)
decides what each can do.

* **Validation reads it as violations, and this is the shipped pattern.** `FactCapture` already
  runs `AuthoredClaimConflicts` over freshly captured rows inside the transaction and returns a
  typed `Detection` the caller folds into the error stream. The rejections this item needs are
  detection views in the `intent_authored_claim_conflict` mould, decoded into located
  `ValidationError`s by a sibling of that class. Rejection stays a typed value; what changes is
  that the rule lives in SQL and the Java decodes a closed verdict vocabulary.
* **Planning joins it onto the command, and the grain matters.** `RoutineRef.ArgBinding` and
  `RoutineChain` live in `rewrite/model`, the walk surface this item may not touch.
  `LauncherCommand` and `LaunchSource.RoutineChain` live in `command/`, the plan surface, which is
  where a complete command row is assembled. So the projection is read by `EmitPlan` into a
  plan-local relation keyed by the pair's natural key and joined into the command row that
  `LauncherCommands` already mints. Nothing in `rewrite/model` changes, the command reaching the
  renderer is complete by construction, and no new committed command relation is minted for
  something that renders no unit (which would owe the render fold a closure obligation it cannot
  discharge).

The distinction is worth stating plainly because "a projection map on the routine-call command"
reads either way, and that ambiguity is where the ruling gets broken by accident.

**One rejection arm this design needs and an earlier draft lacked.** The view resolves a projection
at every `site` its `UNION` covers, but the emitters land site by site. A projection that resolves
where no emitter is wired is a classified decision implying a generator branch that does not exist,
which is the silence this item was filed to close. So the detection stratum carries a `deferred`
arm keyed on the `site` column, naming the sites that emit, and it shrinks as sites land.

### The `sql_` family gains two populations

Two facts this item needs are unreachable outside the codegen classloader, so both close in
capture, where the fact-finding code is already standing on the objects that carry them. `sql_`
is the family for both, and `sql_column` is the model, down to the rationale: "A column carries two
types, not one: the SQL type the database declares and the Java type jOOQ binds it to. Both are
facts about the column and neither derives from the other by any rule the store could apply."

**A table-returning routine is exposed by jOOQ as a table**, and that decides where its facts hang.
It arrives in the catalog as a `Table` whose table type is `FUNCTION`, which is exactly how
`JooqCatalog.resolveTableValuedFunction` finds it: `findTable(routineName)` first, then the
`getOptions().type().isFunction()` check. So the walk that writes `sql_table` has already visited
every routine `@routine` can name. `JooqCatalog.findNonTableValuedRoutine`'s `routines/`
sub-package probe is *not* part of this picture: it is the fallback for a routine that is **not**
table-valued, which `@routine` rejects outright.

The additions, all inside `CatalogFactCapture.captureCatalog`'s existing loop over
`jooq.allTableEntries()`:

* **`sql_table.table_type`**, one column, read as `table.getOptions().type()` (the accessor every
  site in the tree uses). jOOQ distinguishes `TABLE`, `VIEW`, `MATERIALIZED_VIEW`, `FUNCTION` and
  the rest; `sql_table` records none of it, so the store cannot currently tell a table-valued
  function from a table. Cheapest true fact in the item, and what makes "which of these rows is a
  routine" a query.
* **`sql_routine_parameter (source_name, table_schema, table_name, position, jooq_name,
  binding_type, ...)`**, hanging off the function-typed `sql_table` row by foreign key exactly as
  `sql_column` hangs off a table. A function's parameters are to it what its columns are to a
  table, one walk reads both, and they share a refresh cadence, so no new anchor and no new source
  vocabulary. Two things the relation's comment must own rather than leave implied: its population
  is table-valued functions only (a non-table-valued routine has no `sql_table` row at all, so its
  absence here is by construction, not by omission), and the rows describe **one method**, the
  table-form convenience overload, since `Routines` also generates a `Configuration`-first form and
  a `Field<?>` form for the same routine.
* **The call surface.** Because the parameters are a fact about a method, the method has to be
  named: the generated `Routines` class FQN and the method name. `sql_schema.keys_class_fqn` is the
  shipped precedent for a per-schema generated artifact recorded as a fact, and the emitter needs
  both values anyway. Without them the relation cannot answer "which overload", and the stated
  downstream payoff (routine hover and completion off the store rather than a live classloader)
  does not arrive.
* **The node metadata population**, which is the gap under the key-column resolution's middle tier:
  the `KjerneJooqGenerator` `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` statics that
  `JooqCatalog.nodeIdMetadata` reads reflectively off the table class. A `type_id` column plus an
  ordered `sql_node_key_column` child of `sql_table` is the shape, and the reader already exists,
  cached per table per build, with a sibling that surfaces malformed-metadata reasons. This is the
  same move as the parameters and for the same reason: `graphitron_node`'s own comment already
  says "the SDL-versus-jOOQ-metadata precedence rules are detections", so the DDL is waiting for a
  population it does not have.

**How capture reads them, and one thing it must not do.** Do not route capture through
`resolveTableValuedFunction`: it is name-keyed, re-runs `findTable`, and collapses a function name
two schemas both declare into `NotInCatalog`. Capture is already holding the resolved `Table<?>`
and its schema and needs no lookup. The in-tree precedent is explicit, `candidateKeys(Table<?>)`
existing as the "table-scoped overload without a (potentially ambiguous) SQL-name lookup" beside
`columnFactsOf(Table<?>)`. So the reader is a new `Table<?>`-scoped method on `JooqCatalog`
returning a value record beside `ColumnFacts`.

It must also return values, not emit vocabulary. `RoutineResolution.Resolved` carries javapoet
(`RoutineParam(String name, TypeName type)`, `ClassName routinesClass`), and reading capture
through it would land a `TypeName.toString()` in `binding_type`. `ColumnFacts`' own javadoc already
rules on this: the javapoet form is "a code-emission representation with no meaning in a relation".

**One dependency the join key inherits, which is silent today.** `jooq_name` is the join key,
because `graphitron_routine_arg_mapping_pair.param_name` holds what the author wrote
(`pInventoryId`) and `RoutineDirectiveResolver` matches it with `p.name().equals(claimed)` against
the reflected method parameter. A reflected parameter name is `arg0` unless the consumer compiled
their jOOQ output with `-parameters`. The generator already depends on this in that matching, and
this repo compiles for it in both `graphitron-sakila-db` and its own test tree. Capturing the name
makes the dependency visible instead of creating it, and the column's comment is where it belongs.
If the flag is absent the row stays faithful (it records what the class says); what degrades is the
join, which is the honest place for that to surface.

The parameters' SQL-side vocabulary (the database's own parameter names and types) sits on
`TableImpl.parameters`, which is `protected`, so reaching it means reading a non-public member.
Decide at pickup, and omit the columns rather than ship ones that are always null.

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

**Base relations, from the catalog walk:** `sql_routine_parameter`, the call-surface columns, the
node-metadata population and the `sql_table.table_type` column, per "The `sql_` family gains two
populations". These carry the usual obligations: dense positions on the ordered children, total
comment coverage, a `FOREIGN KEY` to `sql_table`, transcription-twin agreement for the decode, and
registration in `FactCaptureAgreementTest` so a new relation cannot arrive unchecked.

**Derived relations:** the three resolution views plus the detection views, all `intent_`, all
registered under the derived arm.

**No new column on the SDL side.** `graphitron_routine_arg_mapping_pair.argument_path` holds the
right side as written, it is still a dotted path, and capture still records it verbatim. What
changes is that its comment enumerates what a segment can name ("a GraphQL argument name or dotted
input path") and now enumerates it incompletely; restate it at the rule's grain on the `@routine`
pair relation and on its five siblings.

The `sql_` half is what makes this more than a view stack, and it is worth doing on its own terms:
both populations are unreachable outside the codegen classloader, so a run that does not capture
them cannot answer a routine or node-metadata question afterwards, and every consumer that wants
one is forced back through a live reflective walk. That is the argument `sql_column.binding_type`'s
comment already makes for columns ("read off the live `Field` during the catalog walk and
unrecoverable afterwards").

## Implementation

* **`graphitron-model.sql`, base relations**: `sql_table.table_type`; `sql_routine_parameter` in
  `sql_column`'s mould with the call-surface columns; the node-metadata `type_id` plus an ordered
  `sql_node_key_column` child of `sql_table`.
* **`JooqCatalog`**: a `Table<?>`-scoped reader beside `columnFactsOf`, returning a value record
  beside `ColumnFacts` (no javapoet). The node-metadata reader already exists
  (`nodeIdMetadata`, with `nodeIdMetadataDiagnostic` beside it) and is already cached per table.
* **`CatalogFactCapture`**: `table_type` in the existing `sql_table` loop, plus
  `captureRoutineParameters` and `captureNodeMetadata` beside `captureColumns` /
  `captureConstraints` / `captureIndexes`, the first guarded on the function arm.
* **Views**, house style per `intent_bound_table` (declared column list, full comment coverage,
  closed vocabularies as `CHECK` or as stated column comments): `intent_resolved_node_key_column`
  (three tiers, `tier` column), `intent_argmapping_binding_leaf` (the keying over
  `intent_input_occurrence_path`, `site` literal per union arm, unconsumed-segment count), and
  `intent_argmapping_node_key_projection`, plus the detection views for the rejections including
  the site-keyed `deferred` arm.
* **Typed products** in `rewrite/derive`, in `AuthoredClaimConflicts`' shape: records built from
  query rows, decoding a closed verdict vocabulary into `Rejection` arms. The only new Java types
  the item introduces.
* **`FactCapture`**: run the detection over the freshly captured rows inside the existing
  transaction and return it in the typed product the caller already folds into the error stream.
* **`GraphitronSchemaValidator`**: fuse the new violations the way the claim conflicts are fused.
* **`ArgBindingMap.of`**: widen the traversal rejection to admit one trailing segment after an
  `ID`-typed leaf, *with* the five-caller audit and the `RoutineCallEmitter.nestedSlotRead`
  consequence handled in the same commit (see "What this item does not add").
* **`EmitPlan` / `LauncherCommands`**: read the projection view into a plan-local relation keyed by
  the pair's natural key and join it into the `LaunchSource.RoutineChain` command row. Nothing in
  `rewrite/model` changes.
* **`RoutineCallEmitter`**: emit the decode-and-read from the command row; `emitCall` yields
  pre-statements alongside its expression and the four call sites add them.
* **`ConditionGlueRenderer`** and the `@service` pair, when their sites land: the same read, with
  the decode helper body hosted on the conditions class for the `@condition` site.
* **Comments**: restate `argument_path` on all six `*_arg_mapping_pair` relations.

## Tests

* **Capture tests for the new populations**, in the shape the `sql_` family already uses: the
  transcription twin proving the rows agree with what the catalog walk read, dense positions,
  `FactSchemaGateTest` for comment coverage. The sakila catalog carries `rent_film` and
  `create_secure_note` as table-valued functions, so the fixtures exist; pin the parameter rows
  each produces, that an ordinary table produces none, and at least the `TABLE` and `FUNCTION` arms
  of `table_type`. The node-metadata population needs a metadata-carrying table and one without.
* **A test that pins the `-parameters` dependency**, since `jooq_name` is the join key and is
  `arg0` without it. This repo already compiles one test package deliberately without
  `-parameters`, so the precedent for covering both sides exists.
* **View-level tests** in the `ColumnMatchClaimTest` / `DemandShadowTest` mould, one per view.
  `intent_resolved_node_key_column` needs all three tiers populated, not two, plus a composite-key
  type (`bar` in the `nodeidfixture` catalog is the one `NodeIdPipelineTest` already uses).
* **The binding-leaf view wants its caveats pinned, not just its happy path**: a bare-scalar
  argument head, a path whose leaf is an input object rather than a scalar, and the two-trailing-
  segment case that must not resolve as a projection.
* **Registration**: `FactCaptureAgreementTest` for every new relation and view, base and derived.
* **Corpus population per arm.** A view arm no fixture reaches is a vacuous pin. Each rejection
  arm, each key-column tier and each admitted `site` needs a coordinate that reaches it.
* **Cross-site parity**: one test over `@routine`, `@service` and `@condition`. The `UNION` arms
  are six hand-written `SELECT`s and a typo in one is exactly the drift it catches.
* **Pipeline tier**: the `rent_film` fixture binding `pInventoryId` from
  `ID! @nodeId(typeName: "Inventory")` through the projected key column, asserting the emitted call
  materialises the record once and reads the column off it, plus the rejection cases.
* **Validate-time tests** that the build fails, not only that a `Rejection` is produced.
* **The widening's blast radius**: a test at each `ArgBindingMap.of` caller that a trailing segment
  which resolves to no projection cannot reach `nestedSlotRead` and emit a raw map read.
* **`@condition` emission** needs a compilation-tier assertion about *which class* hosts the decode
  helper body, since that is the half the pipeline tier cannot see.
* **Execution tier** (`graphitron-sakila-example`): one round trip proving the decoded key reaches
  the database as a key rather than a base64 string, alongside the existing
  `NodeIdValueAgreementExecutionTest`.

## Risks

* **The widening at `ArgBindingMap.of` is the sharp edge**, not the views. Five callers, and a
  consumer (`RoutineCallEmitter.nestedSlotRead`) that will happily emit a raw map read for a
  segment nobody interpreted. Gate it or teach every consumer in the same commit.
* **Whether the parameters' SQL-side vocabulary is worth reaching.** They are `Field<?>` values on
  the protected `TableImpl.parameters`; the Java side, which is what the join and the gate need,
  comes off the generated `Routines` method with no such problem. Settle before the DDL is written
  and omit the columns rather than ship ones that are always null.
* **The `-parameters` dependency** the join key inherits: real, pre-existing, and now explicit.

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
  from the node type's key columns. Under this design it is answerable from a *relation*:
  `intent_resolved_node_key_column` is exactly the completion list, all three tiers of it. That the
  editor is a second reader of the same view is the argument for naming it rather than leaving it a
  CTE inside whoever asked first, and it is why the view earns its place independently of this
  item's own use of it. So key-column completion is reachable *ahead* of the general case rather
  than after it. It is still its own item and must not ride this one, but R626's "offer nothing"
  note should be narrowed to the input-object arm when either item lands, so it does not read as a
  blanket bar on a case that is no longer blocked.
* `roadmap/nested-argmapping-syntax.md` (R249) extends the right-hand side with a nested object
  form. It varies the same grammar from the other end and composes with the openability rule
  rather than negotiating against it, so the two no longer need a joint decision on the
  separator. They still share an owner, so coordinate on edits to
  `ArgBindingMap.parseArgMapping` plus `ArgBindingMap.of`.

## Scope: three items, in dependency order

This spans base-relation capture, a view stack, detection views fused into validation, a grammar
widening with a five-caller audit, a planning join and three emit sites. That is too much for one
item, and the split that helps is not the one an earlier draft proposed (bare-form rejection versus
projection), which separates two consumers of the same view stack and leaves the capture extension
welded to both.

1. **Capture.** `sql_table.table_type`, `sql_routine_parameter` with its call surface, and the node
   metadata population. Registered, twinned, comment-covered, with no reader. This is R666's
   shipped shape exactly (land the facts, change no production read) and it has value independent
   of this item: two populations that are otherwise unreachable after the classloader closes.
2. **Views plus the bare-form rejection.** `intent_resolved_node_key_column`, the binding-leaf
   keying over `intent_input_occurrence_path`, the projection reduction, the detection views, the
   typed product, the validator fusion. No grammar change, no planning join, no emit. This closes
   the silent-base64 hole, which is the sharper half of the problem and the half worth landing even
   if the rest slipped.
3. **Grammar plus emit.** The `ArgBindingMap.of` widening with its five-caller audit, the planning
   join, `RoutineCallEmitter`'s pre-statement signature change, and the `@condition` helper
   hosting. This is where the risk actually is, and the only piece that needs the emitter-signature
   question answered.

(1) and (2) are both shadow-shaped and cheap to review. Splitting this way also means the
projection never resolves at a site whose emitter is unwired without the `deferred` arm saying so,
because (2) ships that arm covering every site and (3) shrinks it.

The interim state after (2) is a rejection with no fix yet available, which is a worse author
experience than either end state. That is the real cost of the split and it is why the verdict
stays `structural` rather than `deferred`: "bind the decoded key by naming a key column" is a
statement about what the author must write, and it becomes constructive when (3) lands rather than
retroactively true.

## Open questions

* **What the plan-local projection relation looks like** in `EmitPlan`, and whether
  `LauncherCommands` is the only command that needs the join or `ProjectionCommands` does too.
  Bounded, but it decides how much of (3) is plumbing.
* Whether `PathFragments.emitTableExpression` takes the pre-statement change (which propagates to
  its callers, since it returns a bare expression consumed inside alias-declaration loops) or keeps
  a per-read call as a documented site-local exception. A signature question one level up, not an
  implementation detail, and it belongs to slice (3).
* Whether a `@nodeId` input field that nothing consumes should warn. Today it is silently
  ignored wherever no consumer reads it, which is how the `TEXT` case above stays invisible;
  the bare-form rejection closes it at the `argMapping` sites only. A general "declared and
  unconsumed" warning is a larger question and belongs in its own item if anyone wants it.
