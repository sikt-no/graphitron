---
id: R723
title: "Warn when a @reference path traverses a 1:N hop into a further projection"
status: In Progress
bucket: validation
priority: 4
theme: diagnostics
depends-on: []
created: 2026-08-19
last-updated: 2026-09-14
---

# Warn when a @reference path traverses a 1:N hop into a further projection

## Goal

A `@reference` path is mechanical foreign-key traversal, and SQL joins produce bags rather than
sets, so a list field over a path that fans out returns duplicate rows. That is the correct
result for the declared path. What is missing is any signal that the path has that property:
the generator emits the multiset silently, and a client reading a field like "the environments
this application has access in" reasonably takes it for a set. This item adds a lint rule that
says so at build time: a list field whose field-site `@reference` path passes *through* an
intermediate table that can hold more than one row per (entering key, leaving key) pair gets a
build warning at the field's SDL coordinate, naming the hop that multiplies and suggesting the
DISTINCT-view remedy, with no change to emitted SQL. A pure join table such as `film_actor` stays
quiet. Filter paths, on arguments and on input-object fields, are not in scope, because the
generator lowers them to a semi-join in which fan-out is unobservable; "Where it lives" carries
the reason.

The property is decidable statically, from foreign-key metadata the store already holds. What
this spec settles is **which** static property, because the obvious formulation is wrong, and
the measurement below shows it firing six times on our own example schema with all six wrong.

## Field report

Reported at https://github.com/sikt-no/graphitron/issues/529, then reformulated by its author
from bug to feature request in the follow-up comment. The reformulation is the substance and
the issue title still says otherwise, so read the comment first. Their position, which this
item adopts:

* Graphitron is doing what the config asks. Their own path runs through a table that carries
  role and period data, so it is an author-composed multi-hop path and not a modelled M:N
  relation the generator could know is meant as a set.
* A default `SELECT DISTINCT` would be wrong in general: it changes semantics for legitimate
  multiset uses and interacts badly with pagination and ordering.
* Their dedicated `SELECT DISTINCT` view is arguably the idiomatically correct relational
  answer rather than a workaround.

Their two asks: a warning naming the multiset property, in the same class of feedback as the
existing deterministic-order requirement; and either an opt-in `distinct` flag on `@reference`,
or documentation blessing the DISTINCT-view plus synthetic-FK pattern.

**The reporter then reviewed this spec on the issue and raised three points, all of which are
folded in here at their request.** The subset direction is the one that changed the document:
they read the stub's ambiguous "covered by" as `bound ⊆ columns(constraint)` and were right that
it is wrong, and the reply that had been posted to the issue stated it in that inverted form, so
the correction is owed publicly as well as here. Their temporal-key and partial-unique-index
points are answered under "Which constraints may clear a hop"; one was already satisfied by
construction and one is a genuine hole whose reach this spec now bounds. Their reading also
exposed something the measurement had missed, recorded in the measurement section: the inverted
predicate scores identically on our corpus, so the numbers could not have caught it.

## The rule

Their proposal, stated as a predicate: a path contains a hop into the *child* side of a foreign
key (1:N) and does not terminate in that child table. Mine, when this item was filed as a
stub, sharpened it by excluding a reverse hop whose FK columns are, in that stub's words,
"covered by a PK or unique constraint on the declaring table". Both are rejected below; the
stub's phrase is also ambiguous about which way the subset runs, and the review round on the
issue caught that. **The direction is the whole rule, so state it before anything else.**

Uniqueness is a statement about columns whose values are *known*. A constraint pins a row only
when every column of that constraint is bound by the join, so the test is
`columns(constraint) ⊆ bound`, never `bound ⊆ columns(constraint)`. The wrong direction fails on
any composite constraint: with an FK on `(a)` and a `UNIQUE (a, b)`, the FK columns sit inside
the constraint, yet arbitrarily many rows share one `a`. Clearing that hop as 1:1 would be a
false negative, which is the failure mode this rule cannot afford: a warning that stays silent
teaches the author the path is a set.

**Both are wrong, and the measurement is what shows it.** Applied to the example schema, each
fires on exactly six coordinates, and all six are `film -> film_actor -> actor`:

```
Film.actors, Film.actorsGenerated, Film.actorsConnection,
Film.actorsOrderedConnection, Film.actorsBySplitLookup, Film.actorsBySplitLookupGenerated
  position 0: film -> film_actor via film_actor_film_id_fkey, fk_on_from = FALSE
```

`film_actor` is a pure join table, so `Film.actors` returns each actor exactly once. Both
formulations flag the single most canonical `@reference` shape in the corpus, and are wrong
every time. A rule with that false-positive profile is unshippable, and neither formulation
carries anything that would have revealed it short of running it.

The property that actually distinguishes the two cases is not the direction of one hop. It is
whether the intermediate table can hold more than one row per (entering key, leaving key) pair.
Take an intermediate `T` entered by one hop and left by the next, and let `bound(T)` be the
union of the columns those two hops bind on `T`. Then:

> The path fans out at `T` unless some PRIMARY KEY or UNIQUE constraint on `T` has all its
> columns inside `bound(T)`, that is `columns(constraint) ⊆ bound(T)`.

For `film_actor`, the entering hop binds `film_id` and the leaving hop binds `actor_id`, so
`bound = {film_id, actor_id}`, and `film_actor_pkey` is exactly `(actor_id, film_id)`. Covered,
so no fan-out, correctly. For a join table carrying its own payload the pair is not unique, so
nothing covers it and the rule fires. That is precisely the distinction the reporter reached
for informally when they said their table "is not a pure join table (it carries rolle/period
data)": this predicate is that sentence made decidable.

Two properties worth stating, because they are why this formulation is the one to build:

* It subsumes the terminal-hop exemption without a special case. A path that ends on the many
  side has no leaving hop, so that element is no intermediate and yields no row at all; that is a
  plain to-many list, exactly as the reporter said.
* It generalises past two hops. The predicate is per intermediate, so a five-hop path is five
  independent questions, and the finding can name the hop that multiplies rather than the path
  as a whole.

Two precisions the implementation needs, both settled here.

**What a hop binds.** A hop over foreign key `F` between `T` and its neighbour binds, on `T`, the
columns of `F` that sit on `T`'s side: the key's own columns where `T` declares `F`
(`fk_on_from` reads TRUE departing `T`, FALSE arriving at it), the referenced columns otherwise.
`intent_foreign_key_column_pair` yields both sides of every foreign key, one row per position, so
`bound(T)` is a union over two joins on that view and nothing is recomputed. Entering
`film_actor_note` from `film_actor` over `FOREIGN KEY (actor_id, film_id) REFERENCES film_actor`
binds `{actor_id, film_id}` on `film_actor_note`, the declaring side, exactly as the join
predicate equates them.

**Which elements the predicate can judge, and how it declines.** `via` on the target view is closed
over four arms, and only `KEY` and `TABLE` elements carry a `constraint_name`. A `CONDITION`
element joins on an authored Java predicate whose columns the store cannot see, and a `NAME_MATCH`
element departs a table-valued function's result, which declares no constraint at all. An
intermediate entered or left by either of those cannot be judged: `bound(T)` is undefined on one
side, and pronouncing either way would be a guess.

**That decline is a row, not a silence, and the distinction is the whole of it.** The view carries
a `verdict` column over a closed vocabulary, and a declined intermediate is a row saying so:

| `verdict` | what it says |
|---|---|
| `FANS_OUT` | no PRIMARY KEY or UNIQUE constraint on `T` has its columns inside `bound(T)`; this hop multiplies, and the row carries the intermediate and the position |
| `COVERED` | some constraint does, and the row names which; the hop is 1:1 on the bound pair |
| `UNDECIDABLE_CONDITION_HOP` | the entering or leaving element is a `CONDITION`, so one side of `bound(T)` is unreadable |
| `UNDECIDABLE_NAME_MATCH_HOP` | the entering or leaving element is a `NAME_MATCH`, departing a table-valued function's result that declares no constraint |

One row per intermediate, keyed on the entering element's coordinate
(`graph_name, type_name, field_name, ordinal, position`), so the path
`film -> film_actor -> film_actor_note -> actor` yields a row at position 0 for `film_actor` and
one at position 1 for `film_actor_note`, and "position 1 multiplies" is a value rather than an
inference. Absence then means exactly one thing, that the walk did not reach the element, which is
the silence `intent_field_reference_step_target` already owns and the only one this view inherits.

The alternative, a view that simply yields no row where it cannot judge, was the earlier draft and
is wrong for a reason this spec states about itself elsewhere. Absence would carry four meanings:
no `@reference` here, the element was not reached, the intermediate is undecidable, and the pair
genuinely is covered. The last is what the author-facing documentation teaches a quiet build to
mean, and the third is a false negative sitting inside it, which is "the failure mode this rule
cannot afford" by this document's own subset argument. `fact-model.adoc` states the general move
("a relation whose absence is load-bearing owes that sentence") using this very walk as its worked
example, and `intent_field_reference_step_target` discharges it by handing its other silences to
`intent_condition_method_route_defect` so that its own absence means one thing. A new view beside
that one does not get to re-accumulate four.

Three things follow, and this item wanted all three anyway. The consumer's decode is a total switch
over the vocabulary with no `default` and a drift throw, exactly `UnlowerableOrderings.Verdict.of`,
whose own switch likewise has an arm that deliberately mints nothing. The undecidable cases are
tested by asserting a named row rather than an absence, which a test pinning a silence cannot do,
being unable to tell "declined" from "not implemented". And the scalar-field sibling rule under
"Out of scope" reads the column instead of re-deriving the predicate.

## Measured on the corpus

Numbers from the example schema's main execution, captured 2026-08-19 on trunk.

| Measure | Count |
|---|---|
| GraphQL fields in the graph | 850 |
| `@reference` applications | 79 |
| Path elements (`graphitron_field_reference_step_entry`) | 94 |
| Elements the walk resolves (`intent_field_reference_step_target`) | 62 |
| Resolved elements arriving on the child side (`fk_on_from = FALSE`) | 35 |
| Non-terminal child-side elements (the two rejected formulations' finding set) | 6 |
| Findings under the wrong-direction reading (`bound ⊆ columns(constraint)`) | 0 |
| **Findings under the pair-coverage rule** | **0** |

**The last two rows are the same number, and that is the important result.** The corpus cannot
tell the correct rule from the inverted one: the only non-terminal reverse hops it contains go
through `film_actor`, whose key is exactly the bound pair, so both readings clear all six. Had
the inverted wording been implemented, the example schema would have reported zero findings and
the measurement would have read as confirmation, while the rule stayed silent on every
composite-key case, the reporter's own tables included. A measurement that cannot separate the
candidate predicates is not evidence for either of them, and this one could not.

Reproduce it with:

```bash
mvn -pl graphitron-sakila-example graphitron:generate@rewrite-generate -Plocal-db \
    -Dgraphitron.store.directory=<dir>
```

then query `intent_field_reference_step_target` in the store under `<dir>`. Two details that
cost time if rediscovered. The example declares five plugin executions that all capture under
the graph name `graphitron-sakila-example`, so a full `generate-sources` leaves only the last
execution's rows (26 fields, no reference steps) and the single-execution invocation above is
what puts the main schema in the store. And the recursive target view took roughly 70 seconds
to materialise on a cold query through the H2 shell, which is a number the implementation has
to re-take in process before this rule goes on every build; see the cost bullet below.

**The zero is not yet evidence the rule is right.** It says the corpus contains no genuine
fan-out shape, so the corpus cannot currently witness the rule firing at all, and per the row
above it cannot separate the correct predicate from the inverted one either. The corpus owes
**discriminating** fixtures, not merely one that fires, and that is an acceptance criterion
rather than a nicety.

The shape to author is exactly the reporter's table: an intermediate whose key carries a
discriminator beyond the columns the two hops bind. Stated as the condition a fixture has to
meet, because two obvious completions fail it in opposite ways: `bound(T)` must be a **strict**
subset of some PRIMARY KEY or UNIQUE constraint on `T`, and no such constraint may sit inside
`bound(T)`. Then the correct rule fires (a key column is unbound) and the inverted rule clears
the hop (the bound set is inside a key), which is the disagreement the fixture exists to exhibit.
A payload-carrying join table with `PRIMARY KEY (a, b, c)`, entered on `(a)` and left on `(b)`,
is the minimal case. A leaving hop that binds a column *outside* the key does not qualify: with
`bound = {a, b, x}` neither `columns(K) ⊆ bound` nor `bound ⊆ columns(K)` holds, so both readings
fire and the fixture no longer separates them. Nor does one that binds the last key column, since
`bound` then equals the key and both readings clear it.

`film_actor_note` in `graphitron-sakila-db/src/main/resources/init.sql` is the corpus's candidate:
`PRIMARY KEY (actor_id, film_id, lang_code)`, entered from `film_actor` over
`FOREIGN KEY (actor_id, film_id)`, so the entering hop already binds two of the three key columns,
and no `@reference` path currently reaches it. What it lacks is a foreign key to leave by, and
the condition above says where that key may sit: on a column the entering hop already binds,
`FOREIGN KEY (actor_id) REFERENCES actor` or `FOREIGN KEY (film_id) REFERENCES film`, so that
`bound = {actor_id, film_id}` stays strictly inside the primary key and `lang_code` stays free.
A key on `lang_code` would complete the primary key and go quiet under both readings; a key on a
new column outside the primary key would fire under both. With the `actor` key added, a
coordinate declaring `film -> film_actor -> film_actor_note -> actor` has two intermediates and is
the whole test in one path: `film_actor` is cleared (`bound = {film_id, actor_id}`, its primary
key) and `film_actor_note` fires, naming position 1 as the multiplying hop. Pair it with the
existing `Film.actors` shape as the negative case and the two coordinates pin the direction
between them.

**Where the discriminating pair lives is settled, and it is not `init.sql`.** What a view returns
given rows is pinned in `graphitron-model` against a store seeded row by row, which is where the
`intent_` view tests already sit; behaviour is pinned at the pipeline tier and above. The
discriminating pair is a statement about what the view returns, so it is a seeded-store test:
`bound(T)` strictly inside a composite key fires, `bound(T)` covering one clears, and the
subset-direction case (an FK on `(a)` against a `UNIQUE (a, b)` on the arriving table) is the same
kind of row. None of that needs a catalog, a `@reference` path, or an `init.sql` edit, and adding
a foreign key there would change the jOOQ catalog every test in the reactor reads for no gain the
seeded rows do not already give. The pipeline tier then pins one end-to-end coordinate: a warning
arrives at the field, with the expected message, naming the multiplying position. The
`film_actor_note` walkthrough above stays in this document as the worked shape the seeded rows
encode, not as an instruction to edit the corpus. The acceptance criterion is unchanged and is met
by the pair: a fixture that only fires would pass under the inverted reading too.

One caveat on the population: 32 of the 94 authored path elements resolve to nothing the walk
can reach, and absence on that view means "not reached" rather than "resolves to nothing in
particular". Those coordinates yield no row here either, and that inherited silence is the one
this view owns, per the verdict vocabulary above: a path that does not walk fails elsewhere, and a
fan-out warning on top would be noise.

## Which constraints may clear a hop

Not every PRIMARY KEY or UNIQUE row in the store carries a guarantee the generated join
inherits. Two kinds were raised in the review round on the issue, and they resolve differently.

**Partial unique indexes do not reach the rule, by construction.** Verified on the session's
PostgreSQL 16: a `CREATE UNIQUE INDEX ... WHERE ...` produces zero `pg_constraint` rows and
appears only in `pg_index`, and PostgreSQL rejects `ALTER TABLE ... ADD CONSTRAINT ... UNIQUE
(a) WHERE ...` as a syntax error, so a partial unique *constraint* does not exist as a thing.
Our capture takes UNIQUE and PRIMARY KEY rows from `Table.getKeys()`
(`CatalogFactCapture`, the `sql_` family load), which is jOOQ's unique-key model over declared
constraints, so a partial unique index never becomes a `sql_constraint` row. The reviewer's
recommendation, treat them as non-covering, is therefore what the rule already does.

Our index facts cannot leak one in either, but for a reason worth stating precisely rather than
overstating: `JooqCatalog.IndexFacts` is `(name, columns)` and `sql_index` has no uniqueness
column, so the information is absent from *our* model by choice. jOOQ itself has it, in both
3.20.11 and 3.21.7: `org.jooq.Index` exposes `getUnique()` and `getWhere()`. That is the useful
half of the finding, because `getWhere()` is exactly what the reviewer's condition requires. If
we ever do want unique indexes to clear a hop, jOOQ hands us the predicate alongside the
uniqueness flag, so the guarantee can be transferred honestly (a partial index clears nothing
unless the join carries its predicate) rather than assumed.

Until then the rule reads `sql_constraint` and must never widen to `sql_index` while `sql_index`
says nothing about uniqueness. An invariant stated in an implementation comment has no enforcer,
which this project treats as an invitation to file the meta-test rather than as a claim already
kept, so state it where something holds it: the predicate lives wholly in the view's SQL, per
"Cost", and a widening is then an edit to the view's declared reads inside the machinery
`MetaDeclarationGateTest` walks, not a comment a later optimisation can quietly step over.

**Temporal keys are a real hole, currently out of reach, and the safe side is the one we are
on.** A `PRIMARY KEY (a, b, valid_at WITHOUT OVERLAPS)` guarantees one row per *instant*, not
one row per `(a, b)`, so it must not clear a hop even when its column set is inside `bound(T)`.
Three findings bound the risk:

* **Neither jOOQ nor our capture can see it, and upgrading does not change that.**
  `org.jooq.Key` in 3.20.11 exposes `getTable`, `getFields`, `getFieldsArray`, `constraint`,
  `enforced` and `nullable`, and `org.jooq.UniqueKey` adds `isPrimary`, with no period concept
  anywhere in that hierarchy, and the jar contains no `WITHOUT OVERLAPS` string; `sql_constraint` has a
  `constraint_type` closed over `PRIMARY KEY | UNIQUE | FOREIGN KEY` and no period flag. 3.21.7
  was checked against the same questions and answers them the same way: the `Key` and
  `UniqueKey` interfaces are unchanged, there is still no `WITHOUT OVERLAPS` string (the
  `OVERLAPS` occurrences are the SQL row-overlaps predicate, `org.jooq.impl.RowOverlaps`), no
  period-named type exists in `org.jooq`, `jooq-meta` mentions `conperiod` nowhere, and its
  generated `pg_catalog.pg_constraint` model carries `CONNAME`, `CONNAMESPACE`, `CONTYPE` and
  `CONKEY` only, so the provider could not read the flag even if the runtime could hold it.
  `roadmap/upgrade-jooq-3-21.md` (R466) therefore does not unblock this, and an explicit
  exclusion is not a predicate tweak whichever version we are on: it needs a catalog read
  outside jOOQ's model.
* **Today the correct direction already protects us.** The period column is part of the
  constraint's column list in the catalog, and graphitron binds columns only through
  foreign-key equality, so a period column is bound by no hop. `columns(constraint) ⊆ bound(T)`
  is therefore false for a temporal key and the rule fires. The reviewer's own counterexample
  demonstrates the corrected rule working rather than failing; it defeats only the inverted
  reading, which is what they were reading.
* **Both plausible jOOQ behaviours land conservatively.** If jOOQ reports the key with all its
  columns, the period column is unbound and the key does not cover. If jOOQ skips a key whose
  backing index is GiST, the table has no covering key at all. Either way the rule fires, which
  is the direction a warning should fail in.

The hole opens if graphitron ever binds a period column, which means gaining range or overlap
join vocabulary it does not have. Two obligations follow, both small: pin the assumption with an
execution-tier fixture on PostgreSQL 18, where `postgres:18-alpine` already runs and
`WITHOUT OVERLAPS` is available, asserting that a temporal key does not clear a hop; and record
on that test why it is the fixture that guards the invariant, so a later change to the join
vocabulary meets it. The session's own PostgreSQL is 16, which cannot express the syntax, so
this could not be settled while writing this spec and is stated as an assumption rather than a
measurement.

## Where it lives

The pivotal question when this item was filed was whether a build-time rule can read the
derived views at all. It can, and since then it has become easier than the earlier draft assumed:
the lint channel itself now runs inside the capture window holding a live store handle. The item
straddles the `graphitron-model` / `graphitron` boundary, so each artefact below names its module.

* **The derivation.** The reduction lives in the view's SQL, and what remains in Java is the decode
  of its closed vocabulary into the values the report carries. `AuthoredClaimConflicts` is the
  precedent for that division of labour and `UnlowerableOrderings` for the verdict decode, a total
  switch over the view's arms with a drift throw. So the pair-coverage reduction is a new view
  beside `intent_field_reference_step_target`, and a small producer decodes its rows.
* **The producer.** `no.sikt.graphitron.model.lint` already holds `LintRule`, `LintFix`,
  `LintConfig` and `DeprecationRecognizer`, which is a lint producer that answers from store rows
  rather than from a parse tree. The fan-out producer belongs there, beside it: it takes a
  `DSLContext` and a graph name and returns `List<BuildWarning.LintFinding>`. It is deliberately
  **not** a member of the `model/derive/` package and does not join the `StoreDetections` record;
  see "Why not a `StoreDetections` family" below.
* **The substrate.** `intent_field_reference_step_target` already yields, per path element,
  `from_*` and `to_*` endpoints, `constraint_name`, `via` and `fk_on_from` ("TRUE when the
  departing table declares the foreign key; the element's direction"). `sql_constraint` is closed
  over `PRIMARY KEY | UNIQUE | FOREIGN KEY`, `sql_constraint_column` carries the column sets, and
  `intent_foreign_key_column_pair` pairs each foreign key's columns with the columns it
  references. Everything the predicate needs is a join away, with no new capture.
* **The warning channel, which is already where this belongs.**
  `GraphQLRewriteGenerator.withLintFindings` assembles the classification advisories, the engine
  findings and the codegen advisories, and applies the `disabledRuleIds` filter last, over the
  combined list. It runs **inside** the capture window, taking the live `StoreHandle` that
  `captureAndRead` hands its callback, and passes it to the lint engine. So a producer folded in
  there beside `SessionStateWarnings` and `DependencyVersionWarnings` reads this run's rows and
  inherits the suppression filter with nothing moved. `BuildWarning.LintFinding` carries the
  message, a `SourceLocation`, a `LintRule` and an optional `LintFix`. The store copy in
  `lint_finding` is written by the dev loop (`DevMojo`, through `BuildWarningFacts`) off the pass's
  suppression-filtered warnings, so a finding that joins that list reaches the report, the LSP
  replay, the MCP projection and the store with no new writer, and a suppressed one reaches none
  of them.

The five artefacts, each with its module:

| Artefact | Module | Where |
|---|---|---|
| The view | `graphitron-model` | `graphitron-model.sql`, beside `intent_field_reference_step_target`; registered `Arm.DERIVED` in `FactCaptureAgreementTest` (in `graphitron`), whose registration map is exhaustive over the schema |
| The producer | `graphitron-model` | `no.sikt.graphitron.model.lint`, beside `DeprecationRecognizer`; one statement driven from the view, decoding `verdict` in a total switch |
| The rule and its `Source` arm | `graphitron-model` | `LintRule`, which owns both the rule constants and the `Source` enum |
| The coverage extension | `graphitron` | `LintRuleRegistryCoverageTest`, whose partition assertion keys off the `Source` arms |
| The fold-in | `graphitron` | `GraphQLRewriteGenerator.withLintFindings`, above the `disabledRuleIds` filter |

**Why not a `StoreDetections` family.** An earlier draft put the verdict on that record with a
sibling `warnings()` accessor and a fold-in in `runPipeline`, because lint assembly then ran before
the capture and could not read the store. It can now, so the reasons that remain all point the
other way. Every path a warning takes to the report, the LSP replay, the MCP projection and
`lint_finding` runs through `withLintFindings` today, and the suppression filter is the last thing
that touches the combined list; a second producer elsewhere in `runPipeline` would be a second
entry point into that channel, with the filter moved to compensate and nothing but prose keeping
the two suppressible alike. And `UnlowerableOrderings` earns its place on the record by having two
consumers, the build-error stream and the capture-cadence rejection-rows writer. This verdict has
one. The durable shared artefact here is the **view**, which the authoring-time counterpart under
"Related" would read as its second consumer; that is an argument for the view, not for a record
member. `StoreDetections` is therefore untouched, and `violations()` stays the one assembly point
for the families that mint errors.

**Why not a lint visitor either.** The engine's own traversal is the other tempting home, and the
read shape rules it out. `intent_field_reference_step_target` is a `WITH RECURSIVE` view carrying
window functions, and `fact-model.adoc` states the measured rule that such a view is taken once
per answer and paired on its key rather than correlated per driving row, since a window sees its
whole partition whatever the outer correlation says. `DeprecationRecognizer` is the sanctioned
opposite, keyed seeks into base relations that nest freely. A visitor asking this question at
`FIELD_DEFINITION` grain would correlate the recursive view once per field, and the example schema
yields zero findings today, so a green build would not reveal it. The producer therefore drives one
statement from the view and attributes findings by the view's own coordinate and location columns,
which is also why it is a producer rather than a visitor and why the `Source` question below has
the answer it does.

Four things this spec decides rather than leaves open.

**The rule needs a new `LintRule.Source` arm, and the axis it joins is producer identity.** Read
`LintRuleRegistryCoverageTest` rather than the enum's prose and what `Source` partitions is
unambiguous: `everyEngineRuleIsRegisteredToExactlyOneVisitor`,
`classifierAdvisoriesAreNotRegisteredAsVisitors`, `codegenAdvisoriesAreNotRegisteredAsVisitors`.
Every assertion is about which producer mints the finding, and therefore which completeness gate
owns the rule. The axis has never partitioned on what a rule *reads*, which is why the enum needed
no edit when `NO_DEPRECATED_DIRECTIVE_USAGE` moved its evidence from the parse tree to store rows.
On that axis this rule is a fourth producer: it has no visitor, so it is not `ENGINE`; it is not
tagged at a classifier emit site, so it is not `CLASSIFIER`; and `CODEGEN` is "a whole-build fact
with no SDL coordinate", which this one has. Add the arm for **findings minted by a store-reading
producer folded in at report assembly**, and extend the coverage test's partition. Do not argue it
on catalog facts against AST facts: that would splice a second axis onto the producer identifier,
and the next row-reading visitor would then belong to two arms at once. The arm is reusable, and
`LintRule`'s `ENGINE` javadoc owes a restatement on the producer axis in the same commit, since
its "re-derivable from the AST alone" is the sentence that sent an earlier draft of this spec down
the wrong line.

**The rule honours `excludedTypes`, through a shared matcher rather than a restated one.** The
engine applies `excludedTypePatterns` before dispatch, and `withLintFindings` notes that a finding
minted outside the walk still fires on an excluded type. That asymmetry is accepted for the
classifier advisories, which carry no coordinate the author excluded by name; it is not acceptable
here, where the finding lands at a coordinate on a type the consumer asked not to be linted, and
would read as a bug. The producer applies the same exclusion, reusing the engine's glob matcher at
a shared point rather than owning a second copy of the rule. Two fences: this changes nothing for
the existing classifier advisories, and the glob matching must not end up stated twice, a
population rule applied two ways with nothing binding them being the drift this project names.

**The finding carries a fix, and the fix is the view pattern.** `lint_finding_fix` plus its
ordered edits is "a suggestion an editor offers, never a rewrite the build performs", which is
the right register for the reporter's second ask: name the DISTINCT-view plus synthetic-FK
pattern as the suggested remedy without committing to a `distinct` flag. The edits list stays
empty, since there is no mechanical rewrite here.

**Field-site projection paths only, for a reason that is semantic rather than a gap in the
substrate.** The substrate is symmetric: `intent_argument_reference_step_target` and
`intent_input_field_reference_step_target` ship beside the field-site view with the same
`constraint_name`, `fk_on_from` and `position` columns, so the reduction would read them
verbatim. They are excluded because the property this rule reports does not exist at a filter
site. The generator lowers a filter path, on an argument or on an input-object field alike, to a
correlated `EXISTS` semi-join (`ReachPath` is the carrier, `ConditionGlueRenderer.reachExists`
the emitter, and both sites converge on `BodyParam.RemoteColumnPredicate` before either reaches
it), so however many rows the path reaches, each parent row is matched once. The example schema
states it on `filmsByBridgedActorFirstName`: "one film reaches many actors, so this is also the
no-row-multiplication proof: film 1 has two actors and comes back once", and
`GraphQLQueryTest.referenceFilter_reverseDirectionFkHop_matchesEachParentOnce` pins it at the
execution tier. A fan-out warning on a filter path would therefore be a false positive by
construction, which is the profile the measurement section rejects. A field-site list path, in
contrast, lowers to a correlated `DSL.multiset` whose inner select carries the join chain, and
the duplicates land inside the child list, which is exactly the reporter's report.
`@referenceFor` is the one projection-shaped path that does carry the property and is still out:
it is a per-participant join on a multi-table child field and has no hop or target view
(`intent_reference_for_application` is the family's only relation), so covering it means
authoring that substrate first. The documentation states both boundaries in their own terms: a
filter path has nothing to warn about, and `@referenceFor` is not yet looked at.

## The ordering hazard, and why it is closed

This section used to be the item's sharpest risk, and it is worth keeping only as a note on why
the placement is no longer free to go wrong.

The store is a warm cache that survives across runs. A store read placed outside the capture
window would therefore not read an empty store, which would be obvious, but **the previous run's
rows**: findings at coordinates an edited schema no longer has, and silence at the ones it does.
The dev loop, which reruns the pass on every save, is where that bites first.

`runPipeline` is the single pipeline body, with `validate()`, `capture()` and `buildOutput()` as
projections through it, so there is one call site that decides this. When this item was filed,
`withLintFindings` ran before `captureAndRead` and the hazard was live. It no longer does:
`withLintFindings` is called inside the continuation `captureAndRead` hands its callback, takes
the live `StoreHandle`, and passes it to the lint engine, whose own deprecation rule already reads
rows through it. A producer folded in there is inside the window by construction, and there is no
reordering to perform.

What survives is the guard. Pin it with a test that edits a path between two runs against one
store and asserts the second run's findings describe the second schema. The shape makes that test
pass on the day it is written; its job is to fail on the day someone moves lint assembly back out
of the window.

## Cost

`roadmap/derived-read-cost-is-a-shape-problem.md` (R876) owns the frame this section reasons in,
and this item adopts it rather than restating it: an expensive derived read is a modelling
defect, the lever order is capture, index, rewrite, registration, in that order, and a new
relation gets an owner rather than a row in `meta_materialize`. Under its ownership walk this
view crosses the `graphitron_` and `sql_` families and computes to the `graphitron` owner, like
every other rule over `intent_field_reference_step_target`. It is authored as a plain view, no
registration, and stays one unless a measurement on a populated store says otherwise, in which
case the lever order applies and a registration is the last thing tried.

The 70-second figure recorded in the measurement section was a cold recursive walk through the
H2 shell and is not the number the rule would pay. `intent_field_reference_step_hop` is already a
registered target, and its registration reason records the target view falling "from around
thirty milliseconds to three" once the hop rows are stored, so the substrate this view reads is
priced and cheap. What the implementation owes is the reach, not a wall clock.

Where that reach is pinned follows from the producer not being a `StoreDetections` family:
`DetectionReadReachGateTest` pins the *detection pass's* per-component reach, and this producer is
not in that roster, so it would not enter that gate. **The predicate therefore lives wholly in the
view's SQL**, with the producer doing nothing but decode, which is the division of labour "Where
it lives" already states for a second reason. The consequence is that the relations the rule reads
are the view's own declared reads, inside the declaration machinery `MetaDeclarationGateTest`
walks, rather than a set a Java class could name incompletely. That is also the cheapest honest
closure of the `sql_index` invariant under "Which constraints may clear a hop": a widening from
`sql_constraint` to `sql_index` would be an edit to the view's declared reads rather than a
comment nobody enforces. `DerivedReadCostTest` is not touched, because it prices registrations and
this item adds none; `MaterializeRegistryGateTest` likewise. If the read proves material on a
populated store, the `store-performance` skill is the method, and the first lever is expressing
the predicate over the target view's rows rather than re-walking, which the view design above
already does.

## Tests

* **The view, seeded-store tier in `graphitron-model`.** The predicate over rows written one at a
  time, per the tier split in the measurement section: a pure join table yields `COVERED`, a
  payload-carrying one yields `FANS_OUT`, a path terminating on the many side yields no row for
  that terminal element (no leaving hop, so no intermediate), and a path with two intermediates
  yields two rows carrying different verdicts at their own positions.
* **The discriminating pair**, per the measurement section: an intermediate whose bound columns
  sit strictly inside a composite key with one key column bound by neither hop (`FANS_OUT`),
  against the `film_actor` shape (`COVERED`). One test asserting both is what pins the subset
  direction; a fixture that only fires would pass under the inverted reading too, which is how
  that reading survived a measurement.
* **The undecidable arms are rows, and the test asserts the row.** An intermediate entered or
  left by a `CONDITION` element yields `UNDECIDABLE_CONDITION_HOP`, and by a `NAME_MATCH` element
  `UNDECIDABLE_NAME_MATCH_HOP`, with no finding minted from either. Asserting the named verdict
  rather than an absence is the point: a test that pins a silence cannot tell "declined" from
  "not implemented".
* **Absence means one thing.** An element the walk did not reach yields no row at all, asserted
  as such, so the view's one silence stays the one `intent_field_reference_step_target` owns.
* **The verdict decode is total.** The producer's switch over the vocabulary has no `default`,
  and a value the enum does not know throws, per `UnlowerableOrderings.Verdict.of`. A test adds a
  row with an unknown verdict and asserts the throw, so the view's arms and the enum move
  together.
* **`excludedTypes` reaches this rule.** A consumer pattern excluding the enclosing type removes
  the finding, asserted beside the existing engine-side case so the shared matcher has one
  meaning.
* **A filter path stays silent.** An argument-site path through a payload-carrying intermediate
  produces no finding, asserted beside the execution-tier proof that the same path matches each
  parent once, so the scope decision and its reason sit in one place.
* **The direction, asserted as such.** A seeded-store case with an FK on `(a)` and a `UNIQUE (a,
  b)` on the arriving table, asserting `FANS_OUT`. This is the minimal counterexample to
  `bound ⊆ columns(constraint)` and it belongs in the suite under that name, so the next reader
  who thinks the subset looks backwards finds the answer in a test rather than re-deriving it.
* **The temporal key**, execution tier on PostgreSQL 18, per the constraints section: a key
  declared `WITHOUT OVERLAPS` does not clear a hop.
* **Suppression.** The rule id in `disabledRuleIds` removes the finding from the report and from
  `lint_finding`.
* **The ordering pin** described above, which passes on the day it is written and exists to fail
  if lint assembly moves back out of the capture window.
* **One end-to-end coordinate, pipeline tier.** The warning arrives at the field, with the
  expected message, naming the multiplying position.
* **Registry coverage and the store gates.** `LintRuleRegistryCoverageTest` extended to the new
  `Source` arm, so the partition stays total, and the arm's own assertion stated on the producer
  axis (no visitor, no classifier emit site) to match the three that exist; the view registered
  `Arm.DERIVED` in `FactCaptureAgreementTest`. `DetectionReadReachGateTest` is **not** touched,
  the producer not being a member of the detection pass; `DerivedReadCostTest` and
  `MaterializeRegistryGateTest` likewise, no registration being added.

## User documentation

A `@reference` page note stating the property and the remedy, and a row in whatever the lint
rules reference page becomes (`roadmap/lint-rule-reference-page.md`, R592, owns that page; this
item should not invent a second home for rule documentation). The note has to say what the rule
does *not* cover, each boundary in its own terms: a filter path on an argument or an input field
is lowered to a semi-join and has no fan-out to warn about; a `@referenceFor` path is not judged,
so a quiet build is not a statement about it; a hand-written join, meaning an intermediate reached
through a `CONDITION` or `NAME_MATCH` element, is where the rule most needs to say it is not
looking, since that is where an author is most likely to assume it did; a type excluded through
`excludedTypes` is not linted by this rule either; and a scalar field over a fanning path is a
different defect, per "Out of scope".

State the semantics plainly, in the reporter's own framing: the multiset is the correct result
of the declared path, the warning exists because the declaration is easy to misread as a set,
and the remedy is a view with set semantics rather than a flag that changes what the join means.

## Out of scope

* The `distinct` flag on `@reference`. It is a directive-surface question owned by
  `roadmap/path-element-surface-cleanup.md` (R235), and nothing here forecloses it.
* Any change to emitted SQL. This item emits a warning and nothing else.
* Argument-site and input-field-site paths, which carry no fan-out, and `@referenceFor` paths,
  which lack a target view, per the scope decision above.
* Scalar fields over a fanning path. A scalar field-site path lowers to a capped correlated
  subselect (`SelectTerm.ScalarSubselect`, one row however many the hop reaches), so fan-out
  there picks an arbitrary row rather than duplicating. The predicate is the same and the view
  will carry the verdict for those coordinates too, but the remedy differs (an ordering or a
  narrower path, not a DISTINCT view), so its message is a sibling rule reading the same view,
  not this one.
* Runtime deduplication of any kind.

## Related

* `roadmap/path-element-surface-cleanup.md` (R235), which separates join-shape from
  WHERE-filter on the path element. Any `distinct` flag lands there.
* `roadmap/lsp-reference-path-authoring.md` (R381), the authoring-time counterpart. Its
  diagnostic rung is about reachability, not cardinality, so there is no overlap in the
  verdict; but a fan-out hint while the path is typed reads off the same view, so one
  derivation can serve both. Worth revisiting once this rule exists.
* `roadmap/lint-rule-reference-page.md` (R592), the home for the rule's documentation row.
* `roadmap/javabean-unbound-input-field-lint.md` (R695), the nearest precedent for adding a
  `LintRule` arm to a silent-partiality gap, and the same noise question. This item answers its
  version of that question with a measurement, which is the method to reuse.
* `roadmap/list-ordering-invariant-enforcement.md` (R677), the closest structural analogue: an
  invariant about list results, enforced off one relation where every leak site is visible.
* `roadmap/derived-read-cost-is-a-shape-problem.md` (R876), which owns the frame the Cost
  section adopts: ownership over registration, and the lever order.
* The three-strata entry in `roadmap/changelog.md` (R712, shipped), which names the strata this
  rule sits across. The new `Source` arm is the lint vocabulary catching up with the derived
  stratum.

Coverage for this issue was found missing by
`roadmap/audits/2026-08-19-github-issue-roadmap-linkage.md`.

## Implementation notes

Every plan deviation, stated here rather than left in the diff.

**`DerivedReadCostTest` is touched after all, and the plan said it would not be.** The Cost
section reasoned that the gate prices registrations and this item adds none, which is true of the
gate's pinned pairs and not of its domain-size assertions: `theDomainIsTheSizeThisTestStates`
counts the views in the fact schema and the cells the registration matrix holds, and any new view
that reaches a registration moves both. This one drives from
`intent_field_reference_step_target`, so it inherits that walk's reach whole: 126 views to 127, 62
readers with cells to 63, and 144 cells to 146, the two new cells being the field-site hop
registration and the resolved type binding. The binding cell is monotonic. The hop cell is not,
and it joins `KNOWN_NON_MONOTONIC` on exactly the mechanism the row above it records for the walk
itself: 20758 scans registered against 19342, and 53 milliseconds against 368. Each figure sits
on the row it prices. `DetectionReadReachGateTest` and `MaterializeRegistryGateTest` are untouched
as planned, the producer not being a member of the detection pass and no registration being added.

**The read's absolute cost was measured and one rewrite lever taken.** 20758 scans on the gate's
twelve-unit fixture is twenty-six times the walk the rule drives from, and about two thirds of it
is the catalog join the predicate is rather than the walk. The first shape measured 31136, with
each hop's contribution to the bound column set written as two union arms selected on the key's
direction; folding each pair into one arm with the direction inside the join predicate is what
took off the third. It stays a plain view on the Cost section's own terms: the lever order is
capture, index, rewrite, registration, the rewrite is taken, and no measurement on a populated
store says to go further. The figure is recorded on the gate's new row so the next reader does not
re-take it.

**The glob matcher was lowered rather than duplicated, which the round-3 note anticipated.**
`LintEngine.globToPattern` was private to a class in `graphitron`, and the producer sits in
`graphitron-model`. The note named two ways out; this takes the first. `ExcludedTypes` in
`no.sikt.graphitron.model.lint` compiles a `LintConfig`'s globs and answers by name, the engine
delegates to it, and the producer applies it at the coordinate it is about to attach a finding to,
so the population rule has one statement. The consumer-facing prose in
`mojo-configuration.adoc` is corrected in the same commit, its "engine-scoped" sentence having
become false: the exclusion now reaches every rule that has a type name to match against, and what
still escapes it is a classifier advisory, which carries no coordinate.

**The end-to-end fixture is `film -> inventory -> store`, not the `film_actor_note` walkthrough.**
The spec settled that no `init.sql` edit was owed, and the catalog turned out to hold a genuine
fan-out already: `inventory` is keyed on its own surrogate, which neither hop binds, so one film
stocked several times at one store yields that store several times. The seeded-store tier encodes
the `film_actor_note` shape as planned, the discriminating pair and the subset-direction case with
it.

**The lint-rule reference page does not exist yet, so the rule's row has nowhere to go.** The User
documentation section asks for one and names the item that owns that page; nothing in `docs/`
enumerates the rules today. The `@reference` page carries the property, the remedy and the five
boundaries, and names the rule id twice so a reader arriving from a build log lands somewhere. No
second home for rule documentation was invented, per the instruction.

**The finding's message follows the engine's own wording, not the plan's prose.** Existing lint
messages open with the coordinate, capitalised; this one does too, and the fix description is the
imperative phrase an editor shows on a quick-fix rather than a sentence.

**The `lint_finding` half of the suppression claim is asserted through the report.** The Tests
section asks that a disabled rule id remove the finding from the report and from `lint_finding`.
The filter runs last over the combined warnings and the store copy is written from what survives
it, by `DevMojo` in another module, so the report assertion is the one that carries the claim;
the pipeline case says so in as many words rather than implying a second assertion it does not
make.

**The temporal-key fixture declines rather than passing where it cannot run.** PostgreSQL 18 is
what accepts `WITHOUT OVERLAPS`, and the local-database profile's server is 16, so the case asks
the server its version and aborts with that reason instead of reporting a pass. It asserts the
disjunction the spec bounds rather than picking an arm: every uniqueness constraint the catalog
reports on the temporal table has a column outside the pair a path would bind, which a key
reported with its period column and a key not reported at all both satisfy, and a key reported
with the period column stripped does not.

That case has therefore not been observed passing. This session's database is 16 and the sandbox
has no container runtime, so what has been verified here is that it compiles and that it declines
with its stated reason rather than reporting a pass; CI, which runs `postgres:18-alpine` for two
execution tests already, is where it first executes. Whoever reviews this at the Done gate should
read its first green run rather than take the assertion on the page.

## Reviewer findings

### Round 1, Spec → Ready, session_01CRSGZMS3zykRiGCUW7p1f9, 2026-09-10

Revisions requested. The goal reads clearly and I could restate it without the plan in hand: a
list field whose `@reference` path passes *through* an intermediate table that can hold more than
one row per (entering key, leaving key) pair gets a build warning at that field's SDL coordinate,
suggesting the DISTINCT-view plus synthetic-FK remedy, with no change to emitted SQL and pure join
tables like `film_actor` staying quiet. The rule itself is the strongest part of the document: the
subset direction is argued correctly, the composite-constraint counterexample is right, and the
measurement's admission that the corpus cannot separate the two candidate predicates is the kind of
negative result specs usually bury. Two findings are against question 2 and block; two more are
against question 1's checkable claims and are substantial enough that I would not want an
implementer to start without them settled.

**1 (question 2, blocking). The argument-site substrate already exists, so the scope boundary rests
on a false premise.** "Where it lives" says the derived hop and target views are field-site only and
that covering filter paths means authoring their siblings first. Both siblings ship today:
`intent_argument_reference_step_hop` and `intent_argument_reference_step_target` are declared in
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql` and registered
`Arm.DERIVED` in `FactCaptureAgreementTest`, and the argument-site target view carries the identical
column list plus `argument_name`, `constraint_name`, `fk_on_from` and `position` included, so the
pair-coverage reduction reads it verbatim. `intent_input_field_reference_step_target` is a third
such view. Only `@referenceFor` genuinely lacks them: `intent_reference_for_application` is the sole
`intent_reference_for_*` relation. This is blocking rather than a factual nit because the spec turns
the premise into two decisions, the item's scope and a sentence the user documentation is told to
carry ("filter paths and `@referenceFor` are outside it today"), and the second would publish the
false reason as author-facing guidance. What would satisfy it: either bring argument-site and
input-field-site paths into scope, since the reduction is the same one, or exclude them on a reason
that holds, the semantic difference between a filter path and a projection path if there is one, and
say which of the two you chose. One honest caveat so you are not rediscovering it: the argument-site
view's own comment discloses an unexercised limit, that a path departing from a multi-table
polymorphic container conflates its `targets`/`candidates` counts across branches. That limit is on
the arity columns, which this rule does not read, so it does not block the extension.

**Response.** Taken the second way, with the reason found and stated. The substrate premise is
withdrawn everywhere it appeared: "Where it lives", the user-documentation sentence and "Out of
scope" now exclude argument-site and input-field-site paths because the generator lowers a filter
path to a correlated `EXISTS` semi-join (`ReachPath`, `ConditionGlueRenderer.reachExists`), in
which each parent row matches once however far the path fans out, so the property this rule
reports is absent there and a warning would be false by construction. The example schema's own
`filmsByBridgedActorFirstName` comment and the `matchesEachParentOnce` execution tests are cited
as the evidence, and a filter-site negative test joins the Tests section. `@referenceFor` stays
out on the substrate reason, which the finding confirms holds for it alone. The new `## Goal`
paragraph states the scope up front. The polymorphic-container caveat is noted and not carried
into the body, since the rule reads neither arity column.

**2 (question 2, blocking). "Where it lives" names the wrong module for the precedent, and the item
straddles a module boundary the spec does not acknowledge.** The spec calls
`graphitron/src/main/java/no/sikt/graphitron/rewrite/derive/` a build-time package whose members
query `intent_` relations during codegen, and puts `AuthoredClaimConflicts` in it. That package holds
two files, `ClaimDomain` and `DemandResidue`; neither queries the store, and `ClaimDomain`'s own
javadoc says it retires with the shadow that reads it. The thirty-member derive package the spec is
describing, `AuthoredClaimConflicts` included, is
`graphitron-model/src/main/java/no/sikt/graphitron/model/derive/`. This is not a path typo I could
fix in passing, because the correct path decides a placement the spec then has to state per piece:
`LintRule`, `LintFix` and `BuildWarning` are in `graphitron-model`, while
`LintRuleRegistryCoverageTest` and `GraphQLRewriteGenerator.withLintFindings` are in `graphitron`.
What would satisfy it: name the module for each of the five artefacts (the new view, the decoding
member, the new `Source` arm, the coverage-test extension, the fold-in).

**Response.** Corrected, and the placement stated per artefact. "Where it lives" now names
`graphitron-model/src/main/java/no/sikt/graphitron/model/derive/` as the derive package,
`AuthoredClaimConflicts` as the division-of-labour precedent and `UnlowerableOrderings` as the
shape precedent (a `StoreDetections` family with a `READS` set), and carries a five-row table
naming the module of the view, the decoding family, the rule and `Source` arm, the coverage
extension and the fold-in. One consequence surfaced by placing it: every existing family yields
violations, so the section adds the decision that this family yields warnings through a sibling
`warnings()` accessor rather than joining `violations()`. The `lint_finding` writer is corrected
too: it is the dev loop's `DevMojo` through `BuildWarningFacts`, not the generator.

**3 (question 1). The discriminating fixture, completed the obvious way, does not discriminate.**
`film_actor_note` is exactly as described in `graphitron-sakila-db/src/main/resources/init.sql`:
`PRIMARY KEY (actor_id, film_id, lang_code)` with `FOREIGN KEY (actor_id, film_id)` into
`film_actor`. But that foreign key is the entering hop, and it binds *both* `actor_id` and
`film_id`, so the table is entered on `(a, b)` of a `(a, b, c)` key, not on `(a)` as the stated
minimal shape has it. The spec says what it lacks is "an outgoing foreign key to leave by" without
saying which column that key may sit on, and the reading the corpus invites, `lang_code` into
`language`, makes `bound(T)` the whole primary key, so the rule clears the hop and the fixture goes
quiet. The leaving key has to be on a column outside the primary key. Since the spec elevates the
discriminating pair to an acceptance criterion, and correctly so, the recipe owes that constraint
explicitly.

**Response.** Taken, and the recipe found to need a tighter condition than the finding proposes.
The measurement section now states what a discriminating fixture must satisfy, `bound(T)`
strictly inside some key with no key inside `bound(T)`, and works `film_actor_note` through it:
the leaving key must sit on a column the entering hop already binds (`actor_id` to `actor` or
`film_id` to `film`) so that `lang_code` stays unbound. A key on `lang_code` goes quiet under both
readings, as the finding says; a key on a column outside the primary key, which is what the
finding's remedy invites, fires under both readings and so does not discriminate either. The
resulting coordinate `film -> film_actor -> film_actor_note -> actor` also serves the
two-intermediate test. The catalog-wide effect of a new foreign key in `init.sql` is flagged for
the implementation to weigh against an authored fixture, and the "only three-column key" claim
is withdrawn.

**4 (question 1). The ordering hazard is real and its code names describe a structure that no longer
exists.** I confirmed the hazard: `withLintFindings` is called at `GraphQLRewriteGenerator.java:684`
and `captureAndRead` at `:697`, so lint assembly does precede the capture and a store read from
`withLintFindings` would see the previous run's rows. The section's frame around that is stale.
`captureFactsAndDetect` appears nowhere in the tree. There are no longer "two paths that matter,
`runPipeline` and `validate()`": the class javadoc's "One body, five projections" says
`runPipeline` is the single body, and `validate()`, `capture()` and `buildOutput()` are projections
through it, so `buildOutput()` cannot order them the other way and the remedy is one call site
rather than "both paths". Rewriting the section is yours because its argument, not just its
identifiers, is built on the two-path shape.

**Response.** Rewritten around the single body. The section now describes `runPipeline` as the
one call site, the store as open only inside the continuation `captureAndRead` hands its
callback, and the placement as the family shape rather than a reordering: the verdict is
computed inside the window by the `StoreDetections` family and merged into the pass's warnings
afterwards, with the `disabledRuleIds` filter moved to run over the merged list.
`captureFactsAndDetect`, the two-path frame and the `buildOutput()` claim are gone. The warm-cache
hazard and the two-run ordering pin stay, the pin now guarding against a later refactor moving
the read back out of the window.

**5 (question 2). The gates a new `intent_` view owes, and R876's live claim on this substrate.**
Three gates fire on a new derived relation and the spec names none: `FactCaptureAgreementTest`'s
exhaustive registration map, `MaterializeRegistryGateTest`, and `DerivedReadCostTest`. More
consequentially, the Cost section reasons about the 70-second materialisation without naming
`roadmap/derived-read-cost-is-a-shape-problem.md` (R876), which is In Progress at priority 1, holds
that an expensive derived read is a modelling defect rather than something to buy off with a
registration, and is dissolving `meta_materialize` entirely. Its slice-1 commit is also the most
recent toucher of this file. The spec's own instinct, express the predicate over the target view
rather than re-walking and check the view family's indexes, is R876's position arrived at
independently, so this is mostly a matter of citing the frame instead of re-deriving it; but a new
view authored during that arc needs to say which discipline it lands under.

**Response.** The Cost section is rewritten under R876's frame and cites it: ownership rather than
registration, the lever order, and the view authored plain with the `graphitron` owner it
computes to. The three gates are named where they apply: `FactCaptureAgreementTest` for the
view's `Arm.DERIVED` registration, `DetectionReadReachGateTest` for the family's pinned reach
(edited in the commit that argues for it, per that gate's own instruction), and
`DerivedReadCostTest` and `MaterializeRegistryGateTest` stated as untouched because no
registration is added. The 70-second figure is put in its place: the hop table is already a
registered target whose reason records the target view at about three milliseconds, so what the
implementation owes is the reach pin, not a wall clock. R876 joins "Related".

Non-blocking, and stated only so they do not survive into implementation:

* `isPrimary()` is declared on `org.jooq.UniqueKey`, not on `org.jooq.Key`, which in 3.20.11
  declares `getTable`, `getFields`, `getFieldsArray`, `constraint`, `enforced` and `nullable`. The
  substantive claim holds: I re-checked both interfaces and the jar, and there is no period concept
  anywhere in that hierarchy and no `WITHOUT OVERLAPS` string.
* `film_actor_note` is not "the corpus's only three-column key". `init.sql` declares at least two
  others, a `(pk_a, pk_b, pk_c)` key and an `(s, k1, k2)` key, plus a four-column one. The property
  the fixture choice actually rests on, that it is a payload-carrying table with a composite key
  already reached by a two-column foreign key, is unaffected.
* `roadmap/three-strata-capture-derive-query.md` (R712) is a dangling path. The item shipped and its
  file was deleted at Done; `roadmap/changelog.md` carries the entry.
* I did not re-run the corpus measurement. The counts are not what any finding above turns on, but
  note that finding 1 changes the measurement's population if argument-site paths come into scope.

**Response to the non-blocking notes.** All four taken. `isPrimary` is attributed to
`org.jooq.UniqueKey`, with `org.jooq.Key`'s actual method list stated. The "only three-column
key" claim is dropped. The R712 citation points at its `roadmap/changelog.md` entry. The corpus
measurement is not re-run; argument-site paths stay out of scope, so its population is unchanged.

### Round 2, Spec → Ready, session_01NMdpgoUnNPHP51NMZXKP49, 2026-09-14

Revisions requested, on one finding. Question 1 passes. I could restate the outcome without the
plan in hand: a consumer whose SDL declares a list field whose `@reference` path runs through a
junction table carrying its own payload now gets a build warning at that field, naming the hop
that multiplies and pointing at a DISTINCT view as the remedy, where today the generator emits
the multiset silently and the duplicates arrive unannounced inside the child list; emitted SQL is
unchanged, a pure join table such as `film_actor` stays quiet, and a filter-site path or a
`@referenceFor` produces nothing. The outcome is reachable: every relation the predicate needs
exists as named, `sql_constraint`'s type is closed exactly as the spec says, `sql_index` carries
no uniqueness column, and `intent_foreign_key_column_pair` yields both sides per position, so
`bound(T)` is the union of two joins on it and nothing is recomputed. The subset direction, the
composite-constraint counterexample and the measurement's admission that the corpus cannot
separate the two candidate predicates all still read as the document's strongest work, and the
round-1 revisions landed: the filter-site exclusion now rests on the semantic reason and its
named carriers exist (`ReachPath`, `ConditionGlueRenderer.reachExists`, `FilterBinding.Remote`
into `BodyParam.RemoteColumnPredicate`), the derive package is named in the right module, the
discriminating-fixture condition is stated and `film_actor_note` matches it, and the Cost
section's quoted figure is verbatim in `intent_field_reference_step_hop`'s registration reason.

**1 (question 2, blocking). The ordering hazard has been closed in trunk, and the placement it
argues for is no longer forced.** "The ordering hazard" says `withLintFindings` runs *before*
`captureAndRead`, so a store read placed there would see the previous run's rows, and the section
makes that the reason the verdict has to be computed by a `StoreDetections` family inside the
window and merged back out. In the current tree the opposite holds. `runPipeline` opens the
window at `GraphQLRewriteGenerator.java:678` and calls `withLintFindings(schema, attributed,
store)` at `:696`, inside the continuation `captureAndRead` hands its callback, with the live
`StoreHandle`; the call site's own comment gives the spec's reason for it, that what feeds the
error stream has to come after the rows it judges. That handle goes to
`LintEngine.builtIn(...).run(attributed.registry(), attributed.injectedNames(), store)`, and the
engine threads `StoreHandle` through every visit into `SinkContext`. A store-reading lint rule
already ships: `DeprecationRecognizer` is three `store.dsl().select(...)` reads against
`graphitron_deprecated_directive`, `graphitron_deprecated_directive_argument` and
`graphitron_deprecated_input_field`, serving `NO_DEPRECATED_DIRECTIVE_USAGE`. This landed in
`cddf62c`, "Deprecation is a captured fact, and lint reads rows", on 2026-09-11, the day after
this file's last revision, so the hazard was real when it was written and is not a drafting
error.

This is blocking rather than a stale-prose note because the premise carries three of the five
artefacts. With the lint channel already inside the window and already store-backed, there is a
fork the spec does not know it has: the rule can be a lint visitor reading the new view through
the channel that exists, which needs no fourth `LintRule.Source` arm, no `warnings()` accessor on
`StoreDetections`, no fold-in and no filter move, since `disabledRuleIds` already runs last over
the combined list inside `withLintFindings`; or it can be the `StoreDetections` family the spec
prescribes. Three artefact-table rows and one of the four "things this spec decides rather than
leaves open" are consequences of the second, chosen against a constraint that no longer binds.
The argument for the fourth arm leans on the same drift from a second direction: it quotes
`LintRule`'s javadoc that `ENGINE` rules are "re-derivable from the AST alone", and
`NO_DEPRECATED_DIRECTIVE_USAGE` is an `ENGINE` rule that is no longer re-derivable from the AST.
The line the spec wants, transcribed AST facts against catalog facts, may well still earn a new
arm, but it has to be drawn on that distinction rather than on a sentence trunk has overtaken.

What would satisfy it: pick one of the two shapes and argue it on what is in the tree now. If the
family shape is still right, say why a set reduction over a derived view does not belong on the
per-node traversal that already reads the store, and keep the artefact table as it stands. If the
lint-engine shape is right, the artefact table shrinks, the `warnings()` and `Source`-arm
decisions go, and "The ordering hazard" becomes a short note that the hazard existed and was
closed, with the two-run pin kept as the guard against a later refactor moving the read back out.
Either way the section's premise sentence and the `disabledRuleIds` instruction need restating
against the current call site. I am not settling this in the review: which shape the rule takes is
what the split exists to keep with the author.

Non-blocking, and stated only so they do not survive into implementation:

* "The thirty-member package" is thirty-one files today. Nothing turns on the count.
* "Every family on the record today mints `ValidationError`s" overstates it. Two members,
  `keyProjections` and `nodeIdDecodeCoverage`, are not detections and mint nothing, as
  `StoreDetections`' own javadoc says. The decision the sentence supports is unaffected: there is
  still no `warnings()` accessor, and `violations()` is still the one assembly point.
* `intent_argument_reference_step_hop` is a plain view where its field-site counterpart is a
  materialized table under a `meta_materialize` registration. Not load-bearing while argument-site
  paths stay out of scope, but it would be if finding 1 of round 1 were ever revisited.
* I did not re-run the corpus measurement, as in round 1. No finding here turns on the counts.

**Response.** Both findings taken, and the shape question answered against the tree as it stands
rather than as the earlier draft found it.

The stale premise is withdrawn. "The ordering hazard" now says what is true: the hazard was live
when this item was filed, `cddf62c` closed it by moving `withLintFindings` inside the capture
continuation with the live `StoreHandle`, and the section keeps only the warm-cache reasoning and
the two-run pin, whose job is now to fail if lint assembly ever moves back out. The
`disabledRuleIds` instruction is gone, the filter staying exactly where it is.

On the fork, neither of the two shapes the finding named. The verdict is computed by a producer in
`no.sikt.graphitron.model.lint` beside `DeprecationRecognizer`, driving one statement from the
view and folded into `withLintFindings` beside `SessionStateWarnings` and
`DependencyVersionWarnings`. That keeps the single warning assembly point and the filter's current
position, needs no `warnings()` accessor and no `runPipeline` fold-in, and still reads the
recursive view once per answer rather than correlating it per node, which the lint engine's own
per-node traversal would do. "Where it lives" carries both refusals with their reasons:
`StoreDetections` is declined because that record's existing members earn their place by having a
second consumer and this verdict has one, the durable shared artefact being the view; the visitor
is declined on the read-shape rule in `fact-model.adoc`. The artefact table names the producer in
place of the decoding family, and `StoreDetections` is untouched.


#### Round 2 addendum, same session, after a `principles-architect` consult

Two corrections to my own round above and one finding I missed. The consult is read-only and
pronounces no verdict; everything below I re-checked in the tree before restating it.

**Correction to finding 1: the `Source` axis partitions on producer, not on evidence, so my
suggested basis for a fourth arm was wrong.** I wrote that the line the spec wants is
"transcribed AST facts against catalog facts". Read `LintRuleRegistryCoverageTest` rather than
`LintRule`'s javadoc and the axis is unambiguous: `everyEngineRuleIsRegisteredToExactlyOneVisitor`,
`classifierAdvisoriesAreNotRegisteredAsVisitors`, `codegenAdvisoriesAreNotRegisteredAsVisitors`.
Every assertion is about which producer mints the finding and therefore which completeness gate
owns the rule. It never partitioned on what a rule reads, which is why `cddf62c` left the enum
untouched when `NO_DEPRECATED_DIRECTIVE_USAGE` moved its evidence to store rows. So the drifted
javadoc sentence is a stale description of the ENGINE members, not a stale criterion. What follows
is cleaner than what I wrote: a rule minted by a visitor is `ENGINE` and needs no new arm; a rule
minted by a fourth producer the engine registry does not own earns one, and must be argued on
*that*, since drawing it on evidence would splice a second axis onto the producer identifier and
leave the next row-reading visitor ambiguous between two arms. `LintRule`'s ENGINE javadoc owes a
restatement on the producer axis either way. It is the sentence that produced this spec's
inference, and left alone it will produce the next one.

**Correction to finding 1: there are three shapes, not two.** My two-way framing missed the one in
between. A producer folded into `withLintFindings` beside `SessionStateWarnings` and
`DependencyVersionWarnings` already has the live `StoreHandle` and already sits above the
`disabledRuleIds` filter, so it needs no `warnings()` accessor, no fold-in in `runPipeline` and no
filter move, while still driving one statement from the view rather than correlating it per node.
That distinction is load-bearing rather than stylistic: `intent_field_reference_step_target` is
`WITH RECURSIVE` carrying window functions, and `fact-model.adoc` states the measured rule that
such a view is taken once per answer and paired on its key, never correlated per driving row (the
24 s to 2 s case). `DeprecationRecognizer` is the sanctioned opposite, keyed seeks into base
relations. A per-node visitor over 850 fields is the pathology, and the corpus currently yields
zero findings, so a green build would not reveal it. The choice is still the author's; what
changes is that the middle shape exists and the read-shape rule bears on it.

**6 (question 2, blocking). The silence the rule pronounces carries four meanings, and one of them
is the false negative the spec is otherwise strict about.** "Which elements the predicate can
judge" makes a `CONDITION` or `NAME_MATCH` intermediate produce no row, and pays for the silence
with a sentence in the user documentation. Absence on the new view would then mean: no
`@reference` here; the element was not reached (32 of 94 by the spec's own measurement); the
intermediate is undecidable because one side carries no constraint; and the pair genuinely is
covered by a key. The last is what the author-facing documentation teaches a quiet build means,
and the third is exactly what the spec calls "the failure mode this rule cannot afford: a warning
that stays silent teaches the author the path is a set". The subset argument refuses that
direction and the silence admits it back.

This is blocking because the project has already settled the move, on this substrate.
`fact-model.adoc` states it ("A relation whose absence is load-bearing owes that sentence") using
this very walk as its worked example, and `intent_field_reference_step_target`'s own comment
discharges it by deferring its other silences to `intent_condition_method_route_defect` so that
its absence means exactly one thing. A new view beside it that re-accumulates four meanings walks
that back. What would satisfy it: give the view a `verdict` column over a closed vocabulary, so
"not reached" stays the one silence it owns. Three things follow, and the spec already wants all
three: the consumer's decode becomes a total switch with the drift throw `UnlowerableOrderings`
models, the "no verdict" test asserts a named row instead of an absence (a test pinning a silence
cannot tell "declined" from "not implemented"), and the scalar-field sibling the Out of scope
section anticipates reads a column instead of re-deriving the predicate.

Three more, non-blocking:

* **`excludedTypes` lands differently per shape, and it is user-visible.** The engine applies it
  before dispatch; `withLintFindings`' own comment states that a finding minted outside the walk
  still fires on an excluded type. A consumer who excluded a type would keep getting fan-out
  warnings on it under every shape but the visitor one. Worth deciding rather than discovering.
* **The `sql_index` invariant has no enforcer.** "State it as an invariant in the implementation"
  is a comment, and the project's standing rule is that an invariant has an enforcer. Keeping the
  predicate wholly in the view's SQL makes the relations it names its declared reads and buys the
  gate; otherwise call it review-only rather than an invariant.
* **The fixture tier is already decided, which dissolves the `init.sql` weighing.** What a view
  returns given rows is pinned in `graphitron-model` against a seeded store, and behaviour is
  pinned at the pipeline tier. The subset-direction case, an FK on `(a)` against a `UNIQUE (a, b)`,
  is a seeded-store test needing no catalog and no `init.sql` edit; the end-to-end coordinate and
  message is pipeline tier. Stating that split removes the catalog-churn risk entirely.

**Response to the addendum.** All three taken.

The `Source` arm is now argued on producer identity, citing the coverage test's three assertions
rather than the enum's prose, and the spec says in as many words not to draw it on catalog facts
against AST facts because that would splice a second axis onto the producer identifier. The
restatement of `LintRule`'s `ENGINE` javadoc onto the producer axis is added to the same commit's
scope, since that sentence is what sent the earlier draft wrong.

The third shape is the one chosen; see the response above.

Finding 6 changed the rule section, not just the tests. The view now carries a `verdict` column
over a closed vocabulary, `FANS_OUT | COVERED | UNDECIDABLE_CONDITION_HOP |
UNDECIDABLE_NAME_MATCH_HOP`, one row per intermediate keyed on the entering element's coordinate,
so absence means only "the walk did not reach this element", the silence
`intent_field_reference_step_target` already owns. The paragraph states why the four-meaning
absence was wrong in this document's own terms, and the three consequences the finding predicted
are taken: a total decode with a drift throw modelled on `UnlowerableOrderings.Verdict.of`, tests
that assert a named row instead of an absence, and the scalar-field sibling reading the column.

The three non-blocking notes are taken as decisions rather than noted. The rule honours
`excludedTypes` through a shared matcher, with a fence saying this changes nothing for the
existing classifier advisories and that the glob rule must not end up stated twice. The
`sql_index` invariant is closed by keeping the predicate wholly in the view's SQL, so a widening
is an edit to the view's declared reads rather than a comment with no enforcer; the Cost section
carries the same decision from the other end, since the producer is not in
`DetectionReadReachGateTest`'s roster and would otherwise have pinned nothing. The fixture tier is
settled as seeded-store in `graphitron-model` for the discriminating pair and the
subset-direction case, pipeline tier for one end-to-end coordinate, which removes the `init.sql`
foreign key and the catalog churn with it; the `film_actor_note` walkthrough stays as the worked
shape the seeded rows encode.

**Authoring note.** These revisions were written by the round-2 reviewer's session at the user's
explicit instruction, so the same session is now this file's last committer and is disqualified
from the Spec → Ready gate. That gate needs a third session, disqualifying both
`session_018X5xFp3fLA3Nt8PPHY6yxz` and `session_01NMdpgoUnNPHP51NMZXKP49`.

### Round 3, Spec → Ready, session_018zHVNJtwc5ujitg7vcXGoX, 2026-09-14

**Signed off.** Both questions pass, and the document is clean enough that inventing a third
round would be the wrong service.

Question 1. Restating the outcome without the plan in hand: a consumer whose SDL declares a list
field whose `@reference` path routes through a junction table carrying its own payload gets a
build warning at that field's coordinate naming which hop multiplies and pointing at a DISTINCT
view as the remedy, where today the duplicates arrive unannounced inside the child list. Emitted
SQL is unchanged, `film_actor` stays quiet because its primary key is exactly the pair the two
hops bind, filter-site paths and `@referenceFor` produce nothing and the documentation says so in
each case's own terms, and the finding is suppressible by rule id and honours `excludedTypes`.

Viability checked rather than assumed, and every code, test and symbol the spec names exists as
named. The substrate: `intent_field_reference_step_target`'s `via` is closed over exactly
`KEY | TABLE | NAME_MATCH | CONDITION`, with `constraint_name` and `fk_on_from` documented NULL on
the latter two, which is what makes the two undecidable arms necessary and decidable;
`intent_foreign_key_column_pair` yields both sides per position, so `bound(T)` really is a union
over two joins on it; `sql_constraint`'s `CHECK` closes `constraint_type` over the three values
quoted, and `sql_index` carries no uniqueness column, while `JooqCatalog.IndexFacts` is
`(String name, List<String> columns)` as stated. The channel: `withLintFindings` is called inside
the continuation `captureAndRead` hands its callback with the live `StoreHandle`, folds in
`SessionStateWarnings` and `DependencyVersionWarnings`, and applies `disabledRuleIds` last over
the combined list, with the call site's own comment stating that a finding minted outside the
walk still fires on an excluded type. The precedents: `DeprecationRecognizer` is three store reads
in `no.sikt.graphitron.model.lint`; `UnlowerableOrderings` has both the drift-throwing `Verdict.of`
and a total switch with no `default` whose `KEY_CAPTURE_SCATTER` arm returns `Optional.empty()`;
`LintRule.Source` has the three arms, `CODEGEN`'s javadoc reads "a whole-build fact with no SDL
coordinate" verbatim, and `LintRuleRegistryCoverageTest` carries all three assertions the spec
keys the axis off. The quotations: `intent_field_reference_step_hop`'s `meta_materialize`
registration reason says "intent_field_reference_step_target from around thirty milliseconds to
three"; `fact-model.adoc:387` says "A relation whose absence is load-bearing owes that sentence"
about this very walk, and `:339` carries the read-shape rule and its twenty-four-second
measurement; `filmsByBridgedActorFirstName`'s schema comment is verbatim. The fixtures:
`film_actor_note` is exactly the stated DDL, `referenceFilter_reverseDirectionFkHop_matchesEachParentOnce`
exists, `postgres:18-alpine` already runs in two execution tests, `intent_field_reference_step_target`
is registered `Arm.DERIVED`, and all eight cited roadmap paths resolve with R712 pointing at its
changelog entry.

Question 2. The shape extends what is in the tree at every point rather than standing anything
beside it: view plus thin decode is `AuthoredClaimConflicts`, the verdict column with a total
decode is `UnlowerableOrderings`, the store-reading lint producer is `DeprecationRecognizer` in
the same package, and the fold-in sits beside two existing fold-ins inheriting the same filter at
the same point. Both refusals hold on inspection. `StoreDetections` genuinely gates membership on
a second consumer, and `violations()` is the one assembly point. The visitor refusal is the
stronger of the two and is the right call: the target view is `WITH RECURSIVE` with window terms,
so a `FIELD_DEFINITION`-grain visitor over the corpus's 850 fields is precisely the pathology
`fact-model.adoc` priced, and a zero-finding corpus would not reveal it. I would hand this to an
implementer as written. The artefact table names five artefacts with modules, the test list names
a tier and a pinned property per entry, the acceptance criterion is a discriminating *pair* with
the condition a fixture must meet stated and both failing completions named, and the gates are
named both where they apply and where they deliberately do not.

Non-blocking, stated only so they do not survive into implementation:

* **The coordinate the verdict view is keyed on is not a key on the substrate.** "One row per
  intermediate, keyed on the entering element's coordinate (`graph_name, type_name, field_name,
  ordinal, position`)" holds only where the entering element resolved to one row.
  `intent_field_reference_step_target.candidates` counts exactly the case where it resolved to
  more (one table reached by three foreign keys is three rows at one position), and `bound(T)`
  differs per route, so the stated key would not hold and a producer joining on it could see two
  verdicts at one coordinate. The walk already requires `candidates = 1` for an expressible hop,
  so the narrowing costs nothing, and the tree has the pattern: `intent_field_column_scope_live`
  reads this same view under `WHERE tg.targets = 1`. A line in the view body, not a redesign, but
  it is owed before the key sentence is true.
* **The `sql_index` invariant's enforcer is weaker than the sentence claims.** `meta_relation`
  gives `sql_constraint` and `sql_index` the same `catalog` owner, so
  `MetaDeclarationGateTest.aDeclaredViewReadsOnlyWhatItsOwnerMay` would not reject a view that
  widened from one to the other. Keeping the predicate in the view's SQL buys visibility, which is
  real, but not the rejection "state it where something holds it" promises. The decision itself
  stands on the Cost section's independent reason; only the enforcement claim overshoots, and the
  honest repair is to call this half review-only.
* **The shared glob matcher crosses a module boundary the artefact table does not.**
  `LintEngine.globToPattern` is a private static in `graphitron`, and the producer is in
  `graphitron-model`, which `graphitron` depends on rather than the reverse. So "a shared point"
  means either lowering the matcher beside `LintConfig`, which is already in `graphitron-model`,
  or applying the exclusion at the fold-in. The spec's own fence, that the glob rule must not be
  stated twice, forces the choice and either lands, so this is a note and not a hole.
* **The producer signature named is not the precedent's.** "Takes a `DSLContext` and a graph name"
  where `DeprecationRecognizer` takes a `StoreHandle` and reads `store.graphName()` off it.
  `StoreHandle` carries both, so nothing is blocked.
* **The `CODEGEN` elimination is drawn on coordinate-ness, not on producer identity.** The
  paragraph insists the axis partitions on producer and then rules `CODEGEN` out because it is "a
  whole-build fact with no SDL coordinate, which this one has". That is `CODEGEN`'s own stated
  criterion so the elimination is correct on the tree's terms, but it is a shape test sitting
  beside an instruction not to splice a second axis. The arm still lands on the producer axis
  proper: the new producer is neither of the two `CODEGEN` fold-ins, has no visitor and no
  classifier emit site, and would own its own completeness assertion. Worth settling in the arm's
  javadoc so it states the criterion the coverage test actually keys off.
* I did not re-run the corpus measurement, as in rounds 1 and 2. No finding here turns on the
  counts.
