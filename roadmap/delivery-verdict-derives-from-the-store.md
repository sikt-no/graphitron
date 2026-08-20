---
id: R666
title: "Delivery verdict derives from the store, not from a hand-maintained negative-space switch"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-20
---

# Delivery verdict derives from the store, not from a hand-maintained negative-space switch

## Problem

Delivery (does this coordinate batch through a `DataLoader`, or fetch inline) is decided twice in
Java and stated by no relation. The store is nearer than that sounds: a shipped view,
`intent_field_separate_fetch`, already names four of the populations delivery reads, for a
neighbouring question. A section below settles what that relation does and does not answer and why
both stay. What matters here is that neither Java site is relational.
`DeliveryFactRelation.mint` is the production source that
`ProjectionCommands` and `LauncherCommands` read through `GraphitronSchema.deliveryOf`;
`DeliveryFact.leafDerivedOf` is a total switch over the sealed leaf hierarchy, kept as the
comparison side and the walk-less fallback. Neither is relational. The relation is a
`Map<FieldCoordinates, DeliveryFact>` computed by a method that switches on type verdicts, which
makes it a second switch wearing a relation's name.

The cost is concrete, and the tree has since paid it in public.
`DeliveryFactRelation.singleTableBackedVerdict` encodes delivery as **negative space**: it returns
the batched-capable predicate by enumerating what does *not* qualify, with
`case GraphitronType.TableInterfaceType _ -> false` and a matching exclusion inside its
`ConnectionType` arm. That is a closed-world claim about the whole shape space, maintained by hand,
with nothing behind it.

What the batched-discriminated-interface-child item then had to establish is the exact cost (that
item has since reached Done and its file is retired; its entry in `roadmap/changelog.md` is where
the record now lives, and every citation of it below reads the same way).
That item gave the discriminated interface child a batched delivery, and settling what the
hardcoded `false` owed it took a full reading of `mint`'s arm order: the answer turned out to be
that the `false` case stays correct as written, because the new delivery arrives as a positive arm
placed ahead of the `tableAnchoredChild` computation, and only the javadoc rationale went false.
Nothing in the code said so. The site's correctness under a new batched shape was established by a
reviewer reading arm order, which is the same as saying it was not established.

And the enumeration outlived what it enumerated. With `mint`'s `discriminatedInterfaceTarget` arm
now standing ahead of the `tableAnchoredChild` computation, every path that reaches
`singleTableBackedVerdict` has already been filtered by it, so the `TableInterfaceType` case can no
longer be reached and the `ConnectionType` arm's exclusion conjunct can no longer be false. Both
survive as dead code with a rewritten javadoc rather than being deleted, because a hand-maintained
switch has no instrument that could say a case stopped mattering. That is the same defect from the
other side: the negative space could not tell anyone it had gone stale any more than it could tell
anyone it was incomplete.

The pin does not close this. `DeliveryFactPinTest` compares the two sides, but
`DeliveryFactRelation`'s own javadoc states what the comparison is worth: "this production and the
classifier's batched-leaf arms read the same marker, source-shape and verdict facts on both sides,
pinned for regression rather than independence". Two readers of the same inputs agreeing is not
integrity, it is duplication with a consistency check. The corpus has since closed the coverage hole
that made the point vividly (a list-cardinality discriminated interface child now exists, added by
the sibling item as it changed both sites), and closing it changes nothing about the argument: a
coordinate the corpus does not carry is invisible to the pin, and which coordinates those are is
whatever nobody has thought to add. Coverage is a thing somebody remembers, on exactly the terms the
negative-space switch is.

## The exemplar is already in the tree

The store already solves this problem for a structurally identical verdict, and the delivery
question is isomorphic to it rung for rung. The demand stratum is:

* `intent_field_demand_rule (graph_name, type_name, rule)`, a `UNION` of one `SELECT` per positive
  rule over base relations (`ROOT_OPERATION` off `graphql_root_operation`, `TABLE_TYPE` off
  `graphitron_table`, `ERROR_TYPE` off `graphitron_error`, `PRODUCER_PAYLOAD` off the producer
  relations).
* `intent_field_exemption_rule (graph_name, type_name, reason)`, the same shape for the negative
  side where the negatives are themselves positive statements about a captured population.
* `intent_resolved_field_demand (graph_name, type_name, field_name, verdict, rule)`, the reduction:
  a two-value verdict plus the winning rule literal, resolved by a declared precedence order, with
  the vocabularies closed and stated in the column comments.

`DeliveryFact` is already that pair: a two-value verdict (`Batched` / `Inline`) plus a
`DeliveryFact.Trigger` naming which rule produced it (`Authored`, `RecordHandedParent`,
`PolymorphicFanIn`). It is the demand stratum's shape, expressed as a sealed hierarchy computed by
a switch instead of as a view over keyed relations.

## What the shape would be

`intent_field_delivery_rule (graph_name, type_name, field_name, rule)` as a union of positive arms,
one per trigger; `intent_field_delivery_exemption_rule (graph_name, type_name, field_name, reason)` for
the negatives that are stated rather than absent; and `intent_resolved_field_delivery (graph_name,
type_name, field_name, verdict, rule)` as the reduction under a declared precedence. Most `INLINE`
is the complement, the absence of any arm, rather than an enumerated set of shapes somebody has to
remember to keep current. The exceptions are the three populations below, and they are why the
exemption relation exists rather than being a file-layout choice copied from the sibling.

Two shape decisions, both settled here because they land in R682's read surface rather than only in
the shadow test. The names carry one noun across the stratum, `delivery`, the way the sibling's carry
`demand`: three relations a reader meets in `SHOW VIEWS` should be recognisable as one stratum, and
an earlier draft's `batching_rule` broke that for a word the `rule` column already says better.
Nothing is lost, because the literals still name batching triggers; the relation says which verdict
it rules on, the row says which trigger produced it. The noun is not free, and the collision is
worth stating rather than discovering: `intent_delivery_container` already spends it on an unrelated
sense, the Java container classes the declared-type peel descends. The `_field_` infix is what keeps
them apart, and it is load-bearing rather than decorative, so all three relations carry it; a reader
meeting `intent_field_delivery_*` beside `intent_delivery_container` should be able to tell from the
key alone that one is about coordinates and the other about classes. The `_rule` suffix rides on both
rule views for the same reason and against an earlier draft's bare
`intent_field_delivery_exemption`: the sibling pair reads as one stratum because
`intent_field_demand_rule` and `intent_field_exemption_rule` share the suffix, and dropping it on the
negative half would spend the recognisability the paragraph is arguing for. The `reason` column keeps
its own name there, as the sibling's does. And **the authored grain here is the
coordinate**, unlike the sibling's, whose rule views are type-keyed on the stated ground that "every
rule shipped so far is a property of the parent type". Delivery's are not: every batching arm reads a
marker or a target on the field itself. The one exception is the root exemption, which is authored on
the parent type and projects to its fields, so it is the sibling's grain appearing as a single arm
inside a coordinate-grained relation rather than a reason to key the relation differently. Say so in
the view comment, because the sibling's comment argues the opposite default and this item's other
instruction is to copy it.

The property worth naming: **every arm is additive**. A new batched delivery is a new `SELECT`
`UNION`ed in, joining the base relations that already witness it. Nothing elsewhere has to be
edited to stay true, which is the difference between this and the `singleTableBackedVerdict` case
that started the item. That is the fact model's stated law ("a capability is added by adding a fact
relation, never a new leaf type") applied to a verdict that never got the treatment.

Integrity moves to the instruments the store already uses. Two of the three arrive by inheritance
rather than by declaration, which is worth being exact about, because a view takes no constraints
and an earlier draft of this section asked for two that cannot be written:

* **The closed vocabularies are declared in the column comments.** That is the
  `intent_resolved_field_demand` mould read literally: its `verdict` column says "a closed two-value
  vocabulary" and its `rule` column draws its literals from the rule views' vocabularies "in their
  declared precedence order", with the enumeration itself sitting on the rule view that owns each
  vocabulary (`intent_field_demand_rule.rule` names its four). None of them is a `CHECK`, because
  H2 views cannot carry one. No `intent_` view in the model
  does. `FactSchemaGateTest.commentCoverageIsTotal` is what makes the comment mandatory, so the
  declaration is build-enforced even where the constraint is unavailable, and what holds the rows
  inside the vocabulary is that each arm emits its literal as a constant. The gain over the switch is
  unchanged: the closed set is written where it is read instead of being implied by which arms
  somebody wrote.
* **The `graphql_field` foreign key is inherited, not declared.** Every `graphitron_` marker relation
  already carries it, so an arm joining one cannot name a coordinate capture never saw; the
  guarantee rides through the projection rather than being restated on it. The switch has no
  analogue, keying a `Map` on whatever the walk happened to mint.
* **Ambiguity becomes a question the model can ask**, in the `intent_authored_claim_conflict` mould.
  Not a deliverable here, and the reason is worth stating so nobody looks for a fourth relation: that
  view reports genuine violations, whereas the precedence this item declares is total, so a delivery
  analogue is empty by construction on landing. Overlap is designed in rather than pathological, as
  the root coordinate below shows. What the store buys is that the question is expressible at all. A
  switch fallthrough is not a row and cannot be counted; two arms claiming one coordinate can be.

### The nearest relation already ships, and it is a sibling rather than this one

`intent_field_separate_fetch` landed on 2026-08-15, after this item was filed, and it is closer to
the proposal above than the demand stratum is. It is coordinate-grained, carries a `rule` column,
is registered `Arm.DERIVED`, and its five arms are `SPLIT_QUERY` off `graphitron_split_query`,
`TENANT_FAN_OUT` off `graphitron_tenant_fan_out`, `SERVICE` off `graphitron_service` at a non-root
parent, `ROOT_OPERATION` off `graphql_root_operation`, and `RECORD_HANDED_PARENT` off
`intent_type_backing_class`. It has live readers: the LSP renders it
through `SeparateFetchRule`, `InlayFacts.marksSeparateFetch` and `DeclarationHovers`, and
`SeparateFetchVocabularyTest` seals its vocabulary against the words an editor shows.

**Both relations stay, and neither derives from the other.** The questions differ, and they differ
exactly where this item's exemptions are. Separate-fetch asks whether a field costs a second trip to
the database, which is what a schema author reads. Delivery asks whether a coordinate batches through
a `DataLoader`, which is what a planner reads. A root operation is fetched by its own statement and
is not batched; a `@service` call is its own fetch and its delivery is owned by the serviceCall
member. So those two populations are *positive rows* in separate-fetch and *exemptions* here, and the
next section is where that is established from `mint` rather than asserted. Two relations disagreeing
on a population both can see is the signal that they answer different questions, not that one is
wrong.

That is the stratum's own provenance rule rather than a preference. `intent_type_backing`'s comment
states it for its own pair: "each population is derived by its own rule from its own facts and
neither is a special case of the other, so they are separate relations". Delivery reads the same base
relations separate-fetch reads, by its own rule, so it is a second relation over shared facts and not
a view over a view. Building delivery on separate-fetch's rows would mean re-interpreting a verdict
whose `SERVICE` and `ROOT_OPERATION` arms delivery has to invert, which is worse provenance than
joining the markers directly.

Four instructions follow for whoever writes the DDL.

* **The literals stay identical on purpose.** `SPLIT_QUERY`, `TENANT_FAN_OUT` and
  `RECORD_HANDED_PARENT` name the same three captured facts in both vocabularies, and the ground
  settled under "Open for the implementer" is
  that the vocabulary names facts rather than decisions. One fact spelled two ways across one
  namespace would be the worse outcome; a reader joining the two relations on a coordinate should
  meet the same word. The two closed sets stay separate (delivery's carries `POLYMORPHIC_FAN_IN`,
  which is not a round-trip rule; separate-fetch's carries `SERVICE`, which delivery inverts), and so
  do their rendering sides: a
  delivery consumer that needs constants gets its own, and `SeparateFetchRule` is not widened.
* **The root arm is a transcription, not a new derivation.** Separate-fetch's `ROOT_OPERATION` arm is
  gate-for-gate what the `ROOT_COORDINATE` exemption needs, down to keying on `graphql_root_operation`
  rather than the three conventional names, and its comment records the renamed-root difference in
  the same words the demand sibling uses. Copy the arm; do not re-derive it. What does *not* transfer
  with it is that difference's standing as a residue: it is one for the sibling and for demand, and
  the negative-side section below establishes that delivery's absent-coordinate default collapses it
  into agreement.
* **The record-handed arm is a transcription too, and this is the instruction that changed latest.**
  That arm landed in `intent_field_separate_fetch` eighteen minutes after this item's previous
  revision, so the section below derives its population from first principles and arrives at the
  right relation by the longer road. The derivation stands, because it is why the arm reads what it
  reads; the *gate* to write is the shipped one. Two of its clauses the from-scratch reading does
  not reproduce, and both matter. It anti-joins the parent's binding over `intent_bound_table`
  rather than over `intent_type_backing`'s `BOUND_TABLE` arm, because that arm drops a table whose
  generated model has no record class (`record_class_fqn = 'org.jooq.Record'`, stated in the view's
  own comment), so a `@table` parent with no generated record has no `BOUND_TABLE` row to lose the
  precedence with and a closure-gated arm would call it record-handed. And it guards the parent's
  kind at `OBJECT`, which the domain join does not subsume: `intent_type_domain` holds "every named
  type, of every kind", while the backing closure holds input objects beside objects and an input
  coordinate is not a delivery. Copy the arm, add `candidates = 1` on the child binding (the sibling
  deliberately leaves it off, its comment pointing a reader wanting the walk's reading at that
  column), and inherit the two departures its comment already records: an ambiguously bound child,
  which the arity filter removes here, and a `@table` interface child at either cardinality, which
  the `DISCRIMINATED_TARGET` exemption and the fan-in arm answer between them. Both are populations
  this item had already reasoned to independently, which is corroboration that the two relations are
  reading one fact. Two clauses are *not* copied, and each is settled in its own section below. Both
  of the sibling's joins name `intent_bound_table` where delivery's name
  `intent_resolved_type_binding`,
  because the sibling holds the `@table` population deliberately for a parent-side question delivery
  does not ask and delivery's own walk-side predicate follows a `@routine` chain's return binding.
  And the sibling's target join names `f.named_type` where delivery's names the authored type read
  through `graphitron_field_synthesis`, its comment recording the connection wrapper as a population
  it does not reach; delivery cannot inherit that gap, because a discriminated `@asConnection`
  carrier is a shipped corpus coordinate its arms have to answer. Those two are the only places the
  transcription instruction does not govern.
* **Say the relationship in both view comments.** A reader meeting two coordinate-grained rule views
  that share two literals and disagree on two populations needs to be told, at each of them, that the
  disagreement is the design. This item owns the comment on its own three views; amending
  `intent_field_separate_fetch`'s to point back is a one-line edit inside this item's scope.

Nothing is left outside this item on that view. An earlier revision recorded its deferred
class-backed-parent arm as a finding to hand back to whoever owns it, the stated blocker ("needs the
backing-class resolution the census does not yet carry") having been closed by
`intent_type_backing_class` landing on 2026-08-16. That arm has since been written, which is why the
instruction above is to copy it rather than to note it.

### Delivery's negative side is not pure complement, and the corpus already proves it

An earlier draft asserted that it was, and left the shape of the view set open on that basis. It is
wrong, in three places, and each has a coordinate in `ClassifiedCorpus` today that would disagree
with a complement-only view. The first two are short-circuits at the top of
`DeliveryFactRelation.mint`, which is what makes them easy to read past: they return before the rules
they override are ever evaluated. The third is the hardcoded `false` this item was filed over.

* **The `@service` claim, reason `SERVICE_CALL`.** `mint` returns `Inline` for a `@service`
  coordinate before any other rule, on the stated ground that the call is the delivery and the
  serviceCall member owns it. That precedence is load-bearing rather than defensive. Take
  `Aggregated.filmsViaService: [Film!]!` from the `service-child-class-backed-parent` example: its
  parent is a class-backed producer payload, so `sourceShape()` is `Record`, and its target `Film`
  is a plain `@table` type, so `tableAnchoredChild` holds. Delete the short-circuit and that
  coordinate mints `Batched(RecordHandedParent)`. A `RecordHandedParent` arm written from the
  captured facts alone matches it for exactly the same two reasons, so a view without the exemption
  reports `BATCHED` where the walk reports `INLINE`.
* **The root coordinate, reason `ROOT_COORDINATE`.** `mint`'s first line returns `Inline` for
  anything that is not a `ChildField`, because nothing arrives at a root so nothing splits. The
  store has no notion of leaf identity, so "is a child" has to be said out loud, and the fan-in arm
  is where it bites: `Query.people(firstName: [String!]): [Person!]!` in the `polymorphic-filter`
  example is a list-valued union root whose members are both `@table`, which is the fan-in arm's
  store-side predicate met in full. Scoping by `intent_type_domain` does not remove it, since the
  domain contains root types by construction and the demand sibling registers their fields under its
  own `ROOT_OPERATION` arm. Say it with `graphql_root_operation` rather than with the three
  conventional names, matching the sibling, but record that this is a *different rule* and not a
  transcription: `mint`'s "not a `ChildField`" resolves through `FieldBuilder`'s `parentType
  instanceof RootType` dispatch to `TypeBuilder`'s literal `Query` / `Mutation` / `Subscription`
  set, its only mint site, while `graphql_root_operation` carries the binding, whose name-convention
  default coincides with those three exactly until a graph spells `schema { query: MyQuery }`. The
  demand sibling meets the same fork and pins the difference as a population, its view comment
  naming "a renamed root's fields" as "a known demanded-but-unregistered population the shadow
  agreement pins". **This item inherits the fork and not the population, and the difference is
  worth stating because it is what keeps a residue off the roster below.** Demand's verdict *is*
  registration, so a coordinate demanded store-side and unclassified walk-side is a real
  disagreement there. Delivery's is not: `DeliveryFactRelation.compute` indexes only the classified
  `OutputField`s, `deliveryOf` answers `DeliveryFact.Inline.INSTANCE` for every coordinate outside
  that index, and a renamed root is not merely unregistered but absent from the walk entirely,
  along with all of its own fields, since the `RootType` mint gate is the three literal names. So
  the walk reads `Inline` at every coordinate of a renamed root whatever markers it carries, and
  the store's `ROOT_COORDINATE` exemption reads `INLINE` there too. The two sides agree, which is
  checkable rather than argued: over `schema { query: MyQuery }` with
  `MyQuery.splitFilms: [Film!]! @splitQuery` beside a marked child of a `@table` parent, the walk
  indexes the second coordinate and reads `Batched(Authored)` on it while the first is absent from
  `fields()` and reads `Inline`. Keep the arm keyed on the binding all the same, on the sibling's
  stated ground that a rule view states the intended rule rather than the walk's dispatch; what
  changes is only that nothing needs excusing. Note that the agreement holds for a stronger reason
  than the corpus: no corpus example spells a `schema` block, so the fork is invisible to the shadow
  test either way, but here it would stay invisible to a corpus that did spell one.
* **The discriminated target, reason `DISCRIMINATED_TARGET`, at single cardinality only.** `mint`'s
  discriminated arm returns `Inline` at single cardinality and `Batched(PolymorphicFanIn)` at list
  or connection cardinality, ahead of both the `tableAnchoredChild` computation and the marker
  reads, so a *single-valued* discriminated interface child reads `Inline` whatever its parent hands
  it and whatever markers it carries. `Inventory.media` in the `table-interface` example is the
  witness, a single-valued discriminated interface child on a `@table` parent. The list half is not
  an exemption at all, and needs no arm of its own: the fan-in rule arm already stands at those
  coordinates, for the reasons the fan-in trace below gives, and reports the same
  `POLYMORPHIC_FAN_IN` literal the walk's trigger carries there. `Language.mediaList` in the same
  example is that coordinate, and `Language.mediaConnection` beside it is the `@asConnection` half,
  whose authored bare list puts it on the same arm walk-side and whose synthesis row puts it there
  store-side, per the fourth predicate below. The connection cardinality forks once more
  underneath that, at a *structurally declared* wrapper the fan-in trace below sends to a residue
  rather than to either side of this bullet. Everything the rest of this bullet establishes about *which* captured
  population the arm reads holds unchanged at either cardinality; only the cardinality gate is
  new. **The captured population is not
  `graphitron_discriminate` alone**, which is the name-matching trap the two predicate entries below
  record, met a third time. `@discriminate` is declared `on INTERFACE | UNION` and nothing rejects it
  on a target carrying no `@table`, while `TypeBuilder` mints a `TableInterfaceType` only where an
  interface carries both markers (its participant pass and its type dispatch state the conjunction
  independently, and agree). So the arm is `graphitron_discriminate` joined to `graphitron_table` at
  `graphql_type.kind = 'INTERFACE'`, keyed on the target type and projected onto the coordinates
  returning it, which is the projection the root arm already makes. On the marker alone the arm would
  also exempt a `@discriminate`-bearing union and a `@table`-less `@discriminate` interface, both of
  which `mint` sends to the fan-in arm; since this exemption outranks every rule arm, the store would
  report `INLINE` at a coordinate the walk reports `BATCHED`. No corpus coordinate carries either
  shape, so the wide arm ships green, which is why the narrowing is stated here rather than left for
  the shadow test to find. The precedence against the marker arms needs no judgement call, because
  the walk now states it: the discriminated arm returns before the marker block is entered at all,
  and its own comment records that placement as deliberate, so "the redundant `@splitQuery` an author
  may write on it cannot claim the trigger at either cardinality". The exemption therefore outranks
  the `SPLIT_QUERY` arm including its `@pivot` disjunct, and on the list half the fan-in row beats
  the split row by the rule arms' own declared order. Transcribe that rather than choosing it. Both
  sides are witnessed: `Film.splitContent` and `Film.splitContents` in `DeliveryFactPinTest`'s
  `MARKER_FIXTURE` are a marked single and a marked list of exactly this shape.

Three consequences, and together they settle what the open questions used to ask.

The relation set is the sibling's, rung for rung: a positive rule view, an exemption view whose
negatives are positive statements about a captured population, and a reduction that declares the
precedence between them. That is now a structural conclusion rather than a layout preference.

The alternative, folding each exemption into the arm it overrides as a `NOT EXISTS`, is available
and should be declined. It is negative space inside an arm, which is the pattern this item exists to
kill; it also spreads one rule across every arm it defeats, so the `@service` claim would have to be
restated in each future batching arm, which is the additivity property gone. That argument governs
`@discriminate` too, and two earlier drafts exempted it from its own rule: they spent the
discriminated target as an anti-join carried into every rule arm rather than as a row. It is a row.

And all three exemption arms are witnessed on landing, by the three coordinates named above, which is
worth contrasting with the `@tenantFanOut` literal the Implementation section flags as reaching no
coordinate at all. The exemption view cannot ship vacuous.

**Every input is already captured.** This is the finding that makes the item view-only rather than
a capture project, and it survives a relation-by-relation check, but only a check at that grain: the
inventory is wider than a first read suggests, and picking the relation whose *name* matches an arm's
vocabulary is how three successive drafts got an entry wrong. The arms need
`graphitron_split_query`, `graphitron_tenant_fan_out`,
`graphitron_pivot`, `graphitron_routine`, `graphitron_discriminate`, `graphitron_table` (raw at two
arms, the discriminated presence test and the fan-in's participant conjunct, each for its own reason
the arms section gives; for a *binding* the arms read `intent_resolved_type_binding`, which is a
fourth entry a draft got wrong the same way and the arms section settles), `graphitron_service`,
`intent_type_backing_class` (the record-handed
arm, and the entry the third draft got wrong; the second predicate below is where that is
established), `graphql_field.is_list`, `graphql_type.kind`,
`graphql_implements`, `graphql_union_member`, `graphql_root_operation` (the root exemption arm,
keyed the way `intent_field_separate_fetch`'s `ROOT_OPERATION` arm already keys it, by the binding
rather than by the conventional names, which is that arm's keying inherited from the demand
sibling), and `graphitron_field_synthesis` (the entry a draft missed altogether rather than
mis-picked, and the one that decides what every other arm joins *on*; the fourth predicate below is
where that is established). All exist, all are keyed
at the coordinate or type grain the arms would join on, and the marker relations already carry the
`graphql_field` FK.

Two families are deliberately absent from that list, each established below. The `sql_` catalog
family, per the second predicate. And the structural connection recognition over `graphql_field` /
`graphql_type`: the third predicate establishes that `graphitron_connection` is the wrong relation
for the arm whose name it matches, and the arms section then settles that no arm resolves a declared
wrapper's element, so what that recognition would have served is the wrapper residue rather
than a join. An arm's inputs are what the arms section's table lists, and the connection shape is not
among them. The `@asConnection` half of the population is answered without the recognition too, by
the synthesis row rather than by the shape, which the fourth predicate settles; a connection is
never something an arm tests for structurally.

Four predicates want attention before an arm is written, for four different reasons. One needs
real care. A second looks like it does and does not, and the reason it does not is worth stating,
because the obvious reading sends the arm at the catalog for nothing. The third is the one the
inventory got wrong twice, by the same name-matching reflex that put the `RecordHandedParent`
trigger on the wrong relation in the predicate before it. The fourth is not a relation choice at
all but a reading of `graphql_field` itself, and it is the one that moves every other arm: what the
store holds at a coordinate is not always what its author wrote there.

**The fan-in arm's gate is non-discrimination, not participant boundness.** The tempting reading is
that the arm has to tell `ParticipantRef.TableBound` from `JoinedTableBound`, because
`DeliveryFactRelation.anyTableBoundParticipant` tests `TableBound` specifically rather than the
`TableBacked` supertype. That narrowing cannot change a verdict, and the trace is short. The arm is
only entered when `unwrapped instanceof TargetShape.Interface || TargetShape.Union`, and
`ChildField.target()` gives `TableInterfaceField` a `TargetShape.Table`, so no discriminated
interface child, single-table or joined-table, reaches the arm at all. Underneath,
`anyTableBoundParticipant` reads participants only from `GraphitronType.InterfaceType` and
`UnionType`; a `TableInterfaceType` falls to its `default -> List.of()`. And `JoinedTableBound` is
minted at one site in `TypeBuilder`, behind an `interfaceTable != null` guard that only the
`TableInterfaceType` construction satisfies (the `InterfaceType` and `UnionType` constructions both
pass null). So a `JoinedTableBound` participant cannot reach the predicate that would reject it.
The batched-discriminated-interface-child item states the same participant invariant
independently, as the reason it must not copy this guard's shape.

One asymmetry to hold onto, because it looks like a disagreement and is not. The walk's fan-in arm
and the store's do not have the same population: the discriminated list child reaches the walk's
*own* discriminated arm and the store's *fan-in* arm, two different arms that mint the same
`PolymorphicFanIn` / `POLYMORPHIC_FAN_IN` value. That is the shadow comparison working as intended,
which compares the verdict and the winning literal rather than the route to them. Nothing about the
trace above changes; it establishes what the walk's arm sees, and the store's gate below is stated on
its own terms rather than transcribed from it.

A second asymmetry sits one helper over, and unlike the first it does reach an arm.
`mint`'s three verdict helpers disagree about connection wrappers.
`discriminatedInterfaceTarget` and `singleTableBackedVerdict` each carry a `ConnectionType` arm
that resolves `elementTypeName` before answering; `anyTableBoundParticipant` carries none, reading
`types.get(baseTypeName(fieldDef))` and falling to its `default -> List.of()` when the authored type
is a declared connection object. The two shapes therefore part company at a child returning a
*structurally declared* connection. Over a plain interface or union the walk returns `Inline`, the
participant scan never seeing past the wrapper; over a `@table @discriminate` interface it returns
`Batched(PolymorphicFanIn)` through the discriminated arm's element resolution. `@asConnection`
reaches neither case *on the walk side*: `mint` reads the pre-rewrite schema, where the authored
expression is still the bare list, so the participant scan and the discriminated arm both see the
element directly. The store side does not come free that way, and the fourth predicate below is
where that is settled: `graphql_field` holds the expansion rather than the authored expression, so
the arms reach the same element and the same cardinality through `graphitron_field_synthesis`
instead of by reading the coordinate's own columns. The corpus's `Language.mediaConnection` is the
coordinate where that distinction is load-bearing.

Three consequences, and all three narrow the arms rather than widening them. **The fan-in arm's gate
is authored cardinality and not connection-shapedness**: firing the arm on the wrapper *as a shape*
would fire it exactly where the walk's participant scan does not, manufacturing a disagreement that
does not exist today, which is why the store-side gate reads the macro fact rather than recognising
a connection structurally. **The
`DISCRIMINATED_TARGET` exemption's `no connection shape` conjunct is inert rather than load-bearing**,
because a coordinate returning a declared connection object is not among "the coordinates returning
the type" the exemption projects onto in the first place; it is kept as a stated guard rather than
deleted, so a later revision that widens the projection cannot silently pick the shape up. **And the
structurally-connection-wrapped discriminated child is a `DeliveryResidue` entry, not an arm
clause.** Teaching the fan-in arm to resolve the element only when the element is discriminated
would transcribe a disagreement among the walk's own three helpers into SQL, which is the move this
item already declines for the renamed root. That entry is not this shape's own: it is the
structurally-declared connection wrapper the arms section states once for every arm, reached here
from the participant side, and its removal criterion is stated there. No corpus coordinate is one,
per the connection bullet in the Implementation section, so nothing observes the gap today either
way.

The arm itself, then. Its store-side gate is that the target is an interface or union,
list-valued, with at least one table-bound participant: `graphql_implements` /
`graphql_union_member` joined to `graphitron_table`, over `graphql_field.is_list`. Every input is in
the inventory above and every hop in this arm is single. It
carries no `@discriminate` anti-join, and since that shape gained a batched delivery the arm is what
positively reports its list half: a list-valued discriminated interface target is an interface with
table-bound participants, so the arm fires there and its literal is the trigger the walk mints. At
single cardinality the arm does not fire and the exemption answers instead. The arm likewise stays
unmasked against the root exemption, which is the sibling's discipline (its rule views let
overlapping readings survive as rows and give the reduction the meet), so this arm does carry
`Query.people` and the reduction is where that row becomes `INLINE`. And the `sql_`
catalog family is not needed by any arm, which is why it is absent from that inventory: no arm's
predicate reaches a foreign key or a primary key. If an implementer finds one that does, that is a
finding worth recording rather than a gap to fill quietly.

**Record-handedness is neither `@record` nor the producer payload set.** Two relations match this
arm's vocabulary and neither is the one to join. `graphitron_record` captures the deprecated,
ignored `@record` directive, which is the obvious miss. The second is the one a draft of this item
actually made: `intent_field_demand_rule`'s `PRODUCER_PAYLOAD` arm assembles the `@service` /
`@externalField` payloads whose class decoded plus every `@mutation` payload, it reads like the
record-handed population, and it is a *seed set* rather than the population.

`ChildField.sourceShape` states what the trigger reads: a projection of the *parent type's backing*,
`Record` exactly when the parent hands a domain record rather than a catalog row. The store states
that directly, as of 2026-08-16. `intent_type_backing` answers what class stands for a type, over
two arms: `BOUND_TABLE`, the resolved table binding read through the table's generated record, which
reads `intent_resolved_type_binding` and not the `@table` population alone, and
`BACKING_CLOSURE`, which is `intent_type_backing_class`, the reachability of
`intent_field_accessor_hop`'s edges from the producer-grounded seeds. Those seeds are
`intent_type_backing_seed`, which stands on the same ground `PRODUCER_PAYLOAD` does, a producer's
resolved method, and reaches it more exactly (through `intent_field_producer_method` and the
declared return element, rather than through the marker rows and the field's named type). So the
payload arm is where a closure the store now carries whole begins, and a parent reached only by an
accessor hop (a nested class-backed carrier, the record-composite carrier's data-field element)
hands records while no producer relation names it.

That gap is witnessed rather than hypothetical: `DemandResidue.reflectionBound` exists for exactly
that population, and `DemandShadowTest` pins its registered-but-undemanded direction inside it over
today's corpus. An arm copying `PRODUCER_PAYLOAD` would therefore have shipped with a residue the
size of the accessor closure, described in this item as a single pivot-slot member.

**The arm's gate is a class backing on the parent type with the parent's own binding anti-joined
away**, and one precedence fact goes with it, because `intent_type_backing` coalesces its two arms
and deliberately prefers neither: a type
both arms answer is two rows, and `intent_type_backing_conflict` is where a reader learns so. The
walk resolves that pair by reading the table and never consulting the class, which that view's
comment calls a defensible reading and explicitly a choice rather than agreement. This arm makes the
same choice out loud: a parent whose type is bound to a table is table-handed whatever its closure
says, which is what `sourceShape`'s table arms do. State it in the view comment; do not leave it to
the join.

Where that choice is *stated* is the correction the sibling instruction above carries. Reading it as
"no `BOUND_TABLE` row on the parent" spends the coalesced view for a precedence the binding relation
holds more exactly, and loses a population on the way: `intent_type_backing`'s table arm requires the
table to have a generated record class, so a `@table` parent whose table reports `org.jooq.Record`
is absent from that arm and is not table-handed by it, while its binding is a fact all the same. The
shipped arm anti-joins `intent_bound_table` for exactly that reason and says so; this arm reads
`intent_type_backing_class` and does the same, over `intent_resolved_type_binding` rather than over
the sibling's narrower relation, per the arms section's binding paragraph. What transfers is the
choice of a binding relation over the coalesced backing view, not which binding relation.

One residue survives and it is the one already named: the pivot-slot record parent, whose source is
the pivot subselect's graphitron-built jOOQ record, so no class backs it on either arm.
`SourceShapeProjectionTest` states the parent-backing predicate independently of the leaf
identities, so it is the cross-check to read before writing the arm.

**A connection target is not `@asConnection`, and the inventory entry was inverted.**
`graphitron_connection` captures the `@asConnection` macro's authored spec, one row per carrier
field. It is not the relation that witnesses "this coordinate's target is a connection", and the
reason is worth following because it points the arm the other way. `mint` reads the *pre-rewrite*
schema, so for an `@asConnection` carrier it sees the bare list the author wrote
(`films: [Film!]!`) and `singleTableBackedVerdict` resolves it straight through its
`TableBackedType` arm, never entering the `ConnectionType` arm at all. Store-side the same
coordinate reaches its bound table in one hop *through the authored-type reading the fourth
predicate below establishes*, and the marker is not needed there either. The `ConnectionType` arm
is entered only when the authored base type is *itself* connection-shaped, which is
`BuildContext.isConnectionType`'s purely structural test (an object whose `edges` field's element
type has a `node` field) and carries no `graphitron_connection` row anywhere:
`ConnectionPromoter`'s structural arm references the SDL-declared type instead of synthesising one.
So the marker's rows are the population the arm never sees, and the arm's population has no marker
rows. Both directions of the inventory entry were backwards.

Two consequences, and neither of them is an arm. Recognising the shape is expressible, and the store
already states it once: `intent_field_exemption_rule`'s `CONNECTION_MACHINERY` arm assembles the
structural edges/node pattern in SQL, so an arm that ever needs the recognition has a model to copy,
the way the `RecordHandedParent` trigger copies `PRODUCER_PAYLOAD`. But *resolving the element the
verdict anchors on* (`ConnectionType.elementTypeName`, the `edges` element's `node` type) is a
further walk of `graphql_field` that no shipped relation publishes, and the arms section spends the
whole shape on a residue rather than on a predicate: no arm resolves a *declared* wrapper's element,
the arms join the authored named type, and the structurally declared wrapper is one
`DeliveryResidue` entry. So this section's finding is
what saves the implementer from an arm keyed on `graphitron_connection`, not an instruction to build
the other one. Every arm the table declares is a single hop, which is what the materialization
question turns on; the closing section states it in the form that survives.

**That residue's removal criterion is one column, and stating it that way is part of the finding.**
"The store gains a connection-element relation" prices it as absent work when the walk is already
written: the `CONNECTION_MACHINERY` arm joins `graphql_field` at `field_name = 'edges'` to its
element object type and on to that type's `node` field, and stops short of projecting `node`'s named
type. Publishing that projection is what closes the entry, and it is deliberately not in this item's
scope: the residue costs nothing while it stands, and an item that ships three views plus a fourth
whose only consumer is a residue it also declares has widened itself for no consumer. Record the
criterion in those words in the `DeliveryResidue` entry, so whoever picks it up prices it at the
join it is rather than at the relation it sounds like.

**`graphql_field` holds the expansion, not the expression the author wrote, and every arm joins
through that.** This is the predicate that moves the others, and it is easy to get wrong in the
direction that ships green over today's corpus and disagrees at a shipped coordinate. The tempting
reading is symmetry: `mint` reads the pre-rewrite schema, capture reads the pre-synthesis registry,
so both see what the author wrote. The first half holds; the second does not, and the reason is the
sentence right after the one it comes from. `MacroCapture`'s javadoc says capture reads the registry
before the pipeline's synthesis rewrites *and therefore runs the expansion itself*, so
`SdlFactCapture` writes the field's row from `MacroCapture.expandedFieldType` under a comment naming
it "the expansion's result, not the expression the field was written with", and
`graphitron_field_synthesis`'s table comment says the written form "survives here while the field's
`graphql_field` row holds the expansion's result". At `Language.mediaConnection` the store therefore
holds the minted wrapper's name and `is_list` false, where the arms need `MediaItem` and a list.

Both readings the arms need are recoverable, and neither is new ground.

* **The authored named type is the shipped `COALESCE`.**
  `COALESCE(REPLACE(REPLACE(REPLACE(fs.authored_type_sdl, '[', ''), ']', ''), '!', ''), f.named_type)`
  over a `LEFT JOIN graphitron_field_synthesis fs`, written exactly that way twice already, in
  `intent_field_column_scope`'s named-type rule and in `intent_routine_return_binding`, each under a
  comment saying a connection field reads its element type rather than its wrapper. A third use is
  transcription, and the idiom's two existing homes are the argument that it is the stratum's way of
  asking this question rather than a local trick.
* **The authored cardinality is the synthesis row's own existence.** The idiom above recovers a name
  and nothing reads a bracket back out of `authored_type_sdl`, which invites the conclusion that
  listness is unrecoverable. It does not follow, because the listness is not in the text.
  `MacroCapture.expandedFieldType` writes the synthesis row only after its
  `unwrapped instanceof ListType` guard passes and its element resolves to a `TypeName`, returning
  the field's own type untouched and writing nothing on either miss, and `macro` is a one-value
  closed set. So a row with `macro = 'CONNECTION'` *is* the fact that the authored expression was a
  bare list of a named type, and the arms read cardinality as a presence test over a captured fact
  rather than as a new reading of a text column:

  ```
  f.is_list OR EXISTS (SELECT 1 FROM graphitron_field_synthesis fs
                        WHERE fs.graph_name = f.graph_name AND fs.type_name = f.type_name
                          AND fs.field_name = f.field_name AND fs.macro = 'CONNECTION')
  ```

The disjunction is the closer transcription as well as the cheaper one, which is why it is stated
here rather than left as an implementation detail. `mint` does not read authored brackets either: it
computes `listOrConnection` as `shape instanceof TargetShape.Connection || leaf.target() instanceof
Target.List`, unioning the connection shape with the list rather than asking what the author typed.
The store-side disjunction is that same union, read off the macro fact instead of off a target
shape. Two alternatives are declined rather than left open. Giving `graphitron_field_synthesis`
structured columns for the authored element and its wrappers would be correct by the fact model's
own lights and buys nothing the presence test does not already have, at the price of capture work
and a `depends-on` edge. Sending the `@asConnection` carrier to the wrapper residue beside the
structurally declared one is cheaper still and costs the item a shipped corpus coordinate and its
own success test, which is the wrong trade for a reading the store already supports.

Two things follow for the sections above, both folded in there rather than left here. The three
binding arms and the exemption's projection join the `COALESCE` rather than `f.named_type`, and the
two cardinality gates read the disjunction rather than `graphql_field.is_list` alone. And the
sibling section's transcription instruction gains its second not-copied clause:
`intent_field_separate_fetch`'s arm joins `f.named_type` and its comment records the connection
wrapper as an absent population, so a verbatim copy inherits that gap. The structurally declared
connection is untouched by any of this and stays a residue, carrying no synthesis row, so both the
`COALESCE` and the presence test fall through and no relation names its element.

### The arms, once

The rules are stated across the sections above in the order the reasoning needed them, which is not
an order anybody writing the DDL can use. Collected, so the arm list has one place to check itself
against. Precedence runs down the table: every exemption arm beats every rule arm, and within each
side the first matching arm names the row.

Two readings are shared by the whole table and are named once here rather than repeated per arm, per
the fourth predicate above. **The coordinate's authored named type** is the `COALESCE` over
`graphitron_field_synthesis`, which every target join uses in place of `f.named_type`. **The
coordinate's authored cardinality** is `f.is_list` disjoined with the presence of that coordinate's
synthesis row, which both cardinality gates use in place of `f.is_list` alone. Where the table below
says "the coordinate's named type" or "list-valued", it means these.

One fact is shared on top of them, and it is worth naming once as a relation rather than as a coined
predicate:
**the target's bound table**, `intent_resolved_type_binding` with `candidates = 1`, joined on the
coordinate's authored named type with no element resolution of its own. Three rule arms read it; the
fan-in arm does not, its target being polymorphic rather than table-bound. Both halves of that
sentence are corrections a review made against a shipped witness, and each has its own paragraph
below: the relation is the resolution rather than `intent_bound_table`, and the connection element
is a residue rather than a join.

**The fan-in arm's participant conjunct reads the raw marker, and that is not the lapse the
paragraph above corrects.** It looks like one: the conjunct asks a table question, and three sibling
arms answer their table question with the resolution rather than with `graphitron_table`. The raw
marker is the matching relation here, and the reason is on the walk's side of the join.
`DeliveryFactRelation.anyTableBoundParticipant` matches `ParticipantRef.TableBound`, which
`TypeBuilder.buildParticipantList` mints only from a `TableBackedType` verdict returned by
`TypeBuilder`'s participant classification, and that pass resolves through `classifyType` alone;
`TypeBuilder.routineReturnVerdict` is reached elsewhere in that class and never from the participant
pass. So an implementor bound only by a `@routine` chain's return is `ParticipantRef.Unbound`
walk-side and carries no `graphitron_table` row store-side, and the two sides already agree. Routing
this join through the resolution to match its siblings would fire the arm where the walk's
participant scan does not, which is the manufactured disagreement the fan-in section declines on the
connection shape, met again from the participant side. Read the raw marker and say why in the view
comment, so the next reader does not repair the asymmetry.

Two earlier drafts coined a `table-anchored target` predicate here instead, folding four things into
one hand-named concept: the target's kind, its binding arity, whether it carries `@discriminate`, and
the connection element walk. The language-server fact-store item (R638, since Done; its record is in
`roadmap/changelog.md`) retired exactly that shape at the classification label, and its reason
transfers without translation. A name folding several
independent facts into one word makes a relation enumerate the combinations, which is the monolith
the fact model exists to take apart, so the store publishes each fact and a reader joins the relation
that owns the one it wants. Here the binding and its arity are `intent_resolved_type_binding`,
`@discriminate` is the exemption arm below, the connection element is the residue below, and the
target's kind is not a fact about the target's binding at all.

**The binding is the resolution, not the authored directive, and a shipped coordinate says so.**
The three rule arms mirror `singleTableBackedVerdict`, which reads the type registry rather than the
`@table` population, and `GraphitronSchemaBuilder` registers `TypeBuilder.routineReturnVerdict` as a
`GraphitronType.TableType` for a type bound by what a `@routine` chain returns with no `@table`
written. `intent_bound_table` is `graphitron_table`-derived and carries no such row;
`intent_resolved_type_binding` coalesces it with `intent_routine_return_binding`, recounts
`candidates` over the union, ships registered `Arm.DERIVED`, and its own comment names it as the
relation for which table stands for a type against `intent_bound_table`'s narrower question of what
the author wrote `@table` for. The witness is in the sakila example rather than hypothetical:
`Actor.filmsSplit(minLength: Int!): [ActorFilm!] @splitQuery @routine(...)`, a marked non-root child
on a `@table` parent whose target `ActorFilm` deliberately carries no `@table`, because restating the
routine's name is the second spelling the return binding exists to remove. The walk mints
`Batched(Authored)` there; an arm reading `intent_bound_table` reports `INLINE`.

`intent_field_separate_fetch` holds both its joins on the `@table` population, and that hold does not
transfer. Its comment states the reason and the reason is parent-side: whether a type standing for a
routine result is a table row or a producer-handed row is the record-handed precedence question that
arm exists to state, so substituting the relation would settle it in passing. Delivery's three arms
read the binding on the *target*, where there is no such question and where the walk has already
answered by registering the return-bound type as table-backed. The parent-side anti-join in the
`RECORD_HANDED_PARENT` arm takes the resolution too, for the same mirroring reason rather than
against the sibling: `mint` reads `ChildField.sourceShape`, a routine-result parent hands its children
`SourceShape.Table`, and anti-joining the resolution is what reproduces that. The two relations
therefore disagree about one coordinate by design, which the reciprocal comment sentence this item
already owes both views is the place to say.

The `DISCRIMINATED_TARGET` arm keeps its raw `graphitron_table` join, which is a presence test rather
than a binding: what the walk reads there is that the interface carries the marker at all, and an
interface is not a routine's return type.

**No arm resolves a connection's element, and the wrapper is one residue across all of them.** An
earlier revision folded an element walk into the shared fact, which contradicted the instruction to
copy `intent_field_separate_fetch`'s arm verbatim: that arm joins the binding on `f.named_type` and
its comment lists the connection wrapper as an absent population rather than a departure, no relation
naming a connection's element type. The contradiction resolves in the sibling's favour once the
population is split, and it resolves only *partly* in the sibling's favour: the authored named type
is read the way the fourth predicate above establishes, which is the second clause the transcription
instruction does not govern. For an `@asConnection` carrier the authored expression *is* the bare list on the walk
side, and store-side the `COALESCE` over `graphitron_field_synthesis` recovers it, so the authored
named type is already the element and no element walk is wanted. Only a
*structurally declared* connection puts a wrapper between the coordinate and its element that
nothing recovers: there the walk's helpers resolve it (`singleTableBackedVerdict` and
`discriminatedInterfaceTarget` each
carry a `ConnectionType` arm reading `elementTypeName`) while the store has no relation that can,
the synthesis row a macro would have left being absent. So
the arms join the authored named type, and the structurally-declared wrapper is a single
`DeliveryResidue` entry spanning every arm whose walk-side predicate unwraps one: the three binding
arms through `singleTableBackedVerdict`, and the discriminated child through
`discriminatedInterfaceTarget`. Its removal criterion is the `node`-type projection the
connection-target section prices, which is the same absence the sibling's comment already records,
so the two relations close it together. This subsumes the fan-in section's separately stated wrapper
residue, which is the same population reached from the other side.

**`@discriminate` is a row, not an anti-join, and that is where the hardcoded `false` actually
lands.** The negative-side section above already declined the anti-join shape for `@service`, on the
grounds that it is negative space inside an arm and spreads one rule across every arm it defeats; the
discriminated target is the same case and takes the same treatment, a third arm in the exemption
view, gated to single cardinality. Read once instead of carried by every rule arm, reported with a
reason literal a reader can count, and witnessed by `Inventory.media`. The fan-in arm needs no
anti-join of its own either, and now has a positive reason not to want one: at list cardinality it
is the arm that carries this very shape.

**There is no polymorphic mask to write.** `mint`'s polymorphic arm returns in *both* branches
because a switch has to pick an exit; a `UNION` of arms does not mask, and teaching it to would be
the closed-world move again. Arms stay unmasked and the reduction owns the meet, which is the
sibling's stated discipline (`intent_field_exemption_rule`: "overlapping readings survive as rows
... one-reason-per-coordinate is the resolved view's job") and which this item already applies to
`Query.people`. Where a mask has no exemption behind it the two sides genuinely disagree, and that is
a `DeliveryResidue` entry with a stated removal criterion rather than a clause to hand-write. One
such population is predictable from reading: `@table` is declared `on OBJECT | INPUT_OBJECT |
INTERFACE` while `TypeBuilder` mints a `TableInterfaceType` only when an interface carries
`@discriminate` as well, so an interface carrying `@table` alone binds a table the rule arms will
read while `mint` sends it to the polymorphic arm and returns `Inline`. Whether that mask is a rule
or an artefact of switch order is a real question; a residue asks it, where a hand-written exclusion
would have answered it silently. Two narrowings belong in that entry, both read off `mint` rather
than assumed, and the first of them is conditional rather than flat.

At list cardinality the walk's polymorphic arm and the store's `POLYMORPHIC_FAN_IN` arm fire on the
same table-bound participants and report the same literal, *where the interface has one*. It need
not, and the tree already names the case: `TypeBuilder.buildParticipantList` admits a directiveless
implementor of a plain interface as `ParticipantRef.Unbound` with no rejection, and `FieldBuilder`'s
polymorphic child arm calls the resulting all-unbound set out by name as a shape that fetches inline
at list cardinality, minting `InterfaceField` rather than `BatchedInterfaceField`. So a `@table`-alone
interface over implementors that carry no `@table` has no table-bound participant, the walk's fan-in
arm returns `Inline` at either cardinality, and the store's `POLYMORPHIC_FAN_IN` arm does not fire
while the marker and record-handed arms still read the interface's own binding. The coordinate is a
classified `InterfaceField` and therefore inside the compared domain, so this is a second disagreeing
shape rather than a boundary case: the entry's population is the single-valued `@table`-alone
interface child at any participant set, *and* the list-valued one over participants that carry no
`@table`. Only the list half over `@table` participants agrees.

The second narrowing holds across both halves: a coordinate disagrees only where a rule arm fires at
all, which is a marker on the coordinate or a record-handed parent; with neither, both sides say
`INLINE` and there is nothing to pin. The corpus carries neither shape, every `@table` interface in
`ClassifiedCorpus` carrying `@discriminate` beside it. This is therefore the second residue owing a
fixture, per the Implementation section.

[cols="2,4,2"]
|===
| Arm | Gate | Reads

| `ROOT_COORDINATE` (exemption)
| the parent type is a root operation binding
| `graphql_root_operation`, projected onto the type's fields; the binding, not the walk's three literal names. The difference is stated at the arm rather than carried as a residue, a renamed root's coordinates reading `INLINE` on both sides per the negative-side section

| `SERVICE_CALL` (exemption)
| the coordinate carries `@service`
| `graphitron_service`

| `DISCRIMINATED_TARGET` (exemption)
| the target is an interface carrying both `@discriminate` and `@table`, which is what `TableInterfaceType` means, *and* the coordinate is single-valued. The list half is the fan-in arm's row, not an exemption; the structurally-connection-wrapped half is a residue, per the fan-in section
| `graphitron_discriminate` joined to `graphitron_table` at `graphql_type.kind = 'INTERFACE'`, projected onto the coordinates whose *authored named type* is the interface, gated on the authored cardinality being non-list. Both readings are the shared ones above, so an `@asConnection` carrier over a discriminated element is correctly not exempted here. The `no connection shape` conjunct is the stated-but-inert guard the fan-in section places

| `POLYMORPHIC_FAN_IN`
| the target is an interface or union, list-valued by the authored cardinality, with at least one table-bound participant. A discriminated interface target meets this too, which is how its list half gets its row without an arm of its own. The gate is authored cardinality and not connection-shapedness: `anyTableBoundParticipant` does not resolve a declared wrapper's element, so that wrapper is the residue the fan-in section states, while an `@asConnection` carrier is list-valued here on its synthesis row
| `graphql_type.kind`, `graphql_implements` / `graphql_union_member`, `graphql_field.is_list` with `graphitron_field_synthesis`, `graphitron_table`

| `RECORD_HANDED_PARENT`
| the parent is an object the backing closure grounds on a class and its own type binds no table, and the target binds one table. `intent_field_separate_fetch`'s arm of this name is the gate; copy it and add the arity filter
| `intent_type_backing_class` on the parent at `graphql_type.kind = 'OBJECT'`, anti-joined against `intent_resolved_type_binding` on the parent, joined to `intent_resolved_type_binding` at `candidates = 1` on the target's *authored named type*. Both joins take the resolution rather than the sibling's `intent_bound_table`, per the binding paragraph above; the target join takes the shared authored-type reading, which is the clause the sibling's arm does not carry

| `SPLIT_QUERY`
| `@splitQuery` on the coordinate, and either the target binds one table or the coordinate carries `@pivot`
| `graphitron_split_query`, `graphitron_pivot`, `intent_resolved_type_binding` at `candidates = 1` on the target's authored named type

| `TENANT_FAN_OUT`
| `@tenantFanOut` on the coordinate, the target binds one table, and the coordinate carries no `@routine`
| `graphitron_tenant_fan_out`, `graphitron_routine`, `intent_resolved_type_binding` at `candidates = 1` on the target's authored named type
|===

Two things the table shows that the prose could not. The two authored readings carry their own
literals (settled below, under the questions retired at review), so a coordinate matching both
yields two rows and the reduction's declared order, `SPLIT_QUERY` first, names the verdict; that
order is a choice this table makes rather than inherits, and it coincides with `mint`'s own arm
order, which evaluates the split reading first. And the within-side order is load-bearing on the exemption side
with a witness already in the corpus: `Query.aggregated`, in the same
`service-child-class-backed-parent` example, is a root *and* carries `@service`, so both exemption
arms match it and `mint`'s order is what makes `ROOT_COORDINATE` the reason it reports rather than
`SERVICE_CALL`. The verdict is `INLINE` either way, which is exactly why the reason column needs the
order stated, and it also settles which instrument can check it. Not the shadow: `mint` returns the
argument-less `DeliveryFact.Inline.INSTANCE` on both exemptions, so the walk carries no reason
literal for an `INLINE` coordinate and there is nothing on that side for a folded comparison to
disagree with. The exemption order is pinned by the overlap fixture instead, per the shadow bullet
below. Folding the literal into the compared value is still load-bearing, on the rule side, where
`Batched` does carry its `Trigger`.

## Which consumers can read it, and when

Delivery has two consumer classes, and the pipeline order splits them. `runPipeline` is, in order:
`GraphitronSchemaBuilder.buildBundle` (the classification walk, where `DeliveryFactRelation.compute`
runs), then `captureFactsAndDetect` (where the store is filled), then `validateAndLogErrors`, then
`EmitPlan.produce`. So:

* **Classifier-internal reads happen before capture and cannot use a view.** Which leaf record the
  walk mints (`BatchedInterfaceField` versus `InterfaceField`) is decided while the store is still
  empty. That read is leaf-zoo business and dissolves with the leaf zoo under
  `roadmap/coordinate-lowers-to-datafetcher-queryparts.md`, not here. This is a fact about today's
  stage order rather than about delivery, but as of 2026-08-14 no open item proposes changing it: the
  item that specified the reorder was repointed onto the emit plan (R682, below) when its owner found
  the reorder had no consumer, and that item now states it does not touch the pipeline order. So the
  ordering constraint is load-bearing for this item's scope, not a temporary accident to route
  around; re-check it at pickup rather than assuming either way.
* **Planning-stage reads happen after capture and are eligible today.** `ProjectionCommands` and
  `LauncherCommands` reach delivery through `GraphitronSchema.deliveryOf`, and both run inside
  `EmitPlan.produce`, downstream of the capture transaction.

Two things follow, and they are the reason this item is worth doing now rather than waiting. It is
**not blocked on R333**: the planning consumers are reachable without touching the classification
walk. And a flip, if one ever happens, is bounded to those two consumers; nothing in this item can
or should reach the walk's own mint decision.

## Scope: the view and its shadow, not the flip

**This item lands the view and its shadow agreement. It changes no production read.** That is the
whole delivery, and the scope is deliberate rather than timid.

A flip requires the shadow residues to be empty over the consumed population, and nobody knows what
those residues are until the view exists. Committing to the flip inside this item means one of two
bad outcomes: the item stalls on a residue nobody predicted, or the flip ships with a residue
papered over, which is the same negative-space move the item exists to kill. The demand stratum
settled this already and is the precedent to copy: it shipped, registered under
`FactCaptureAgreementTest`, and is still in shadow, with `intent_resolved_field_demand`'s own
comment stating "nothing gates on it in shadow".

**The integrity gain does not wait for the flip, and this is the point.** Today's
`DeliveryFactPinTest` compares two readers of the same in-memory inputs, which the relation's
javadoc concedes is "pinned for regression rather than independence". A store-derived view is a
genuinely independent second derivation: it reads captured base relations, not the walk's verdicts.
So the shadow agreement catches exactly the class this item was filed over. A batched delivery
added on the leaf side with no corresponding rule arm surfaces as a new residue row and fails the
pin, with no consumer moving and no reviewer required to notice. That enforcer is what the current
two-switch model structurally cannot have.

The deliverable is therefore not "the store also knows about delivery". It is **the delivery rule
set acquiring an enforcer that is not another switch.**

## Implementation

* The three views in the DDL, in the demand stratum's shape:
  `intent_field_delivery_rule (graph_name, type_name, field_name, rule)` as a `UNION` of one arm per
  trigger, `intent_field_delivery_exemption_rule (graph_name, type_name, field_name, reason)` with the
  three arms established above, and `intent_resolved_field_delivery (graph_name, type_name,
  field_name, verdict, rule)` as the reduction. Seven arms in total, and the table above is the
  checklist: four rule arms, one literal each, three exemption arms. The closed vocabularies are
  declared in the column comments rather than in constraints a view cannot carry, per the integrity
  note above, with full comment coverage per `FactSchemaGateTest.commentCoverageIsTotal`; the
  vocabulary's *enforcement* is the shadow test's containment and floor assertions, in the
  `DeliveryShadowTest` bullet below.
* Amend `intent_field_separate_fetch`'s view comment to name this stratum as its sibling, stating the
  two shared literals and the two populations the pair deliberately answers in opposite directions.
  The reciprocal sentence belongs in `intent_field_delivery_rule`'s and
  `intent_field_delivery_exemption_rule`'s comments. Per the sibling section above, this is the whole of
  that view's involvement: its rows are not read, its arms are not moved, and its
  class-backed-parent gap stays with whoever owns it.
* The reduction's precedence runs exemption before rule, which is the opposite of the demand
  sibling's and has to be stated as such in the view comment rather than inherited by analogy.
  `intent_resolved_field_demand` lets demand beat exemption because a `@table` type shaped like a
  connection still classifies its fields; here `mint` returns on the exemption before the rules run,
  so a coordinate carrying both readings is `INLINE`. Getting this backwards makes both witnessed
  coordinates above disagree, which the shadow test will catch, but the reason it is inverted
  belongs in the comment where the next reader meets it.
* Scope the resolved view's domain by joining `intent_type_domain`, the same anchor the demand
  reduction uses. `DeliveryFactRelation`'s domain is the flat classified index and explicitly
  excludes a nesting type's fields; the comparison has to be over one domain or it is measuring the
  boundary rather than the rule.
* Reach a type's table binding through `intent_resolved_type_binding` rather than through
  `intent_bound_table` or a raw `graphitron_table` join, per the binding paragraph above: the walk's
  predicate follows a `@routine` chain's return binding and the `@table` population alone does not
  carry it, with `Actor.filmsSplit` in the sakila example as the live disagreement. The resolution
  ships as a registered `Arm.DERIVED` relation over the same six columns, recounts `candidates` over
  the union, and makes binding ambiguity rows instead of a silent pick: two candidate tables are two
  rows, so an arm that needs a settled binding states `candidates = 1` the way the column-match
  classifier does. The same view is the `RECORD_HANDED_PARENT` arm's parent-side anti-join, unfiltered
  there because what makes a parent a table row is that it is bound at all. This governs the three
  rule arms that need a *binding*, and not the
  `DISCRIMINATED_TARGET` arm, whose `graphitron_table` join is a presence test: what the walk reads
  there is that the interface carries the marker at all, so routing that arm through the binding view
  would drop an ambiguously-spelled discriminated interface out of the exemption and hand it back to
  the rule arms it exists to defeat.
* `DeliveryResidue` in `DemandResidue`'s mould: a record naming each population the store cannot yet
  express, each with a stated removal criterion. Predicted from reading, to confirm at
  implementation: the nesting-field domain boundary above, and any arm whose predicate depends on
  classifier-internal route resolution (`resolveChildPolymorphicJoinPaths`) rather than on a
  captured fact. The structurally declared connection wrapper is the one the arms section settles,
  and it is a residue rather than a candidate: one entry across every arm whose walk-side predicate
  unwraps a *declared* connection, an `@asConnection` carrier reaching its arms through the synthesis
  row instead. Write its removal criterion as the column it is, per the connection-target section:
  `CONNECTION_MACHINERY` already walks `edges` to its element type's `node` field and stops short of
  projecting that field's named type, so publishing the projection is what closes the entry.
  The one predicate-driven residue *candidate* is the pivot-slot record parent, the
  single member of the record-handed population neither backing arm witnesses, its source being a
  graphitron-built record that no class stands for. Confirm that one before writing it: the arm it
  would disagree with needs a table-anchored target, and `ChildField.target()` gives `PivotSlotField`
  a `TargetShape.Field`, so `mint`'s record-handed arm may never fire at a pivot slot and the entry
  may be empty by construction. An empty residue is not a harmless extra here, the shadow
  discipline below asserting each one non-empty on the shapes that create it. Note what is *not* on this list any more: the
  accessor-reached class-backed parent, which was a residue for as long as this item planned to copy
  the producer-payload arm and stopped being one when the arm moved to `intent_type_backing_class`.
  The renamed root is next, and it comes off for the reason the root-exemption bullet establishes
  rather than because the population is unreachable: the walk answers `Inline` at a coordinate it
  never indexed, which is the same verdict the exemption reports, so the two sides agree at every
  coordinate of a renamed root and there is nothing for a residue to excuse. Keeping the entry
  anyway would put an exclusion in the record that no shape can exercise, which is a residue nobody
  could tell had gone inert: the negative-space defect this item exists to kill, reproduced in its
  own test plan. The fork itself is real and stays stated at the arm, where the binding keying is
  the intended rule; what the fork does not produce here is a disagreement. And the `@table`-alone interface
  child is the last, per the polymorphic-mask paragraph in the arms section: the interface binds a
  table the rule arms read while `mint` routes it to the polymorphic arm and returns `Inline`, so a
  single-valued such child under a marker or a record-handed parent disagrees. Its removal criterion
  is the walk gaining a table-backed reading of a `@table` interface that carries no
  `@discriminate`, which is the question the residue exists to ask rather than one this item
  answers. The entry covers two shapes rather than one, per the polymorphic-mask paragraph: the
  single-valued child at any participant set, and the list-valued child over implementors that carry
  no `@table`, where the walk's fan-in arm finds no table-bound participant and returns `Inline`
  while the marker and record-handed arms still read the interface's binding. Only the list half over
  `@table` participants agrees, both sides' polymorphic arms reporting `POLYMORPHIC_FAN_IN` there, so
  a flat narrowing to single cardinality would state the population narrower than it is. The
  joined-table participant is explicitly *not* a residue candidate, per the fan-in trace above.
* **Every residue this item declares needs a shape that populates it, and two of them have none
  today.** The non-empty rule in the shadow bullet below is not satisfiable by a residue whose
  population the corpus cannot reach, so each such residue owes a fixture beside the shadow test in
  the same place the `@tenantFanOut` and connection-child fixtures live. It is the same rule that
  takes the renamed root off the roster above rather than giving it a third fixture here: a shape
  that populates a residue is one where the two sides disagree, and a renamed root's coordinates
  agree. The structurally declared connection wrapper is the first, and
  its fixture is the connection-child coordinate the corpus bullet below already names: authored
  against an SDL connection type rather than through `@asConnection`, it witnesses the residue rather
  than an arm, which is what that bullet means by the fixture being a deliverable. An earlier
  revision said this population was carried by its residue's non-empty assertion *instead of* a
  fixture, which cannot be true of the same assertion. The `@table`-alone interface child is the
  second, and it is the cheaper of the two: an interface carrying `@table` and no `@discriminate`
  over `@table` participants, returned single-valued from a `@table` parent under `@splitQuery`.
  Nothing in `ClassifiedCorpus` is one, every `@table` interface there carrying `@discriminate`
  beside it, and nothing beside a shadow test is either; the shape is authorable and reaches
  `GraphitronType.InterfaceType` with no rejection, `TypeBuilder`'s interface arm falling through to
  the plain build when only one of the two markers is present. Its second shape costs a second
  interface beside that one, per the polymorphic-mask paragraph: a `@table` interface *all* of whose
  implementors are directiveless, returned list-valued and marked, which is where the participant
  conjunct rather than the cardinality is what the two sides disagree over. One interface cannot
  carry both shapes, a single `@table` participant beside the directiveless ones being enough to make
  the walk's fan-in arm fire. Both ride the same beside-the-test fixture as the connection child
  rather than needing a file of their own.
* `DeliveryShadowTest` in `DemandShadowTest`'s mould, registered in `FactCaptureAgreementTest` under
  `Arm.DERIVED` for all three views. Per that test's stated residue discipline: equality outside the
  named residues, each disagreement direction pinned against a store-derived population rather than
  a Java-side coordinate list, and each residue asserted non-empty on the shapes that create it so
  no pin can go vacuous. Three specifics that mould already settles, worth copying rather than
  re-deciding. Compare the verdict and the winning literal as one value, the way the sibling folds a
  coordinate to `DEMANDED:<rule>`, or a mis-ordered precedence among the rule arms passes wherever
  two rules agree on `BATCHED`. That fold reaches the rule side only, `DeliveryFact.Inline` carrying
  no trigger to compare an exemption reason against; the exemption order is the overlap pin's job,
  below, and stating which instrument owns which half is the point. One fold is one-directional: the walk's
  `DeliveryFact.Trigger` mints `Authored` for both authored literals, so the comparison folds the
  store's `SPLIT_QUERY` and `TENANT_FAN_OUT` onto `Authored` rather than asking the walk for a
  distinction it never made; the precedence between the two authored literals is pinned by the
  overlap fixture below, not by the shadow. Assert both vocabularies are subsets of the declared
  sets, since that assertion is what actually enforces the closed sets the comments declare and the
  DDL cannot. Assert each vocabulary is *reached* as well as contained, one non-empty check per rule
  and per reason literal: containment stops a stray word, and only the floor stops an arm that
  matches nothing from shipping as a vocabulary entry no coordinate can produce. That is the same
  vacuity this item's corpus bullet is about, and the per-trigger floors on `DeliveryFactPinTest` are
  the local precedent for spelling it as an assertion rather than trusting the corpus. And pin the
  exemption overlap directly. The sibling pins it beside its DDL rather than in its shadow test, in
  `DemandRuleTest` over seeded stores in `graphitron-model`:
  `everyFieldExemptionArmAnswersWithItsOwnReason` holds both overlapping readings surviving as rows
  in the rule view, and `demandBeatsExemptionAndTheFirstDeclaredArmWinsWithinASide` holds the
  reduction picking the declared winner within a side. Copy the two assertions, not their home: this
  item's pin reads the captured corpus store the shadow test already opens, `Query.aggregated` being
  a shipped coordinate both exemption arms match, so no seeded store is needed to reach the overlap.
* Corpus population for every arm the view declares. A shadow test over a corpus that does not
  exercise an arm is vacuous in exactly the way that item's review found `DeliveryFactPinTest` to be,
  so each declared rule needs a coordinate that reaches it. Three populations are missing today, all
  counted against `ClassifiedCorpus` rather than assumed:
  * The discriminated interface child at both cardinalities, which
    the batched-discriminated-interface-child item has since landed, so this is now a check
    rather than an authoring job. All three coordinates that item specified exist: the unmarked list
    child is `Language.mediaList` in the `table-interface` corpus example, and the marked pair is
    `Film.splitContents` and `Film.splitContent` in `DeliveryFactPinTest`'s `MARKER_FIXTURE`, homed
    there on purpose because a marked coordinate raises that item's new redundancy lint and a
    `@classified` verdict row would have to reconcile it. `Inventory.media` remains the single
    unmarked witness. **What does not come free is the store side.** `MARKER_FIXTURE` is a private
    constant of a pipeline-tier test with no captured store behind it, so `DeliveryShadowTest` cannot
    read it; the marked pair needs its own fixture there, in the mould of `DemandShadowTest`'s
    per-shape fixtures beside its corpus sweep. The unmarked list child needs nothing, the corpus
    sweep reaching it already.
  * **The `@tenantFanOut` arm has no witness anywhere in the corpus.** `@tenantFanOut` occurs zero
    times in `ClassifiedCorpus`, against three `@splitQuery` coordinates, three `@routine` and two
    `@pivot`; count the SDL blocks rather than grepping the file, whose javadoc prose mentions each
    marker several times over and inflates every figure but the zero. And
    `DeliveryFactPinTest`'s own `MARKER_FIXTURE` covers only the split-query half, by its comment
    "an authored split child riding a table parent". With `TENANT_FAN_OUT` now its own rule literal
    (settled below), an unwitnessed arm would ship a vocabulary entry no coordinate can reach:
    vacuous on landing, in exactly the class this item exists to kill. So the fixture is a
    deliverable, not a choice, and its home is settled too: a beside-the-corpus fixture the way
    `MARKER_FIXTURE` already does it, rather than a corpus example, because the marker's existing
    fixtures all live beside their tests (`TenantFanOutClassificationTest`,
    `TenantFanOutFetcherPipelineTest`, `TriggerFactPopulationPinTest`) and the corpus owes an
    example to a classification shape, not to every marker spelling. The fixture carries two
    coordinates: a tenant fan-out child that witnesses the arm, and a child carrying both
    `@splitQuery` and `@tenantFanOut` that pins the authored overlap, both rows surviving in the
    rule view and the reduction picking `SPLIT_QUERY`, the same discipline the `Query.aggregated`
    exemption-overlap pin applies one side over.
  * **No structurally declared connection reaches any arm, and no connection at all reaches one as
    a child through its wrapper.** The corpus carries four `@asConnection` carriers and two
    structural connections. Three carriers sit on `Query` (`catalog`'s `films`,
    `paginated-joined-table-interface`'s `parties`, `faceted-connection`'s `films`) and so do both
    structural ones (`connection` and `arrival-connection-ancestor`, each
    `Query.films: FilmsConnection`); a root returns `Inline` on `mint`'s first line, before
    `tableAnchoredChild` is computed. The fourth carrier is a child, `Language.mediaConnection` in
    the `table-interface` example, and it misses the arm for the other reason the corrected
    predicate above gives: an `@asConnection` carrier's authored expression is the bare list, so
    `mint` reads `MediaItem` and the discriminated arm answers before `tableAnchoredChild` is
    reached. Between the two reasons `singleTableBackedVerdict`'s `ConnectionType` arm is unreached
    over the whole corpus, and its javadoc states the shape it exists for: "a connection verdict
    anchors through its element, so authored connection returns stay batched-capable". That is a
    child whose *declared* type is connection-shaped, under `@splitQuery`, and nothing in the corpus
    is one. Note what that same coordinate *does* witness, because it is the one fixture this item
    does not owe: `Language.mediaConnection` is a shipped corpus child whose `graphql_field` row
    holds the minted wrapper, so the authored-type `COALESCE` and the synthesis-row cardinality test
    are both exercised by the corpus sweep on landing. Get either reading wrong and
    `DeliveryShadowTest` fails at that coordinate rather than shipping green, which is the opposite
    of the situation the rest of this bullet describes and is why the fourth predicate is settled in
    the body rather than left to the implementer to discover.
    The predicate this leaves unwitnessed is the structural one corrected above, so the
    missing coordinate and the mis-picked relation are the same gap seen twice, which is why an arm
    keyed on `graphitron_connection` would have shipped green. **So this fixture is a deliverable
    too**, on the `@tenantFanOut` bullet's terms and in the same home, beside the test rather than in
    the corpus: a child declared against an SDL connection type over a plain `@table` element and
    marked `@splitQuery`, which is the coordinate that puts the structural predicate and the
    `SPLIT_QUERY` arm's connection reading under the shadow at once. What it witnesses is the
    wrapper *residue* rather than an arm, per the residue bullet above: the store has no relation
    naming a connection's element, so the coordinate is where the two sides are pinned to disagree
    and the non-empty assertion needs a shape to stand on. The same fixture carries the sibling
    shape, the same child over a `@table @discriminate` element, which reaches the residue through
    `discriminatedInterfaceTarget` instead of `singleTableBackedVerdict`; one fixture, two
    coordinates, one residue.
  * **The routine-return-bound target reaches no arm either, and it is the population the binding
    correction turns on.** `ClassifiedCorpus`'s routine read example is `Query.tilganger` in
    `routine-table-valued-read`, a root whose target `Tilgang` restates `@table` for the same table
    the routine names; the two mutation routine examples are roots as well. So every corpus
    coordinate that could exercise the binding is either exempted before the rule arms run or carries
    an authored `@table` that makes `intent_bound_table` and `intent_resolved_type_binding`
    indistinguishable, and an arm reading the narrower relation ships green. The shipped
    disagreement lives one module over, at `Actor.filmsSplit` in the sakila example, which no shadow
    test sweeps. So the third fixture is a marked non-root child on a `@table` parent whose target is
    bound only by a `@routine` chain's return, in the same beside-the-test home as the other two,
    and it is what makes the corrected relation observable rather than merely argued.

## The exit criterion, and the successor

The successor slice flips `ProjectionCommands` and `LauncherCommands` onto
`intent_resolved_field_delivery`. It is filed: `roadmap/planners-read-facts-emitters-read-commands.md` (R682, Spec)
declares this item as its dependency, and its measured read surface counts `deliveryOf()` as one of
the accessors it converts, at two call sites, which are exactly the two consumers named above (a
whole-tree check confirms there is no third production reader). So the successor is not a slice to write later but a plan
already specified, and this item's job is to leave that plan a view it can read. Its precondition is
checkable rather than a judgement call: **the shadow residues are empty over the coordinates those
two consumers actually read.** Residues elsewhere (a nesting boundary the planning consumers never
ask about) do not block it.

What retires then, and only then: `DeliveryFactRelation`'s hand-maintained production, including
`singleTableBackedVerdict` and its negative space. `DeliveryFact.leafDerivedOf` outlives it as the
walk-less fallback, and its own javadoc claims it outlives the leaf zoo too, on the grounds that
delivery is one of the axes the leaf reconstruction key keeps. Take that claim as the current
position rather than as settled: if the leaf zoo dissolves there is no leaf encoding left to read,
so the crosswalk's survival is R333's call to make, not this item's, and nothing here depends on
which way it goes.
`DeliveryFactPinTest` changes meaning rather than disappearing, and that is the point: today it
pins two duplicate readers, and afterwards it either becomes a store-versus-consumer agreement or
goes away with the crosswalk it compares.

**One test of whether this item succeeded:** the discriminated interface child must not appear in
`DeliveryResidue` at either authored cardinality, single or list, the `@asConnection` carrier
riding the list half on its synthesis row rather than on a bare list `graphql_field` never held.
The one carve-out is the structurally declared
connection wrapper, which the fan-in section sends to a residue for a reason that is about the
walk's own three helpers rather than about this shape, and which no corpus coordinate reaches.
The reason is not that the hardcoded `false` is wrong; per
the Problem statement it survived its own sibling item intact, and has since become unreachable
rather than incorrect. The reason is that this shape is the one whose delivery nobody could settle
without reading arm order, so a view that cannot state it has left the question exactly where it was
and reproduced the defect in a new place. Two arms between them pass this test, and the split is the
point: the `DISCRIMINATED_TARGET` exemption states the single half and the `POLYMORPHIC_FAN_IN` rule
arm carries the list half, each as a row rather than as a predicate folded into the others. A residue
is silent and an anti-join is unreportable, while a row carries a literal and a count, and here the
two rows say out loud that cardinality is the fork, which is exactly the fact a reviewer previously
had to derive from arm order.

## Out of scope

* **Flipping any consumer.** The successor above, gated on its stated criterion.
* **The classifier's own mint decision.** Ordering-blocked, per the eligibility section, and owned
  by R333.
* **Retiring `DeliveryFact.leafDerivedOf` or `DeliveryFactPinTest`.** Both are the comparison side
  while the window is open.
* **Collapsing R557's sweep into the anti-join.** Cross-referenced below, decided in that item.
* **`intent_field_separate_fetch`'s own arms.** The sibling section leaves that relation's rows
  untouched and takes only the reciprocal comment sentence. Its class-backed-parent arm has since
  been written by its owner, so this item reads it as the model for its own record-handed arm and
  changes nothing there.

## Relationship to items already open

* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333, Ready) is the umbrella that owns
  the leaf zoo's normalization. This item is one axis of it taken early, chosen because delivery is
  the axis where the two-site duplication has already produced a live defect. It should ride R333's
  vocabulary rather than invent its own. On whether that makes it a slice or a dependent, the
  question the earlier draft left to R333's author now has a precedent to follow rather than needing
  a ruling: R682 states plainly that R333 "owns the drain" and that it is a slice of it, while
  carrying its own `depends-on` edge. Read this item the same way, a slice of R333 that is
  independently schedulable, unless R333's author says otherwise.
* `roadmap/planners-read-facts-emitters-read-commands.md` (R682, Spec) is the successor named above and the one item
  that declares a dependency on this one. It converts the whole emit plan onto the store, and the
  two delivery consumers this item leaves in place are inside its scope. Two consequences for
  whoever implements this item. The view's column names and rule vocabulary become R682's read
  surface, so pick them for a consumer rather than only for the shadow test. And the exit criterion
  above is checkable against a real population now, because R682's measured read surface says
  exactly which coordinates `deliveryOf()` is asked about.
* The batched-discriminated-interface-child item (Done; see `roadmap/changelog.md`) **has landed**, and it went
  first. There was deliberately no dependency in either direction, an N+1 defect not being something
  that should block on a structural item, and either order would have worked; the consequence of the
  order that happened is that this item models a delivery rule set that item has already changed, and the
  sections above are written against the tree that item left rather than against a prediction. Three
  concrete inheritances, all already folded in above and collected here so the next reader can check
  them against the item rather than rediscover them. The `DISCRIMINATED_TARGET` exemption is gated to
  single cardinality, the list half being the fan-in arm's row. The precedence of that exemption over
  the marker arms is now a transcription rather than a choice, `mint`'s discriminated arm returning
  ahead of the marker block by design. And the three coordinates that item's delivery-agreement
  bullet asked for all exist, so this item's corpus work shrinks to a store-side fixture for the
  marked pair. What that item's landing does *not* change is this item's motivation: its own spec
  says so, deferring the negative-space defect here rather than restructuring the site, and the
  enumeration it left behind is now unreachable rather than merely fragile.
* The language-server fact-store item (R638) has since reached Done; its file is retired and its
  record lives in `roadmap/changelog.md`. It shipped the two relations this item leans on hardest:
  `intent_field_separate_fetch`, the sibling relation this item's own section places, and
  `intent_type_backing_class`, the backing closure the `RECORD_HANDED_PARENT` arm now reads. Its
  landing dissolves the moving-target caveat an earlier revision carried (that item's
  `RECORD_HANDED_PARENT` arm landed eighteen minutes after a revision of this spec, which is why the
  sibling section instructs a copy where it used to instruct a derivation): the sibling view no
  longer has an in-flight owner, so the reciprocal comment sentence edits a view nothing else is
  moving. Re-read that view at pickup all the same; the sibling section names the five arms as of
  this revision, and the relationship this item declares (two relations, shared literals, two
  deliberately opposite populations) is what survives another arm landing.
* `roadmap/split-query-marker-sweep.md` (R557, Backlog) wants a completeness enforcer for
  `@splitQuery`: every marker consumed, inert-by-construction, or rejected. Its spec proposes a
  total switch over the classified leaf. If delivery becomes a view, that sweep is an anti-join
  instead (`graphitron_split_query` rows with no `intent_field_delivery_rule` row and no stated
  inert reason), which is both simpler and the same instrument the demand stratum's future gate
  already plans to use. The Spec-time question that draft left open, whether R557 collapses into
  this one, resolves to no. R557's deliverable is a validate-time rejection with a stated reason per
  inert position, and this item changes no production read and raises no diagnostic, so folding it
  in would drag a diagnostics surface into a shadow-only item. There is also a shape mismatch worth
  recording, and the negative-side section above sharpens rather than removes it. This item does
  ship an exemption relation carrying reasons, but for exactly three populations that override a
  matching batching rule, which is a different question from the one R557 asks: why a marker that
  matched nothing is nonetheless not an error. So R557 gains its *population* from the anti-join,
  gains the exemption view's shape as a model for stating its own inert reasons positively, and
  still has to author that vocabulary itself. The one real coupling is ordering: R557 should not be
  picked up before this lands, or it writes the total switch this item exists to retire.

## Open for the implementer

Nothing is left open. The earlier draft carried two questions and a later revision a third; all
three are settled below, in the order the questions were retired. A fourth was raised at the Spec
review gate and is settled in the body rather than here, being a reading of `graphql_field` the arms
depend on rather than a question about them: the captured-inputs section's fourth predicate is where
it lands, and the arms table's two shared readings are what it produced.

**Whether `graphitron_service`'s claim is a rule arm or a domain exclusion: it is neither.** It is an
exemption arm with declared precedence, per the negative-side section above, which also establishes
that the root coordinate is a second one. The complement-only reading the question presupposed is
what turned out to be false, and both alternatives it offered inherited that reading. The same
finding resolves the view-count question: three relations, the sibling's shape, for a structural
reason rather than by copying a file layout.

**The resolved view needs no stored materialization.**
`intent_type_domain` is a table rather than a view because H2 cannot state a terminating closure over
a cyclic type graph. Every arm in `mint` reads its own coordinate's markers, that coordinate's target
type, and that type's participants or bound table, so nothing recurses and no arm needs a closure.
The joined-table anti-join was the one candidate exception, and the fan-in trace above removes it:
no arm reaches the `sql_` family. The connection element walk was the other, and the arms section
removes it too, by settling that no arm resolves an element and the wrapper is a residue. So every
arm the table declares is a single hop and the answer holds by a wider margin than the question
assumed. It would have held either way: what a view cannot state is a closure of unbounded depth
rather than a join of more than one, and that walk is fixed-depth by the shape's definition, so the
day the store gains a connection-element relation and the residue closes, a plain view still holds.

**The `Authored` trigger becomes two rule literals, `SPLIT_QUERY` and `TENANT_FAN_OUT`.** The
ground is that the vocabulary captures facts, not decisions. The two readings witness two different
captured populations, `graphitron_split_query` and `graphitron_tenant_fan_out`, so the literal
names the fact that produced the row; `Authored` is the walk's decision label, a "the author asked
for it" grouping, and folding two facts under it already cost the vocabulary its precedence: under
one literal a coordinate carrying both markers yields one row by `UNION` dedup and the order of the
two readings is unobservable, the same trade the negative-space switch was making. Two literals
make the precedence a stated fact, `SPLIT_QUERY` first, coinciding with `mint`'s own arm order. The
costs the question used to weigh resolve with it. The unwitnessed `@tenantFanOut` arm is answered
by the fixture deliverable in the Implementation section, since the arm is equally unwitnessed
under a shared literal and merely less visibly so. And the asymmetry that made this cheap now and
expensive later, the rule vocabulary becoming R682's read surface once that item lands, is why it
is settled at spec rather than left to the implementer. The walk keeps its coarser `Trigger`
vocabulary while it stands as the comparison side; the shadow folds the two literals onto
`Authored`, per the shadow bullet above.

Both literals are already spelled that way in `intent_field_separate_fetch`, which is corroboration
rather than collision: two relations reading the same two captured facts arrived at the same two
words independently, which is what the facts-not-decisions ground predicts. The sibling section
above settles that the spelling is deliberately shared and the closed sets stay separate.

## Coverage

The shipped derived views each carry a hand-written anchor test the view cannot produce by
construction (`AuthoredClaimConflictsTest`, `DemandShadowTest`, `InputOccurrenceShadowTest` and
`SeparateFetchTest` in `rewrite/derive`, with `ColumnMatchClaimTest` beside the DDL in
`graphitron-model` instead), plus `Arm.DERIVED` registration in
`FactCaptureAgreementTest`, whose driver fails both on an unregistered relation and on a
registration the DDL no longer declares. This item follows that pattern rather than inventing one.
The two additions specific to it are in the Implementation section above: the domain join that
keeps the comparison off the nesting boundary, and the per-arm corpus population without which the
agreement is vacuous.

## Provenance

Surfaced in the Spec review of the batched-discriminated-interface-child item, where the
missing update to `singleTableBackedVerdict` was the review's blocking finding. The item exists
because the finding is a symptom: a delivery rule change had to be applied by hand at a second site
whose encoding is a negative-space enumeration, and nothing but a reviewer's reading stood between
that and a silent disagreement. Sliced out of R333 at its author's direction, as the delivery axis
of the leaf zoo's normalization taken early, on the grounds that it is the axis where the
two-site duplication has already produced a live defect.
