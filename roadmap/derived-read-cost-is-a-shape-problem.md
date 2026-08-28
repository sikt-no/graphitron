---
id: R876
title: "Expensive derived reads are a modelling defect: capture writes the subtypes and omits the supertype, and materialization has been the first lever reached for instead of the last"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
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
  which method; six views reconstruct it. Six carry a written table-or-routine reference; four
  reconstruct it. Eight carry an argMapping pair, three carry a declared type reference. The signature
  is mechanical off the DDL and needs no store, which is what makes this the finite part of the item.
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

**The signature is mechanical, needs no store, and is therefore gate-able.** Two halves, both read
off the shipped DDL and nothing else. A **subtype set** is three or more capture tables sharing a
group of attribute names, where an attribute is a column that is not in that table's own primary key
and is not provenance. A **confirmed omission** is a subtype set some view `UNION`s three or more
members of. Run over today's schema, the two halves agree on four:

[cols="4,1,1,4"]
|===
| the fact nobody wrote a table for | subtypes | union sites | the shared attributes

| a directive site that names a Java method | 10 | 6 | `class_name`, `method`
| a written table-or-routine reference | 6 | 4 | `table_ref` and its four split and folded halves
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
relation saying "this site declares this method on this class". Six views reconstruct it, the widest
being `intent_argmapping_bound_parameter_type`, whose six `UNION ALL` arms are the same query six
times: take an argMapping pair, join the directive table that owns the site, project `class_name` and
`method`. The arms differ only in which table they join and which `site` literal they filter on. That
view is not badly written; it is doing, once, the modelling that capture did not do, and five other
views do it again.

**What this reframing buys that the cost framing did not.** It found the declared type reference,
which the performance investigation never reached because that one is cheap on this consumer's schema
and a hunt driven by wall clock stops when the clock stops. It found that the table-or-routine
reference is reconstructed at four sites rather than the one the audit's class A named, so a fix that
repoints only `intent_spelled_table_live` leaves three reconstructions standing. And it names the
mechanism connecting a modelling defect to a measured cost: H2 expands a shared subtree once per path,
so every independent reconstruction of one missing supertype is expanded independently at every reader
above it, which is why this shows up as plan expansion rather than as slow scans.

**What it does not license, and the trap that hid the biggest set.** A shared attribute group is a
candidate and not a verdict: four `sql_*` tables share `table_schema`, `table_name` and `column_name`
without being subtypes of anything, because they reference a column rather than being kinds of one.
The union half separates the two, since a reader that wants a supertype unions the arms where a reader
that wants a reference joins them. And the key-versus-value split has to be read from each table's own
primary key rather than from a list of key-looking names. The first version of this scan excluded
`class_name` globally, on the ground that it is the key of `jvm_class`, and so missed the ten-table set
entirely. A name is a key in one family and a value in another, and a detector that forgets this hides
exactly the sets where a supertype is most overdue.

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

**The repoint is wider than the audit's class A said, and that is this slice's main correction.** The
table-or-routine reference is unioned at four sites, not one: `intent_spelled_table_live` at six arms,
and `intent_condition_param_extraction`, `intent_condition_table_parameter` and
`intent_argmapping_bound_parameter_type` at three each. Repointing only the first leaves three
reconstructions standing, and since each is expanded independently at every reader above it, the three
left behind are where a partial fix would look like a partial result.

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
subtypes, six reconstruction sites, and it is the only confirmed omission no slice above covers.
Slice 1 takes the table-or-routine reference and the argMapping pair; this slice takes this one and
the declared type reference.

It is also the one with a measured argument behind it, which the first draft had no way to make. In
the zero-materialization arm three of the residual relations, `intent_argmapping_projection_defect`,
`intent_node_id_decode_defect` and `intent_resolved_node_key_projection`, all sit above
`intent_argmapping_bound_parameter_type`, which is the six-arm reconstruction of this supertype, and
all three refused a 120-second budget. They are the only residual relations that sit above an
unrepointed reconstruction. That is a prediction this slice can be held to: repoint the six sites and
those three should move, and if they do not, the expansion is coming from somewhere the signature does
not name and slice 3 needs re-scoping rather than continuing.

The declared type reference is the cheap one on this consumer's schema, so argue it on the model and
not on a timing. `intent_declared_type_ref` already states its own supertype shape in a comment that
reads as a specification for the table it should have been: a discriminator over a closed set, a
uniform key, and arm-determined NULLs the comment is at pains to say are key shape rather than facts
withheld.

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
  nothing about scan-count monotonicity. The nine rows below them are the instrument's own four-scan
  floor and one is the node-id instruction's; no slice here touches any of them.
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
- **A gate over the supertype signature, which is the one this item most owes.** Both halves of the
  signature read off the booted store the way `MaterializeRegistryGateTest`'s axes already do:
  the subtype sets come from grouping capture tables by shared non-key attribute names, and the
  confirmations come from scanning view bodies for a `UNION` over three or more members of one set.
  A gate that fails on a *new* confirmed omission is what stops this defect class being re-entered
  the next time a directive is added, which is exactly how eight `*_arg_mapping_pair` tables
  accumulated without anybody deciding to have eight. It needs a declared exemption list, because
  three candidate sets are being left alone deliberately and the two `sql_*` reference groups are
  not subtype sets at all, and an exemption that has to be written down is the point rather than a
  weakness: it turns adding the ninth sibling into a decision somebody records.
- **A gate over the access form.** The reachability walk's soundness rests on every `intent_` read
  going through a jOOQ constant, and that is checkable mechanically where the walk's result is not.
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
third access form does exist, jOOQ's `table(name("..."))`, in four classes. Every site names a
`meta_*` relation or `store_graph`, so no `intent_` relation is reached that way and the walk stands,
but the form is there and nothing stops it being pointed at a derived relation. The audit is
corrected. This makes the gate over the access form more worth building rather than less, and whether
it belongs in this item is a fair question for the gate: it is the one claim in this whole subject
that a build could hold, and everything else here is research evidence.

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
