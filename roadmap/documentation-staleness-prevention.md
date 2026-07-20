---
id: R507
title: "Documentation staleness prevention: prose-truth rules, retirement sweep, retired-vocabulary guard"
status: Backlog
bucket: architecture
priority: 4
theme: docs
depends-on: []
created: 2026-07-20
last-updated: 2026-07-20
---

# Documentation staleness prevention: prose-truth rules, retirement sweep, retired-vocabulary guard

The repo has repeatedly paid for stale, misleading documentation: prose that was
true when written and silently falsified as the code moved. The cleanup arc is
long (R483 javadoc-implementation drift audit, R484 string-literal purge, R126
and R504 retired-vocabulary scrubs, R301/R307 `@record` retirement, R179, R15,
R495, R497) and the periodic staleness audits under `roadmap/audits/` keep
finding the same drift classes. The structural gap: existing enforcement covers
symbol existence and structural membership (the javadoc `{@link}` reference
gate, `RoadmapReferenceGuardTest`, the `*DocCoverageTest` family, the
roadmap-tool `--verify` regen gates) but nothing guards prose truth, whether a
sentence's factual claim about the code it names is still correct. R497 is the
canonical instance: a hand-maintained caller census in javadoc passed the
`{@link}` gate while being factually wrong, so it read as enforced while
drifting. Every cleanup episode above closed a prose-truth drift; none of the
surviving gates covers it.

Drift comes in two shapes needing different treatment. Token drift: prose names
a retired identifier (mechanically detectable). Mechanism drift: prose
paraphrases a retired design with no surviving token, e.g. "deferrals point at
a roadmap item" after `Rejection.Deferred` lost its plan pointer (not
mechanically detectable; preventable only by not writing mechanics into prose).

## Proposed direction

One escalating pipeline (declare, sweep, promote), landing in three places.
Validated against the principles by a principles-architect consult on
2026-07-20.

1. **Rewrite the "Documentation names only live tests/code" corollary in
   `docs/architecture/explanation/development-principles.adoc`,
   affordance-first** (in-place edit; the doc is budget-capped by
   `DocSizeBudgetTest`). Every prose claim about code is (a) anchored on a
   checked reference (`{@link}`), (b) pinned by a named test or type, or
   (c) written at intent altitude (the why and the shape) so it survives
   mechanical refactors. The smell is narrowly line-by-line narration of a
   body or mechanism, not orientation javadoc: the class-level on-ramp docs
   R35 wants (scope, entry points, model output, doc pointer;
   `GraphitronSchemaBuilder`'s top comment as exemplar) are the blessed
   (c) case and must be named as such, or this rule becomes the stick that
   kills them.

2. **Prefer generated docs for anything enumerable** (one clause folded into
   "Principles are stated at altitude"). Arm lists, coverage tables, and
   inventories should be materialized views regenerated and `--verify`-gated
   on every build (the `supported-directives.adoc` /
   `inference-axis-coverage.adoc` precedents). This is the only move that
   removes the drift class instead of policing it; hand prose falls through
   to rule 1 only when it is genuinely rationale.

3. **Retirement sweep in `roadmap/workflow.adoc`.** Item-file convention: an
   item that retires a symbol or mechanism lists the retired vocabulary in
   its body (safe from the inventories-rot critique precisely because the
   file is deleted at Done, and load-bearing because the fresh-context Done
   reviewer otherwise has no grep query). In Review -> Done checklist line
   beside the existing user-facing-doc check: the reviewer greps all prose
   surfaces (javadoc, comments, `.adoc`, user manual, fixture prose, test
   names, roadmap bodies) for the declared vocabulary and skims for
   paraphrases of the retired mechanism. Plus one agent-facing trigger line
   in `CLAUDE.md` pointing at the workflow rule.

4. **`RetiredVocabularyGuardTest`, populated on demonstrated recurrence
   only.** A registry-driven guard generalizing `RoadmapReferenceGuardTest`,
   scoped to habitats the compiler cannot see (`.adoc`, user manual, string
   literals, fixture prose; javadoc `{@link}` is already dominated by the
   reference gate). A term enters the registry when an audit finds it
   surviving a cleanup, not at every rename (the `Record*`/`Split*` family
   re-drifted three windows running and earns an entry; a one-off rename
   does not). The roster gets a reverse-enforcer: the test also asserts each
   registered term resolves to no live symbol, so a stale entry or a
   re-introduced name fails the build. The registry lives in the test, never
   in the principles prose.

5. **Honest residue.** Mechanism-paraphrase drift has no forcing function;
   the answer is rule 1 (do not narrate mechanics; prefer deletion over
   rewrite, per R504's mandate), swept by rule 3. R207 already proposes the
   "design doc says X, no test pins X" conformance audit; this item should
   cross-reference it, not duplicate it.

Likely splits at Spec: a principles/workflow text item (rules 1-3, 5) and a
guard-test item (rule 4).
