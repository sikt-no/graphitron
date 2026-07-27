---
id: R551
title: "Simplify the srp hand-off templates: goal-oriented review prompts that trust reviewer judgment"
status: Ready
bucket: improvement
priority: 5
theme: tooling
depends-on: []
created: 2026-07-27
last-updated: 2026-07-27
---

# Simplify the srp hand-off templates: goal-oriented review prompts that trust reviewer judgment

The `srp` skill's emitted hand-off prompts over-constrain the reviewing agent, and the reviews coming back are shallower than the current model generation can deliver. Both templates prescribe a fixed reading order, a fixed five-bullet assessment rubric, and a per-finding output format. Observed effect: reviews shaped like the rubric rather than like the item under review; one paragraph per bullet, findings stretched to fill the agenda, and nothing surfaced outside it. Current Anthropic guidance for the Claude 4.5+/5 model generation says this prompt shape is now counterproductive; the fix is to restate the templates as goal + hard invariants + pointers, and let the reviewer decide how to investigate and how to report.

## Research findings (what the guidance actually says)

Sources: the Claude Code best-practices page (code.claude.com/docs/en/best-practices), the Agent Skills authoring guide (code.claude.com/docs/en/skills), the Anthropic engineering posts on context engineering for agents and on long-running agent harnesses, the current prompting guidance for the Opus 4.5+/5 generation, and the guidance bundled with Anthropic's own `skill-creator` skill.

- **"Delegate, don't dictate."** Give context and direction, then trust the model to figure out the details; do not script which files to read or in what order. Prescribed step sequences fight the agentic loop, where each step is chosen from what the previous step revealed.
- **"Delete instructions, don't add them."** For the 4.5+/5 generation, over-specification measurably degrades output. Redundant verification instructions ("double-check", "verify you found everything") cause over-verification; aggressive triggering language causes over-triggering. Newer models need less hand-holding, and instruction bloat has the same failure mode as tool bloat in the context-engineering guidance.
- **Checklists get completed, not thought about.** A fixed rubric turns the review into rubric-filling; the model optimises for covering the bullets instead of exercising the judgment the fresh context exists to provide. The fresh-context reviewer pattern works because the reviewer applies independent judgment to a simple task specification, not because it executes a procedure.
- **Match freedom to fragility** (skill-authoring guidance): keep low degrees of freedom for fragile mechanical steps, high degrees of freedom for judgment work. Explain *why* a constraint exists instead of stacking MUSTs; "if you find yourself writing ALWAYS or NEVER in all caps, or using super rigid structures, that's a yellow flag" (skill-creator).
- **Trim output-format prescriptions to what the consumer needs.** State what the output must enable (here: a go/no-go the author can act on), not the line-by-line shape of each finding.

## What stays prescriptive (fragile, mechanical; low freedom is correct)

The skill's own Procedure (steps 0-6) is untouched in intent: sync-first, ID resolution, template selection, disqualified-session resolution, recent-commits block, single-fenced-block emission. In the emitted templates, these survive as hard invariants because a fresh reviewer cannot infer them and getting them wrong invalidates the gate:

- The sync-first commands and the reason (the spec body may only exist on trunk).
- The reviewer rule: disqualified session ID(s), the trailer convention, the git-author fallback, and the instruction to hand off if the reviewer matches.
- The two-outcome contract and the state-machine actions attached to each outcome (roadmap-skill flip, spec-file deletion + optional changelog entry, publish).
- Project facts a reviewer cannot infer: where "good" is defined (development-principles.adoc, architecture index, workflow.adoc); the stale-reference rule with the FQN-aware-grep hint; the ban on code-string assertions over generated method bodies; build green via `mvn install -Plocal-db` as an approval precondition (implementation stage).

## What the templates stop prescribing (judgment work; high freedom)

- The "Read first (in this order)" mandated sequence becomes a short list of materials with one line each on why they matter; depth and order are the reviewer's call.
- The five-bullet "What to assess" rubrics are dropped. Replaced by a one-paragraph statement of the question the gate answers ("would you hand this plan to an implementer as-is" / "does the delivery honor the contract the spec set"), plus the non-inferable project facts above stated as constraints with reasons, not as an agenda.
- The per-finding output format (summary line / location / principle / fix shape) is dropped. Replaced by: report what materially bears on the decision, anchor each finding so the author can act on it, end with an unambiguous verdict, and say plainly when the item is clean instead of inventing findings.
- Explicit licence to investigate: run greps, read the code the spec touches, run the build; the reviewer is an agent, not a reader.

## Deliverable

Rewrite the two templates in `.claude/skills/srp/SKILL.md` along the lines above; both should shrink substantially. The skill's Procedure, Output rules, and Hard rules sections keep their current contracts (one fenced block, pre-filled tokens, no improvising the template per call; template consistency across reviewers is itself a workflow property and is not up for relaxation). Add one short paragraph to the skill noting the design intent (goal + invariants + pointers; no rubrics) so future edits do not re-accrete checklists.

Apply the same treatment to the sibling `reviewer-prompt` skill only if this item's review says so; default is out of scope.

## Out of scope

- The `reviewer-prompt` skill's "What to look for" taxonomy (it doubles as the project's canonical review taxonomy; changing it is a separate decision).
- The roadmap state machine, reviewer rule, and workflow.adoc.
- Any eval harness for comparing review quality before/after (worth doing, separate item if wanted).
