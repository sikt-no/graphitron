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
or documentation blessing the DISTINCT-view plus synthetic-FK pattern. They offered to have the
issue relabelled as an enhancement. The reply on the issue records the divergence this spec
arrives at.

## The rule

Their proposal, stated as a predicate: a path contains a hop into the *child* side of a foreign
key (1:N) and does not terminate in that child table. Mine, when this item was filed as a
stub, sharpened it by excluding a reverse hop whose FK columns are covered by a PK or unique
constraint on the declaring table, since such a hop is 1:1.

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
> columns inside `bound(T)`.

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
| **Findings under the pair-coverage rule** | **0** |

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
fan-out shape, so the corpus cannot currently witness the rule firing at all. Adding a fixture
that does fan out is therefore an acceptance criterion, not a nicety: without it the rule ships
proven only against the cases where it stays quiet.

One caveat on the population: 32 of the 94 authored path elements resolve to nothing the walk
can reach, and absence on that view means "not reached" rather than "resolves to nothing in
particular". Those coordinates get no verdict from this rule, which is right, since a path that
does not walk fails elsewhere and a fan-out warning on top would be noise.

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
* **The fixture the corpus lacks.** A genuinely fanning-out `@reference` path in the example
  schema, since the rule currently has no witness for firing at all. Pair it with the existing
  `Film.actors` shape as the negative case, which is the regression this rule's first two
  formulations would have shipped.
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
