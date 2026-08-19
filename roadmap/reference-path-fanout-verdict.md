---
id: R723
title: "Warn when a @reference path traverses a 1:N hop into a further projection"
status: Spec
bucket: validation
priority: 4
theme: diagnostics
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Warn when a @reference path traverses a 1:N hop into a further projection

A `@reference` path is mechanical foreign-key traversal, and SQL joins produce bags rather than
sets, so a list field over a path that fans out returns duplicate rows. That is the correct
result for the declared path. What is missing is any signal that the path has that property:
the generator emits the multiset silently, and a client reading a field like "the environments
this application has access in" reasonably takes it for a set. This item adds a lint rule that
says so at build time.

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
  side has no leaving hop, so there is no pair to cover and no verdict; that is a plain
  to-many list, exactly as the reporter said.
* It generalises past two hops. The predicate is per intermediate, so a five-hop path is five
  independent questions, and the finding can name the hop that multiplies rather than the path
  as a whole.

## Measured on the corpus

Numbers from the example schema's main execution, captured 2026-08-19 on trunk.

| Measure | Count |
|---|---|
| GraphQL fields in the graph | 850 |
| `@reference` applications | 79 |
| Path elements (`graphitron_field_reference_step`) | 94 |
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
discriminator beyond the two join keys. A payload-carrying join table with `PRIMARY KEY (a, b,
c)`, entered on `(a)` and left on `(b)`, is the minimal case: the correct rule fires because
`c` is bound by neither hop, and the inverted rule clears it. `film_actor_note` in
`graphitron-sakila-db/src/main/resources/init.sql` is already half of it (`PRIMARY KEY
(actor_id, film_id, lang_code)` with an FK on `(actor_id, film_id)`); it is the corpus's only
three-column key and no `@reference` path currently reaches it, so what it lacks is an outgoing
foreign key to leave by and a coordinate that walks through it. Pair that with the existing
`Film.actors` shape as the negative case and the two fixtures pin the direction between them.

One caveat on the population: 32 of the 94 authored path elements resolve to nothing the walk
can reach, and absence on that view means "not reached" rather than "resolves to nothing in
particular". Those coordinates get no verdict from this rule, which is right, since a path that
does not walk fails elsewhere and a fan-out warning on top would be noise.

## Which constraints may clear a hop

Not every PRIMARY KEY or UNIQUE row in the store carries a guarantee the generated join
inherits. Two kinds were raised in the review round on the issue, and they resolve differently.

**Partial unique indexes do not reach the rule, by construction.** Verified on the session's
PostgreSQL 16: a `CREATE UNIQUE INDEX ... WHERE ...` produces zero `pg_constraint` rows and
appears only in `pg_index`, and PostgreSQL rejects `ALTER TABLE ... ADD CONSTRAINT ... UNIQUE
(a) WHERE ...` as a syntax error, so a partial unique *constraint* does not exist as a thing.
Our capture takes UNIQUE and PRIMARY KEY rows from `Table.getKeys()`
(`CatalogFactCapture`, the `sql_` family load), which is jOOQ's unique-key model over declared
constraints, so a partial unique index never becomes a `sql_constraint` row. `sql_index` carries
no uniqueness column at all, so nothing else can leak one in either. The reviewer's
recommendation, treat them as non-covering, is therefore what the rule already does.

State that as an invariant in the implementation, because it is one an optimisation could
destroy: the rule reads `sql_constraint` and must never widen to `sql_index`. A future change
that captures index uniqueness has to carry the index predicate with it, since the generated
join does not include that predicate and the guarantee does not transfer without it.

**Temporal keys are a real hole, currently out of reach, and the safe side is the one we are
on.** A `PRIMARY KEY (a, b, valid_at WITHOUT OVERLAPS)` guarantees one row per *instant*, not
one row per `(a, b)`, so it must not clear a hop even when its column set is inside `bound(T)`.
Three findings bound the risk:

* **Neither jOOQ nor our capture can see it.** `org.jooq.Key` in 3.20.11 exposes `getFields`,
  `isPrimary`, `enforced` and `nullable`, with no period concept, and the jar contains no
  `WITHOUT OVERLAPS` string; `sql_constraint` has a `constraint_type` closed over `PRIMARY KEY |
  UNIQUE | FOREIGN KEY` and no period flag. An explicit exclusion is therefore not a predicate
  tweak: it needs a fact nobody captures, read outside jOOQ's model.
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
derived views at all. It can, and the pattern is established.

* **The derivation.** `graphitron/src/main/java/no/sikt/graphitron/rewrite/derive/` is a
  build-time package whose members query `intent_` relations during codegen.
  `AuthoredClaimConflicts` is the precedent to copy, including its division of labour: "the
  reduction itself ... lives in the view's SQL; what remains here is the decode of its closed
  vocabulary" into the values the report carries. So the pair-coverage reduction is a new view
  beside `intent_field_reference_step_target`, and the Java member decodes its rows.
* **The substrate.** `intent_field_reference_step_target` already yields, per path element,
  `from_*` and `to_*` endpoints, `constraint_name`, and `fk_on_from` ("TRUE when the departing
  table declares the foreign key; the element's direction"). `sql_constraint.constraint_type` is
  closed over `PRIMARY KEY | UNIQUE | FOREIGN KEY` and `sql_constraint_column` carries the
  column sets. Everything the predicate needs is a join away, with no new capture.
* **The warning channel.** `GraphQLRewriteGenerator.withLintFindings` is the single fold-in
  point, and the `disabledRuleIds` filter applies to the combined list there, so per-graph
  suppression comes free. `BuildWarning.LintFinding` carries the message, a `SourceLocation`, a
  `LintRule` and an optional `LintFix`. Storage into `lint_finding` happens through
  `BuildWarningFacts` off the suppression-filtered list, so a suppressed finding is absent from
  the store as well as the report.

Three things this spec decides rather than leaves open.

**The rule needs a new `LintRule.Source` arm.** The three existing arms are a declared
partition that `LintRuleRegistryCoverageTest` keys its completeness assertion off, and none of
them fits: `ENGINE` rules are "re-derivable from the AST alone" and this one needs catalog
facts; `CLASSIFIER` rules are "advisories the classifier already computes" and tagged at its
emit site, and this one is computed in the store instead; `CODEGEN` rules are "a whole-build
fact with no SDL coordinate", and this one has a coordinate. Add a fourth arm for
store-derived, coordinate-carrying findings and extend the coverage test's partition, rather
than mislabelling this rule into an arm whose stated meaning it contradicts. The arm is
reusable: every future verdict the derive package computes lands in it.

**The finding carries a fix, and the fix is the view pattern.** `lint_finding_fix` plus its
ordered edits is "a suggestion an editor offers, never a rewrite the build performs", which is
the right register for the reporter's second ask: name the DISTINCT-view plus synthetic-FK
pattern as the suggested remedy without committing to a `distinct` flag. The edits list stays
empty, since there is no mechanical rewrite here.

**Field-site paths only, in this item.** The capture family is symmetric
(`graphitron_argument_reference_step`, `graphitron_reference_for_step`), but the derived hop and
target views are field-site only, so covering filter paths and `@referenceFor` means authoring
their sibling views first. That is a bigger change than this rule and would be the tail wagging
the dog. State the gap in the rule's documentation so an author is not misled into thinking a
quiet build means a clean filter path.

## The ordering hazard

This is the one thing that would silently produce wrong findings, so it is called out
separately.

On the two paths that matter, `runPipeline` and `validate()`, `withLintFindings` runs **before**
`captureFactsAndDetect`. The comment immediately below the call says "Capture runs ahead of
validation ... so the store has to be filled before the verdict is pronounced", and that is true
of validation and not of lint assembly. `buildOutput()` happens to order them the other way.

Reading the store from `withLintFindings` as it stands today would therefore not read an empty
store, which would be obvious. The store is a warm cache that survives across runs, so it would
read **the previous run's rows** and report paths from the last build, which on an edited schema
means findings at coordinates that no longer exist and silence at ones that do. Either move the
capture call ahead of lint assembly on both paths, or thread the derived findings in after
capture. Whichever it is, pin the ordering with a test that edits a path between two runs in one
store and asserts the second run's findings describe the second schema.

## Cost

The rule adds a query to every build, and the 70-second cold materialisation above is a warning
sign rather than a measurement of what it would cost in process (the H2 shell reopened the file
and had no warm page cache). Take the real number before wiring it in, on the example's main
execution and on the fixture corpus. If it is material, the recursion is the suspect: the
predicate needs only the elements a path actually resolves plus their successors, so it can be
expressed over the target view rather than re-walking, and the view family's existing indexes
are the first thing to check. A rule that costs a noticeable fraction of a build to say nothing
on a clean schema is not worth its place, and that verdict belongs to the implementation with
numbers in hand.

## Tests

* **Unit / pipeline.** The predicate over authored fixtures: a pure join table stays quiet, a
  payload-carrying join table fires, a path terminating on the many side stays quiet, a path
  with two intermediates fires once and names the multiplying hop.
* **The discriminating pair the corpus lacks**, per the measurement section: a composite-key
  intermediate whose extra key column is bound by neither hop (fires), against the existing
  `Film.actors` shape (quiet). One test asserting both is what pins the subset direction; a
  fixture that only fires would pass under the inverted reading too, which is how that reading
  survived a measurement.
* **The direction, asserted as such.** A unit-tier case with an FK on `(a)` and a `UNIQUE (a,
  b)` on the arriving table, asserting the hop fires. This is the minimal counterexample to
  `bound ⊆ columns(constraint)` and it belongs in the suite under that name, so the next reader
  who thinks the subset looks backwards finds the answer in a test rather than re-deriving it.
* **The temporal key**, execution tier on PostgreSQL 18, per the constraints section: a key
  declared `WITHOUT OVERLAPS` does not clear a hop.
* **Suppression.** The rule id in `disabledRuleIds` removes the finding from the report and from
  `lint_finding`.
* **The ordering pin** described above.
* **Registry coverage.** `LintRuleRegistryCoverageTest` extended to the new `Source` arm, so the
  partition stays total.

## User documentation

A `@reference` page note stating the property and the remedy, and a row in whatever the lint
rules reference page becomes (`roadmap/lint-rule-reference-page.md`, R592, owns that page; this
item should not invent a second home for rule documentation). The note has to say what the rule
does *not* cover: filter paths and `@referenceFor` are outside it today, and a quiet build is
not a statement about them.

State the semantics plainly, in the reporter's own framing: the multiset is the correct result
of the declared path, the warning exists because the declaration is easy to misread as a set,
and the remedy is a view with set semantics rather than a flag that changes what the join means.

## Out of scope

* The `distinct` flag on `@reference`. It is a directive-surface question owned by
  `roadmap/path-element-surface-cleanup.md` (R235), and nothing here forecloses it.
* Any change to emitted SQL. This item emits a warning and nothing else.
* Argument-site and `@referenceFor` paths, per the scope decision above.
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
* `roadmap/three-strata-capture-derive-query.md` (R712), which names the strata this rule sits
  across. The new `Source` arm is the lint vocabulary catching up with the derived stratum.

Coverage for this issue was found missing by
`roadmap/audits/2026-08-19-github-issue-roadmap-linkage.md`.
