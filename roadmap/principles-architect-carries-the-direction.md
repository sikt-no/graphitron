---
id: R1163
title: "The principles architect carries the direction: the rulings live in the docs it reads, and every design is judged by what it lets us delete"
status: Ready
bucket: dx
theme: tooling
depends-on: []
created: 2026-11-09
last-updated: 2026-11-09
---

# The principles architect carries the direction: the rulings live in the docs it reads, and every design is judged by what it lets us delete

## Goal

Every session working on the rewrite, on this machine, in the cloud or elsewhere, is steered by the same direction, because the principles architect (the read-only `principles-architect` subagent an author consults while drafting) carries it. Today the design rulings Alf and Filip give reach a session only when someone relays them: most live in local agent memory on one machine, so sessions elsewhere never see them, and their work drifts and is caught late, at harvest or in CI. When this lands, each ruling lives in the published architecture docs the architect reads on every invocation, stated once with why and how to apply it, in the doc it belongs to (much of the direction is already there: `code-generation-triggers.adoc` for the occurrence model, `testing.adoc` and `development-principles.adoc` for the testing discipline). The architect judges a design by where the code is going, asking what it lets us delete and what dissolves, and names the store-side shape wherever a design extends the classified model, the walk, the `intent_` family or the derivation family. Consulting it is a named step in the workflow, before a node is taken up, at a design fork and before work is handed over for harvest. The architect stays advisory and read-only; the author weighs its advice, and the verdict stays with Alf and Filip, who work with the author. A new ruling enters the docs the day it is given, through an ordinary commit to trunk.

## Decided

* **R1163 rewrites the strata account and absorbs R983** (Alf, 2026-11-09). The store is the model and a
  query uses it: gatherers build the model in a declared order, each in a capture phase and an anchor
  phase, an anchor phase reads only upstream, and the docs stop speaking of strata. What R983 sketched
  moves here whole:
  * `fact-model.adoc` § "The three strata" is replaced by that account, with views as part of the
    model and the view-stack signal; `modeling-discipline.adoc` says capture phase against anchor phase
    where it said stratum one against stratum two.
  * The "Enforced by:" paragraph closing `fact-model.adoc` § "Provenance: every source is a fact, and
    the resolved value is another" is restated: a view's output is pinned by fact documents under
    `graphitron-model/src/test/resources/facts`, run by `FactExpectationTest`, not by a store seeded
    row by row, and the seeded class names go.
  * Every other "stratum" or "strata" is reworded to what it names: a phase, a family, a set of rules,
    or a section of the DDL. `DerivationStratum` itself is R876's to dissolve; this item rewrites the
    prose, DDL comments and javadoc around it so nothing calls it a stratum while it stands.
* **The store's defect model is authoritative for validation** (Alf, 2026-11-09). `defects-as-facts.adoc`
  is stale where it says a stored defect and a walk rejection both report until the walk's goes, and
  that most defects do not fail the build: `validate` and `generate` read the store's verdict first and
  stop on it, so an author meets one error. The page is updated here.

* **Grain, derived relations, anchors and views** (Alf, 2026-11-09). This replaces the account in
  `fact-model.adoc` § "Derived reads are views, not stored facts" and the architect's "A derivation
  stored where a view belongs".
  * Every relation has a grain, and every fact is about a grain: the grain is what the primary key
    says one row is.
  * A derived fact is stored in a relation too, written by its owner's stage from a rule view and
    marked and swept like any other, so it has keys, foreign keys and referential integrity.
  * Anchoring is establishing a new grain, a relation whose composite primary key keys something no
    existing relation does. A derived fact stored at an existing grain is a derived relation, not an
    anchor.
  * A plain view is for a trivial join and filter of existing relations.
  * No view reads another view, rule views included: H2's planner pays for it. A view's inputs are
    stored relations.
  * A rule view is simple. A complex rule view means a derivation was skipped at an earlier stage,
    and the fix is to store that derivation upstream, not to grow the rule.

## Where each ruling lands

A census on 2026-11-09 checked 29 design rulings, most of them held only in local agent memory,
against the docs: 6 are stated in docs the architect reads, 3 only in the user manual, 10 in part or
only in R876's plan, 8 nowhere. Each lands once, in the page that owns its subject, stated with why
and how to apply it; the architect reaches it by reading that page. `development-principles.adoc`
keeps its 3,500-word budget (`DocSizeBudgetTest`), so rulings go to the explanation and reference
pages and an axiom there gains at most a sentence pointing at them.

`fact-model.adoc`:

* § "The three strata" is replaced by the gatherer account under Decided, and § "Which stratum each
  family is in" becomes which gatherer and which phase owns each family.
* § "Derived reads are views, not stored facts" is rewritten to the grain ruling under Decided: grain,
  derived relations, anchors as new grains, plain views for trivial joins and filters, no view over a
  view, simple rule views.
* § "Ownership" gains: nothing is added to the derivation family, whose steps move to their owners'
  anchor phases; a dissolving relation (`intent_`, a retiring stage) is never re-keyed, migrated onto
  or extended, and a list touching one is sorted into stays, re-keys and dissolves first; a claim that
  nothing reads a relation also checks foreign keys, view bodies and rule `EXISTS` clauses, not only
  Java.
* § "The key discipline" gains: a line and column key a fact inside one document only; a reference
  across files anchors to a name, a list index or an `as:`.
* § "One base, many views" gains: each use of the store (a planner, an emitter's query, the LSP, the
  MCP surface) owns its own query, and what two uses share moves into the model as a relation.
* The "Enforced by:" paragraph closing § "Provenance" is restated as Decided says.
* Calling a `@reference` chain "a walk" goes: a resolved route from one table to another is a path;
  "the walk" names only the classifier being dissolved.

`modeling-discipline.adoc`:

* Stratum wording becomes capture phase and anchor phase.
* § "A corpus is captured into a family shaped by the questions" gains: the code family captures the
  generated jOOQ classes as code facts like any class, with no second reference to them, while which
  table a record class belongs to is the sql family's; and a relation may land before its reader when
  that reader is named.
* § "What a stored relation owes" gains the cascade rule: an entry and a derived relation cascade from
  what they hang off; an anchor's foreign key to its sources is `SET NULL`.
* § "Where it pinches" says new work reads no `intent_` relation, not only that it takes no shape from
  one.

`defects-as-facts.adoc`:

* The paragraph on a stored defect and a walk rejection both reporting goes. The store's verdict is
  authoritative: `validate` and `generate` read it first and stop, so an author meets one error.
* § "Adding a defect" gains: a defect raised on a valid schema is a regression, fixed ahead of planned
  work, never left as a known gap; and a defect's fact document shows the defect rows beside the fact
  rows.

`code-generation-triggers.adoc`, the rulings now stated only in the user manual:

* § "Path" gains the input path rules: inputs follow the output path rules with fewer forms; a
  `@nodeId(typeName:)` is table-bound and inferred as on output; a written path on an input may span
  several hops only where identity passes along composite keys.
* § "Producer" gains: a table record a `@service` or a write returns carries only its primary key, and
  its fields resolve by the lifts that follow, never by same-table rules; a `@table` child on its
  parent's table is refused unless the schema says how it is reached, by a nesting type or a
  `@reference` keyed on the primary key.

`testing.adoc`:

* § "Build commands" gains: a local build's large heap hides a heap regression that CI's default heap
  meets, so a change that adds or reshapes views over the path or occurrence relations runs the LSP
  tests at `-Xmx2g`.

Not carried: process rules of one machine (build turns, local repositories, harvest protocol), how
agents address Alf, and promises between two co-authors about a single change.

## The architect

`.claude/agents/principles-architect.md` changes from a referee of designs to the voice of the
direction:

* **Read first** is every invocation, in order: the strategic and technical principles, `fact-model.adoc`,
  `modeling-discipline.adoc`, `defects-as-facts.adoc`, `code-generation-triggers.adoc`, `testing.adoc`,
  then the pipeline overview and the index. Touched-only: the manual's directive reference when the
  design changes what an author writes, `naming-the-row.adoc` when it names relations, and the R876
  nodes it touches. The duplicate listing of `code-generation-triggers.adoc` goes.
* **The first question is what the design lets us delete.** For each design it names what dissolves
  (a classified-model type, a walk branch, an `intent_` or derivation relation, a command arm) and,
  where the design extends one instead, the store-side shape that replaces it, citing the page and
  section where the rule lives.
* **The modelling checks follow the grain ruling:** what grain a fact is about and whether a relation
  already keys it; whether a derivation is trivial enough for a view; whether any view reads a view;
  whether a rule view is complex enough that a derivation was skipped upstream. The "A derivation
  stored where a view belongs" check goes.
* **It stays advisory and read-only.** It gives no verdict; the author weighs it, and Alf and Filip,
  who work with the author, decide. It may say a design is clean.
* Its description names when it is called, so a session reading only the agent list knows.

## The workflow

* `CLAUDE.md` § "Development Workflow" and `roadmap/workflow.adoc` name the consultation as a step,
  not an option: when a node or item is taken up, at a design fork, and before work is handed over for
  harvest or review. The handover names what the architect raised and what the author did with it.
* A ruling enters the docs the day it is given: whoever hears it (normally the session harvesting for
  the item) commits it to the owning page, as a `docs` commit straight to trunk.

## Tests

The change is prose, and the existing gates hold it: `check-schema-identifiers` (every relation the
pages cite exists), `ArchitectureDocSymbolGuardTest` (every Java type they name exists),
`DocSizeBudgetTest`, `check-adoc-xrefs`, and `check-transient-citations` for `CLAUDE.md`. It reaches
`graphitron` and `graphitron-sakila-example` tests, so it owes the full verification build. Acceptance
is a trial: the rewritten architect, given two designs that recently went wrong (a defect that fired
on a valid schema; a view chain that cost the build), raises both from the pages alone.

## Retired vocabulary

* "stratum", "strata", in every form: "stratum one/two/three", "the three strata", "derivation
  stratum", "derived stratum", "capture stratum", "claim stratum", "diagnostics stratum"
* `DerivationStratum`, retired by R876 rather than by this item, with everything named after it

**The edge.** The rule is what the word names. Where "stratum" names the retired three-strata model,
it goes here. Where it names `DerivationStratum` and its steps, a class still standing that R876
dissolves, it goes with the class, so that R876's dissolution leaves no name behind and this item
renames nothing R876 is about to delete.

Changes here:

* prose in `docs/` outside `docs/history/`, `CLAUDE.md`, `.claude/agents/` and `.claude/skills/`;
* DDL comments in `graphitron-model.sql`, javadoc and comments in main and test sources;
* the test identifiers that name the retired model, with their `@DisplayName` text:
  `AstEntryIndexTest.thePopulationIsTheEntryStratum` and
  `WarmStartRefreshTest.aStoppedFirstStratumIsRepairedByTheNextCapture`.

Stays, and why:

* identifiers and strings naming `DerivationStratum`'s roster, which go with the class in R876:
  `StageOrderGateTest`'s `STRATUM` constant, `theRosterIsTheStratumItChecks`,
  `everyDerivedTableHasAWriterInTheStratum` and `everyRelationHasOneWriterInTheStratum`,
  `DerivationStratumCeilingGateTest` and its `theStratumWritesNoMoreRelations`, and their
  `@DisplayName` and `.as(...)` text;
* `StageProgress`'s log line `"graphitron: derivation stratum done in "`, which reports that class's
  run; it changes when the class goes, and `.claude/skills/store-performance/SKILL.md`, which tells a
  reader to look for that exact line, keeps quoting it until then;
* other roadmap items' bodies, corrected when each is next edited rather than in bulk, as the stage
  order gate's retired names were; a slug (`name-matching-stratum`) is an item's identity and is not
  renamed; `roadmap/changelog.md` is permanent and `docs/history/` is a record of the past.

`development-principles.adoc` sits at its 3,500-word budget by `DocSizeBudgetTest`'s count, so any
sentence an axiom gains displaces one.

## Reviewer findings

### Round 1: Spec → Ready (session_01WkW2JEeeQCuEvkzNXeRSgW, 2026-11-09)

Verdict: withhold, on one finding under question two. The goal reads on its own: a session anywhere
meets the same rulings because they live in the pages the architect reads, and the architect judges a
design by what it lets us delete. Every page, section, test, gate and symbol the plan names exists as
named, and the defect ruling matches the code. The plan extends the shapes already there (the agent
file, the owning pages, the existing doc gates) and stands nothing beside them. What stops it is the
edge of the retirement, which the Done reviewer will be checking.

**Finding 1 (question two: would an implementer take it as-is). The retired vocabulary reaches
further than the plan's rewrite, and the plan does not say where the edge is.** Retired vocabulary
says "stratum", "strata", "in every form", with only `DerivationStratum` excepted. Decided says what
gets reworded is "the prose, DDL comments and javadoc". The retirement sweep in `roadmap/workflow.adoc`
also checks test names and roadmap bodies. In the tree today, these sit between those two statements:

* test identifiers: the `STRATUM` constant in `StageOrderGateTest`, the methods
  `theStratumWritesNoMoreRelations`, `everyRelationHasOneWriterInTheStratum`,
  `everyDerivedTableHasAWriterInTheStratum`, `theRosterIsTheStratumItChecks`,
  `thePopulationIsTheEntryStratum` and `aStoppedFirstStratumIsRepairedByTheNextCapture`, and the
  class `DerivationStratumCeilingGateTest`, which R876 also names as its ceiling gate;
* a main-code string, `StageProgress`'s `"graphitron: derivation stratum done in "`. It prints in a
  consumer's build output, and `.claude/skills/store-performance/SKILL.md` tells a reader to look for
  that exact line;
* other surfaces: the bodies of other roadmap items (R876's among them) and the slug
  `roadmap/name-matching-stratum.md`, `roadmap/changelog.md` (permanent), `docs/history/`, and
  `.claude/skills/store-performance/SKILL.md`.

Without an edge, the implementer has to decide whether the build's log line changes and whether other
items' plans get edited, and the Done reviewer has nothing to judge that decision against. The plan
would satisfy this by saying which of these change, which stay, and why, and by making Retired
vocabulary match. If the log line changes, the skill that matches on it changes in the same commit.

Non-blocking: `development-principles.adoc` is at exactly 3,500 words by `DocSizeBudgetTest`'s count,
so the sentence an axiom may gain has to displace one. The test's own message says so; the plan could
say it too.

### Round 2: Spec → Ready (session_01WkW2JEeeQCuEvkzNXeRSgW, 2026-11-10)

Verdict: sign off. Finding 1 is met. Retired vocabulary now draws the edge by what the word names:
the retired three-strata model goes here, and `DerivationStratum`'s roster goes with the class.
Every identifier on both lists exists in the class named, every `Stratum` identifier in the Java
tree is on one list or the other, and R876's "`DerivationStratum` is gone" node already takes
`StageProgress` and `StageOrderGateTest`, so what stays has an owner. The budget note is taken up.
