---
id: R755
title: "srp review prompts lead with commit bookkeeping where the gate turns on goal, design fit, fidelity, and completeness"
status: Spec
bucket: improvement
priority: 4
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---


# srp review prompts lead with commit bookkeeping where the gate turns on goal, design fit, fidelity, and completeness

## Goal

A reviewer session that pastes an `srp` prompt should come back having answered the question the
gate exists to ask, and should feel entitled to say nothing about naming, phrasing, or formatting.
Today it comes back with nits, because nits are what the prompt spends its words on.

Concretely, when this lands: `roadmap/workflow.adoc` states what each of the two guarded gates
decides, the `srp` templates open with those questions and close with the session-ID bookkeeping
rather than the reverse, and the In Review template points the reviewer at the delivered state of
the tree instead of at the commit series that produced it.

## Why the current shape produces nits

Count the sections of the emitted Spec-stage template. "Sync first", "Reviewer rule" (with its
recent-commits block and disqualified-ID paragraph), and "Outcomes (exactly two)" come to 35 of the
body's 71 lines. The Implementation-stage template is the same story: 37 of 82. Roughly half of each
prompt is process mechanics.

The questions those gates turn on get one sentence apiece. Spec stage: "would you hand this plan to
an implementer as-is?". Implementation stage: "does the delivery honor the contract the spec set?".
Everything else about substance is a list of files to read plus a closing paragraph asking for
opportunities as well as problems.

A reviewer weights what the prompt weights. Given one sentence of goal and thirty-five lines of
mechanics, the reachable findings are the ones visible without forming a view: a name that could be
better, a comment that could be clearer, a section that could be shorter. The gate is supposed to
catch an item whose goal nobody can state, or whose design stands a second mechanism beside one we
already have, before an implementer spends a session on it; and to catch a delivery that is correct
code but a different change than the one that was approved. Neither is reachable from a sentence.

One aggravating factor is worth naming: the Implementation-stage template offers the
`reviewer-prompt` skill's "What to look for" section as "the project's canonical review taxonomy, if
you want one". That taxonomy is eighteen bullets of diff-level architectural lenses. It is good at
what it does, and it is exactly the wrong thing to hand someone whose job is deciding whether a
delivery met its goal, because it converts the gate into a checklist sweep.

## The second symptom: reviewing the commit series instead of the delivery

The Implementation-stage template's Materials section points at "The implementation commits
(`git log --oneline -20`, then `git show`)". So reviewers read the sequence of commits and comment on
the steps. At this gate the deliverable is the state of the tree, not the path taken to it.
Intermediate commits are working history: a fix landed in the third commit retires a comment about
the second, and the reviewer who reads forward spends attention on states that no longer exist.

## Design

### The gate definition belongs in workflow.adoc

`roadmap/workflow.adoc` § "States and transitions" already owns the guards, the reviewer rule, the
User-facing-doc check, and the Retirement sweep. What each gate *decides* is missing from the one
document that owns everything else about the gates, which is why it ended up improvised inside an
agent-facing skill file. Put it where the rest of the gate semantics live, and have the skill quote
it.

Draft of the new starred paragraph, to sit immediately after the *Reviewer rule* paragraph:

> *What each gate decides.* `Spec → Ready` turns on two questions. First, is the goal well
> communicated and viable: can a reader say what changes for a consumer of graphitron when the item
> lands, without reconstructing it from the phase list, and is that outcome reachable in this
> codebase? Second, does the proposed solution fit the architecture we have, extending a shape
> already in the tree rather than standing a parallel mechanism beside one that exists?
> `In Review → Done` turns on two more. Third, is the implementation correct *and* the change the
> spec approved: correct code can still be a different change. Fourth, how do we know the item is
> complete: which named test, fixture, generated output, or documented behaviour demonstrates the
> stated goal is delivered. A green build answers a weaker question, because it is compatible with
> the goal being half-delivered. Naming preferences, phrasing, formatting, and micro-refactors are
> not what either gate decides; a reviewer raises one only when it bears on one of the four
> questions, and says which.

### What the templates become

Both templates keep their content and change their order and their proportions. New section order,
with the current names in parentheses where they survive:

1. Item identification, then a two-line sync imperative. Sync stays at the top because it is an
   ordering requirement, not bookkeeping: a reviewer who reads the questions first and syncs later
   has already read a stale spec. Only the explanatory paragraph moves down.
2. **The questions this gate decides.** The gate's two questions, restated from workflow.adoc in
   reviewer voice under a line naming workflow.adoc as the definition, each with the observable that
   answers it. This is the section that carries the most words. The restatement is deliberate and
   labelled: the definition is written as definition prose, and the prompt needs it in second person
   with the observable attached, so the templates paraphrase rather than quote.
3. **What is out of scope.** Named explicitly: naming preferences, phrasing, formatting,
   micro-refactors, restating the diff, speculative features. Reportable only when they bear on one
   of the questions, and the reviewer says which. New scope goes in a fresh Backlog item.
4. **Materials** (unchanged in substance, trimmed).
5. **Verdict**, folding in the two outcomes and their state-machine actions (today's "Outcomes
   (exactly two)").
6. **Bookkeeping**, last: the reviewer rule paragraph, the disqualified session IDs, the
   recent-commits block, and the no-trailer fallback. Still a hard requirement, still pre-filled, no
   longer the first thing on the page and no longer what the prompt appears to be about.

Total length holds flat or shrinks. The skill's own "Template design intent" section warns against
re-accreting checklists, and it is right: a rubric gets completed instead of thought about. Four
questions are exactly the shape that rots into a form to fill in, so the budget for section 2 comes
out of sections 5 and 6, and the questions are phrased as the decision the reviewer owns.

### The delivery, not the commit series

In the Implementation-stage template, the Materials section stops suggesting a commit walk and starts
naming the tree. The delivered state is the files the item touched as they now stand; the commit list
stays, relabelled as provenance for anyone who wants it rather than as the thing to read.

The Spec review struck the mechanism first drafted for this, a `{{delivery-range}}` token of
`<oldest-implementation-commit>^...HEAD` resolved from the skill's step-4 commit scan. Trunk
interleaves items: within twenty-five commits of trunk at review time, seven items were landing
concurrently. On a linear trunk `oldest^...HEAD` is therefore the diff of everything since that
point, not this item's delivery, so the token would hand the reviewer a materials pointer strictly
worse than today's, which at least filters to the item. The endpoint is unreliable for a second
reason: step 4 resolves it by grepping `git log -50` for a bare `R<n>`, and R553 already records that
this scan both misses commits whose subjects omit the ID and picks up commits that merely mention it.
Its oldest hit is usually the Backlog-filing commit, not an implementation commit.

No cheap SHA range isolates one item's cumulative delivery on an interleaved trunk, and inventing one
is not what this item is for. The fix here is the framing, which is what produced the symptom: point
the reviewer at the touched paths as they stand, and say that the commit series is provenance. That
removes the suggestion to read forward through superseded states without adding a token that cannot
be resolved correctly.

### What this item does not change

`reviewer-prompt` stays as it is. It runs no gate: it asks for architectural opportunities on a diff,
and its long lens taxonomy is appropriate to that job. Only the `srp` Implementation-stage template's
pointer at that taxonomy changes, demoted below the four questions and marked as never sufficient on
its own to hold a gate. (An earlier framing of this item claimed `reviewer-prompt` would inherit the
gate definition for free; it would not, and does not need to.)

The disqualified-session *resolution* stays as it is. R553 covers the Spec-stage resolver's bug (it
names one session where the guard means every session with a trail on the draft). This item rewrites
the lines that bug lives in, so the new Bookkeeping section uses a plural
`{{disqualified-session-ids}}` token at both stages, filled by today's single-session resolution at
the Spec stage until R553 fixes the resolver. A list of one is honest; a singular token that R553
would have to re-plural is not.

## Implementation

* `roadmap/workflow.adoc`: add the *What each gate decides* starred paragraph after *Reviewer rule*.
* `.claude/skills/srp/SKILL.md`:
  * Rewrite the Spec-stage template to the section order above. The draft below is the intended
    output; land it as the template with the values re-tokenised.
  * Rewrite the Implementation-stage template to the same order, with questions 3 and 4 verbatim
    from workflow.adoc, keeping the existing "Approval preconditions" section (build passes, no
    code-string assertions on generated method bodies, spec body reflects what shipped) as an
    appendix to the Verdict section rather than a peer of the questions.
  * The Implementation-stage Materials section names the touched tree as the review surface and
    relabels the commit list as provenance. No new token; see *The delivery, not the commit series*.
  * The "Template design intent" section gains one sentence: the gate questions are restated from
    `roadmap/workflow.adoc`, which owns them, and changing them there is the way to change them.
  * "Output rules" takes the Spec-stage disqualified-token list plural, matching the Implementation
    stage.

## How we know the item is complete

The primary evidence is the diff plus a measurement, because a skill document has no build gate:
the two templates' Bookkeeping-plus-Verdict line count must be less than their gate-questions
section, inverting today's ratio, and neither template's total body may exceed its current 71 and 82
lines. That is checkable by reading the diff and stated here so the reviewer does not have to take
it on faith. The two baselines were verified at the Spec review: the Spec-stage template body is 71
lines and the Implementation-stage body 82, so the ceiling has real anchors.

The drift risk is real: the question text lives in `workflow.adoc` as the definition and is restated
in two templates, so a template can silently keep an old wording. The Spec review struck the
`check-gate-questions` step drafted as the guard against it, a `roadmap-tool` sibling to
`check-adoc-tables`, `check-transient-citations`, and `check-module-enumeration` asserting that each
question sentence in `.claude/skills/srp/SKILL.md` also appears in `roadmap/workflow.adoc` after
whitespace normalisation. Two reasons. The predicate does not hold on this item's own output: the
templates restate the definition in second person with the observable attached ("Say in your own
words ... If you cannot, that is the finding"), which no whitespace normalisation reaches, so the
drafted first-client prompt would fail the step the same draft proposed. Forcing verbatim quoting to
satisfy the step would spend the reviewer voice that is the point of the rewrite. And the three named
siblings each guard a crisp predicate over content that rots invisibly and renders wrong when it
does; string containment across two documents with deliberately different voices is not that shape,
and it would be the first build gate to reach into `.claude/skills/`, a habitat
`check-transient-citations` deliberately stays out of.

What holds the two documents together instead is already in the design and costs nothing: the
templates carry a line naming `roadmap/workflow.adoc` § "What each gate decides" as the definition,
and the "Template design intent" sentence says changing them there is the way to change them. A
future editor of the question text is told where the base is. Drift then costs a reconciliation by
whoever notices, which for two paragraphs in adjacent documents is proportionate.

The behavioural evidence, that reviews come back answering the four questions, arrives on the next
few gate uses and cannot be produced at Done time. Saying that plainly is better than pointing at
the build and calling it proof.

## First-client check: the new Spec-stage prompt

Per the item-file convention that a plan with a user-visible surface drafts that surface as the
design's first client. The emitted prompt *is* the surface here.

````text
You are an independent reviewer doing the Spec → Ready sign-off on roadmap item
{{Rn}} in graphitron-rewrite, "{{title}}".

Repo: {{repo-root}}
Spec: {{spec-path}}

Sync before you read anything. The spec body may live in a commit that has not
reached your local branch, and reviewing a stale copy voids the gate:

    git fetch origin claude/graphitron-rewrite
    git rebase origin/claude/graphitron-rewrite

If the rebase conflicts, surface and stop.

# The two questions this gate decides

roadmap/workflow.adoc, "What each gate decides", is the definition. Both
questions are restated here so you can work from them directly.

1. Is the goal well communicated, and is it viable? Say in your own words, and
   without reconstructing it from the phase list, what changes for a consumer of
   graphitron when this lands. If you cannot, that is the finding. Then ask
   whether the outcome is reachable in this codebase: the spec makes claims
   about code that exists, and those are checkable, so check them.

2. Does the proposed solution fit the architecture we have? Does it extend a
   shape already in the tree, or stand a parallel mechanism beside one that
   exists? Would you hand this plan to an implementer as-is, or would you end up
   redesigning it as you went?

A spec that answers both is Ready. A spec that fails either stays in Spec, and
your finding is the reason.

# What is out of scope

Naming preferences, phrasing, formatting, and micro-suggestions are not what
this gate decides. Raise one only when it bears on question 1 or 2, and say
which. Scope you would like to see added belongs in a fresh Backlog item (any
session can file one), not in this verdict. Anything else you noticed goes in a
short non-blocking section, or goes unsaid.

# Materials

- {{spec-path}}: the plan under review.
- docs/architecture/explanation/development-principles.adoc and
  docs/graphitron-principles.adoc: where "good" is defined here.
- docs/architecture/index.adoc: orientation when the spec's domain is
  unfamiliar.

Depth and order are your call. You are an agent, not a reader: grep the tree,
open the code the spec touches, check the spec's claims against reality. One
check is not optional, because specs rot silently without it: every code, test,
and symbol the spec names must exist as named. Use FQN-aware grep
(`grep -rn the.full.Name`); partial-name searches miss mismatches.

# Verdict

End with one of two, unambiguously:

- Sign off. Flip Spec → Ready with the `roadmap` skill, then `publish`.
- Request revisions, naming which question failed and what would satisfy it.
  Either commit spec revisions yourself on a fresh feature branch (status stays
  Spec, and you become the last committer, so the next pass needs a different
  session), or leave the notes for the author.

A clean spec is a valid outcome. Say so plainly rather than inventing findings.

# Bookkeeping (hard requirement, settle it before you sign off)

Per roadmap/workflow.adoc, "States and transitions", Spec → Ready requires a
reviewer session different from the last committer of the spec file, compared by
Claude Code session ID (the `https://claude.ai/code/session_<id>` trailer on each
commit), not by git author and not by human identity. Your own ID is in your
system prompt. If it matches a disqualified ID, hand off to a different session.

Disqualified session IDs: {{disqualified-session-ids}}
Git-author fallback, for `<no-trailer>` entries: {{disqualified-authors}}

Recent commits touching the spec file, most recent first:

{{recent-commits}}
````
