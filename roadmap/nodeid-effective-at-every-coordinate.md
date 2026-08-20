---
id: R728
title: "@nodeId encode and decode become store relations, and an instruction the generator drops fails the build"
status: Spec
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-19
last-updated: 2026-08-20
---

# @nodeId encode and decode become store relations, and an instruction the generator drops fails the build

`@nodeId` is an instruction. The manual states it in one sentence
(`docs/manual/reference/directives/nodeId.adoc`): "The site axis decides direction: on a
`FIELD_DEFINITION` the directive *encodes* the parent's primary-key columns into the opaque ID; on
`INPUT_FIELD_DEFINITION` and `ARGUMENT_DEFINITION` it *decodes* the ID back to typed key columns at
the carrier."

So there are two states, not three. Either a slot carries the instruction, explicitly or by one of
the rules below, and the generator owes an encode or a decode; or it does not, and there is nothing
for the generator to do. The contract that follows is the one this item enforces everywhere: **a slot
carrying the instruction never delivers the wire format to consumer code, and never takes it from
there.** A service does not know that node ids exist, and neither does the bean that feeds it or the
exception an `@error` type reads. Nothing else needs saying, and this item introduces no vocabulary for it:
encode and decode are the words the manual uses and the words the code already carries in
`CallSiteCompaction.NodeIdEncodeKeys`, `CallSiteExtraction.NodeIdDecodeKeys` and the generated
`encode<TypeName>` / `decode<TypeName>` helpers.

The bug is the third state the generator actually has today, which is being given the instruction and
dropping it. The author writes the directive, the build says nothing, and the raw base64 flows through
to consumer code that takes it apart by hand. The development principles name that outcome as a smell
in their own terms (`docs/architecture/explanation/development-principles.adoc`, "Boundaries decode
and encode; the interior is typed"): "a bypass around classified information the boundary already
carries."

A field report against 10.0.0-RC32 named four coordinates where it happens. This item does not take
that list on trust. It makes the instruction and its resolution facts, which turns "does `@nodeId`
mean the same thing everywhere" from a claim into a query, and then answers every coordinate the
query finds. The census is wider than the report in one direction and narrower in another: the
reported `@error` case is one arm of four that share its cause, and one reported case already works
and needs only a message and a page.

There is no coordinate where a dropped instruction is wanted, so the rule is total and
unconditional. Three candidates were tested and none survives. A documentation marker ("this ID is a
`PlasstildelingV2`") is the bypass the principle names, and the type system plus the manual already
do that job. A forward declaration written before its destination exists wants the build to fail,
which is the signal. And an interface declaration site does not exist as a pattern: SDL directive
applications are per-declaration with no interface inheritance, while a `@table` interface is itself
table-bound and encodes like any bound type, so an author who wrote `@nodeId` only on the interface
field has made a mistake worth reporting.

## Where the instruction comes from

The instruction has three forms, and the manual documents all three. Getting the population right
matters more than anything else in this plan, because the detection is "instructed and not carried
out" and an instruction the population misses is a coordinate that stays silent.

* **Explicit.** `@nodeId(typeName: T)`. Captured as a row in `graphitron_field_node_id` on
  `(graph_name, type_name, field_name)`, which covers output fields and input fields alike, or in
  `graphitron_argument_node_id` on `(graph_name, type_name, field_name, argument_name)`. Both carry
  `node_type_ref` as the author wrote it and a source position.
* **Bare.** `@nodeId` with no `typeName:`, which is the same captured row with `node_type_ref` NULL,
  the relation's own comment already reading that NULL as "inference when NULL is a derivation". The
  manual gives inference exactly two rules: (a) on a non-`@reference` object field, the containing
  type is itself a `@node`; (b) on a `@reference` field or jOOQ-record input field, exactly one
  `@node` type binds to the same target table. Rule (b) "is the only place the backing table decides
  anything", an explicit `typeName:` taking the `typeId` and key columns from the named type's own
  `@node` instead, which is what keeps several node types over one table from making a named leaf
  ambiguous.
* **Name-carried, with no directive at all.** Two documented cases, one per direction. A node type's
  own `id:` field is "a node ID by construction", and `typeName:` is *rejected* there because the
  containing type has already answered. And a slot *named* for the target's own `id` decodes without
  the directive: `id` on an input consumed against a node-backed table, or on an argument of a field
  returning a node type. "The name is what carries it, so a differently-named slot (`ids`,
  `customerId`) still needs the directive; graphitron does not guess at plurals or suffixes."

The third form has no row in either `@nodeId` relation, so the population is not simply those two
relations. It is a derivation over them plus the name-carried rule, which the store can state:
`graphql_field` and `graphql_argument` carry the slot's name, and `intent_node_type` with
`intent_resolved_type_binding` carry which types are node-backed and over which table. Where the
target table backs more than one node type the build already fails and asks for `typeName:`, and a
directive-less slot colliding with a real column of the same name is already an error at every
coordinate, so the two ambiguity cases the name-carried rule could raise are answered before this
item starts.

What the instruction *means* is then one further join: the node type plus the graph name reaches
`intent_resolved_node_key_column`, keyed `(graph_name, type_name, position, column_name, tier)`. That
relation is the opaque format's other end, T's key columns in order with the tier that answered.

None of this is a classifier reading, and that is the design. The classifier knows nothing here the
store cannot: it resolves these same facts in Java, separately per coordinate kind, which is exactly
why the coordinate kinds it never grew an arm for are silent instead of reported. Asking the
classifier which coordinates carry out the instruction would be asking the thing whose gaps are the
bug.

## Design

### The resolution is two relations, one per direction

`intent_node_id_decode` and `intent_node_id_encode` state how one instruction is carried out. An
instruction with no row in its own direction's relation was not carried out, and the defect view
below says which precondition stopped it. Absence here is therefore never the message: it is the
membership condition the defect view is keyed on, which is what keeps the two from restating one
fact. That split follows `intent_argmapping_key_column_candidate`'s own argument for being separated
from the projection beside it, that stating an unknown column as the absence of a candidate row and a
type disagreement as a candidate row with no projected row makes the two arms "disjoint by
construction" so neither has to re-test the other's predicate.

**Two relations rather than one with a direction column**, because the two directions genuinely
answer different questions and sharing a row shape would make half the columns nullable by kind.
Decode has a destination that receives a tuple, and where that destination is a table it also has a
column correspondence and possibly a join path. Encode has a source that produces one, and never has
either. `intent_input_occurrence_path_step` states the same discipline for its own shape, that it is
"homogeneous over input-field steps only ... so no column here is nullable by kind". Direction is
never a column: it follows from the coordinate kind, `graphql_type.kind` on the owning type plus
which relation answered.

**Grain: the instruction and its use site.** An argument and an output field are their own use site.
An input field's use sites come from `intent_input_occurrence_path`, whose
`(root_type_name, root_field_name, root_argument_name)` is exactly the consuming coordinate, whose
every prefix is a row, and whose steps are ordinal-decomposed in
`intent_input_occurrence_path_step` so no reader parses the serialized key.

The use-site grain is load-bearing rather than tidy. One input type may be consumed where the decode
resolves and where it cannot, so a verdict keyed on the instruction alone would have to pick one
answer for two consumers. `ArgmappingProjectionDefects` already argues this for its own messages,
that "one input type can be consumed where inference works and where it cannot, and an author told to
add `typeName:` needs to know which consumer is asking". The same reasoning fixes the grain here, and
the message names the use site for the same reason.

It also settles a question the previous draft answered with policy. An input field on an input type
nothing reaches produces no occurrence path, so no use site, so no row and no verdict. That is not a
reachability gate and it is not an exemption: a decode is "these values go here", and with no
consuming coordinate there is no here. The earlier draft argued instead for a detection deliberately
ungated by walk reach, reasoning over `walk_claim_domain_type` / `walk_claim_domain_field`. That
reasoning is retired twice over: this rule never needed those relations, and they no longer exist,
R743 having deleted both when `intent_authored_claim_conflict` stopped gating on the walk's reach.
The shape that replaced them is the one this item follows, and the detection section below states it
as such: the relation carries no accept line of its own, and each consumer applies the population its
own question needs.

**`intent_node_id_decode`** carries the coordinate, the use site, the resolved node type, and the
destination that receives the decoded tuple. Four destinations, a closed vocabulary:

* `OWN_TABLE_COLUMNS`. The decoded keys land on a column tuple of the row's own table: same-table
  identity, a foreign key's child columns, or the tuple an identity-carrying chain lifts back to. The
  predicate binds locally with no join.
* `TARGET_TABLE_COLUMNS`. No own-table tuple exists, so the predicate binds the node type's own key
  columns on its own table inside a correlated `EXISTS`.
* `JOOQ_RECORD`. A generated `*Record`: a `@service` method parameter, or a record-typed member of a
  consumer bean. A record holds a tuple, which is why these two work today.
* `SINGLE_KEY_COLUMN`. The destination holds one value, so one key column's value goes in it. Reached
  two ways that share a shape: the author names the column as a trailing `argMapping` segment
  (R668's capability), or the node type has exactly one key column and arity names it. Requires the
  column's Java type to match the slot's own; see "Sites 1 and 2" below.

**Two ordinal-keyed children on the decode relation**, following the parent-plus-steps shape
`intent_input_occurrence_path` / `_step` already uses:

* `intent_node_id_decode_column`, one row per key position, carrying the node type's key column at
  that position and the local column it lifts to on the row's own table, `NULL` where no lift exists.
* `intent_node_id_decode_hop`, one row per foreign-key hop from the coordinate's own table to the
  node type's table, in authored order.

Those two children are why the destination vocabulary can be flat. `OWN_TABLE_COLUMNS` is every
position having a local column; `TARGET_TABLE_COLUMNS` is none of them having one. Which is the whole
of site 4b, as the next section explains.

**`intent_node_id_encode`** carries the coordinate, the resolved node type, and the source that
produces the tuple. Two sources:

* `PROJECTED_COLUMNS`. The field's value is a column tuple in scope and the encode wraps it at the
  SELECT-side projection. Everything that encodes today.
* `READ_VALUE`. The field's value is *read* rather than projected, through an accessor, a by-name
  record read, a typed column off a record, or graphql-java's own property machinery, and the encode
  applies to what the read yields. New with this item; see "The dropped encode is the whole read
  family" below.

**`NodeIdLeafResolver` becomes a reader.** It resolves these facts in Java today. After this item the
relations resolve them and the classifier reads the rows, which is R682's direction
(`roadmap/planners-read-facts-emitters-read-commands.md`: planners read facts) arriving early for one
directive rather than being retrofitted onto a Java sealed hierarchy a release later.

**Where a view cannot express the walk, materialize it, through the registered mechanism rather than
by hand.** The lift is a positional-subset check between adjacent hops, and computing the lifted tuple
walks the chain back from the terminal hop. Where that has no safe recursive H2 view form it lands as
a materialized relation, which `intent_input_occurrence_path` and `intent_type_domain` both already do
and both state in their own comments. What has changed since those two is that R742, now Ready, mints a
registry for it: `meta_materialize`, a constrained table carrying one row per registration, the source
view under a `_live` suffix and the target table under the canonical name every existing reader already
uses, plus a required `reason` that is where the materialization doctrine lives. One materializer in
`graphitron-model` empties each target and refills it from its source on the capture cadence. Anything
this item materializes registers there rather than growing a second writer. Refresh *ordering* is
deliberately not in that registry, R742 having established that neither of its two registrations sits in
the other's closure and left the general case to R746; a relation this item registers whose source reads
another registered target is therefore the case that makes R746 real rather than additive, and stage 2
says so if it arrives.

### The derivation depth is a design constraint, not an afterthought

R742 measured the precedent this item follows and the number is the reason to read it first. One read
of `intent_argmapping_projection_defect` expands to **2149 relation instantiations** and 24.5 seconds,
because H2 inlines a view wherever it is named and does no common-subexpression elimination, so
multiplicities compound down a stratum twenty-two views deep. Two of its costs are directly
instructive here.

**The six `UNION ALL` arms are most of the bill.** Each arm re-joins the same driving relations with a
different `WHERE` and a different verdict literal, which is where the 1036 and 745 contributions come
from. The defect view below has two verdicts and must not be written that way: it computes both
preconditions in one pass over the population and picks the verdict with a `CASE`, so the driving
relations are named once. Same for the two resolution relations, whose destination and source
vocabularies are tempting to write as one arm per value.

**`intent_resolved_node_key_column` is windowed, so predicates do not push into it.** It carries a
`DENSE_RANK() OVER` for its tier precedence, and a window sees its whole partition whatever predicate
a reader applies outside. This item joins it twice by nature, once for the key columns and once for
the arity count, which is exactly the shape the fact model already prescribes a remedy for: take the
relation once and pair it on its key. The arity is then a count over the rows already taken, not a
second read.

Both points are exit conditions rather than advice, because the metric is checkable without a
database: inline multiplicity is computable statically from `graphitron-model.sql` by parsing the
`CREATE VIEW` bodies and multiplying textual reference counts down the tree, and R742 proposes exactly
that as a `roadmap-tool` build gate. Whether that gate has landed or not, the number for each relation
this item adds is computable the same way, and stage 2 states it rather than discovering it in a
profile later.

**One piece of navigation has to be authored first.** `intent_field_reference_step_hop` and
`intent_field_reference_step_target` resolve reference-path hops, and they are field-site only. An
argument-site `@reference` path has no equivalent, over
`graphitron_argument_reference_step`. R723 named authoring those sibling views as the prerequisite it
was declining to take on. This item takes it on, because a `@nodeId` filter path is an argument-site
path and the decode hop child above cannot be populated without it.

### Site 4b dissolves into the relation rather than being refactored

The junction-table case (`Sak` filtered by `Tagg` ids through `soknad_tagg`, or `film` through
`film_category` to `category` in sakila) is reported as unsupported and the emitter it needs is
already built. `BodyParam.RemoteColumnPredicate` carries a whole `joinPath`;
`ConditionCommands.narrowPath` narrows every step; and `ConditionGlueRenderer.reachExists` walks the
whole reach, selecting from the terminal alias, bridging back through hops `n-1 .. 1`, and
correlating hop 0 against the row's own table. `FkTargetConditionFilter` says so in its own javadoc
("Single-hop for the common case; multi-hop walked inside the `EXISTS`").

What blocks it is upstream, and it is one conjunct doing the work of two. `NodeIdLeafResolver` picks
`DirectFk` when `permutationToKeyColumns` succeeds on the terminal hop, while `DirectFk`'s *meaning*
is "the decoded keys lift to a tuple on the field's own table". Those are two facts and the resolver
checks one, because `validateLift` runs earlier and rejects rather than recording that no lift
exists. `TranslatedFk`, whose whole premise is "no own-table tuple, bind on the target inside an
`EXISTS`", is therefore unreachable for a multi-hop path.

In the relation those are two columns. A decode whose `intent_node_id_decode_column` rows all
carry a local column is `OWN_TABLE_COLUMNS`; one whose rows carry none is `TARGET_TABLE_COLUMNS`; and
the junction chain is the second because the lift walk contributes no local column, not because
anything rejects it. There is no sealed result to invent, no discriminator to state twice, and
nothing to retire from Java beyond the gate itself.

This was spiked before the relation was designed, with the discriminator expressed as a nullable slot
in Java, and the spike is still the evidence that the emitter side is ready.
`film -> film_category -> category` lowered to a two-hop `RemoteColumnPredicate` and rendered:

```java
DSL.exists(DSL.selectOne()
    .from(table_fkt0_1)
    .join(table_fkt0_0).onKey(Keys.FILM_CATEGORY__FILM_CATEGORY_CATEGORY_ID_FKEY)
    .where(table_fkt0_0.FILM_ID.eq(table.FILM_ID).and(table_fkt0_1.CATEGORY_ID.in(categoryIds))))
```

which is the predicate the report asks for. The identity-carrying chain still bound locally and still
emitted the local `IN`. The full non-execution suite ran 3626 of 3627 green, and the single failure
was `NodeIdLeafResolverTest.multiHopLiftTranslationRejected`, the test pinning the rejection the
change deliberately removes. That fixture is worth keeping and rewriting rather than deleting,
because it is the case that proves the two conjuncts are two: its terminal hop's target-side columns
*are* the node type's key columns, so a change that dropped `validateLift` without recording the
absent lift would route it to a local predicate over a tuple that does not exist.

Two properties make the relaxation safe rather than merely cheap.

* **The write rails already refuse a remote binding.** INSERT (`MutationInputResolver`),
  `UpdateRowsWalker`, `DeleteRowsWalker` and `FieldBuilder.classifyPlainLookupKeyArg` each gate on the
  `FilterBinding` arm with the shared `FilterBinding.remoteBindingUnsupported` text, and
  `TranslatedFkTargetRailGatesPipelineTest` already pins all four against a single-hop translated FK.
  A junction chain reaching a write or `@lookupKey` coordinate meets a stated message rather than
  emitting a wrong statement.
* **`EXISTS` is already the argued-for shape at non-unique cardinality.** R57's changelog entry
  settles it: no row multiplication when the path is non-unique, and a NULL foreign-key column fails
  the correlation instead of duplicating or dropping rows. A junction chain is the non-unique case
  that argument was written for.

Relaxing a producer obliges a consumer audit in the same commit, which is a stated rule here
(`development-principles.adoc`, "Acceptances: classifier guarantees shape emitter assumptions"). The
spike's 3626 is evidence about the *read* path only: nothing pins a write-side multi-hop non-lifting
`@nodeId` today, because the lift rejection fires before those coordinates are reached. Afterwards
such a path binds remotely and meets each rail's own refusal, whose text is about a remote binding
rather than about the author's chain. So the audit is per rail and it is an exit condition, not a
follow-up: each of the four states which message the author now sees, and the junction fixture joins
`TranslatedFkTargetRailGatesPipelineTest` rather than starting a second class beside it.

**The condition-join hop is not relaxed here, and the reason is the emitter.** `CONDITION_STEP_MARKER`
and `validateLift` state one predicate twice, so both express themselves through the relation, and
that much is this item's. But the `EXISTS` emitter is hop-general over foreign-key hops and over
nothing else: `ConditionCommands.narrowPath` narrows every step through `FkHop.narrow`, which throws
`IllegalStateException` on any hop whose `on()` is not `On.ColumnPairs`. Routing a condition hop to a
remote binding today would replace a stated author-facing rejection with an untyped generation-time
throw. Widening it is R705's work, which retires `FkHop`, `FkHop.narrow` and `narrowPath` outright.
So a condition hop keeps its own rejection through this item, and R705 inherits the relation rather
than co-authoring it.

### The dropped encode is the whole read family, not the `@error` type

The report named an `@error` type's extra field: an `ID` carrying `@nodeId(typeName:)` whose value
reaches the consumer already encoded by hand, because `FieldBuilder.classifyChildFieldOnErrorType`
classifies it as a plain `ChildField.RecordReadField` with a `ValueLocator.DefaultRead` and the
directive contributes nothing.

The cause is wider than the `@error` type, and the store framing is what makes that visible. A
`RecordReadField` is every output field whose value is *read* rather than projected, and
`ValueLocator` has four arms: `TypedColumn` off a jOOQ table record, `JavaAccessor` off a class-backed
parent, `ByName` off a record carrier, `DefaultRead` where graphitron locates nothing. Not one of them
carries a `CallSiteCompaction`; only `ChildField.ColumnBackedField` and `ColumnBackedReferenceField`
do. So `@nodeId` is equally inert at all four, and the reporter hit one of them. Same silence, same
cause: a read has no wire direction.

So the fix is one slot, not a per-`@error`-type carrier. `RecordReadField` gains a `CallSiteCompaction`
beside its `ValueLocator`, and the encode then works at every read arm at once. That is the vocabulary
`ColumnBackedField` already uses, and the `NodeIdEncodeKeys` arm carries only a `HelperRef.Encode`,
whose emitter reads `encoderClass()` and `methodName()`, so it ports to a read unchanged. The
classification change is one arm in each `RecordReadField` construction site;
`classifyChildFieldOnErrorType` today ignores every directive, and
`recordReadFieldOrUnclassified` is the shared lift the other arms go through.

The `@error` runtime path needs one further move, and it is a unification rather than an addition. An
`@error` type's extra fields are not projected by a generated fetcher at all: they are read at runtime
by graphql-java's `PropertyDataFetcher`, registered in
`GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers`, which emits two hardcoded registrations
for `path` and `message` and then folds over `GraphitronType.ErrorType.accessorOverrides`. That list
holds only the fields carrying `@field(name:)`; an extra field without one gets no registration and
falls through to graphql-java on the SDL name. So the encode has to be reachable both with an override
and without one, which means the registration folds over every extra field rather than over the
override subset. `ErrorType` carries one per-field list whose slot holds the read (an accessor base, or
the built-in `path` / `message` arm) and the wire direction, `buildErrorTypeFieldFetchers` becomes a
fold over that one list, and the classified `RecordReadField` and the type-level override list stop
being two spellings of one per-field read.

R686 landed the same move on the neighbouring field while this was being written, which makes the shape
a precedent rather than a proposal. `message` used to carry an `Optional<String> description` on each
handler and fall back at runtime; it now carries a sealed `ClientMessage` resolved once at lift time,
`Static` or `FromSource`, so the emitted body picks its statement per arm rather than "carrying a
runtime null test over a decided value", and the arm sits on the three dispatch variants rather than on
`Handler` because `ValidationHandler` has no client message to carry and "would have to fake one". The
wire direction this item adds to a per-field read is the same kind of fact, decided at lift and read per
arm, and that argument says it belongs on the slot that can carry it rather than on a supertype that
cannot.

**The encode side takes the same two preconditions, for the same reason.** `encode<TypeName>` takes
N key values positionally and a read yields one Java value, so a `READ_VALUE` source carries the
encode out exactly when the node type's key is one column and that column's Java type matches what
the read yields. Both are facts in the relation rather than a validator mirroring a classifier
invariant: the arity is `COUNT(*)` over `intent_resolved_node_key_column`, and the read's own type
comes off the accessor or column the `ValueLocator` names. The refusals name which precondition
failed, the type and the count on one, both types on the other, and one place then says everything
about that coordinate.

This is the same rule as sites 1 and 2 read in the other direction, and stating it once in both
places is deliberate: a consumer neither receives nor supplies the wire format. A read yielding a
`String` where the key column binds as `Long` is refused rather than encoded off a coerced value,
because the value the consumer supplied is then not the key.

The reporter's own case (`opptaksrundeId`) is single-key, so this refuses only what it can name.
Widening to composite wants a spelling that does not exist yet, either a read yielding a jOOQ
`Record` of the node's key shape unpacked positionally, or a way for the SDL to name N reads, which
`@field(name:)` cannot express; that is a later item and this one states the refusal rather than
inventing the spelling.

### Site 4a: the reverse hop needs a message and a page

One reported case already works. A single reverse hop, filtering parents by their children's node
ids, resolves to a remote binding and lowers to the correlated `EXISTS` on both the argument and the
input-field surface. The only thing between an author and it is that
`JooqCatalog.findUniqueFkToTable` searches from the containing table outward, so the reverse
direction rejects with "no unique FK from X to Y; declare `@reference(path: [{key: ...}])` to
disambiguate". That message does name the spelling that works, which is why this is a wording fix and
not a missing remedy: it frames the spelling as *disambiguation among several candidates*, and an
author whose problem is *zero* candidates in the searched direction reads it as not applying to them.

Two changes, and one of them has a shape the current signature does not admit.

* The rejection distinguishes its two causes. Several foreign keys is a disambiguation; none in the
  searched direction is a different fact, and the message should say that a foreign key declared on
  the *target* side is reachable by naming it explicitly. Note that `findUniqueFkToTable` returns
  `Optional.empty()` for both zero matches and several
  (`matches.size() == 1 ? Optional.of(...) : Optional.empty()`), so the two causes are not
  distinguishable at the call site today; `findForeignKeysBetweenTables` plus `foreignKeyOnSource`
  already give the count, and the split is a signature or call-site change rather than a message
  edit.
* `docs/manual/how-to/multi-hop-nodeid-filter.adoc` stops asserting that a single direct foreign key
  never produces a subquery. That page correction is R691, and this item **absorbs** it rather than
  depending on it: this item is already editing that page twice over, so leaving a third false
  sentence to a separate item would mean three passes over one file to fix one page's account of one
  mechanism. R691 is discarded at this item's Done gate and its file stays as the redirect until
  then.

### Sites 1 and 2: the consumer never sees the wire format

A plain argument on a `@service` field, and a member of a bean-backed `@service` input, are one
problem wearing two coordinates, and the rule at both follows from the invariant this whole item
exists to enforce: **a slot carrying the instruction never delivers base64 to consumer code.** A
service does not know that node ids exist, and neither does the bean that feeds it. The author wrote
`@nodeId`, so the value the consumer receives is decoded, and there is no arm in which the generator
hands a `@service` method the wire string and calls it done.

That rules out both of the answers a plan might reach for. Passing the base64 through is the bug.
Refusing the coordinate outright is also wrong, because the instruction is carriable whenever the
node type has one key column: the decode yields exactly one value, nothing about which value is
ambiguous, and a single-valued slot takes it. So the decode resolves at these coordinates under two
preconditions, and refuses naming which one failed:

* **The node type's key is one column.** `COUNT(*)` over `intent_resolved_node_key_column` for the
  node type. A composite key has N values and the slot has one place, so the refusal names the type
  and the count.
* **That column's type matches the slot's own type.** The column's Java type as jOOQ binds it,
  against the `@service` parameter's or the bean member's declared type. A `Long` key column and a
  `String` parameter is a refusal naming both types, not a pass-through, because a pass-through is
  exactly the bug: the only value a `String` parameter could receive there is the base64.

Neither precondition is minted here. R668 shipped both operands and the rule that joins them, and
this item reuses them rather than restating them one directive over.
`intent_argmapping_key_column_candidate` already carries a matched key column with "that column's
Java type beside it where the catalog can say", reached by an outer join precisely so an untypeable
column is a NULL and not a missing row. `intent_resolved_node_key_projection` already makes the
agreement a *join predicate* rather than a check after the fact, at **equality of the erased Java
type with no widening admitted**, which is the user-visible half of this rule: a `SMALLINT` key column
against an `Integer` slot is a disagreement, and softening it is what would let a narrowing through.

**And this item inherits R668's stand-aside rule verbatim, which is the half easiest to get wrong.**
The type gate fires only where *both* operands are known. Where the catalog cannot type the key column
(a pinned key column on an unbound or ambiguously-bound node type, which
`intent_resolved_node_key_column` deliberately admits as a row) or the classpath census cannot type
the slot (a consumer compiled without `-parameters`, a reference resolving no method), the pair
resolves exactly as it did before the predicate existed, with javac's own error as the backstop it
always was. So the gate strictly adds refusals and removes no emission, which is what makes it landable
as a join rather than as a staged flip, and stage 4 states it as the exit condition rather than
discovering it when a fixture without `-parameters` starts failing.

One operand is weaker than it reads, and this item should say so rather than assume it.
`intent_resolved_node_key_column.column_name` hands out a *spelling*, the winning tier's own, and its
comment says outright that whether the name is a column the table actually has "is deliberately not
asked here". Reaching the column's Java type therefore crosses from a spelled reference to a catalog
reading, which is why the candidate relation folds case on both crossings. That crossing is R731's
subject, and R724 is the machinery it names (`intent_stated_key_column_match`, carrying the matched
`sql_column`'s own spelling alongside the arity that says whether the match was unambiguous). Neither
is a dependency: this item reads the candidate relation's payload as it stands. What changes if they
land first is that the type this item refuses on comes off a column decided rather than picked.

The second precondition is what makes the reporter's own code fail rather than quietly keep working,
and that is the intended outcome. Their method takes `String plasstildelingId` and today receives
base64; afterwards the build tells them the key column binds as `Long` and asks for a parameter of
the column's own type. The remedy is one line of Java in their own signature and no SDL change at
all.

The evidence that neither happens today is the classified carrier rather than a reading of the
source. `ServiceCatalog.argExtraction` is the whole story at site 1: it takes the parameter's Java
type and the SDL leaf type and no directive container at all, checks enum parity and wire coercion,
and resolves every scalar to `CallSiteExtraction.Direct`. It cannot see `@nodeId` even in principle.
At site 2 an `ID` field carrying `@nodeId(typeName:)` on an input type backing a consumer bean
generates `java.lang.String title = (java.lang.String) raw.get("title");` inside the `create<Bean>`
helper. That is the shape the reporter reached for as the workaround for site 1 and found equally
inert.

What exists today is the tuple-shaped destination, `JOOQ_RECORD`: a `@service` parameter typed as a
generated `*Record`, and a record-typed member of a consumer bean. `InputBeanResolver` shows the
asymmetry directly. Its `buildJooqRecordLeaf` reads `@nodeId` on a record-typed bean member and
rejects a missing `typeName:` there; `collectJooqBindings` and `buildRecordKeyDecode` do the same on
the record-param axis; and neither has an arm for the single-valued member. That arm is what this
item adds, under the two preconditions above.

**The authored and inferred forms are one destination, not two.** R668's capability is the author
naming a key column as a trailing `argMapping` segment; the rule above reaches the same column by
arity when no segment names it. Both put one key column's value in one slot, so both are
`SINGLE_KEY_COLUMN` with the same column in the key-column child and the same emitted decode.
Whether a segment named it is provenance rather than shape, and it is already answerable by joining
`intent_argmapping_pair`, so this relation does not restate it.

That has a consequence for a verdict already in the tree, and stage 2 owes the edit.
`ArgmappingProjectionDefects`' `BARE_NODE_ID` currently rejects an `argMapping` pair that binds a
`@nodeId` leaf and names no key column, saying the encoded id "would reach the database verbatim".
Under the arity rule that is no longer true for a single-key node type, where the inferred projection
carries the decode out. Its population shrinks to composite keys, and its text moves from "names no
key column" to the arity fact. The type-disagreement half needs no new wording at all: R668's
`KEY_COLUMN_TYPE_MISMATCH` already says "projects '<column>' of '<type>', which jOOQ binds as X, but
the parameter it binds to takes Y; bind a parameter of the column's own type, or project a key column
the parameter can take", and the second clause of that remedy simply has nothing to offer at arity 1.

### The detection is a verdict view, and it partitions the population

Once the two preconditions above are facts, the refusals stop being absences and become statements.
A composite key at a single-valued slot and a type disagreement are both things the store can say,
with the operands to say them. So the detection is not a bare anti-join: it is a view over the
instruction population whose rows are the instructions with no resolution, each carrying which
precondition failed, in a closed vocabulary. That is `intent_argmapping_projection_defect`'s shape
one directive over, and following it is what lets the message text converge on
`ArgmappingProjectionDefects.rejectionOf` rather than be renegotiated.

Written in one pass over the population, with the verdict picked by a `CASE` rather than one
`UNION ALL` arm per verdict, for the reason under "The derivation depth is a design constraint"
above. The verdicts the census yields:

* `KEY_ARITY_EXCEEDS_SLOT`. The node type's key is several columns and the destination or source
  holds one value. Names the type, the count, and the coordinate.
* `KEY_COLUMN_TYPE_DISAGREEMENT`. One key column, and its Java type is not the slot's. Names both
  types and the column, on `KEY_COLUMN_TYPE_MISMATCH`'s existing wording.

**The two views partition the instruction population, and that is the invariant worth stating.**
Every instruction either resolves, with a row in its direction's relation, or is refused, with a row
here. An instruction in neither is not an author error at all: it is a coordinate the model cannot
account for, which means the census missed a shape. Stating the partition is how a gap announces
itself as a defect in this item's own work rather than as a silent pass at a consumer, and it is why
the detection reads the population rather than the captured `@nodeId` rows.

The stand-aside rule above is not a third arm of that partition, and it is worth saying so because it
reads like one. An instruction whose type gate stood aside for want of an operand *resolves*: it emits
exactly as it does today and lands in its direction's relation, with javac as the backstop. It is only
the refusal that requires two known types, never the resolution.

**The refusal view carries no accept line of its own.** Trunk settled this shape one detection over
while this item was being written: `intent_authored_claim_conflict` used to gate on the walk's reach as
a join, and now states the whole predicate and nothing else, its own comment putting the reason plainly,
that "a filter wearing the view's name would substitute one consumer's population for the fact". Each
consumer then applies the population its question needs, and the two differ: the build-error surface
joins `intent_type_domain` because only the emitted surface can fail a build, while the editor's
diagnostic arm reads the rows ungated, a coordinate nothing reaches being where an author most needs the
signal. The same split applies here without amendment. A refused instruction at an unreached coordinate
is still a refused instruction, and the LSP should say so; the build fails on the ones the emitted
surface reaches. The use-site grain does not conflict with this: it is what makes a row answerable at
all, not a filter on which rows exist.

The projector is small and its home exists: a further component on `StoreDetections` beside the two
detection families already there, `AuthoredClaimConflicts` and `ArgmappingProjectionDefects`, decoded
into located `ValidationError`s the same way. One rule, every coordinate, and the same fact available
to the LSP and the MCP context rather than living inside two walk classes.

Both verdicts are `Rejection.structural`. Neither is a deferral: a composite key at a single-valued
slot wants a spelling nobody has minted, and a type disagreement is the author's to fix in one line.
There is no arm in this item that fails while promising an emitter later.

## Stages

Ordered so each stage is separately verifiable, and so nothing ships a rejection ahead of its
replacement.

1. **Argument-site reference-step resolution.** The sibling views over
   `graphitron_argument_reference_step` that `intent_field_reference_step_hop` and
   `intent_field_reference_step_target` have at field site and argument site lacks. Exit: an authored
   argument-site `@reference` path's hops and terminal target are readable from the store, agreeing
   with the field-site views' answers on the same path shape. R723 named this as its own prerequisite
   and gains it.
2. **The instruction population and the two resolution relations.** The population first, all three
   forms of the instruction including the name-carried one that has no captured row; then both
   relations, the use-site grain over `intent_input_occurrence_path`, the four decode destinations and
   two encode sources, and the decode relation's key-column child with its lift plus its hop child.
   The `SINGLE_KEY_COLUMN` destination gains its inferred arm, which is what makes sites 1 and 2
   resolve rather than refuse, and `BARE_NODE_ID`'s text is edited down to the arity fact in the same
   stage. `NodeIdLeafResolver` becomes a reader of the rows rather than the resolver of the facts. Exit: every `@nodeId` shape that generates today has a row naming the
   destination or source it actually uses; a `@service` method whose parameter type matches a
   single-column node key receives the decoded value and never the base64; and the tree's existing
   `@nodeId` behaviour suite stays green without modification. Each relation added here states its
   own inline multiplicity, computed statically from the DDL, and none of them introduces a
   per-verdict or per-destination `UNION ALL` arm that re-joins the driving relations.
3. **The junction chain.** With the relation in place this is the absence of a rejection rather than
   an addition: `validateLift` stops rejecting and its absent lift becomes absent local columns, so
   the chain binds remotely and reaches the hop-general `EXISTS`. Exit: a junction chain returns each
   parent once against PostgreSQL; the identity-carrying chain still binds locally; a condition hop
   still rejects with its own message; each of the four write rails has a stated message.
4. **The read-family encode.** The `CallSiteCompaction` slot on `RecordReadField`, the classification
   arm at each construction site, the `ErrorType` per-field unification with its registration swap,
   and both preconditions stated in the relation. Exit: an `@error` field carrying
   `@nodeId(typeName:)` returns an encoded node id and the reporter's hand-written encoder call sites
   can go; the same holds at the accessor, by-name and typed-column read arms; a composite-key node
   type at a read coordinate is refused with a message naming the count, and a read whose type
   disagrees with the key column's is refused naming both.
5. **The defect view and its projector.** The two verdicts over the instruction population, read by a
   projector into located `ValidationError`s as a further component on `StoreDetections` beside the two
   detection families already there (`AuthoredClaimConflicts` and `ArgmappingProjectionDefects`;
   `ResolvedKeyProjections` is the record's third component and not a detection family). Both arms are
   `Rejection.structural`. Exit: a composite key at a single-valued slot and a type disagreement each
   fail the build naming their own operands; a pair whose key column or whose slot the census cannot
   type still emits exactly as it did before this stage, so the view strictly adds refusals and removes
   no emission, which is R668's stand-aside rule and is checked here rather than assumed; the view
   carries no population filter of its own, the build-error surface applying one and the editor's arm
   reading it ungated; the resolution relations and this view partition the
   instruction population, so no instruction falls in neither; and the same fact is available to the
   LSP and the MCP context rather than living inside two walk classes. The message vocabulary
   converges with `ArgmappingProjectionDefects.rejectionOf`, which is shipped text to read rather than
   a wording to negotiate.
6. **Site 4a, the message and the page.** The auto-discovery rejection separates its two causes; the
   manual page's single-hop claim is corrected; the reverse filter gets the execution-tier row-count
   pin it has never had. Independent of every other stage and the smallest thing in the item.

Stage 6 is independent throughout. Stages 1 and 2 are the spine and nothing after them lands without
them.

## Tests

Behaviour, at the tier that can observe it. No test asserts that a relation agrees with the classified
model: how a fact is sourced is not a behaviour, and a test that knows is a test that breaks when
R682 moves the sourcing.

The strongest guard is already installed and costs nothing. The tree's existing `@nodeId` suite is the
accept set: if a resolution relation fails to enumerate a shape that works today, a currently-green
pipeline or execution test goes red, because the build now rejects a schema that used to generate.
That is what makes a total census safe to attempt.

* **Pipeline tier**, carrying the primary behavioural weight. The junction chain lowering to a remote
  binding with a two-hop path, on both the argument and the input-field surfaces. Each of the four
  write rails refusing a remote-bound junction carrier, asserting the text that rail actually
  produces, as cases in `TranslatedFkTargetRailGatesPipelineTest`. The read-family encode at each of
  the four read arms. And the behaviour the invariant is about, stated as a matrix over the two
  preconditions at a single-valued slot, on both a `@service` parameter and a bean member: a
  single-column key whose type matches, which receives the decoded value and never the base64; a
  composite key, which draws the arity refusal; and a matching-arity type disagreement, which draws
  the type refusal naming both types. The authored-`argMapping` and inferred spellings of the
  matching case draw the *same* resolution rather than different ones, which is the claim that the
  two forms are one destination. The partition against R668 is then over arity rather than over
  spelling, and is pinned as such.
* **Unit tier.** `NodeIdLeafResolverTest` gains the junction-chain case, and
  `multiHopLiftTranslationRejected` is *rewritten* from a rejection assertion to a remote-binding
  assertion rather than deleted, so the fixture that proved the old gate proves the new routing. A
  sibling case pins that an identity-carrying chain still binds locally with its lifted tuple, which
  is the regression this change could plausibly cause and the spike shows it does not.
* **Compilation tier.** Rides `graphitron-sakila-example`. `film_category` already exists in the
  sakila schema and is the natural junction fixture, so this may cost SDL only rather than `init.sql`
  changes.
* **Execution tier**, carrying the row-semantics claim. R57's argument for `EXISTS` is that a
  non-unique path multiplies no rows and a NULL foreign key fails the correlation instead of
  duplicating or dropping. A junction table is the shape where that claim is load-bearing and only
  PostgreSQL can check it, so the shipped assertion is a row count through a junction fixture with a
  parent matching two children appearing exactly once, not the generated SQL text. Site 4a's reverse
  filter gets the same pin, which is the verification the Backlog notes flagged as outstanding before
  calling that shape shipped.

The spike's rendered SQL was the right evidence for a spike and is the wrong assertion to ship:
code-string matching on generated bodies is banned at every tier. Where an emission claim genuinely has
to be made about a rendered body, R668's Done-gate rework is now the worked shape and this item follows
it rather than re-deciding: `TypeSpecAssertions` grew a projected-key section, so the call site asks a
typed question ("does this method materialise the decoded record once?", "is the column named rather
than indexed?") and the rendered spelling lives in the one file the ban allows. The behavioural half of
each such claim moves to the tier that owns behaviour, which is where that rework put "the decode
precedes the write transaction": a malformed or wrong-type node id surfaces as a request-level error with
a null payload and no committed row.

## Risks

* **A resolution the relations fail to enumerate fails a schema that works.** This is the cost of going
  total and it is the item's main risk. It is bounded rather than open: the tree's existing `@nodeId`
  behaviour suite is the accept set, so a missed resolution is a red test during stage 2 and not a
  shipped false rejection. What the guard cannot cover is a shape no fixture exercises, which
  is why stage 2's exit condition is stated over the suite rather than over a count.
* **A consumer that decodes by hand today stops compiling, and that is the point.** The invariant is
  that the consumer never sees the wire format, so a `@service` method or an exception constructor
  typed `String` against a `Long` key column no longer receives base64: it draws a type-disagreement
  refusal, and the remedy is one line in the consumer's own signature. That is a breaking change for
  every existing consumer who wrote the hand decode the report is about, which is most of them, and
  the changelog entry has to say so in those words rather than describing it as a fix. This project
  has no warning severity to soften it, a `ValidationError` carrying a `Rejection` and nothing
  weaker, so "tell the author" and "fail the build" are one act. The mitigation available is the
  message: it names the column, the type it binds as, and the type the slot declares, so the fix is
  mechanical wherever it fires.
* **The site-4b relaxation widens what classifies.** Schemas that fail the build today start
  generating, which is the point, but a schema whose author wrote a junction path expecting the
  rejection now gets an `EXISTS` over a fan-out. R723 is the item that says "this path multiplies" out
  loud, and its rule does not reach here and does not need to; see "Relationship to other items".
* **The write-side diagnostic gets worse before the audit fixes it.** Handled by making the per-rail
  message an exit condition of stage 3 rather than a follow-up.
* **Editing a verdict while its own item is still open.** The arity rule makes `BARE_NODE_ID` wrong
  for single-key node types, so stage 2 edits its text down to the arity fact rather than leaving two
  answers to one question standing. R668's own Done gate sent it back over a test finding with no
  production change requested; that rework has since landed and the item is In Review again, so the
  text is stable to edit while the item is still open, and whoever takes the Done gate should hear that
  its verdict population is shrinking. The mitigation on the substance is that
  the two items are the only producers on either side of the partition: R668's remaining population is
  composite keys at authored `argMapping` sites, this item's is composite keys and type disagreements
  everywhere, and the boundary has a test in the Tests section rather than only an argument here. The
  wording itself needs no negotiation, `ArgmappingProjectionDefects.rejectionOf` being text already in
  the tree.
* **A carrier named for the reporter's subject would inherit the question's shape.** The hazard of an
  item scoped by subject is producing a model type to match: a `NodeIdBinding` or `NodeIdEffective`
  spanning coordinates would take its grain from "whatever the sites needed". The check is the one the
  fact model prescribes: every stage must be able to say what it asserts without naming the reporter
  or this item. Nothing in the design needs such a type, the two relations being named for the
  mechanism, and the review should treat one appearing as a signal the grain slipped.
* **This item lands ahead of R682 on a mechanism R682 will touch.** Deliberate. Doing it narrow means
  someone rewrites a Java sealed result a release later when the planners move to facts. The exposure
  is that stage 2's relation shape has to survive R682's own planner rewrite, which it should, being a
  fact relation rather than a planner.

## User documentation

* `docs/manual/reference/directives/nodeId.adoc` gains the coordinate table this item is really
  about: where `@nodeId` encodes and decodes, what it resolves against, and what happens where it
  cannot. The destination and source vocabularies are the table's own spine, and the page states the
  invariant the table serves, that a consumer neither receives nor supplies the wire format. Its
  Constraints list gains the two preconditions at a single-valued slot, and its `argMapping` bullet
  loses the claim that binding a `@nodeId` leaf without opening it "sends the encoded id to the
  database verbatim", which the arity rule makes false for a single-key node type.
* `docs/manual/how-to/multi-hop-nodeid-filter.adoc` gains the junction shape as a worked example,
  loses its `=== identity-carrying FKs` rejection section, and loses the false single-hop claim.
* `docs/manual/reference/directives/error.adoc` gains the `@nodeId` extra-field case.

## Retired vocabulary

* `NodeIdLeafResolver.LIFT_FAILURE_MARKER` and the "identity-carrying FKs" rejection text.
* The residual "identity-carrying-lift" phrasing in `NodeIdLeafResolver.resolveFkJoinPath`'s javadoc,
  which describes a gate this item removes.
* `NodeIdLeafResolver.JoinPathResult` and its nullable-slot shape, whose `error` slot carries prose
  (it downgrades typed `Rejection`s to their message, only for `resolve` to re-wrap them as
  `Rejection.structural`). The relation replaces it rather than a sealed result succeeding it.
* The `=== identity-carrying FKs` rejection section of
  `docs/manual/how-to/multi-hop-nodeid-filter.adoc`, and the two further statements of the same gate
  on that page: the intro's "deferred to a sibling Backlog item" and the `== Why identity-carrying`
  section's framing of the property as a requirement.
* Three statements of the old one-conjunct discriminator, in `NodeIdLeafResolver`'s own javadoc. All
  are falsified by a junction chain, whose terminal target-side columns *are* the node's key columns
  and which translates nothing; the remote arm is reached because no own-table tuple exists, not
  because a translation is needed. The three are the `FkTarget` seal's "sealed into two arms on the
  positional-correspondence question between the FK's target-side columns and `T`'s `keyColumns`";
  `TranslatedFk`'s "FK target-side columns differ from `T`'s key columns", which appears twice, in the
  seal's arm list and in the record's own javadoc with its "SQL has to convert a decoded key into an
  FK-column value" gloss; and `TranslatedFk`'s
  `@param joinPath single-hop FK path from the containing table to T.table()`.
* `CallSiteCompaction`'s statement of its own carrier population, "Carried by the column-backed output
  carriers (`ChildField.ColumnBackedField`, `ChildField.ColumnBackedReferenceField`)". The read
  carrier is a third and is not column-backed. The neighbouring sentence about arity goes with it: it
  justifies the arity-1 claim by "the carriers' constructor invariant", and the read carrier is not one
  of those carriers, so the refusal is stated in the encode relation instead.
* The previous draft's own claim that the detection is "capture-total and deliberately ungated by walk
  reach", along with its reasoning over `walk_claim_domain_type` / `walk_claim_domain_field`. The
  use-site grain answers the question those relations were being consulted about, and both relations
  have since been deleted outright rather than re-pointed. Nothing in this item's vocabulary survives
  from that draft, so the sweep has nothing to find here; the entry stands so a reader of the earlier
  draft knows the argument was withdrawn and not merely reworded.

`CONDITION_STEP_MARKER` is deliberately *not* retired here, and neither is the rejection it anchors;
see the emitter argument under Design. The Done-gate sweep should read a surviving
`CONDITION_STEP_MARKER` as intact rather than as a missed retirement.

The arm name `TranslatedFk` outlives its own description: the property that selects it is "binds
remotely" rather than "translates". Renaming it is not in this item, and saying so here is the point.
The javadoc rewrites above state the arm's actual precondition so the name is the only thing left
carrying the old reading.

The retirement sweep has one coordination point beyond the tree.
`roadmap/nodeid-filter-per-participant-paths.md` (R676, Spec) names `LIFT_FAILURE_MARKER` as a
constraint its path grammar inherits, so its author has to be told the constraint moved rather than
disappeared. The `@LoadBearingClassifierCheck` mechanism the original lift work paired with that marker
no longer exists (R237 retired the annotations and their audit wholesale), so there is no annotation
obligation; the surviving structural pin is the decode relation plus the pipeline-tier behaviour, and
stage 3 expresses it there.

## Relationship to other items

* **R682** (`planners-read-facts-emitters-read-commands`, Spec) is the architecture this item works
  inside. Its sentence is that capture writes facts, the walk's sealed leaves dissolve into those
  facts, planners read facts and produce commands, and emitters render commands. Making the `@nodeId`
  encode and decode relations and turning `NodeIdLeafResolver` into a reader is that sentence applied
  to one
  directive, ahead of R682 rather than against it. Worth telling that item's author, because the
  `@nodeId` decode and encode facts are one fewer thing its planner rewrite has to source.
* **R742** (`determinism-ratchet-run-count`, Ready) is why this item states its own derivation depth.
  It measured the precedent this plan follows, `intent_argmapping_projection_defect`, at 2149 relation
  instantiations and 24.5 seconds for one read, and diagnosed the cause as H2 inlining views with no
  common-subexpression elimination over a stratum twenty-two views deep. Two of its findings are
  design constraints here rather than context, both stated under "The derivation depth is a design
  constraint": the per-verdict `UNION ALL` arms that carry most of that bill, which this item's defect
  view must not reproduce, and the non-pushdown behaviour of the windowed
  `intent_resolved_node_key_column` this item joins by nature. R742 also mints the materialization
  registry anything here materializes should register into, and proposes the static multiplicity
  metric as a build gate, which this item's new relations are computable under whether or not that
  gate has landed. A notification in both directions: R742 gains relations to price, and this item
  gains its ceiling.
* **R743** (`sdl-fact-gatherer-staged-pipeline`, In Progress) settled the question the previous draft
  of this item argued at length, and has already landed the part that mattered here. Its gatherer now
  owns its assembly stage and closes with a rooted traversal that writes `intent_type_domain`
  (`ClassificationDomainCapture`), and the walk's two membership grains are deleted rather than
  re-pointed, `intent_authored_claim_conflict` having stopped gating on the walk's reach. This item
  reads none of those relations, so nothing here blocks or waits on it. What it does inherit is that
  traversal's own doctrine, stated in its class javadoc and worth quoting because this item applies it
  to the same subject: the node seed is the *declaration* and not the inference, seeding being monotone,
  so "what each member's nodehood amounts to is a question for the readers of the captured node facts,
  one join away". This item is one of those readers.
* **R668** (`nodeid-key-projection-on-routine-params`, In Review) is the nearest neighbour. It makes a
  node type's key columns nameable as a trailing `argMapping` path segment, which is the
  `SINGLE_KEY_COLUMN` destination above. Its production surfaces are in the tree and this item reads
  them; its Done gate sent it back over a test rather than a design, and that rework has since landed,
  so it is In Review again awaiting a third session. The finding was that
  `ArgmappingKeyProjectionEmissionPipelineTest` asserted raw generated-method-body strings, the pattern
  `development-principles.adoc` bans at every tier and `TypeSpecAssertions` exists to replace, with no
  production change requested. So the surfaces below are stable to converge on, and the round trip is a
  live worked example of the rule this item's own Tests section states, which is why that section now
  names the helper the rework grew rather than re-deciding the question. Two of R668's relations do more
  than neighbour this item: it reads
  `intent_argmapping_key_column_candidate` for a key column's Java type and
  `intent_resolved_node_key_projection` for the erased-type-equality rule and its stand-aside
  behaviour, which are the two preconditions' operands and not a parallel invention. What has landed:
  the resolution views, the rejection
  family (`intent_argmapping_projection_defect` plus `ArgmappingProjectionDefects`, six verdicts across
  three `Rejection` channels), the carrier move, and the `@routine` emitter with its execution round
  trip. Outstanding is the `@service` emitter, a named empty slot rather than an open question:
  `ArgmappingProjectionDefects.EMITTING_SITES` holds `ROUTINE`, `FIELD_CONDITION` and
  `ARGUMENT_CONDITION`, with its javadoc stating that "`SERVICE` joins when its emitter lands".
  `depends-on:` stays empty; the field means "must ship first" and renders as *blocked by*, and this
  item reads R668's shipped surface rather than waiting on it.

  R668 also names a coordinate it cannot reach and states why, which under this item's total rule
  becomes this item's to answer: the input-field `@condition`. Its pair rows are keyed by the input
  type and input field while the condition row rendering them is keyed by the consuming output field,
  so "the coordinate never matches and the lookup misses by construction rather than by omission".
  Under the use-site grain above the two coordinates are one row, so this is a keying fix rather than
  an emitter, and it is why the census counts that coordinate as answerable rather than deferred.
* **R57** (Done, see `roadmap/changelog.md`) shipped the single-hop translated-FK `EXISTS` and filed
  multi-hop translated paths as deferred. The junction case is that deferral. Its reasoning that
  `EXISTS` is the semantically right shape rather than a convenient one is the argument stage 3
  inherits.
* **R705** (`condition-join-hops-in-reference-filter-paths`, Spec) is R57's sibling deferral for the
  other rejected hop kind. The two are adjacent in the classifier and far apart in the emitter: both
  gates express themselves through the decode relation, but only the lift gate can relax without the
  reach carrier being widened first, and that widening is R705's own work (retiring `FkHop`,
  `FkHop.narrow` and `ConditionCommands.narrowPath`). R705 does not relax the marker either: its
  targets are the plain-`@reference` filter rejections in
  `FieldBuilder.referenceFilterConditionJoinRejection` and
  `GraphitronSchemaValidator.validateInputColumnBackedReferenceField`, and its non-goals keep an FK
  path required where a `@nodeId` leaf is involved. This item lands first and lands the relation; R705
  inherits it. Worth telling that item's author, because its body cites `NodeIdLeafResolver`'s
  FK-only-at-every-position rule as a standing fact and after stage 2 that rule is stated in a
  different place.
* **R676** (`nodeid-filter-per-participant-paths`, Spec) states that its path grammar inherits "the
  identity-carrying lift validation ... the `NodeIdLeafResolver` arms behind `LIFT_FAILURE_MARKER`".
  Stage 3 removes that gate, so its author needs to know the constraint moved rather than vanished.
  A notification, not a dependency in either direction.
* **R723** (`reference-path-fanout-verdict`, Spec) is the item that warns when a `@reference` path fans
  out, and it gains something here. Its own scope section names authoring sibling views over
  `graphitron_argument_reference_step` as the prerequisite for covering argument-site paths, and
  declines it; stage 1 authors them. In the other direction R723's rule does not reach this item's new
  population and does not need to: the defect it names is duplicate rows in a *projection*, and a
  filter path lowers to a correlated `EXISTS`, which is the shape R57 argued does not multiply rows.
  What R723 keeps is a documentation obligation it already accepted, that a quiet build is not a
  statement about filter paths.
* **R691** (`multi-hop-nodeid-filter-single-fk-claim`, Backlog) is why site 4a reads as unsupported.
  The manual still tells the reader that a single direct foreign key never produces a subquery and that
  the translated emission "is not yet shipping", both of which R57 made false. **Absorbed**: this item
  edits that page in two stages already, so the correction rides along and R691 is discarded at the
  Done gate rather than sending a third pass over one file. Its `status: Backlog` file is a tombstone
  in the meantime.
* **R731** (`resolved-key-column-forwards-a-spelling`, Backlog) is the weakness under this item's type
  precondition, and naming it here is how this item avoids pretending otherwise.
  `intent_resolved_node_key_column` answers with the winning tier's *spelling*, not a resolved column,
  so every reader that has to match against it folds case at the crossing. This item's type refusal
  reads a Java type reached through exactly that crossing. Not a dependency, and not a blocker: the fold
  is correct today and R668's candidate relation already performs it on both sides. What R731 would
  change is the payload, so the type comes off a column rather than a name.
* **R724** (`stated-key-column-match-states-its-arity`, Ready) is the machinery R731 names, and it
  matters here for one reason. Its subject is `intent_node_metadata_defect`'s `KEY_COLUMN_UNRESOLVED`
  arm spending an ambiguity silently: on a table with two columns differing only by case, which one
  resolved the entry is decided by whichever the join reached. A type refusal minted by this item on a
  silently-picked column would name a type the author cannot check. R724 lands the arity, so if it goes
  first this item refuses on a column decided rather than picked; if it goes second, nothing here is
  wrong, the ambiguity being upstream of the operand rather than introduced by it. A notification in
  R724's direction: this item adds a consumer that turns that pick into a build failure's text.
* **R262** (Done) rejects `@nodeId` on a non-`ID` coordinate at validate time: the precedent for the
  rejection half, and the reason its vocabulary is already established. This item extends the same
  judgement from the slot's *type* to what the slot can *hold*.
