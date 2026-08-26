---
id: R838
title: "Agents write archeological, deliberation-narrating javadoc; the conventions and guards only cover roadmap citations"
status: Spec
bucket: dx
priority: 3
theme: docs
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# Agents write archeological, deliberation-narrating javadoc; the conventions and guards only cover roadmap citations

Agent-authored javadoc in this tree keeps coming out dense and rambling, and the density has a recognizable shape: the author narrates its own working session. Having just weighed alternatives and debugged pitfalls, the agent writes the deliberation and the war story into the comment, addressing the reviewer of the change ("here is why my choice is sound") instead of the next reader of the file. Call this *archeological* style: prose that is only true relative to a previous version, a rejected draft, or the session that produced the code. `InlineMultiplicityCheck`'s class javadoc is a live specimen ("Two parsing details are load-bearing and both were learned the hard way", and the sentences defending "Reports rather than gates" against an imagined objection; the report-versus-gate claim itself is enforcer declaration and stays, only its defense is the smell), and "rather than / instead of" rationale clauses, the tell of documenting alternatives that are not in the code, appear 40+ times in roadmap-tool main sources alone. The wanted style is *declarative*: state what the code guarantees and the constraint that forces its shape, as if the code had been born this way. Document why; do not document why-not.

Three mechanisms keep the archeological style alive despite existing guidance:

1. **The rule is three words.** "Prefer terse over verbose" sits mid-paragraph in a CLAUDE.md section that is otherwise entirely about roadmap-citation hygiene. Agents read that section as "don't cite R&lt;n&gt;" and move on.
2. **Mimicry backfires.** Agents match surrounding comment density, and the surrounding density is already bad; every dense class teaches the next agent. The existing corpus is a stronger style signal than any rule.
3. **The principles doc half-blesses it.** `development-principles.adoc` endorses intent-altitude prose ("the why and the shape") and names only narrow smells (line-by-line narration, hand-maintained caller censuses). Deliberation-narration passes that smell test, so agents write four paragraphs of "why" and feel principled. The doc distinguishes what-narration from why, but not why from deliberation.

Everything in this repo that is mechanically enforced (roadmap citations, adoc tables, module enumeration) got fixed by being enforced; comment style has no gate, so it drifts.

## Plan

Layer the fix the same way the roadmap-citation fix was layered: a written convention, a principles hook the reviewer gates can cite, and a narrow build-time guard whose job is to make the rule fire where agents actually see it.

### 1. CLAUDE.md: a comment-style section of its own

Split comment *style* out of the citation-focused "Javadoc conventions" section into its own named section, so it cannot be skimmed away as citation hygiene. The rules, stated testably rather than as adjectives:

- **The day-one test.** Write every comment as if the code had been born this way, and let content outrank surface form. A sentence whose only content is the authoring session ("learned the hard way", "historically", "this change") gets cut. A real constraint phrased archeologically ("an earlier version of this case asserted over two empty partitions and survived an unscoped DELETE") gets restated in present tense ("the non-emptiness assertions are load-bearing; a case asserting over an empty partition passes vacuously"): the constraint is live, only the phrasing is history.
- **Why, not why-not.** State the constraint that forces the code's shape; do not narrate rejected implementations. "X rather than Y, because Z" collapses to "X, because Z" when Y is merely a draft the author considered. The line is drawn by subject, not by grammatical form: why-not about the code's own boundary is a fact about the code and stays. That covers disclosed coverage gaps (a guard's javadoc owning what it does not catch), the meaning of a silence, a measured counter-result (the fact model's priced rejected rewrites), and the genuine trap where the obvious alternative is wrong in a way that will tempt a future editor to regress it.
- **Paragraph admission, not a length budget.** Class javadoc opens with one sentence of what. Every paragraph after that must state a constraint a reader could violate (a boundary, a floor's purpose, a habitat exclusion, a trap); a paragraph that defends the choice against an objection gets cut. Stated as content rather than length, the rule needs no exception list: `GraphitronSchemaBuilder`'s two-paragraph on-ramp passes (each paragraph carries a boundary or call-order claim), `InlineMultiplicityCheck`'s "learned the hard way" paragraph fails. Rationale bigger than constraint paragraphs belongs in `docs/architecture/`, linked.
- **Break the mimicry loop explicitly.** The dense javadoc already in the tree is precedent for nothing; do not match it. Without this line the admission rule loses to the match-surrounding-style instinct every time. Pair it with the em-dash stance: the existing corpus is not a cleanup backlog, rewrite on touch only.

### 2. development-principles.adoc: name the smells

Extend "Documentation names only live tests/code" so archeology and rejected-implementation narration join line-by-line narration and caller censuses as named smells, scoped explicitly to the comment/javadoc habitat, and draw the line the principle currently misses: *why* is the constraint that makes the code correct; *deliberation* is the process that found the constraint. Document the first, discard the second. The smell must be stated by subject so it cannot swallow the two why-not forms the repo's own method requires: disclosed coverage gaps and measured counter-results (`fact-model.adoc` prices its rejected rewrites in seconds precisely so the next agent does not re-attempt them). The doc is size-budgeted by `DocSizeBudgetTest`, so hold the addition to clauses on the existing smell sentence; if it cannot fit, the displaced words are a deliberate implementation-time choice, not a surprise at build time. The smell's *Enforced by* line names a pair: the phrase guard below for the named subclass, review for the rest. The Spec → Ready and In Review → Done reviewer gates already cite this principle, so this gives them something concrete to point at.

### 3. A narrow mechanical guard

A sibling of `RoadmapReferenceGuardTest` (same comment-region lexing as `RoadmapReferenceScanner`, whose `scanSource(Path, String)` shape is already unit-testable over in-memory strings) that fails the build on unambiguous archeological tells in comment and javadoc regions across main and test sources. Seed phrase list: "learned the hard way", "historically", "in an earlier version", "in a previous version". Admission criterion: a phrase qualifies only if every plausible comment use narrates repository history rather than program behavior. That keeps "previously", "no longer", "used to", and "rather than" off the list; each has legitimate present-tense or dataflow readings ("a module the pom no longer declares", "used to resolve the trace glob"), and "rather than" is sometimes legitimate why. This is deliberately a lower admission bar than `RetiredVocabularyGuardTest`'s demonstrated-recurrence rule: the seeds enter on the convention's launch evidence; later additions follow the recurrence bar. The guard's failure message teaches the day-one rule, mirroring how the citation guard's message teaches its rewrite rule.

Three honesty requirements, each following an in-tree guard precedent:

- **Disclosed residue.** The guard's javadoc states its scope precisely: it catches the named phrase subclass and nothing else; declarative style at large stays review-enforced (the `CaptureCorpusIsolationTest` / `CollectionValuedColumnGateTest` pattern). Without the disclosure, a green build reads as "the tree is declarative", which it does not establish.
- **Anti-vacuity pin.** Once the current hits are rewritten the guard has zero live positives, so a lexer projection bug or an over-tightened phrase pattern would pass forever. A both-directions test over synthetic strings pins it (`RetiredVocabularyGuardTest`'s pattern): wording that must fire, including a phrase broken across a javadoc continuation line, and near-miss wording that must not. Plus the scanned-file floor the sibling guards carry.
- **Scope by axis split, not roster fork.** `GuardScope`'s roadmap-tool exclusion is a property of the roadmap-citation *subject* (an item id in roadmap-tool sources is a legitimate reference), not of the module roster, and `GuardScope`'s one-definition invariant must survive this guard. So: `GuardScope` keeps the single module roster, and each guard subtracts what its own subject requires, with the reason stated at the subtraction. The phrase guard's subject has no roadmap-tool exemption, so it scans roadmap-tool, which is where the style is thickest. This settles, for prose guards, the fork the Backlog item `roadmap-tool-store-site-and-guard-scope` poses; update that item's body when this lands.

The tree currently has four hits (`InlineMultiplicityCheck`, `RewriteSchemaLoaderTest`, `BuildOutputReportPipelineTest`, `TestFilmDetailsDto`); rewriting them ships with the guard, each restating any real constraint in present tense per the day-one rule (in `InlineMultiplicityCheck`, the report-versus-gate declaration stays; only its defense goes).

### 4. Reviewer-prompt hook, srp untouched

Add a comment-style entry to the `reviewer-prompt` skill's "What to look for" list, beside "Stale references", so that review shape reads the diff's comments against the convention. The `srp` templates get nothing: their design-intent section forbids re-accreting checklists and names phrasing as out of the gate's scope, and the workflow gates are covered anyway, since they cite the principles doc this item extends and the build guard runs regardless.

### Out of scope

- A mechanical density gate (maximum javadoc lines). Any threshold is arbitrary, the blessed orientation javadoc legitimately exceeds it, and it teaches compression tricks rather than selectivity.
- A corpus-wide rewrite of existing dense javadoc. Rewrite on touch. The one eager exception: the handful of files agents read constantly, since those are the de facto style teachers; picking them is an implementation-time judgment, not a list this spec maintains.

## Acceptance criteria

- CLAUDE.md carries the comment-style rules as a section separate from citation hygiene: the day-one test with the present-tense-restatement remedy, the subject-scoped why-not rule, the paragraph admission test, and the mimicry-loop breaker.
- `development-principles.adoc` names archeology and rejected-implementation narration as comment/javadoc-habitat smells, states the why-versus-deliberation line, leaves disclosed gaps and measured counter-results conforming, stays within its `DocSizeBudgetTest` budget, and the smell's *Enforced by* line names the guard-plus-review pair.
- A build-run guard test fails on the seed phrases in comment regions of in-scope sources including roadmap-tool, discloses its residue in its own javadoc, carries a both-directions synthetic phrase pin and a scanned-file floor, and states the rewrite rule in its failure message; the current hits are rewritten with real constraints restated in present tense, and the build is green.
- `GuardScope` still has one module roster; per-guard subject subtractions carry their reasons; `roadmap-tool-store-site-and-guard-scope`'s body reflects the settled fork.
- The `reviewer-prompt` skill instructs the reviewer to check diff comments against the convention; the `srp` templates are unchanged.
