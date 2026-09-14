---
id: R943
title: "A dev round on a consumer schema answers in seconds: three registrations are over an hour of the refresh pass and eleven others are six seconds"
status: Spec
bucket: dx
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-11
last-updated: 2026-09-14
---

# A dev round on a consumer schema answers in seconds: three registrations are over an hour of the refresh pass and eleven others are six seconds

## Goal

A `graphitron:dev` round on a real consumer schema answers in seconds. On the `sis` consumer it does
not: the refresh pass is dominated by three of its twenty-one registrations, which together run for
over an hour, while eleven of the others cost six seconds between them. The cost is concentrated
rather than spread, so this item is the pricing and the fix of those three, not a broad campaign
against the register.

Two terms, glossed once. The *fact store* is the H2 database each generator pass captures the schema,
the jOOQ catalog and the classpath into. A *registration* is a row of `meta_materialize` that keeps a
derived rule in a view under a `_live` name and moves the canonical name onto a table the capture
refills once per pass; the *refresh pass* is that refill, and a dev round pays it at boot and again
after every save.

## What the pass costs

Measured 2026-09-14 on the `sis` consumer's `sis-graphql-spec` module, by `mvn graphitron:dev -X`,
which prints one line per registration before its statements and one after. These are `done in` lines
from a real capture rather than a standalone ranking, which is the distinction R939 paid for twice
and which this item keeps: a timing taken outside the transaction that has just written the relations
a rule reads is not what a round pays.

On the in-transaction cadence, `Materializations.refresh`, against a warm store:

| registration | time | rows |
|---|---|---|
| 1 to 11 (eleven registrations) | 2.9 s at worst, about 6 s in total | 85 to 11183 |
| 12 `intent_node_id_instruction_live` | 386.1 s | 683 |
| 13 `intent_input_field_filter_role_live` | 2076.0 s | 1765 |
| 14 `intent_node_id_decode_hop_live` | over 30 min, stopped before it returned | not reached |
| 15 to 21 | not reached | |

Two readings the table carries. The output is tiny where the time is large: 0.57 s per row produced
at registration 12 and 1.18 s per row at registration 13, which is re-evaluation per driving row
rather than volume. And the dearest is not the one this item previously named as its first target:
`intent_input_field_filter_role` is on the list, but at roughly ninety times the figure the item was
carrying, and two node-id relations sit beside it that the item did not name at all.

## The cost is the shape, not the planner's statistics

`FactCapture` picks its refresh cadence on whether the store already held a graph: a cold store gets
`Materializations.refreshAnalysing`, which commits and analyses per registration, and every later
capture gets `Materializations.refresh`, which runs the pass inside the capture's transaction and
analyses nothing. The analysing path exists because a pass planned with no selectivity on what it
reads is, in that method's own words, hours rather than a factor, so the cadence is the first thing
that looks like an explanation for these figures.

It is not the explanation, and the control says so on the same population. A cold boot, taking the
analysing path, produced identical row counts at every registration and ran registration 12 in
**470.1 s against the in-transaction path's 386.1 s**, which is 22% slower rather than faster;
registration 13 was still running past 21 minutes when the observation stopped. So neither the
cadence nor the statistics it supplies is the multiplier, and a fix aimed at either buys nothing.
The remaining candidate is the shape of the rules themselves, which is where the plan below goes.

## Where the cost multiplies through

Registrations 12 and 13, the two priced in full, reach one chain that no other registration's view
tree reaches at any depth:

```
intent_node_type -> intent_inferred_node_type -> intent_node_metadata_defect
```

`intent_node_metadata_defect` is the bottom of it, and carries the shape a stack sample of the stuck
statement showed: ten union arms and two correlated `EXISTS`, expanded once per path through the
views above it. Registration 14 does not read that chain; it shares
`intent_argument_reference_step_hop` with registration 12 instead.

The static ranking prices that reach as breadth, and the reading is worth taking before any timing
because it is free. `mvn -pl roadmap-tool exec:java -Dexec.args='report-inline-multiplicity .'`
ranks `intent_node_id_instruction_live` first of the 127 views at 230 relation instantiations per
read, and reports its direct children as 114 `intent_node_type`, 80
`intent_argument_reference_step_target` and 4 `intent_field_reference_step_target`. The 114 is one
relation named six times at 19 instantiations each, and the 19 is the chain: `intent_node_type`
names `intent_inferred_node_type` exactly once, which names `intent_node_metadata_defect` exactly
once. The chain is linear, which is the fact that decides where to register.

Turning a candidate into a table, which is what a registration does to every reader above it, moves
the three priced registrations' breadth like this. The last row is the plan's first rung and is not
a registration at all; the rest are simulations of the metric above, not measurements:

| the candidate becomes a table | 12 instruction | 13 filter role | 14 decode hop |
|---|---|---|---|
| nothing, as shipped | 230 | 61 | 91 |
| `intent_node_type` | 122 | 43 | 91 |
| `intent_inferred_node_type` | 134 | 45 | 91 |
| `intent_node_metadata_defect` | 158 | 49 | 91 |
| `intent_argument_reference_step_target` | 151 | 61 | 12 |
| `intent_node_type` and the step target together | 43 | 43 | 12 |
| `intent_node_type` re-sourced onto two captured tables | 134 | 45 | 91 |

The relation to reach for is the one the page's rule actually names, which is the one every expensive
reader has in common and low enough that materializing it stops the re-evaluation for all of them.
Depth is the tie-break among candidates that satisfy that, not the criterion: the page prefers the
deepest candidate whose materialization removes re-evaluation for *more readers than the one you
started from*, and its counter-case is a candidate registered below the relation still being
expanded. On a linear chain the top is the common relation, because every level of the chain is paid
once per naming of the top, and `intent_node_type` is named thirteen times across six view bodies
where the bottom of the chain is named twice. Two further clauses of the same page land on the same
relation. Its anti-join paragraph says materialization applies to the relation being expanded rather
than to the expensive relation inside it, and `intent_node_type` is exactly the excluded relation in
registration 12's second arm, a correlated `NOT EXISTS` with no drivable side. And its
inline-multiplicity paragraph says a table counts one and truncates the tree at that name for every
rule above it, which is the arithmetic the table above computes.

Breadth is not cost, which the store-performance procedure says with its own retracted readings, so
the table above ranks suspects and the timings in the plan below price them. Two further readings
stand behind the plan's candidate order. R876's finding across every relation it measured is that
the lever was a stored key and an index rather than a registration, since no registration can index
an expression, and this item expects the same answer before it reaches for a twenty-second row of
`meta_materialize`. And the static metric counts relation names, so it cannot see a common table
expression referenced several times: registration 13 names only two views, but its `resolved_name`
expression, which is `intent_input_field_column_match`, is referenced three times and each reference
is correlated on a driving row, so one read of that rule expands three times per row rather than
once per naming. At 1765 rows out that is upwards of five thousand expansions, which puts one
expansion at four tenths of a second or less against the measured 2076 s. That arithmetic closes,
which is what makes it a hypothesis worth a control rather than a story.

## What is now settled

The reading this item owed R876 is taken. R939's step 1 named a slice that never ran,
`intent_argument_scope_table` alone and its row count against `intent_field_scope_table`'s, the
question being whether crossing the field scope with the field's arguments widens the seed on a
consumer population. It does not: on `sis` the argument relation holds 968 rows against the field
relation's 2606, and refreshing it costs 5 ms. Whatever else that wrong-grain case is, it is not a
cost on this consumer, and this item does not carry it further.

## Price the chain first

The baseline measurement comes first, and it is not the thing that chooses the first two levers:
those are settled by the doctrine and the DDL below and do not wait on a timing. What the baseline
is for is everything after them. It says whether they were enough, it is the before half of the
figures the delivery reports, and it is the only thing that can refute the two readings the sections
above take on faith, which is why the plan states it rather than leaving it to instinct. The store
is already on disk: the dev round
that produced the table above persisted one under the per-user cache home
`DevMojo.resolveStoreDirectory` computes, `mvn clean` does not remove it, and it carries
the consumer's own population including the classpath census a hand-built probe omits. Stop the
build, copy the file, work on the copy, and drive it from a single-file JDBC program at the H2
version the root pom pins.

What the run asks for, in one capture rather than one per relation: `SET OPTIMIZE_REUSE_RESULTS
FALSE`, then `QUERY_STATISTICS`, then sweeps over the whole relation list rather than adjacent
repeats per relation, so the first-execution drift spreads across every relation instead of being
charged to whichever one is listed first. Sweep count is a judgment against the clock here rather
than a constant: a relation that answers in a second takes five sweeps, and one that takes minutes
is ranked once cheaply and repeated only if it stays a suspect. A row with an execution count of one
ranks and does nothing else.

The relations to name in that sweep, which is the cheapest control in the procedure and the one most
likely to refute the reading above:

- Registration 12's tree: `intent_node_type`, `intent_inferred_node_type`,
  `intent_node_metadata_defect`, `intent_argument_reference_step_target`,
  `intent_argument_reference_step_hop`, `intent_field_reference_step_target`.
- Registration 13's tree: `intent_input_field_column_match`, `intent_input_field_column_scope`, and
  `intent_node_type` again.
- Registration 14's tree: `intent_argument_reference_step_target`, `intent_node_id_decode_endpoint`,
  `intent_input_field_reference_step_target`.
- The three source views themselves, so a reader's own cost is priced against its children's.

Expect the outcome where every child answers in milliseconds and the reader takes minutes, which is
the shape R939 met on the same family and which names no line of SQL. Then bisect the body rather
than reaching for a lever: the `WITH` blocks of each source view timed one at a time, then each
top-level `UNION` arm wrapped in a count with the relation's own column list as the alias list, then
one join dropped at a time where an arm is a flat join of derived tables. Registration 12's
bisection axis is its three common table expressions, `instructed` being referenced eight times and
`table_node`, which is where its naming of `intent_node_type` sits, twice; registration 13's is its
ranked arms and the three references to `resolved_name`.

The refresh is its own instrument and no new harness is needed for the before-and-after.
`RefreshProgress.lines` already renders a per-registration tier that reports the delete and the
insert separately, in nanoseconds, with both row counts, and the insert is the view evaluation. That
is where the table above came from and it is where the delivery's figures come from too.

## The three levers, and why only one of them is a registration

The fact-model page orders the levers, and the order is the whole reason this plan does not open
with a `meta_materialize` row: a captured fact first, then a stored key and an index, then a
rewrite, and a registration only where a rule has no owner to schedule its refresh. Read against
that order, two of the three registrations turn out to be asking for something other than a
registration, and the plan commits to each rather than leaving the arm open.

**Lever 1: the nodehood chain already exists as captured facts, so re-source the view onto them.**
`Nodes.derive` computes the inferred arm's exact conjunction as a stage of the `graphitron`
gatherer, table bound, implementing `Node`, a `sql_node_metadata` row, no defect rows for it, and
lands it in `graphitron_node`, keyed `(graph_name, type_name)`, which is the key all six readers of
nodehood join on. So `graphitron_node_entry UNION graphitron_node` is `intent_node_type`'s own
population: the captured table's declared arm is the entry relation intersected with the table
binding, so the union absorbs it and the inferred arm is what remains. The two rules spell
"implements `Node`" through different relations, `intent_inferred_node_type` through
`graphql_implements_interface` and `Nodes.derive` through `graphql_poly_member`, and they agree
because that view's `INTERFACE` arm is `graphql_implements_interface` verbatim. Re-sourced, the
chain's nineteen instantiations become three, no refresh is added anywhere, no register row is
written, and the index the readers join on is the target's primary key, which already exists. That
is the page's top rung and it is already paid for; registering the view buys the same truncation and
adds a twenty-second refresh for it.

Three things the re-sourcing owes, none of them optional. The population equality is a claim and
gets the register's own two-line proof, `EXCEPT` in both directions against the view as it stands
today, on a populated store rather than a seeded one. The two interface spellings need one of them
chosen and the other left alone deliberately, since `intent_poly_member`'s comment says internal
readers should stand on the implements relation. And the re-sourced view reads one family, which by
the page's ownership rule files it under `graphitron_` rather than `intent_`: that is a rename six
view bodies spell, so the item either makes the filing move and says so or states why it is
deferring a rename it has just made correct. Deferring silently is the one arm not available.

**Lever 2: the defect relation has an owner, so it does not need the register at all.**
`intent_node_metadata_defect` reads `sql_node_metadata`, `sql_node_key_column` and `sql_column` and
nothing else, which is one family, and its own comment says so. The page's ownership rule is
decidable rather than editorial: a view reading one family is a view of that family, owned by that
family's gatherer, and a relation whose refresh is known to somebody may be materialized, indexed or
left a view at its owner's discretion, needing no row in `meta_materialize`, which exists for rules
with no owner to schedule them. The cadence is the payoff and it is the one a dev round actually
pays: the catalog moves when a consumer regenerates jOOQ, where the SDL moves on every keystroke, so
a catalog-owned table is refreshed almost never and a register row would be refreshed on every save.
Its four remaining correlated readers, `intent_resolved_node_key_column`'s `JOOQ_METADATA` tier,
`intent_resolved_node_type_id`, and the Java readers `Nodes.derive` and `NodeKeyColumns.published`,
stop expanding ten union arms per driving row without any of them being edited.

The stored fold rides with this lever, because it is the same relation and the second rung of the
same order. The `KEY_COLUMN_UNRESOLVED` arm probes `sql_column` with `UPPER(c.jooq_name) =
UPPER(k.column_name) OR UPPER(c.column_name) = UPPER(k.column_name)`, folding the probed side per
candidate row, where `sql_column` already carries `column_name_upper` and `jooq_name_upper` as
generated columns. This schema names a stored folded column in eighty-two places, twenty-one of them
on a column name or a jOOQ name, and `intent_input_field_column_match` two screens over compares
against both stored columns in exactly this disjunction; this arm is the outlier. Respelling it is
the join-key rule read as a lever rather than a rewrite in the page's sense, it adds no refresh, and
it makes the comparison reachable by an index where the folded expression is not.

**Lever 3: registration 13 is the page's named rewrite exception, not a registration.**
`intent_input_field_column_match` collapses its matches with `ROW_NUMBER() OVER (PARTITION BY ...)`,
and a window sees its whole partition whatever the outer correlation says, so the three correlated
references to `resolved_name` each pay the entire view's evaluation once per driving row. That is the
one shape the page says rewriting does fix, and it carries the measurement: the sibling relation
`intent_column_match_claim`, with the identical `ROW_NUMBER` collapse, cost twenty-four seconds read
as correlated subqueries and came under two seconds when the statement drove from the view with the
witnesses joined in as arity-preserving left joins. Here all three references are positive existence
rather than anti-joins, and the expression projects only the key columns of a view that is already
one row per that key, so the left-join form is arity safe by construction and the rewrite is the
lever rather than a guess. It also owes that view the cost warning its comment is missing, which the
page says each such view owes at its own name because the cost is invisible at the call site.

**Lever 4: `intent_argument_reference_step_target`, which is the one genuine register question.**
The other 80 of registration 12's 230 and the whole of registration 14's 91, named by three views
and covered by no registration today. Its own 78 is two namings of the recursive
`intent_argument_reference_step_hop`, so the hop is a rung below it, priced only if registering the
target leaves the recursion re-reading a view once per accumulated row, which is the page's stated
counter-case. A registration here owes what every registration owes: an index on the target over the
columns its named readers join on, in the `ix_node_id_instruction_coordinate` shape the DDL already
uses, the `reason` column's arithmetic, and its `DerivedReadCostTest` pair.

The four are grouped by what they fix rather than ranked against each other, because they are not
alternatives: lever 1 leaves registration 14 at 91, lever 4 leaves registration 12 at 151, and the
two together take registration 12 to 55 and registration 14 to 12. Levers 1 and 2 are settled by the
doctrine and the DDL and need no timing to justify starting; what the measurement decides is whether
they were enough, which of levers 3 and 4 is still owed after them, and whether the hop rung under
lever 4 is reached.

**Where the numbers go.** This file is deleted at Done, so a measurement whose only home is this
body evaporates with it. The page names three durable homes and every figure this item takes lands
in one of them: a registration's `reason` column for the refresh-against-re-evaluations arithmetic
and the rewrites that were tried and lost, the relation's own `COMMENT ON` for a cost warning a
caller cannot see at the call site, and `DerivedReadCostTest`'s pinned set for a pair the item
deliberately accepts. A refuted candidate lands there too, at the relation where the next reader
meets the hypothesis rather than in conversation.

The pass is re-measured whole after every lever rather than only the relation just fixed. Two
reasons, and the second is the stronger: a registration changes what every other reader's plan looks
like, which is why `DerivedReadCostTest` exists; and registrations 15 to 21 have never been reached
on this consumer, so the ranking this item is working from is a prefix of the pass rather than the
pass. If what the pass reveals past registration 14 is a cost of a different shape, that is a
successor item with its own goal rather than a widening of this one.

**When the levers stop.** The goal's "in seconds" needs an operational reading, because the item
before this one was signed off Done with a 53 s pass on a different consumer and the reviewer had to
decide that question at the gate rather than read it off the plan. The reading this item takes is
relational rather than a number invented here: the pass is no longer dominated when none of the
three is an outlier against the pass's own distribution, meaning each lands inside the band the
other eighteen registrations occupy, which today is 2.9 s at worst and about 6 s in total. A lever
that takes a registration from 2076 s to 30 s has moved it by two orders of magnitude and has not
met that criterion, and the next lever is owed. A delivery that meets it on two of the three and
leaves the third is one this item can be judged on, provided it says which one and why the next
lever lost, because the goal is the round rather than any one relation.

**Named as this item's neighbour and left to a successor: the node-identity resolution is stated
twice.** `intent_resolved_node_type_id` re-derives three tiers, in the closed vocabulary
`SDL_DECLARED` and `JOOQ_METADATA` and `TYPE_NAME`, that `graphitron_node` already resolves into
`type_id` and `type_id_origin` at capture; `intent_resolved_node_key_column`'s `tier` column is the
same duplication against `graphitron_node_keycolumn`'s `column_origin`, and
`intent_resolved_node_key_shape` one screen over already stands on the captured relation while its
sibling does not. Two spellings of one resolution agree exactly until one of them changes, so this
is a modelling defect on the page's own terms and not merely a cost. It is named here because lever
1 is the same move at the membership grain and an implementer will see it immediately, and it is
left out of scope because the two `intent_` reductions have populations of their own to reconcile,
declaration level against took effect, which is a plan rather than a paragraph. The measurement this
item takes prices it either way: the same two relations are what lever 2 takes from 55 and 24
instantiations to 11 and 12. File it as a successor when this item's levers land, not before, so
that the population question is written against a tree where the chain is already gone.

**Pickup precondition.** The measurement is on a consumer schema that is not in this repository, so
implementation needs a session that can run `mvn graphitron:dev` on the `sis` consumer's
`sis-graphql-spec` module and copy the store it leaves behind. Nothing in this repository captures a
schema that exposes the cost, which the section below states as a scope boundary and which is a
scheduling fact here: an implementer without that access can price nothing and should not pick the
item up.

## Tests

The wall clock is consumer-side and is not a build gate, per the section below: the delivery records
the per-registration `done in` figures and the pass total before and after, on the same consumer and
the same store generation, and says which levers were taken and which were refused by a control.

What answers the Done gate's completeness question inside this repository is the breadth metric,
which is the one surface the levers move that a reactor build can read. It is checkable today and it
moves in a stated direction: `report-inline-multiplicity` ranks `intent_node_id_instruction_live`
first at 230 instantiations with 114 of them one relation, and lever 1 alone takes that view to 134
and `intent_input_field_filter_role_live` to 45, lever 2 takes `intent_resolved_node_type_id` from
55 to 11 and `intent_resolved_node_key_column` from 24 to 12, and lever 4 takes the instruction view
to 55 and `intent_node_id_decode_hop_live` from 91 to 12. The delivery states the ranking before and
after, and the item is not complete while the node-id family still holds the top of it.

What the reactor owes alongside that, one build rather than one per gate:

- Lever 1's population equality gets the `EXCEPT` pair in both directions against the view as it
  stands today, over a populated store rather than a seeded one, plus a case that pins the arm the
  arithmetic turns on: an `@node` type with no table binding stays a node type through the entry
  relation, and a table-bound type inferred through published metadata stays one through
  `graphitron_node`.
- Lever 2's defect relation, once its owner stores it, keeps its rows by equality against the view
  it replaces, and `NodeMetadataDefectTest` gains the stored-fold case: a fixture carrying both a
  column whose SQL name folds onto the written spelling and one whose jOOQ name does, so the
  respelling cannot silently drop an arm of the disjunction.
- Lever 3's rewrite owes `intent_input_field_filter_role`'s row set by equality before and after,
  the `role` column included, since a left-join form that changed a verdict rather than its cost is
  the failure mode, and the arity claim is the reason the rewrite is safe.
- A new registration, which on this plan is lever 4 alone, owes the register's own two-line proof as
  a named test, its `_live` view in `FactCaptureAgreementTest`'s registration list, which fails a
  full build and not a scoped one, an index on the target in the shape the DDL already uses, and its
  `DerivedReadCostTest` pair. `MetaDeclarationGateTest` needs no roster edit by construction and the
  delivery should say so rather than discover it: the target keeps the canonical name, which already
  stands on the frozen roster, and the register subtraction exempts the new `_live` view.
- `DetectionReadReachGateTest` pins each detection component's read reach by equality, so a Java
  component whose `READS` set moves fails until it is declared. Levers 1 and 2 both move reach.
- Where lever 1's filing move is taken, the renamed relation is what every gate above reads, and
  `MetaDeclarationGateTest.aDeclaredViewReadsOnlyWhatItsOwnerMay` is what holds the new owner to its
  corpus set.

## What this item is not

It is not a wall-clock gate; nothing in this repository captures a schema of the size that exposes
this, and a fixture that did would be the build-wall-clock item's. It is not R857, which makes a
round refresh only what the edit touched and so attacks how often the cost is paid rather than what
it is. It is not R876, which is the ownership and shape argument the register sits inside, and which
states of itself that the refresh is not the cost it claims. Those three and this one can all land
independently, and this one is the only one whose unit of account is the pass's own wall clock on a
consumer population.

## Other solutions we've considered

**Registering the deepest relation in the chain and stopping there.** The instinct the breadth table
corrects. It cuts registration 12's breadth by 72 against the 96 lever 1 takes off it for no
refresh, leaves the two views above it expanded once per naming, and pays a per-save refresh of the
ten-arm union for a relation whose corpus moves on a jOOQ regeneration. Lever 2 takes the same
relation off every reader's evaluation path and pays nothing per save, which is the same prize on the
right cadence.

**Registering `intent_node_type` rather than re-sourcing it.** It is one instantiation against the
re-sourcing's three, so it is marginally the better truncation, and it costs a twenty-second row of
`meta_materialize`, a refresh of the whole chain once per graph per pass, an index declaration, a
`DerivedReadCostTest` pair, and a `_live` rename of a relation whose population is already sitting
in a keyed captured table. The page's own order is what decides it: the top rung is not the cheapest
rung by argument, it is the rung that says a rule reconstructing what capture already wrote is a
defect in the model whatever it costs.

**A well-formedness relation for the conjunction, stated in the `intent_` family.** The shape
`intent_inferred_node_type`'s comment names as the follow-on neither view performs, a relation on
`sql_table`'s key asserting that a metadata row has no defect rows. Rejected as this item's lever on
two counts. It is the negation of a detection rather than a fact with a grain, which is what "name
the row, not the question" is about: the five sites do not want well-formedness, they want the node
type, the type id and the key columns, and all three of those are already captured. And filing it
under `intent_` would put a single-family rule in the family the ownership rule says it does not
belong to, which is the fault lever 2 exists to fix rather than to reproduce one relation lower. The
comment's follow-on is not wrong, it is a fifth reader of the conjunction; lever 2 removes the cost
that made it look necessary.

**Changing the refresh cadence.** Refuted on the same population by the control recorded above: the
analysing path ran registration 12 22% slower than the in-transaction path with identical row counts
at every registration, so neither the cadence nor the statistics it supplies is the multiplier.

**Rewriting the defect view's ten arms as one pass over `sql_node_metadata`.** A rewrite with none
of the page's named exceptions behind it, unlike lever 3: no window function, no recursive term, and
no anti-join on the relation being rewritten. The page's general warning then applies, that a
rewrite usually changes nothing the planner cares about and sometimes regresses, two rewrites that
looked like obvious wins having measured several times worse than the shape they replaced. Priced
only if every lever above it loses, and the eight separate scans of `sql_node_metadata` are not
evidence of cost on their own.

**Waiting for R857.** Refreshing only what the edit touched divides this cost by how often it is
paid, which would make a save cheap and leave the boot pass where it is. The boot pass is what a
consumer meets first, and a fix that needs the other item to land is a fix neither item can be
judged on.

## Reviewer findings

### Round 1 (2026-09-14, Spec to Ready, reviewer session 01LEFtas3tdQ4XEbZ9S3wmiJ)

Revisions requested. The goal is stated in a consumer's terms and is judgeable on its own, the four
levers are each a named rung of the fact-model page's own order rather than a mechanism stood up
beside it, and every static figure in the body reproduces against the tree: the breadth table's
seven rows, the 230 with its 114/80/4 children, the 55 and 24 the Tests section moves, `instructed`
at eight references and `table_node` at two, the three positive references to `resolved_name`, the
`KEY_COLUMN_UNRESOLVED` disjunction, and the twenty-one registrations that make lever 4 a
twenty-second. What is holding the gate is the acceptance evidence rather than the plan, plus one
lever stated to a lower standard than its three siblings.

**The one check the reactor can read is passed by lever 1 alone, and the dearest registration moves
nothing it can see.** Simulating lever 1 in the metric's own terms, `intent_node_type` re-sourced
onto `graphitron_node_entry UNION graphitron_node`, and re-running `report-inline-multiplicity`
puts the top of the ranking at `diagnostic` 145, `intent_argmapping_projection_defect` 141, then
`intent_node_id_instruction_live` 134. So the node-id family is off the top after the cheapest
lever, with registration 13 still at 2076 s and lever 3 not started. Lever 3 is invisible to that
metric by construction, besides: it rewrites three correlated references to a common table
expression, and the metric counts `intent_input_field_column_match` once in that body before and
after. The Tests section's criterion, that the item is not complete while the node-id family holds
the top of the ranking, therefore passes early and cannot fail late, which is the shape of evidence
the Done gate's completeness question exists to refuse. The section already computes figures that
would discriminate, 55 and 45 and 12 per registration; state those, or say plainly that breadth is
a floor and that lever 3's acceptance is the consumer-side before-and-after plus the row-set
equality. Two views the ranking leaves above the pass after lever 1, `diagnostic` and
`intent_argmapping_projection_defect`, are read by no registration, so whatever the criterion
becomes should say what it expects the top to be rather than which family is not on it.

**Lever 2 is the only lever with no mechanism, and it carries the filing move the item makes
non-optional for lever 1.** Lever 1 owes a population proof, a chosen interface spelling, and an
explicit filing decision, of which the item says deferring silently is the one arm not available.
Lever 4 owes an index in a named shape, a `reason` arithmetic and a `DerivedReadCostTest` pair.
Lever 2 owes "once its owner stores it": no owner stage, no write, no table DDL, no clearing round,
no name. By the ownership reading the item itself applies, a view reading one family is that
family's, `intent_node_metadata_defect` reads only `sql_`, and the Other-solutions section already
calls its present filing the fault lever 2 exists to fix. So lever 2 is a move into `sql_`, with a
rename behind it that two Java importers (`Nodes.derive`, `NodeKeyColumns`) and two view bodies
(`intent_inferred_node_type`, `intent_resolved_node_type_id`) spell. One ordering fact comes with
it and constrains which owner can take the relation: `Nodes.derive` reads it, so the write has to
land before the graphitron gatherer's nodes stage. State the lever to lever 4's standard, and give
the Tests section the bullet the other three levers have.

The stored fold that rides with it has a closer precedent than the item cites.
`NodeKeyColumns.folds` is `COLUMN_NAME_UPPER.eq(upper(written)).or(JOOQ_NAME_UPPER.eq(upper(written)))`,
resolving the same published key-column spellings against the same catalog columns as the arm being
respelled. Citing it makes the respelling a transcription of the sibling rather than a judgment
call, which is the argument the lever wants.

**The stopping criterion is read against a band the item has not measured.** "Each lands inside the
band the other eighteen registrations occupy, which today is 2.9 s at worst and about 6 s in total"
takes as given a distribution over eighteen registrations, where the measurement section says
registrations 15 to 21 were never reached and the table prices eleven. The plan already re-measures
the pass whole after every lever, so the repair is small and the criterion survives it: say the band
is the eleven measured today, and that it is re-read against the pass's own distribution once the
pass completes. As it stands a delivery can meet a band that turns out not to be the band.

**The bisection axis for registration 12 points at the wrong common table expression.** The counts
are right, `instructed` at eight references and `table_node` at two. But of the six namings of
`intent_node_type` in that body, one sits in `table_node` and five sit in the union arms directly,
among them the correlated `NOT EXISTS` this item separately and correctly picks out as the excluded
relation with no drivable side. An implementer bisecting `table_node` for the chain's cost prices a
sixth of its reach and reads the chain as cheap.

Corrected in passing, being a symbol rather than a design question: the pickup section named
`AbstractRewriteMojo.resolveStoreDirectory` for the store that survives a clean. That method
resolves the build directory, which `mvn clean` does remove; `DevMojo` overrides it with the
per-user cache home the sentence describes, and the citation now names the override.
