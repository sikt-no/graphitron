---
id: R755
title: "srp review prompts lead with commit bookkeeping where the gate turns on goal, design fit, fidelity, and completeness"
status: Backlog
bucket: improvement
priority: 4
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# srp review prompts lead with commit bookkeeping where the gate turns on goal, design fit, fidelity, and completeness

The `srp` skill hands a fresh agent session a prompt that decides whether a roadmap item advances.
Most of that prompt is bookkeeping about commits: sync instructions, the reviewer rule, the
disqualified session IDs, the recent-commits listing, and the fallback for commits with no session
trailer. The substance the gate actually turns on gets a handful of lines at the end. Reviews come
back matching the emphasis they were given: naming preferences, comment wording, phrasing nits, and
micro-refactors, with the load-bearing questions passed over. That is the cost. The gate is supposed
to catch an item whose goal nobody can state, or whose design fights the architecture, before an
implementer spends a session on it, and to catch a delivery that does not do what it promised before
the item ships.

## The four questions the gate turns on

Two belong to the Spec → Ready gate:

1. **Is the goal well communicated, and is it viable?** Can a reader say what changes for a
   consumer of graphitron when this lands, without reconstructing it from the phase list? And is
   that outcome actually reachable in this codebase?
2. **Does the proposed solution fit our architecture?** Not "is it clever", but: does it extend the
   shapes we already have, or does it introduce a parallel mechanism next to one that exists?

Two belong to the In Review → Done gate:

3. **Is the implementation correct, and does it match the stated goal and the proposed solution?**
   A delivery can be correct code and still be a different change than the one the spec approved.
4. **How do we know the item is complete?** Which named test, fixture, generated output, or
   documented behaviour demonstrates the stated goal is delivered. "The build passes" answers a
   weaker question, because a green build is compatible with the goal being half-delivered.

## A second symptom: reviewing the commit series instead of the delivery

The implementation-stage template points the reviewer at the commit history (`git log`, then
`git show`), so reviewers read the sequence of intermediate commits and comment on the steps.
At this gate the deliverable is the state of the tree, not the path taken to it. Intermediate
commits are working history; a fix landed in commit three retires a comment about commit two.

## Fix shape (for the Spec pass to confirm)

- Reorder both templates so the gate questions come first and carry the bulk of the words. For each
  question, name the observable thing that answers it, so the question is answerable rather than
  rhetorical.
- Demote the commit bookkeeping to a short trailing section, labelled as bookkeeping. It stays a
  hard requirement; it stops being the first thing a reviewer reads and the thing the prompt appears
  to be about.
- Say out loud what is out of scope: naming preferences, comment wording, formatting, and
  micro-refactors are reportable only when they bear on one of the four questions.
- In the implementation template, point the reviewer at the cumulative delivered state rather than
  the commit series, and require the completeness evidence to be named.
- Hold total length flat or shorter. The skill's own "Template design intent" section warns against
  re-accreting checklists, and the four questions must land as the decision the reviewer owns, not
  as a form to fill in. Trade section budget; do not add.

## Open question for the Spec pass

Whether the four questions belong in `roadmap/workflow.adoc` as the definition of what each gate
decides, with the skill templates quoting that definition instead of restating it. The workflow doc
already owns the state machine and the reviewer rule, so a gate that is defined in a skill document
is arguably in the wrong file, and the sibling `reviewer-prompt` skill would inherit the same
definition for free. Related: R553, which corrects the disqualified-session resolution in the same
step 4 this item wants to demote.

