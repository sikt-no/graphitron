---
id: R998
title: "Four unindexed H2 statements cost the sis dev loop about a minute per run"
status: In Review
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-10-07
last-updated: 2026-10-08
---

# Four unindexed H2 statements cost the sis dev loop about a minute per run

## Goal

`mvn graphitron:dev` on the sis schema takes 2m57s to start. Every schema save reruns the same
pipeline: capture, the 28 derivation stages, and the generator pass. About 65s of that goes to
four store statements that H2 runs as nested loops, because no index serves the join. The goal is
to remove those 65s from startup and from every save, without changing any fact the store holds or
any diagnostic a consumer sees.

## Evidence

The evidence is a profile of sis at `b46296e19`, taken on 2026-10-07. It is a timestamped Maven
log plus JFR with a stack depth of 1024, and 80% of main-thread samples end inside H2.

Each fix below was timed as a standalone statement in H2 2.4.240 against a copy of the sis fact
store. That store is the H2 database `graphitron:dev` keeps under `target/graphitron-model/`.

All four costs have the same shape: a join that no index serves. H2 either compares every pair of
rows or re-runs a derived table once for every outer row.

**What the figures are.** They were taken on an analysed copy, outside the capture transaction.
The 65s is their sum, not a reading off the pipeline.

`fact-model.adoc` gives two reasons such figures can differ from what runs in place.
`OPTIMIZE_REUSE_RESULTS` hides repeats. And a capture into a populated store plans inside one
transaction, with only the `SELECTIVITY 1` partition declaration to go on.

Fixes 1 and 3 run in capture, so they are exposed to that second effect. The figures locate the
cost; they do not promise it. What the goal is judged on is the sis run under Verification below.

## Plan

One commit per fix. The four fixes are independent of each other.

### 1. Index directive applications by source position

`GraphQLAstCapture.directiveApplicationArguments` joins `graphql_ast_applied_argument_entry`
(6,375 rows on sis) to `graphql_directive_application` (6,902 rows). It joins on the position of
the `@` sign, `(graph_name, source_name, source_line, source_column)`, and it filters on the
application's `touched_at`.

Those four columns are not a prefix of the application's primary key,
`(graph_name, coordinate, directive_name, ordinal)`, so nothing serves the join.

Add `graphql_directive_application_position_ix` on the four position columns. Write it beside the
table, as the nearby `graphql_union_member` and `graphql_implements_interface` indexes are written,
with a comment that names the reader.

Notes on the index:

* The source columns are nullable for synthesised applications, which an index allows.
* The `touched_at` filter is a residual on the rows the seek finds, so it does not belong in the key.

Measured: 11.0s → 0.03s, with the same 6,375 rows. The index changes the DDL hash, so the store
path moves. That is expected.

### 2. Drive the argMapping carrier from `graphql_element`

`ArgMappingCandidates.carrier()` is a `UNION ALL` derived table. Its rows are the position whose
value an `@argMapping` reads, which can be an argument or an input field. It has two arms:

* an argument: `graphql_argument` joined to `graphql_argument_element`;
* an input field: `graphql_field` joined to `graphql_field_element`, and to `graphql_type` gated on
  `INPUT_OBJECT`.

The union projects `carrier_name`, `carrier_type`, `carrier_is_list` and `declaring_type`. Four
statements read it:

* `expand` left-joins it once per depth.
* `markContestedCarrierName` checks it with `EXISTS`.
* `seedCarrierItself` and `seedCarrierChildren` enumerate it.

In the first two, H2 re-runs the whole union for every candidate row, and there are 6,212
candidate rows at depth 0.

The `INPUT_OBJECT` gate asks a question the store has already answered.
`graphql_element.element_kind` is stamped at capture as `FIELD_ARGUMENT` or `INPUT_FIELD`, and its
column comment says that deciding it at the write "is what keeps every reader from asking that
question again".

So the carrier becomes one join chain:

1. Start from `graphql_element` on its primary key `(graph_name, coordinate)`, filtered to
   `element_kind IN ('FIELD_ARGUMENT', 'INPUT_FIELD')`.
2. Left-join `graphql_argument_element` on its `UNIQUE (graph_name, coordinate)`, then
   `graphql_argument` on its primary key.
3. Left-join `graphql_field_element` on its `UNIQUE (graph_name, coordinate)`, then
   `graphql_field` on its primary key.

The four carrier columns are `COALESCE`s over the two arms. `declaring_type` is the input field's
`type_name`, and it is null for an argument, as it is today.

One Java helper appends this chain to a join tree, given the element alias, and names the four
columns.

* The two readers join `graphql_element` from the candidate's coordinate, then call the helper.
* The two seeds start from `graphql_element` filtered to the graph, then call the same helper.

The carrier is then spelled once, in one shape. `carrier()` and its union are deleted.

The chain has to be a flat join tree, not a derived table. Wrapping each arm of the union in its
own derived table was timed (6.08s), and it is as slow as the union, because H2 does not seek into
a derived table.

At most one arm matches a coordinate. That follows from `graphql_element`'s primary key: one row
per coordinate, and so one `element_kind`.

The fix adds no relation. In `fact-model.adoc`'s lever order, storing a rule comes last. The keys
the chain seeks on already exist, and the rewrite brings the cost close to zero, so a stored
carrier table would buy nothing.

Measured on a copy of the sis store, with the earlier shape of this chain that went through the
`graphql_type` gate:

* `expand` at depth 0: 6.57s → 0.05s. The result is identical: 5,253 rows, 2,628 marked
  deprecated, none closing a cycle.
* `markContestedCarrierName`'s predicate: 2.5s → 0.06s, with the same zero matches.

The `graphql_element` chain seeks on the same `UNIQUE` and primary keys, plus one more primary-key
seek. The implementer re-times all four statements in the new shape, under the same conditions. If
the seeds regress, because they now scan `graphql_element` for the graph rather than unioning two
smaller relations, the fallback is this: the seeds keep a union whose arms call the same per-arm
join helpers, and the readers keep the flat chain. The fallback is a choice made on a measurement,
and the item records which one landed.

A stored carrier payload at the element grain is the modeling fix, and it is R1001. This item does
not need it.

### 3. Key the field and argument upserts on the primary key

jOOQ 3.20 renders `onDuplicateKeyUpdate()` on H2 as `MERGE … ON (pk match OR unique match)`
whenever the target table carries a second `UNIQUE` key. No index serves the `OR`, so each source
row scans the target.

There are three sites:

* `GraphQLAstCapture.fieldElements`, target `graphql_field_element`. Measured: 4.90s → 0.16s.
* `EmittedAnchor.fields`, target `graphitron_field`. Measured: 7.32s → 0.48s.
* `EmittedAnchor.arguments`, target `graphitron_argument`. It is the next method in the same
  writer, against the same kind of key. The profile does not name it, and it is folded in so that
  `EmittedAnchor` does not keep two upsert idioms side by side.

All three tables carry a primary key on the decomposed coordinate:

* `graphql_field_element` and `graphitron_field`: `(graph_name, type_name, field_name)`.
* `graphitron_argument`: `(graph_name, type_name, field_name, argument_name)`.

Each also carries `UNIQUE (graph_name, coordinate)`. Switch all three sites to
`onConflict(<pk columns>).doUpdate()`. No store code uses `onConflict` yet. The generator emits it
in `TypeFetcherGenerator`, so the jOOQ form is proven on this stack.

**The invariant becomes a constraint.** The coordinate is the key spelled out. Four tables gain a
`CHECK` saying so:

* `graphql_field_element` and `graphitron_field` get `CHECK (coordinate = type_name || '.' || field_name)`.
* `graphql_argument_element` and `graphitron_argument` get
  `CHECK (coordinate = type_name || '.' || field_name || '(' || argument_name || ':)')`.

`graphql_argument_element` is not an upsert target here. It is checked because fix 2's chain seeks
it by coordinate, and its twin `graphitron_argument` is checked.

These four constraints hold on sis today:

| table | rows that pass |
| --- | --- |
| `graphql_field_element` | 7,134 of 7,134 |
| `graphitron_field` | 8,436 of 8,436 |
| `graphql_argument_element` | 530 of 530 |
| `graphitron_argument` | 967 of 967 |

Every writer already spells the coordinate this way:

* `GraphQLAstEntries` writes the capture tables.
* `SeededStore`'s seeders write the capture tables through `SchemaCoordinateSyntax.ofField` and
  `ofArgument`.
* `EmittedAnchor` is the only writer of `graphitron_field` and `graphitron_argument`. It writes
  from `graphitron_field_authored`, `graphitron_field_minted`, `graphitron_argument_authored` and
  `graphitron_argument_minted`. Those views concatenate the same way.

GraphQL names cannot contain a dot or a parenthesis, so the spelling is injective.

The rest of the `*_element` family is left to R1000, which owns the conversion that each such
`CHECK` makes safe. That covers `graphql_directive_argument_element` and the coordinate of
`graphitron_field_chain_application`.

**Why a CHECK, and why a gatherer may carry one.** The DDL states some invariants of the capture
tables in a comment rather than a `CHECK`, "because a gatherer that refuses a row is a gatherer
that stops gathering". `NamedTypeHierarchyGateTest` records one constraint that was withdrawn after
it refused 103 captures.

Those were invariants that author input could reach: the SDL an author writes could break them.
These cannot be reached that way. The writer computes the coordinate from the same strings, in the
same call that computes the key, so no SDL can produce a row that fails the check.

A `GENERATED ALWAYS AS` coordinate would rule out a misspelling altogether. It would also change
the writer contract at every site, including `SeededStore` and the foreign key into
`graphql_element`. That is a family-wide modeling change, and it is weighed in R1000, not here.

**Behaviour change.** Today a row that collides on `coordinate` alone, under a different key,
overwrites the existing row. After the change, that collision is refused by the `CHECK` before any
upsert sees it. So the `OR` arm being removed could only ever fire on corrupt input.

R1000 adds the guard test that keeps the converted sites converted. Until it lands, a converted site
that slid back to `onDuplicateKeyUpdate()` would be slow but not wrong, because the `CHECK` keeps
the `OR` arm dead.

### 4. Read the claim conflicts before reading the claims

`AuthoredClaimConflicts.typeClaims` joins the `intent_authored_claim_conflict` view to
`intent_authored_type_claim`. `fieldClaims` joins the same view to `intent_authored_field_claim`.

The view has a `GROUP BY`, so `fact-model.adoc`'s rule applies: read the view once per answer and
pair it on its key. The rule's prescribed form is to drive the statement from the view.
`typeClaims` already does that, with the view first in `FROM`, and H2 still drives from the claims
and re-evaluates the view once per claim. The doc's form does not hold here, so the pairing moves
into Java.

On sis the conflict view has **zero** rows:

| statement | time | rows |
| --- | --- | --- |
| the view alone | 0.16s | 0 |
| `typeClaims`, as written | 4.6s | 0 |
| `fieldClaims`, as written | 5.8s | 0 |

Both run in `AuthoredClaimRejectionRows.derive`. `typeClaims` runs again from `typeGrain`, on the
generator pass's `StoreDetections` path.

The new shape has two parts:

* One shared reader returns the violated keys for a graph: type names for the type grain, and
  `(type_name, field_name)` pairs for the field grain.
* `typeClaims` and `fieldClaims` each take those keys in place of reading the view. With no keys
  they return an empty map without a statement. Otherwise they read the claims restricted to the
  keys: type names with `IN`, and field pairs with a row-value `IN`.

This replaces the existing two-argument entries; it does not sit beside them as an overload.

* `AuthoredClaimRejectionRows.derive` calls the key reader once and passes its result to both.
* `typeGrain` already reads the view itself, gated by `inDomain`. It collects the type names from
  that read and passes them on, so it no longer reads the view a second time.

The result is the same map, and the cost drops to the cost of reading the view.

Reading the stored `intent_authored_claim_rejection` rows in `typeGrain` instead was considered
and rejected. Those rows carry the rendered message, not the `Rejection` value and location that
`typeGrain` builds.

`fact-model.adoc` may owe a sentence: when H2 will not drive from the view even with the view first
in `FROM`, the pairing moves into Java. The implementer adds that sentence only if the
implementation confirms the planner behaviour. It is not part of the scope.

R817 is in the same file and has a related shape: `fieldGrain` reads a recursive view once per
conflict row. It stays separate. On a schema with no conflicts it costs nothing, which is why the
sis profile does not show it.

## Tests

**Behaviour.**

* **Constraint refusals.** A new test in `graphitron-model`, beside `NamedTypeHierarchyGateTest`,
  seeds one row whose coordinate does not match its key into each of the four tables. It asserts
  that the `CHECK` refuses each row with `IntegrityConstraintViolationException`. Existing capture
  tests cover the upsert path itself.
* **Carrier.** `ArgMappingCandidateTest` (pipeline tier, `graphitron`) pins the candidate set,
  including the contested carrier mark and the deprecated spelling. It must pass unchanged. One
  case is added: an `@argMapping` at a `FIELD`-kind coordinate whose field name matches a field
  of an input type. It asserts that the coordinate gets no carrier, which pins the `element_kind`
  filter.
* **Claim conflicts.** `AuthoredClaimConflictsTest` (pipeline tier, `graphitron`) pins the
  non-empty path through its seeded conflicts. It gains a case with no conflicts, which asserts
  that both claim maps are empty.

**Plans.** For fixes 1, 2 and 3, a test in `graphitron-model` runs the fixed statement under
`EXPLAIN ANALYZE` on a seeded store. It asserts the property the fix buys:

* fix 1: the join seeks `graphql_directive_application_position_ix`;
* fix 2: the carrier is reached by key seeks, not through a derived table;
* fix 3: the `MERGE`'s `ON` names the primary key only.

It asserts that property and not a whole-plan comparison. `fact-model.adoc` warns that reductions
miss changes when two plans are compared, but here there is only one plan, checked for one named
access path. If a seeded store too small for the planner to prefer the seek makes this assertion
meaningless, the implementer drops it for that fix and says so in the item.

**Not the goal.** None of these tests shows the 65s. The goal's evidence is the sis run below.

## Verification

Install the build. Then, in the sis checkout, run `mvn graphitron:dev -pl :sis-graphql-spec` with
this worktree's `aether.enhancedLocalRepository` flags, so that it resolves this build's jars.

The figure that counts is from a warm run:

* The first run after the DDL hash changes rebuilds the store, and fixes 1, 2 and 3 all change
  it. That run takes the cold cadence, analysing stage by stage.
* Every later save takes the warm cadence, inside one transaction. Saves are what the goal claims,
  so report the second run, and one save after it.

Compare these log markers against the profile's 2026-10-07 column, and record the before and after
numbers in this item at In Review:

* "deriving 28 stages"
* "derivation stratum done in"
* "initial run failed validation"
* "LSP listening"

## Implementation notes

All four fixes are in, one commit each, with the tests the plan names. What landed differs from
the plan in three small places, recorded here so the Done reviewer can weigh them:

* **Fix 2, flat chain.** The readers and the seeds share one `Carrier` record in
  `ArgMappingCandidates` that holds the aliases, appends the four left joins to a select's join
  tree and names the four columns. `carrier()` and its union are gone. The seeds read
  `graphql_element` for the graph and add `carrier name IS NOT NULL`, so an element with no
  declaration behind it seeds nothing, as the inner joins of the union did. The fallback (seeds
  keep a union) was **not** taken, and the sis re-timing below shows it is not needed.
* **Fix 4, no separate key reader.** Each caller already reads the conflict view for its own
  rows: `AuthoredClaimRejectionRows.derive` for every row it mints, `typeGrain` for the domain's
  type rows. Each now fetches those rows once and passes the keys off them to `typeClaims` /
  `fieldClaims`, which take a `Collection` of keys in place of the old two-argument entry. A
  shared reader would have read the view a second time in `derive`. The `fact-model.adoc`
  sentence was not added: the planner behaviour was not re-confirmed in this session.
* **Plan tests use plain `EXPLAIN`.** `UnservedJoinPlanTest` runs the production writers again
  through a statement-recording `DSLContext` and explains what they executed. Plain `EXPLAIN`
  shows the access path, which is the asserted property; `EXPLAIN ANALYZE` would execute the
  inserts a second time. The seeded store was large enough for H2 to choose the seek in all three
  cases, so no assertion was dropped. As a sanity check, the old `onDuplicateKeyUpdate()` shape on
  `graphql_field_element` explains as `tableScan` with `ON (… COORDINATE … OR …)`, and the new one
  as a `PRIMARY_KEY` seek.

## Verification results

Taken on 2026-10-08 on one machine, against one snapshot of the sis checkout: `9c4e2a611d` plus
uncommitted local edits, some of them in `sis-graphql-spec`'s schema and `sis-service`'s
conditions. Both builds ran against the same copy, so the snapshot is a constant of the
comparison, not a variable.

* **After** is trunk `8405ec308`, with all four fixes in.
* **Before** is `a174f94c4`, the parent of the first fix commit, so the comparison isolates this
  item rather than the day's trunk.

The figures differ from the 2026-10-07 profile, which is not recorded in this item. They are not
compared against it.

**How it ran.** Two deviations from the command under Verification:

* The sis siblings (`sis-jooq`, `sis-service`, `fs-extended-scalars`) cannot be rebuilt on this
  machine, because jOOQ codegen needs the Oracle database. Their already-built jars were installed
  into each build's private prefix, with the poms flattened the way the sis build does it.
* Every run used its own explicit `-Dgraphitron.store.directory`, and every run after the first was
  `-o`. That kept remote snapshot lookups out of the timings and kept the two stores apart. The
  stores also differ in DDL hash: `0727c4c27e00d52b` before, `2b0622db6d273878` after.

Each run was a startup to "LSP listening". Then one save appended a newline to `emne.graphql`,
and the run waited for the regenerate round. The file was restored before the next startup.

The machine was shared with other sessions' builds (load average 2.4 to 6.2 on 14 cores), so
single figures carry noise of tens of percent. The warm save, which is what the goal claims, was
taken twice for each build.

All times are in seconds, read off the log markers:

* **capture**: from the round's banner to "deriving 28 stages";
* **derive**: "derivation stratum done in";
* **generate**: from the stratum's end to "run ok" or "regenerate ok".

| run | build | round total | capture | derive | generate |
| --- | --- | --- | --- | --- | --- |
| cold startup | before | 146.5 | 54.2 | 50.8 | 41.4 |
| cold startup | after | 128.4 | 39.2 | 45.7 | 43.6 |
| warm startup 1 | before | 180.5 | 74.1 | 62.6 | 43.8 |
| warm startup 2 | before | did not finish | 69.7 | 59.8 | over 40 min, killed |
| warm startup 3 | before | 198.6 | 80.7 | 72.1 | 45.8 |
| warm startup 1 | after | 140.0 | 45.2 | 47.3 | 47.5 |
| warm startup 2 | after | 106.6 | 32.5 | 39.5 | 34.6 |
| save 1 | before | 233.9 | 94.0 | 86.8 | 53.1 |
| save 2 | before | 183.4 | 84.6 | 61.7 | 37.1 |
| save 1 | after | 106.0 | 35.8 | 36.1 | 34.1 |
| save 2 | after | 104.5 | 28.0 | 39.2 | 36.2 |

Every round that finished passed validation; none logged "initial run failed validation".

**What the figures show.**

* A warm save went from 183 to 234 seconds, down to 105. That is 80 to 130 seconds per save,
  more than the 65s the plan summed from analysed copies. That fits `fact-model.adoc`'s warning
  that the in-transaction warm cadence plans without fresh statistics, so it pays more for a
  nested loop than an analysed copy shows.
* The saving is in capture (85 to 94 down to 28 to 36) and derivation (62 to 87 down to 36 to 39).
  The generator pass shows no consistent change.
* Cold startup moves less, 146.5 to 128.4, because the cold cadence analyses stage by stage.

**One unexplained stall, before the fixes only.** The before build's second warm startup finished
derivation in 59.8s. Its generator pass then logged nothing for more than 40 minutes, and the run
was killed. The next before startup, against the same store, finished its generator pass in 45.8s.
No thread dump was taken during the stall, so its cause is unknown. Fix 4's statements run in
that pass, on the `StoreDetections` path, but nothing here ties the stall to them. No after run
stalled, in four rounds.

**Fix 2 re-timed in the new shape.** `ArgMappingCandidates.derive` ran on a copy of each warm store,
inside a rolled-back transaction, twice per copy, with each statement timed:

| statement | before | after | rows |
| --- | --- | --- | --- |
| `seedCarrierItself` | 0.29 / 0.11 | 0.21 / 0.22 | 3,055 |
| `seedCarrierChildren` | 0.14 / 0.09 | 0.13 / 0.13 | 2,628 |
| `markContestedCarrierName` | 5.37 / 4.72 | 0.11 / 0.11 | 0 marked |
| `expand`, depth 0 | 8.77 / 9.71 | 0.26 / 0.24 | 5,253 |
| `expand`, depth 1 | 2.09 / 1.57 | 0.14 / 0.09 | 1,373 |
| whole `derive` | 18.8 / 17.1 | 3.1 / 1.6 | 13,280 |

Every statement returns the same row count in both shapes. The seeds did not regress, so the
seed fallback is not needed.

**CHECK counts.** On the before store, which carries none of the new constraints, 967 of 967
`graphitron_argument` rows and 8,409 of 8,409 `graphitron_field` rows already satisfy the
coordinate-equals-key `CHECK`. The after store holds the same counts.
