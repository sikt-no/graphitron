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
of. Case-insensitive matching is the intent; check at pickup whether that is inherited from a
settled convention in the neighbouring `ColumnRef.sqlName` comparisons or is a new rule this item
introduces, and say which in the docs. The answer changes nothing about the design and everything
about whether the rule needs stating.

`@nodeId` without `typeName:` is rejected at this position. `NodeIdLeafResolver.inferTypeName`
infers a bare `@nodeId`'s target from the *containing table*, and a routine parameter has no
containing table; there is nothing to infer from. That owner already carries two permanent messages
for this same underlying fact ("cannot infer a node type here"), though they end differently ("Add
typeName: explicitly." and "Specify typeName: explicitly."). The projection's rejection belongs in
that owner as a third message rather than freshly composed at the detection site, so authors meet
one vocabulary for one condition; converge the existing two on one wording while adding it, since
the whole argument for putting it there is that the vocabulary is shared.

### It is not a `NodeIdLeafResolver` reuse

`NodeIdLeafResolver.resolve` answers a table-anchored question: given a containing table, is this
`@nodeId` the table's own identity (same-table) or a foreign key into another table (FK-target),
and which columns does the predicate bind against. A routine IN parameter has no containing table
and no predicate. It wants only the wire half, "decode into typed key values", with no projection
against a table at all.

The seam already exists under a name, and it is neither of the two the Backlog draft guessed at:
`BuildContext.resolveNodeIdRecordDecode(typeName)` takes a type name and produces exactly the
record decode this item projects from. It reads its key columns through `resolveTargetKeys`, the
same place `NodeIdLeafResolver` does, "so the `@node(keyColumns:)` fallback lives in exactly one
place".

Two precisions, because the loose version of this sentence misled an earlier draft of the item.
The call returns `BuildContext.NodeIdRecordDecode`, a `Resolved` / `Rejected` pair, not a
`CallSiteExtraction` carrier; `InputBeanResolver.buildJooqRecordLeaf` mints the
`NodeIdDecodeRecord` from a `Resolved` plus a `nonNull` flag it derives from the member's SDL
nullability. So the projection site does the same: one call for the decode data, plus its own
`nonNull` read off the `@nodeId` leaf's SDL type. And `resolveTargetKeys` is the *only* admissible
source for the candidate key-column list. `NodeIndex.forName(typeName).nodeKeyColumns()` is not a
substitute: that list is documented as possibly empty ("neither source supplied one; the primary
key is used at code-generation time"), so resolving candidates off it would produce an empty list
and a bogus "not one of its key columns" rejection at exactly the shapes `resolveTargetKeys`'
three-arm fallback exists to cover.

That is the strongest evidence for the record model: this item ends up reusing an existing
resolver *and* an existing emitter, and contributes the one thing genuinely missing, which is
opening the record a level further. Routing instead through the table-anchored
`NodeIdLeafResolver` would force a fake containing table into the call and re-derive an FK verdict
nobody asked for.

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

**What the node id opens into is a `TableRecord`, and that is the thing the segment opens
further.** This is the model, and it decides the carrier. `CallSiteExtraction.NodeIdDecodeRecord`
already exists and already emits exactly this: it calls
`encoderClass.decodeValues(typeId, nodeId)` and loads the values onto the target record's key
columns with one `record.fromArray(values, Tables.<T>.<col1>, ...)`, through a per-record-type
`decode<RecordType>Record` helper that returns the generated `*Record` type. So the segment does
not need a new decode mechanism; it needs a column read off a record that is already produced.

So the projection builds on `NodeIdDecodeRecord` rather than adding a `NodeIdKeyProjection` over
`NodeIdDecodeKeys`, and it builds *on top of* that decode's result while leaving the decode itself
untouched. **`NodeIdDecodeRecord` does not change.** The projection is a new top-level
`CallSiteExtraction` arm, `NodeIdRecordColumn(NodeIdDecodeRecord record, ColumnRef column)`,
meaning "open this record and read this column". The carrier nesting mirrors the path nesting,
which is the model made structural: `NodeIdDecodeRecord` is the node id opened, and
`NodeIdRecordColumn` is that record opened again. Existing consumers of the record decode see a
zero diff, and the emitted `decode<RecordType>Record` helper is the same method an input-bean
member already gets, called the same way.

Adding a slot to `NodeIdDecodeRecord` instead is wrong for the reason a terminal `PathExpr` arm
was wrong: it fuses two axes on one carrier. It would also be a nullable slot that
`InputBeanInstantiationEmitter` carries forever and never reads, a fact with no consumer at one
site and load-bearing at another. The new arm costs a compile error in every exhaustive
`CallSiteExtraction` switch until each names it, and those errors are the work list. State that as
a property of most switches rather than all of them: `ServiceMethodCallEmitter.scalarLeaf` carries
a `default ->` arm, so there the new carrier falls silently into a plain `($T) rawValue` cast
instead of failing the compile. That is a reason to drop the `default`, not a reason to doubt the
arm, but the "compiler enumerates the work list" argument is only true once it is dropped.

**What `nonNull` means once the record is wrapped.** `NodeIdDecodeRecord` carries a `nonNull`
component whose live readers today are in `JooqRecordInstantiationEmitter`, and wrapping brings it
along. It is not meaningless at a projection: it is the `@nodeId` leaf's SDL nullability, and it
decides whether the emitted read guards a null wire value before decoding (`ID!` cannot be absent,
`ID` can). Pin that reading on `NodeIdRecordColumn`'s javadoc and give the projection emitters the
null-guard fork, so the component has a named consumer at this site rather than riding along
undefined.

Four things follow from the record model, and they are why it wins rather than merely being
available:

* **No positional index, so no transposition.** A `NodeIdDecodeKeys` projection reads
  `RowN.value<i>()`, positional against `HelperRef.Decode.outputColumnShape`. A record read names
  its column (the generated typed accessor, or `Tables.<T>.<COL>`), so a transposed composite-key
  projection is unconstructable rather than merely tested for. That deletes a bug class and the
  execution-tier test whose stated job was catching it.
* **Typed for free.** The generated accessor returns the column's Java type, so the type gate
  compares real types and javac backstops the whole thing in the consumer's own compile.
* **The decode is reused whole, not adapted.** The resolver
  (`BuildContext.resolveNodeIdRecordDecode`) and the helper *body* are both taken as they are, so
  the diff on existing node-id machinery is zero and nothing becomes routine-flavoured on the way
  through. The reuse is of the body, not of its host: see "Emission" for the one place that costs
  something, which is that the body now needs emitting onto a second generated class.
* **The overlap dissolves rather than needing documentation.** Binding the whole record and
  binding one of its columns are one mechanism at two depths, which is exactly what the
  openability rule predicts: `input.organisasjonId` *is* the record, `.organisasjonskode` opens
  it. There is no longer a second way to get a decoded key into a call that the docs must
  disambiguate against the first.

Recorded for whoever reads the superseded shape: had this ridden `NodeIdDecodeKeys`, the arm
would have had to be a top-level `CallSiteExtraction` composing with it rather than a sibling of
`ThrowOnMismatch`, because that interface's documented axis is the failure mode and because
`ConditionGlueRenderer.decodeCall` reads the mode as
`nidk instanceof ThrowOnMismatch ? THROW : SKIP` and would have routed a new sibling silently to
SKIP. That ternary is still fragile and still wants to be an exhaustive switch, but it is now
somebody else's item rather than this one's problem.

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

Resolution is shared too, because there is nothing directive-specific in it: the candidate segment
resolves through `BuildContext.resolveNodeIdRecordDecode`, hence `resolveTargetKeys`, which is the
single place the `@node(keyColumns:)` fallback lives. One shared resolver owns that call and its
rejection messages, called by all three directive resolvers, the way `ServiceCatalog.argExtraction`
is already a shared gate called by both `@service` and `@routine`.

What must *not* absorb the resolution is `ArgBindingMap.of`. It is a `static`, pure function over
`(slotTypes, overrides)`; one of its `BuildContext` call sites passes an *empty* slot map (the
path-step `@condition`), where there is nothing to project from at all, and the other passes a
one-entry map. Threading schema state through it to serve callers that have none is the wrong
altitude. So the split is by *kind of question*, not by directive:

* **Grammar, one owner, and it is purely syntactic.** `of` admits *one* trailing segment after a
  leaf whose type is the `ID` scalar, and records it as an unresolved candidate: the segment name,
  resolved against nothing. It deliberately does **not** check for `@nodeId`. It cannot: its slot
  map is name-to-type (`FieldBuilder.argSlotTypes` drops the `GraphQLArgument`), so for a
  head-segment `ID` argument it holds no directive container at all, and an earlier draft asserted
  a capability the signature cannot support. Admitting on the `ID` type alone needs nothing it does
  not already hold, and costs nothing: an `ID` leaf with no `@nodeId` is rejected by the resolver
  with a directed message ("this `ID` carries no `@nodeId`, so there is nothing to open") instead
  of by `of` with the traversal message. `of` keeps its traversal rejection for every non-`ID`
  scalar unchanged, restated at the rule's grain ("this thing has nothing to open", naming what the
  thing is).
* **No per-site admission predicate, because uniformity means there is nothing to key on.** The
  `$session` sigil needs `ArgMappingSigil.Site.admitsSessionSigil()` because it is admitted at one
  site out of seven. The projection is admitted wherever the right-hand side is an argument path at
  all, which is exactly the set that routes through `of`, so a `Site.admitsNodeKeyProjection()`
  predicate would be a constant function. An earlier draft proposed one; drop it. The absence is
  the uniformity guarantee in its strongest form.
* **Resolution, one owner.** The shared resolver above: candidate segment plus leaf container in,
  a resolved projection or a located rejection out. It owns the `@nodeId`-presence check, the
  explicit-`typeName:` check, the key-column membership check and the decode data. All three
  directive resolvers call it.
* **Type gate, one predicate, three targets.** See "Type gate".
* **Emission, three sites.** `RoutineCallEmitter`, the `@service` pair
  (`ArgCallEmitter` / `ServiceMethodCallEmitter`), and `ConditionGlueRenderer`.

The remaining `ArgMappingSigil.Site` values are excluded because their right-hand side is not an
argument path at all, which is a difference in grammar rather than an asymmetry in capability:
`ENUM` maps enum constants, `RECORD` and `EXTERNAL_FIELD` bind their own target vocabularies
(and `@externalField` is folding into `@service` under its own item), and `REFERENCE_STEP` is the
path-step `@condition` whose slot map is empty, so it has nothing to project from. `columnMapping`
never routes through this owner and admits no sigil or projection: a column has nothing to open.

Those sites need no exclusion message, and that is the point: `$session` needs a per-site
non-admission message because it is a capability withheld from six of seven sites, whereas the
projection is withheld from nobody. A site that has no argument path has nowhere to write the
segment. Nothing in this design defers.

### One walk, not three: the leaf container rides out on `BoundPath`

`@nodeId` sits on the *field*, not on the type, so both the bare-form rejection and the projection
resolution need the `GraphQLInputObjectField`. Two walks already exist and each drops it:
`ArgBindingMap.of` walks the segment chain (with its own `unwrapForTraversal`) and keeps only what
it needs to build a `PathExpr`; `ServiceCatalog.resolvePathLeafType` walks it again (with its own
inline unwrap) and projects `field.getType()` immediately. A draft that resolves the container in
the directive resolvers would make three hand-maintained walks that must agree on list unwrapping,
non-null stripping and the null-on-miss convention, with nothing binding them.

So do not widen `resolvePathLeafType` and do not walk again. `of` already holds the field for every
segment it traverses; have it record the *leaf* container on the `BoundPath` it now produces
anyway. `resolvePathLeafType` takes a zero diff, no third unwrap is written, and the container
reaches the gate and the resolver as a component of the binding rather than as a re-derivation.

**The head segment is the exception, and it decides a scope line.** `of`'s slot map is
name-to-type (`FieldBuilder.argSlotTypes` drops the `GraphQLArgument`), so a head-segment leaf has
no container in any of the three walks. That covers `input.organisasjonId.<col>`, the motivating
shape and every shape where the `@nodeId` sits on an input-object field, but not `someIdArg.<col>`,
a `@nodeId` on the *argument itself*. Two ways out: widen `argSlotTypes` to carry the
`GraphQLArgument` alongside the type, which touches all six `of` call sites, or scope head-segment
`@nodeId` arguments out of this item. Take the second, with a directed rejection saying why ("a
`@nodeId` on the argument itself is not openable yet; move it to an input field"), never a
fall-through to the traversal message. The motivating case is field-borne, the LSP story is
unaffected, and the slot-map widening is a mechanical follow-up that need not be entangled with the
projection's first landing.

### The bare form becomes a rejection

Once the segment exists, binding a `@nodeId` leaf with *no* key-column segment is rejected,
naming the node type and listing its key columns. This is the change that closes the silent
`TEXT`-parameter hole, and it is worth landing even if everything else here slipped: today that
spelling writes a base64 string into a database column and nothing in the build says a word.

The counter-proposal, implicit decode for single-key node types, is rejected. It would make the
same spelling mean two different things depending on a fact (the node's key arity) that is not
visible at the `argMapping` site, and it would leave composite-key node types needing the
explicit segment anyway. One spelling, always explicit.

**The rejection is universal in this item, and the target-driven reading is deliberately not
taken.** The record model makes a tempting rule visible: unopened, `input.organisasjonId` denotes
the decoded `TableRecord`, so binding it to a `@service` parameter typed as the generated `*Record`
would be exactly what `NodeIdDecodeRecord` was built to serve, and only a single-value target such
as a routine IN parameter would reject. That reading is a *new capability*, not a rejection this
item declines to make, and it was checked rather than assumed: `ParamRole.ArgBound` routes through
`argExtraction(typeName, resolvePathLeafType(...))`, which for an `ID` leaf against a `*Record`
Java type yields the same `WireCoercionError.Assignability` rejection the `@routine`-into-`INTEGER`
case gets, and `NodeIdDecodeRecord` is minted in exactly one place,
`InputBeanResolver.buildJooqRecordLeaf`, for a jOOQ-record-typed *input-bean member*. A top-level
`@service` record parameter uses a different carrier entirely (`CallSiteExtraction.JooqRecord` via
`ValueShape.JooqRecordInput`).

So the bare form is rejected at every `argMapping` site here, and "bind a whole decoded record
through `argMapping`" is its own item for whoever wants it. Two things follow that are worth having
in writing: `ArgCallEmitter`'s `NodeIdDecodeRecord` invariant-throw ("an input-bean field leaf
only") stays *true* under this item rather than becoming a lie, and the item does not quietly grow
a second capability while claiming to close a hole.

This is a breaking change for any schema relying on the silent pass-through. It is a rejection
of a spelling that produces wrong data, so it is a bug fix rather than a capability removal, and
the rejection message names the fix. Call it out in the changelog entry at Done.

Three properties of this rejection to settle here rather than let fall out of implementation:

* **Ordering.** `leafTypeGate` runs list-shape → **`resolvePathLeafType == null` pass-through** →
  non-scalar → `argExtraction`, and that second step is the one an earlier draft did not name: it
  returns `null` ("unresolvable leaf: pass through rather than over-reject"). The rejection must
  run ahead of `argExtraction` *and* ahead of that pass-through, or an unresolvable leaf still
  slips through silently, which is the defect this item exists to close. Placed after
  `argExtraction`, an author gets the directed message or the `Assignability[...]` message
  depending on which column type they happened to bind into, which is the same defect wearing a
  different hat.
* **Verdict class.** `Rejection.structural`, since there is no future in which the raw base64 was
  intended. That verdict holds even if the rejection ships ahead of the projection (see "Scope"):
  a `deferred` verdict would say "this will emit later", and the bare form never will.
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

**The predicate must normalise boxing, and that is the part that is not free.** Today's
`columnMapping` check is `column.columnClass().equals(param.type().toString())`, string equality,
and it is sound *there* because a jOOQ routine parameter's type is always a boxed class. A
`@service` or `@condition` target is a reflected Java parameter that may be primitive, so the same
string equality would reject `int` against `java.lang.Integer`: a legal binding refused by the
shared enforcer at two of its four callers. Widen the predicate to compare after boxing, and pin it
with a unit case per caller. This is the one place where "one enforcer, four targets" costs
something rather than merely being tidier.

### Emission

The expression is the existing record-decode helper plus a column read:
`decode<RecordType>Record(<raw read>).get<Column>()`. The helper already raises the generated
client error on a malformed or wrong-type id, so the failure surface is inherited rather than
rebuilt.

**Where the helper body lives is the hard part, and an earlier draft had it backwards.** The
`decode<RecordType>Record` helper is not a free-floating method. `TypeFetcherGenerator` collects it
up front (`InputBeanInstantiationEmitter.collectRecordDecoders` over the class's *input-bean*
carriers), names it through `FetchersHelperNames`, and drains it onto that class's `<Type>Fetchers`
builder. `ConditionGlueRenderer` renders a separate conditions class per glue owner, each with its
own `CompositeDecodeHelperRegistry`, and a private static helper on `<Type>Fetchers` is not
callable from there. So the projection needs the helper body emitted onto *whichever generated
class holds the read*, and this item's three emit sites do not share one class.

Two consequences, and they replace the earlier draft's two:

* **`FetchersHelperNames` cannot be the threading vehicle, on a build-enforced rule.**
  `RoutineCallEmitter` lives in `no.sikt.graphitron.render`, and `PackageImportDirectionTest`'s
  render leg rejects any `no.sikt.graphitron.rewrite` import that is not on the borrow dial.
  `FetchersHelperNames` is a `rewrite.generators` naming resolver and is not on it (nor should it
  be: it is not a model ref). `CallSiteExtraction` and `PathExpr` *are* on the dial, so the new arm
  itself imports cleanly; the resolver does not. This is why `ArgPathHelperRegistry` and
  `CompositeDecodeHelperRegistry` both live in `render` in the first place.
* **The vehicle is a render-side, per-class, register-during-emit registry** in the
  `CompositeDecodeHelperRegistry` shape: an emitter asks it for a name, it lifts the body onto the
  class currently being built. That reaches all three hosts through one mechanism, which is what
  "identical at every site" requires of the emission half.

Two hazards this creates, both to be settled at pickup rather than discovered:

* **Duplicate helpers on `<Type>Fetchers`.** That class can host both an up-front input-bean record
  decode and a registered projection decode for the same record type. The registry must dedup
  against the names `FetchersHelperNames` already minted for that class, or one class gets two
  bodies for one job (or, worse, two names for it).
* **The existing up-front collection dedups by record class with `putIfAbsent`**, keyed on
  `rec.table().recordClass()` alone, so two `NodeIdDecodeRecord`s for one record class that differ
  in any component silently collapse to the first. `nonNull` is exactly such a component. Either
  pin that the differing components cannot reach the helper *body* (which is the likely truth, and
  is then worth a comment at the collector) or key the dedup on shape, the way
  `JooqRecordHelperNames` already keys on binding shape for this reason.

The up-front `scalarDecoders` collection does **not** widen to reach `argMapping` bindings. Leaving
it alone is what keeps the input-bean path at zero diff; the projection registers its own helper
where it is read.

**Reversing the double-decode decision: materialise once, via a hoisted local.** The earlier call
in this item was to accept one decode per projected column, on the grounds that a decode is a
base64 parse and the alternative cost an `emitCall` signature change. Under the record model both
halves of that reasoning change. The duplicated work is now a full record materialisation
(`decodeValues` plus a `fromArray` over every key column) and, more importantly, a duplicated
*failure* site: two identical throw points for what is one bad id. Hoisting one typed local gives
one materialisation and one failure site, and the generated source reads as what it is.

So take the signature change: `emitCall` yields pre-statements alongside its expression, and the
call sites add the pre-statements before the statement they already build. Three of the four have
statement context immediately to hand (`TypeFetcherGenerator`'s two routine fetchers and
`RootLauncherRenderer` all wrap the result in `addStatement`).

**The fourth site is a signature question one level up, not a site-local detail.**
`PathFragments.emitTableExpression` returns a bare `CodeBlock` consumed inside alias-declaration
loops, and neither its signature nor its callers give it statement context. So "hoist to the method
preamble" is not something that site can do by itself: it means `PathFragments` takes the same
pre-statement change and propagates it to *its* callers. Decide between that propagation and
keeping a per-read call at this one site with a comment saying why. A correlated routine call
binding two columns of one node id from `argMapping` is narrow enough that the site-local fallback
is honest rather than a hole; what is not honest is calling it an implementation detail when it is
a second signature change.

**The three emitters are at three different starting points, and none of them is free.** An
earlier draft called `@condition` "already there" and `@service` "the surprise"; measured against
the code, that ordering is wrong, because what `@condition` already has is the *`NodeIdDecodeKeys`*
mechanism, which is precisely the mechanism this design does not use:

* **`@condition` looks closest and is furthest.** `ConditionGlueRenderer.decodeCall` emits a
  `NodeIdDecodeKeys` helper through its per-class `CompositeDecodeHelperRegistry` and declares a
  typed local for the result, so the *hoist* is free there. The record decode is not: the conditions
  class has no `decode<RecordType>Record` body and no path to the one on `<Type>Fetchers`, so this
  site is where the second emission home has to be built. Size it as the expensive one.
* **`@service` refuses in three places, and only one of the three is a claim this item overturns.**
  `ArgCallEmitter` carries invariant-throws on `NodeIdDecodeKeys` (top-level extraction switch and
  `buildNestedInputFieldExtraction`, both saying node-id decodes belong to the condition glue) and a
  third on `NodeIdDecodeRecord` ("an input-bean field leaf only"). All three stay true: this item
  routes neither carrier to a service argument. What it adds is a *new* arm, `NodeIdRecordColumn`,
  in the same switches. Separately, `ServiceMethodCallEmitter.scalarLeaf` has a `NodeIdDecodeKeys`
  arm emitting a **plain cast** (`($T) rawValue`) alongside a `default ->` doing the same; drop the
  `default` so the new arm is a compile error rather than a silent cast.
* **`@routine` has to build the switch,** since `argExpression` never reads `extraction()`.

So the emission half is three real implementations against two helper hosts, not one slice plus two
touch-ups. The `@service` throw messages are load-bearing documentation of today's boundary and
remain accurate; the work there is a new arm beside them, not a rewrite of them.

Four `RoutineCallEmitter.emitCall` sites need a decode registry in scope: `RootLauncherRenderer`,
`PathFragments.emitTableExpression`, and two in `TypeFetcherGenerator`. Only the two in
`TypeFetcherGenerator` read `ctx.argPathHelpers()`; `RootLauncherRenderer` and `PathFragments`
receive an `ArgPathHelperRegistry` as a parameter from their own callers, so a sibling
`ctx.compositeDecodeHelpers()` accessor is the symmetric move for two of the four and a threaded
parameter for the other two, exactly as `ArgPathHelperRegistry` already is.

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
projection is a derived fact whose home is the classifier, not the capture twin: which node type,
which key column and which slot are all a function of that string plus `graphitron_node` and `sql_`
facts the store already holds, which is the "resolved value is always a view over the populations,
never a stored merge" case exactly. The raw `arg_mapping` column is unaffected either way.

**The strangler question this raises, stated rather than dodged.** The classification walk is
documented as a surface being drained, where a new capability arrives as a fact relation rather
than a walk-side extension. This item adds, entirely on the walk side: a `CallSiteExtraction` arm,
a `BoundPath` type in `ArgBindingMap`, a shared resolver, and a render-side registry accessor. That
is defensible during the window, because none of it asserts a new *fact*: every one of those types
is emit-side plumbing over facts the store already carries, `CallSiteExtraction` is a model ref on
the borrow dial rather than a `GraphitronField` leaf, and the emit side is not yet re-sourced. It
is worth saying so out loud so that a reviewer does not have to reconstruct it, and so that the
item is not later cited as precedent for widening the drain surface with a genuinely new fact.

## Implementation

* `CallSiteExtraction.NodeIdDecodeRecord`: **unchanged**, zero diff. Its javadoc may gain a
  sentence pointing at the new arm as the further opening, but the record itself does not move.
* `CallSiteExtraction`: new top-level arm
  `NodeIdRecordColumn(NodeIdDecodeRecord record, ColumnRef column)` in the sealed permits list, with
  javadoc pinning what the wrapped `nonNull` means here (whether the read null-guards the wire
  value). Every exhaustive switch without a `default ->` becomes a compile error until it names the
  arm; that enumeration is the work list. `InputBeanInstantiationEmitter` rejects it the way it
  already rejects `JooqRecord` (not an input-bean field leaf), and the emitters below implement it.
* `ArgBindingMap`: `of` admits *one* trailing segment after an `ID`-typed leaf and records it as an
  unresolved candidate, alongside the leaf's `GraphQLInputObjectField` when the leaf is not the head
  segment. It does **not** check for `@nodeId` (it holds no container for a head-segment leaf) and
  needs no `Site`. `Map<String, PathExpr> byJavaName` becomes `Map<String, BoundPath>`, where
  `BoundPath` is `(PathExpr path, String candidateSegment, GraphQLDirectiveContainer leafContainer)`
  with the last two nullable. `of` stays free of `NodeIndex` and `BuildContext`, and its one
  rejection is today's traversal message on a non-`ID` scalar, restated at the rule's grain.
* `ServiceCatalog.resolvePathLeafType`: **unchanged**. The container reaches its consumers on
  `BoundPath`, so no second producer is widened and no third unwrap is written.
* New shared resolver (one owner, called by all three directive resolvers): candidate segment plus
  leaf container in; a resolved `(NodeIdDecodeRecord, ColumnRef)` or a located rejection out. It
  owns four rejections: the leaf carries no `@nodeId`; the `@nodeId` has no explicit `typeName:`
  (routed through `NodeIdLeafResolver`'s existing message owner, whose two current messages end
  "Add typeName: explicitly." and "Specify typeName: explicitly." respectively, so joining them
  means picking one wording for all three); the segment is not a key column, listing the candidates;
  and the head-segment `@nodeId` argument that is out of scope. Key columns and decode data come
  from `BuildContext.resolveNodeIdRecordDecode` (hence `resolveTargetKeys`), never from
  `NodeIndex.forName(...).nodeKeyColumns()`, which may be empty. `nonNull` is read off the leaf's
  SDL type at this site, the way `InputBeanResolver.buildJooqRecordLeaf` reads it off the member.
* Shared type-gate predicate: lift `RoutineDirectiveResolver`'s `columnMapping` check
  (`column.columnClass()` against the target's Java type) into one predicate with boxing
  normalisation, called by `columnMapping`, the projected `@routine` binding, `@service` and
  `@condition`.
* `RoutineDirectiveResolver`: run the shared resolver and the shared type gate, place the bare-form
  rejection in `leafTypeGate` ahead of both `argExtraction` and the `resolvePathLeafType == null`
  pass-through, and mint `NodeIdRecordColumn` instead of `Direct`.
* `ServiceDirectiveResolver` / `ConditionResolver` (both sites): the same resolver and the same
  gate against their own target type (the reflected Java parameter, the condition-method parameter).
* `ArgCallEmitter`: a `NodeIdRecordColumn` arm in the top-level extraction switch and in
  `buildNestedInputFieldExtraction`. Its three existing invariant-throws (two on `NodeIdDecodeKeys`,
  one on `NodeIdDecodeRecord`) stay exactly as they are and stay true.
* `ServiceMethodCallEmitter.scalarLeaf`: a `NodeIdRecordColumn` arm, and drop the `default ->`
  fallback so a future arm is a compile error rather than a silent `($T) rawValue` cast.
* `ConditionGlueRenderer`: the projected read, plus the record-decode helper body on the conditions
  class (the second emission home; see "Emission").
* `RoutineCallEmitter`: `argExpression` switches on `b.source()`'s extraction for the first time.
  `emitCall` gains the render-side decode registry as a parameter and yields pre-statements
  alongside its expression; the four call sites thread the first and add the second. Not
  `FetchersHelperNames`, which `PackageImportDirectionTest` forbids this package from importing.
* Render-side record-decode registry in the `CompositeDecodeHelperRegistry` shape (per class,
  register-during-emit), deduping against the names `FetchersHelperNames` already minted for
  `<Type>Fetchers`. The up-front `collectRecordDecoders` walk is **unchanged**.

## Tests

* `ArgBindingMapTest` (unit): admission of one trailing segment after an `ID` leaf, the unchanged
  traversal rejection on a non-`ID` scalar, and the leaf container arriving on `BoundPath`. `of`
  stays pure, so this stays a pure unit test; node resolution is not exercised here because `of`
  does none.
* The shared type-gate predicate wants its own unit test with a **primitive** target (`int` against
  an `INTEGER` column), because string equality passes the `columnMapping` caller and fails the
  other three, and no pipeline test at `@routine` would catch it.
* `RoutineMutationWritePipelineTest` (pipeline): a sakila-shaped variant of the existing nested
  `rent_film` fixture binding `pInventoryId` from `ID! @nodeId(typeName: "Inventory")` through
  the projected key column. Assert the classified `ArgBinding` carries a `NodeIdRecordColumn`
  wrapping an unaltered `NodeIdDecodeRecord`, and assert the emitted call site materialises the
  record exactly once and reads the column off it. The wrapping is worth asserting structurally,
  not just the emitted string: it is what keeps the decode carrier reusable rather than
  routine-flavoured.
  Plus the rejection cases: the bare `@nodeId` leaf, a segment naming a non-key
  column, a `@nodeId` without `typeName:`, and a projected column whose Java type does not match
  the parameter's.
* Each rejection also wants a **validate-time** test that the build fails, not only that
  classification yields a `Rejection`. "Validator mirrors classifier invariants" is the rule, and
  a rejection that classifies but does not fail the build is the failure mode it guards against.
* **Cross-directive parity is itself the contract, so pin it as one.** The same projected binding
  at `@routine`, `@service` and `@condition` must resolve to the same projection and produce the
  same rejections; a parameterised test over the three sites states that directly and fails when
  one site drifts, which prose in three separate test classes cannot. Pair it with the constraint
  that makes a phased landing impossible: **no site may classify a projection it cannot emit.**
  That is the invariant the parity test is really guarding, and it is what forbids shipping the
  resolver ahead of any one emitter.
* **`@condition` needs its own emission test against the helper host**, not just the read: the
  conditions class must carry its own `decode<RecordType>Record` body, and `<Type>Fetchers` must not
  end up with two. Both are assertions about which class holds which method, so they belong in the
  compilation tier where the generated sources are on disk.
* Composite-key coverage belongs on the `nodeidfixture` catalog (`bar` is the composite-key node
  type `NodeIdPipelineTest` already uses), binding two parameters from one node id. What it pins is
  the *single materialisation*: one `decode<RecordType>Record` call and one failure site, with two
  column reads off the local. Note what this fixture no longer has to pin: under the record model a
  transposed projection is unconstructable, because the reads name their columns instead of
  indexing a tuple, so the test covers the hoist rather than the ordering.
* Execution tier (`graphitron-sakila-example`): one round trip proving the decoded key reaches
  the database, alongside the existing `NodeIdValueAgreementExecutionTest`. This is now a
  smaller claim than the earlier draft made, since the transposition it was chiefly guarding
  against cannot arise; keep it for the end-to-end proof that the decode reaches SQL as a key
  rather than a base64 string.

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
  from the node type's key columns, which the snapshot can carry cheaply. One caveat to carry with
  that: the completion list must be the *resolved* key columns (`resolveTargetKeys`, hence the
  catalog primary-key fallback), not `NodeType.nodeKeyColumns`, which is empty whenever the author
  did not pin `@node(keyColumns:)`, and an empty completion list is worse than none. So key-column
  completion is reachable *ahead* of the general case rather than after it. It is still its own item and must
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

Landed alone it needs no new `CallSiteExtraction` arm, no grammar change, no `emitCall` signature
change, no emitter work at any of the three sites and no helper-hosting decision. It is one
predicate in `leafTypeGate` and its two siblings: the resolved leaf carries `@nodeId` and the target
takes a single value, so reject. That closes the silent-base64 hole, which this item itself calls
the sharper half of the problem and "worth landing even if everything else here slipped".

Splitting does not reintroduce a per-directive deferral. Rejecting the bare form at all three sites
*is* uniform behaviour, and a rejection needs no emitter anywhere. Nor does the message have to
promise the segment: "bind the decoded key by naming a key column" is a `structural` statement about
what the author must write, and it becomes constructive when the projection lands rather than
retroactively true. That is what keeps the interim verdict `structural` rather than `deferred`.

The other two heavy pieces, the `@service` and `@condition` emitter arms and the `emitCall`
pre-statement change, belong with the projection: they are what makes it emit.

The argument against splitting is that a rejection with no fix available yet is a worse author
experience than either state alone. That is real, and it is why this is a judgment call for the
sign-off rather than a decision recorded here.

## Open questions

* Whether `PathFragments.emitTableExpression` takes the pre-statement change (which propagates to
  its callers, since it returns a bare expression consumed inside alias-declaration loops) or keeps
  a per-read call as a documented site-local exception. Answered far enough to size: it is a
  signature question one level up, not an implementation detail.
* Whether a `@nodeId` input field that nothing consumes should warn. Today it is silently
  ignored wherever no consumer reads it, which is how the `TEXT` case above stays invisible;
  the bare-form rejection closes it at the `@routine` site only. A general "declared and
  unconsumed" warning is a larger question and belongs in its own item if anyone wants it.
