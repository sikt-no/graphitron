---
id: R723
title: "Warn when a @reference path traverses a 1:N hop into a further projection"
status: Backlog
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
this application has access in" reasonably takes it for a set.

The property is decidable statically. Each hop's direction is known from the foreign-key
metadata the joins are generated from: departing the table that declares the key is N:1 and
safe, arriving at it is 1:N and fans out. A path containing a 1:N hop that does not terminate
in that child table yields a multiset whenever the intermediate holds more than one matching
row, with no runtime insight needed.

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

Their two asks: a codegen or validator warning naming the multiset property, in the same class
of feedback as the existing deterministic-order requirement; and either an opt-in `distinct`
flag on `@reference`, or documentation blessing the DISTINCT-view plus synthetic-FK pattern as
the official answer for set-semantics fields. They offered to have the issue relabelled as an
enhancement, which is worth taking them up on.

## The verdict is nearly a query already

The fact base did most of this before the item was filed, which is the main reason to file it
now rather than as a classifier patch.

`intent_field_reference_step_target` (a derived-stratum view in
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`) walks a
field's `@reference` path recursively from the enclosing type's table binding and yields, per
path element: `from_*` and `to_*` endpoints, `constraint_name`, and `fk_on_from`, documented as
"TRUE when the departing table declares the foreign key; the element's direction". That is
exactly the N:1-versus-1:N signal, already computed per hop. The underlying
`intent_field_reference_step_hop` enumerates both orientations deliberately, "because a foreign
key is a hop in either direction and which one an element means depends on where the chain
stands".

So the reporter's rule is a predicate over rows that exist: an element with
`fk_on_from = FALSE` at a position short of the path's terminus.

The store also affords a **more precise** rule than the one they proposed, and the item should
take it. `sql_constraint.constraint_type` is closed over `PRIMARY KEY | UNIQUE | FOREIGN KEY`
and `sql_constraint_column` carries the column sets, so a reverse hop whose foreign-key column
set is covered by a primary key or unique constraint on the declaring table is 1:1, not 1:N.
The reporter's heuristic would flag a 1:1 detail table as fan-out; this refinement does not,
while their own genuinely-1:N path still fires. For a warning that has to stay quiet to be
worth having, that distinction is the difference between shippable and noise.

## What Spec has to settle

* **Which lint source.** This cannot be a `LintRule.Source.ENGINE` rule: those are documented
  as re-derivable from the AST alone, and this one needs catalog facts. So it is a `CLASSIFIER`
  advisory tagged at an existing emit site, or a new store-derived source. Deciding that is
  the first fork, and it decides where the rule's code lives.
* **Noise.** The same question `roadmap/javabean-unbound-input-field-lint.md` (R695) defers, in
  the same terms: how many existing schemas would light up, and whether the rule should be
  narrowed (only list-shaped fields, say) to stay credible. Measure it against the example
  schema and the fixture corpus before committing to the population.
* **Which fix, if any, the finding carries.** `lint_finding_fix` plus its ordered edits is a
  suggestion an editor offers, and the reporter's second ask fits there: name the DISTINCT-view
  pattern without committing to a `distinct` flag.
* **Scope beyond the field site.** The capture family is symmetric
  (`graphitron_argument_reference_step`, `graphitron_reference_for_step`), but the derived hop
  and target views are field-site only today. Filter paths and `@referenceFor` need the sibling
  views before the verdict reaches them, so state whether they are in scope or deferred.

The `distinct` flag itself is **not** in this item unless Spec argues it in. It is a directive
surface question, and `roadmap/path-element-surface-cleanup.md` (R235) owns that surface.

## Related

* `roadmap/path-element-surface-cleanup.md` (R235), which separates join-shape from
  WHERE-filter on the path element. Any `distinct` flag lands there, so the shape of this item
  depends on whether that cleanup goes first.
* `roadmap/lsp-reference-path-authoring.md` (R381), the authoring-time counterpart. Its
  diagnostic rung is about reachability, not cardinality, so there is no overlap in the
  verdict, but a fan-out hint while the path is typed reads off the same relation. One
  derivation, two consumers, is the outcome to aim for.
* `roadmap/javabean-unbound-input-field-lint.md` (R695), the nearest precedent for adding a
  `LintRule` arm to a silent-partiality gap, and the same noise question.
* `roadmap/list-ordering-invariant-enforcement.md` (R677), the closest structural analogue: an
  invariant about list results, enforced off one relation slot where every leak site is
  visible. Same problem shape, a property of the configuration that no check compares against
  the emitted SQL.
* `roadmap/condition-join-hops-in-reference-filter-paths.md`, adjacent in the path-hop area and
  not overlapping.

Coverage for this issue was found missing by
`roadmap/audits/2026-08-19-github-issue-roadmap-linkage.md`.
