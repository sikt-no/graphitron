---
id: R771
title: "A skill for authoring nested jOOQ at one grain, driven from the right relation"
status: Spec
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# A skill for authoring nested jOOQ at one grain, driven from the right relation

Nested jOOQ is authored in this tree at two surfaces: hand-written reads against the fact store
(the MCP and LSP fact classes, model-tier derivations, condition fixtures) and the generator's
own emitters, whose multiset, batch, and correlation shapes an agent edits when it works on
`ProjectionUnitRenderer`, `BatchedRowsFragments`, and their planners. Both surfaces have one
authoring discipline, and the tree has already written it down, sentence by sentence, in places
no agent reads before writing a query: pick the grain of the answer first (the natural key of
what one row means), drive the statement from the relation that owns that grain, and let every
child list be a correlated `MULTISET` hanging off the key its relation already declares. When an
agent skips that and reaches for Java instincts instead, the recorded failures follow: several
statements at several grains folded back together with an invented grouping key ("a relational
join written in Java", `docs/architecture/explanation/fact-model.adoc`), joins that fan out and
get papered over with `DISTINCT`, aggregates over the wrong grain, predicates bound to the wrong
alias (the grain-proof fixture in `ReferencePathConditionFixtures` exists because an emitter once
bound the hop-0 target alias as both parameters), and derived relations correlated once per
driving row at a measured factor of seventy. No skill or docs page names the grain and the
driving relation together as one discipline; that is the gap.

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

## Plan

Two deliverables, ordered. The doctrine sentence gets a durable home first; the skill then
curates and triggers, and "curation layer" is literally true rather than aspirational.

### Deliverable 1: the doctrine lands in the contributor tree

Grain-and-drive-from-as-one-decision is not published anywhere: it exists as per-site javadoc on
`ParentCorrelation` and `SchemaQueries`. A skill alone would mint the doctrine sentence in the
tree's least durable habitat while copying the parts that are published, and when
`fact-model.adoc` moves, nothing would fail. So the first deliverable is a short section in
`docs/architecture/explanation/fact-model.adoc`, beside the existing one-projection-per-grain
thesis, stating the authoring rule in one place: name the grain of the answer (the natural key
of what one row means), drive the statement from the relation that owns that key, attach the
rest by declared keys, and nest a child grain as a correlated `MULTISET` on its own key or as a
second statement paired on a real key, never folded in Java. If the paragraph outgrows the
explanation page during writing, it becomes a `docs/architecture/how-to/` page on authoring
store reads instead; that is a judgement call at implementation, not a fork needing review.

The cost half of the driving-relation rule stays where it already lives: `fact-model.adoc`
carries the correlated-versus-driven measurement and the "the view's own shape decides"
sentence. Neither the new section nor the skill restates those numbers; both cite it. The
broader H2 evaluation-model inventory (non-recursive `WITH` inlining, expression-keyed joins) is
R770's fork to place and this item neither pre-empts nor depends on it: nothing here needs those
facts beyond the already-published sentence.

### Deliverable 2: the skill

`.claude/skills/nested-jooq/SKILL.md`. The name is `nested-jooq`: the narrowest true label,
since the emitter half shrinks to a smell detector below; `query-shape` overpromises and a
grain-anchor name would bake in the colliding word the vocabulary ruling avoids.

The `description` front-matter is the only content that fires, so it is drafted here rather
than left to implementation:

> Author a nested jOOQ query at one grain: name what one row of the answer means, drive the
> statement from the relation that owns that key, nest child grains as correlated MULTISETs on
> their own keys, and pin the result with a seeded test. Use when writing or reviewing
> hand-written store reads (MCP, LSP, model derivations), @condition fixtures, or any query
> that returns duplicates, drops rows, puts rows under the wrong parent, or needs "how do I
> nest this".

The body encodes a procedure whose closing step is verification, because a skill in this tree
is a procedure over gated artifacts, not an essay:

1. **Name the grain first.** State what one output row means and which relation owns that key.
   For store reads the DDL's `COMMENT ON` prose is the grain register, surfaced by
   `StoreCatalog` and the rendered schema reference pages, with its standing stated honestly:
   the gate (`FactSchemaGateTest`) checks comment presence only, so a missing or vague grain
   sentence is work to do, not license to guess.
2. **Drive from the grain's owner.** The relation owning the answer's grain goes first in the
   `FROM` clause; everything else attaches by declared keys. Never fold a second grain into the
   projection; nest it as a `MULTISET` on its own key, or make it its own statement paired on a
   real key.
3. **Cardinality is declared, not discovered.** A join whose cardinality is not proven either
   fans out or silently deduplicates; `EXISTS` for boolean questions over non-unique paths,
   arity-preserving left joins for witnesses, and no `DISTINCT` to repair a shape chosen wrong.
4. **Per-surface guidance.** Store reads imitate `SchemaQueries` (graphitron-mcp) and verify
   against `SeededStore`. `@condition` fixtures keep the predicate bound to the calling
   fetcher's `FROM` clause per the custom-conditions manual page, and honour the N x M contract
   from the batching-model manual page, stated with its gap: the contract is developer
   discipline, not build-enforced, and R647 is the adjacent gate. The emitter surface gets
   three lines pointing the other way: on the emit side the plan already decided, so read the
   `ParentCorrelation` arm; finding yourself choosing a grain or a driving table inside a
   renderer is the smell of an incomplete command, not a query-authoring question.
5. **Close with the pin.** State the grain in the relation's `COMMENT ON`; pin what the view
   returns given rows with a seeded model-tier test, including where no row appears; a deep
   derivation owes a cost warning in its own comment. For emitter work the sakila SQL baseline
   tests pin the rendered shape; they are pins, not the specification, so a baseline is never
   read as the oracle for what shape is correct.
6. **Smells, one live exemplar each.** An invented grouping key (the `fact-model.adoc` thesis
   names the failure); grains folded in Java (`SchemaQueries` pairs on the type's own key
   "rather than a grouping invented here"); a deep derivation correlated per driving row (the
   `intent_column_match_claim` comment carries the measured factor); a predicate bound to the
   wrong alias (the grain-proof fixture in `ReferencePathConditionFixtures`).

**Citation policy:** the skill cites doctrine pages and class names, never `file:line`. Class
names are partly protected by the javadoc reference gate and the tests bearing them; line
numbers are protected by nothing. Manual pages are cited only where the reader plays the
consumer's role (the `@condition` section); everywhere else the sources are contributor-side
(`fact-model.adoc`, `emitter-conventions.adoc`).

### Vocabulary ruling

`grain` keeps its axiom-level fact-modelling sense; a query's grain is the natural key of the
answer it returns, so the senses agree and no second word is coined. The FROM-clause origin is
called **drive from / the driving relation**, the phrasing `fact-model.adoc` and
`SchemaQueries` already use. `anchor` is *not* used for it: the store's DDL says "anchored by"
over a hundred times meaning keyed into the existence-anchor relation, and the emit side has
`LaunchSource.AnchorTable` and the parent-anchor correlation arm; teaching "anchor on the
grain's owner" against that background would conflate a join target with a driving relation.
"Scope table" is not reused either, being taken by name-resolution scope
(`intent_argument_scope_table`).

## Boundaries against neighbours

R770 is the cost half of the same instinct problem and this item is the shape half: R770 makes an
agent measure relationally when something is slow, this one makes it design relationally before
anything runs. They stay separate because their triggers differ (slowness against authoring), and
the shared facts are single-sourced per Deliverable 1 rather than split between two ungated skill
documents. R723 is the build-time fan-out verdict; this skill is its authoring-time counterpart
and cites it rather than restating the lint's semantics. R647 (condition table-parameter
anchor assignability) and R698 (views carry keys, not payloads) are enforcement and store-side
doctrine respectively; the skill points at both.

## Out of scope

No build gate over the skill document itself. The exposure is named instead of gated: skill
prose citing class and page names can drift, and the citation policy above is the cheap
mitigation. A liveness check over symbol names cited in `.claude/skills/` is mechanically
available (the roadmap-tool citation scan already reads non-Java markdown and excludes the
skills directory deliberately, for worked-example ids rather than unreachability), so if drift
bites, that gate is its own Backlog item.

## Exit criteria

* `fact-model.adoc` (or a how-to page, per Deliverable 1's judgement call) carries the
  grain-and-drive-from authoring rule in one place; the docs build and the schema-identifier
  drift check stay green.
* `.claude/skills/nested-jooq/SKILL.md` exists with the description drafted above, the
  six-step procedure ending in the pin, the per-surface guidance including the emitter
  inversion, and the smells-with-exemplars list.
* The skill contains no `file:line` citations and restates no measured numbers; it cites the
  doctrine pages and class names that carry them.
* Roadmap README regenerated; roadmap-tool checks green.
