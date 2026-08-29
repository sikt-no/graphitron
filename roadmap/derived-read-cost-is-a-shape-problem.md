---
id: R876
title: "Expensive derived reads are a modelling defect: capture writes the subtypes and omits the supertype, and materialization has been the first lever reached for instead of the last"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-08-29
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

**The refresh is not the cost any more, and this item no longer claims it.** The first draft promised
a capture in the tens of seconds against four hours and nineteen minutes. That gap closed on the tree
while the item sat at Spec, and the audit's new section 10 measures it: the same store, rebuilt on
the shipping DDL under the shipping cadence, refreshes in **43.0 seconds**, every registered target
coming out at the row count the original capture recorded for it. Two changes did it, both already
merged: the cold-refresh split, and the two payload registrations that landed 2026-08-28, which are
worth 588.2 seconds down to 43.2 on their own. Nothing in this item's slices was needed for any of
it.

**The outcome this item owes is a store that spends no time materializing and still answers its
readers.** Not a cheaper refresh: no refresh, because every fact a reader needs is a fact capture
wrote, and every key a reader joins on is a column an index can serve. On that target the register
is not a thing to prune, it is a thing to make unnecessary.

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
the arm above fixes two of them and leaves the largest untouched. The outcome this item owes is
therefore stated as a target and a test: **no relation in the consumer read set refuses a 120-second
budget with nothing materialized**, which is what makes `mvn graphitron:validate` and
`mvn graphitron:dev` usable on that schema and lets `graphitron:dev` reach its language-server and MCP
binds. If the four supertypes and two keys land and that is still false, the remaining registrations
have earned themselves an argument they have never yet had to make.

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
It is not retired here because the honest test is to demote that one relation with the rest of the
register intact, and inferring it from the emptied arm is the same mistake the correction above
records.

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

### Slice 6: re-measure the performance claims written into DDL comments

Every claim in the store's relation comments was taken in the regime the audit's section 4 describes,
and several steer the next author away from a shape. Census which comments make a re-checkable claim
at all before deciding whether any of them should become rows a gate can read.

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
- **A gate over the supertype signature, which is the one this item most owes.** All three parts read
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
