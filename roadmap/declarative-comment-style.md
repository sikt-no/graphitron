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

Agent-authored javadoc in this tree keeps coming out dense and rambling, and the density has a recognizable shape: the author narrates its own working session. Having just weighed alternatives and debugged pitfalls, the agent writes the deliberation and the war story into the comment, addressing the reviewer of the change ("here is why my choice is sound") instead of the next reader of the file. Call this *archeological* style: prose that is only true relative to a previous version, a rejected draft, or the session that produced the code. `InlineMultiplicityCheck`'s class javadoc is a live specimen ("Two parsing details are load-bearing and both were learned the hard way", plus a paragraph defending "Reports rather than gates" against an imagined objection), and "rather than / instead of" rationale clauses, the tell of documenting alternatives that are not in the code, appear 40+ times in roadmap-tool main sources alone. The wanted style is *declarative*: state what the code guarantees and the constraint that forces its shape, as if the code had been born this way. Document why; do not document why-not.

Three mechanisms keep the archeological style alive despite existing guidance:

1. **The rule is three words.** "Prefer terse over verbose" sits mid-paragraph in a CLAUDE.md section that is otherwise entirely about roadmap-citation hygiene. Agents read that section as "don't cite R&lt;n&gt;" and move on.
2. **Mimicry backfires.** Agents match surrounding comment density, and the surrounding density is already bad; every dense class teaches the next agent. The existing corpus is a stronger style signal than any rule.
3. **The principles doc half-blesses it.** `development-principles.adoc` endorses intent-altitude prose ("the why and the shape") and names only narrow smells (line-by-line narration, hand-maintained caller censuses). Deliberation-narration passes that smell test, so agents write four paragraphs of "why" and feel principled. The doc distinguishes what-narration from why, but not why from deliberation.

Everything in this repo that is mechanically enforced (roadmap citations, adoc tables, module enumeration) got fixed by being enforced; comment style has no gate, so it drifts.

## Plan

Layer the fix the same way the roadmap-citation fix was layered: a written convention, a principles hook the reviewer gates can cite, and a narrow build-time guard whose job is to make the rule fire where agents actually see it.

### 1. CLAUDE.md: a comment-style section of its own

Split comment *style* out of the citation-focused "Javadoc conventions" section into its own named section, so it cannot be skimmed away as citation hygiene. The rules, stated testably rather than as adjectives:

- **The day-one test.** Write every comment as if the code had been born this way. A sentence that is only true relative to a previous version, a rejected draft, or the session that produced it ("learned the hard way", "historically", "this change") gets cut, not rephrased.
- **Why, not why-not.** State the constraint that forces the code's shape; never narrate rejected alternatives. "X rather than Y, because Z" collapses to "X, because Z": if Y is not in the code, Y needs no documentation. One exception: a genuine trap, where the obvious alternative is wrong in a way that will tempt a future editor to regress it. That earns one sentence.
- **A budget.** Class javadoc is one sentence of what, plus at most one paragraph for the single non-obvious constraint. Longer than that means the content is either design rationale (belongs in `docs/architecture/`, linked) or deliberation (belongs nowhere). The blessed orientation on-ramp (`GraphitronSchemaBuilder`'s top comment) is the exception and is already named as such in the principles doc.
- **Break the mimicry loop explicitly.** The dense javadoc already in the tree is precedent for nothing; do not match it. Without this line the density rule loses to the match-surrounding-style instinct every time. Pair it with the em-dash stance: the existing corpus is not a cleanup backlog, rewrite on touch only.

### 2. development-principles.adoc: name the smells

Extend "Documentation names only live tests/code" so archeology and why-not narration join line-by-line narration and caller censuses as named smells, and draw the line the principle currently misses: *why* is the constraint that makes the code correct; *deliberation* is the process that found the constraint. Document the first, discard the second. The Spec → Ready and In Review → Done reviewer gates already cite this principle, so this gives them something concrete to point at.

### 3. A narrow mechanical guard

A sibling of `RoadmapReferenceGuardTest` (same `GuardScope` walk, same comment-region lexing as `RoadmapReferenceScanner`, generalized or duplicated as implementation finds cleanest) that fails the build on unambiguous archeological tells in comment and javadoc regions across main and test sources. Seed phrase list: "learned the hard way", "historically", "in an earlier version", "in a previous version". Admission criterion for the list: a phrase qualifies only if every plausible comment use narrates repository history rather than program behavior. That criterion keeps "previously", "no longer", "used to", and "rather than" off the list; each has legitimate present-tense or dataflow readings ("a module the pom no longer declares", "used to resolve the trace glob"), and "rather than" is sometimes legitimate why. The guard's failure message teaches the day-one rule, mirroring how the citation guard's message teaches its rewrite rule. The tree currently has four hits (`InlineMultiplicityCheck`, `RewriteSchemaLoaderTest`, `BuildOutputReportPipelineTest`, `TestFilmDetailsDto`); rewriting them declaratively ships with the guard. Note `InlineMultiplicityCheck` lives in roadmap-tool, which `GuardScope` excludes by design, so either the phrase guard gets its own scope decision or that file's rewrite is convention-only; the spec leaves this to implementation with a stated preference for scanning roadmap-tool too, since it is where the style is thickest.

### 4. Reviewer-gate checklists

Add a comment-style row to the `srp` and `reviewer-prompt` skill checklists so the gates read the diff's comments against the convention, not just its code against the principles.

### Out of scope

- A mechanical density gate (maximum javadoc lines). Any threshold is arbitrary, the blessed orientation javadoc legitimately exceeds it, and it teaches compression tricks rather than selectivity.
- A corpus-wide rewrite of existing dense javadoc. Rewrite on touch. The one eager exception: the handful of files agents read constantly, since those are the de facto style teachers; picking them is an implementation-time judgment, not a list this spec maintains.

## Acceptance criteria

- CLAUDE.md carries the comment-style rules as a section separate from citation hygiene, including the day-one test, the why-not rule with its trap exception, the class-javadoc budget, and the mimicry-loop breaker.
- `development-principles.adoc` names archeology and why-not narration as smells under "Documentation names only live tests/code" and states the why-versus-deliberation line.
- A build-run guard test fails on the seed phrases in comment regions of in-scope sources, with a failure message that states the rewrite rule; the current hits are rewritten and the build is green.
- The `srp` and `reviewer-prompt` skills instruct the reviewer to check diff comments against the convention.
