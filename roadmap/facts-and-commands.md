---
id: R549
title: "Facts and commands: grain-first hierarchies and the three command relations"
status: In Progress
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-07-27
last-updated: 2026-07-28
---

# Facts and commands: grain-first hierarchies and the three command relations

This item is a **programme**, not a single deliverable, in the sense R117 uses the word: it frames a
direction, states the invariants that make it falsifiable, and lists slices that each ship on their own.
It sits under R333, which owns the model itself; this item owns the reframing R333's dissolution implies
once you look at where the hierarchies actually sit. The measurements it rests on, with their method and
a re-derivation script, are in `roadmap/audits/2026-07-26-fcis-command-layer-distance.md`.

## The reframing in one paragraph

There is no intermediary command model to design. Graphitron's model already contains its commands, as
sealed hierarchies, and the reason they do not read as commands is that four different *kinds* of
hierarchy are fused at one grain: the per-coordinate leaf. Separate them by how a row comes to exist and
by their cardinality against the field coordinate, and the emit turns out to be three relations, all
three of which already exist somewhere in the tree under a different name. The work is labelling,
re-homing, and grain repair, not construction.

## Four kinds of hierarchy, and the test that sorts them

The discriminator is not subject matter, it is **how a row comes to exist**: walked or minted.

| kind | test | examples |
|---|---|---|
| walked facts | read off the SDL or catalog by a traversal | `Source`, `Target`, `TargetShape`, `TenantBinding`, `ScalarResolution`, `ProducerBinding`, 19 of `GraphitronType`'s 24 permits |
| resolved views | a coalesce or inference over facts, with no walk of its own | `JoinStep` / `On`, the `reference` resolution, `resolvedTable` |
| commands | minted at emit grain from facts | `Operation` (17 permits), `BodyParam`, `DmlReturnExpression`, `CallSiteExtraction`, `OrderBySpec`, `RowsMethodShape` |
| the walk's error channel | facts minted by the gathering pass rather than read off a traversal, the `Err` arm of classification | `Rejection`'s 13 own leaves plus the nine seals `AuthorError` delegates to: `PivotError` (12), `ServiceMethodCallError` (7), `UpdateRowsError` (6), `ErrorChannelWalkerError` (5), `ReflectionError` (4), `DeleteRowsError` (3), `WireCoercionError` (2), `ServiceCarrierShapeError` (2), `MutationTableArgError` (1) |

`Operation` is the proof that commands are already written: R333 describes its members as *minted by
triggers* (a table-bound return type mints `select`, pagination args mint `paginate`, `@condition` mints
`condition`, `join` is minted by the `reference` fact), which is derivation at emit grain, not a fact
anybody walks for. One entry in that list is target vocabulary rather than tree: `Operation` has no
`condition` arm today, the filter surface riding as `WhereFilter` components on its `Fetch` and
`Paginate` arms (R552's model correction), and nothing in this programme turns on whether R333 later
promotes it. The error-channel kind is a genuine fourth at 55 leaf records across `Rejection` and the nine
seals its `AuthorError` arm delegates to (counting rule: concrete `record` arms in `Rejection.java` plus the
nine delegated seal files, measured 2026-07-28), far larger than `Operation`, but it is still inside the
fact base: the development principles say rejections are facts too, located violations asserted once and
rendered into views. What distinguishes the kind is provenance (minted by the gathering pass, not read off
SDL or catalog), not membership, and its grain is the located violation keyed by location plus code. Stated
that way, invariant 2's grain-and-home rule covers all 55 leaves with no exemption.

## Grain decides what a leaf can hold

A leaf is one row keyed by coordinate, so a hierarchy's cardinality against the coordinate decides
whether a leaf can hold it at all. Every strain point in the current model is a 1:N or type-grain family
stuffed into a per-coordinate row.

| grain vs coordinate | leaf-able | families | tell in the code |
|---|---|---|---|
| 1:1 | yes, and should stay a sealed record | source, target, tenancy binding | `Source`, `Target`, `TenantBinding` |
| 1:N | no, must be a relation | operations, join hops, conditions, arguments, pivot slots | `List<JoinStep> joinPath()`, `List<WhereFilter> filters()`, `PivotSpec.slots()`, `callParams()`, and ten more list accessors |
| type grain | belongs to the type, not the field | the `$fields` fold, input record shape, node identity | `$fields` is type-granular and a fold; `EntityResolution` rides the `entitiesByType` sidecar |
| 0:1, two populations | authored and inferred, resolved by a view | reference, defaults, ordering | `reference` is authored `@reference` *or* inferred unique FK |

Node identity is the instructive row-3 entry, because it is the one a coordinate reader reaches for first
and gets wrong. `EntityResolution` is keyed by type name on the `entitiesByType` sidecar, not by coordinate,
and its `alternatives` is a list of `KeyAlternative` (including the synthesised `NodeId` arm), so it is
type-grain *and* 1:N within the type. That is the same fact the keystone spends later: node-id-ness is a
wrap applied at the fetcher value, so it has no projection footprint at all. (The `NodeMetadata` record is
not this fact. It lives on `catalog/CompletionData` as a `Map<String, NodeMetadata>` for the language server
and the model-context projection, which is a downstream view, not a hierarchy in the fact base.)

This also explains why the leaf model reached "ok, but not 100%". A leaf names a point in a product
space, which is strictly *better* than a relation while the families co-vary (one name, compile-checked
exhaustiveness, cheap dispatch) and a cross-product bomb the moment they vary independently. R432
collapsing `SplitTableField` and `RecordTableField` (differing only by source shape, so correlated and
merely over-split) and R501 minting three pivot leaves (delivery varying independently) are the two
outcomes of the same rule. The corollary is conservative: **split a family out of a leaf on measured
independence or measured multiplicity, never on aesthetics.**

## The three command relations

| relation | key | where it lives today |
|---|---|---|
| global commands | unit kind | the 33 `write(...)` calls in `GraphQLRewriteGenerator.runPipeline`, including an inline `federationLink && usesOneOf` gate |
| type-keyed commands | `(typeName, unitKind)` | roughly 11 generators that each loop the schema asking "should I emit my kind for this type", with the naming vocabulary already centralised as data in `compile/GeneratedUnits` (`typeClass`, `fetchers`, `conditions`, `inputRecord`, `schemaShape`, plus `singleton` / `rootUnit` for globals) |
| coordinate-keyed commands | `(coordinate, operation)` | `Operation`'s minted arms, plus `MethodCommandRegistry`'s four-string records minted during rendering |

The type-keyed relation is derived, never independently walked: a `(typeName, unitKind)` row is a fold
over coordinate-grain facts (emit a conditions class for a type exactly when some coordinate on it carries
a condition operation), so slice 3b's producers are GROUP BYs over the coordinate relation. Re-asserting
membership with a per-kind predicate would relocate those loops rather than dissolve them, the same trap
the per-family recipe's honest note names for leaf dispatch.

**Sizing that population, because the obvious number over-counts it.** 24 `generate` entry points in
`generators/` take `GraphitronSchema`, but that is invariant 1's count and not this relation's. They sit in
17 files; three of those never loop at all (`GraphitronFacadeGenerator`,
`GraphitronDevExecutorGenerator`, `EntityFetcherDispatchClassGenerator`), and several of the rest loop to
build **one** aggregate unit rather than one per type: `GraphitronSchemaClassGenerator`,
`NodeIdEncoderClassGenerator` and `ErrorMappingsClassGenerator` are `GeneratedUnits`' `singleton` /
`rootUnit` schemes, which is slice 1's global population by this item's own vocabulary. What is left is the
per-type-emitting families, around 11, and those are slice 3b's. Sizing 3b off the 24 would have it
re-migrate what slice 1 already owns.

The coordinate-keyed relation holds two kinds that must not be conflated. A **projection command** returns
the select list for one projection unit. A **launcher command** owns a query: it composes a projection
call with its own extras and adds the FROM, joins, WHERE, ordering, and windowing. The discriminator
between them is what a column's presence depends on:

> **Projection contributions are gated on client selection. Launcher extras are entailed by the
> mechanism.**

`__idx__` (scatter correlation), `__rn__` (the window ordinal), the seek columns cursors are built from,
and the `__typename` literal are all needed whenever their mechanism runs, so they belong to launchers,
which is where they already are. Everything a projection command emits is there because the client asked
for it.

Commands nest: a projection command calls other projection commands (nested units, inline table children),
so *complete* means the core decided everything, not that a command contains everything inline. R333's
closure invariant is the right test for that, and an inlining rule is not.

Three observations follow that the plan leans on. First, the emit's identity scheme is already data, in
`GeneratedUnits`, but it lives in `compile/` because the dependency graph was the first consumer to need
it that way. Second, that makes three separate copies of emit knowledge outside the emit
(`CompileDependencyGraphBuilder` duplicating the call graph, `MethodCommandRegistry` auditing the names,
`GeneratedUnits` holding the vocabulary), each built where its consumer sat rather than in the core.
Third, `GraphitronType`'s five synthesised permits (`ConnectionType`, `EdgeType`, `PageInfoType`,
`FacetsType`, `FacetValueType`) are command *outputs* stored in the fact map. The model admits it for the
facet pair, whose `NO_CASE_REQUIRED` exemptions state that no SDL declaration exists to carry a
`@classifiedType`; the connection triple carries no exemption because the structural hand-written form also
exists in source SDL (the corpus's connection example carries `@classifiedType` on it), so those three are
dual-provenance, synthesised by `@asConnection` or walked when authored structurally, and it is their
synthesised population that is a command output.

## The keystone: the projection command

The `$fields` method is the keystone, and designing its command first is what validates or breaks
everything above. Five properties make it so: it is the only command whose body aggregates contributions
from other keys; it is the hub of the emitted call graph (`fetchers.X` to `types.Y#$fields`, and type
classes calling each other); it straddles the static/runtime line, since the arm set is closed at build
time while which arms fire is a per-request selection value; its demand computation is already duplicated
by an independent checker that throws at generation time; and its grain is the one the model cannot
currently express.

```java
record ProjectionCommand(
    UnitRef unit,                      // types.Film, or a nesting type's own unit
    TableRef table,                    // the table whose columns the contributions name
    List<Contribution> contributions)  // every one gated on client selection
{}

sealed interface Contribution {
    /** Terms this unit builds from its own table context. */
    record Project(String field, List<SelectTerm> terms)              implements Contribution {}
    /** Fields another projection unit decides. */
    record Call(String field, UnitRef callee, CallWrap wrap)          implements Contribution {}
}

sealed interface CallWrap {                            // how the callee's fields arrive
    record Splice()                     implements CallWrap {}  // same row: the callee's fields become mine
    record Multiset(JoinRef join, Arity arity) implements CallWrap {}  // other rows: one multiset field
}
```

**Two kinds, because provenance is not a distinction.** Read off what the current arms emit: a scalar column
adds `table.TITLE`; a composite adds N of those; a direct `@reference` adds `table.COL.as(alias)`; a remote
one adds `DSL.field(DSL.select(ref.COL)...).as(alias)`; a computed field adds `Helper.method(args).as(...)`;
a pivot adds a multiset of `max(...).filterWhere(...)` terms; a batched or `@service` child needs its
correlation columns added. Every one of those is "add these terms when this field is selected", differing
only in how the term expression is built. The discriminator for `Call` is stated precisely: **the callee is
a projection unit**, whose contribution list merges into this one. "Another unit decides the terms" is not
it, because a scalar `@reference` also reaches rows this unit does not own yet names no unit (see below).
Modelling a correlation key, a node key, a service key and a
plain scalar as separate contribution kinds records *why* a column is wanted, which nothing downstream needs.
The test generalises: an arm split earns its place by counted downstream consumers of the distinction,
never by provenance alone, and the condition family is the worked counter-example, where three consumers
(suppression semantics, emit ownership, edge typing) make a two-arm split right (R552).

**Unit identity is typed.** `UnitRef` is minted only by the plan's naming vocabulary (`GeneratedUnits`, once
slice 1 moves it out of `compile/`), never parsed from a string, so a `Call` naming a unit no producer
committed is unrepresentable rather than a test failure. That is the lesson of retiring
`MethodCommandRegistry`'s four-string record: reproducing it as a record of three strings would reproduce
the diagnosis, and invariant 4's oracle then narrows to the cross-family names the type cannot yet carry.

`Splice` versus `Multiset` is likewise not provenance: it is whether the callee projects the **same row** (a
nesting unit, so its terms merge into this list) or **other rows** (a child table, so its terms sit inside a
correlated subquery). Row identity is a structural fact, and it is the only axis a call needs.

**No unconditional rows.** The "always included" category in today's emit is an artifact, not a
requirement. The selection switch has arms for exactly the seven leaf kinds that project data of their
own; `BatchedTableField`, `BatchedLookupTableField` and `@service` children have **no arm at all**, because
from the switch's point of view they project nothing. When it turned out they do need their correlation key
in the parent SELECT, the only available home was an unconditional append at the end of the method. That is
the origin of the whole category, and of the chain that widened it (R425 force-included, R426 promised the
full row, R436 built the reserved-alias scheme, R516 narrows it back). The missing arm is an ordinary `Project`
contribution carrying the correlation columns: project them when the child is selected, project nothing when
it is not. One constraint makes that arm safe to trust: **its column list is read from the same accessors the
extraction emitter consumes** (`BatchKeyField.sourceKey()`, `ParentRowDemand.parentRowColumns()`), so supply
and demand are single-sourced rather than derived twice and compared.

Consequences, all of them things that stop existing rather than things that get built:

- The required-projection walk (`TypeClassGenerator.collectRequiredProjection`) has nothing left to
  discover, since no demand crosses a key without travelling through a call.
- `ParentProjectionContainmentCheck` loses its subject, for the single-sourcing reason and not the
  exhaustiveness one. The check cross-checks two *independent* derivations of the key columns, and its
  javadoc states the independence as a hard requirement rather than a preference, with the reason: the
  shipped bug was a pattern-match omission *inside* the projection walk, so a requirement side that shared
  that traversal would reproduce the omission on both sides and pass green over the exact bug family the
  check exists to catch. Exhaustive dispatch does close that particular omission, which is invariant 5's
  job, but it is not what retires the check, because a coverage check cannot see a present arm reading the
  wrong columns. What retires it is the constraint above, that the correlation arm and the extraction
  emitter read the same accessors, so only one derivation remains and there is nothing left to compare.
  That is deliberately the opposite of the check's independence requirement: single-sourcing is safe only
  because supply and demand become the *same read*, not two reads that happen to agree.
- Over-projection goes away as a runtime effect. A query selecting only `title` on a type with three split
  children currently projects the union of all their keys.
- Node key columns need no forcing. Node-id-ness is a wrap applied at the fetcher value, not in the SELECT
  ("Compaction does not affect projection: the SELECT terms are the same columns in both cases"), so
  selecting `id` projects those columns through the ordinary column arm and not selecting it needs nothing.
- `@lookupKey` has no projection footprint at all. Its work is the VALUES join, emitted by
  `LookupValuesJoinEmitter`; a lookup *field* projects because it is a field, not because of the argument.

**The gate is the field, not the result key.** A command is static, and result keys are per-request values:
the client mints them (`recent: reviews(...)`), so no build-time record can carry one. What a contribution
carries is the SDL field whose selection gates it; at run time the emitted switch matches on that field name
and iterates the selected occurrences, each occurrence keyed by its result key. That is exactly the shape
`$fieldsGrouped` has today (arms per field, iteration per result-key bucket), so the split is a description,
not a change: field names are the command's vocabulary, result keys are the runtime's.

**Every contribution adds fields to this unit's projection when its field is selected.** That is the
one thing all of them do, and a `Call` is not an exception: it also lands a field, the difference being that
the field is a subselect rather than a column on this table. What varies is only where the field expressions
come from:

| contribution | fields it lands |
|---|---|
| `Project` | one per term, each an expression over this unit's table context |
| `Call` + `Splice` | the callee's fields, added as they are, since a nesting unit projects the same row |
| `Call` + `Multiset` | **exactly one**: a multiset subselect whose inner select list is the callee's fields |

Two details from the current emit that the wrap axis has to respect. `DSL.multiset(...)` is used
**uniformly for both cardinalities**, deliberately, because jOOQ 3.20's `DSL.row(Collection)` flattens
nested rows; single cardinality caps the subselect with `.limit(1)` and unwraps the `Result` to its first
record at read time. So cardinality is a slot on the wrap, as the sketch's `Arity` on `Multiset` has it (it
decides the limit and the read) and never a
different wrap, and nobody should later "optimise" a to-one into a row. And a scalar `@reference` is *not* a
call: it emits `DSL.field(DSL.select(terminal.COL)...).as(alias)`, a scalar subselect over one column with
no callee unit and no edge, so it is a `Project` term whose expression happens to be a subselect. The term
algebra therefore covers columns, scalar subselects, aggregates, and helper invocations, with aliasing a
slot on the expression arms rather than an arm of its own (see the alias rule below).


**Aliases are mostly inherited complexity, and the consumer decoupling lets us drop them.** The consumer of
a projected column is a generated DataFetcher, and several fetchers reading the same projected column is
fine, so a column does not need a per-occurrence name. Sorting today's alias uses by whether they carry
weight: a plain column is already projected unaliased; a **standalone `@reference` whose start table equals
its target** aliases the parent's own column as `__rk_<resultKey>` purely so its reader matches the subquery
shape's reader, which is inherited, not load-bearing; a multiset, scalar subselect, aggregate or helper call
genuinely needs *a* name, since there is no column identity to read by; and two occurrences with different
arguments (`recent: reviews(first: 5)` versus `old: reviews(first: 1)`) genuinely need distinguishing,
because the expressions differ. The one further alias class today's emit had, the reserved namespace
carrying a parent's full row for typed-record `@service` keys, is already deleted and needs no verdict
here.

> **Alias a term only when it has no column identity to read by, and alias by result key only when its
> expression is occurrence-dependent.**

That drops the reader-uniformity alias, because a command tells each end which shape it is rather than
forcing one convention on both, and it keeps aliasing out of the term algebra as an arm: the slice-3 term
type carries the alias as a slot on expression terms, never as a term kind of its own.

**Deduplication then becomes safe, with a stated scope.** A term readable by column identity is added **once**
no matter how many selected occurrences reference it; an occurrence-dependent term is added per occurrence.
That is a property of the term arm, decided in the producer, not a runtime name check like the connection
helper's `selectedNames.contains(...)`. The scope limit comes from the emit: result-key bucketing already
unions sub-selections per result key, so `a: reviews { id }` and `b: reviews { text }` yield two multisets
even with identical arguments, and deduplicating those would mean comparing merged sub-selections at runtime.
So the win is column terms, which is the common case, and no attempt should be made to dedupe delegated
fields.

**The rule that keeps this collapsed**, because the risk is that the zoo reappears one level down:

> **Term arms are SQL shapes, never reasons.** Two contributions that render to the same SQL shape use the
> same arm.

That keeps `SelectTerm` small (a column, an aggregate, a scalar subselect, a helper
invocation) and rejects a proposed `CorrelationKeyTerm` on sight, since it renders `table.COL` exactly as a
plain column does.

**Edges are a derived view over the command, not a top-level list.** A `Call` names a callee unit, but a
helper invocation inside a `SelectTerm` is also a reference to a method we emit, so an emitted-method
reference is one slot that appears on both sides of the `Project`/`Call` split, carried once wherever a
contribution or term reaches outside this unit. The edge set for closure and for the recompile-graph
projection is then a **total switch over the `Contribution` and term arm sets with no default arm**, the
same compile-checked projection seam `CatalogBuilder.projectFieldClassification` is the exemplar for: a new
arm carrying a method name fails compilation until the edge view covers it, rather than being silently
missed by a walk somebody remembered as small. The rule is stated once and holds per command kind: every
command kind owes its own total-switch edge view (the launcher's projection `UnitRef` and its condition
reference are slice 3c's instances, and slice 3d's authored `@condition` callees teach the view the
emitted-versus-external split), and slice 7's recompile graph is the union of the per-kind views.
Slice 8's corpus projection follows the same rule.

**`__typename` is not a contribution.** The polymorphic path appends it after calling the participant's
projection, so it is a launcher extra alongside `__idx__` and `__rn__`, and it belongs nowhere in this
command.


**Return, do not mutate.** A nested projection takes the scoped selection and returns its contributions;
the caller merges. Mutating a passed accumulator would make the callee's contract include the caller's
state and make call order significant, in the one place where nothing else is, and it would defeat the
independent assertability that motivates cutting the seam at all.

**Every projection unit is caller-parameterised.** A nesting type shares the parent's table alias by
definition: if it had its own table instance it would not be a nesting type. Since table-backed units also
take their instance from the caller (`$fields(sel, table, env)`), there is no "owns its alias" case and no
alias axis on the command. What distinguishes a nesting unit is that it has no key of its own.

**Nesting types become projection units, keyed by anchor and prefixed by it.** Giving a nesting type its own
unit collapses the contribution key from `(host, pathFromHost)` to `(anchor, typeName)`: the path drops out,
the host does not. That retires the depth-suffixed generated locals that exist only to dodge JLS shadowing
when everything inlines into one method, and hands the dependency graph a node and edges for free, which is
the grain R459 and R462 both stumbled on.

The host stays in the key because **sharing one nesting type across hosts with different tables is a
supported shape today, not a hypothetical.** `GraphitronSchemaBuilderTest.NestingFieldCase.SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE`
reaches one `FilmDetails` from `Film` (`film`) and `FilmList` (`film_list`), and
`ChildField.NestingField`'s javadoc states both the support and the mechanism: the parent's table varies
across reuse sites, the children read by column name on the generic `org.jooq.Record`, and the
domain-return identity is that generic `Record` precisely so "any nesting reuse-site agrees on" it. Today's
emit dodges the question by inlining, so no unit exists to be ambiguous. Promoting nesting types to units
creates the question, and the answer is the key: **one unit per `(anchor, nesting type name)`, with the
generated class name prefixed by the anchor type.**

Three consequences worth stating, because each is a decision an implementer would otherwise have to make
mid-slice:

- **The prefix is the anchor, not the immediate parent.** Nesting is a pass-through (`env -> env.getSource()`),
  so every nesting descendant reads the table-backed anchor's row, and the anchor is what fixes the emitted
  parameter type. At depth 1 anchor and parent coincide; at depth 2 and beyond only the anchor disambiguates,
  since two anchors reaching the same parent-child nesting pair would collide again under an
  immediate-parent prefix.
- **One unit, one table becomes structural rather than checked.** The anchor determines the table class, so
  a unit cannot carry two `TableRef`s by construction. The plan-build rejection on differing `TableRef` for
  one unit stays as a backstop, but it is now a genuine invariant guard rather than a bet on a shape being
  absent, and it can no longer fire on `SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE`.
- **The prefixed name needs a collision verdict.** `<Anchor><Nested>` can collide with an authored type name
  (`Film` plus `Details` renders the same class as an authored `FilmDetails`), and `GeneratedUnits` has no
  prefixed-unit scheme today. Slice 3.1 owns the naming, and the rejection vocabulary already exists:
  `Rejection.InvalidSchema.CaseFoldCollision` is the sibling verdict, and R475 is the precedent for a
  generated-name collision being a typed rejection rather than a silent overwrite. A separator that cannot
  appear in an SDL type name is the cheaper answer if one is available in a Java identifier.

The invariant that makes all of this work is **locality: a projection method never looks beyond its own
nesting level.** Its own level's contributions are columns and calls; anything deeper is another unit's
business, reached by a call and returned as a list. That is what makes a projection unit self-contained, and
it is the property to state and hold rather than any claim about how many parents reach a nesting type.
`ColumnRef` carries no table, so contributions name columns resolved against whatever instance the caller
passes.

One residual for slice 3.1 to confirm rather than assume, on the same footing as slice 3.3's directive
question. A `@pivot` field's projection type is registered as an ordinary `GraphitronType.NestingType`, and
its columns come from the attribute table rather than the anchor's, so a type reached by both a nesting edge
and a pivot edge would want two tables under one `(anchor, typeName)` key. `MixedSourceReachIndex` cannot
tell the two edges apart, deliberately: `GraphitronSchema.reachableSourceShapes` maps every `NestingType` to
`NESTING_RECORD` alone, and the pivot edge "contributes the identical `NESTING_RECORD` reach to the union".
So the reach axis will not answer this, and the backstop above is what surfaces it. Confirm at
implementation whether the double-reach shape is author-reachable; if it is, the pivot-reached projection is
its own unit kind keyed on the attribute table, not a nesting unit.

**Polymorphic projection needs no folding in.** `MultiTablePolymorphicEmitter` already emits
`Type.$fields(PolymorphicSelectionSet.restrictTo(env.getSelectionSet(), "Film"), t, env)` per participant
and states that the discriminator's "real column is projected by the participant `$fields`". So the
polymorphic path is a launcher that consumes projection commands, and the only thing the contribution set
owes it is nothing at all, since `__typename` is appended by the launcher. Both scoping adapters (`restrictTo` by concrete type,
`SelectionOccurrences.mergeByResultKey` by depth) sit at the call site and converge on the same callee
input, which is also why the `$fields` family collapses to one method: two public overloads, one per
adapter, plus the private `$fieldsGrouped` they both delegate into.

**Pagination is already outside `$fields`, and correctly so.** `ConnectionHelperClassGenerator` computes
`selectFields` as "selection ∪ extraFields, name-deduped", a runtime union of what the client selected with
what cursors need. Four sites already append to a projection's output this way (`__idx__` in the scatter
path, `__idx__` plus `__rn__` in the ROW_NUMBER envelope, `__typename` in the polymorphic path, `multiset`
wrapping in the inline paths). The runtime dedup is also evidence that overlap between the two sets is a
name collision to reconcile at runtime, not a build-time invariant to enforce, which is a second argument
against the demand walk.

**The launcher is the other half, and slice 3c proves it.** This command decides a select list; something
else decides the `select(<unit>.$project(...)).from(t).where(...)` around it, appends the extras above, and
picks how the query is invoked. Slice 3 states that boundary and slice 3c tests it against the root SELECT
family, though only for the one append site that family reaches: the connection root's extra-ordering
columns. `__idx__` and `__rn__` are `SplitRowsMethodEmitter`'s and arrive with the child path in slice 5,
`__typename` belongs to the polymorphic launcher, and multiset wrapping is projection-side rather than a
launcher extra at all. One populated mechanism still falsifies the boundary if it fails to decompose, and
designing the projection without ever writing its dual would leave the claim untested entirely, which is
the argument for taking the launcher second rather than eventually.

## Production path and coexistence

**Where commands come from.** The producer/renderer seam already exists as a function boundary.
`TypeClassGenerator.generateForType(schema, typeName, outputPackage)` partitions `schema.fieldsOf(typeName)`
into seven per-leaf-kind buckets, computes the required projection, runs the containment check, and hands
all of it to `buildTypeSpec(typeName, table, sevenLists..., outputPackage)`. Everything above that last call
is production; the last call is rendering. So the keystone is mostly a change of shape: the seven positional
buckets become one ordered `List<Contribution>`, the required projection becomes a gated `Project` arm, the
containment check goes away, and `buildTypeSpec` becomes `render(ProjectionCommand, RenderContext)`. That
is the split line between the keystone's two halves: the bucket-to-contribution-list reshape and the
`buildTypeSpec` rewrite are slice 3.1, the gated correlation arm and the two deletions it enables are
slice 3.2.

**The dispatch partition moves with the dispatch.** `TypeFetcherGenerator.PROJECTED_LEAVES` is not the
`$fields` arm set: its javadoc states the narrower semantic, leaves whose projection is emitted inline *and*
for which the dispatch switch emits no fetcher method. The `$fields` switch covers seven leaf kinds, but two
of them (`ColumnBackedField`, `ComputedField`) also get fetcher methods and so sit in `IMPLEMENTED_LEAVES`,
and `PivotSlotField` sits in `PROJECTED_LEAVES` with no arm of its own because its projection rides the
pivot arm's emission. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` plus
`ValidateMojo` rest on the four-way partition staying exhaustive and disjoint. Slice 3.1 relocates the arm
set into a producer that the generator package cannot import, so leaving the set behind as a hand-maintained
restatement would be R268's bug class verbatim. The slice re-sources the bucket instead, and the derived
fact must carry both halves of the semantic or it breaks the disjointness it feeds: a leaf is projected
exactly when the producer mints a contribution for it (directly, or through the contribution of the arm it
rides, which is how `PivotSlotField` stays covered) **and** the fetcher dispatch mints nothing for it.
Deriving from contribution coverage alone would put `ColumnBackedField` and `ComputedField` in two buckets
at once. The partition test reads the derived fact, and a leaf the producer cannot yet mint for surfaces as
a validate-time deferred rejection, not a producer-side throw.

Two authored doc surfaces name that partition and go false the moment it re-sources, so slice 3.1 updates
them in the same commit: the "Rejections: validator mirrors classifier invariants" section of
`docs/architecture/explanation/development-principles.adoc`, which enumerates the four-way partition and
justifies the enumeration by the test that pins it, and `docs/architecture/reference/code-generation-triggers.adoc`,
whose inline-projection bullet names `TypeClassGenerator.$fields` and `PROJECTED_LEAVES` directly. Both are
the principles doc's own rule about unguarded inventories applied to this change; the `testing.adoc` rubric
row below is the third.

The producer reads exactly where the generator reads today, so **slice 3 needs no fact walk**. Slice 4 later
re-sources the producer onto fact relations and the renderer never notices, which is what makes 3-before-4
safe rather than merely convenient. The reshape also fixes emit order by accident: today's arms are grouped
by leaf kind because the buckets are, whereas one ordered list makes SDL declaration order natural, and
deterministic emit is what the dev loop's changed-set detection wants.

**Produce eagerly, render second.** The whole command relation materialises before any rendering. Lazy
per-unit production would reintroduce the pull this design exists to remove, and eager production is what
makes the relation assertable by the corpus, projectable into the recompile graph, and countable for the
ratchets.

**Commands are not stored on `GraphitronSchema`.** They are a derived artifact of a distinct core step
(working name `EmitPlan`). That keeps the fact/command distinction structural rather than conventional,
keeps the fact store from growing an emit concern, and leaves the language-server and model-context
projections untouched, since they read the model and have no use for commands.

**Coexistence: one new step, and `runPipeline` becomes the scoreboard.**

```java
var bundle = GraphitronSchemaBuilder.buildBundle(attributed, ctx);
var schema = bundle.model();
var plan   = EmitPlan.produce(schema);                                        // new core step

write(TypeClassGenerator.render(plan.projections(), ctx),      "types",      log);  // migrated
write(TypeConditionsGenerator.generate(schema, outputPackage), "conditions", log);  // not yet
```

Migrated and unmigrated families sit in the same list, so migration state is readable off the call sites:
anything still passing `schema` is unmigrated. No feature flag, no dual-source period inside a family, and
the pipeline's order does not change.

**The interpreter is typed, not generic.** One renderer per command kind, total over the sealed arm set,
with no default arm. Not an evaluator walking a command tree: that would relocate the generic-fact-bus
mistake into the shell, trading compile-time coverage for flexibility this domain does not need.

**New code drops the `rewrite` package.** `no.sikt.graphitron` currently contains nothing but `rewrite/`, so
new packages sit directly under it and the absence of `rewrite` in an import is a reliable pre/post marker
while the migration runs. `rewrite` goes away wholesale once nothing is left in it. The split that follows:

| package | holds | may import |
|---|---|---|
| `no.sikt.graphitron.command` | command records and their sealed arms, pure data | never the emit library; from the model, only the named ref allowlist (see the vocabulary section) |
| `no.sikt.graphitron.plan` | producers, and `EmitPlan` | the model (for now), never the emit library |
| `no.sikt.graphitron.render` | interpreters, one per command kind | the emit library, never the model |

That dependency triangle is worth more than a visual aid: it makes two invariants structural for new code
instead of ratcheted. R545's "no emit vocabulary in the model" becomes "only `render` imports the emit
library", enforceable from the first file with no allowlist, leaving the allowlist to cover only legacy
`rewrite.model`. And "the shell decides nothing" becomes an import-direction rule, which is a simpler check
than any signature convention.

The one softening is `command`'s model-import line, and it is deliberate rather than a leak. A blanket
ban reads cleaner but buys its cleanliness by forcing a parallel copy of `TableRef`, `ColumnRef`,
`MethodRef` and the extraction hierarchy, which is a larger and more permanent cost than a checked
allowlist. The allowlist is enumerated, is the migration dial, and empties into a shared pure-data
floor as R545 de-javapoets its entries; the vocabulary section carries the reasoning and the list.

**The per-family recipe**, since the intent is to run every slice serially:

1. Find the emitter's decisions: its `instanceof` and switch sites, plus its "should I emit for this unit"
   predicate.
2. Move them into a producer that emits command rows in SDL order, stating the family's membership as a
   predicate derived from facts the model already carries wherever the facts permit it, since a derived
   membership makes "did we cover it" decidable rather than a judgment call (R541's covered family, a
   conjunction over `operation()` and target shape with no exemption list anywhere, is the exemplar).
3. Rewrite the emitter as a total function over the command's arms, dropping its `GraphitronSchema`
   parameter.
4. Point both ratchets down: one entry point off the 24, N leaf references out of `generators/`.
5. Land the family's three new test surfaces: pipeline-tier assertions on the produced command rows over
   existing fixtures (the decisions as data, asserted without javapoet), per-arm unit tests on the
   family's renderer (a total function whose inputs are record literals, needing no schema, fixture, or
   catalog plumbing), and the relation's non-vacuity and boundary pins: every covered coordinate in the
   corpus appears exactly once, and every shape the family's membership predicate excludes appears zero
   times. The pin is what replaces bidirectional oracles under a keyed relation ("exactly one per key" is
   the key, structurally), so what is left to falsify is that the relation is populated and correctly
   bounded; R541 worked this out for the launcher family and it holds for every family.
6. Acceptance: compilation and execution tiers unchanged, closure oracle green, the family's graph edges
   now read off the command instead of being predicted, and the family's rows in R333's seam worklist (the
   living table) updated to record the landed verdict, so the model item and this programme cannot drift
   on seam decisions. Two sharpenings generalised from slice 3c: where the cutover claims SQL-neutrality,
   the sharp form of "execution tier unchanged" is an exact-SQL equivalence pin authored before the
   cutover and kept green unchanged through it (a slice that deliberately changes SQL, as slice 3.2 does by
   ending over-projection, pins the new behaviour instead, per the baseline rule below); and the family's migration dial closes with a
   membership enforcer in the same commit, no window. Where membership is a derived fact, the enforcer is
   the fact's true-set equalling the relation's key-set; where the family still needs an exemption list,
   the list is the dial and emptying it is the enforcer.

Step 4 deserves an honest note: migrating a family does not delete its leaf dispatch, it **relocates** it
from `generators/` into a producer, which is where leaf dispatch belongs until slice 4 turns it into fact
reads. A falling leaf-reference count in `generators/` is progress on the boundary, not evidence that the
dispatch is gone.

## The SQL baseline lands before the keystone, and moves exactly once

Step 6's equivalence pin is per family, which leaves the largest SQL-affecting change in the programme
with nothing behind it: slice 3.2 ends over-projection, and R541's suite is authored after the
keystone precisely so it needs no carve-out. So the programme owns one thing the families do not.

**Slice 2 lands the equivalence harness and a projection-covering baseline, authored against today's
output.** Same mechanism the families use (a per-test-class `SQL_LOG` `ExecuteListener` in
`graphitron-sakila-example`, exact rendered strings and statement counts, not `contains`), with a
representative set chosen for projection shapes rather than for one family: a type with split
children, a nesting type, **one nesting type shared across two hosts with different tables**, a multiset
child, a polymorphic root, and a query selecting one scalar on a
type with several children, which is the over-projection case itself.

The shared-nesting case is in that list on purpose. It is the shape slice 3.1's anchor-prefixed unit key
exists to serve, `SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE` proves it classifies, and pinning its SQL
here turns "prefixing preserved both hosts' projections" into a test the keystone either keeps green or
does not, rather than a property argued in a plan body.

Slice 3.1 keeps that baseline green unchanged, which is what makes "the reshape moved no SQL" a
result rather than a claim. **Slice 3.2 re-baselines it exactly once, and that diff is the
deliverable**: the columns that disappear from those expected strings are precisely the
over-projection this programme is removing, reviewable as a diff instead of asserted in a plan body.
After that commit the strings freeze again and the families' own rule (editing expected strings is a
defect being papered over, not test maintenance) applies to everything downstream. A slice that needs
to move a pinned string and is not 3.2 has found a bug.

This also fixes an ordering hazard in the family rule as written. R541 and R552 each author a pin
"before its own cutover", which for R541 means after the keystone; without a programme-level baseline
the keystone itself is the one cutover in the programme with no pin on either side of it.

## The shared command vocabulary, and why it is narrower than it looks

Three specs now sketch command types, and between them they name `UnitRef`, `TableRef`, `ColumnRef`,
`Coordinate`, `SelectTerm`, `ColumnTerm`, `Binding`, `Reach`, `ExternalRef`, `FkHopRef`, `JoinRef`,
`OrderTerm`, `Pagination`, `Invocation`, `ResultShape`, `CallWrap`, `Arity` and `FacetFragment`, on
top of three command records and their arms. Read as a list that is alarming, and the alarm is
partly justified and partly an artifact of how the sketches are written. Sorting it is worth doing
once, here, because the answer changes what slice 1 builds.

**Most of the list is not new vocabulary. It is renaming things the model already has.** A code walk
against `rewrite/model/` (124 types):

[cols="1,3"]
|===
| sketch name | what it already is

| `TableRef`, `ColumnRef`
| `model/TableRef.java`, `model/ColumnRef.java`, verbatim

| `ExternalRef`
| `model/MethodRef.java`, the authored-callee ref R552's `Authored` arm already reads

| `FkHopRef`, `JoinRef`
| `JoinStep.Hop` plus `On.ColumnPairs` and its `Keying`, which the two EXISTS emitters already narrow to

| `Binding`
| `CallParam` plus `CallSiteExtraction`, a sealed hierarchy of roughly ten arms including the nested-path, enum-coercion and node-id-decode cases

| `Coordinate`
| graphql-java's `FieldCoordinates`, which `GraphitronSchema.fields` keys on
|===

Genuinely new: `UnitRef`, the command records, and their arms. That is the honest size of the
invention, and it is small. What made the list read as large is that each spec, obeying the package
triangle's "`command` may import neither the emit library nor the model", quietly implied a parallel
copy of vocabulary that exists. Nobody wrote "re-mint `CallSiteExtraction`", but "`Binding` is
`command`-package vocabulary, not a re-export of the model's `CallParam`" means exactly that, and for
the extraction hierarchy it means ten arms.

**So the decision is not which arms to trim, it is one dependency question: does `command` borrow the
model's ref vocabulary or copy it?** Copying doubles the surface and creates two definitions that
drift, which is R268's bug class at the type level. The obstacle to borrowing is that `TableRef`,
`ColumnRef` and `MethodRef` hold javapoet `ClassName` / `TypeName` today, so they are themselves
among R545's roughly 30 offending model files (the audit's import histogram is the counting rule); borrowing
them into `command` would drag the emit library in
behind them, which invariant 3 exists to stop.

The target is a **shared pure-data floor**: those refs shed javapoet (FQCN strings in, `ClassName.get`
at the renderer) and move to a package below everything, imported by `model`, `plan`, `command` and
`render` alike. One definition, no adapters, and `command` still imports neither the emit library nor
the model.

The target is not slice 1's job, because de-javapoeting `TableRef` alone touches every consumer of
`tableClass()` and that is not a cost the cheapest slice should carry. **Slice 1 borrows instead: the
`command` package may import a named, enumerated allowlist of model ref types (`TableRef`,
`ColumnRef`, `MethodRef`, `JoinStep`, `On`, `CallParam`, `CallSiteExtraction`, `FieldCoordinates`) and
nothing else from the model.** The import-direction check enforces the allowlist rather than a blanket
ban, the allowlist is the migration dial, and R545's cleanup empties it by moving each entry to the
floor. That keeps the rule mechanical from the first file while refusing the duplication, which is
what the blanket ban would have bought at the price of a second vocabulary.

**What actually gets cut.** Applying the programme's own rules (a split earns its place by counted
downstream consumers; term arms are SQL shapes, never reasons; no arm before a populated row) to the
genuinely new types:

[cols="1,1,3"]
|===
| type | verdict | why

| `OrderTerm`
| delete
| ordering is already a named emitted unit, the `private static <field>OrderBy(env, table)` helper `TypeFetcherGenerator` builds and both root and child call. The launcher slot is a `UnitRef`, absent when unordered. Modelling order terms here also pre-builds R333 row 9's family, which is its own seam

| `Pagination`
| delete
| `Seek` appears exactly when the shape is `ConnectionResult`, so the two slots make illegal pairs representable. R541 already recommends the fold; it is now made rather than recommended

| `Reach`
| delete
| `Local()` is an empty record meaning "no hops". The slot is the hop list, and empty is local. A sealed pair whose first arm is empty is an `Optional` wearing a name

| `ColumnTerm`'s four arms
| collapse
| `Eq` / `In` / `RowEq` / `RowIn` is a 2x2 of (scalar, row) x (equality, membership), and row-ness is `columns.size() > 1`. One record plus a two-value match kind covers it. The model's `BodyParam` keeps its four arms; the command has no reason to mirror them

| `Invocation`, `Arity`
| demote to enum
| two payload-free records each. A sealed interface earns its shape when an arm carries data; `Batched` will, and promoting then is a two-line change. Until then the interface is ceremony

| `FkHopRef` vs `JoinRef`
| unify
| two names for one join-path reference, minted in two specs for the same underlying `JoinStep.Hop`
|===

And the contrast cases, kept deliberately, so the rule is a filter rather than a mood: `UnitRef`
against the model's `MethodRef` stays two types because the closure oracle resolves them differently
(against the plan, against `ServiceCatalog` reflection), which is two counted consumers, and the
distinction is the emitted-versus-external split slice 7's edge view is built on; `CallWrap`'s
`Splice` / `Multiset` stays because same-row and other-rows are two counted consumers even though
`Splice` is empty; `Contribution` and `Predicate` keep their two arms on the arguments their own items
already make.

**Per-slice budget, so the width is checkable rather than felt.** After slice 1 the entire command
vocabulary is `UnitRef`, a unit-kind enum and the global command record: three names. R552 slice 1
adds the condition command, two predicate arms, the column term, its match kind and the facet
fragment. Slice 3.1 adds
the projection command, `Contribution`, `CallWrap`, the select-term arms and `Arity`. R541 adds the
launcher command, `Invocation` and `ResultShape`. Anything else appearing in a slice is a finding to
report, not a detail to absorb.

## What the seam buys the test pyramid

The producer/renderer seam is also a testability repair, and the payoff is measured, not aspirational.
R25's JaCoCo baseline names the emitters as the worst-covered generator code (`JooqRecordInstantiationEmitter`
40.7%, `FetcherEmitter` 50.2%, `generators/` the lowest-covered package), and the cause is reachability
cost: covering an emitter branch today means driving the whole pipeline (SDL fixture, schema build,
classification) into the one leaf configuration that reaches it. The command seam removes that cost at
three tiers:

- **Renderers become unit-tier testable as total functions.** A renderer takes a command arm and a
  config-only `RenderContext`; constructing either in a test is a record literal, with no `TestSchemaHelper`,
  no fixture, no catalog. Every arm of the sealed set is reachable directly, which per-arm coverage of the
  emit has never had. The tier guide's "pipeline beats unit: per-variant structural tests are bookkeeping"
  doctrine was written against fixture-plumbed generator tests asserting `TypeSpec`-shape proxies; renderer
  arm tests are a different species (no plumbing, inputs constructed at the point of assertion), and
  `docs/architecture/how-to/testing.adoc` gains a rubric row for them when slice 3 lands.
- **Producers become pipeline-tier assertable without javapoet.** SDL to command rows is a new assertable
  surface: the decisions as data, cheaper and more precise than asserting the `TypeSpec` shape that encodes
  them. Recipe step 5 requires this per family, and it is the down payment on slice 8, which generalizes
  the same assertions into the corpus.
- **Closure gets a plan-time form.** With typed `UnitRef` edges, referential integrity over the command
  relation (every callee a committed command) is checkable on `EmitPlan` alone, in the pipeline tier,
  before any rendering: milliseconds in the inner loop, with `MethodClosureOracleTest` keeping the
  end-to-end guarantee over the rendered output. The plan-time invariants (one unit one table, no ungated
  contribution) are likewise plain producer unit tests.

None of this changes the slice set; it changes the per-family recipe (step 5) and slice 2's instrument
list. The measurement to watch: re-run R25's ad hoc JaCoCo baseline alongside the other numbers after
slices 1 to 5, expecting renderer coverage to climb family by family as arms become directly
constructible. Wiring R25 into the build before slice 3.1 lands would make that signal cheap to read, which
is a reason to raise its priority, not a dependency.

## Invariants: what makes this falsifiable rather than believed

The current statements of the cut ("commands must be complete", "the shell assembles nothing") are not
checkable, which is why the boundary drifts. Under the labelling they become mechanical, and each one is
installable as a ratchet at its current value before any migration happens.

1. **A command-based emitter takes no `GraphitronSchema`.** This is the sharp form of "the shell decides
   nothing", and it cannot be satisfied halfway: an emitter either holds the model or it does not. Today 24
   `generate` entry points in `generators/` take it, so the ratchet is 24 to 0. Renderers may take a
   `RenderContext` carrying
   config (output package, tenant key type, federation flag, helper-name registries), but that context must
   have no field typed `GraphitronSchema` or any model hierarchy, or the rule is defeated by smuggling; both
   halves check in one meta-test. For new code the same rule is an import-direction check on the package
   triangle above, which needs no ratchet at all.
   **Take every count in this invariant from the audit script, do not restate it**, because the counting
   rule is what makes a ratchet mean anything: `generate` methods only, so the 14 non-entry-point helpers
   in `generators/` that also take the model are out of scope until their family migrates. The secondary
   count is leaf references inside `generators/` under the audit's own grep, which spans seven hierarchies
   (`ChildField`, `QueryField`, `MutationField`, `InputField`, `OutputField`, `GraphitronType`,
   `GraphitronField`) and stands at 104 `instanceof` sites. Narrowing it to the three field hierarchies
   gives 59, and counting `case` patterns as well as `instanceof` adds 89 more (the audit's grep with
   `case` in place of `instanceof`, seven hierarchies, measured 2026-07-28), so the definition has to
   travel with the number or slice 2 installs the ratchet at whichever of the three it guesses. Driven to
   zero family by family, remembering that those
   relocate into producers rather than disappearing until slice 4. The tertiary count guards the
   relocation itself: leaf references inside `plan/`, which grow through slices 3 and 3b and ratchet to
   zero during slice 4. During that window the pipeline for migrated families is facts, then leaves, then
   commands, then render, four layers, and if slice 4 stalls the leaves survive precisely to feed the
   producers, which is the kept-alive-to-feed-one-consumer failure the progress section warns about. The
   tertiary count makes that stall a flat line on a named number instead of a feeling.
2. **Every hierarchy declares its grain and lives in exactly one relation at that key.** This is
   `VariantCoverageTest` generalised from "every leaf is demonstrated" to "every hierarchy has a declared
   grain and a home". It is the guard against the current bug class, where something exists that no
   coordinate names (`PivotSlotField` riding `PivotSpec.slots()`, R462's emitted methods with no
   coordinate).
3. **No emit-library vocabulary in the model** (R545). A command holding a `CodeBlock` is output the core
   already rendered; a fact holding a `TypeName` is comparable only through the renderer's equality.
   Landable immediately as an allowlisted guard over the roughly 30 current offenders, which converts a
   growing surface into a shrinking one.
4. **Closure stays green** (`MethodClosureOracleTest`): every callee name resolves to a committed command.
   This already exists and works for the load-bearing families; every slice below must hold it.
5. **Projection dispatch is exhaustive over the sealed set, with no default arm.** This is what closes
   R425's bug class rather than one of its instances: a leaf that demands a correlation key and has no arm
   becomes a compile error instead of a forgotten entry in a global append that silently nulls a DataLoader
   key at runtime under a federation `_entities` fetch.
6. **No unconditional columns in a projection command.** Every contribution is gated on client selection;
   anything a mechanism needs regardless belongs to a launcher. Structural, not checked after the fact:
   every `Contribution` arm carries its gating field as a mandatory component, so an ungated contribution is
   unrepresentable and the compiler is the enforcer, with renderer totality (one emit path per arm, no
   default) covering the render side. No body-shape meta-test; asserting the statement layout of a
   generated method is the code-string assertion the test tiers ban, and it would fire on a legitimate
   helper-locality lift. The behavioural residue, that an unselected child projects nothing, is pinned at
   the execution tier.
7. **Concentration ratchet** (optional but recommended): share of package LOC in the top five files, and
   largest single file per package. Today 46% / 7,102 for `generators/` and 52% / 7,754 for the core.
   Totals are a poor discriminator, since they can stay flat while structure degrades; concentration is
   what actually tests the direction. It also constrains the regrowth that defeated R6 and R7, where
   `FieldBuilder` returned to being the largest file in the tree and `TypeFetcherGenerator` grew from
   1,646 to 7,102 lines while a decomposition item waited.

## Slices

Numbered because these are real seams: each ships to trunk on its own with the build green, and the
intermediate states are observable. **The intent is to deliver all of them, serially**, so the numbering is
an ease-of-execution ordering rather than a set of decision points: sequence by what is cheapest to do next
given what has landed, not by which slice earns the next one. Slice 3 stays ahead of slice 4 on that basis,
since projection commands can be derived from today's leaves and re-sourced onto facts when the walk exists,
whereas doing 4 first means building the engine before anything consumes it.

Two things shape the numbering below beyond that ordering rule, and both exist to keep the expensive
slice from also being the first one.

**The skeleton rides slice 1, not the keystone.** `EmitPlan`, the `command` / `plan` / `render`
packages, the import-direction check over them, `UnitRef`, and `GeneratedUnits` moved out of
`compile/` are the vocabulary every command family needs, and none of it is projection-specific. The
global command list needs all of it too (its producer has to name the units it commits, and the
naming vocabulary for globals, `singleton` and `rootUnit`, is already sitting in `GeneratedUnits`),
so the cheapest slice in the programme is also a complete vertical through the whole architecture:
producer in `plan`, records in `command`, the shell folds. Landing the skeleton there means it is in
trunk, reviewed, and running against real output before anything expensive depends on it, and the
keystone inherits a proven skeleton instead of inventing one under load. The alternative, which the
earlier draft of this table had, makes the first visible artifact of the whole direction a large
diff that simultaneously invents the vocabulary and migrates the hardest family.

**The keystone ships in three parts.** Slice 3 as originally scoped bundled six independently risky
things: a reshape of the producer/renderer seam, a rename of the most-called generated method, the
deletion of a build-time check, a change to emitted SQL, a new directive reach, and a dependency on
R516. Splitting them along the lines that actually differ (does it move SQL, does it depend on
R516, is its scope a finding) costs nothing in total work and means a wobble in one part does not
hold the other two. References to "slice 3" elsewhere in this item, and in R541 and R552, mean 3.1
to 3.3 together unless a part is named.

`@splitQuery` on a nesting field is slice 3.3, a consequence of promoting nesting types to
projection units: the split launches a keyed query against the parent's table selecting that unit's columns,
correlated by the parent's key. Today the directive is accepted there and `NestingField` carries no delivery
slot, so it appears to be silently ignored; confirm that at implementation, because if it is, that slice
either implements the launcher or lands a validate-time deferred rejection on the shape, per the
validator-mirrors-classifier rule: an accepted classification whose emit is unimplemented fails the build,
and the rejection deletes for free when the launcher arrives. A lint advisory is too weak (the build would
succeed while the directive does nothing), and doing nothing is the one option that is already wrong.
It is its own part precisely because its scope is a finding, and a slice two other items depend on
should not carry one.

| # | slice | why here | cost |
|---|---|---|---|
| 1 | **Global command list, and the vocabulary skeleton.** `runPipeline`'s 33 `write(...)` calls become a relation the core computes and the shell folds over, and the slice brings the machinery that relation cannot exist without: `EmitPlan`, the `command` / `plan` / `render` packages, the import-direction check over them, `UnitRef`, and `GeneratedUnits` moved out of `compile/` | touches no leaf, no fact, no javapoet, no emitted output, so the cheapest slice in the programme is also its first complete vertical; makes "the core decides the entire emit" literally true for the one population where it is currently orchestrator control flow, and puts the package triangle in trunk before anything expensive rests on it | very low |
| 2 | Label the hierarchies (walked / resolved / command / error), install invariants 1 and 3 at their current counts, and ship the programme's three instruments: the exemption-list triage, the corpus pair-independence extension (both below), and the SQL equivalence harness with its projection-covering baseline | the labelling is the programme's vocabulary, a ratchet installed before the migration is what stops the surface growing while the work proceeds, the pair-independence data validates the two-arm contribution collapse before slice 3.1 spends the medium cost, and the baseline is what makes the keystone's SQL change a reviewable diff instead of a claim | low |
| 3.1 | **The keystone, part one: the projection command.** One method per projection unit, grouped selection in and select list out, replacing the two `$fields` overloads and their private `$fieldsGrouped` and renamed to say what it returns (`$project`) uniformly across table-backed and nesting units. Nesting types promoted to units, keyed `(anchor, typeName)` and anchor-prefixed, with the naming collision verdict; the seven positional buckets become one ordered `List<Contribution>`; exhaustive dispatch with no default | designing this validates or breaks the whole model. It reshapes the producer/renderer seam and renames a method without moving any SQL, so it lands against the frozen baseline and proves the reshape in isolation | medium |
| 3.2 | **The keystone, part two: correlation keys, and the end of over-projection.** The correlation columns become a gated `Project` arm reading the same accessors the extraction emitter consumes; the required-projection walk and `ParentProjectionContainmentCheck` are deleted; an unselected child projects nothing. Depends on R516 and on 3.1 | the only slice that deletes a build-time throw, a duplicated walk and a runtime over-projection at once, and the only part of the keystone that changes SQL, which is what makes it the part that re-baselines the pin | medium |
| 3.3 | **The keystone, part three: `@splitQuery` on a nesting field.** Confirm the directive is accepted and silently ignored there; implement the launcher it implies, or land the validate-time deferred rejection. Depends on 3.1 | the one part whose scope is a finding rather than a plan, so it is separated from the two parts R541 and R552 wait on | low |
| 3c | **The launcher dual: the root SELECT family becomes launcher commands.** Owned by R541, rewritten in command terms: `(coordinate, operation)` rows carrying invocation strategy, return shape, and a `UnitRef` naming the projection unit they select from. Depends on 3.1 and 3.2 (it names projection units, and its exact-SQL pin wants the select list final) | the second proof of concept. Slice 3 proves type grain and a contribution list; this proves the three things it cannot reach, namely coordinate keying, strategy as data, and one command referencing another, which is what slice 7's edge projection rests on. Generalising to slice 5 from two proofs at different grains beats generalising from one | medium |
| 3d | **The third proof: condition commands.** The WHERE family becomes coordinate-keyed condition units, owned by R552: one relation, one glue rendering per row, every WHERE consumer (root, child, inline, polymorphic) calling glue instead of composing the fold inline, the typed entity layer retiring once its last caller converges. Depends on slice 1 only; 3.1 is what unlocks its R472 fix, and without it that defect converts to a deferred rejection instead. Runs before 3c (ordering below) | reaches what neither sibling proof can: a QueryPart-valued unit proves the command vocabulary is not select-specific, authored `@condition` methods give the edge view its emitted-versus-external split before the service family needs it at scale, and the glue GROUP BY hands slice 3b its exemplar rows | medium |
| 3b | The type-keyed relation `(typeName, unitKind)` replaces the roughly 11 per-type generator predicates (sized above; the 24 that take the model include slice 1's globals), one kind at a time | inverts "should I emit" from 11 independent loops into one relation the shell folds over; renderers barely move, and the unit vocabulary already landed with slice 1 | medium |
| 4 | Fact-visitor engine: one shared traversal dispatching to per-fact visitors, on the `LintEngine` pattern, with the registry-coverage meta-test, one genuinely independent fact as beachhead | dissolves the central switch that made `FieldBuilder` the largest file in the tree, using an architecture that already shipped here | medium |
| 5 | Coordinate-keyed command relation: `Operation` rows become the command set the shell consumes; `MethodCommandRegistry`'s parallel four-string record retires into it | this is where the flow finally inverts from shell-asks-core to core-tells-shell | medium |
| 6 | Grain repair, worked from the exemption lists | 19 stated data points about where the grain is wrong, already written down with reasons | medium |
| 7 | The recompile graph becomes a projection over the command relation, retiring `CompileDependencyGraphBuilder`'s coarsening switch; R10's rebuild drop lands once connection synthesis is a relation | removes the largest duplicate derivation and with it a recurring bug class (R455, R459, R462) | high |
| 8 | The corpus asserts facts, then commands (R543) | the payoff that justifies the command half at all, and it wants the relations to exist first | medium |

**Ordering among the three proofs is decided here, not by their reviewers.** R541's fork 1 and R552's
fork 4 each ask how the launcher gets its WHERE clause, and each defers to the other item's reviewer,
which means nobody decides. The programme owns sequencing, so: **R552 slice 1 lands before R541
slice 1.** Four of R541's five root shapes already call named `<field>Condition` methods, so the
launcher consumes the condition relation from its first row; the reverse order has R541 mint a
`ConditionRef` from a formula R552 then re-homes, which is the migration payment this programme
refuses. R552 also needs only slice 1 to start, and it carries fixes for output that does not compile
today (R472's dangling reference converted at its slice 1, R475's collision dissolved at its slice 3),
so running it first puts a user-visible correctness win in front of the first purely architectural
family. Both forks now resolve by pointing here rather than by weighing options. The full sequence:
1, 2, R552 1 and 2, 3.1, 3.2, 3.3, R552 4 jointly with 3c, then 3b, 4, 5, 6, 7, 8. R552's slice 3
(entity-layer retirement) is unblocked the moment its slice 2 lands and depends on nothing in the
keystone, so it floats anywhere after that point.

R552 slice 2 and slice 3.1 are the one pair in that sequence that must not run concurrently: the
inline `$fields` arm emitters are R552 slice 2's convergence targets and slice 3.1's raw material, so
both would be editing the same emitters for different reasons. R552 slice 2 goes first, which is also
the cheaper order, since 3.1 then folds arms whose condition composition is already a one-line call.
The R472 coupling runs the other way and is soft: without slice 3.1 there is no walkable home for
nested coordinates, so R552's fix for it degrades to a deferred rejection rather than blocking, which
is exactly what that item already specifies.

Slice 4 owes one design decision before any code: **gather versus resolve.** Lint rules are independent
inspections, which is why their registry is clean, but facts interlock (`resolvedTable` is a coalesce over
three walked facts, `reference` mints `join`, the read-side facts gate on the source object). The
model-consistent split is R333's own: visitors gather only authored and inferred populations, and every
resolved value is a view computed after the walk with no traversal of its own. If that split holds the
engine is simple; if it does not, the design becomes a pass-ordering DAG, which is a materially heavier
thing and should be recognised as such before starting.

Slice 4 also carries a real safety regression to mitigate: `FieldBuilder`'s switch over sealed permits is
compile-checked today, and a visitor registry is not, so a forgotten registration is a silently missing
fact rather than a build break. The lint engine hit exactly this and answered it with
`LintRuleRegistryCoverageTest` (every rule registered exactly once; subscribed kinds union not-linted
kinds partition the node kinds with no overlap or gap). The equivalent must land with the first visitor,
not after.

Slice 5 owes its own design decision before any code: **the general launcher command.** The keystone
designs the projection half, and R541 (slice 3c) sketches the launcher for one family, the root SELECT
launchers, with an In Review hand-off required to report whether the `extras` slot, the conditions
handshake, and the `UnitRef` edge shape held, and R552 (slice 3d) proves the value-shaped condition unit,
the external-callee edge, and boundary marshalling as data. Slice 5 generalises from those three proofs,
and what it owes beyond them is stated now. The `Batched` invocation arm lands with its first row when the child family folds in (R541
deliberately declines to pre-declare it). The design must state how `Operation`'s arms partition across
launcher renderers, projection calls, and DML rendering, because that partition is what slice 5's
exhaustive dispatch is total over. And where a unit composes several operation rows, the relation must say
which unit each row lands in, the anchor column R333's operation relation already names (`anchor address`);
R541's single-operation launchers never need it, and a general launcher cannot do without it.

## Slice log

- **Slice 1 landed 2026-07-28.** The `command` / `plan` / `render` packages exist with
  `PackageImportDirectionTest` enforcing the triangle; `EmitPlan.produce` computes the global
  command relation and `runPipeline` folds over it, landing each unit at the address its row
  committed; the vocabulary budget held at three names (`UnitRef`, `GlobalUnitKind`,
  `GlobalCommand`). Findings to report, per the budget rule:
  - `QueryNodeFetcher` is a keyless fixed-name singleton gated on node-type presence, so it is a
    23rd global kind by this item's own definition, not a slice 3b family; the per-type-emitting
    population 3b migrates is 10.
  - `usesOneOf` landed as a bundle fact beside `federationLink` (computed once in
    `GraphitronSchemaBuilder.buildBundle`), so the producer reads two booleans and `plan` never
    imports graphql-java. `OneOfDirectiveSdl` moved from `generators/schema` to the core
    `rewrite/schema` package because the builder now reads it.
  - The connection runtime's 4-versus-5 unit set is single-sourced on
    `SessionStateConfig.emitsHookImplementation()`; the producer and
    `ConnectionRuntimeClassGenerator` read the same fact.
  - `GeneratedUnits` landed in `plan` (this item already calls it the plan's naming vocabulary)
    and mints `UnitRef`s; the import guard pins the minting site. Legacy `compile/` keeps a
    pure-delegation String view (`UnitNames`) that retires with the graph builder at slice 7.
  - `UnitRef` carries `(packageName, simpleName)` and the write step lands units at the committed
    ref, so there is one naming derivation; a renderer emitting an undeclared unit or dropping a
    committed one fails the run.
  - Deliberately not absorbed: per-family argument assembly stays shell-side (including
    `tenantKeyType`, a javapoet `TypeName` the plan must not hold), and the migrated generators
    keep their `GraphitronSchema` signatures, so invariant 1's entry-point count does not move in
    this slice; slice 2 installs the ratchet at whatever the audit script prints.
  - The two hand-maintained copies of the global census in `compile/` (`UtilSingleton.ALL` and
    `CompileDependencyGraphBuilder.addSingletonNodes`) remain; the completeness oracle already
    pins the graph against the real emitted set, and both copies retire with slice 7.

- **Slice 2 landed 2026-07-28.** The labelling, both ratchets at the audit script's printed
  counts, and the three instruments. Findings to report:
  - The labelling is `HierarchyKindRegistryTest`: a coverage-checked registry over the fact
    base's top-level sealed hierarchies (68 today: 67 under `rewrite/model` plus `BuildWarning`,
    the error channel's non-fatal half, enumerated by name from the core package). Scope
    exclusions are by principle, not path habit: builder-internal result channels are gathering
    scaffolding, `catalog/` seals are projections over the model, `plan/` internals are producer
    scaffolding. The `command` package is in the scan from day one so slice 3.1's arms cannot
    land unlabelled. The four kinds are documented as cells of a provenance-by-phase product,
    not a closed partition; the derived-view-at-emit-grain cell joins the enum when slice 7's
    projections exist.
  - `GraphitronType`'s five synthesised permits moved from prose to data
    (`SYNTHESISED_TYPE_PERMITS`), and the exemption lists' synthesised category is pinned to
    stay inside that set.
  - Invariant 1 landed as `CommandSeamRatchetTest` with the counting rules in the pattern
    constants: 24 model-taking entry points, 104 `instanceof` plus 89 `case` leaf references in
    `generators/` (the seven-hierarchy definition), and the tertiary count opened at 1, not 0:
    slice 1's `EmitPlan` already relocated the node-fetcher membership gate (an `instanceof
    GraphitronType.NodeType`) into `plan/`, which is the relocation the tertiary count exists to
    make visible. Invariant 3 landed as `ModelEmitVocabularyGuardTest`, allowlisting the 29
    model files importing the emit library, shrink-only in both directions.
  - The exemption triage landed as typed data (`Exemption(category, reason)`) on both lists
    rather than prose; the predicted three-way partition needed a fourth category
    (`FIXTURE_GAP`, 8 of 19 entries), the grain-repair category holds exactly one entry
    (`PivotSlotField`), and the verdict paragraph lives in the exemption-lists section above.
  - The pair census landed in `ClassifiedDslTest.axisPairCensusIsDerivable`, sharing one axis
    extraction with the single-axis exercise test so the two instruments cannot drift. Measured
    verdicts are recorded in the census section above, along with the correction that the
    census is slice 6's instrument and cannot falsify the keystone's two-arm collapse, which
    validates by counted consumers instead.
  - The SQL baseline landed as `ProjectionSqlBaselineTest` in `graphitron-sakila-example`:
    whole rendered statements over six projection shapes, statement counts pinned by
    exact-log assertions, on the purpose-built fixtures already in `schema.graphqls`
    (`SplitParent`, `FilmSummary`, `OccupantLocation` under both hosts, `Customer.address`,
    `Query.search`, and the one-scalar `Customer` probe, whose select list today carries
    `address_id` and `store_id` for unselected children: the over-projection the re-baseline
    diff will remove). Framed as behaviour at the execution tier, not a carve-out from the
    code-string ban: SQL is the contract with the database.
  - Deliberately not built, per the consult: no validator mirror (slice 2 adds no classifier
    branch) and no concentration ratchet (invariant 7 is optional and not in this slice's row).

## The exemption lists are the grain worklist

`VariantCoverageTest.NO_CASE_REQUIRED` (13 entries) and `ClassifiedDslTest.OPERATION_KNOWN_GAPS` (6) each
state why something the model declares cannot be reached at the grain a test walks. Read as a set rather
than one at a time, they should partition into (a) genuinely unimplemented behaviour, (b) synthesised
things with no SDL origin, and (c) things riding another row's list rather than their own key. Category (c)
is the direct worklist for slice 6, and (b) is the connection-promotion residue slice 7 clears. Nobody has
read them as a class yet. Slice 2 owns the triage: it is cheap, it sharpens slices 6 and 7 before either
starts, and writing it down alongside the labelling keeps the worklist from being re-derived at each
slice.

**Triage verdict (slice 2, 2026-07-28).** The triage lives as data on the lists themselves, not as prose
here: both maps now carry a typed `Exemption(category, reason)` value, so the worklist is a filter over
the live lists and a new entry must pick a category. Two findings from reading the 19 entries as a set.
First, the predicted three-way partition did not close: eight entries are neither unimplemented nor
synthesised nor mis-keyed but *demonstrated outside the corpus's reach* (composite-PK node types,
synthesised node-id metadata and plain jOOQ records missing from the fixture catalog, plus one entry whose
demonstration lives in a test shape the coverage walker does not read), so the taxonomy gained a fourth
category, `FIXTURE_GAP`. Second, the measured partition is: unimplemented behaviour 6 (UPSERT and its
operation-arm mirror, condition-matched UPDATE and DELETE, the errors field, federation `_entities`),
synthesised with no SDL origin 4 (the facet pair, connection `Count` and `Facet`), riding another row's
key exactly 1 (`PivotSlotField`, slice 6's whole current worklist at this grain), fixture gaps 8. The
synthesised rows are additionally pinned against the labelling's `SYNTHESISED_TYPE_PERMITS` set
(`HierarchyKindRegistryTest`), so a sixth synthesised type fails a test rather than drifting a paragraph.

## Empirically deciding which families are independent

The corpus already records, per coordinate, which arm each axis lands on, and
`ClassifiedDslTest.everyDimensionValueIsExercised` tracks which arms are populated. Extend it from single
axes to **pairs**: for each pair of families, is the cross-product populated across the corpus, or only a
diagonal? A populated product means independence, so the families must separate; a diagonal means they
co-vary, so keep them fused and save the machinery. That turns "which families are real" from a judgment
call into a measurement, and it makes the corpus an instrument for designing the model rather than only
for pinning it. Slice 2 ships this extension alongside the labelling, so the measurement exists before
slice 3.1 commits to the two-arm collapse it would falsify.

**Measured (slice 2, 2026-07-28; re-derive by running `ClassifiedDslTest.axisPairCensusIsDerivable`,
which prints the matrix).** Over 83 corpus coordinates, with denominators built from corpus-observed
values so known-gap arms cannot inflate a product, and source-wrapper-by-source-shape skipped as a
containment rather than a pair: three pairs are fully populated products (source x target wrapper 8/8,
source shape x target wrapper 4/4, target wrapper x target shape 12/14 with the two holes structural,
since a connection is single-wrapped by construction), which is measured independence. The sharpest
diagonal is operation x target shape at 20 of 77: the verb largely pins the projection shape, which is
measured co-variation and supports keeping the operation-shape families fused. Source x operation sits
between (17/44), most holes being structural (no mutation verbs on child sources, no DML on Query).

**One correction from the consult, recorded so the instrument is not oversold.** The census measures
co-variation between classification axes at the coordinate grain, and that is what it feeds slice 6's
grain repair and the split-on-measured-independence rule with. It cannot falsify the keystone's two-arm
contribution collapse, whose discriminator is structural (the callee is a projection unit) and whose
splitting rule is counted downstream consumers, not provenance. The sentence above claiming the
measurement exists "before slice 3.1 commits to the two-arm collapse it would falsify" over-claimed;
the collapse validates at slice 3.1 by consumer count, and the census stands as slice 6's instrument.

## Relationship to existing items

| item | relationship |
|---|---|
| R333 (Ready) | governs. This item consumes its model and does not re-litigate it. The fourth-reader note in its consumers section is the corpus's stake in the re-sourcing; its seam worklist stays the living table of seam verdicts, updated by recipe step 6 as families land |
| R545 (Backlog) | becomes slice 2's invariant 3. Stays as filed; it is a precondition, not an independent win |
| R546 (Discarded 2026-07-27) | absorbed here. It asked what shape `MethodCommand` should grow into, and this reframing answers "none": the hierarchies are the commands, so a parallel four-string record is exactly the intermediary model this programme says is unnecessary. Its flow-inversion scope became slice 5, its recompile-graph justification became slice 7 (full argument in the audit's gap 7), and its abandon condition became this item's |
| R543 (Backlog) | slice 8. Its fact half needs slice 4, its command half needs slice 5 |
| R544 (Backlog) | independent, and this reframing strengthens it: the error-channel hierarchies are a first-class fourth kind at 55 leaf records across ten seals, so pinning them declaratively is model work, not only test hygiene |
| R541 (reopened to Spec 2026-07-27) | **the second proof of concept**, slice 3c. Rewritten in command terms, which dissolves rather than defers most of its previous design: its `QueryUnitField` naming capability, its `declareRootQueryUnit` registry seam and its bidirectional oracle were all machinery for facts an emitter reads directly, and under a coordinate-keyed relation the name is a producer-computed field and "exactly one command per coordinate" is the key. Building them first would have been a migration payment slice 5 then retires. Its five root emit shapes stay the real content, and its exact-SQL equivalence pin gets simpler rather than narrower: authored after slice 3, the select list is already final, so the pin covers whole statements with no carve-out. Its conditions fork was re-posed at Spec review 2026-07-27: the root builders already call named `<field>Condition` methods, so what the fork actually does is finish R333 row 5's naming lift (the `QueryConditionsGenerator` end, R2 today) for the covered family's coordinates; the slot resolves as a `UnitRef` into R552's condition relation and the scope question moved there (its fork 4). Its live open fork is now the connection root's `ConnectionResult` carrier plan (`totalCount`'s `(table, condition)` binding and the facet condition fragments), which the launcher sketch has no slot for. Two notes from the rollout review: it is the one proof of the three with no capability payload, which the "no slice that is purely a migration payment" rule makes a thing to name in its own body rather than discover at review; and it runs after R552, per the ordering decision in the Slices section |
| R552 (Spec) | **the third proof of concept**, slice 3d, and the first family to land. The WHERE family as one coordinate-keyed condition relation with a total glue unit per row: the first value-shaped unit, the second cross-kind edge including the first external callees, argument marshalling as data, and the type-keyed GROUP BY that hands slice 3b its exemplar rows. Dissolves R541's conditions fork by owning condition production; the ordering question its fork 4 leaves open is decided in the Slices section (R552 first). Absorbs R472, R475, R387 and R334 for its family, which is why it is also the right family to run first: the programme's earliest visible commits fix output that does not compile today |
| R516 (Ready again after a fourth gate pass, priority 2) | **dependency of slice 3.2**, and of nothing earlier: the keystone's reshape half does not need it, which is part of why the split is worth having. It has already deleted the full-row parent projection for typed-record `@service` keys and the reserved alias namespace that carried it, which was the one demand no parent-owned fact could serve, and it ships independently as correctness work. Its force-include of the parent PK is an interim expression that slice 3 converts to a gated `Project` arm; it was scoped as PK plus node key until 2026-07-27, when the node-key half was dropped for the reason stated here, that node-id-ness does not affect projection. Whatever it leaves in `ParentProjectionContainmentCheck` should be the minimum that keeps the check honest, since slice 3.2 deletes it. Re-read its body at slice 3.2 pickup rather than trusting this row: it has bounced the Done gate repeatedly, so its scope and its status both move faster than this table |
| R462 (Spec) | fix by hand now, do not generalise; slice 7 dissolves its class. Advisory already noted on the item. Its Spec body cites `GraphitronSchemaValidator.NESTED_WIREABLE_LEAVES`, which no longer exists under that name anywhere in main or test, so the implementer must re-derive the current nested-leaf bound rather than trusting the citation |
| R10 (Backlog) | dependency of slice 7. Its own body says it wants "a concrete signal"; the fact engine making connection synthesis a relation is that signal |
| R7 (Backlog) | subsumed in effect. `TypeFetcherGenerator` splits along command kinds under slice 3 and slice 5 rather than by a decomposition pass that regrows |
| R25 (Backlog) | supplies the coverage half of the falsification baseline, and its per-tier split is the instrument that measures the renderer-testability payoff (see the test-pyramid section); wiring it before slice 3 is cheap and worth a priority bump |
| R112 / R117 | unaffected, but the KB's "model as projection" framing gets easier once the relations exist |

## Progress measurement

The owner's stated intent is to deliver every slice, serially, without a stop gate, so the measurements
below are an instrument rather than a decision procedure: they say whether the direction is doing what it
claims, and a slice that moves none of them is a slice worth re-examining before the next one starts. The
facts half of R333 has been paying its way slice by slice (R432
collapsed four leaves to two, R438 made join facts orthogonal, R435 shipped a user-facing feature off the
fact model), and the discipline that produced that record is the one to keep: **no slice that is purely a
migration payment.** Each slice above ships a simplification, a deletion, or a capability.

Baseline, measured 2026-07-26: 1,641 branches in `generators/`, 104 leaf-naming `instanceof` sites,
29,837 generator LOC, 366 `ClassificationCase` constants, top-five concentration 46% (`generators/`) and
52% (core), largest files 7,102 (`TypeFetcherGenerator`) and 7,754 (`FieldBuilder`), and R25's
emitter coverage figures (`JooqRecordInstantiationEmitter` 40.7%, `FetcherEmitter` 50.2%).

**Every figure above is now script-derived, which it was not when this item was drafted.** The audit's
script block originally produced only the branch, LOC and `instanceof` counts; the entry-point count, the
`ClassificationCase` count and the concentration figures were hand-counted, and two of the three were
wrong by more than drift (25 entry points against the script's 24, 400 `ClassificationCase` constants
against 366). The missing greps are now in the audit's script block, so slice 2's job is to run it and
install invariants 1 and 3 at what it prints, not to re-derive a counting rule. A number with no counting
rule attached is a number the next reader re-derives differently, which is how invariant 1's secondary
count came to carry a seven-hierarchy figure under a three-hierarchy definition. Small drift against the
measurement date is expected and fine (branches and LOC have both moved by well under a percent, and the
core's largest file by five lines); a figure nobody can reproduce is not.

Re-run after slices 1 to 5. What matters is direction, not a threshold. The one risk the numbers guard
against is stalling mid-migration, since a partly-converted shell is worse than either endpoint: R333 says
so itself, that leaves kept alive to feed one consumer is how the leaf zoo returns as a second model. Given
the intent to run the slices to completion, the mitigation is sequencing rather than an exit: the ratchets
in the invariants section hold each conversion once it lands, so an interruption leaves a smaller shell
rather than two half-models.

One honest qualification on that mitigation, because the widest window is the one the ratchets measure
but do not close. Between slice 3.1 and the end of slice 4, migrated families run facts, then leaves,
then commands, then render, and invariant 1's tertiary count (leaf references inside `plan/`) makes a
stall in that window a flat line on a named number. Visible is not the same as costly. The programme's
stated answer is the intent to run to completion, which is a commitment rather than a mechanism, and
that is a fine answer for serial single-session work: say so plainly and the risk is accepted, not
hidden. If the work is ever parallelised across sessions, the four-layer window stops being self-
closing, and the thing to do then is name slice 4's owner before slice 3.1 lands rather than after
the count flattens.

## Retired vocabulary

Declared per the item-file conventions in `roadmap/workflow.adoc`, so the Done-gate retirement sweep
has a grep list. Each term names the slice that retires it; a slice landing before its term's retirement
leaves the term live, so the sweep runs against whatever has actually shipped. The `$fields` family is the
widest by far (roughly 75 Java and AsciiDoc files name it today), which is the recurrence risk
`RetiredVocabularyGuardTest`'s registry exists to catch, and the reason this list is worth carrying rather
than re-deriving at the gate.

| term | slice | successor |
|---|---|---|
| `$fields`, `$fieldsGrouped` | 3.1 | `$project`, one method per projection unit |
| `TypeClassGenerator.buildTypeSpec` | 3.1 | `render(ProjectionCommand, RenderContext)` |
| `TypeFetcherGenerator.PROJECTED_LEAVES` | 3.1 | projected-ness derived from the plan |
| `TypeClassGenerator.collectRequiredProjection` | 3.2 | gated `Project` contribution carrying the correlation columns |
| `ParentProjectionContainmentCheck`, `ParentProjectionContainmentCheckTest` | 3.2 | single-sourced correlation columns, so there is no second derivation to compare |
| `MethodCommandRegistry`, `MethodCommand` | 5 | the coordinate-keyed command relation |
| `CompileDependencyGraphBuilder` | 7 | the recompile graph as a projection over the command relation |
| the `no.sikt.graphitron.rewrite` package | end state | `command` / `plan` / `render` and the shared pure-data floor |

The sibling items declare their own: R541's launcher terms and R552's condition terms are swept at their
own gates, not here.

## Non-goals

- A generic fact bus. No `Fact` interface, no `Map<String, Object>`, no dynamically registered fact kinds.
  This is a constrained domain of roughly ten to twelve typed relations, and the relational discipline is
  a design discipline, not a runtime, per R333's own resolution.
- A query-engine runtime for the model.
- Re-platforming the whole shell in one program. Slices ship independently; the shell's renderers mostly
  do not move, they stop deciding.
- **Byte-identical emitted output as an acceptance test.** Tempting for a re-platforming, and wrong for the
  same reason code-string assertions are wrong: it makes generated text the contract, and it forbids
  improving the emit (slice 3.1 renames a method and adds arms, so it cannot hold anyway). Acceptance for
  every slice is the tiers that already exist: the compilation tier proves the emit compiles, the execution
  tier proves it runs against PostgreSQL, `MethodClosureOracleTest` proves the graph is closed,
  `IncrementalCompileHarnessTest` proves the recompile set is right, and the corpus proves the
  classification. For a pure rename, ordinary refactoring practice (rename at the definition, let the
  compiler and the closure oracle find every reference) is the discipline, not output diffing.
- Any change to emitted *behaviour*. Slices 1, 3.1, 3b and 3c change who decides what to emit, not what runs; where
  a slice does change output (slice 3.1's rename and new arms, and slice 3.2's projection it stops emitting for
  unselected children), it changes shape and never semantics. Slice 3.2's SQL change is the single
  exception the baseline rule is built around, and it is a narrowing: fewer columns, same rows.
- Changing what any directive means. The one directive whose *reach* grows is `@splitQuery` on a nesting
  field, which today is accepted and appears to do nothing; slice 3.3 gives it the launcher it implies.
  Otherwise this is entirely internal.

## Acceptance

The programme is not "done"; individual slices are. What signals it worked: the shell contains no
reference to a walked-fact hierarchy, every hierarchy names its grain and has exactly one home, the
recompile graph is a projection rather than a prediction, and a contributor adding a schema shape
registers a fact visitor and mints commands rather than adding an arm to a central switch. What signals it
failed: the ratchets stall for two consecutive slices, or a slice lands whose only product is migration.
