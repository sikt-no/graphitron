---
id: R771
title: "A skill for authoring nested jOOQ at one grain, anchored on the right table"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# A skill for authoring nested jOOQ at one grain, anchored on the right table

Nested jOOQ is authored in this tree at two surfaces: hand-written reads against the fact store
(the MCP and LSP fact classes, model-tier derivations, condition fixtures) and the generator's
own emitters, whose multiset, batch, and correlation shapes an agent edits when it works on
`ProjectionUnitRenderer`, `BatchedRowsFragments`, and their planners. Both surfaces have one
authoring discipline, and the tree has already written it down, sentence by sentence, in places
no agent reads before writing a query: pick the grain of the answer first (the natural key of
what one row means), anchor the statement on the relation that owns that grain, and let every
child list be a correlated `MULTISET` hanging off the key its relation already declares. When an
agent skips that and reaches for Java instincts instead, the recorded failures follow: several
statements at several grains folded back together with an invented grouping key ("a relational
join written in Java", `docs/architecture/explanation/fact-model.adoc`), joins that fan out and
get papered over with `DISTINCT`, aggregates over the wrong grain, predicates bound to the wrong
alias (the grain-proof fixture in `ReferencePathConditionFixtures` exists because an emitter once
bound the hop-0 target alias as both parameters), and derived relations correlated once per
driving row at a measured factor of seventy. No skill or docs page names grain and anchor
together as one discipline; that is the gap.

## The doctrine already exists, scattered

The skill mostly curates sentences the tree has already earned:

* **The thesis.** "A consumer's answer is one projection at its own grain, never several grains
  folded back together in the consumer" (`fact-model.adoc`, which also enumerates the three ways
  the folded alternative fails: the invented grouping key can be invented wrong, consistency has
  to be argued rather than held, and the JDBC row count becomes the product rather than the sum).
* **Grain and anchor are one decision.** `ParentCorrelation`'s javadoc states it for the
  generated surface: a hop-0 filter reads the parent row, so the batch must be keyed on the
  parent PK and the query must anchor the parent table; "keying on anything coarser would hand
  two distinct parents one shared verdict", and the grain is a pure projection off the
  correlation arm so the two cannot drift apart.
* **One statement per grain, keys pair grains.** `SchemaQueries` in graphitron-mcp is the
  hand-written exemplar: two statements, one per grain, paired on the type's own key "rather than
  a grouping invented here", every child list a correlated `MULTISET`, "so a mis-paired child
  cannot arise from a projection that never joins siblings together".
* **Where the statement drives from decides the cost.** A windowed or deep derivation wants to be
  first in the `FROM` clause with witnesses joined in as arity-preserving left joins; a base
  relation correlated per row is an index seek that nests freely. "The rule is narrower than
  'avoid correlated subqueries' and sharper than 'measure it': the view's own shape decides"
  (`fact-model.adoc`).
* **Fan-out is a property, `DISTINCT` is a smell, `EXISTS` is the shape at non-unique
  cardinality.** SQL joins produce bags; a path that fans out returns duplicate rows correctly,
  and the recorded fixes are `EXISTS` where the question is boolean and a re-anchored projection
  where it is not, never a `DISTINCT` that changes semantics and breaks under pagination
  (R723's territory on the build-lint side).

## What the skill carries

A `.claude/skills/` document that triggers on "write a query against the store", "add an MCP/LSP
read", "this query returns duplicates", "rows under the wrong parent", "how do I nest this", and
encodes a procedure:

1. **Name the grain first.** State what one output row means and which relation owns that key.
   For store reads, the DDL is a grain register: every view's `COMMENT ON` states its grain and
   its silences, and `StoreCatalog` or the rendered schema reference pages surface it.
2. **Anchor on the grain's owner.** The relation owning the answer's grain goes first in the
   `FROM` clause; everything else attaches by declared keys. Never fold a second grain into the
   projection; nest it as a `MULTISET` on its own key, or make it its own statement paired on a
   real key.
3. **Cardinality is declared, not discovered.** A join whose cardinality is not proven either
   fans out or silently deduplicates; `EXISTS` for boolean questions, arity-preserving left
   joins for witnesses, and no `DISTINCT` to repair a shape chosen wrong.
4. **The worked examples to imitate.** The frozen SQL baselines in graphitron-sakila-example
   (`ProjectionSqlBaselineTest`, `BatchedChildSqlBaselineTest`, `ConditionSqlBaselineTest`) are
   exact-string contracts showing the correct multiset, scatter, and correlation shapes;
   `SchemaQueries` for the hand-written store idiom; the grain-proof condition fixtures for the
   anchor-binding hazard.
5. **The vocabulary, used precisely.** `grain` keeps its axiom-level fact-modelling sense (a
   query's grain is the natural key of the answer it returns, so the senses agree and no second
   word is coined); `anchor` is the FROM-clause origin the correlation binds against, as
   `LaunchSource.AnchorTable` and the custom-conditions manual page already use it; "scope table"
   is not reused, being taken by name-resolution scope (`intent_argument_scope_table`).

## Boundaries against neighbours

R770 is the cost half of the same instinct problem and this item is the shape half: R770 makes an
agent measure relationally when something is slow, this one makes it design relationally before
anything runs. They stay separate because their triggers differ (slowness against authoring), but
the Spec should cross-reference rather than duplicate the H2 evaluation-model facts, which live
with R770. R723 is the build-time fan-out verdict; this skill is its authoring-time counterpart
and should cite it rather than restate the lint's semantics. R647 (condition table-parameter
anchor assignability) and R698 (views carry keys, not payloads) are enforcement and store-side
doctrine respectively; the skill points at both.
