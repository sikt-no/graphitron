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
otherwise, reconciled into `NodeType.nodeKeyColumns` by `TypeBuilder` (SDL wins on order). The
authority is therefore the `@node` declaration on the type the `@nodeId` refers to, which is the
same place an author already looks to know what a node id encodes.

The spelling is the SQL column name, because that is what `@node(keyColumns:)` itself is a list
of. Matching is case-insensitive, the way every other SQL-name comparison in the classifier is.

`@nodeId` without `typeName:` is rejected at this position. `NodeIdLeafResolver.inferTypeName`
infers a bare `@nodeId`'s target from the *containing table*, and a routine parameter has no
containing table; there is nothing to infer from. That owner already carries two permanent
messages for this same underlying fact ("cannot infer a node type here", both ending "Specify
`typeName:` explicitly"). The routine-site rejection belongs in that owner as a third message
rather than freshly composed at the detection site, so authors meet one vocabulary for one
condition.

### It is not a `NodeIdLeafResolver` reuse

`NodeIdLeafResolver.resolve` answers a table-anchored question: given a containing table, is this
`@nodeId` the table's own identity (same-table) or a foreign key into another table (FK-target),
and which columns does the predicate bind against. A routine IN parameter has no containing table
and no predicate. It wants only the wire half, "decode into typed key values", with no projection
against a table at all.

The seam is narrower than either that resolver or the `resolveDecodeHelperForType` /
`resolveTargetKeys` pair the Backlog draft named: `NodeIndex.forName(typeName)` returns a
`NodeType` that already carries both `nodeKeyColumns` and `decodeMethod`, fully reconciled by
`TypeBuilder`. Everything the segment needs is one index lookup. Routing through the
table-anchored resolver would force a fake containing table into the call and re-derive an FK
verdict nobody asked for.

### The path stays SDL-only; the decode rides the extraction slot

The projection splits cleanly in two, and the split is what keeps both halves honest:

* **`PathExpr` keeps meaning "walk SDL input fields".** The resolved path stops at the `@nodeId`
  field. `RoutineCallEmitter.nestedSlotRead` and `ArgPathHelperRegistry` are untouched: the
  descent helper still walks a `Map` chain to a leaf and casts, and for a node binding that leaf
  is the `String` id.
* **The decode-and-project becomes a `CallSiteExtraction` arm** on `ParamSource.Arg.extraction()`.
  That slot is exactly "how to extract one argument value at the call site", it already carries
  `NodeIdDecodeKeys.ThrowOnMismatch(decodeMethod)`, and the only fact missing for a routine
  parameter is *which* key column to project.

A terminal `PathExpr` arm, the Backlog draft's guess, is the wrong shape and the reason is worth
recording: `ParamSource.Arg(CallSiteExtraction extraction, PathExpr path)` already splits these
as two axes, *where the value lives* and *what transform applies once extracted*, and a terminal
arm fuses them. The concrete cost is that `ServiceCatalog.resolvePathLeafType` walks segments
against input-object fields and would return `null` for a projection segment, falling into its
documented "pass through conservatively rather than over-reject" arm. That is a silent hole in
the wire-coercion gate, which is the same defect this item exists to close, one level up.

**The new arm composes with `NodeIdDecodeKeys` rather than joining it.**
`NodeIdKeyProjection(NodeIdDecodeKeys decode, ColumnRef column, int keyIndex)` is a top-level
`CallSiteExtraction` arm that *carries* the decode. Making it a sibling of `ThrowOnMismatch`
under `NodeIdDecodeKeys` would be wrong twice: that interface's documented axis is the failure
mode, and a projection is not a failure mode (a projected binding still needs one); and
`ConditionGlueRenderer.decodeCall` reads the mode as
`nidk instanceof ThrowOnMismatch ? THROW : SKIP`, so a new sibling arm would silently route to
SKIP semantics. If a later item does widen `NodeIdDecodeKeys`, that ternary must become an
exhaustive switch first.

The extraction slot is the item's other payoff. `RoutineDirectiveResolver` hardcodes
`new CallSiteExtraction.Direct()` on every argument-sourced binding and `RoutineCallEmitter`
never reads `arg.extraction()` at all: a model fact with no consumer, which
`roadmap/routine-coercing-arg-extractions.md` (R625) is chartered to fix. Riding the slot makes
this item open that switch for the first time, which makes the R625 relationship **directional**
rather than merely adjacent: R668 introduces the two-arm switch (`Direct`, the projection arm)
and R625 fills `EnumValueOf` and `JooqConvert` into it.

### `argMapping` behaves identically at every site

The form is admitted, resolved and emitted at `@routine`, `@service` and `@condition` alike. It
is one binding language; a projection that is useful at one of its sites is useful at all of
them, and shipping it at `@routine` only would bake in exactly the asymmetry the shared
right-hand side exists to prevent. There is no deferral arm in this design.

What genuinely varies per directive is narrower than the Backlog draft claimed, and it is the
same axis that already varied before this item: the **binding target**. `@routine` binds a
catalog IN parameter, `@service` a reflected Java parameter, `@condition` a condition-method
parameter. Every one of those is a Java type name, so the type *predicate* is shared and only its
target argument differs (see "Type gate"). That is one enforcer with a parameter, not three
dispatch sets.

Resolution is shared too, because there is nothing directive-specific in it: the candidate
segment resolves against `NodeIndex.forName(typeName)`, whose `NodeType` carries both
`nodeKeyColumns` and `decodeMethod`. One shared resolver owns that lookup and its rejection
messages, called by all three directive resolvers, the way `ServiceCatalog.argExtraction` is
already a shared gate called by both `@service` and `@routine`.

What must *not* absorb the resolution is `ArgBindingMap.of`. It is a `static`, pure function over
`(slotTypes, overrides)`, and two of its `BuildContext` call sites pass an *empty* slot map (the
path-step `@condition`), where there is nothing to project from at all. Threading schema state
through it to serve callers that have none is the wrong altitude. So the split is by *kind of
question*, not by directive:

* **Grammar, one owner.** `of` admits the trailing segment when the preceding leaf is an `ID`
  carrying `@nodeId`, and records it as an unresolved projection candidate. Its traversal
  rejection is restated at the rule's grain ("this thing has nothing to open", naming what the
  thing is), so it covers both openable kinds and stays permanent rather than becoming
  conditional on a node lookup. `of` decides *openability*, which is a grammar fact it can see
  from the directive's presence alone; it does not decide *which* key column, which is
  resolution.
* **Admission, one predicate.** `ArgMappingSigil` is the precedent: it already owns the literal
  set, the parse fork, the per-`Site` admission predicate and the canonical messages, precisely
  so parse, diagnostics and completions cannot drift. A sibling `Site.admitsNodeKeyProjection()`
  gives the new form the same single-owner admission fact, true at `SERVICE`, `CONDITION` and
  `ROUTINE`.
* **Resolution, one owner.** The shared resolver above: candidate segment plus leaf container in,
  a resolved projection or a located rejection out. All three directive resolvers call it.
* **Type gate, one predicate, three targets.** See "Type gate".
* **Emission, three sites.** `RoutineCallEmitter`, the `@service` pair
  (`ArgCallEmitter` / `ServiceMethodCallEmitter`), and `ConditionGlueRenderer`.

The remaining `ArgMappingSigil.Site` values are excluded because their right-hand side is not an
argument path at all, which is a difference in grammar rather than an asymmetry in capability:
`ENUM` maps enum constants, `RECORD` and `EXTERNAL_FIELD` bind their own target vocabularies
(and `@externalField` is folding into `@service` under its own item), and `REFERENCE_STEP` is the
path-step `@condition` whose slot map is empty, so it has nothing to project from. `columnMapping`
never routes through this owner and admits no sigil or projection: a column has nothing to open.

Unlike `$session`, whose non-admission message is permanently true, these exclusions are
statements about a different grammar rather than about a capability gap, so they are structural
rejections and not `Rejection.deferred`. Nothing in this design defers.

### One walk, not two: `resolvePathLeaf` must keep the container

`ServiceCatalog.resolvePathLeafType` walks `iot.getField(name)` and immediately projects to
`field.getType()`, discarding the `GraphQLInputObjectField`. But `@nodeId` sits on the *field*,
not on the type. Both the bare-form rejection and the projection resolution need the field, so
as drafted the routine resolver would re-walk the same segment chain to find it: two walks over
one path that must agree on list unwrapping, non-null stripping and the null-on-miss convention,
with nothing binding them.

Widen the producer once instead. `resolvePathLeaf(path, slotTypes)` returns a small record
carrying `(GraphQLDirectiveContainer container, GraphQLInputType type)`, and
`resolvePathLeafType` stays as a thin projection over it for existing callers. The head
segment's container is a `GraphQLArgument` and a deeper one is a `GraphQLInputObjectField`; both
are `GraphQLDirectiveContainer`, which is also what `NodeIdLeafResolver.resolve` takes, so the
shape composes with the existing node-side vocabulary.

### The bare form becomes a rejection

Once the segment exists, binding a `@nodeId` leaf with *no* key-column segment is rejected,
naming the node type and listing its key columns. This is the change that closes the silent
`TEXT`-parameter hole, and it is worth landing even if everything else here slipped: today that
spelling writes a base64 string into a database column and nothing in the build says a word.

The counter-proposal, implicit decode for single-key node types, is rejected. It would make the
same spelling mean two different things depending on a fact (the node's key arity) that is not
visible at the `argMapping` site, and it would leave composite-key node types needing the
explicit segment anyway. One spelling, always explicit.

This is a breaking change for any schema relying on the silent pass-through. It is a rejection
of a spelling that produces wrong data, so it is a bug fix rather than a capability removal, and
the rejection message names the fix. Call it out in the changelog entry at Done.

Three properties of this rejection to settle here rather than let fall out of implementation:

* **Ordering.** It must run *ahead* of `argExtraction` in `leafTypeGate` (today: list-shape →
  non-scalar → `argExtraction`). Placed after, an author gets the directed message or the
  `Assignability[...]` message depending on which column type they happened to bind into, which
  is the same defect this item is closing.
* **Verdict class.** `Rejection.structural`, since the rejection and the syntax that fixes it land
  together and there is no future in which the raw base64 was intended. Only if someone splits the
  item and ships the rejection first does it want `Rejection.deferred` in the interim, naming what
  will exist.
* **Keying axis.** The rejection is *use-keyed*. One input type can be consumed by a `@routine`
  mutation (no containing table, projection required) and by a table-bound `@service` mutation
  (inference works, bare form legal). An author who reads "add `typeName:`" and edits the shared
  input type is editing a definition-keyed fact to satisfy a use-site constraint, so the message
  must name the consuming field that is asking.

### Type gate: the `columnMapping` check, not a bypass of `argExtraction`

The Backlog draft called this "a different input to `argExtraction` (or a bypass of it)".
"Bypass" is the wrong framing: it is a different question that already has an answer twelve
lines away in the same file. `argExtraction` asks a *wire* question, "is graphql-java's coercion
output for this SDL leaf assignable to this Java type". A projected node key never reaches the
parameter in SDL-scalar shape; its Java type is the column's.

`RoutineDirectiveResolver` already compares `column.columnClass()` against
`param.type().toString()` for `columnMapping` bindings, with the rationale that a mismatch would
be a javac error in the generated source. That is the same fact: a resolved catalog column feeding
a Java-typed binding target, legitimately skipping the wire gate for the same reason.

Factor it into one predicate taking the column and the target's Java type name. `columnMapping`
passes the routine IN parameter, a projected `@routine` binding the same, `@service` the reflected
Java parameter type, `@condition` the condition-method parameter type. Four callers, one enforcer,
which is what keeps "identical at every site" true by construction rather than by three
implementations agreeing. A mismatch names both types, the column and the node type, so an author
projecting the wrong column of a composite key is told exactly that.

### Emission

`CompositeDecodeHelperRegistry` already emits precisely the required helper.
`register(HelperRef.Decode, Mode.THROW, list)` produces a private static method returning the
single key column's type at arity 1 and a typed `RowN` above, raising the generated
`GraphitronClientException` on a null decode with the two-branch malformed-versus-wrong-type
message. So:

* arity 1: `decode<Type>(<raw read>)`
* arity N: `decode<Type>(<raw read>).value<i>()`, `i` being the projected column's 1-based
  position in `HelperRef.Decode.outputColumnShape`

The `list` axis is `false` here: a routine IN parameter takes one value, which the existing
`leafTypeGate` cardinality check already enforces.

**A composite key decodes once per projected column, and that is accepted.**
`CompositeDecodeHelperRegistry` deduplicates *helpers*, keyed on
`(encoderClass, methodName, mode, list)`, not *calls*. Two parameters projecting two columns of
one composite key therefore register one helper and emit two `decode<Type>OrThrow(raw)`
invocations. Decoding once instead would need a hoisted typed local, and `emitCall` returns a
single `CodeBlock` expression with no statement context to hoist into, so it would cost a contract
change across the four call sites the registry threading already touches.

Take the double decode. A decode is a base64 parse and an arity check, not a query, and the
duplicated call keeps `emitCall`'s signature intact. The visible cost is two identical throw sites
in the generated source for a composite-key binding, which is acceptable; if it ever stops being
acceptable, the lift is a self-contained follow-up and
`CompositeDecodeHelperRegistry.decodedType(decode, list)` already exists for it, "so a caller
declaring a typed local for the helper's result derives the type from the same shape the helper
body is built from". Only `ConditionGlueRenderer` calls it today.

**The three emitters are at three different starting points, and the middle one is the surprise.**
Worth measuring before sizing, because the intuition "`@service` is the mature path" is wrong here:

* **`@condition` is already there.** `ConditionGlueRenderer` threads
  `CompositeDecodeHelperRegistry` and already emits `@nodeId` decodes through `decodeCall`, and
  already calls `decodedType` to declare a typed local. The projection is a small addition to a
  site that decodes today.
* **`@service` explicitly refuses, and its refusal is the claim being overturned.**
  `ArgCallEmitter` has two invariant-throws on `NodeIdDecodeKeys`, one in the top-level extraction
  switch and one in `buildNestedInputFieldExtraction`, both stating that "NodeId decodes are
  condition-binding concepts rendered inside the condition glue". Under a uniform `argMapping`
  that assertion is false and both arms become real implementations. Separately,
  `ServiceMethodCallEmitter.scalarLeaf` has a `NodeIdDecodeKeys` arm that emits a **plain cast**
  (`($T) rawValue`) alongside a `default ->` fallback doing the same. That arm is documented as
  unreachable for well-formed scalar leaves; this item makes it reachable, so a bad cast that is
  latent today would become live. Implement it rather than leaning on the fallback, and consider
  dropping the `default ->` so the compiler enumerates future arms instead of swallowing them.
* **`@routine` has to build the switch,** since `argExpression` never reads `extraction()`.

So the work is not three times the `@routine` slice, but it is not one site either: one new switch,
one small addition, and three arms whose current contents are an assertion that this item
invalidates. The `@service` throw messages are load-bearing documentation of today's boundary, so
they are also the precise work list.

Four `RoutineCallEmitter.emitCall` sites need a decode registry in scope: `RootLauncherRenderer`,
`PathFragments.emitTableExpression`, and two in `TypeFetcherGenerator`. Three of the four already
thread `ctx.argPathHelpers()`, a generation-context-level `ArgPathHelperRegistry` drained onto the
class builder in `TypeFetcherGenerator`; adding a sibling `ctx.compositeDecodeHelpers()` accessor
drained the same way is the symmetric move and keeps the threading identical at every site.

Both call surfaces carry it: the uncorrelated value overload takes the projected value directly,
and the correlated `Field` overload wraps it in the existing `DSL.val(...)`.

### Fact capture

An `argMapping` grammar change is a capture-relation change, and the Backlog draft was silent on
the store. `graphitron_routine_arg_mapping_pair.argument_path` is commented "the right side as
written: a GraphQL argument name or dotted input path", and the same comment sits on the sibling
`*_arg_mapping_pair` relations.

Under the rule above the column's *contents* do not change shape: it holds the right side as
written, it is still a dotted path, and capture still records it verbatim. What changes is that
the comment enumerates what a segment can name and now enumerates it incompletely. That is a
comment the coverage gate cannot catch, because the gate checks presence rather than truth, so
the item fixes it explicitly: restate the comment at the rule's grain ("a GraphQL argument name,
or a dotted path whose segments open input fields or a node id's key columns") on the `@routine`
pair relation and on whichever siblings the admitted-site set covers.

No new column or child relation is proposed. The verbatim record stays faithful, and a resolved
projection is a derived fact whose home is the classifier, not the capture twin. The raw
`arg_mapping` column is unaffected either way.

## Implementation

* `CallSiteExtraction`: new top-level arm
  `NodeIdKeyProjection(NodeIdDecodeKeys decode, ColumnRef column, int keyIndex)` in the sealed
  permits list, composing with the failure-mode axis rather than joining it. Every exhaustive
  switch over `CallSiteExtraction` becomes a compile error until it names the arm, which is the
  point; arms that cannot reach it document that rather than falling through.
* `ArgMappingSigil.Site`: `admitsNodeKeyProjection()`, the single admission predicate parse,
  diagnostics and completions all read.
* `ArgBindingMap`: `of` admits the trailing segment when the preceding leaf is an `ID` carrying
  `@nodeId`, and records it as an *unresolved* candidate (the segment name, unresolved against
  any node type). The value type `Map<String, PathExpr> byJavaName` becomes
  `Map<String, BoundPath>` where `BoundPath` pairs the `PathExpr` with an optional candidate
  segment. `of` stays free of `NodeIndex` and `BuildContext`; the two rejections it keeps are
  today's non-`@nodeId`-scalar traversal message (unchanged) and a non-admitted site.
* `ServiceCatalog`: `resolvePathLeaf` returning `(container, type)`, with `resolvePathLeafType`
  kept as a thin projection for existing callers.
* `RoutineDirectiveResolver`: resolve the candidate against `NodeIndex.forName` (which yields the
  `NodeType` carrying both `nodeKeyColumns` and `decodeMethod`), reject a segment that is not one
  of its key columns with a candidate list, reject a bare `@nodeId` leaf, reject a `@nodeId`
  without `typeName:` through `NodeIdLeafResolver`'s existing message owner, run the shared
  resolved-column type gate, and mint the projection arm instead of `Direct`.
* `ServiceDirectiveResolver` / `ConditionResolver` (both sites): resolve the candidate through the
  same shared resolver and run the same type-gate predicate against their own target type (the
  reflected Java parameter, the condition-method parameter).
* `ArgCallEmitter`: replace the two `NodeIdDecodeKeys` invariant-throws with real emission, and
  drop the stale "condition-binding concept" rationale from both messages.
* `ServiceMethodCallEmitter.scalarLeaf`: implement the `NodeIdDecodeKeys` arm instead of emitting a
  plain cast, and consider removing the `default ->` fallback so a future arm is a compile error.
* `ConditionGlueRenderer`: the projection arm alongside the existing `decodeCall`.
* `RoutineCallEmitter`: `argExpression` switches on `b.source()`'s extraction for the first time.
  `emitCall` gains the decode-registry parameter; the four call sites thread it.
* `GenerationContext`: `compositeDecodeHelpers()` accessor plus the drain in
  `TypeFetcherGenerator`, mirroring `argPathHelpers()`.

## Tests

* `ArgBindingMapTest` (unit): admission of the trailing segment and the unchanged traversal
  rejection on a non-`@nodeId` scalar. `of` stays pure, so this stays a pure unit test; node
  resolution is not exercised here because `of` no longer does it.
* `RoutineMutationWritePipelineTest` (pipeline): a sakila-shaped variant of the existing nested
  `rent_film` fixture binding `pInventoryId` from `ID! @nodeId(typeName: "Inventory")` through
  the projected key column. Assert the classified `ArgBinding` carries
  `NodeIdKeyProjection`, and assert the emitted call site registers and invokes the decode helper
  exactly once. Plus the rejection cases: the bare `@nodeId` leaf, a segment naming a non-key
  column, a `@nodeId` without `typeName:`, and a projected column whose Java type does not match
  the parameter's.
* Each rejection also wants a **validate-time** test that the build fails, not only that
  classification yields a `Rejection`. "Validator mirrors classifier invariants" is the rule, and
  a rejection that classifies but does not fail the build is the failure mode it guards against.
* **Cross-directive parity is itself the contract, so pin it as one.** The same projected binding
  at `@routine`, `@service` and `@condition` must resolve to the same projection and produce the
  same rejections; a parameterised test over the three sites states that directly and fails when
  one site drifts, which prose in three separate test classes cannot. The `@service` arms need
  their own emission coverage besides, since their current contents are invariant-throws and a
  test asserting the throw is gone is not the same as a test asserting the decode is right.
* Composite-key coverage belongs on the `nodeidfixture` catalog (`bar` is the composite-key node
  type `NodeIdPipelineTest` already uses), pinning the `.value<i>()` index and the
  decode-once-project-twice sharing. A transposed projection is exactly the failure a
  single-column fixture cannot catch.
* Execution tier (`graphitron-sakila-example`): one round trip proving the decoded key reaches
  the database, alongside the existing `NodeIdValueAgreementExecutionTest`. A pipeline assertion
  on emitted source cannot prove the projection is not transposed; only a real row can.

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
  non-`Direct` extraction arms (`EnumValueOf`, `JooqConvert`). Riding the extraction slot makes
  this **directional**: R668 opens the `RoutineCallEmitter.argExpression` switch on
  `extraction()` with two arms and R625 fills the coercing ones into it. The Backlog draft said
  the ordering was free either way; that was true only under the terminal-`PathExpr` shape and
  stopped being true when the design moved onto the extraction slot. If R625 is already in
  flight, coordinate on who writes the switch rather than both writing it.
* `roadmap/lsp-argmapping-routine-coordinate.md` (R626) gives `@routine(argMapping:)` completions
  and diagnostics at all. R626 explicitly leaves dot-path expansion unmodelled ("offer nothing
  rather than a misleading flat list") because the LSP snapshot carries no nested-input-field
  projection. Under the openability rule that limitation splits by kind rather than being uniform:
  the input-object arm still waits on the snapshot projection, while the node-id arm is answerable
  from `NodeType.nodeKeyColumns`, which the snapshot can carry cheaply. So key-column completion
  is reachable *ahead* of the general case rather than after it. It is still its own item and must
  not ride this one, but R626's "offer nothing" note should be narrowed to the input-object arm
  when either item lands, so it does not read as a blanket bar on a case that is no longer blocked.
* `roadmap/nested-argmapping-syntax.md` (R249) extends the right-hand side with a nested object
  form. It varies the same grammar from the other end and composes with the openability rule
  rather than negotiating against it, so the two no longer need a joint decision on the
  separator. They still share an owner, so coordinate on edits to
  `ArgBindingMap.parseArgMapping` plus `ArgBindingMap.of`.

## Open questions

* Whether `emitCall` lifts to `(statements, expression)` so a composite key decodes once rather
  than once per projected column. Recommendation is to accept the double decode; the counter is
  the duplicated throw site in the generated source.
* How the projection at a `@service` argument relates to `CallSiteExtraction.NodeIdDecodeRecord`,
  which already decodes a `@nodeId` input-bean member into a jOOQ record. They are different
  targets (a scalar parameter versus a record member) and should coexist, but an author now has
  two ways to get a decoded key into a service call and the docs must say which is for what.
* Whether a `@nodeId` input field that nothing consumes should warn. Today it is silently
  ignored wherever no consumer reads it, which is how the `TEXT` case above stays invisible;
  the bare-form rejection closes it at the `@routine` site only. A general "declared and
  unconsumed" warning is a larger question and belongs in its own item if anyone wants it.
