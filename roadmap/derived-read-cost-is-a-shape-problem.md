---
id: R876
title: "Expensive derived reads are a modelling defect: capture writes the subtypes and omits the supertype, and materialization has been the first lever reached for instead of the last"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-08-31
---

# Expensive derived reads are a modelling defect: capture writes the subtypes and omits the supertype, and materialization has been the first lever reached for instead of the last

Every performance decision the fact model has taken has asked one question: should this rule be
stored or recomputed. That framing admits two answers, and `meta_materialize` is the record of
choosing "stored" twenty-two times. It never asks the prior question, which is why one evaluation of
the rule is expensive at all. On every relation measured against a real consumer schema, the answer
is almost never that the rule is inherently costly. It is a relation the capture family never wrote,
because capture modelled the kinds of a thing and never the thing, so readers reconstruct it from
multi-arm unions that H2 expands once per path through the dependency graph; or a join key that
exists only as an expression, which no index can serve. Both are defects in the model rather than in
any query, both are fixable at the source for milliseconds, and where they are fixed a registration
has nothing left to buy. That last claim is established at one relation by a controlled comparison
and is what the rest of this item has to establish at the others; the honest status against it is in
"What changes when this lands" rather than here.

This item takes over the performance narrative from nine dissolved items and states the lever
ordering the fact model should have been using. The evidence is in
`roadmap/audits/2026-08-28-derived-read-cost-premise.md`, which is filed as an audit precisely so it
outlives this file.

## What changes when this lands

**Restated as the item closes, because what it produced is not what it set out to produce.** The
register is the diagnostic and not the deliverable. Every defect this item fixed was found by asking
why a relation had been registered, and not one of the fixes was a retirement: the two supertypes,
the two stored join keys, the five grains, the captured head, the keyed candidate tree and the
gathering architecture are all things that question uncovered rather than answers to it. Twenty
registrations stand today against twenty-one when the item opened. That is the honest count, and it
is not the measure of the work.

**Two things ship, and they are worth naming separately because they were found on the same thread.**
The first is a fact model that states more about itself: relations that had never said what one of
their rows was about now carry grains and keys, the two reconstructions readers were unioning are
captured tables, the join keys that existed only as expressions are stored columns an index can
serve, and the argMapping candidate tree is keyed by the path it resolves. The second is a capture
that can read what it has written. Gatherers run in a declared order and each one's rows reach the
store before the next one starts, so a rule that needs another corpus is a query rather than a
hand-threaded Java parameter. That second one was not in the first draft's plan at all. It arrived
because four separate modelling questions kept coming back as charter arguments, and every one of
them turned out to have the same mechanical cause.

**The empty register stays as the standard rather than as this item's scope.** The five-second
budget below, and the two arms it is measured on, are unchanged and still say what they said. What
changes is who owes it. A successor takes the target carrying the burden this item established, that
a registration has to be shown necessary rather than shown wasteful, and carrying what this item
measured: four confirmed supertype omissions, of which this repointed two and left the largest
untouched, and the ten relations that move the wrong way on the emptied arm, three of which have a
named cause and seven of which do not.

Everything below this paragraph is the diagnosis as it was written, and stands as taken.

**The target is a register that is empty, and that is a statement about the model rather than about
performance.** At the data volume the ninety-ninth percentile of consumers carries, nothing in this
schema should need materializing at all. The register exists because the store ran without
statistics and therefore without a working cost-based planner, and materializing was how a rule that
the planner could not cost was made to finish. The planner works now. What is left in the register is
the crutch, and every registration still standing is a claim that the capture model cannot carry one
of its own queries honestly.

That sets the burden the other way from how the register's own reasons read. A registration does not
have to be shown wasteful before it goes; it has to be shown necessary, and necessary means the rule
underneath it is correctly modelled and still cannot be planned. Where it cannot, the answer is
capture writing the fact, an index on a stored column, or a rewrite, which is the lever order the
fact-model page now carries. Retiring a registration is therefore not the last step of this item, it
is how each defect underneath one gets exposed.

**Amended, and the amendment is sharper than what it replaces.** "The register should trend to
empty" was the right instinct with the wrong subject. Two things established since make it precise.
The first is that the register is not a mechanism standing on its own: every relation has an owner,
and the crossing rules are owned by a gatherer that runs after every corpus gatherer, so
`meta_materialize` is that owner's refresh plan rather than an institution to be abolished. The
second is a fact about this schema that no argument was needed to find.

**Every registered target is a relation with no primary key, and every relation with no primary key
is a registered target. Twenty and twenty, both directions, no exceptions.** That is not a
correlation to be interpreted, it is the same set counted two ways. A relation with no key is a
relation that has not said what one of its rows is about: one row per this, except when that, in
which case per something else. That is what makes it unkeyable, and unkeyable is what makes it
unindexable, and unindexable is what left materialization as the only lever anybody could reach.

So the question this item has been asking each registration, whether it is necessary, was asking too
late. **A registration is what a relation with no grain gets given.** The prior question is why the
relation has no grain, and there is no such thing as an unkeyable grain, only modelling that has not
been finished. The tree already carries the proof rather than the assertion: slice 3 collapsed three
census relations into one, hit exactly this wall because a key column cannot be nullable, and shipped
a spelled not-applicable bound to the discriminator by check constraints in both directions. That
relation is keyed today and states more about itself than the three separate ones could.

**The coincidence has since been broken on purpose, which is the point of having recorded it.** Five
of the twenty carry a primary key as of slice 14, so the two sets no longer coincide and the sentence
above describes where this item started rather than the schema today.

**Which also says what the last gatherer should be materializing, and it is not what it materializes
now.** If that gatherer's job is to build the tables the views and queries above it stand on, then
what it builds should be grain tables, keyed on what a row is about. Twenty keyless copies of view
bodies are not grain tables. The register is not only larger than it should be, it is the wrong
shape, and that is not a defect any single retirement can reach.

**None of this retires the emptied arm or the lever order below.** They are unchanged and still say
what they said. What changes is the order of the questions: grain first, then whether a lever is
needed at all.

**Two costs of holding one, and the second is why break-even is not a reason to keep.** The first is
the refresh, per capture, which is what the reasons in the register price. The second is that a
materialized target is a table with statistics of its own, so every planner decision above it bottoms
out there and the rule underneath becomes invisible to the planner and to anyone reading a plan. The
item has a worked case of exactly that: `intent_errors_field`'s real cost was a window function one
relation below it, five and a half seconds of it, and it sat unseen behind the registration until the
register was emptied to look. A registration that is not paying for itself is not neutral. It is a
blindfold over whatever is underneath it.

**So the arm with the register emptied is a defect detector and not a research arm.** A relation that
does not answer there is not an expensive rule that earns its registration; it is a modelling defect
with a registration in front of it. On the arm taken 2026-08-29, `intent_argument_scope_table` takes
101 seconds and `intent_input_field_resolving_table` 117. Those are the next two questions this item
has, and they are questions about capture rather than about the register.


**The refresh is not the cost any more, and this item no longer claims it.** The first draft promised
a capture in the tens of seconds against four hours and nineteen minutes. That gap closed on the tree
while the item sat at Spec, and the audit's new section 10 measures it: the same store, rebuilt on
the shipping DDL under the shipping cadence, refreshes in **43.0 seconds**, every registered target
coming out at the row count the original capture recorded for it. Two changes did it, both already
merged: the cold-refresh split, and the two payload registrations that landed 2026-08-28, which are
worth 588.2 seconds down to 43.2 on their own. Nothing in this item's slices was needed for any of
it.

**The outcome the target names is a store that spends no time materializing and still answers its
readers.** Not a cheaper refresh: no refresh, because every fact a reader needs is a fact capture
wrote, and every key a reader joins on is a column an index can serve. On that target the register
is not a thing to prune, it is a thing to make unnecessary. Per the restatement above, that outcome
is what the successor is measured against; what this item owes is the shape work underneath it.

**Measured against that target, the item is not there and the gap is now named rather than guessed.**
Two arms on the kept store, both with statistics present, both over the 39 relations the generator,
the language server and the MCP server name, 120 seconds per statement. The read figures are
`SELECT count(*)` per relation, which is an upper bound on what a consumer pays and is used here only
to compare arms, the same statement running in each:

[cols="4,2,2,2"]
|===
| arm | refresh | reads | over budget

| the register as it ships, 22 registrations | 43.0 s | 251.5 s | 1 of 39
| no registrations at all, plus the two supertypes and two keys this item knows about | 0 s | 1054 s, a floor | 7 of 39
|===

So as things stand the register wins the total, and this item should not pretend otherwise. What
makes it still the right item is the shape of the difference rather than its sign.

**One relation moves the right way, and it is the one the register cannot fix.**
`intent_field_accessor_hop` is the only statement that refuses the budget with all 22 registrations
in place. With none of them, and a `GENERATED ALWAYS AS` bean-property column with one index on it, it
returns its 21287 rows in **1.90 seconds**. The register had been the answer to that relation for as
long as it has existed and was never able to fix it, because the defect was a join key that existed
only as an expression and no registration can index an expression.

**Ten move the wrong way, and three of them have a named cause.**
`intent_argmapping_projection_defect`, `intent_node_id_decode_defect` and
`intent_resolved_node_key_projection` all refuse the budget without the register, and all three sit
above `intent_argmapping_bound_parameter_type`, the six-arm reconstruction of the one confirmed
missing supertype no slice in the first draft took. They are the only residual relations that sit
above an unrepointed reconstruction, which makes them a prediction slice 3 can be held to rather than
a mystery. The other seven have no such cause identified yet, and saying so is more useful than
implying the signature explains everything.

**So the honest status is under-implemented, not refuted.** Four confirmed supertype omissions exist;
the arm above fixes two of them and leaves the largest untouched. The target is
therefore stated as a target and a test: **no relation in the consumer read set refuses a five-second
budget with nothing materialized**, which is what makes `mvn graphitron:validate` and
`mvn graphitron:dev` usable on that schema and lets `graphitron:dev` reach its language-server and MCP
binds. If the four supertypes and two keys land and that is still false, the remaining registrations
have earned themselves an argument they have never yet had to make.

**The budget is five seconds, and every measurement in this item was taken against a hundred and
twenty.** The figures below are left as they were taken, because a measurement is a record and not a
claim about what is acceptable; what changes is the line they are judged against, and the line moves
by two orders of magnitude. Two consequences, both derivable from figures already here rather than
needing a new arm.

**The register as it ships does not meet this budget either.** The audit's read pass over the 39
relations a consumer names records one relation refusing outright and seven more between 7.1 and 32.6
seconds. So at least eight of thirty-nine refuse five seconds with all the registrations in place,
against one of thirty-nine at a hundred and twenty. That is the strongest form of this item's premise
yet stated and it is not this item's own measurement: the register was already failing the standard,
and reading its arm as the winning one was an artefact of where the line was drawn.

**And on the emptied arm, three of the twenty registered targets answer inside five seconds.**
`intent_resolved_type_binding` and `intent_spelled_table` inside a second,
`intent_field_reference_step_hop` at 1.5. The three that answered between 25 and 117 seconds move from
successes to failures, which sharpens rather than weakens what the table below says: those three were
never evidence that a rule is fine unregistered, they were evidence that it can be made to finish.

**A five-second budget also makes the measurement cheap, which is worth saying out loud.** A sweep
whose failures each cost two minutes is a job to schedule; one whose failures cost five seconds is a
job to run inline. The exception is a relation that cannot be planned, where the budget does not
apply at all and the cost is whatever the planner spends before it gives up.

Nothing a reader asks the store changes: the same relations, the same rows, under the same names.

## Vocabulary

Carried from the dissolved cut-set item, because the subject needs it and the file that held it is
gone.

A **derived relation** is a view: a rule stated once in SQL and evaluated whenever a reader names it.
A **registration** is a row of `meta_materialize` keeping the rule in a view under a `_live` name and
moving the canonical name every reader spells onto a table, which the **refresh** refills once per
capture. A registration is paid for twice: **read cost** is what it buys, the evaluations that no
longer happen because readers meet a table, and **refresh cost** is what it charges, the one
evaluation the materializer pays per capture. A relation read once between writes gains nothing from
a registration; a relation read zero times gains nothing at all and still pays.

A **grain** is the unit of fact a relation states one row of.

A **subtype set** is a closed group of captured tables that state the same fact about different kinds
of site, carrying the same attributes and differing only in the key that says which site owns the row.
The eight `*_arg_mapping_pair` tables are one. Its **supertype** is the relation that states the fact
once, with a **discriminator** column saying which kind of site a row came from and a uniform key. A
supertype is **missing** when no captured table states it, at which point readers reconstruct it by
`UNION`, once per reader. The first draft called this a missing grain and argued it from cost; the
vocabulary here is deliberate, because a missing supertype is a defect in the model whether or not any
reader is currently slow, and that is what makes the set of them enumerable.

## What is established

Stated compactly; the audit carries the tables, the method and the provenance of each figure.

- **The refresh was 15477.1 seconds and is now 43.0.** Five of twenty positions carried 99.4% of the
  old pass and one of them was half of it while inserting 545 rows, which is what said the cost was
  never about volume. Both of those positions now cost under a second and a half between them. The
  old figures are the record of what the defect cost; nothing in this item is an argument to spend
  effort on the refresh axis again.
- **Twelve of the twenty-two registered targets are unreachable from anything that reads the store.**
  They are not orphans: their readers are each other plus seven further `intent_` views, none of
  which is reached either. It is a closed subgraph with no exit, and it is the mutation write surface
  and the input-filter surface, which are under construction. On the old pass they carried 95.4%; on
  today's they carry 67.4% of 43.0 seconds, which is 29 seconds. **The finding is intact and its
  stakes are not.** That a registration gets made before anything reads it is still true and still
  unowned by any rule; what is no longer true is that it costs hours.
- **Four facts are written as subtype tables with no supertype table, and readers reconstruct them
  by union.** Ten tables carry `class_name` and `method` and no relation says which site declares
  which method; three views reconstruct it. Six carry a written table-or-routine reference and one
  view reconstructs it, eight carry an argMapping pair and one does, three carry a declared type
  reference and two do. The signature is mechanical off the DDL and needs no store, which is what
  makes this the finite part of the item.
- **Two of the four cost 0.05 and 0.04 seconds to build as tables, at 313 and 108 rows, and take the
  worst plan in the stratum from 235756 lines to 89296.** Five relations that had not completed in 120
  seconds complete, with nothing materialized.
- **One expression-keyed join, fixed by a stored computed column plus an index, goes from over 120
  seconds to 1.84 seconds** at 21287 rows either way. Re-measured with the register emptied
  altogether: 1.90 seconds, against refusing a 120-second budget with all twenty-two registrations
  present. **This is the one place the whole thesis is settled rather than argued.**
- **Materialization kept winning because it was the only candidate on the ballot, and because a
  registration silently delivers an index** that a view cannot carry. That is why demoting the
  register looks catastrophic and why it reads as load-bearing.
- **Statistics were necessary and were not sufficient, and the second lever was a registration
  after all.** The cold-refresh split is 69-fold across the old positions 1 to 16, 6262.6 seconds
  against 90.8, and it bought almost nothing on the tail. What closed the tail was the two payload
  registrations, measured here at 464.5 and 82.3 seconds down to 0.2 and 1.3. That is worth stating
  plainly because it cuts against this item's own ordering at one point: on those two relations, on
  that axis, a registration was what worked. What it does not show is that a registration was the only
  thing that would have worked, because rungs 1 to 4 were never tried on them, and that distinction is
  the whole of what the ordering claims.
- **The register is not buying good reads either.** With all twenty-two registrations in place the
  consumer read set costs 251.5 seconds and one relation refuses a 120-second budget. With none of
  them and the two known supertypes and two keys in place it costs 1054 seconds and seven refuse. So
  the register wins the total today, and the relation it cannot fix is the worst one in the set. Both
  halves of that belong in the item rather than only the convenient half.

## How this stands against the set-level pricing of the register

R848 priced the register as a set and reached Done on 2026-08-28, concluding that all twenty
registrations earn their place. Its file is gone, so its figures live in `roadmap/changelog.md` under
its own entry and the full reconciliation is the audit's section 9. On a first reading the two
results look opposed. They are not, and the four points that matter to this plan are these.

**They measure different quantities.** That harness removes a registration and reports refresh saved
against read time lost. This item walks the view graph outward from the 39 relations a consumer names
and asks whether a target is demanded at all. For twelve targets both answers hold at once: the
readers exist, they do get much more expensive without the registration, and no consumer is ever
waiting on any of them. Its own entry contains the seam, recording that `intent_argument_column_scope`
"has no reader that is not itself a registered source view", and letting that row stand on a
pre-committed rule rather than on a measured value.

**Its population caveat has mostly discharged itself.** Its Done gate bounded every verdict to sakila
on the ground that a consumer population's refresh axis weighs orders of magnitude more. That axis is
now 43.0 seconds against about 1317 ms, a factor of thirty-three rather than four orders of magnitude,
so the reason a sakila verdict could not travel has largely gone.

**The one figure worth re-taking has been re-taken.** Candidate C costs 568 seconds across thirty
readers, summed over readers whose reachability was never checked. The check is structural and needs
no harness: on R848's own twenty-registration schema, keeping its four shoulders, **31 relations above
the register lose a registration and 16 of the 31 are unreachable**. Thirty-one against thirty is the
same set counted the same way. It is not half of the 568 seconds, because the entry records that eight
of the thirty refused a 60-second budget without saying which eight, so only the relations split and
not the time. This is the outcome that could have gone the other way: had most come out reachable,
the argument about the register here would have failed, and the plan was written to let that happen.

**What does not carry is one inference**, from "the readers of X get dearer without X" to "X earns its
place", which holds only where somebody pays for those reads. What is sound and is not re-run here is
the harness, the three-capture spread analysis, the candidate-C result and the shape pin; the spread
discipline is the right standard for anything measured on this subject and should be adopted rather
than re-derived. Nothing here says the register is incoherent or reopens a Done item.

## The defect underneath the cost: capture models the subtypes and omits the supertype

The first draft called this "a grain the capture family never wrote" and argued it from what it
costs. That is the symptom. The defect is a modelling one, it can be stated without measuring
anything, and stating it that way is what makes the rest of this item finite.

**What capture does.** Where one fact is written at several kinds of site, the capture family writes
one table per kind and no table for the fact. A directive site that names a Java method is written by
ten tables, one per directive that can name one; a written table-or-routine reference by six; an
argMapping pair by eight, one per directive that can carry `argMapping`; a declared type reference by
three, one per census position that can hold a type. Each set is closed, and within a set every member
carries the same attributes and differs only in the key that says which site owns the row.

**What is missing is the supertype.** A subtype set with no supertype relation leaves every reader
whose question is uniform across the sites to reconstruct one, and a reader reconstructs it the only
way SQL allows: `UNION` the arms, synthesise a discriminator, and synthesise a uniform key. That is
not an optimisation the reader chose badly; it is a reader doing the modelling capture did not do,
once per reader.

**The store already contains a worked confession of this.** `intent_declared_type_ref` unions the
three `jvm_*_type_ref` tables and its own comment explains why: "the three census relations are three
keys, so a reader whose question is uniform across the owners has to name the owner before it can
ask". It carries `owner_kind`, a discriminator whose comment says there is "one value per census
relation of this shape, and there is no fourth". It carries `owner_descriptor` and `owner_position`,
both NULL on exactly the arms whose key does not need them, and the comments say each NULL is "the
union's key shape rather than a fact withheld". A discriminator, arm-determined NULLs and a
synthesised uniform key over a closed set of subtypes is a supertype relation. It is written as a
view because capture wrote no table for it.

**The signature is mechanical, needs no store, and is therefore gate-able.** Three parts, all read
off the shipped DDL and nothing else. A **subtype set** is three or more capture tables sharing a
group of attribute names, where an attribute is a column that is not in that table's own primary key
and is not provenance. A **confirmed omission** is a subtype set that some view `UNION`s three or more
members of **and** whose shared attributes that view names, the third part being the one that stops a
set claiming a union it has no part in. Run over today's schema:

[cols="4,1,1,4"]
|===
| the fact nobody wrote a table for | subtypes | union sites | the shared attributes

| a directive site that names a Java method | 10 | 3 | `class_name`, `method`
| a written table-or-routine reference | 6 | 1 | `table_ref` and its four split and folded halves
| an argMapping pair | 8 | 1 | `argument_path`, `param_name`
| a declared type reference | 3 | 2 | `referenced_class`, `variance`
|===

Two more sets carry a shared attribute group that nothing unions yet, so they are the same modelling
defect with no reader paying for it: a GraphQL type expression, written by `graphql_field`,
`graphql_argument` and `graphql_directive_argument`, which share all eight of `type_sdl`,
`named_type`, `non_null`, `is_list`, `item_non_null`, `default_value_sdl`, `description` and
`ordinal`; and a described ordinal member, written by three.

**The first row is the finding, and no cost-driven pass was going to reach it.** Ten directive tables
each carry `class_name` and `method`, because ten directives can name a Java method, and there is no
relation saying "this site declares this method on this class". Three views reconstruct it:
`intent_argmapping_bound_parameter_type`, whose six `UNION ALL` arms are the same query six times,
take an argMapping pair, join the directive table that owns the site, project `class_name` and
`method`, differing only in which table is joined and which `site` literal is filtered; and
`intent_condition_param_extraction` and `intent_condition_table_parameter`, which do the same at five
arms each. None of the three is badly written. Each is doing, once, the modelling capture did not do,
and there are three of them because there is nowhere to do it once.

**The third part of the signature was missing until the Spec gate found it, and the correction is
worth carrying rather than quietly applying.** Without it the first two rows read six sites and four.
Both were inflated by the same mechanism: set membership overlaps, the `*_reference_step` tables
carrying `table_ref` and `class_name` and `method` all three, so a view that unions them to answer
"which method does this site name" was counted as a reconstruction of the table-or-routine reference
as well. `intent_condition_param_extraction`, `intent_condition_table_parameter` and
`intent_argmapping_bound_parameter_type` contain no occurrence of `table_ref` or `routine_ref` at all.
Requiring the union's own view to name the set's attributes puts row 1 at three sites and row 2 at
one, and leaves rows 3 and 4 where they were.

**What this reframing buys that the cost framing did not.** It found the declared type reference,
which the performance investigation never reached because that one is cheap on this consumer's schema
and a hunt driven by wall clock stops when the clock stops. It found the method-bearing site, which is
the largest set in the schema and had no name before this. And it names the mechanism connecting a
modelling defect to a measured cost: H2 expands a shared subtree once per path, so every independent
reconstruction of one missing supertype is expanded independently at every reader above it, which is
why this shows up as plan expansion rather than as slow scans.

**What it does not license, and three traps the scan fell into in order.** A shared attribute group is
a candidate and not a verdict: four `sql_*` tables share `table_schema`, `table_name` and
`column_name` without being subtypes of anything, because they reference a column rather than being
kinds of one. The union part separates those, since a reader that wants a supertype unions the arms
where a reader that wants a reference joins them.

The key-versus-value split has to be read from each table's own primary key rather than from a list of
key-looking names. The first version excluded `class_name` globally, on the ground that it is the key
of `jvm_class`, and so missed the ten-table set entirely. A name is a key in one family and a value in
another, and a detector that forgets that hides exactly the sets where a supertype is most overdue.

And a set may not claim a union on membership alone, which is the attribute part above. The two traps
pull in opposite directions and that is the useful thing about them: the first hid a real set, the
second invented reconstructions for sets that had none, and each was caught by asking what the arms
actually project rather than which tables they name. Both were found by checking the scan's output
against the DDL by hand, which is the standing rule for this instrument: a confirmed row is a reading
to check and not a verdict to inherit, and anything built as a gate owes either a tighter check than
name matching or a written exemption list.

## What the register is cutting: the size of the statement the planner has to hold

The store's own notes already record that H2 inlines a view at every naming and performs no
common-subexpression elimination, so a rule named twice inside another rule is evaluated twice. What
that fact does at the scale of this schema had never been counted, and counting it changes what the
register looks like it is for.

**The quantity, and it needs no captured store.** Give every base table one unit. Give every rule one
unit plus, for each relation name it spells, that relation's own count multiplied by the number of
times it spells it. The result is the size of the fully inlined statement the parser and the planner
have to build before any row is read, and it is a function of the DDL alone, so any session can
compute it from the file in this repository with no store, no capture and no consumer.

**On the register as it ships, nothing is large.** The biggest number any relation in the schema
reaches is 963, at `intent_argmapping_projection_defect`. Every registered target is a table, and a
table costs one, so each registration truncates the tree at that name.

**With the register emptied, the same schema reaches 2739455.** The relation that gets there,
`intent_mutation_write_agreement`, is not registered and never has been: it is an ordinary view that
names `intent_mutation_write_destination` twice, and that target's rule expands to 1369722 on its
own. The twenty registered targets, with the register emptied, in order, beside what a
`SELECT count(*)` over each one does on that arm against the kept 2026-08-27 consumer store, one JVM
per statement with a 120-second budget:

[cols="4,2,3"]
|===
| registered target | inlined size, register emptied | `count(*)` on the emptied arm

| `intent_mutation_write_destination` | 1369722 | no plan: heap exhausted after 144 s
| `intent_mutation_payload_key_membership` | 456350 | no plan: heap exhausted after 154 s
| `intent_mutation_payload_column` | 174786 | planned, over the 120 s budget
| `intent_mutation_payload_refusal` | 106107 | planned, over budget
| `intent_input_field_carrier_role` | 41227 | planned, over budget
| `intent_node_id_decode_column` | 14773 | planned, over budget
| `intent_input_field_filter_role` | 10592 | planned, over budget
| `intent_node_id_decode_hop_column` | 5359 | planned, over budget
| `intent_node_id_instruction` | 3115 | planned, over budget
| `intent_argument_column_match` | 1414 | planned, over budget
| `intent_argument_column_scope` | 1409 | planned, over budget
| `intent_mutation_write_payload` | 661 | planned, over budget
| `intent_input_field_resolving_table` | 660 | 1804 rows in 117 s
| `intent_argument_scope_table` | 657 | 967 rows in 101 s
| `intent_field_scope_table` | 655 | planned, over budget
| `intent_field_column_scope` | 362 | planned, over budget
| `intent_carrier_data_field` | 318 | 151 rows in 25 s
| `intent_resolved_type_binding` | 79 | answered inside a second
| `intent_field_reference_step_hop` | 45 | 12817 rows in 1.5 s
| `intent_spelled_table` | 4 | answered inside a second
|===

**Eighteen of the twenty cannot answer a row count in two minutes, and two cannot be asked.** That
is the defect list this item said the emptied arm would produce, and it is now complete rather than
partial. Three caveats on how it was taken, none of which move the shape. The arm is the kept
2026-08-27 store rebuilt on the DDL as it stood before this item's two retirements, with the seven
tables that capture predates transcribed in, so it is a schema of this item's making and not a
consumer's. Each statement ran in its own JVM, so none of them warmed the page cache for the next,
which makes these slower than the same statements taken in one session and is why the two failures
that are not timeouts are reported as heap rather than as time. And a `count(*)` is an upper bound on
what a consumer pays, used here only to compare arms.

**So the register's first effect is not that it saves evaluation time. It is that it stops the
statement from growing.** That is a different claim from the one the register's own reasons make,
and it is the one that explains the failures at the top of that table.

**Most of what the register protects is not registered.** With the register emptied, thirty-eight
relations sit above the 963 the whole schema lives under today. Twenty of those are the registered
targets and their rules, one pair each. The other thirteen are ordinary views nothing has ever
registered, and the largest relation in the schema on that arm is one of them. They are plannable
today only because a table sits between them and the rules underneath. So a registration is not
bought for its own target's sake, it is bought for whatever stands above it, and the register's own
reasons, which argue each row on its target and its named readers, are arguing about the wrong end
when the cost is structural.

**Two relations cannot be planned at all, and the evidence is that no row is involved.** On the arm
with the register emptied, `intent_mutation_write_destination` and
`intent_mutation_payload_key_membership` both exhaust a four-gigabyte heap, after 144 and 154 seconds,
with stacks that are entirely `Parser` and `TableFilter.getBestPlanItem` frames and contain no
execution frame at all. The failure therefore precedes any row being read and is a property of the
schema rather than of the data in it.

**Which also corrects how the earlier arms were counted.** `SET QUERY_TIMEOUT` bounds execution, so a
statement that never finishes planning is never bounded by it. The relations that refused a
120-second budget on the earlier arms were two different failures read as one: some planned and then
ran out of budget, which is a cost, and some never reached execution, which is not a cost but an
inability. Only the first kind is a candidate for being made faster.

**What the quantity predicts, and what it does not.** It predicts plannability and nothing else. On
the emptied arm `intent_field_column_scope` is 362 and refuses a 120-second budget in execution,
while `intent_carrier_data_field` is 318 and answers in 25 seconds: the same expansion regime, and
the difference is the data. Above roughly two hundred thousand the planner dies; between there and a
few hundred a plan gets built and may still be slow; below a thousand the statement is the size the
schema already lives with today. Anyone reading this table for execution cost is reading it for
something it does not measure.

**Seven cuts get the whole schema back to where it already is, and all seven are registrations
today.** Treating a name as resolving to stored rows rather than to a rule is a cut. Cutting
greedily, worst-first, seven bring the emptied schema's largest statement to 993, against the 963 it
lives at with the register in place: `intent_field_scope_table`, `intent_mutation_payload_column`,
`intent_mutation_payload_refusal`, `intent_node_id_instruction`, `intent_field_reference_step_hop`,
`intent_resolved_type_binding` and `intent_node_id_decode_column`, in that order and with the first
worth more than the other six together. Going strictly under 963 takes an eighth, and the eighth is
`intent_node_type`, which is not registered and never has been. Greedy, so this is an upper bound on
the minimum rather than the minimum.

**The thirteen registrations outside that set contribute nothing to plannability.** Whatever each of
them buys, it is not the planner's ability to build a plan, and each is therefore answerable on
execution cost alone, which is the argument the register's own reasons make and the only one left to
them.

**A cut is not the same thing as a registration, and that is this item's point.** A cut is any way
the name comes to stand for stored rows. Capture writing the fact is a cut, an index is not a cut but
makes one affordable, and a registration is the cut that pays a refresh for the privilege. The list
above is therefore not a list of registrations to keep. It is the ranked list of facts the capture
model has to take responsibility for, and it is the first time this item has been able to state that
list in an order rather than as a principle.

**Taken through the store's own walk, not through a text scan.** The counts above come from
`ViewReferences`, which parses each stored view definition with jOOQ's parser and returns one entry
per reference, with aliases and common table expression names normalized away. That class already
exists for the refresh ordering and its javadoc already states the mechanism this section measures,
that H2 inlines a view at every naming and eliminates no common subexpression; what had not been done
is to compound it through the graph. A naive count of textual namings over the DDL was run first and
agrees with it to the digit on this schema, which says only that no alias or column name here
collides with a relation name. The parser walk is the one to build anything further on.

**What this measurement is and is not.** It counts namings, so it is a model of what H2 does with a
view rather than a measurement of what H2 does. Its warrant is that it ranks the observed failures
correctly, with both unplannable relations at the top and every relation the schema plans today far
below. It should be read as an ordering, not as a size in bytes. `ViewReferences` also records
whether a reference sits in a correlated or a recursive position, which multiplies evaluations rather
than expansion, and this count deliberately ignores that: the quantity here is how large the
statement gets, not how many times it runs.

## The lever order

`docs/architecture/explanation/fact-model.adoc` already states a hierarchy, and this item's ordering
is not a gloss on it. **What survives from the page is its top rung and its reasoning.** A captured
fact is first, for the cost argument the page gives, that a fact has no refresh to pay at all; and a
rewrite is treated with suspicion, for the reason the page gives, that it usually changes nothing the
planner cares about, which the audit's two refuted rewrites confirm at 330.7 seconds and at
byte-identical plans. **What changes is where a registration sits.** The page puts it in the middle,
above a rewrite. This item puts it last, behind a precondition the page does not have. That is a
doctrine change and is named as one below rather than presented as practice catching up, because a
contributor meets the page before they meet any roadmap item. It also adds a rung the page does not
carry at all, the index, which the audit found the register has been silently delivering.

In the order to attempt them:

1. **Capture the supertype.** If a view reconstructs one fact from a union over sibling tables of a
   closed subtype set, capture is missing a relation and should write it. This is a correctness rung
   before it is a performance one: the reader is doing modelling work, and it does that work once per
   reader. The supertype table needs no refresh, cannot go stale, needs no registry row, and carries
   indexes for free, but those are consequences of it being a fact rather than the argument for
   writing it.

   **What happens to the subtypes is decided by their data, never by their constraints.** A subtype
   keeps a relation of its own when it carries data the supertype cannot hold, and then the two sit
   side by side. A subtype whose rows are the shared fact and nothing more does not: it becomes rows
   of the supertype told apart by the discriminator column, and its table goes. The first draft of
   this item got that wrong in one direction and it is worth recording why, because the wrong answer
   is the tempting one. Each of the eight `argMapping` pair relations carried a foreign key into the
   directive that owned its rows, and no relation spanning nine parents chosen by a column can
   express that, so the first slice kept all eight beside the supertype and paid for it immediately:
   every producer that was not capture owed a second write, which is what the model-tier fixtures
   then had to be given by hand. A foreign key is a constraint, not data. Giving one up costs an
   enforced edge, which becomes an invariant a producer maintains and a test checks; keeping the
   table costs a duplicated relation, a second write on every producer, and a choice on every
   reader. The second is the larger bill, and it is paid forever.
2. **Store the key.** If a join key exists only as an expression, put the value in a column: a
   `GENERATED ALWAYS AS` column when it is a pure function of a neighbouring column, a captured fact
   when the value arrives from outside. Then index it.
3. **Index what is already stored.** Necessary, and never a fix for expansion.
4. **Rewrite the rule.** Only against a measured alternative; see the two refuted rewrites in the
   audit.
5. **Register it.** Last, and only for a relation with a reader, after the four above have been tried
   on it.

Rung 5's precondition is the one with teeth: a registration is currently made without anybody asking
whether the target has a reader, and twelve of twenty-two do not.

**Where the ordering is on weaker ground, stated here rather than left for the gate.** The two
payload registrations that landed 2026-08-28 took their positions from 464.5 and 82.3 seconds to 0.2
and 1.3. Nobody tried rungs 1 to 4 on those two relations first, so what that pair shows is that a
registration worked, not that it was the only thing that would have. This item does not reopen them
and does not claim they were wrong. It claims the four rungs above should have been on the ballot,
which is a claim about process that their success does not settle either way.

## Implementation

The first draft opened with a slice 0 of two determinations that had to be answered before anything
below could be designed, and made the rest of the plan conditional on them. **Both have been taken,
against the kept store, and they are reported in the audit's section 10 rather than left as work.**
What they found is folded into the slices below, so the plan is no longer conditional and there is
nothing here for an implementer to unblock.

**No slice below removes a registration, and that is what makes them safe to land one at a time.**
The measured arm that empties the register is a research arm and not a proposal: it exists to price
what the shape fixes are worth on their own, and it comes out worse than the register today. Landing
a supertype or a stored key while the register stays in place cannot regress a read, because it
removes an evaluation the reader was doing and adds a table the reader can seek. Retiring a
registration is a separate decision, taken per relation, against the read figures that exist after
the shape work rather than before it. An implementer who reads the arm in "What changes when this
lands" as an instruction to empty `meta_materialize` has read it backwards.

### Slice 1: capture the two supertypes that are unioned today

Write the written-table-or-routine reference and the argMapping pair as supertype tables, and repoint
every view that reconstructs one. Both were measured on the kept store: the tables fill in 0.05 and
0.04 seconds, and each repointed view returns exactly the rows it returned before, 313 and 108.

**What the two are worth is isolated by a pair of arms that differ only in them.** With the register
empty and no shape fixes, `intent_carrier_routine_hop` costs 77.90 seconds. With the register still
empty and these two supertypes captured, it costs **0.07 seconds**. Nothing else changed between the
two arms for that relation, and nothing is materialized in either, so the whole of that difference is
two tables capture should have written.

**The repoint is exactly as wide as the audit's class A said, and an earlier draft of this slice
claimed otherwise.** It said the table-or-routine reference was unioned at four sites and made
"repointing only the first leaves three reconstructions standing" its main correction. That was an
artefact of the detector counting union sites from set membership alone, before it required the view to
name the set's own attributes; the three extra sites union the same tables for `class_name` and
`method` and contain no occurrence of `table_ref` or `routine_ref`. The reference is reconstructed at
one site, `intent_spelled_table_live`, and repointing it is the whole of this half of the slice. The
argMapping pair is likewise one site, `intent_argmapping_pair_live`, whose eight arms cover seven of
the set's eight members, `graphitron_field_condition_arg_mapping_pair` twice for its two site literals
and `graphitron_argument_reference_for_step_arg_mapping_pair` not at all. Nothing about the
supertypes themselves moves; what moves is a claim about how much work repointing them is, in the
direction of less.

Two things the Spec decides here rather than the implementer. **Where the supertype lands and what its
key is**: the subtype tables differ only in the key that says which site owns the row, so the
supertype needs a discriminator and a uniform key, and `intent_declared_type_ref`'s `owner_kind`,
`owner_name`, `owner_descriptor`, `owner_position` shape is the worked example to follow rather than
to reinvent. **Whether writing it is capture's job or a producer's**: capture, on the ground that both
supertypes re-tag rows that were facts on arrival and capture already holds their provenance.

One naming defect to fix on the way, because it is the same defect showing through. Six of the seven
subtypes spell the reference `table_ref`; `graphitron_routine` spells the identical fact
`routine_ref`. The mechanical detector misses the seventh arm for that reason alone. One fact spelled
under two names is what happens when there is no supertype to name it once, and the supertype is where
that gets settled.

**What this slice shipped first and had to take back, recorded because the correction is the item's
main lesson rather than a footnote to it.** It landed add-only: both supertypes written beside the
per-site tables, all of which stayed. That was decided on the foreign keys, each pair relation having
one into the directive that owned its rows and no nine-parent relation being able to express it. The
bill arrived immediately. Every producer that is not capture then owed a second write, which is why
`SeededStore` grew a transcription step, and forty-three model-tier fixtures needed it before they
passed again. The rule on rung 1 above is what settles it and it settles it against the shipped
shape: the eight pair relations carried no data beyond the shared pair, so they are gone and
`graphitron_arg_mapping_pair` is the only relation that holds one. The spelled reference is the other
side of the same rule and keeps its sites, each of those carrying its own directive's payload.

Two consequences worth naming because neither was foreseen here and both are evidence for the lever
order rather than against it. **The collapse deleted the transcription outright**, the second write
having been the only thing it stood in for, so the fixture surface got smaller rather than larger.
And **`intent_argmapping_pair`'s registration is now buying close to nothing**: its source view is a
plain read of one captured table, where the reason in `meta_materialize` prices it as an eight-arm
union expanding to fifty-five instantiations. Rung 1 landing retired a rung 5, which is what the
ordering predicts and is the first instance of it in this item. Re-pricing that registration is owed
and is a slice of its own, the register being priced set-relative so that dropping a member re-prices
its neighbours; it is not folded into the slice that caused it.

### Slice 2: store the two transformed join keys as columns

The bean-property name as a `GENERATED ALWAYS AS` column on `jvm_method` with an index, and the
navigated type name as a stored fact. Then repoint the readers. The split is the audit's rule: a value
computed from a neighbouring column is a generated column, a value arriving from outside is a captured
fact.

**This is the slice with the strongest result behind it, and three arms isolate it.**
`intent_field_accessor_hop` is the worst read in the consumer read set, and it returns the same 21287
rows in all three:

[cols="5,2"]
|===
| arm | the accessor hop

| all twenty-two registrations, no column | refuses a 120-second budget
| no registrations at all, no column | refuses a 120-second budget
| no registrations at all, with the column and its index | **1.90 s**
|===

The register makes no difference to it in either direction and the column makes all of it. That is
this item's thesis met at its strongest point, and it is the one place where the evidence is a
controlled comparison rather than an argument.

The navigated-type site needs a decision the first draft got half right. It said the fact has to be
total on `graphql_field` rather than on `graphitron_field_synthesis`, because a sparse fact would need
`COALESCE` at every reader and buy none of the plannability. The totality argument is right. The
placement is open, because `intent_field_navigated_type` resolves three rungs and only the first comes
from the synthesis record, so the value is a function of more than `graphql_field` holds. Both forms
were tried on the kept store: as a column on `graphql_field` the `ALTER TABLE` stalls, H2 recompiling
every dependent view, and as a total captured relation keyed on the field coordinate it applies in
0.17 seconds and returns the same 8408 rows. The relation form is also the conservative one to measure
against, costing readers one join the column form would not. Recommendation is the column, with the
relation as the fallback if declaring it at capture proves to need the value before capture can
compute it.

### Slice 3: the remaining missing supertypes

The first draft made this a contingency scoped by plan-reading, on the grounds that the residual would
have to be hunted with an instrument. It does not: the signature above enumerates the candidates off
the DDL, and the enumeration is in the section that states it. What this slice owes is a decision per
candidate, not a search.

**The method-bearing directive site is the one to take here, and it is the largest of the four.** Ten
subtypes, three reconstruction sites, and it is the only confirmed omission no slice above covers.
Slice 1 takes the table-or-routine reference and the argMapping pair; this slice takes this one and
the declared type reference.

It is also the one with a measured argument behind it, which the first draft had no way to make. In
the zero-materialization arm three of the residual relations, `intent_argmapping_projection_defect`,
`intent_node_id_decode_defect` and `intent_resolved_node_key_projection`, all sit above
`intent_argmapping_bound_parameter_type`, which is the six-arm reconstruction of this supertype, and
all three refused a 120-second budget. They are the only residual relations that sit above an
unrepointed reconstruction. That is a prediction this slice can be held to: repoint the three sites,
`intent_argmapping_bound_parameter_type` at six arms and `intent_condition_param_extraction` and
`intent_condition_table_parameter` at five each, and those three relations should move. If they do not,
the expansion is coming from somewhere the signature does not name, and slice 3 needs re-scoping rather
than continuing. Three sites rather than the six an earlier draft claimed makes the prediction sharper
rather than weaker: it is now a claim about one reconstruction reached three ways, which is what a
supertype table would collapse.

The declared type reference is the cheap one on this consumer's schema, so argue it on the model and
not on a timing. `intent_declared_type_ref` already states its own supertype shape in a comment that
reads as a specification for the table it should have been: a discriminator over a closed set, a
uniform key, and arm-determined NULLs the comment is at pains to say are key shape rather than facts
withheld.

**What this slice found that the signature could not.** The detector reads the DDL, so it sees a
reconstruction only where a view body writes one. `BindingUsages.classSites` in `graphitron-lsp` was
the same reconstruction written in Java: nine of the eleven sites unioned by hand, three of the arms
joining back to the owning application only to reach its source position, about a hundred lines of
it. Nothing in the DDL names it and no gate would have. It is now a scan of the supertype with the
class predicate on it, and the move fixed a defect nobody had reported: the hand-written union
omitted the argument-site `@referenceFor` step, so a class named there would not have been found.
That site has no rows today because the validator rejects the coordinate, which is exactly why the
omission could sit there unnoticed.

The lesson is about the instrument rather than about this reader. A supertype is missing wherever a
fact is reconstructed, and a reconstruction can be written in any language the repo uses; a detector
that only reads SQL will under-report, and the number it reports should not be quoted as a total.
The `graphitron_source_row` decision came out of the same gap in the other direction: it is not a
reconstruction site at all, it is a subtype whose rows were the shared fact and nothing more, which
membership in the set says and no union site would have shown.

**What the rule decided, per subtype.** Nine of the ten keep their relations because each carries
data this supertype cannot hold: an `override` flag at the two condition sites, a table or key
reference at the four step sites, a declaration coordinate at the enum, an authored `argMapping`
string at three. The tenth, `graphitron_source_row`, was exactly the shared fact and is gone;
`SOURCE_ROW` is a value of the discriminator.

One finding this slice turned up and did not act on, because it changes the answer above rather than
following from it. The authored `argMapping` string is captured on nine relations and read by
nothing: no view, no Java, no test, the only occurrences being the writer copying it into its own
insert. It is the sole thing keeping `graphitron_service`, `graphitron_external_field` and
`graphitron_enum` from collapsing too. Whether a captured column with no asker is worth keeping is
the same question the register asks about a materialized relation with no reader, and it should be
answered on that footing rather than as a side effect of a collapse.

**The declared type reference, as it landed.** The three census relations carried identical payload
and differed only in the key naming their owner, so the rule collapses them into
`jvm_declared_type_ref` with an `owner_kind` discriminator. Two union sites went, not one: the
retired `intent_declared_type_ref` view, and `intent_jvm_ancestor`'s `asked` seed, which is the
one worth noting because it sat under a recursive CTE and so was re-derived at the most expensive
position in the schema.

The design fork here was the key, and it resolved against the first answer. A collapsed table cannot
take a primary key over the arm-determined parts, a key column not being nullable, and the two ways
out are not both available. Coalescing the NULLs into `GENERATED ALWAYS AS` key columns works and
H2 enforces it, but the census writes with duplicate-ignore, which jOOQ renders as a `MERGE` keyed
on the primary key and so puts the generated columns back into the statement as binds; making that
work would mean depending on how jOOQ renders a merge. The other way is to spell not-applicable as
a value, and that is what shipped: the empty descriptor and the negative position, each bound to
`owner_kind` by a check constraint in both directions, which is more than the three separate
relations could state at all.

That choice is also what settles the constraint question, and it settles it the opposite way from
the argMapping pair. Keeping any foreign key needs a nullable descriptor so the reference is skipped
on the record arm, and a nullable column cannot be in the key: it is the edges or uniqueness, not
both. Uniqueness won, because a census is machine-written from classfiles where the plausible fault
is a duplicate row, and a duplicate silently doubles every join over the largest relation in the
store while a dangling row is visible the moment anything reads it. All three edges are checked in
`FactCaptureAgreementTest` instead, beside the census assertions that already hold a real classpath
scan; a hand-built fixture would not have had the population for a writer bug to show.

Two incidental results. The four-step descent below this relation compared its owner key null-safely
at every step and now compares it with plain equality. And `DerivedReadCostTest`'s pinned view count
fell by exactly one, the union that became a table, with the priced cell count unchanged.

The two unconfirmed candidates are the GraphQL type expression and the described ordinal member. Each
is the same modelling defect with nothing currently paying for it. Record them and do not act, on the
rule this schema applies elsewhere that a relation with no asker is inventory, and revisit each the
first time a view unions its members. One measured caution for whoever does reach for a `graphql_*`
unification: the audit built one a layer up, at `graphql_applied_directive`, and plan sizes came out
byte-identical, because only two view bodies name more than one of those tables. That is evidence
about that layer and not about this one, and section 6 of the audit should be read before it is
rebuilt.

### Slice 4: repoint the three `intent_` views that re-derive semantics from the SDL

Both layers answer 529 types identically and the semantic tables are an order of magnitude smaller.
The two `authored_*_claim` views and the `@notGenerated` read stay as they are, for the reasons the
audit's section 5 gives.

**The worst of the three now has a figure against the target rather than against today.**
`intent_carrier_data_field` costs nothing to read while it is a table, so on the shipping DDL this
slice looks like tidying. With the register emptied it costs 35.3 seconds, which is what the rule
actually costs to evaluate and therefore what this slice is worth. Its body is where the audit's
twelve-name `IN` list over `graphql_field_directive` lives, in three disqualification arms correlated
on the type per driving row, with a `NOT EXISTS` over `intent_errors_field` nested inside the widest
of them.

That nested term is worth separating from the rest of the slice, because it is a different defect
wearing the same symptom: it is a correlated probe into a relation that is a registration today, so
demoting the register turns one probe into a full re-evaluation per row. Repointing the directive
question at the semantic family does not touch it. Whether it wants restating as a join, or wants
`intent_errors_field` to stay a table, is a decision this slice should take explicitly rather than
discover.

**What this slice shipped, and what the measurement said it is worth.** All three views now ask the
decoded family. `intent_errors_field_live` reads `graphitron_connection` for the connection
exclusion. `intent_mutation_routine_seat` reads `graphitron_field_condition`,
`graphitron_argument_condition` and `graphitron_order_by` for the read-surface verdict, which splits
one two-name list across the two relations that hold the two facts. And the carrier scan's
twelve-name list becomes ten probes into the decoded relations plus one SDL probe for
`@notGenerated`, correlated on the field coordinate rather than on a directive application, so the
errors-channel exclusion inside it is asked once per field instead of once per application. No
`intent_` view now asks the syntax-near family a semantic question; the three SDL reads that remain
are the two authored-claim views and `@notGenerated`, which the audit's section 5 already argued are
not defects.

**Row identity holds exactly, on the consumer store rather than on a fixture.** The 2026-08-27
capture was rebuilt on the shipping DDL by the audit's own method, with the five relations the store
predates transcribed in from their pre-collapse siblings. The rebuild reproduces all twenty-two of
the audit's registered row counts position for position, which is what says the transcription is
faithful rather than merely non-empty. Against that store the repoint changes nothing: zero rows
differ on every one of the twenty-two registered targets and on `intent_mutation_routine_seat`,
compared row by row and not by count.

**It is worth nothing in wall clock, and the item should stop implying otherwise.** On the shipping
register the three views cost 5.7 s, 1.6 s and 0.1 s before and 5.7 s, 1.6 s and 0.1 s after. The
35.3 second figure this section attributed to the carrier scan is real and is not this arm: with the
register emptied the carrier costs 26.2 s here, and repointing the directive question moves it to
27.5 s, which is inside the noise of a single reading. The slice is a layering fix. That is worth
having, and the reason is the one the arm itself demonstrates rather than a principle: the
twelve-name list carried `@orderBy`, a directive declared `on ARGUMENT_DEFINITION` and therefore
incapable of ever matching a row of `graphql_field_directive`, and nothing could have said so. A
relation name is checked by the compiler and a string in an `IN` list is checked by nobody.

**The decision this slice owed, taken on the evidence.** `intent_errors_field` stays a table. The
nested term is a correlated probe, and both restatements are worse rather than better: pointing the
three probes at a join-shaped statement of the same rule takes the carrier from 26.2 s to 46.9 s,
and restating them as anti-joins in the carrier itself takes it to 64.3 s. H2 pushes the correlation
into a table and cannot push it into either of those.

**That conclusion was right and the reason given for it was wrong, and the correction is slice 4a's.**
Two restatements being worse does not make the term expensive, and this passage read it as though it
did. Slice 4a removed 5.5 seconds from `intent_errors_field_live` and the carrier moved from 26.2 s
to 25.2 s on the same arm: about one second of the twenty-six was the errors probe. So the probe was
cheap in its correlated form all along, H2 pruning it, and the two failed rewrites were failures of
those rewrites rather than evidence about the term they replaced. The paragraph below inherits the
error and is corrected with it. What follows for the register is the opposite of what this said:
demoting `intent_errors_field` costs its readers close to nothing now, so the registration is a
candidate for retirement rather than the one load-bearing case in the item.

**What the arm turned up that belongs to a different question.** `intent_errors_field_live` costs
5.5 seconds and none of it is the directive read. Its two correlated probes into `intent_poly_member`
are the whole figure: the same population answered by one grouped pass costs 13 ms, the shape
conditions cost 85 ms, and the view stated as a join returns the same 149 rows in 653 ms. The class A
defect is therefore inside `intent_errors_field_live` rather than anywhere this slice touched, and
that part stands. What does not is the sentence this paragraph originally drew from it, that the
26 second carrier figure was three inlined evaluations of the same thing: it was not, and slice 4a
measured the difference at about one second. Two relations were expensive for two unrelated reasons
and sharing a probe made them look like one reason, which is the inference to distrust the next time
a fix upstream is predicted from a figure downstream. Not folded in here, because a correlated-probe
rewrite is not a repoint.

**A test that pins the outcome and not the rule, said out loud.** Eleven cases were added across the
three repointed relations, and mutating the schema kills two of the three arms they cover: dropping
the `@service` probe fails the carrier case, dropping the `@orderBy` probe fails the seat case. The
connection exclusion survives its mutation, and the reason is worth recording rather than papering
over: the macro rewrites an `@asConnection` field before capture writes it, so what reaches
`graphql_field` is a single-valued Connection and the shape conditions exclude it twice over before
the connection term is consulted. Whether any field can carry `@asConnection` and still read as a
nullable list of a polymorphic type is open. The term was repointed rather than removed, a guard on
an unsettled hypothesis being worth more than the line it costs.

### Slice 4a: the polymorphic membership capture writes, and the window it removes

Absorbed into this item rather than filed separately, on the grounds that it is the same defect one
family lower and splitting it would have let the shape spread while a second item waited for review.

**What the digging found.** `intent_errors_field_live` cost 5.5 seconds to answer a question about
820 rows, and none of it was the directive read slice 4 repointed. The whole figure was
`intent_poly_member`, a two-arm union view whose interface arm ranked its `position` with
`ROW_NUMBER() OVER (PARTITION BY graph_name, interface_name ...)`. A window sees its whole partition
whatever the outer correlation says, so every correlated probe re-ranked all 552 rows of the
implements relation; only 150 driving rows ever reached that probe, which is about 35 milliseconds
per row to look up membership in a relation smaller than most fixtures. The decomposition, on the
consumer store with the register emptied: through the view as it shipped, 5319 ms; the same union
with the window replaced by a constant, 549 ms; the union arm alone off its base table, 86 ms; the
interface arm alone, 535 ms; as a plain indexed table, 28 ms.

**The fix is rung 1 and the ordinal is the whole of it.** The window ordered by four columns capture
already wrote, so a sort at capture time reproduces it exactly: zero interface rows differ, and the
ordering key has no ties, so the sort is total. Nothing was being computed at read time that capture
could not have written once. Why capture declined is visible in the two writers, which sat ten lines
apart and were otherwise near-identical: a union declares its members in one place, so a running
counter in the loop works, while an interface's implementors are declared apart from it and apart
from each other, so the first of them is not known until the last site is read. The order is settled
now in one pass after the walk, and the two relations are one captured `graphql_poly_member` with a
`container_kind` discriminator, on the same rule this item applied to the argMapping pair: neither
carried data the other's shape could not hold, and which end of the edge the document spells is
provenance rather than data.

**Where it is captured, which was a fork worth stating.** The created-schema stage is the easiest
place to ask graphql-java what implements an interface, and it is the wrong place to write this. Two
reasons, both checkable. `graphql_` is the complete-transcription family, and the created-schema
stage is conditional on assembly: its established convention is `if (schema == null) return;`, which
is right for an `intent_` derivation and wrong for a transcription, since an editor asking who
implements this interface needs the answer most while the schema is broken. And the registry walk is
already complete for literal membership, walking every site of every type including extensions, so
the assembled schema adds no row it misses and would substitute graphql-java's registration order,
which this schema's own comment already calls neither source order nor documented.

**What it is worth**, all row-identical, compared row by row and not by count, on the 2026-08-27
capture rebuilt on this DDL:

[cols="4,2,2"]
|===
| relation | before | after

| `intent_poly_member` | reconstructed | 18 ms, 820 rows
| `intent_errors_field_live` | 5524 ms | 193 ms
| `intent_errors_field_member` | 4207 ms | 6 ms
| `intent_inferred_node_type` | 462 ms | 382 ms
|===

`intent_field_participant_scope_table` is the honest caveat: it is the third reader, it costs 75
seconds, and this buys it about 7%. Its cost is its own and belongs to a different question.

**A gate row deleted rather than added.** `DerivedReadCostTest` holds `KNOWN_NON_MONOTONIC` with
equality both ways, so a pair that stops being a regression is a failure until somebody removes it.
`intent_errors_field|intent_errors_field_member` stopped, and the reason is a third shape worth
naming beside the two that comment already carries: a pair can leave this set because something
neither of its relations mentions stopped being a reconstruction.

**One defect surfaced and deliberately not fixed.** The two arms do not share a base: a union's
members are numbered from zero, an interface's implementors from one, in one `position` column. The
window hid it. Nothing reads the absolute value, every consumer reading only the order, which is why
it survived unnoticed; but the mapping-constant fingerprint digests a handler list in this order, so
rebasing is a change to emitted names rather than a tidy-up. Preserved exactly, stated on the column
and in capture, and owed as its own decision.

### Slice 4b: the first registration retired, and the census that found it

The register held twenty-two, not the twenty its own reasons are priced against. Two questions were
put to all of them: which are reachable from what a consumer names, and which rules have become
cheap enough that the registration buys nothing.

**Reachable, walked from the relations main sources name and following view bodies: seven of
twenty-two.** The audit's walk said ten of twenty-two reachable and this one says seven, and the
disagreement is not settled here; the number is offered as a second reading rather than a
correction, and nothing in this slice is decided on it. What the item already says about the
unreachable ones still holds: they are work under construction, and their registrations may be right
the moment their readers land.

**One rule is a projection of a single relation with no window, union or probe.**
`intent_argmapping_pair_live` is `SELECT` fourteen columns `FROM graphitron_arg_mapping_pair`, which
is what slice 1 left behind when capture started writing the widened shape. Materializing it copied
rows into rows: a reader naming it fifty-five times read one table fifty-five times whichever shape
it had, and the reason still in the register was counting those namings against an eight-arm union
that no longer existed.

**The index made it plainer than the timings did.** The registration carried
`ix_argmapping_pair_use_site` on the graph, the site, the use site and the position. That is
`graphitron_arg_mapping_pair`'s primary key, the same four columns in the same order, so the table
underneath was better keyed than the copy of it. A registration that buys neither an evaluation nor
an index is a refresh paid every capture for nothing. This is the fact-model page's newly stated
index rung read from the other side: that page now warns that a registration may be delivering an
index nobody priced, and here it was delivering one that already existed a relation down.

**Retired, measured with the rest of the register intact.** Five relations, identical row counts
either way:

[cols="4,2,2"]
|===
| reader | registered | retired

| `intent_argmapping_bound_parameter_type` | 92 ms | 87 ms
| `intent_argmapping_segment_binding` | 95 ms | 79 ms
| `intent_argmapping_projection_defect` | 7905 ms | 8255 ms
| `intent_node_id_decode_slot` | 295 ms | 213 ms
| `intent_argmapping_pair` | 0 ms | 0 ms
|===

Three readers fall, one rises four per cent on an eight-second query, and the refresh is gone. The
relation keeps its name and its column comments as a view, so no reader moved. Whether an `intent_`
relation whose rule is a projection of one `graphitron_` table belongs in that family at all is a
separate question this slice does not take: the charter says rows there are computed and never
captured, and these are captured.

**Three gates made the retirement say its own name**, which is the property worth recording about
them. `MaterializeRegistryGateTest` pins the count. `DerivedReadCostTest` pins the domain and the
cells, and both moved: nine cells go because a registration takes its whole column out of the
matrix, the readers-with-cells figure falls by two, and the schema's view count does not move at all
because a retirement deletes a `_live` view and a table and puts the rule back under the canonical
name. And its `KNOWN_NON_MONOTONIC` set lost a second row today, this time because the registration
it named stopped existing.

**What the census says about reducing further: not wholesale.** On the arm with the register
emptied, six of the first twelve targets do not answer inside a hundred and twenty seconds,
`intent_argument_scope_table` taking 101 s and `intent_input_field_resolving_table` 117 s. The
register is doing real work and the reduction available is per relation.

**The next candidate, named but not taken.** `intent_errors_field` costs 10 ms as a view now where
it cost 5.5 seconds before slice 4a, so its registration is priced on a shape that no longer exists.
It was not retired on that figure, because the honest test is to demote that one relation with the
rest of the register intact; the paragraph below reports that test, which refused it.

**The second candidate was measured and refused, and the way it failed is the finding.**
`intent_errors_field` stays registered. Slice 4a took its refresh from the audit's 5.6 seconds to
0.21 seconds, so the registration is now nearly free to hold; the question was whether it still buys
anything. Demoting exactly that one relation, with the other twenty kept, moves its one registered
reader's refresh from 2.12 s to 2.24 s. So retiring it would save 0.21 s and cost 0.12 s, which is a
wash rather than a win, and nothing about the relation argues for the churn.

The pass totals for the two arms are 47.28 s and 48.81 s, and that difference should not be read as
the cost of the demotion. Most of it is `intent_input_field_filter_role` at 20.54 s against 22.68 s,
and that relation does not name `intent_errors_field` anywhere; `intent_node_id_instruction` moved
the other way by about the same fraction. A few per cent either way across a forty-seven second pass
is what one reading of this harness is worth, which is worth knowing before quoting any single
position from it.

**What this refutes is a method, not a relation, and it had already misled this item once today.**
The arm with the register emptied says `intent_errors_field` costs 10 ms as a view, and that is true
and useless: on that arm every relation is a view, the carrier costs 25 seconds, and a 10 ms probe
inside it is unmeasurable. With the register intact the carrier's refresh is 2.1 seconds and the
same probe is a visible fraction of it. **The emptied arm prices the register as a set and cannot
price a member of it.** Pricing one registration means demoting exactly one, which is the only
reading that answers the question the register's own reasons ask.

That is the same inference that produced the correction recorded against slice 4 above, arriving a
second time from the other direction: there a cheap-looking fix upstream was predicted to move an
expensive figure downstream, here an expensive-looking figure downstream was read as licence to drop
something upstream. Both are the set-relative pricing rule being ignored, and the rule is in the
register's own charter rather than being new here.
**Retired after all, and the arithmetic that first said otherwise was wrong.** The two figures add
rather than offset: holding the registration costs its own 0.21 s refresh *and* leaves its reader's
refresh at 2.12 s, for 2.33 s; demoting it costs 2.24 s and nothing else. Demoting is cheaper. The
earlier reading here subtracted one from the other and called the result a wash, which it is not.

The margin is inside this harness's noise either way, so the clock does not decide it. What decides
it is the second cost of holding a registration, which no timing of the registered arm can show and
which this relation is the item's own worked case of: a materialized target is a table with
statistics of its own, so the planner bottoms out there and the rule underneath is invisible to it
and to anyone reading a plan. This relation's real cost was a window function in `intent_poly_member`
and it sat unseen behind this registration until the register was emptied to look for it. The
registration was not neutral while it broke even. It was a blindfold.

The target carried no index at all, so unlike the argmapping retirement there was not even an index
to weigh. `MaterializeRegistryGateTest`'s no-index roster loses the row that argued the absence, and
the register stands at twenty.

**What the two retirements together say about the pinned figures.** The read-cost domain fell by nine
cells for the first and three for the second. A registration's weight there is how many relations
reach it, not what its refresh costs and not what its rule looks like, which is worth knowing before
reading either figure as a size.

### Slice 5: amend the lever order on the fact-model page

The ordering above contradicts the one in `docs/architecture/explanation/fact-model.adoc`, and until
the page is amended the tree carries two of them with the page the one a contributor meets first.
Four edits, and no more than four. Move the registration rung below the rewrite rung, with the cost
argument for the move rather than an assertion. Add the index rung the page lacks, together with the
finding that a registration has been delivering an index nobody priced separately, which is what made
the register read as more load-bearing than it is. Attach the reader precondition to the registration
rung. And restate the top rung, which is the edit that matters most and the one the first draft did
not know it needed: the page argues a captured fact from cost, that it has no refresh to pay for, and
the argument is a modelling one that happens to have that consequence. A subtype set with no supertype
is a defect in the model whether or not any reader is currently slow, and a page that argues the top
rung from cost cannot tell an author to write the supertype for the declared type reference, which
nothing is waiting on today.

Two things this slice deliberately does not touch. The page's paragraph on which relation to register
once you have reached for that lever, the deepest-common-reader rule with its measured endpoint case,
is unaffected by the reordering and stands. And the materialized-view prohibition above it is a
separate ruling on a separate subject.

**Whether the precondition sentence ships with the rest of this slice is the one open question in
it.** The precondition is the policy question the last section defers. If the page states it while no
gate holds it, the page is ahead of the tree; if the page ships without it, the reordering lands
without the thing that gives it teeth. The recommendation is to ship it as a rule with the reachable
count beside it, which is what the page does elsewhere for rules it states before they are enforced.

**Landed, and the four edits are the four the section named.** The lever order paragraph is now four
paragraphs, one per rung, in the order this item argues rather than the one the page carried.

The top rung is restated as a modelling argument. It used to read "because it has no refresh to pay
for at all", which is true and is not the reason: a rule that reconstructs what capture could have
written is a defect in the model whether or not any reader is currently slow, and argued from cost
the rung cannot tell an author to write the supertype for a relation nothing is waiting on. The
cheapness is stated as the consequence it is.

The index rung is added, with the finding that motivated adding it: a registered target is a table,
a table is what an index can sit on, so a registration has been delivering an index silently and a
figure this page attributes to materialization may be an index nobody priced separately. The page's
own reference-step hop measurement, half index and half statistics, is the worked case and is left
where it was rather than repeated.

The registration rung moves below the rewrite rung, on the argument that it is the only one of the
four that adds work rather than removing it, and it picks up the reader precondition: it is the only
rung bought on behalf of readers rather than of the model, so a target no consumer reaches is a
refresh paid every capture for nobody.

Neither of the two paragraphs this slice was told to leave alone was touched: the deepest-common-
reader rule and the materialized-view prohibition stand as written.

One edit outside the four, in another item rather than on the page. `roadmap/capture-stops-reading-
the-walk.md` carries a coordination note warning whoever lands second to read the other's edit,
whose reason was that the top rung "argues from cost". That is now the opposite of what the page
says, and a stale warning about a shared file is worse than none, so the note is repointed at the
landed state.

### Slice 6: the census of performance claims written into the DDL

The slice asked which comments make a re-checkable claim at all, before deciding whether any should
become rows a gate can read. The file carries 2564 `COMMENT ON` statements. They divide into four
classes and only one of them has gone stale.

**Thirteen comments carry a numeric measurement, twenty-four claims between them, and twelve of the
thirteen name the fixture they were taken on.** Five name the read-cost gate's twelve-unit fixture
and seven name a store captured from the sakila example schema. Both are reproducible from this
repository, so every one of those claims can be re-taken by any session with no consumer capture and
no kept store. That is the property that matters and it is the reason none of them needs a gate: a
claim that names its own instrument is already checkable, and the read-cost gate runs the same
fixture on every build.

**One measurement does not name its instrument.** `intent_facet_binding`'s comment says its reader
went from 659 scans to 7779 "on a fixture of 144 applications" without saying which fixture, and it
is the only measurement in the file that cannot be re-taken from its own text. It is also the file's
only argument for a deliberate non-registration, so it is the one worth repairing first if any of
this class is repaired.

**Twenty comments carry a `Materialized:` state claim, and that class is exactly in sync.** The
roster of relations whose comment says it is a table refilled on the capture cadence is precisely the
twenty rows of `meta_materialize`, which is what it should be, and the two retirements this item made
took their claims with them. Nothing to do.

**One comment states the wrong kind for its relation.** `COMMENT ON VIEW intent_field_scope_table`
names a table. H2 resolves the object by name whichever keyword is used, and the rendered reference
pages take a relation's kind from the booted catalog rather than from the comment's keyword, so
nothing observable is wrong; the file simply says something false about itself. Corrected here.

**The stale class is not in the comments at all. It is in the register's own reasons.** Eighteen of
the twenty rows of `meta_materialize` open with "Priced against the register of twenty". The two that
do not are the payload registrations that landed 2026-08-28. The count is right again and the set is
not: the twenty those eighteen reasons were priced against contained the two registrations this item
has since retired and did not contain the two that landed. So the number reads as current while
naming a set that no longer exists, which is worse than reading as out of date.

**They cannot be repaired one at a time, by this item's own rule.** A register-relative measurement
prices the register as a set and says nothing about a member of it in a different set. Re-taking
eighteen arms is the only thing that would make eighteen such sentences true, and this item's target
is a register with no members, so those arms would price a thing being deleted. What the reasons owe
is therefore not a re-measurement but a correction of what they claim: an arm identified by the date
it was taken rather than by a count that silently changes meaning. That edit is eighteen authored
sentences in the DDL and is left as a decision rather than taken here, because whether it is worth
making depends on how many of the eighteen survive.

### Slice 7: the single-family census, and what `intent_` is actually for

**The criterion is about membership and ownership, not about form.** A view that reads relations from
only one family is a view *of that family*. It is not an `intent_` view, and the fix is not to
flatten it into something capture writes row by row. Capture sees its corpus as a stream and writes
what it has in hand; a view sees the corpus whole. Those are different powers and the second one is
worth keeping. What is wrong with these rules is where they are filed, and the first draft of this
slice got that wrong: it said they should become captured tables, which throws away the capability
the view form has and answers a question nobody asked.

**Filed in a family means owned by that family's gatherer, and that is where the leverage is.** A
gatherer knows when its own family is complete, so it knows the refresh, and a relation whose refresh
is known can be materialized, denormalized, indexed or left a view entirely at its owner's
discretion. None of that needs a row in `meta_materialize`, which exists to schedule refreshes for
rules that have no owner to schedule them. So a rule that moves into a family does not get converted
into anything. It leaves this item's question.

**Which gives `intent_` a definition it has not had.** Not "derived", which describes how the rows
arose and separates nothing, but *a rule whose facts no single gatherer owns*, whose refresh depends
on more than one family being complete. That is a property a census can decide, and the census below
is the decision. The target for the register follows from it and is sharper than "empty": a register
holding only the rules that genuinely cross.

**`graphql_` and `graphitron_` are one family because they are one gatherer.** Treating them together
is not a convenience. `GraphitronFactCapture` is a field of `SdlFactCapture`, constructed by it and
writing through the same sink, so the SDL walk and the directive decode are one traversal of one
corpus with one owner. `store_graph_source` is the spine every relation joins for its graph and
counts as neutral. This is the fact-model page's top rung restated as something a census can apply:
the page argues that reconstructing what capture could have written is a defect whether or not a
reader is slow, and this says how to recognise one.

**Three things in the tree say the shape already exists and is only unevenly applied.**

`graphql_directive_site` is a `graphql_` view already, and it is this item's own defect class solved
the way this slice describes: a supertype UNIONing the five directive-site tables over schema, type,
field, argument and enum-value sites, filed in the family rather than in `intent_`. One precedent,
and the right one.

`ClassificationDomainCapture` is a gatherer-owned derivation already. Its javadoc calls it the SDL
gatherer's rooted traversal, last stage, and it writes `intent_type_domain` inside the capture
transaction. So a gatherer deriving a fact and owning its refresh is not a new mechanism; it is an
existing one whose output is filed under `intent_`, which is exactly the misfiling this criterion
names.

Transaction control is the part that genuinely is not there. `FactCapture` opens one transaction and
runs every gatherer inside it, configuration then SDL then catalog then the classification
derivation, so no gatherer can commit its own family and refresh its own relations against current
statistics. H2 commits the current transaction as a side effect of `ANALYZE`, which is why. The tree
already carves out one exception for exactly this: a store holding no graph commits its facts and
refreshes outside the transaction so the refresh can be planned against statistics it has. What this
criterion asks for is that exception promoted to the normal shape, one boundary per gatherer, and
that is a real change with a real precedent rather than a new idea.

**How the census reads a rule.** Through `ViewReferences`, the same jOOQ-parser walk the expansion
count uses, taking each rule's references and expanding through every `intent_` relation it names
until only non-`intent_` relations are left. A materialized target is read as its rule, so a
registration does not hide a family crossing underneath it. What comes back is the set of captured
facts the rule ultimately stands on, and the criterion is a question about that set.

**Of the 107 `intent_` rules with a body, twenty-five stand on one family.** Seventeen on the authored
family, four on `sql_` alone and four on `jvm_` alone. The remaining eighty-two cross at least two.
The full signature census:

[cols="2,5"]
|===
| rules | the families their captured facts sit in

| 40 | authored, `sql_`, `jvm_` and a hand-written `intent_` table
| 21 | authored, `sql_` and `jvm_`
| 17 | authored only
| 8 | authored and `sql_`
| 6 | authored and a hand-written `intent_` table
| 4 | `jvm_` only
| 4 | `sql_` only
| 4 | authored and `jvm_`
| 1 | `jvm_` and `sql_`
| 1 | authored, `sql_` and a hand-written `intent_` table
| 1 | nothing at all
|===

**Three of the seventeen are the criterion half applied, which is what makes it more than a
proposal.** `intent_poly_member` and `intent_argmapping_pair` became projections of
`graphql_poly_member` and `graphitron_arg_mapping_pair` in this item's slices 4a and 4b, and
`intent_field_navigated_type` is a projection of `graphitron_field_navigation` from before it. The
census finds all three without being told, so it is recognising a shape rather than being fitted to
one. Half applied because each still carries an `intent_` name over a single family's fact: the
membership question was answered and the filing question was not, which is precisely the distinction
this slice's first draft missed.

**The remaining fourteen, in the order their readers make them worth taking:**

[cols="4,1,1,5"]
|===
| rule | readers | size | the authored facts it stands on

| `intent_errors_field` | 4 | 9 | connection, error, field, poly member, type
| `intent_authored_field_claim` | 4 | 23 | eleven, across lookup keys, external fields, node id, mutation, routine, service
| `intent_field_payload_producer` | 3 | 10 | mutation, routine, service, field, root operation
| `intent_field_demand_rule` | 2 | 14 | eight, across error, external field, mutation, service, table
| `intent_field_producer_reference` | 2 | 3 | external field, service
| `intent_authored_claim_conflict` | 2 | 33 | fifteen, the widest in the set
| `intent_field_exemption_rule` | 2 | 26 | nine, the exemption side of the demand rule
| `intent_type_demand` | 1 | 49 | nine, the type-grain twin of the field rule
| `intent_type_exemption` | 1 | 3 | type
| `intent_connection_element_type` | 1 | 5 | field, type
| `intent_facet_binding` | 1 | 9 | facet, binding, condition, node id, reference, field, type
| `intent_authored_type_claim` | 1 | 7 | error, table, type directive
| `intent_errors_field_member` | 0 | 12 | connection, error, field, poly member, type
| `intent_connection_facet` | 0 | 12 | facet and the field-shape family
|===

The four standing on `sql_` alone are `intent_foreign_key_column_pair`, `intent_name_matched_key_pair`,
`intent_node_metadata_defect` and `intent_table_key_candidate`, all of them questions about the
catalog that the catalog census could answer as it walks. The four on `jvm_` alone are
`intent_class_member_element`, `intent_class_member_slot`, `intent_declared_type_element` and
`intent_jvm_ancestor`, the same argument one family over.

**Six more stand on the authored family plus a hand-written `intent_` table, and those tables are the
second finding.** `intent_input_occurrence_path`, `intent_input_occurrence_path_step` and
`intent_type_domain` are captured tables filled by a hand-written derivation rather than by the
materializer, so a rule standing on one of them plus authored facts has not actually crossed a
family: it has crossed a filing mistake. Those three are captured facts sitting in the derived
family, and under this criterion they belong in the family whose facts they were derived from. That
raises the count from twenty-five to thirty-one, and it raises a question this item should not answer
alone, because moving a captured table between families renames it at every reader.

**What the criterion does not do, said plainly.** It does not fix the cost. The eight cut points the
expansion count ranks are all multi-family except one: `intent_field_scope_table`, the three mutation
payload rules, `intent_node_id_instruction`, `intent_node_id_decode_column`,
`intent_field_reference_step_hop` and `intent_resolved_type_binding` each stand on authored facts and
the catalog and the classpath together, so none of them is a captured fact waiting to be written. The
exception is `intent_node_type`, the eighth cut, which stands on the authored family and `sql_` and
nothing else. So the criterion and the expansion ranking are two different questions that meet in one
place, and taking the twenty-five would leave the plannability cliff exactly where it is.

**The tier where they do meet is the near miss: authored plus exactly one catalog family.** Twelve
rules sit there. `intent_spelled_table` is one of them and is a registration, four units wide and
named by ten rules. `intent_node_type` is another, named by seven, and is the eighth cut.
`intent_bound_table` is a third, named by seven. The others are `intent_federation_key`,
`intent_field_accessor_hop`, `intent_field_chain_start`, `intent_field_producer_method`,
`intent_field_routine_method`, `intent_inferred_node_type`, `intent_producer_cardinality_conflict`,
`intent_synthesized_federation_key` and `intent_type_backing_seed`.

**And that tier is what the criterion is for once it is read as a rule about where to cut.** A rule
that crosses is not thereby excused: it is a rule with a single-family part inside it that has not
been separated out. The mutation write destination is the worked case. Its intended destination is
authored, knowable while the SDL gatherer traverses, and the only thing it lacks is the jOOQ table,
which is one join. Under this criterion the answer is not to capture the whole relation. It is to
push the authored part down into the authored family, where its own gatherer owns it and may
materialize it if it wants, and leave `intent_` holding the join and nothing else. Applied that way
the criterion does not move twenty-five rules out of `intent_` and stop. It shrinks most of the
remaining eighty-two to whatever part of them genuinely crosses, which is a much smaller thing than
each of them is now, and none of it requires deleting a rule anybody reads.

**So the order of work this slice argues for is the opposite of its own reader ranking.** The
twenty-five are the cheap, safe demonstration that filing and ownership can move at all, and they buy
no speed. The twelve are where the two questions meet, and `intent_spelled_table` is the smallest
thing in that tier that is also a registration, which makes it the first case where moving a rule
into a family should retire a row from the register rather than merely rename a relation.

**One rule reads nothing.** `intent_delivery_container` names no relation at all, so it is a stated
set rather than a derivation and the criterion does not apply to it.

### Slice 8: the rule moves to the fact-model page, the figures stay here

An item is deleted when it reaches Done, so anything in this file that a later contributor needs is
in the wrong place. The ownership rule is that kind of thing, so it now has a section of its own on
`docs/architecture/explanation/fact-model.adoc`, beside the strata it is a second axis to: what
ownership is, why filing a rule in a family gives its gatherer refresh authority and takes it out of
`meta_materialize`'s remit, what `intent_` therefore means, where to cut a rule that crosses, the two
places the shape already exists in the store, and the per-gatherer transaction control that is its
one real prerequisite.

**The split between the two documents is deliberate and follows that page's own discipline.** The
page carries the rule and the check that would close it, and states that the check is not a gate yet.
It deliberately carries no counts: an unguarded census rots silently, which that page warns about two
sections above where this one now sits. The figures live here, in the slice above, where they are
dated and where their instrument is named, and they are expected to go stale as the rules move.

### Slice 9: the supertype-signature gate, and the tranche it found

`SupertypeSignatureGateTest` in `graphitron-model` now holds the signature this item has been
describing, read off a booted store the way the register gate's axes are. Three assertions: the set
roster, the reconstruction roster, and the claim that no set of three or more members is
reconstructed anywhere. The first two are pinned by equality in both directions.

**The exemption list the plan asked for turned out to be a rule.** Grouping capture tables by the
columns outside their own primary key reports two large groups that are not subtype sets at all: the
thirteen relations whose entire shared payload is where the row was read from, and the eight
coordinate relations with no payload whatever. Both fall out on one line rather than by name: a set's
payload must be non-empty and must hold at least one column that is not provenance. Sharing only your
provenance is sharing nothing. That leaves eleven sets, every one of them a real group of relations
saying the same thing under different keys, and no relation had to be excused by hand.

**The threshold moved from three members to two, and that is where the finding is.** Three was the
right number for a scan with provenance still in the payload, because at two the noise swamps
everything. With provenance ruled out, two is honest, and at two this schema has **ten reconstruction
sites** that the plan's own threshold could not see. Every one of them has the same shape: an
argument-site relation unioned with its field-site twin, because an author may write the same
directive at either coordinate and no relation says so once.

[cols="4,4"]
|===
| the view that reconstructs | the twins it unions

| `intent_argument_filter_role` | argument and field `@condition`
| `intent_input_occurrence_override` | argument and field `@condition`
| `intent_condition_method_route` | argument and field reference step
| `intent_condition_method_route_defect` | argument and field reference step
| `intent_condition_param_decode` | argument and field `@condition`
| `intent_field_demand_rule` | external field and service
| `intent_field_exemption_rule` | external field and service
| `intent_field_producer_reference` | external field and service
| `intent_node_id_instruction_live` | argument and field `@nodeId`
| `intent_resolved_node_key_column` | constraint column and node key column
|===

**At three or more the answer is zero, and that is this item's four repoints stated as a build
claim.** Every wide reconstruction the signature can see is gone, which is what slices 1 and 3 were
for, and the assertion is empty rather than a roster so that one cannot come back quietly.

**Both halves were mutation-tested rather than trusted.** A view unioning three reference-step
relations and naming `table_ref` fails the wide-reconstruction assertion and the reconstruction
roster; a table added with the node-id payload under a different key fails the set roster. Each fired
on the assertion it should and on no other.

**What the gate does not claim.** Confirming a site is name matching over a stored definition, not
proof that the set's column is projected from the unioned arms, which is why the roster is pinned
rather than merely counted: the pin is what makes a wrong reading somebody's to correct rather than
the gate's to hide. Membership comes from `ViewReferences` rather than from text, so an alias sharing
a relation's name is not counted as a read of it.

**The ten are not this slice's to fix.** They are the same defect one grain down, and each is a
decision about whether the argument coordinate and the field coordinate are two sites of one fact,
which is a modelling question per pair and not a sweep. What the gate changes is that they are now
counted, and an eleventh cannot join them without somebody editing a roster.

### Slice 10: a correction to the ownership rule this item published

Slice 8 said `intent_` is the family of rules whose facts no single gatherer owns. That is wrong, and
the modelling done for the house-cleaning item found it. **Every relation has an owner.** A rule
reading one family is owned by that family's gatherer, and a rule whose facts cross families is owned
by a derivation gatherer that runs after every corpus gatherer has finished, because that is the
earliest moment its inputs are all complete. The single-family criterion is unchanged; what changes is
what `intent_` is, from an absence of ownership to an owner that runs last.

**The correction matters because of what it does to `meta_materialize`.** If the crossing rules had no
owner, then a register scheduling their refreshes has to be a mechanism of its own, standing outside
the ownership rule and answerable to nobody, which is exactly how it reads today and exactly the
problem this item opened with. Under an owner that runs last it is not a mechanism at all. It is that
gatherer's refresh plan, the same kind of thing any other gatherer would hold for its own family, and
the question stops being whether a registration should exist and becomes whether it is the right
thing for the last gatherer to materialize.

**Which makes this item's own headline finding read differently.** The twenty relations with no
declared primary key are exactly the twenty registered targets. If materialization is a gatherer
building the tables its views stand on, then what it builds should be grain tables, meaning keyed on
the thing a row is about. Twenty keyless copies of view bodies is not that. So the register is not
only too large, it is materializing the wrong shape, and the fix is not per registration.

Corrected in both durable places, the fact model page's ownership section and the `intent_` family's
charter row.

### Slice 11: the first registered target gets a grain, and an assumption is refused

The amendment above says a registration is what a relation with no grain gets given. This slice tests
that on the smallest of the twenty, and the result is smaller than the claim needed it to be, which
is worth saying first: `intent_spelled_table` did not need remodelling. It needed a key nobody had
written.

**Every column of its natural key was already NOT NULL at source.** The rule resolves an author's
table spelling against the catalog: `graphitron_spelled_reference` is keyed on the graph and the
spelling and both are NOT NULL there, and the three columns naming the matched table come from
`sql_table`'s own primary key through an inner join. So the grain, one author spelling resolved to
one catalog table, was expressible as a key the whole time. It is one now, and **it holds against
every fixture in the pipeline tier**, 4091 tests with nothing else changed.

**Then an assumption was made and the gates refused it, which is the more useful half of this
slice.** The relation's declared index, `ix_spelled_table_spelling`, is on the graph and the spelling,
a strict prefix of the new key, and every one of the nine namings across seven view bodies probes on
exactly those two columns. So it reads as redundant and it was removed, together with a change to
`MaterializeRegistryGateTest` so a target stating its grain as a key would count as indexed.

Two gates fired on that. `DerivedReadCostTest` lost a pinned regression pair, which is an improvement
and only fails because that set is pinned both ways. `RefreshPlanStatisticsTest` was the real
finding: three refresh statements, `intent_carrier_data_field_live`,
`intent_node_id_decode_column_live` and `intent_resolved_type_binding_live`, moved into needing the
registered targets' statistics in order to plan, which is precisely the cold-capture exposure that
test exists to bound.

**Attributed by separating the two changes rather than by reasoning about them.** With the key added
and the index kept, both gates pass unchanged and nothing moves. So the key alone is free, and every
effect above came from removing the index. A narrow non-unique index is not the same offer to the
planner as the leading columns of a wide unique one, whatever the column lists have in common.

**So the index stays and the gate change goes back.** Making the gate treat a key as an index was
justified by the claim that the key's own index serves any prefix probe, and that claim had just been
measured false on this relation. Encoding it into a gate immediately afterwards would have shipped a
refuted premise as a rule. The relation's comment now records the measurement instead, so the next
author who notices the apparent redundancy finds out why it is still there.

**What this slice does and does not show.** It shows that at least part of the twenty is not a
modelling problem at all: a relation whose grain was expressible and merely unstated, keyed at the
cost of one line. It says nothing yet about the ones whose grain is genuinely conditional, which is
where the claim that there is no such thing as an unkeyable grain will actually be tested. And it
adds one caution for the rest of them: a key is a statement about the model and not automatically an
improvement to a plan, so keying a target is not a licence to drop what was indexed beside it.

### Slice 12: the twenty, measured rather than argued

Slice 11 keyed the smallest of the twenty and found it free. That says nothing about the other
nineteen, so this slice asks all twenty the same two questions against the kept consumer capture with
every target refreshed: does any column hold a null, and are the rows distinct at all. Both are
one statement per relation and neither needs an opinion.

**Sixteen of the twenty hold no null in any column.** Every column count equals the row count, over
data from a real consumer schema. Whatever made these relations look like they had a conditional
grain, it was not conditional in anything they actually produced.

**Eighteen of the twenty have wholly distinct rows.** So for eighteen, some key exists, at worst the
full column list, and the work is finding the smallest one rather than inventing a shape.

**Two of the twenty contain duplicate rows, and that is the finding.**
`intent_node_id_decode_column` holds 1559 rows of which 1114 are distinct, and
`intent_node_id_decode_hop_column` holds 1078 of which 1009 are. Not near-duplicates: exact repeats.
One row of the hop column, an input field's hop at position zero mapping a column to itself, appears
nine times, and 41 groups repeat at all. Neither rule carries a `DISTINCT` and both are unchanged
since the arm was built, so this is current rather than archaeology.

A relation with duplicate rows has no key by construction, so these two are the only members of the
twenty that genuinely cannot state a grain today. They are also the two where it costs most: a
duplicate in a materialized target multiplies every join above it, so a reader paying nine times for
one fact is paying for a fan-out in a rule rather than for anything it asked.

**Where the nulls are, they are the site discriminator.** Only four relations hold any, and in three
of them the nulls sit in `argument_name` and `path`: null for a field-site row, populated for an
argument-site one. That is the same one-fact-at-two-coordinates shape the supertype gate counts ten
of, arriving here as an unkeyable column instead of as a union. Each of those three already carries a
`site` discriminator, so the ingredients for a key are present and only the not-applicable spelling is
missing, which is the shape slice 3 already shipped once.

**What this measurement is worth, stated precisely because uniqueness measured once is not
uniqueness.** One consumer schema, one capture. A key that holds on this data is a candidate and not
a proven constraint, and the honest form is what slice 11 did: derive the key from the rule's sources
and let the pipeline tier's fixtures try to break it. The negative result carries further than the
positive one. Duplicate rows here are proof that no key exists, on this data, today, and no further
schema can rescue that.

So the amended thesis has its first real test and comes out ahead of where it stood: of twenty
relations that looked like they had no grain, eighteen have one available and two have a defect that
is not about grain at all but about a rule that fans out and never says `DISTINCT`.

### Slice 13: what the duplicate rows actually are

Slice 12 found duplicate rows in two targets and called the cause a rule that fans out and never says
`DISTINCT`. That was wrong, and the real cause makes `DISTINCT` the wrong fix rather than the missing
one.

**The duplicates are information loss, not repetition.** `intent_node_id_decode_hop_column` joins
`intent_node_id_decode_hop` to the foreign key column pairs and projects the column names while
dropping the constraint and the tables it came from. At the worked coordinate, a filter input over
person roles, the hop relation legitimately holds nine rows: nine foreign keys from nine different
role tables, all pointing at the same role table. Every one of them maps the same two column names.
So nine distinct facts arrive at the projection and nine identical rows leave it. Adding `DISTINCT`
would collapse nine real hops into one and hand any reader that wanted to know which table it was
hopping from a single arbitrary answer.

**The inflation is larger than the row counts suggested, because it multiplies per coordinate.** At
that coordinate `intent_node_id_decode_column` holds 162 rows of which 2 are distinct. Six other
coordinates behave the same way, 64 against 4, 40 against 5, 28 against 7.

**And it reaches a stated value that is simply false.** `intent_node_id_decode.arity` is
`COUNT(*) OVER (PARTITION BY graph_name, use_site)` over that relation, so the node identity at that
coordinate is described as having 162 key columns where it has 2.

**Two things bound how bad this is, and both are worth stating precisely rather than leaving to
relief.** No main source reads that column: `intent_node_id_decode` is named in javadoc and by tests,
and the generator consumes the relation's `destination` rather than its `arity`, so nothing emitted
today carries the wrong number. And `destination` itself survives, because it is
`lifted = positions` and the duplication inflates both sides proportionally at all seven coordinates,
every row there carrying a non-null lifted column.

That second one is an accident and should be read as one. Nothing in the rule makes the inflation
proportional. One duplicated row with a null lifted column at any of those coordinates flips
`destination` from own-table to target-table columns, and that value is consumed. The defect is
therefore one differing row away from being visible in emitted output, and it has been sitting in a
materialized target.

**The fix is a grain decision and not a repair, which is why this slice stops here.** Either the
relation's grain is the column mapping, in which case the nine hops genuinely are one row and the
rule should say so where the collapse happens rather than at the top; or its grain includes which
foreign key was walked, in which case the distinguishing columns belong in the projection and every
reader above inherits a wider relation. The first is right if a consumer only ever needs the columns
to decode; the second is right if any consumer needs to know which branch of a polymorphic filter it
is on. Neither is decidable from the read-cost evidence this item carries, and guessing would
either delete facts or widen four relations on a hunch.

### Slice 14: four more grains, and one key measured and refused

Slice 11 keyed the smallest of the twenty and slice 12 showed eighteen of them have a key available.
This slice takes the next tranche, the five relations that answer where a coordinate's SQL is rooted,
and it is worth reporting as two results rather than one, because four of them shipped and the fifth
was measured and held back.

**The four keys, each derived from its own rule rather than from the data.** The measurement against
the kept consumer capture agrees in every case, and is the check rather than the argument.

- `intent_field_scope_table`, keyed on the field coordinate and the table. Each of the three ranked
  rungs yields at most one row per coordinate and the ranks are distinct, so the ranked half is
  unique on the coordinate alone; the participant arm is `DISTINCT` on the table and its precondition
  is that the named type binds no table, which is what the upper rung requires it to have. The
  relation's own column comment already said this key was the grain. `basis` is a function of it: no
  coordinate in 2618 rows carries two of them.
- `intent_argument_scope_table`, keyed on the argument coordinate and the table. The rule is
  `graphql_argument` joined to the relation above on the field coordinate, so its key is that
  relation's key with the argument added.
- `intent_field_column_scope`, keyed on the field coordinate alone. Three arms, pairwise disjoint by
  their own guards: the path arm requires reference steps and the other two exclude them, and those
  two are split on whether the named type is an object or a leaf. Each arm yields one row per
  coordinate. The one-row-per-site property this gives was already stated in prose on a sibling
  relation's comment as something readers stand on; it is a constraint now.
- `intent_argument_column_scope`, keyed on the argument coordinate and the table, the two arms
  disjoint on the same reference-step guard.

**One of the four also fixes a declaration defect.** `intent_argument_scope_table` declared all eight
of its columns nullable while its rule cannot produce a null in any of them, and the capture holds
none. They are `NOT NULL` now.

**Attribution, by five arms differing in one key each, because slice 11's lesson was that a key is a
statement about the model and not automatically an improvement to a plan.** Three of the four move
neither gate: `intent_field_scope_table`, `intent_field_column_scope` and `intent_argument_column_scope`
each leave `DerivedReadCostTest` and `RefreshPlanStatisticsTest` exactly as they were. They are free,
which is the expected result and the reason to check rather than assume.

**The fourth closes three pinned regressions, and closes them the right way round.** All three
readers charged to `intent_argument_scope_table` get cheaper on the registered side while the
unregistered baseline does not move, in scans: the argument reference walk 187 to 9 against a
baseline of 9, the decode hop 1294 to 1116 against 1122, that hop's column child 1387 to 1209 against
1215. Two of the three now cost less registered than unregistered and the third ties. Nothing was
restructured and no index was added beside the target; the coordinate index it already carried is a
prefix of the new key and stays. **So the registration was never what made those readers dearer than
the rule they replaced. The target having no grain to probe on was.** That is this item's thesis
arriving as three deleted rows in a ratchet rather than as an argument.

**The fifth key was measured, works, and is not being taken.** `intent_resolved_type_binding` is
unique on the graph, the type and the table by construction, its rule being a `UNION` over exactly
those columns with `candidates` a window count over the result. Declaring it empties three more rows
from the read-cost gate and two from the refresh-statistics gate. It is refused because of why those
rows would leave. The registered figures barely move (3451 against 3451 on the condition parameter
decode, 795 to 735 on the field reference walk, 2572 to 2512 on the field column scope rule) while
the unregistered baselines collapse: 2923 to 4059, 607 to **32997**, 2384 to **34774**. The pairs
would leave without any reader getting cheaper, which is the gate getting weaker rather than the
schema getting better, and the same gate's own note warns about exactly that on its other axis.

**The mechanism is the wide unique index and not the primary key, which was worth separating.** An
arm declaring the identical five columns as a `UNIQUE` constraint instead reproduces every figure
above to the scan. So this is not H2 reorganising a table's storage around its primary key; it is the
planner given a new index it can choose, inside an inlined rule, choosing fifty times worse. That
lands on the same axis as this item's expansion measurements: the statements where it happens are the
unregistered arms, which is where the inlined statement is largest, and a planner picking badly in a
statement it can barely hold is the failure mode those numbers predict. Naming it is as far as the
read-cost evidence here reaches, and taking the key belongs after somebody has answered why, not
before.

**One stale claim corrected on the way.** The comment on `ix_argument_scope_table_coordinate` said
the index is not unique because `basis` discriminates two rows the coordinate cannot tell apart, and
named a pair of values that belong to `intent_argument_column_scope`'s vocabulary rather than to that
relation's. What the coordinate cannot tell apart is two tables, under a polymorphic root; `basis` is
a function of the coordinate and the table both there and on the relation it fans out from.

**Where the twenty stand after this slice.** Five keyed, of which four were free and one closed three
regressions; fifteen unkeyed, of which thirteen have a key available on slice 12's measurement and
two hold duplicate rows and are a filed defect rather than a grain question. The claim that a
registration is what a relation with no grain gets given has now been tested on a quarter of them and
has not needed an exception.

**Corrected: the reason given for refusing the fifth key was the weaker of the two available, and
naming the stronger one changes what the refusal is evidence of.** It was refused above because the
pairs would leave the gate without any reader getting cheaper, which reads as gate hygiene. The
stronger reason is that the arm those numbers collapsed on is the one this item is trying to reach. A
store with an empty register is a store where every one of these rules is a view, so the unregistered
column is not a control, it is the destination. A key that leaves the registered figure flat and
takes the view path from 607 to 32997 is not a neutral change measured against a convenient baseline.
It is a fifty-fold regression on the only arm that survives this item. The refusal was right and the
finding underneath it is larger than the paragraph above claimed.

### Slice 15: the register priced honestly, and the base that cannot carry the load

The four slices above ask each registered target for its grain, which is worth doing and is not the
goal. They also drifted into reading `DerivedReadCostTest`'s two arms as a scoreboard, and that
reading is wrong in a way worth stating before any more measurement is taken on it.

**The registered arm wins that comparison by construction, so winning it means nothing.** It is a
table with statistics and a declared index, against the same rule inlined at every naming with no
common subexpression eliminated between the copies. Nothing about the fact model has to be good for
the table to be cheaper, and a registration that only proves it can beat its own inlined rule has
proved the thing every registration in the register could prove on the day it was written. That gate
was built as a defect detector, and it detects one thing: a registration losing to its own rule,
which is a registration buying nothing. A cell where it wins is not a result.

**The goal is that the register is empty, and the question every relation has to answer is an
absolute one.** Not "is the table faster than the view", which it is, but "is the rule fast enough
that nothing needs to stand in front of it". That is a question about the fact model, and the item
has already answered it yes at one relation by fixing the model underneath rather than by pricing an
arm. Where a rule is not fast enough, the levers are the ones the fact-model page now ranks: capture
writing the fact, an index on a stored column, a rewrite. A registration is the lever of last resort
and the register is the record of it having been reached for first.

**Which puts the keying work in its right place, smaller than the last four slices implied and still
worth having.** A primary key on a materialization target does not survive the target's dissolution:
retire the registration and the table goes, and the key with it. Keying does not move a relation
toward an empty register. What it does is force the relation to say what one of its rows is about,
and that statement is what makes the next question answerable, because it is what exposes a column
sitting at a different grain from the key it is stored beside. The relation priced below carries
exactly such a column, `candidates`, an arity over the spelling that sits above the key the whole row
is stored at, and it was slice 11's key that made the mismatch visible. That column turns out not to
be what the relation costs, which is worth stating in the same breath: knowing the grain is what let
the question be asked, and the answer to it was no.

**First, a defect in this item's own harness, which has to be stated before any figure below is
read.** The bench that prices a registration builds a store from the DDL, copies a real consumer's
captured facts in, refreshes the registered targets and then times a workload. It refreshed them in
the order the register's `INSERT` lists them, and that is not a dependency order: a target on today's
register is listed second while a target its own rule reads is listed last. On a cold store that
fills the earlier one from the later one's empty table. The effect was not marginal. **In every
registered arm this item has taken, eighteen of the twenty-two targets held zero rows**,
`intent_spelled_table` among them, and `intent_field_reference_step_hop` held 742 rows where a
faithful arm gives it 12817.

So every figure in this item taken from a registered arm was measured on a store where most of the
relations being priced were empty, and the two retirement timings in slice 4b are the load-bearing
ones. Neither retirement turns on them. The argmapping retirement was argued on the registration's
index being column-for-column its own source table's primary key and the rule being a bare
projection, which is a structural fact no timing enters; the errors-field retirement was argued on a
registration being a blindfold over the rule beneath it, likewise. **The milliseconds quoted beside
both are void and should not be cited again.**

**The shipping refresh does not have this defect**, which was checked rather than assumed: the
gatherer orders itself out of the derived refresh edges that a boot-time routine populates from the
stored view definitions, and a gate pins that order against a fixture built so an unordered refresh
fails it. The bench had no such routine because it builds its store from the DDL alone. It now
refreshes to a fixed point instead and reports the passes that took, and both arms below converged in
four.

**What the defect does not reach is the evidence this item actually rests on.** The census of the
twenty on the emptied arm, the inlined-statement sizes, the two relations that exhaust a heap inside
the planner and the seven cuts that bring the schema back under its current largest statement were
all taken with the register emptied, where nothing is materialized and no refresh runs at all. Refresh
order cannot touch them.

**The register, priced honestly for the first time.** Twenty registrations, a real consumer's facts,
every target holding the rows its rule actually produces, and a workload of one `count(*)` per
`intent_` relation, 112 of them. Taken against the schema as it stands after the decode family was
given its branch grain, so these are current rather than this slice's first reading:

[cols="4,2,2"]
|===
| arm | refresh pass | workload

| all twenty registered | 42.4 s | 270.8 s
| `intent_spelled_table` demoted, other nineteen kept | 71.8 s | 429.5 s
|===

**The demotion costs 29 seconds of refresh and 159 of workload, and that penalty is the point of
taking it.** The registration's own reason predicted removing it alone would make the refresh about
twice as dear and the readers about half again as dear; measured, 1.69 and 1.59, so the reason is
accurate and this is the first time one has been tested against a faithful arm. Accurate is not the
same as sufficient. A penalty on demotion is what a registration is for, and reading it as a verdict
would end the investigation exactly where it should begin. The useful question is which relation the
penalty lands on and what underneath that relation cannot carry the load.

**It lands on one relation, and that relation is expensive before anything is demoted.** Of the 270.8
seconds the fully registered arm spends, **159.3 are `intent_node_id_decode`**. The second dearest is
27.5 and 97 of the 112 answer inside a second. That relation has never been registered and no item
has proposed registering it, so this is not a cost the register is holding back; it is what the
schema costs today with every registration in place. Its own comment already says as much, calling it
the deepest derived read in the schema and warning a reader never to take it correlated per row. The
figure here is what that warning is worth in seconds.

**And it is where the demotion's penalty went, almost exactly.** Demoting `intent_spelled_table` adds
158.7 seconds of workload, of which 157.1 are `intent_node_id_decode` and `intent_node_id_encode`. No
other relation of the 112 moves by more than three seconds and one moves the other way. So the
registration that reads as protecting thirty-two readers is, on this consumer's data, protecting two
of them, from a defect that has nothing to do with resolving a table spelling.

**Taken apart, the relation says exactly what is wrong with it.** Every statement below ran in one
warm session, so these are H2's own per-statement times with no JVM start in them:

[cols="5,2,2"]
|===
| statement | rows | time

| the registered `intent_node_id_decode_column` table, counted | 1167 | 4 ms
| `intent_argmapping_binding_leaf`, counted | 108 | 52 ms
| `intent_resolved_node_key_shape`, counted | 635 | 268 ms
| `intent_node_id_decode_slot`, counted | 48 | 520 ms
| the first arm's projection deduplicated, anti-join removed | 351 | 4 ms
| the whole relation | 351 | 156.9 s
|===

**Four milliseconds against a hundred and fifty-seven seconds.** Every input is cheap, the
deduplication is free, and the `NOT EXISTS` beside them is the entire cost. What that clause does is
ask a 48-row relation a membership question once per driving row.

**The cost is linear in driving rows, which two independent readings agree on.** Moving the
anti-join after the deduplication takes the driving set from 1167 rows to 330 and the time from
140.1 s to 40.4 s: a ratio of 3.47 against the row ratio of 3.54. And the branch grain that landed on
the decode family while this slice was being measured is the same experiment run by someone else:
it took `intent_node_id_decode_column` from 1559 rows to 1167 and the relation from 198.1 s to
159.3 s, tracking the row count rather than anything about the new columns. Three other rewrites were
tried against the clause before this one and none of them made H2 evaluate the slot relation once: a
`LEFT JOIN` against a `SELECT DISTINCT` was no better than the original, a `GROUP BY` derived table
that H2 need not inline landed on the same figure as simply reordering, and computing the surviving
use sites first and joining them back cost more than three times the original because the chain then
got inlined twice.

**So this is not a query that can be written better, which sends the question down rather than
sideways.** Rewriting is the third lever on the fact-model page and it has now been tried properly and
refused. If the clause cannot be made to run fewer times, what has to change is what one run costs,
and that is a question about the relation underneath rather than about the statement above.

**One run is a 350-node plan, and the descent from there ends at a single relation.** Counting plan
references, the same expansion measure this item takes on statement size:

[cols="5,2,2"]
|===
| relation | plan references | rows

| `intent_node_id_decode` | 818 | 351
| `intent_node_id_decode_slot` | 350 | 48
| `intent_argmapping_binding_leaf` | 293 | 108
| `intent_resolved_node_key_shape` | 111 | 635
| `intent_argmapping_segment_binding` | 96 | 202
| `intent_argmapping_bound_parameter_type` | 27 | 108
| `intent_field_producer_method` | 9 | 97
| `intent_argmapping_pair` | 2 | 108
| `intent_input_occurrence_path` | 1 | 3027
| `intent_node_id_decode_column`, `intent_spelled_table` | 1 each | 1167, 313
|===

**Two hundred and ninety-three of the slot rule's three hundred and fifty plan references are one
relation, and that relation has 108 rows.** `intent_argmapping_binding_leaf` is the base that cannot
carry the load. It answers in 52 milliseconds when asked once, so nothing about reading it is slow;
what it costs is being stated at all, and every layer above inherits that. The slot rule adds
fifty-seven references to it, the decode rule adds the anti-join, and the anti-join is what makes the
whole chain run 1167 times. A 108-row relation reached through a 293-node plan is not a query-shape
problem and no index on it would help, because the cost is not in reading a table. It is in there
being no table to read.

**These counts are unchanged by the branch grain**, which is worth stating because that change landed
in the middle of this measurement and moved every timing on the page. It took the decode family's
duplicate rows out and the plan sizes did not move at all: 818, 350 and 293 before and after. The
expansion is a property of how the rules are stated and the row counts are a property of the data,
and this item has been treating them as one thing in places.

**Read the other way, the table is a check on this item's own two retirements.**
`intent_argmapping_pair`, demoted from the register in slice 4b, costs two plan references. That
retirement was argued structurally, on the registration's index duplicating its source table's
primary key and the rule being a bare projection, and the structure is exactly what the number says:
the demoted relation is as good as the table it was copying. The starved timings quoted beside it
were void and the decision they were attached to was right anyway.

**So the foundation work this item has been circling has a first address.** Not the twenty
registrations and not the relation that dominates the workload, but the 108-row relation three layers
under it. What that relation costs to state, why stating it takes 293 plan nodes, and which of the
levers applies to it are the next slice. The dissolution question comes back afterwards and on better
ground: shore this up and the 159-second relation moves, and the reason `intent_spelled_table` is
still registered moves with it, since 157 of the 159 seconds its demotion costs are spent inside the
same chain.

**A note on how these were measured, because it changed the numbers.** Every probe before this
paragraph's table was a fresh JVM per statement, and H2's start-up is around six hundred
milliseconds, which is most of what a sub-second reading contains. Batching a session's worth of
statements into one invocation and reading H2's own per-statement times took `intent_node_id_decode_slot`
from a reported 1.1 s to 520 ms and `intent_argmapping_binding_leaf` from about a second to 52 ms.
Nothing above a few seconds moved. Any figure in earlier slices below about two seconds that came
from this bench should be read as containing a JVM start.

### Slice 16: the base named, and what capture should have been writing

Slice 15 ended at `intent_argmapping_binding_leaf` and called it the base that cannot carry the load.
That was one layer short. Under it is a fact with no name, and under that is the reason the fact has
no name.

**The unnamed fact is not a relation. It is two columns of `graphitron_arg_mapping_pair`.** The
`headed` CTE inside `intent_argmapping_segment_binding` resolves each argMapping pair's path head,
the segment at position 0 of its own `argument_path`, together with whether that head enters at an
argument or at an input field. Measured against the capture: 108 rows, 108 distinct on
`(graph_name, site, use_site, position)`, 108 pairs in the table, no nulls, two values of the kind.
Total and unique on the pair table's own primary key, so it adds no grain at all. The kind is a
function of `site` alone, `INPUT_FIELD_CONDITION` giving one value and the other eight the other.

**A fact at the pair's own grain, computable from the pair, currently exists only as a CTE.** Which
means it has no key, no index, and no single evaluation. Counting how many times the pair table is
instantiated in one plan gives the price:

[cols="5,2"]
|===
| relation | instantiations of `graphitron_arg_mapping_pair`

| the `headed` rule alone | 6
| `intent_argmapping_segment_binding`, which names it four times | 24
| `intent_argmapping_binding_leaf`, which names that twice | 48
| `intent_node_id_decode` | 84
|===

Each layer names the one below twice, once to drive and once to test whether a next one exists, and
H2 inlines both. The decode then runs its 84-instantiation plan once per driving row, 1167 of them,
which is on the order of 98000 evaluations of the head rule for one read.

**Storing the layer above was measured and is the wrong fix.** Materializing
`intent_argmapping_segment_binding` as a table and repointing the chain takes the decode from 168.8 s
to 114.4 s. Real, and proportional rather than structural, because the 1167 correlated evaluations
survive it. Naming the fact a layer lower attacks the multiplier instead of the multiplicand, which
is the difference between this item's lever order and the one it replaced.

**And the reason the head has no name is the larger finding.** An argMapping names which method
parameter an argument's value reaches. **The grain of that is the pair, so an argMapping pair is just
an argMapping, and every argument of every service call has exactly one.** Some are authored with the
directive and the rest follow from rules the generator already applies. The relation is total on a
coordinate the store already keys.

**What capture writes is not a smaller version of that relation. It is one of its two populations.**
On the measured consumer, 34 rows against 66 service arguments. The other half is left implicit, and
an implicit population can only be reached by naming what is absent, which is why every reader that
wants the whole thing unions an arm carrying a correlated `NOT EXISTS` against what capture did
write. Absence does not join, and an anti-join is what doubles a statement at every layer. The
multiplication in the table above is that decision compounding four times.

**The schema already says this in its own words, in two places.**
`intent_node_id_decode_slot`'s comment defines its two carriers as an authored pair and "the producer
method declaring a parameter of the root argument's own name with no pair on that parameter", and
states plainly that "the absence of a pair on the parameter is what makes the two disjoint". The same
comment records a hole in the seam: a path descending into an argument to bind one input field below
it is seen by neither arm, so such an input field is read as binding a predicate. That is a
correctness gap living exactly where the relation stops being total, and it closes when the relation
is total.

**The by-name inference is spelled inline in at least two readers**, `intent_node_id_decode_slot` and
`intent_argmapping_bound_parameter_type`, each as an arm plus an anti-join. There is no relation named
for the result, so nothing can join to it and each reader pays to rebuild it. That is the same
supertype omission this item opened on, arriving at the relation it costs the most.

## What this item now takes on

**Capture writes one row per parameter of every call, carrying how it was bound.** Provenance is not
decoration: it is what lets a reader take the whole relation and filter, rather than reconstruct the
whole from one half. The vocabulary the retiring Java walk already uses is the starting point, an
authored directive binding, an identity name match, and whatever survives of the arity-unique and
type-unique inferences that R219 proposes to collapse into one rule. This slice does not settle that
vocabulary. It settles that the relation is total and that provenance is a column on it.

**It stays in `intent_`, and an earlier draft of this slice got that wrong in a way worth keeping.**
That draft expanded the head rule, found it reaches only `graphitron_arg_mapping_pair`,
`graphitron_argument_path_segment` and `graphql_argument`, counted `graphql_` and `graphitron_` as
one family because they are one gatherer, and concluded the rule crosses nothing and therefore
belongs to capture, where it could be stored with no `meta_materialize` row. The arithmetic was right
about the head and wrong about the relation. The head is not what has to become total. What has to
become total is one row per parameter of every call, and knowing what parameters a call *has* means
reading the classpath census for a service method and the catalog for a routine. The relation
therefore spans the SDL, `jvm_` and `sql_`, which is three families and exactly what `intent_` is
for: a rule whose facts cross is owned by the gatherer that runs after all of them.

**Which relocates the question rather than answering it, and the relocated question is the better
one.** If this must be a crossing rule, it cannot be made cheap by moving it. It can only be made
cheap by the families underneath it handing up facts it can build on. So the thing to ask each base
is not whether it holds the rows, which they all do, but whether it holds them in a shape a crossing
rule can use.

**Asked that way, one base is failing far worse than the rest.** Counting instantiations of each
captured relation inside a single plan of `intent_node_id_decode`:

[cols="4,2,2"]
|===
| relation | instantiations in one plan | rows

| `graphitron_argument_path_segment` | 106 | 202
| `graphitron_arg_mapping_pair` | 84 | 108
| `intent_input_occurrence_path_step` | 38 | 3526
| `sql_node_metadata` | 26 | -
| `graphql_argument` | 26 | -
| `sql_routine_parameter`, `jvm_method_parameter` | 4 each | -
|===

The parameter censuses, the thing an argMapping actually binds to, are read four times each. The
authored path's segment list is read a hundred and six times. Whatever is wrong here is not that the
upper layers reach too far into the lower ones. It is that they reach into one of them over and over
for something it could have said once.

**What that base withholds is the shape of its own decomposition.** Capture takes an argMapping path,
splits it into segments and stores one row per segment with a position. It knows the length at the
moment it writes them. It stores neither the length, nor which segment is last, nor how many follow
any given one. So every rule above it rebuilds those by self-join: a correlated `COUNT(*)` over
segments past this position for the trailing count, an anti-join on `position + 1` for the last one,
an equality on `position = 0` for the first. Three questions about an ordered list of at most a few
elements, asked of a 202-row table a hundred times per statement, each one an anti-join and each
anti-join doubling the statement that contains it.

**And the second withheld fact is a correspondence, which the schema already admits it is missing.**
The same descent is decomposed twice in this schema: once as the authored path's segments by
position, and once as the input type's occurrence path by ordinal. Nothing states their
correspondence, so the two rules that need it align the decompositions inline with a `NOT EXISTS`
containing a `NOT EXISTS`, which is where the occurrence-path step relation's 38 instantiations come
from. `intent_node_id_decode_slot`'s own comment names this as the gap behind its stated limitation,
calling it "a reconciliation between two decompositions of one descent rather than a join". A
correspondence with no name is being recomputed as nested negation.

**So the answer is that the bases hold the right rows and hand up the wrong facts.** The parameter
censuses are fine and barely read. The path decomposition is read a hundred times for three facts
its own writer knew and discarded, and the correspondence between two decompositions is read as a
double negation because nobody has stated it as a relation. Neither is fixed by making the argMapping
relation total, and both have to be fixed before totality is affordable, which reorders this item's
own plan: the base facts first, the total relation on top of them.

**Four questions this slice does not answer.**

- **Totality across the nine sites.** The claim is stated for service calls. The `site` vocabulary is
  closed at nine and four of them carry rows on the capture measured here. Whether each site's
  population is total in the same sense has to be settled per site rather than assumed from one.
- **Where the inference runs.** Capture writing the inferred rows moves inference below the
  generator, which is the axis R864 already occupies. If inference stays in the generator the store's
  relation cannot be total and this reduces to naming the head.
- **Provenance vocabulary.** Settle jointly with R218 and R219, which hold the same distinction in
  the Java model being retired. Nothing here should encode a branch boundary R219 removes.
- **The documented hole.** Whether closing the input-field-under-an-argument case belongs here or
  beside it. The decode slot's comment already names it as an unstated reconciliation between two
  decompositions of one descent.

**What would show it landed.** Three instantiation counts in one plan of `intent_node_id_decode`,
against the 106, 84 and 38 slice 16 measured: the path segment list read a handful of times rather
than a hundred, the pair table in the low single digits, and the occurrence-path step relation
likewise once the correspondence has a name. Behind those, the by-name arm gone from both readers
that spell it and the correlated anti-join probing a keyed relation instead of inlining a rule.
Slice 15 holds the before figures for the timings. **Measured in slice 29: two of the three met, the
pair table missed at twelve.**

**The two sides of an argMapping resolve against different families, and only one of them has been
modelled.** An argMapping has a left side and a right side. The left names a parameter and resolves
against the service or routine being called, which is the classpath census or the catalog. The right
names a path and resolves against the SDL, descending through input-object-typed fields, so it can
carry several segments. The relation the store holds today keys both halves to the same row and
decomposes only the right one, into positioned segments that reference nothing on the SDL side. That
is why the alignment above exists at all: the right side was stored as its own decomposition of a
descent the SDL already describes.

**The descent the SDL already describes is stored, keyed, and prefix-closed.**
`intent_input_occurrence_path` is a table keyed on the graph and the serialized path, with
`intent_input_occurrence_path_step` its ordinal-keyed decomposition, both with foreign keys into the
GraphQL coordinate relations. On the measured capture that is 3027 paths and 3526 steps at a maximum
depth of four. Its comment claims every prefix of a path is itself a row and the claim holds: zero
paths of depth two or more are missing their parent. It carries no `meta_materialize` row and does
not need one, being stored under the charter's other clause, that no view could express the rule,
because cyclic input nesting is legal GraphQL and has no safe recursive form in a view.

**So the relation the editor needs for this already exists and the editor does not read it.**
`ArgMappingCompletions` resolves the left side against the store, selecting parameter names from
`jvm_method_parameter` for the sibling method the directive targets. For the right side it reads the
parse tree instead, offers the enclosing field's argument names, and then stops: it returns nothing
as soon as the token contains a dot, under a comment saying that dot-path expansion into nested input
fields is not modelled and a flat list would mislead. The traversal completion this needs is
therefore not merely slow, it is absent, and the reason recorded in the code is the absence of a
model rather than a cost.

**Which is also the answer to why this cannot be a capture-cadence materialization of the
argMapping.** The editor is navigating a path the author has not finished typing and will not save
mid-navigation, so nothing keyed to an authored argMapping can answer it. Nothing has to be. The
input surface being traversed is a function of the saved SDL, which does not change between
keystrokes inside a directive string, and every legal prefix of every path is already a row in a
keyed table. Completing a partial path is an index probe for the rows one step longer than the
prefix. The unsaved thing is the path, and the path is what the author is choosing, not what has to
be looked up.

**That makes one change serve both readers, which is the argument for doing it here.** If an
argMapping's right side resolves to an occurrence path, carrying its key rather than a parallel
segment list, the generator gets a join where it currently has two decompositions aligned by nested
negation, and the editor gets a traversal it can probe by prefix. The instantiation counts slice 16
measured, 106 for the authored segment list and 38 for the occurrence step relation, are both
consequences of the two decompositions being unrelated; a foreign key between them is what removes
the alignment rather than optimising it.

**There is a specification of the right-hand side, and it already says candidates, selection and
invalidity.** The manual states it once, under "Binding a parameter to a nested input field" on the
`@service` page, and both the `@routine` page and the custom-conditions how-to cross-reference it
rather than restating it. Its rules are exactly the model this slice has been reaching for: the head
segment must name a slot in scope at the directive's site, each subsequent segment must name
something the value at that depth opens into, a path may be any depth, reading is null-safe, and a
bare name with no dots is the single-slot form and is what an entry with no `argMapping` binds to
implicitly. It even names the failure: a head naming nothing in scope is a build error listing the
slots that are, and a later segment naming neither an input field nor a key column is a build error
naming what it looked in.

**Two things open, and the specification is explicit that nothing else does.** An input object opens
into its fields. An `ID` carrying `@nodeId(typeName:)` opens into the key columns of the node type it
names, so the segment after it names a key column rather than a field of any SDL type.

**A draft of this slice read the second arm as crossing into the catalog and was wrong twice.** It
said validating that arm means reading the catalog's node key columns, naming `sql_node_key_column`
and its 1262 rows. That relation is not the catalog: its own comment says it holds the key-columns
constant *as stated*, that the constant may spell a column the table does not have, and that whether
an entry resolves is a defect relation's question. It is a claim read off a generated class. The
catalog proper enters only through a third arm that never fires here. And the deeper error was
supposing the argMapping site should validate at all. What an `@nodeId` opens into is what `@node`
claims; whether the claim holds against the catalog is `@node`'s question, owned by
`intent_node_metadata_defect`, and answering it at the `@nodeId` site puts a resolution where a match
belongs.

**The rule as written does resolve, and that is where the crossing enters.**
`intent_argmapping_key_column_candidate` reads `intent_resolved_node_key_column`, which is a
three-tier precedence union under a `DENSE_RANK`: the `@node` directive's own claim first, the jOOQ
metadata constant second behind a resolved type binding and a defect anti-join, the catalog's primary
key third. That is why a plan of `intent_node_id_decode` instantiates `sql_node_metadata` 26 times
and `sql_node_key_column` 10.

**And the tiers say the same thing about `@node` that this item has been saying about `argMapping`.**
Of 2294 resolved entries, 2 are the SDL's own claim, covering one node type of 249. The other 2292
come from the metadata constant, and the catalog tier never wins. So the effective key-column claim
is almost entirely a default the store computes rather than a fact anybody captured, and every reader
that needs it re-derives the precedence union. Authored subset captured, effective set recomputed:
the same defect, one directive over.

**Which repairs the family answer rather than abandoning it.** If capture wrote `@node`'s effective
claim, defaulting from the constant where the SDL does not pin it, the argMapping right-hand side
would match a claim rather than resolve one, both of its opening kinds would sit in the SDL
gatherer's own family, and the candidate space would be a `graphitron_` fact throughout. The
crossing in the rule today is a consequence of the missing capture, not a property of the
specification.

**The left-hand side has no such single statement, and what is written is behind the code.** Three
pages each say that unmentioned parameters bind to a GraphQL argument of the same name and that an
empty `argMapping` is identity. None of them mentions the two further inference branches the
retiring Java walk applies, the arity-unique and type-unique matches that R218 names and R219
proposes to collapse. So the documented rule set is explicit binding plus identity, and the
implemented rule set is explicit binding plus identity plus two inferences. Whichever is right, the
store cannot hold a total relation with a provenance column until that is settled, because the
vocabulary of the column is the rule set.

**And the store models none of the candidate space.** Two relations in the schema carry the word
candidate for this walk and both are keyed to a path somebody already wrote.
`intent_argmapping_key_column_candidate` is a view over `intent_argmapping_binding_leaf`, so it
enumerates candidates for the trailing segment of an authored path and holds no rows at all on this
capture. The input-object arm has no candidate relation whatever; `intent_input_occurrence_path`
describes the same descent and is keyed and prefix-closed, but it is framed as the occurrences that
exist rather than as what a position opens into, and nothing reads it for this purpose. A
specification that defines a candidate set, a selection over it and a rejection when the selection
misses, against a store that models only selections that were made.

**And the order the work has to go in, which slice 16 changed.** The base facts first, because the
total relation is a crossing rule and a crossing rule cannot be made cheap by relocating it: the
path decomposition states its own shape, the correspondence between the two decompositions gets a
name, and only then is one row per parameter of every call affordable to state. Doing it the other
way round would build the total relation on the same hundred-fold read and measure no better.

**A word on the word pair.** The relation is named for a pair because it replaced eight relations
each spelling one, and the name records that collapse rather than the grain. If the grain is the
argMapping, the qualifier is doing no work and invites exactly the layered reading above it, where
something assembles pairs into a mapping. Renaming is not the substance here and should not be taken
for it, but it should not survive the change either.

### Slice 17: the head becomes a captured column, and a third of the schema's read cost goes with it

Slice 16 named the fact and this slice writes it. `graphitron_arg_mapping_pair` gains two columns,
`head_segment` and `head_kind`, and the capture writer fills them where it already had both: the head
is the first segment of a path it has just split, and the kind follows from the site alone. A check
constraint ties the kind to the site so the two cannot drift, and neither column claims the head
resolves.

**The rule that was re-deriving them now reads them.** `intent_argmapping_segment_binding`'s `headed`
common table expression was three arms, each joining the pair relation to the segment child at
position zero and one of them to `graphql_argument` besides. It is three arms over the pair alone
now. Two of them are column comparisons with no join at all, an argument-level condition's head
having to equal its own argument name and an input-field-level condition's its own field name, and
only the first still joins, because what it checks is that the head names a declared argument.

**The separation the specification asks for is what made this possible.** The manual's rules for the
right-hand side are a candidate set, a selection over it and a refusal when the selection misses.
The old rule fused all three: the head was resolved and validated in one union, so the resolution
could not be stored because the validation needed relations the resolution did not. Splitting them
puts the selection where it belongs, on the pair, as spelled, and leaves the refusal to the rule that
already performs it. Nothing about which paths are legal changed.

**The first published reading of this slice was taken on a starved arm and is withdrawn.** The bench
copies base facts from a capture that predates these two columns, and because they are `NOT NULL` the
copy of `graphitron_arg_mapping_pair` failed outright, leaving the arm with no argMapping pairs at
all. Every relation in the chain measured zero rows and the decode looked half as expensive because
half of it was empty. The figures that reading produced, 83.8 s and a 171.1 s workload, are void. The
bench now backfills the two columns during the copy, deriving them as capture does.

**Re-measured correctly, the change buys no time at all.** Three readings of `intent_node_id_decode`
on each arm, the two arms run under identical contention so the comparison is fair even though both
are inflated:

[cols="3,4"]
|===
| arm | `intent_node_id_decode`, three readings

| before this slice | 152.6 s, 158.8 s, 158.3 s
| after this slice | 155.0 s, 160.8 s, 159.9 s
|===

Marginally slower, which is to say indistinguishable. The workload totals agree: 270.8 s before and
274.2 s after. Row counts are identical everywhere along the chain, 108 pairs, 202 segment bindings,
108 leaves, 48 slots, 351 decodes, so the rewrite is behaviour-preserving and simply does not pay.

**What the change did buy is structural and deterministic.** Inside one plan of
`intent_node_id_decode` the authored segment list falls from 106 instantiations to 34 and the plan's
total relation references from 818 to 674. Those numbers are properties of the schema, reproducible
exactly, and unaffected by the noise above.

**Which re-teaches this item its own lesson, from the wrong end.** Slice 4's census already said the
expansion count predicts plannability and nothing else, and that anyone reading it for execution cost
is reading it for something it does not measure. This slice removed 72 plan nodes out of 818 and
timed the result expecting a saving, and the nodes it removed were index probes into a 202-row table,
which were never what the statement was spending its time on. Making a statement smaller and making
it faster are different projects. The head still belongs on the pair, because a fact at the pair's
grain that four readers re-derive is a modelling defect whatever it costs, and because the total
relation this item is heading for needs it. It is a modelling step with no measured speedup, and
recording it as anything else would have been the third starved figure this item has published.

**What is actually slow, measured rather than inferred.** Two multiplied together. The outer factor
is the correlated anti-join in `intent_node_id_decode`, which evaluates the slot rule once per
driving row, 1167 of them. The inner factor is inside that rule and is not the path walk at all:
ranking the scans of one slot evaluation, the largest by a factor of two and a half is 11772 index
scans of `graphitron_arg_mapping_pair` under a predicate that binds only the graph. That is the
by-name inference's `NOT EXISTS`, asking of each producer-method parameter whether any pair claims
it, and because the pair relation is keyed by site and use site and position, a probe by parameter
name has no index to use and reads the graph's whole partition. A hundred and eight rows read a
hundred and nine times, inside a rule run eleven hundred and sixty-seven times.

**So the thing this item has been circling is also the thing the clock is on.** The by-name arm exists
because capture writes one of the argMapping relation's two populations and the other is defined by
absence. Absence has no index. Make the relation total with provenance as a column and that anti-join
becomes an equality on a stored value, which is the one change that attacks the inner factor. The
outer factor, the correlated evaluation, is a separate fix and the two compose.

### Slice 18: the candidate is keyed by its path and carries its parent

The right-hand side of an argMapping selects from a candidate set, and the candidates form a tree:
each one is identified by the path that reaches it, and each one below a root has a parent candidate,
the path one segment shorter. That is the whole of the shape, and everything the two consumers need
falls out of it. Completing a partially typed path is a probe for the candidates whose parent is the
prefix. Validating a written path is a probe for a candidate with that key. Asking whether a segment
is the last is asking whether its candidate has children. None of the three is a walk.

**It also removes the alignment rather than optimising it.** An argMapping's right side is a path and
a candidate is keyed by its path, so the selection is a foreign key. Nothing has to reconcile a
positional segment list against an ordinal step list, because there is one decomposition and both
sides name it the same way. The nested negation slice 16 measured, and the positional walk slice 17
made smaller without making faster, are both consequences of the two decompositions being unrelated,
and both go when they are the same relation.

**The relation that looked like it already was this one is not, and the gap is the interesting
part.** `intent_input_occurrence_path` is keyed by graph and path, prefix-closed, and holds the
descent through input-object-typed fields. Measured against the same capture: 528 arguments exist,
406 of them have a root row there, and 3027 rows in total of which 406 are roots and 2621 are descent
steps. **So 122 arguments are candidates that relation does not hold**, because it admits an argument
only when the argument's named type is an input object, and an argument of a scalar type is a
perfectly legal right-hand side under the specification's single-slot form. A bare name with no dots
is what an entry without `argMapping` binds to implicitly, so the missing 122 are not an edge case,
they are the common case.

**Which explains why the argMapping rules walk positions instead of joining.** They cannot join the
occurrence path for the head, because for 122 of 528 arguments there is no row to join to. The
positional segment walk and the alignment by double negation are what a rule does when the relation
it wants covers a different population than the one it has. The defect is not that somebody chose the
harder query; it is that no relation held the candidates.

**Shape, stated so the next slice can build it.** Take a worked path: `input.nodeId.COLUMN_A`. It is
one candidate. Its parent is `input.nodeId`, whose parent is `input`, which is a root. Each of the
three has a type, and **the type is what says what the candidate opens into**: `input` is an input
object so it opens into fields, `input.nodeId` is an `ID` carrying `@nodeId` so it opens into the
node type's key columns, and `COLUMN_A` is a column so it opens into nothing. That is why one
relation covers a descent whose opening rule changes at every level: the levels differ but the row
shape does not, and the discriminator is a fact the candidate already carries about itself rather
than a rule the reader has to apply.

**The key is the field coordinate, then the argument, then the descent below it.** A candidate is
rooted at a field, so the graph, the type and the field lead the key of every row. The argument is
the next column rather than the first segment of a string: a field may declare several and each is
its own subtree. Below that sits the path an author writes under the argument, empty at the root, so
the worked example decomposes as the field coordinate, `input`, and then `nodeId` and
`nodeId.COLUMN_A` beneath it. The parent is the same coordinate and argument with the path one
segment shorter, null where the path is empty.

Each row also carries its own element name, the thing an author writes at this step, and its type,
the type being what says whether anything opens below. **The name is stored beside the parent and not
left to be recovered from the key**, on the rule this schema already holds elsewhere: an occurrence
path is its own identity and its step child carries the same data relationally so that no consumer
parses the serialized key, and the writer that splits an argMapping path records the split because no
reader may split a string. With the parent and the element name both present, the path column is
identity and nothing else: every question about a candidate is answered by a column or by following
the parent, and no rule performs string surgery to ask it. **A root exists for every argument, scalar or input type alike**, which
is the whole of what the occurrence path relation is missing: its 406 roots are the arguments whose
named type is an input object, and the 122 it omits are the scalars, which open into nothing and are
still perfectly good candidates because a bare name with no dots is a legal selection.

**The input-field-level site looked like an exception to this and is not.** Such a `@condition` sits
on an input field rather than on the field, and the specification lets its head name that input field
itself. The coordinates and the key do not change for it: the input field it sits on is already a
candidate in this tree, reached from the enclosing field through its argument, so the site has a
candidate key like any other position and its head names a candidate under the same four leading
columns. What varies between sites is which node in the tree the directive stands at, and that is a
fact about the site, not a second shape for the candidate. No degenerate root, no extra kind, no
alternative key.

**Which makes the right-hand side a foreign key in the literal sense, and slice 17 already cut it
at the join.** The head column that slice added to the pair is the argument half of this key, and
what follows the first dot is the path half, so an authored right-hand side splits exactly along the
candidate's key columns. A written path either matches a candidate row or does not, and that is the
whole of validity. The positional segment child
disappears into the parent chain: what was `position` is depth along the parents, what was "how many
follow" is how many descendants remain, and what was an alignment between two decompositions is one
relation read twice.

**Where it lives, and what it is allowed to cost.** The candidate rule reads arguments and input
fields from the SDL and key columns from the `@node` claim, which is one gatherer, so the relation
belongs to that family and needs no `meta_materialize` row. It has to be stored rather than stated as
a view for the reason its neighbour already is: cyclic input nesting is legal GraphQL and has no safe
recursive form in an H2 view, and `intent_input_occurrence_path` is the precedent, a table written by
a capture-cadence derivation writer under the charter's other clause.

**Staging, forced by an unrelated defect.** The key-column kind depends on the `@node` claim, and
slice 16 measured that claim as almost never made: 2 of 2294 resolved key-column entries come from
the SDL, covering one node type of 249, with the rest defaulted from the jOOQ metadata constant. A
candidate relation whose key-column arm reads only the SDL claim would be very nearly empty. So the
argument and input-field kinds come first and the key-column kind waits on capture writing `@node`'s
effective claim, which is the same fix this item already owes one directive over.

### Slice 19: the candidate relation exists

`graphitron_argmapping_candidate` holds what an argMapping right-hand side may name, one row per
candidate, keyed by the field coordinate, the argument and the path below it, with the parent path a
foreign key back to itself. Each row carries its own element name and its type. A capture-cadence
writer fills it beside the occurrence-path writer it is modelled on, for that relation's reason: the
descent is recursive and a view has no safe recursive form over it.

**Seeded from every argument, which is the whole difference from the relation beside it.** The test
that would fail if this were quietly re-derived from `intent_input_occurrence_path` is the first one:
a field declaring an input-object argument and a scalar one yields two roots here and one there. A
bare name with no dots is a legal right-hand side and is what an entry with no `argMapping` binds to
implicitly, so the scalar argument is a candidate; the neighbour admits only arguments that descend,
and that population mismatch is why the argMapping rules could not join to it.

**The cycle is a row rather than an absence.** Cyclic input nesting is legal GraphQL and does reach
capture, which runs before anything classifies, so an assumption that the classifier has already
refused such a schema is false and two existing fixtures proved it within minutes of being written.
The element that closes a cycle is nameable, so it gets a row and carries a marker saying what it
is, and nothing below it is written. Marking it rather than merely stopping is the point: a candidate
with no children is otherwise ambiguous between a leaf and a stopping point, and a relation whose
absences carry meaning is the shape this item exists to remove. The marker also pays for itself
twice, because the next pass's guard reads the column instead of walking the ancestry again, and
because the refusal an author needs to see for the unsupported case is now a query over these rows
rather than a special case somebody has to remember.

**One price this shape pays, recorded because it is the only one.** A candidate carries its parent
and not its ancestors, so the writer recovers the ancestry with one join per level to decide the
marker. That is bounded by the same pass bound as the expansion, and it buys a single relation where
the neighbour needs a path table and a step child to get the same test cheaply.

**Nothing reads it yet.** The rewiring is the next slice: the remaining segment reads and the
alignment by double negation in `intent_argmapping_segment_binding` and
`intent_argmapping_binding_leaf` become joins to this tree, and `graphitron_argument_path_segment`
retires when nothing names it. Two populations stay out until their own dependencies land, and
neither is an oversight: the key columns an `@nodeId` opens into wait on `@node`'s effective claim
being captured rather than defaulted per read, and nothing about the input-field-level condition's
own site is missing, because the input field it sits on is already a candidate here.

### Slice 20: the selection is spelled as a candidate key, and the fan-out the rewiring runs into

Two columns, so that a written right-hand side and a candidate can be compared without either side
walking. The pair gains `candidate_path`, the written path below the head, which with the
`head_segment` slice 17 added is a candidate's key under the pair's own coordinate. The candidate
gains `is_list`, because the leaf relation reports arity beside type and a consumer that had to join
back to the SDL for it would be re-reading what the writer already read. Both are splits of a string
that capture performs and no reader may.

**What that was meant to unlock, and did for most of the population.** `intent_argmapping_binding_leaf`
is read by eight relations; `intent_argmapping_segment_binding` is read by nothing except the leaf,
twice, once to drive it and once to ask whether a next segment exists. So expressing the leaf as a
probe of the candidate tree retires the segment relation outright, and for a pair whose head is an
argument the probe is exactly that: the pair's coordinate, its head and its `candidate_path` are a
candidate's primary key.

**The input-field-level condition is not that, and the reason is a fan-out rather than a key
mismatch.** Such a pair is keyed on the input object type and the input field the condition sits on,
with a null argument: on the measured capture,
`StudieprogramStudieretningerFilterInput.terminfilter` with a path of `terminfilter.arstall`. That
coordinate is type-local. The candidate tree is keyed by the query field coordinate and the argument
the path descends from, and one input field occurs under every argument that reaches it, so the
pair's coordinate resolves not to one candidate but to all of its occurrences. That is what the two
arms of the existing rule are doing with the occurrence path relation, and it is why they read as
alignment: they are resolving a type-local site into path-keyed positions.

**The candidate keys do not change for it, which is the part worth being precise about.** The input
field is a candidate at each of its occurrences and needs no new shape. What the rewiring needs is
the mapping from a type-local site to those occurrences, which is a join on the parent's type and the
element name, and then the pair's remaining path has to descend from each match. Descending from a
match is where it stops being free: a candidate's path is absolute under its argument while the
pair's remainder is relative to the site, so matching them means either constructing the absolute
path in a reader or walking a segment at a time. The observed remainders are one segment, where the
walk is one join, but the general case is not bounded by that.

**That framing was wrong, and the correction dissolves the fan-out rather than routing around it.**
There are two perspectives, not one relation with an awkward case. From the field grain a path is
rooted at a query field and enters through one of its arguments. From the input-field grain a path is
written relative to the coordinate a directive sits on, and **nothing about the enclosing position
can change what follows**: an input field opens into whatever its own type opens into, so all 127
occurrences of the worst case offer the same candidates. Keying that grain by the coordinate rather
than by the occurrence is therefore both smaller and correct, and it turns the resolution back into a
probe of one key. The fan-out was never in the model; it was in a resolution that walked a relative
path through an absolute tree for no reason.

**Checked against the data, the two perspectives share one rule.** In every pair the written path
begins by naming the site's own position: all 2 argument-level pairs have `head_segment` equal to
their argument, all 4 input-field-level pairs have it equal to their field, and field-level pairs
begin with an argument of the field. So resolution is origin plus path in all three cases, and the
only thing that varies is which position the origin is.

### Slice 21: one relation, two origins, and a gate that refused the easy answer

The candidate relation now holds both grains: `graphitron_argmapping_candidate`, keyed by the graph,
a spelled origin and the path below it, with the origin discriminated as `ARGUMENT` or `INPUT_FIELD`.
An argMapping pair carries `candidate_origin` and `candidate_path`, so every site kind resolves by a
single primary-key probe and none of them walks, aligns or fans out.

**Two relations were built first and a gate refused them, which is the useful part.**
`SupertypeSignatureGateTest` scans capture tables for groups sharing a payload under different keys
and pins the roster of such groups. A field-grain candidate relation and an input-field-grain one are
exactly that, and the gate said so within a minute of the second table existing. Its roster is not a
list of blessed pairs: the javadoc calls each entry "a supertype the schema has not declared", a
decision somebody has taken or owes, and every existing two-member entry is an argument-site relation
beside its field-site twin. Adding a row would have meant this item, whose whole thesis is that those
twins are the defect, minting a fresh one and recording it as known.

**So the schema's own answer was taken instead.** `graphitron_arg_mapping_pair` already solves this
shape: nine kinds of site in one relation, a `site` discriminator, and a `use_site` spelling that is
total by construction "which is what lets it key this relation where the decomposed columns beside it
cannot, three of them being null on the sites that have no such part". The candidate relation is that
pattern applied to two origins rather than nine sites. The origin is spelled in the same coordinate
grammar, `Type.field(argument)` where a path is rooted at a field and `Type.field` where it is
relative to an input field; the decomposed columns sit beside it with the argument nullable by kind
and gated to that kind.

**Two smaller things the build insisted on, both of them right.** The parent link cascades on delete,
because capture clears a graph's partition in one statement and ordering that delete by depth would
have made every clearing site know this relation's shape. And a test fixture's helper landed between
a javadoc and its method, which `-Werror` caught as a comment attached to nothing; worth recording
only because it is the second time this session a mechanical edit has been placed correctly by
pattern and wrongly by position.

**Four tests pin what the relation claims.** That every argument is a root whatever its type, which
is the population the occurrence-path relation does not hold. That a nested candidate carries its
parent, its element name and its type. That the element closing a cycle keeps its row and says so.
And that an input field is one origin however many arguments reach it, which is the assertion that
would fail if the fan-out crept back in.

**Still nothing reads it.** The rewiring is next and is now a small change rather than an open
question: `intent_argmapping_binding_leaf` becomes a probe of this relation from the pair's two new
columns, and `intent_argmapping_segment_binding`, which nothing else reads, retires with it.

### Slice 22: the resolution reads the tree, and the first relation is declared

`intent_argmapping_binding_leaf` no longer walks. It joins the pair to the authored path's segments
and probes `graphitron_argmapping_candidate` on the origin and the prefix each segment completes,
then takes the deepest match by rank. Two enabling columns made that an equality rather than string
surgery: the candidate carries `container_type_name`, so naming what a path bound needs no step to
the parent, and the authored segment carries `candidate_path`, the prefix it completes, spelled
exactly as the tree spells its own paths.

**What that deletes.** The three-arm `headed` union that resolved the head. The alignment of two
decompositions of one descent, an anti-join inside an anti-join, matching authored segment positions
against occurrence-path ordinals. The correlated `COUNT(*)` over segments for the trailing count,
now arithmetic over a window already in the statement. The two `LEFT JOIN`s to `graphql_argument`
and `graphql_field` for the leaf's type and arity, now columns on the candidate.
`intent_argmapping_segment_binding`, which nothing else reads, is left with no reader.

**Two behaviours the old rule had that the new one had to be told.** The four reference-step sites
bind nothing whatever they spell, which the old three-arm union encoded by not admitting them and a
permissive `ELSE` let through; it is a closed list now and says so. And an argument-level condition
may name only its own argument, an input-field-level one only its own field, while the field-rooted
sites may name any argument, which is the origin's existence and needs no test.

**One behaviour deliberately changed, and it is the fan-out removal arriving as a row.** A path
written on an input type that no argument reaches used to stop at the head with the rest counted as
trailing. Not because anything was wrong with it: resolution descended through the occurrence
surface, an orphan type has no occurrence rows, and the walk ran out of relation to follow. That made
a correctly spelled path on an unreachable type indistinguishable from a misspelled one on a
reachable type, which is the distinction the trailing count exists to carry. Candidates are keyed by
the coordinate a path is written from and do not ask who reaches it, so the path now resolves and the
orphan is a defect of its own. The test that pinned the old answer states the new one.

**The writer moved into the model module.** The model tier seeds stores row by row and cannot call
into the generator, so a rule reading a writer-populated table could only have been tested there
against a second, hand-written copy of the descent, which is the drift this whole line of work
exists to remove. The rule reads only rows the store already holds and needs no schema document, so
it sits beside the other model-side derivations; capture calls it, and the fixtures call it too.

**And the relation is declared, which is the first row of the declaration model to exist.** R877
slice 1 landed `meta_grain` and `meta_relation` with the roster of undeclared relations frozen and
only ever shrinking, so a relation arriving after it cannot be undeclared. Declaring this one costs
what the model intends it to cost: the table comment is now its grain sentence and its example
verbatim, both gated against the declaration, and the reasoning that used to sit inline moved into
`rationale` under a fifteen-hundred character bound. The frozen roster falls from 283 to 282.

**Still unmeasured.** Every figure this slice could produce would be taken on the same bench that
has now twice reported a starved arm as a result, and the two enabling columns are `NOT NULL` on
relations the kept capture predates, so the arm needs its backfill extended again before it can be
trusted. The plan-instantiation counts are the honest measure here and they are the next thing to
take.

**Taken in slice 29, and the caveat above turned out not to reach them.** A plan is not made of
rows, so the counts need neither the bench nor its backfill. The three relations slice 16 named come
out at 2, 12 and 0.

### Slice 23: the scope widens to how facts are gathered, and the reason is mechanical

This item has hit the same wall four times, each time reading it as a modelling question and each
time being partly wrong about which. Whether capture may write `@node`'s defaulted key columns.
Whether the argMapping candidate tree belongs to a capture family or to `intent_`. Whether the head
of a written path can be resolved where it is split. Whether a total argMapping relation can be
captured at all. Every one of them came back to a family or charter argument, and every one of them
has the same mechanical cause underneath, which this slice went and found in the code rather than
inferring from the shape.

**Nothing any gatherer writes exists in the store until every gatherer has finished.** `FactSink`
buffers into a `Map<Table, List<TableRecord>>`, and the single `sink.flush()` in `FactCapture` is the
first moment any fact reaches the database. So a gatherer cannot read another gatherer's facts,
because there are none to read, and everything one gatherer needs from another has to arrive as a
Java parameter instead. That is what the `Expansions`, `ClasspathSources`, `attribution`,
`extensions` and `refusedSourceNames` chain is: not shared state somebody chose, but the only channel
that exists.

**The sharpest instance is the one that blocked this item twice.** `GraphitronFactCapture` is a field
of `SdlFactCapture` and is driven by five `decode.captureXDirective(...)` callbacks from inside the
SDL walk. It is not a gatherer that runs after the SDL gatherer; it is a visitor the walk calls while
walking, so it can only ever see what the walk is holding at that instant. That is why it cannot
default `@node`'s key columns from the primary key, and the reason is not that the catalog is another
corpus. It is that `sql_primary_key` has no rows yet.

**And the ordering reads backwards from how the code is arranged.** The catalog gatherer runs last,
so there is no phase in which SDL facts exist and catalog facts do not. There is one phase in which
nothing exists and one in which everything does.

**The fix is far smaller than the diagnosis suggests, which is why this belongs here rather than in a
plan of its own.** A flush is not a commit. `sink.flush()` already clears its buckets and is
therefore re-entrant, and flushing once per gatherer inside the same transaction changes nothing
about atomicity, locking or the retry path: it only makes each gatherer's rows visible to the
gatherers after it. Two properties the buffer is doing real work for both survive it. Its dedup is
within a relation, and one relation has one writer with two exceptions, `MacroCapture` writing
alongside the SDL walk it belongs to and `store_source` shared by the SDL and catalog gatherers, so
dedup does not span gatherers. And its write order is derived from the generated foreign keys rather
than a hand-kept list, so ordering across gatherers is the same derivation applied at a coarser
grain, which is exactly what `meta_gatherer_dependency` already declares.

**There is a worked example of the target shape in this repository, under a name that will have to be
settled.** `no.sikt.graphitron.facts` has a shared traversal, visitors that declare the subject kinds
they subscribe to, a sealed permit list and a total switch that makes registering a visitor without
an output slot a compile error. It touches no `DSLContext`, no `Tables` and no `FactSink`: it runs
over the assembled `GraphQLSchema` during the build, not over the store during capture, so despite
its name it is generator-side. It gets away with the shape because everything it needs is in the
object it is handed. Capture's equivalent shared object is the store, and the store is empty.

**Why this item and not another.** Three items touch this ground and none of them covers it. R877 is
about the store: the declared documentation model, grains, gatherers, dependencies, as data. It says
nothing about the code that populates them, and its `meta_gatherer_dependency` roster is a
description the runtime does not yet obey. R864 is about module boundaries and moving the existing
code below the generator; moving these packages without changing how they hand facts to each other
relocates the coupling rather than removing it, and R864's own dependency, that capture stops reading
the walk, is a different constraint from this one. This item is about the model defects that force
the cheating, and the gathering architecture is now the largest of them: it is what turned four
modelling questions into charter arguments, and the fixes this item has left to make cannot be made
without it.

**What that adds to this item's scope**, stated so the boundary against the other two stays legible:
the flush becomes per gatherer inside the one transaction, in the declared dependency order; the
hand-threaded parameters are replaced by reads of the store; `GraphitronFactCapture` stops being a
callback of the SDL walk and becomes a gatherer that runs after it; and `@node`'s defaulted key
columns become a captured fact rather than a read-time tier. What stays out: the module line R864
draws, the declaration rows R877 populates, and the naming collision between the two fact packages,
which is worth settling once both live under one tier rather than twice.

### Slice 24: the catalog leads, each gatherer's rows land before the next one runs

Slice 23 named the mechanism and argued the fix was cheap. This slice made it, and the fix is the two
lines the argument predicted plus the roster rows that say what the new order means.

**The order.** `FactCapture.capture` ran configuration, SDL, SDL verdicts, catalog. It now runs
configuration, catalog, SDL, SDL verdicts. The catalog leads the two crawlers for the reason its
corpus differs from the other's: a consumer's database schema changes on a release cadence and their
`.graphqls` files change on a keystroke, so the corpus that is stable belongs underneath the corpus
that is not. The reordering cost nothing to establish, which a read-only check made certain before
anything moved: `CatalogFactCapture` names no `graphql_` or `graphitron_` relation anywhere, its one
real store read is the partition clear it owns, and `store_source`, the single relation both crawlers
write, is claimed first-wins by whichever reaches it, so it works under either order. There was no
reverse dependency to unpick.

**The flush.** Each gatherer now flushes before the next one starts, inside the load's one
transaction. Everything slice 23 predicted survives: the sink's dedup is per relation and each
relation has one writer, so no claim spans a flush boundary; the write order is derived from the
generated foreign keys, and no foreign key runs from a catalog-family relation to an SDL-family one
or back, so no flush can strand a child ahead of its parent. Two tests pin the property the whole
order now rests on, at the sink rather than through a capture, because no gatherer exercises the read
yet and a test of a mechanism should fail for the mechanism's own reasons: a row flushed by one
gatherer is readable by the next, and a later flush's foreign key resolves against it; and a load
that dies between two flushes leaves the store untouched, which is the atomicity the single flush
used to buy by accident and now has to be stated.

**What the rosters say now.** `GraphitronFactCapture` was not in `meta_gatherer` at all, which was
the roster agreeing with the code that it is part of the SDL gatherer rather than a gatherer. It has
a row now, it reads the `sdl` corpus, and it declares one read edge, on `sdl`. That edge is the whole
argMapping story in one row: the decode reads what the transcription wrote rather than re-reading the
document. The derivation gatherer gains the matching edge, and the one declared `graphitron_`
relation is repointed from the SDL gatherer to its own. Enforcement stays out of scope: the edges are
a declaration of what the order has to satisfy, and nothing yet fails a build when it does not.

**A correction this slice made and then had to withdraw, which is worth keeping because the mistake
is an easy one.** The first draft read the graphitron gatherer as a crawler over the SDL, and drew
from that the conclusion that `@node`'s defaulted key columns cannot be a captured fact: a crawler's
rows about its own corpus may not vary with any other corpus's contents, so reading `sql_primary_key`
to resolve a default would be out of bounds. The premise is wrong. The graphitron gatherer reads no
corpus. Its family's own charter in this schema says so in as many words, that a `graphitron_` row is
a decode of a captured application and therefore a derivation whose producer runs at capture cadence
rather than a second transcription. A gatherer that reads captured rows instead of a corpus is
exactly the kind that may cross, which the schema already said of the derivation gatherer and now
says of this one too. So the edges are `graphitron` on `sdl` and on `catalog`, the two crawlers
depend on nothing, and the defaulted key columns resolve in the decode.

That in turn narrows `CaptureCorpusIsolationTest` to `graphql_`. Not a concession: "does not vary
with the catalog" was never a property the decode should have, and the historic defect that gate was
built for was a synthesized `@key` appearing as a row in `graphql_type_directive`, a transcription of
a directive nobody wrote, which still fails the gate on the relation where it is actually wrong.

### Slice 25: the decode stops being a visitor of the SDL walk

`GraphitronFactCapture` was a field of `SdlFactCapture`, driven by five
`decode.captureXDirective(...)` callbacks from inside the walk. That is the shape slice 23 named as
the cause of four charter arguments, and it is now gone: the decode is a gatherer with its own entry
point, running after both crawlers have flushed, reading the applications back out of the store.

**The store already held the applications losslessly, which is why this was smaller than it looked.**
A coordinate, a directive name and an ordinal are columns; the application's own position is three
more; and each authored argument is a row on a `_directive_arg` relation holding the literal exactly
as `AstPrinter` rendered it. And the decode asks a directive for two things only, an argument by name
and its own source location, which a census of the class confirms rather than a reading of it: one
`getArgument(name)` helper and five uses of `getSourceLocation()`, no iteration over the argument
list and no use of a position below the directive. So the input is rebuilt by parsing each stored
literal back to the value it was printed from, and the eleven hundred lines of decoding underneath
did not have to change at all. The round trip is the same one the applied-directive emitter already
ships into generated sources, so it is not a new trust either.

**What that made it: an adapter, not a rewrite.** Five readers, one per application grain, each
fetching its arguments in a single grouped query rather than one query per application, since a
consumer schema carries tens of thousands. One of the five reads a second relation: whether a field
is an input field used to be a boolean the walk carried down through two call frames, and is now
`graphql_type.kind`, read once for the whole graph. That substitution is the change in miniature. The
walk knew it because it was standing there; the gatherer knows it because it can ask.

**Three properties are better after the move rather than merely preserved.** The decode sees exactly
the applications that won their coordinate claim, where before it ran ahead of the claim and could
decode one the transcription then quarantined. Its ordinals are the ones the transcription recorded,
rather than a second count that has to agree with them. And a literal that will not parse back now
lands on the quarantine relation that already existed for a literal that does not fit its declared
shape, which is the same tolerance rule reaching one step further out.

**What is pinned.** `DirectiveLiteralRoundTripTest` asserts print-then-parse idempotence over every
literal a capture stores, on a fixture reaching the shapes that could plausibly fail: escapes and a
block string, a negative and an exponent, an enum against a string of the same spelling, and a list
of objects nested two deep, all authored on an undeclared directive because that is the population a
round trip has no schema to lean on for. The existing suite carried the rest: 4117 tests over the
generator's tiers pass with the decode moved, which is the fidelity claim measured rather than
argued.

**What is still on the SDL walk and should not be.** `MacroCapture` and the walk itself write three
`graphitron_` relations between them, the two synthesis-provenance relations and
`graphitron_field_navigation`. Those are the expansion's record of what it did while it was doing it,
so they are not reconstructible after the fact the way a directive application is, and moving them is
a different question from this one. The gatherer roster says the graphitron gatherer owns that
family; today it owns all of it but those three.

### Slice 26: the census of who actually reads the expansion's rows

The remaining graphitron semantics in the SDL walk is `MacroCapture`, the `@asConnection` expansion,
and it is different in kind from the decode that just moved: it does not merely read the corpus, it
*writes into* `graphql_type`, `graphql_field` and `graphql_type_declaration`. So purifying the walk
forces a modelling decision rather than a mechanical move. A synthesized coordinate cannot stay in
`graphql_` written by the graphitron gatherer, that being two gatherers on one relation, so either
the coordinates move to `graphitron_` and readers that want the expanded set read a union, or the
walk keeps them and stays impure.

The reason to prefer moving them is not purity for its own sake. `MacroCapture`'s own comment records
the defect the current placement produces: for the field type the expansion *rewrites*, "the
expression the field was written with survives only in that relation's own text column, and no
anti-join recovers it". Inverted, the problem disappears. `graphql_field` holds what the author wrote
because that is what a transcription is, the rewritten expression sits in the graphitron relation,
and "as authored" against "as graphitron sees it" becomes two reads instead of one read plus an
anti-join that does not work. It also dissolves a constraint that reads as arbitrary today: an
expansion inside a crawler may read only that crawler's corpus, which is why `@asConnection` may run
there and federation's key synthesis may not, having been exiled to `intent_` because nodehood needs
the jOOQ metadata. Move the expansion to the gatherer that reads no corpus and the two belong in the
same place.

**The cost was worth measuring before committing, because the static count is frightening and wrong.**
The three relations are named directly by 35 of the schema's 115 views and imported by 16 main-source
Java files across the reactor. Counted that way the union looks like a migration of a third of the
schema.

**Measured, it is eleven views.** The method: capture a schema, then un-expand the store in two
stages and see which views answer differently. Stage one puts every carrier field's authored type
expression back, which `graphitron_field_synthesis.authored_type_sdl` holds exactly. Stage two
deletes every row about a minted type, the minted names coming from
`graphitron_type_declaration_synthesis`, children before parents in the reverse of the write order
the foreign keys already derive. Materializations refresh between stages so a registered target
cannot answer from a stale copy. On the sakila example schema (4547 lines, 33 `@asConnection`
applications, 40 minted types, 20 rewritten carrier fields), 93 of the 115 views hold rows and
**11 of them change**:

[cols="1,1,3"]
|===
| Relation | Rows before / after | What it is about

| `intent_connection_element_type` | 22 / 2 | the Connection to element resolution, which is the expansion's whole subject
| `intent_resolved_field_demand` | 942 / 822 | which fields the emitted surface demands
| `intent_field_navigated_type` | 959 / 839 | total over `graphql_field`, so every minted field has a row
| `intent_field_scope_table_live` | 409 / 351 | the table a field's scope resolves to
| `intent_field_exemption_rule` | 212 / 132 | which coordinates a rule exempts
| `intent_resolved_type_demand` | 287 / 247 | which types the emitted surface demands
| `intent_type_demand` | 193 / 153 | the demand rule underneath it
| `intent_field_participant_scope_table` | 152 / 116 | the participant side of the same scoping
| `intent_field_column_table` | 130 / 116 | the table a field's column sits on
| `intent_field_column_scope_live` | 281 / 267 | the column scope itself
| `intent_field_payload_producer` | 139 / 139 | changes content without changing count
|===

**Why the gap between 35 and 11 is structural rather than a shortfall of the fixture.** A minted type
is a plain object type carrying machinery fields and no directives at all: no `@table`, no `@service`,
no `@field`, no arguments. So every directive-driven reader is blind to it by construction, and that
is most of the 35. The eleven that see it are, without exception, readers about the *emitted* surface
rather than about what the author wrote: what the generator must produce, what it navigates through,
what table a field resolves to. That is exactly the population a union view is for, and eleven
readers is a slice rather than a migration.

**What the census turned up in passing, and it is worth deciding rather than inheriting.** None of
the four author-facing LSP surfaces that read these relations, the declarations, the type usages, the
descriptions and the diagnostics, filters the synthesis provenance. So the minted `Connection` and
`Edge` types are indistinguishable from authored ones there today. Whether that is wrong differs per
surface: a minted type's declaration position is the carrier's own `@asConnection` application, so a
jump to it lands somewhere defensible, while offering the author a completion for a type name nobody
wrote is harder to argue for. Purification decides it by construction instead, which is the argument
for it that has nothing to do with gatherers.

### Slice 27: the navigation rung that is already redundant, and the seam the union goes through

The sequencing this item announced was to lift `graphitron_field_navigation` out of the SDL walk
first, as the free step, and take the expansion after. Measuring the relation inverted that, and the
measurement is worth keeping because it is also the clearest evidence for what purifying the
transcription buys.

**The navigation relation resolves three rungs in precedence.** The expression the author wrote where
a macro rewrote the field's type, read out of `graphitron_field_synthesis`; below it the structural
connection, `intent_connection_element_type` keyed by the field's current named type; below that the
field's own named type. The relation's comment says the upper two agree wherever both fire and keeps
the precedence anyway, "because the authored expression is the more direct evidence and because a
macro that expanded to something other than a connection would need it".

**Measured on the sakila example schema, the top rung is already carrying nothing.** 959 navigation
rows, of which 20 answer `AUTHORED_EXPRESSION`, 6 `CONNECTION_ELEMENT` and 933 `NAMED_TYPE`. Every
one of the 959, the 20 included, is reproduced exactly by
`coalesce(connection_element(named_type), named_type)`. Zero disagreements. So the rung that forces
this relation to be written where the parse is, and therefore forces a writer to sit inside the SDL
walk, is a defence against a hypothetical second macro rather than a rule any schema exercises.

**Which is why the expansion has to move first.** The two rungs that remain both turn on the
connection-shape rule, and that rule has to run over the population the generator emits, authored
connections and minted ones alike. Once the minted types leave `graphql_type`, that population is a
union, so the shape rule crosses two families and the navigation rule sits on top of it. There is no
version of the navigation lift that is stable before the union exists; doing it first would be work
thrown away. What the measurement does buy is that the lift, when it comes, is a two-arm rule over
one relation rather than a three-rung resolution needing a stored SDL expression parsed in Java.

**The seam, which is what this slice actually lands.** `intent_expanded_type` and
`intent_expanded_field` are the names a reader uses for the population the generator works with:
authored plus minted for types, and for fields the same plus the macro's rewrite at a coordinate both
populations hold. Today both are exact projections of `graphql_type` and `graphql_field`, because the
expansion still writes into those relations, so this slice changes no answer anywhere: 4117 tests
pass unchanged. What it does is put the seven direct readers that want the expanded population onto
the union name, so the arms can move underneath them without a reader diff. The seven are
`intent_connection_element_type`, `intent_field_column_scope_live`, `intent_field_exemption_rule`,
`intent_field_payload_producer`, `intent_field_scope_table_live`, `intent_resolved_field_demand` and
`intent_type_demand`; the other four of the census's eleven reach the expansion through one of these
rather than naming the base relations themselves.

Both union views are declared in `meta_relation` rather than added to the undeclared roster, the
declaration gate being explicit that a new relation owes a declaration. Their grain rows are
`expanded-type` and `expanded-field`, and their owner is the derivation gatherer, which is what a
relation whose arms will span two families is.

### Slice 28: the expansion leaves the transcription

`graphql_type`, `graphql_type_declaration` and `graphql_field` now hold what the author declared and
nothing else. What `@asConnection` mints is `graphitron_minted_type`, its per-carrier
`graphitron_minted_type_site` and `graphitron_minted_field`; what it rewrites is
`graphitron_field_synthesis`, which flips direction and holds the macro's replacement where it used
to stash the authored expression the transcription had overwritten. The two union views the previous
slice put the expanded-population readers on become real unions, so no reader moved twice.

**The expansion also changed hands.** It was a component of `SdlFactCapture`, called from inside the
walk with the parse in hand. It is now a stage of the graphitron gatherer, driven by the decode's own
`graphitron_connection` rows joined to the carrier's transcribed field. Everything it used to need
from the walk is a column: the element type and its nullability come from the carrier's authored type
expression, the position from the application's own row with the field's as fallback, and the
author-declared-name rule, which used to be a lookup in the registry the walk happened to be holding,
is a query against the type coordinates. The gatherer now has three stages that read each other
through the store, decode then expansion then navigation, which is the whole architecture in one
class.

**What the defect inversion buys, stated concretely.** `MacroCapture`'s own comment used to record
that for the field type the expansion rewrites, "the expression the field was written with survives
only in that relation's own text column, and no anti-join recovers it". Both readings are now plain
rows at the same coordinate: `graphql_field` for what the author wrote, `graphitron_field_synthesis`
for what the generator reads, `intent_expanded_field` for the two resolved. `MacroCaptureTest` asserts
all three on one fixture, which was not a thing that could be asserted before.

**The navigation rule collapsed with it, as slice 27 predicted.** Its top rung is gone, and with it
the last reason that rule had to be computed where the parse was. It is now two rungs stated as one
statement over the union inside the gatherer, and `SdlFactCapture` loses both the rule and the
connection-shape reading it maintained for it, about a hundred and thirty lines.

**Four readers wanted the union rather than the transcription, and the suite found every one.** The
classification domain's own materialisation, which selected its members out of `graphql_type` and so
silently stopped admitting minted shapes; the mutation seat's connection-return arm, which asks
whether a field's named type is a connection; the demand sweep's coverage gate, which counts the
domain's field coordinates; and the seeded-store harness, which turned out to carry a second spelling
of the navigation rule, string surgery over the authored expression and all. That last one is the
"five spellings" hazard this schema warns about, found by the change rather than by a census.

**One key was lost and it is worth naming.** `intent_type_domain` referenced
`graphql_type_coordinate`, which was exact while the transcription held every name in the graph. Its
population is now the union, and a view is no key's target, so the relation keeps only its graph key.
The alternative was minting SDL coordinates for types the SDL does not declare, which would put the
graphitron gatherer back inside a relation the SDL crawler owns and buy the constraint at the price
of the property the whole slice is for.

### Slice 29: the criterion slice 16 set, measured

Slice 16 stated what would show its line of work had landed, as three instantiation counts in one
plan of `intent_node_id_decode` against the 106, 84 and 38 it had just taken. Slice 22 closed by
saying those counts were the honest measure and the next thing to take. Six slices went past without
taking them. This one does.

**The instrument, and why it costs nothing.** An empty in-memory store on the shipping DDL, one
`EXPLAIN SELECT COUNT(*) FROM intent_node_id_decode`, and a count of `PUBLIC.<name>` references in
the returned plan text per relation. No kept store, no consumer capture, no backfill. That is not a
convenience, it is the claim slice 17 made when it called these numbers properties of the schema
rather than of a run, and it is why slice 22's caveat about a starved arm never applied to this
measurement: an arm can starve of rows, and a plan is not made of rows.

**Which is a claim this slice was able to check rather than repeat.** The same probe was run against
two earlier states of the tree, each in a detached worktree, and compared to what those states
published at the time.

[cols="4,2,2,2"]
|===
| relation | slice 16 published | probe at that commit | probe at slice 17's commit

| `graphitron_argument_path_segment` | 106 | 104 | 34, published as 34
| `graphitron_arg_mapping_pair` | 84 | 82 |
| `intent_input_occurrence_path_step` | 38 | 36 |
| `sql_node_metadata` | 26 | 26 |
| `graphql_argument` | 26 | 26 |
| `sql_routine_parameter` | 4 | 4 |
| `jvm_method_parameter` | 4 | 4 |
|===

Four figures exact, three low by two, and slice 17's single published number reproduced to the digit.
The constant offset of two on exactly the relations that carry an outermost reference is a difference
in what the two counts admit, not a disagreement about the plan. The instrument is sound and the
figures below are on the same footing as the ones they are compared against.

**The counts today.**

[cols="4,2,2,2"]
|===
| relation | slice 16 | slice 17 | today

| `graphitron_argument_path_segment` | 104 | 34 | **2**
| `graphitron_arg_mapping_pair` | 82 | 92 | **12**
| `intent_input_occurrence_path_step` | 36 | 40 | **0**
| `intent_argmapping_pair` | 80 | | 10
| `graphql_argument` | 26 | 26 | 0
| `sql_node_metadata` | 26 | 26 | 9
| `sql_routine_parameter` | 4 | 4 | 4
| `jvm_method_parameter` | 4 | 4 | 4
| total relation references, this method | 473 | 419 | 128
| plan text, characters | 623 413 | 511 237 | 139 755
|===

**Against the criterion: two met and one missed, and the miss is worth stating as a miss.** The
segment list was to be read a handful of times rather than a hundred, and it is read twice. The
occurrence-path step relation was to fall once the correspondence had a name, and it is gone from the
plan entirely, as is `graphql_argument`. The pair table was to be in the low single digits and it is
at twelve. That is a fall of eighty-five percent and it is not what the criterion said.

**And the trajectory says something slice 17 did not report about itself.** Between slice 16 and
slice 17 the segment list fell from 104 to 34, which is the number slice 17 published, while the pair
table rose from 82 to 92 and the occurrence-path step relation from 36 to 40. The head column moved
instantiations rather than removing them, and slice 17's own conclusion, that the change bought no
time because the nodes it removed were index probes into a 202-row table, reads better for knowing
the count went up somewhere else at the same time. What removed them was the candidate tree of slices
18 through 22, which is where the three named relations fall to 2, 12 and 0.

**The totals are internally consistent and not comparable to the pair slice 17 quoted.** That slice
reported the plan's total relation references falling from 818 to 674, and this method counts 473 at
the same commit, so the two methods admit different things. The 473 to 128 fall stands on its own
arm; the 818 and 674 stand on theirs; neither should be quoted against the other.

**What this does not measure.** Not time. Slice 17 already established at this exact relation that a
smaller statement and a faster one are different achievements, and slice 4's census said the
expansion count predicts plannability and nothing else. What 128 relation references and a plan of a
hundred and forty thousand characters buy is a statement the planner can hold, which is the
precondition for costing it, not a reading of what it costs.

### Deferred: the registration precondition

Whether a rule earns a `meta_materialize` row before anything reads it. No other item holds it, and
both reasons the first draft gave for deferring it have expired: R848 reached Done on 2026-08-28, so
there is no in-review item to avoid adjudicating, and the slice 0 result it was waiting on has been
taken.

**It stays deferred anyway, for a reason the first draft could not have given: its stakes fell by two
orders of magnitude.** It was deferred while governing 95% of a four-hour refresh, which made
deferring it the weakest thing in the plan. It now governs 67.4% of a 43-second pass, which is 29
seconds. A rule about when a registration is earned is still worth having, and the argument for it is
now about coherence and about not repeating the mistake at the next consumer scale, not about hours.
That is a different item's argument to make, on a different axis, and it should be filed on its own
terms rather than carried here as a promise this item's outcome no longer depends on.

**And it is the wrong shape for where this item is now pointed.** A precondition asks when a
registration is earned, which presumes registrations. The target above is a store with none, at which
point the question dissolves rather than being answered. The precondition is worth having as the rule
for whatever survives that target, and it should be written when the survivors are known, not before.

### Not in this item: a fresh consumer capture

The first draft said no session working from this repository could price the shipping DDL against a
consumer schema. That was too strong and the audit's section 10 does exactly that, so the boundary
has moved and is worth restating precisely, because it is the difference between two things the first
draft ran together.

**Refreshing a captured schema needs no consumer machine.** The kept store holds the captured base
facts, and a refresh is those facts against a schema, which is a file in this repository. So any
DDL's refresh pass and any DDL's reads can be priced from the kept store by anybody who has it, which
is what every figure in this item now rests on.

**Capturing a schema does.** It needs the consumer's sources, its catalog and its build. So nothing
here tests capture itself: if a change to the capture family writes a fact differently, no
measurement in this item would see it. The bound on that is stated in section 10, which is that the
shipping DDL declares exactly one base table the kept store predates, every other table matches
column for column, and every registered target reproduces its row count. That bound is what licenses
the figures; it is not a substitute for a capture, and a slice that changes what capture writes owes
one.

The recipe for taking that capture, inherited from the payload-verification item, is worth keeping
written down either way: `mvn -X` for the per-registration tier, a pinned store directory, and the
discharge rule that a position's name is emitted before its `DELETE`, so the one that never returns
is the one that gets named.

## Tests

The gates that already govern this ground, and what each will say when the slices land:

- `DerivedReadCostTest` refuses a change that costs some reader more than it saves. A supertype that
  removes expansion should clear cells rather than add them. Three rows of `KNOWN_NON_MONOTONIC` sit
  on relations the slices rebuild and are the ones to re-examine when they land:
  `intent_argmapping_pair|intent_argmapping_bound_parameter_type`, which the set's own comment
  attributes to the pruning an inlined body offers and a table cannot, and
  `intent_spelled_table|intent_carrier_routine_hop` and
  `intent_spelled_table|intent_mutation_routine_seat`, which it attributes to the unindexed named-type
  join slice 2 stores. Whether any of the three becomes removable is for the Done gate to measure and
  is not a prediction inherited from the audit, which measures plan sizes and wall clock and says
  nothing about scan-count monotonicity. Of the ten rows that remain, eight are the instrument's own
  four-scan floor on `intent_field_reference_step_hop`, one is the node-id instruction's, and one is
  the `intent_errors_field` pair, which shares its comment with the argmapping row named above; no
  slice here touches any of them.
- `MaterializeRegistryGateTest` holds the index decisions on registered targets. Slice 2's index sits
  on a captured base table, outside that gate's scope, which is the gap the surviving named-type
  index item documents; take its doctrine rather than inventing one.
- `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension` needs a case for each new
  supertype table.
- `CaptureCorpusIsolationTest` is the gate the gathering order answers to. It captures one registry
  with the jOOQ catalog and once without and requires the `graphql_` and `graphitron_` rows to be
  identical, which is the crawler rule as a differential. Running the catalog first does not weaken
  it and is exactly the change that could: a crawler reading a corpus it does not answer for now has
  rows to read where before it had none.
- `GathererHandoffTest` pins the two properties the per-gatherer flush rests on: an upstream
  gatherer's row is readable by the one after it, and a load that dies between two flushes publishes
  nothing.

What this item owes that no gate holds today:

- **Row-identity anchors on every repointed view.** A view that reads a supertype table must return
  what it returned when it reconstructed the supertype by union. This is the one class of defect the whole item can
  plausibly introduce and it is cheap to pin, and the figures to pin against are measured rather than
  assumed: on the kept store, with all four repoints applied, `intent_spelled_table_live` returns 313
  rows before and after, `intent_argmapping_pair_live` 108, `intent_class_member_slot` 4198,
  `intent_field_navigated_type` 8408, and `intent_field_accessor_hop` 21287. Equality on every one of
  those is the anchor.
- **A reachability check over `meta_materialize`.** The mechanical form of rung 5's precondition:
  every registered target is reachable from the consumer read set, or states why not. It mirrors
  `everyTargetIsIndexedOrStatesWhyNot` in shape. The walk itself has been taken and its current answer
  is twelve of twenty-two unreachable; whether it ships as a gate is the deferred policy question
  above.
- **A gate over the supertype signature, which is the one this item most owes.** Landed as
  `SupertypeSignatureGateTest`; slice 9 records what it found and where it departed from the design
  below, which is that the exemption list became a rule about provenance and the member threshold
  came down from three to two. The design as specified: all three parts read
  off the booted store the way `MaterializeRegistryGateTest`'s axes already do: subtype sets from
  grouping capture tables by attributes outside their own primary key, confirmations from view bodies
  carrying a `UNION` over three or more members of a set, **and the set's own attributes named by that
  view**. The third part is not a refinement to add later. Without it the scan reported six union sites
  where three are real and four where one is, because membership overlaps and a view unioning the
  reference-step tables for `class_name` was credited with reconstructing `table_ref` too. A gate
  carrying that defect would fire on a supertype the view does not reconstruct and would attribute a
  real reconstruction to the wrong set, which is worse than no gate.
  Even with the third part the check is name matching over a view body, not proof that the attribute is
  projected from the unioned arms, so the gate owes either a tighter check or a declared exemption
  list. It needs the exemption list regardless, because two candidate sets are being left alone
  deliberately and the `sql_*` reference groups are not subtype sets at all. That is the point rather
  than a weakness: it turns adding the eleventh sibling into a decision somebody records, which is
  exactly what nobody did while eight `*_arg_mapping_pair` tables accumulated.
- **A gate over the access form.** The reachability walk's soundness rests on every `intent_` read
  going through a jOOQ constant, and that is checkable mechanically where the walk's result is not.
  One shape it has to handle, which is the case for building it rather than an obstacle to it: five
  main-source classes reach relations by string through `table(name(...))`, and one of those sites
  names nothing literally, `StoreProse` building the name from a variable bounded to `metaRelations`
  at its call site. A gate that refuses a literal `intent_` name would pass a computed one, so the
  check belongs on what the argument can be rather than on how it is spelled.
  Listed here even though the section it supports is deferred, and lower priority than the one above,
  since the walk is now evidence for a coherence argument rather than for a cost one.

No wall-clock assertion, for the reason `DerivedReadCostTest` already states: a duration is not a
build assertion, and every timing in this item is research evidence rather than a ratchet.

## What this item does not do

It does not delete the twelve unreachable targets or their relations. They are work under
construction, they appear in two to five test sources each, and their registrations may be right the
moment their readers land. The question is when a registration is earned, not whether the relations
belong.

It does not reopen the two payload registrations that landed 2026-08-28, and the case for leaving
them alone is now measured rather than deferential: without them the pass on this store is 588.2
seconds and with them it is 43.2, all of the difference landing on the two positions that dominated
the old capture. That is the largest single measured improvement anywhere in this subject and it was
a registration, which this item's own lever ordering puts last. The ordering is a claim about what
should be tried first, not a claim that a registration is never the answer, and nothing here proposes
undoing a pair that took two positions from 464.5 and 82.3 seconds to 0.2 and 1.3.

It does not build a consumer-scale fixture. Nothing in this repository captures a schema of that
size, and a fixture that did would be a wall-clock gate, which the build-guardrail item owns.

And it does not promise a scoring function over the register. One was built for the dissolved cut-set
item, failed its own pre-committed gate, and was deleted; the audit records why a static reading of
the view definitions cannot rank the register.

And it does not re-argue the refresh. That axis is closed on the tree, section 10 measures it, and a
slice here proposing to make a registration's refresh cheaper would be work against a 43-second pass.
Anything this item does to the register it does for the reads above it or for the coherence of when a
row is added, never for the pass.

## Superseded items

Dissolved against this item and the audit, all on 2026-08-28. Each is named by subject rather than by
id, because the ids become gaps and the audit's section 8 carries what each established.

- The consumer-capture item, whose two registrations had already landed and whose remaining work was
  four transitions to record delivered code. Its framing was that the hour is a refresh cost and the
  fix is two registrations, and this item dissolved it as the purest example of the premise being
  argued against. **That judgement was wrong on the facts and is corrected here rather than left
  standing.** The refresh pass on the shipping DDL is 43.2 seconds; with those two registrations
  demoted it is 588.2. They were the fix, they were a refresh cost, and the item that said so was
  right about its own subject. What this item retains against it is narrower: nothing tried the four
  rungs above a registration on those two relations, so the pair shows a registration worked and not
  that it was the only thing that would have.
- The payload-verification item, filed as the consumer-capture item's escape hatch for an unpaid
  measurement. The measurement has since been taken, and the audit's section 10 carries it; the
  recipe it contributed for a reportable long capture is recorded under "Not in this item" because
  the boundary it drew turned out to be in the wrong place.
- The write-payload read-cost item, whose central question is answered and whose reader-count premise
  is dead, the count being zero across the family.
- The node-id decode-read item, whose lever question this item's ordering answers. Its second half,
  that no gate holds a figure over that read, is an obligation inherited here.
- The field-column-table inlining item, whose remaining question was whether a residual still earns
  work. It does: `intent_field_column_table` costs 9.2 seconds to read with the whole register in
  place and refuses a 120-second budget without it, so it is one of the seven residual relations with
  no cause named yet.
- The expression-keyed-join item, which is rung 2 above, filed narrowly against one of the two known
  instances.
- The inline-multiplicity reporter item. Plan size measures expansion directly, so the metric is
  superseded rather than repaired.
- The DDL performance-claims item, now a systematic consequence rather than a single catch.

**Not dissolved, and why.** The per-refused-row reader item is a caller-side loop in Java, at two
call sites the item names itself, not a store shape, and nothing here touches it. The `graphql_field` named-type index item
carries the doctrine for indexing a captured base table, which rung 1 needs rather than replaces. The
`meta_relation_reference` item is a measured, self-contained fix. The view-read census and bridge
closure is a gate, and the layer violation above is live evidence for it. The build wall-clock
guardrail is independent.

## What a reviewer should press on

Three places where this plan is weakest, named so the gate does not have to find them.

**This item's thesis has now been measured against, and it is half confirmed.** With zero
registrations and the shape fixes applied, the worst relation in the consumer read set goes from
refusing a 120-second budget to 1.90 seconds, which is the strongest evidence anywhere here that the
register is not what those reads need. But relations the register was carrying got worse in the same
arm, and one of them went from a fifth of a second to refusing the budget. So "capture the supertype
and store the key and the register can go" is directionally supported and is not yet true, and this
item should be read as naming the residual rather than as claiming the register can be retired. A
reviewer should press on whether the residual is scoped tightly enough to be work rather than a hope:
the honest position is that four confirmed omissions exist, the measured arm fixed two of them, three
residual relations are predicted to move when the largest is fixed, and seven residual relations have
no cause named at all.

**The read workload is a proxy, biased upward.** Every read figure here is `SELECT count(*)` over one
of the 39 relations a consumer names. That is the right set of relations and the wrong set of queries:
a real read carries predicates that can prune where a count forces the whole relation. The figures are
therefore upper bounds, sound for comparing arms because the same statement runs in each, and not
sound as a claim about what any consumer pays for a given relation. Before any registration is retired
on read evidence, the workload owes a faithful extraction from a traced `generate` run, and a reviewer
should treat that as a precondition on the target rather than a refinement of it.

**The evidence is one store, one schema, one investigator.** Every figure comes from a single capture
of a single consumer schema, and the reachability walk is a static analysis of the shipped DDL
against main sources rather than anything the build checks. It has not been reproduced by a second
party. Each lever was measured against a named alternative, which is the standard the audit sets, but
nothing here has the three-capture spread discipline R848 established, and the read-side figures in
particular are single readings on a shared machine. The separations that carry weight here are large
ones, over a hundred-fold in two places; the single-digit differences in the same tables should not
be read as measurements.

**The reachability walk rests on a claim about how consumers read the store, and the first draft
stated that claim too strongly.** It said no raw-SQL relation name appears in any main source. A
third access form does exist, jOOQ's `table(name(...))`, in five classes. Every site resolves to a
`meta_*` relation, `store_graph` or `INFORMATION_SCHEMA`, so no `intent_` relation is reached that way
and the walk stands, but the form is there and nothing stops it being pointed at a derived relation.
One of the five names nothing literally, `StoreProse` building the name from a variable, which is the
shape a grep-shaped gate cannot see. The audit is corrected. All of this makes the gate more worth
building rather than less, and whether it belongs in this item is a fair question for the gate: it is
the one claim in this whole subject that a build could hold, and everything else here is research
evidence.

## Reviewer findings

### Round 1 (2026-08-28, Spec -> Ready, reviewer session 01P2HFCFzA3YiKbaLjgXzet7)

Verdict: withhold. Two blocking findings on question one, one on question two, one traceability
finding, one figure to reconcile.

*What was checked and holds.* Every test symbol the item names exists under the name it gives:
`DerivedReadCostTest` and its `KNOWN_NON_MONOTONIC` set, `MaterializeRegistryGateTest` and
`everyTargetIsIndexedOrStatesWhyNot`, `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`.
So do the DDL objects the two key slices land on: `jvm_method`, `graphql_field.named_type`,
`graphitron_field_synthesis`, and the schema's thirty-five existing `GENERATED ALWAYS AS` columns, the
count the audit cites. Section 1's table sums to 15477.19 against its stated 15477.1, the ten
unreachable positions to 14759.5 as stated, and positions 14 to 18 to 15382.5 as stated, so the pass
arithmetic is internally sound. Each surviving item the supersession section says is untouched exists and is untouched; the four
citation redirects landed and read correctly. The dissolution itself is well argued and the decision to
file the evidence as a dated audit rather than in a file that dies at Done is right, and worth keeping
whatever happens to the findings below. The diagnosis, that a comparison between "evaluate" and "store"
cannot report that a third option was better, is the strongest thing in the item and I am not disputing
any of it.

**Finding 1 (question one: is the stated outcome reachable). "What changes when this lands" promises a
capture in the tens of seconds, and no slice in this item is measured against the positions that carry
99.4% of the pass.** Section 1 puts 15382.5 s in positions 14 to 18. Four of those five are in the
unreachable subgraph, so the only lever the item names over them is the registration precondition, which
this file explicitly defers. The reachable ten carry 717.6 s, so perfect success on every non-deferred
slice leaves over four hours on the audit's own store, and on the shipped DDL leaves the predicted 7126 s
of which the item's own section 4 citation says over 98.7% is positions 17 and 18. Slices 1 to 5 name
`intent_spelled_table`, `intent_argmapping_pair`, `intent_carrier_data_field`, the accessor hop and the
named-type sites; none of them is a payload or filter-role position, and nothing in the audit measures a
grain fix against one. There may well be a real claim here, that cutting expansion at the two grains cuts
the refresh of the unreachable views too, since section 3 shows five timed-out relations completing with
nothing materialized. But the item does not make that claim, and it is the whole distance between the
promised outcome and the slices. Resolve it one of three ways: state and evidence the claim that the
grain and key fixes reach positions 14 to 18; or restate what changes when this lands as what the slices
actually deliver, with the hours left to the deferred question; or pull the deferred slice into the item.
The third is now open in a way it was not when the item was drafted: the stated blocker, that R848 should
not be adjudicated while in review, cleared when R848 reached Done on 2026-08-28.

*Author's response.* Resolved, by none of the three routes offered, because the finding was right
about a bigger thing than it claimed. The promised outcome was not merely unreached by the slices; it
was on the wrong axis. The kept store re-priced on the shipping DDL refreshes in 43.0 seconds, so
positions 14 to 18 are not a gap this item has to close and the deferred slice is not the way to
close it: the tree closed it, with the cold-refresh split and the two payload registrations, and the
audit's new section 10 measures each contribution separately. "What changes when this lands" is
restated on the read side, which is the axis every slice was always measured on, and the target is now
a store that materializes nothing rather than one that refreshes faster. The finding's underlying
complaint, that the outcome and the slices were measured against different things, is what the rewrite
fixes, and it is now fixed with an arm rather than an argument: the outcome is measured by emptying the
register, applying the slices this item knows about, and timing the consumer read set. That arm comes
out worse than the register today, which is stated in the item rather than smoothed over, and the
residual it leaves is what slice 3 is scoped by.

**Finding 2 (question one: viability). Slice 0 gates the design of everything below it and runs against an
artifact this repository does not contain, with no stated way for an implementer session to obtain it.**
"Nothing below is designed until they are answered", and both determinations are reads "against the kept
2026-08-27 store". The audit says only that the store file is kept, 99 MB, with a SHA-256 recorded
alongside it; where it is kept, and by whom, is nowhere in either document. Meanwhile the item's own
"Not in this item" section establishes that no session working from this repository can take a fresh
consumer capture. Taken together, an implementer picking this up may be unable to start, which is a
viability question rather than a detail. Say where the store lives and how a session gets at it; or state
that slice 0 belongs to the session that holds it and cannot be handed off, and what the item does if that
session is not available. A reproduction recipe good enough to re-take the capture elsewhere would settle
it, but the item argues that is out of reach, so the location is the answer that is left.

*Author's response.* Resolved by taking slice 0 rather than by documenting a hand-off, so there is no
determination left for an implementer to be blocked on. The finding's premise turned out to be wrong
in the useful direction: the item claimed a capture on the shipping DDL was out of reach, and the
distinction it had missed is that refreshing an already-captured schema needs the kept store and a DDL
file, not the consumer's machine. The whole pass is therefore reproducible by anyone holding the
store, which is what the audit's section 10 does and documents. The store's location, its provenance
file and its recorded SHA-256 are stated at the end of that section anyway, and "Not in this item" is
rewritten to draw the line where it actually falls, between refreshing a captured schema and
capturing one.

**Finding 3 (question two: architecture fit). The lever order contradicts the one in
`docs/architecture/explanation/fact-model.adoc`, and no slice amends that page.** The page orders three
rungs: a captured fact top, a registration middle, and "a rewrite is the last rung, because it usually
changes nothing the planner cares about". This item orders five, with rewrite fourth and registration last
behind a new reader precondition. That is a doctrine change, not the gloss the item gives it ("the top rung
was never actually tried, so the ordering was doctrine rather than practice"): it demotes the middle rung
below the one the page calls last, and it adds two rungs the page does not have. The item is right on the
merits as far as the evidence goes, but if it lands as written the tree carries two orderings and the one a
contributor finds first is the page. Name the page edit as a slice and say whether the precondition sentence
goes in with it or waits for the deferred policy question, or say why the page stands as written. Related:
the item's own claim that the page "already states a hierarchy" should be narrowed to which part of it
survives.

*Author's response.* Accepted in full. The page edit is now slice 5, scoped to three edits and with
the two things it must not touch named. "The lever order" is rewritten to say which part of the page
survives (its top rung, and its reasoning about rewrites, which the audit's two refuted rewrites
confirm) and which part this item changes (where a registration sits, plus a rung the page does not
carry at all). The gloss the finding objected to is gone, and the change is labelled a doctrine
change. The finding's sub-question, whether the precondition sentence ships with the page edit or
waits for the policy question, is named in the slice as its one open question with a recommendation
rather than left implicit.
**Finding 4 (traceability). "The audit predicts four named regressions in `KNOWN_NON_MONOTONIC` become
removable" is a prediction the audit does not contain.** Neither the audit nor this file mentions
monotonicity or that set anywhere else, and the four pairs are not named. A reader can guess at them from
the two grains, the set's rows on `intent_argmapping_pair` and the two on `intent_spelled_table`, but a
guess is not what the Tests section should hand an implementer, and the count is a figure the Done gate
would be checked against. Name the four pairs in the item, or drop the count and keep the direction.

*Author's response.* Accepted, taking the second option and then the first as well. The count is
dropped because the audit does not contain it, and three pairs are named instead: the ones sitting on
relations the slices rebuild, with the set's own comments quoted for why each is there. The Tests
section now also says explicitly that whether any of the three becomes removable is for the Done gate
to measure rather than a prediction inherited from the audit, since the audit measures plan sizes and
wall clock and says nothing about scan-count monotonicity.
**Finding 5 (figure to reconcile, minor). The audit's two readings of positions 1 to 16 disagree by about
30 seconds.** Section 4 states 6293 s cold against 90.8 s analysed, from which the 69-fold ratio comes.
Section 1's table sums to 6262.6 s over the same sixteen positions, which is where this item's "removes
about 6172 seconds" comes from (6262.6 minus 90.8), so the item is consistent with the table and the audit
is not consistent with itself. Half a percent changes no conclusion, but the audit is the artifact that
outlives every file citing it, and a figure a later reader cannot reconcile is exactly what an audit is for
avoiding. Say which reading is the pass and where the other came from.

*Author's response.* Accepted, and settled from the capture's own log rather than by choosing between
the two readings. The per-registration lines were re-summed: positions 1 to 16 are 6262.6 s, all
twenty are 15477.2 s, and positions 14 to 18 are 15382.5 s, so section 1's table is the pass and is
internally consistent. The 6293 exceeds the pass by 30.4 seconds, which is exactly position 8 counted
twice, so it is arithmetic and not a second measurement. The audit now states this where the 6293
stood, and the 69-fold ratio is unchanged.
*Fixed in passing, per the reviewer-fix rule.* "The two per-refused-row reader items" was a stale count:
there is one such item, R812, which names both call sites in its own body. Corrected in the supersession
section; nothing else in that paragraph changed.

### Round 2 (2026-08-28, Spec -> Ready, reviewer session 01P2HFCFzA3YiKbaLjgXzet7)

Verdict: withhold. One blocking finding on question two, one small correction beside it. Every round 1
finding is addressed, and two of them were addressed by taking the measurement rather than by editing
the prose, which is the better answer in both cases.

*What was checked this round.* The reframing from cost to modelling is the right move and the detector
is the reason: a defect you can state off the DDL is enumerable where a wall-clock hunt is not, and the
item is honest about what that reframing found that the cost pass missed. The subtype-set inventory
checks out against `graphitron-model.sql` exactly as stated: ten capture tables carry `class_name` and
`method`, eight are `*_arg_mapping_pair`, six carry `table_ref` with `graphitron_routine` spelling the
seventh `routine_ref`, three are `jvm_*_type_ref`, and `graphql_field`, `graphql_argument` and
`graphql_directive_argument` share all eight of the named columns. `meta_materialize` seeds twenty-two
registrations. `intent_argmapping_bound_parameter_type`'s `hosted` CTE is six arms differing only in the
directive table joined and the `site` literal filtered, as described; its seventh `UNION ALL` is in the
separate `resolved` CTE and is not one of the six. `intent_declared_type_ref` carries the comments quoted
from it, and reads as the worked confession the item says it is. The 43.0-second re-pricing, the
attribution arms and the store's location are in the audit's section 10, and the 6293 correction landed
where it stood. The read-side honesty in "What changes when this lands", stating an arm that comes out
worse than the register today, is what makes this item trustworthy on the rest.

**Finding 1 (question two: what the implementer builds). The detector's union-site column is inflated
at two of its four rows, and slice 1 is scoped by one of the inflated numbers.** The detector confirms
an omission when a view `UNION`s three or more members of a subtype set, with no check that the set's
shared attributes are what those arms project or join on. Membership overlaps between sets, so a view
that unions six directive tables to reconstruct one fact is counted as a reconstruction of every set
those tables belong to.

That is not hypothetical. Slice 1 says the table-or-routine reference is reconstructed at four sites and
makes "repointing only the first leaves three reconstructions standing" its main correction over the
audit's class A. Grepping the three: `intent_condition_param_extraction`, `intent_condition_table_parameter`
and `intent_argmapping_bound_parameter_type` contain no occurrence of `table_ref` or `routine_ref` at all.
All three union those tables for `class_name` and `method`, which is row 1 of the table and slice 3's
work. A table-or-routine-reference supertype cannot repoint any of them, so the count is one site and the
slice's central correction dissolves. The same rule inflates row 1 the other way round: of its six union
sites, only those same three mention `class_name` or `method`; `intent_spelled_table_live`,
`intent_argmapping_pair_live` and `intent_condition_membership` union member tables for other facts
entirely. Rows 3 and 4 survive the tightened rule at one and two sites.

Three consequences, and the reason this blocks rather than being a note. The count that scopes slice 1 is
wrong, and slice 1 is the slice with the strongest isolation behind it, so the item should not ship
telling an implementer to repoint three views that cannot be repointed. Slice 3's prediction inherits the
same arithmetic: "repoint the six sites and those three residual relations should move" is a prediction
over three sites, which changes what a failed prediction would mean. And the gate this item says it most
owes is specified as this detector, so it would ship the same false positives into the build, which is
the one place they would be expensive: a gate that confirms an omission from set membership alone will
fire on a supertype the view does not reconstruct, and attribute a real reconstruction to the wrong set.
The fix looks small and is the author's to take: require the union's arms to project or join the set's
shared attribute group, re-derive the union-site column under that rule, and re-scope slices 1 and 3 to
what it gives. The audit's section 11 owes the same correction in its own table and in its "there are
four" sentence, since the audit outlives this file and the detector script is cited from it.

Nothing about the defect thesis moves. Four subtype sets with no supertype still exist off the DDL, the
genuine reconstructions are still there, and the modelling argument does not depend on how many readers
happen to be paying today, which is the item's own point.

*Author's response.* Accepted in full; the finding is right and the defect was mine. Verified before
fixing: `intent_condition_param_extraction`, `intent_condition_table_parameter` and
`intent_argmapping_bound_parameter_type` contain no occurrence of `table_ref` or `routine_ref`, and of
the method-bearing set's six reported sites only those same three name `class_name` or `method`. The
scan now requires the view to name the set's own attributes as well as to union three or more of its
members, which puts row 1 at three sites and row 2 at one and leaves rows 3 and 4 untouched.

Everything the inflated numbers scoped is re-scoped. Slice 1's "main correction" is deleted rather than
adjusted, because there was nothing to correct: the audit's class A named the one reconstruction that
exists and was right, and the slice now says so and says the claim against it was this scan's defect.
Slice 3's prediction is over the three sites it can actually be run against. The gate specification
carries the attribute check as one of three parts, with the measured consequence of omitting it stated
where an implementer will read it, and with the further limit that even the tightened check is name
matching over a view body rather than proof that the attribute is projected from the unioned arms.

The audit takes the same corrections in its section 11 table and prose, and it now records both
calibration errors together, since they pull in opposite directions and the pair is more instructive
than either: the first hid a real set, the second invented reconstructions for sets that had none, and
both were caught only by checking output against the DDL by hand.

**Finding 2 (small, question one). The access-form claim is off by one class and understates the case for
the gate it argues for.** Round 1's finding on this was answered well, and the corrected claim is nearly
right: `table(name(...))` appears in five main-source classes, not four (`StoreProse`, `StoreCatalog`,
`Materializations`, `MaterializeDependencies`, `ViewReferences`). The conclusion holds, every site names a
`meta_*` relation, `store_graph` or `INFORMATION_SCHEMA`. But one site does not name anything literally:
`StoreProse` builds the name from a variable, `table(name(relation.toUpperCase(Locale.ROOT)))`, bounded to
`metaRelations(dsl)` at the call site. That is the form a grep-shaped gate cannot see, and it is worth a
sentence where the gate is proposed, because a gate that only refuses a literal `intent_` name would pass a
computed one.

*Author's response.* Accepted, both halves verified. Five classes carry the form, the fifth being
`ViewReferences`, and `StoreProse` line 61 builds the name from a variable. Corrected in the item and in
the audit's section 2, with the resolved-to set widened to include `INFORMATION_SCHEMA`, which the
four-class version had not accounted for either. The computed site is now a sentence on the access-form
gate bullet rather than only a correction: the check belongs on what the argument can resolve to and not
on how it is spelled, which is a real constraint on how that gate gets written.

*Fixed in passing, per the reviewer-fix rule.* The Tests section's account of `KNOWN_NON_MONOTONIC`'s
remaining rows was a miscount: the set holds thirteen, of which the three named leave ten, and those are
eight on `intent_field_reference_step_hop` plus the node-id instruction's plus the `intent_errors_field`
pair, which shares its comment with the argmapping row. Corrected in place.

### Round 3 (2026-08-28, Spec -> Ready, reviewer session 01P2HFCFzA3YiKbaLjgXzet7)

Verdict: sign off. Both round 2 findings are resolved at the source rather than papered over, and the
resolution of the first is better than the finding asked for: the detector grew a third part, the two
inflated rows are re-derived under it, everything they scoped is re-scoped, and the two calibration
errors are recorded together in the audit because they fail in opposite directions. Deleting slice 1's
"main correction" rather than adjusting it is the right call, since there was nothing to correct.

*Re-verified against `graphitron-model.sql` this round.* Of the method-bearing set's six previously
reported sites exactly three name `class_name` or `method`: `intent_argmapping_bound_parameter_type`
at six arms and `intent_condition_param_extraction` and `intent_condition_table_parameter` at five
each, which is the table's new row 1 and slice 3's new prediction. `intent_spelled_table_live` is the
only view naming `table_ref` or `routine_ref`, so row 2 is one site. Rows 3 and 4 are unchanged at one
and two, `intent_jvm_ancestor` and `intent_declared_type_ref` being the two. Five main-source classes
carry `table(name(...))` and `StoreProse:61` is the computed one, as the item and the audit now say.

**One thing for the implementer to carry into slice 1, not a finding against the plan.** The one
argMapping reconstruction covers seven of the set's eight members: eight arms, with
`graphitron_field_condition_arg_mapping_pair` appearing twice for `FIELD_CONDITION` and
`INPUT_FIELD_CONDITION`, and `graphitron_argument_reference_for_step_arg_mapping_pair` absent. That
table's own comment says capture is total across every SDL-legal location even though today's validator
rejects the coordinate, so the eighth member can hold rows the current view does not return. The
supertype is over the closed set of eight; the repointed view has to keep returning 108. Those two are
consistent only if the view keeps the eighth arm out, so the discriminator has to let a reader exclude
it, and a supertype that silently folded it in would break the row-identity anchor in the one direction
the anchor exists to catch. The plan already forces this through its anchor rather than leaving it open,
which is why it is a note and not a finding. The arm count is corrected in the slice and in the audit's
section 3 table.

*Fixed in passing, per the reviewer-fix rule.* Two stale arm counts for that view: "seven arms" in
slice 1 and "7-arm `UNION ALL`" in the audit's class A table, both now eight arms over seven of eight
members.
