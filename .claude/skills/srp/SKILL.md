---
name: srp
description: Produce a single copy-pasteable code block that prompts another agent to perform a workflow-gate review on a graphitron-rewrite roadmap item. Use when the user asks for a "spec review prompt", "review prompt for R<n>", "Spec → Ready handoff", "In Review → Done handoff", or otherwise wants a hand-off prompt for the workflow-gate review on a specific roadmap item. The emitted prompt encodes the relevant reviewer-rule and pre-fills recent commit history so the next reviewer can apply the rule without digging.
---

# Spec Review Prompt

Generates a self-contained prompt the user copies into another agent to perform a workflow-gate review on a graphitron-rewrite roadmap item. The reviewer either signs off (advancing the gate) or requests revisions (item stays in current state).

The skill's job is the *hand-off*. Do not perform the review yourself.

## When to use

The user names a roadmap item `R<n>` and asks for a hand-off prompt for the next reviewer. Two gates need this:

- **Spec → Ready** (item is `status: Spec`). Reviewer reads the spec body and either signs off or requests revisions. Reviewer rule: ≠ Claude Code session that authored the most recent commit touching the spec file.
- **In Review → Done** (item is `status: In Review`). Reviewer reads the shipped implementation against the spec and either approves (delete the spec file, optionally entry the changelog) or requests rework. Reviewer rule: ≠ Claude Code session(s) that authored the implementation commits.

For other statuses (Backlog, Ready, In Progress), no formal review handoff applies; tell the user and stop. For paired sibling skills, see `roadmap` (state machine + ID lookup) and `reviewer-prompt` (architecture-focused code-diff review handoff). The `principles-architect` subagent is the *forward* counterpart to this skill; suggest it as a self-check when the user is preparing a Spec → Ready handoff and hasn't already consulted it (it's read-only and produces no verdict, so it doesn't compete with the reviewer-rule guard).

## Procedure

0. **Sync first.** Always fetch and rebase before resolving anything else; the spec body the reviewer will read may live in a commit that hasn't reached the local branch yet, and a stale `git log -- <slug>.md` produces a stale recent-commits block and a stale disqualified-session-ID attribution. Run:

   ```bash
   git fetch origin claude/graphitron-rewrite
   git rebase origin/claude/graphitron-rewrite
   ```

   If the rebase reports conflicts, surface and stop — the user resolves before the handoff is meaningful. Working-copy dirt is the user's call (don't auto-stash).

1. **Resolve the item.** Resolve `R<n>` to a file via:

   ```bash
   grep -lE "^id: R<n>$" roadmap/*.md
   ```

   Multiple matches: roadmap-tool bug; stop and surface. No matches: ID is unallocated, or the item shipped (file deleted on Done) and only its changelog entry remains; tell the user.

2. **Read the front-matter.** Pick `status:` and `title:` from the YAML block.

3. **Pick the template.**
   - `Spec` → Spec-stage template (gate: Spec → Ready)
   - `In Review` → Implementation-stage template (gate: In Review → Done)
   - Anything else → stop, tell the user no review handoff applies at status `<X>`.

4. **Resolve the disqualified party.** This is the load-bearing piece — the next reviewer applies the rule by ID, not by re-deriving it. Per `roadmap/workflow.adoc` § "States and transitions" (Reviewer rule paragraph), the comparison identifier is the Claude Code session ID recorded as the `https://claude.ai/code/session_<id>` trailer on each commit. Resolve both the session ID (primary) and the git author name (fallback for trailer-less commits) so the emitted template carries both.

   Spec stage:
   ```bash
   sha=$(git log -1 --format=%H -- roadmap/<slug>.md)
   git log -1 --format=%B "$sha" | grep -oE 'session_[A-Za-z0-9]+' | head -1
   git log -1 --format='%an' "$sha"
   ```

   The first command yields the disqualified session ID (e.g. `session_01Kc8d1cyEHM1rxZXpbm8QyE`); the second yields the git author. If the trailer grep is empty, the commit predates the trailer-tracking convention; surface that as `<no-trailer>` in the emitted template so the reviewing agent knows to defer to the user's judgment that they are an independent session.

   Implementation stage: the implementer is whoever authored the implementation commits between the most recent `Ready → In Progress` and `In Progress → In Review` status flips. Approximate by listing unique session IDs from the recent commits referencing `R<n>`, with git author as fallback:
   ```bash
   git log --pretty='%H %s' -50 | grep -E '\bR<n>\b' | awk '{print $1}' \
     | while read sha; do
         sid=$(git log -1 --format=%B "$sha" | grep -oE 'session_[A-Za-z0-9]+' | head -1)
         author=$(git log -1 --format='%an' "$sha")
         printf '%s\t%s\t%s\n' "$sha" "${sid:-<no-trailer>}" "$author"
       done
   ```

   Dedupe by the second column for the disqualified-session-IDs list; keep authors alongside as fallback.

5. **Get the recent-commits block.** Indent four spaces under `Recent commits ...:`:
   ```bash
   git log --oneline -10 -- roadmap/<slug>.md
   ```

6. **Emit the prompt.** Output exactly one fenced block, pre-filled with the resolved values. Use the appropriate template below verbatim.

## Template design intent

Both templates are goal + hard invariants + pointers, deliberately. They state the question the gate answers, the mechanical facts a fresh session cannot infer (sync-first, the reviewer rule, the two outcomes and their state-machine actions, non-inferable project facts), and where the judgment materials live. The stated goal is improvement, not just gatekeeping: the reviewer is asked for opportunities that would make the item land better alongside blocking problems, with the verdict keeping the two distinct. They do not prescribe a reading order, an assessment rubric, or a per-finding output format: rubrics get completed rather than thought about, and the fresh-context reviewer exists to apply independent judgment. When editing the templates, prefer deleting instructions over adding them; do not re-accrete checklists.

## Spec-stage template

Emit this as a fenced ```text``` block, replacing the `{{...}}` tokens.

````text
You are an independent reviewer doing the Spec → Ready sign-off on roadmap item
{{Rn}} in graphitron-rewrite. The review exists to help this item land as well
as it can, and the gate answers one question: would you hand this plan to an
implementer as-is? Either flip the status to Ready, or request specific
revisions (the item stays in Spec for another pass).

Repo:    {{repo-root}}
Spec:    {{spec-path}}  (id: {{Rn}}, title: {{title}}, status: Spec)

# Sync first (hard requirement)

The spec body may live in a commit that hasn't reached your local branch, and
reviewing a stale copy invalidates the gate:

    git fetch origin claude/graphitron-rewrite
    git rebase origin/claude/graphitron-rewrite

If the rebase conflicts, surface and stop until resolved.

# Reviewer rule (hard requirement)

Per roadmap/workflow.adoc § "States and transitions", Spec → Ready requires a
reviewer different from the last committer of the spec file, compared by
Claude Code session ID, recorded as the `https://claude.ai/code/session_<id>`
trailer on each commit; not git author, not human identity. Your own session
ID is in your system prompt, embedded in that trailer URL. If it matches the
disqualified ID below, hand off to a different session.

Recent spec-touching commits (most recent first):

{{recent-commits}}

Disqualified session ID: {{disqualified-session-id}}
(If that is `<no-trailer>`, the commit predates the trailer convention; fall
back to git author {{disqualified-author}} and defer to the user's judgment
that you are an independent session.)

# Materials

- {{spec-path}}: the plan under review.
- docs/architecture/explanation/development-principles.adoc: where "good" is
  defined for this codebase.
- docs/architecture/index.adoc: architectural orientation when the spec's
  domain is unfamiliar.
- roadmap/workflow.adoc: the state machine and reviewer rule, if you need the
  source.

Depth and order are your call. You are an agent, not a reader: grep the tree,
open the code the spec touches, check the spec's claims against reality. One
check is non-negotiable because specs rot silently without it: every
code/test/symbol the spec names must exist as named (the "Documentation names
only live tests/code" principle); FQN-aware grep (`grep -rn the.full.Name`)
catches mismatches that partial-name searches miss.

# Outcomes (exactly two)

1. Sign off. Use the `roadmap` skill to flip status Spec → Ready, then
   `publish` to push and fast-forward trunk.
2. Request revisions. Either commit spec revisions yourself on a fresh feature
   branch (status stays Spec; you become the last committer, so the next pass
   needs a different reviewer session), or leave notes for the original
   author. Use the `roadmap` skill for a recorded Spec → Spec revise
   transition if needed.

Surface opportunities as well as problems: a stronger shape, a simpler path,
sharper coverage. Keep the two distinct in your report; a problem blocks
sign-off, an opportunity is the author's call and does not, and new scope
belongs in a fresh Backlog item rather than in this verdict. Report what
materially bears on the decision, anchored so the author can act on it. A
clean spec is a valid outcome; say so plainly instead of inventing findings.
End with an unambiguous verdict and what happens next.
````

## Implementation-stage template

Emit this as a fenced ```text``` block, replacing the `{{...}}` tokens.

````text
You are an independent reviewer doing the In Review → Done approval on roadmap
item {{Rn}} in graphitron-rewrite. The review exists to help this item land as
well as it can, and the gate answers one question: does the delivery honor the
contract the spec set? Either approve (the item ships and its spec file is
deleted), or request rework (status flips back to Ready for another pass).

Repo:    {{repo-root}}
Spec:    {{spec-path}}  (id: {{Rn}}, title: {{title}}, status: In Review)

# Sync first (hard requirement)

Implementation commits may live on trunk and not yet on your local branch, and
reviewing a partial delivery invalidates the gate:

    git fetch origin claude/graphitron-rewrite
    git rebase origin/claude/graphitron-rewrite

If the rebase conflicts, surface and stop until resolved.

# Reviewer rule (hard requirement)

Per roadmap/workflow.adoc § "States and transitions", In Review → Done requires
a reviewer different from the implementer, compared by Claude Code session ID,
recorded as the `https://claude.ai/code/session_<id>` trailer on each commit;
not git author, not human identity. Your own session ID is in your system
prompt, embedded in that trailer URL. If it matches any disqualified ID below,
hand off to a different session.

Recent commits referencing {{Rn}} (most recent first):

{{recent-commits}}

Disqualified session IDs (any session that authored an implementation commit):
{{disqualified-session-ids}}
(For entries that are `<no-trailer>`, the commit predates the trailer
convention; fall back to the git authors {{disqualified-authors}} and defer to
the user's judgment that you are an independent session.)

# Materials

- {{spec-path}}: the contract; what the implementer promised to deliver.
- The implementation commits (`git log --oneline -20`, then `git show`): the
  delivery.
- docs/architecture/explanation/development-principles.adoc: where "good" is
  defined for this codebase.
- roadmap/workflow.adoc: this gate carries obligations of its own; its
  "User-facing-doc check" and "Retirement sweep" paragraphs apply before
  approval.
- docs/architecture/index.adoc: architectural orientation when the touched
  area is unfamiliar.
- The `reviewer-prompt` skill's "What to look for" section: the project's
  canonical review taxonomy, if you want one.

Depth and order are your call. You are an agent, not a reader: run the build,
grep, read the diff and the code around it.

# Approval preconditions (non-inferable project facts)

- `mvn install -Plocal-db` passes. A failing build is automatic rework.
- No code-string assertions on generated method bodies anywhere in the
  delivered tests; development-principles.adoc pins this test-tier rule.
- The spec body reflects what shipped: phases collapsed to one-line
  "shipped at <sha>" notes, remaining work clearly named.

# Outcomes (exactly two)

1. Approve. Delete the spec file (`rm {{spec-path}}`); if the milestone is
   worth preserving, append a one-line entry to roadmap/changelog.md naming
   the {{Rn}} ID and the landing commit SHAs. Regenerate the README via the
   `roadmap` skill, commit on a fresh feature branch, then `publish` to push
   and fast-forward trunk.
2. Request rework. Use the `roadmap` skill to flip In Review → Ready and
   capture the review feedback in the spec body for the next pass. The
   reviewer rule applies again next cycle.

Surface improvement opportunities as well as defects, and keep them distinct:
a broken contract or invariant means rework, while an improvement the contract
does not demand becomes a follow-up Backlog item (any session can file one)
rather than a reason to hold the gate. Report what materially bears on the
decision, anchored (file:line) so the implementer can act on it. A clean
delivery is a valid outcome; say so plainly instead of inventing findings.
End with an unambiguous verdict and what happens next.
````

## Output rules

- Exactly one fenced block. No "Here's the prompt:" preamble, no trailing notes — those break one-click copy.
- Pre-fill `{{Rn}}`, `{{repo-root}}`, `{{spec-path}}`, `{{title}}`, `{{recent-commits}}`, plus the disqualified-identifier pair: `{{disqualified-session-id}}` + `{{disqualified-author}}` (Spec stage, singular) or `{{disqualified-session-ids}}` + `{{disqualified-authors}}` (Implementation stage, plural). The user should not have to edit the block. When the trailer is absent, fill the session-ID slot with `<no-trailer>` literally; do not omit it.
- `{{recent-commits}}` is the literal output of `git log --oneline -10 -- <spec-path>`, indented four spaces.
- If any resolved disqualified session ID matches the invoking session's own ID (visible to the agent in its system prompt's commit-trailer URL), surface that fact in a short line *outside* the fenced block, but still emit the prompt — the user may want to forward it to a fresh agent. If every disqualified entry resolves to `<no-trailer>`, surface that the gate has no signal and the user must vouch for reviewer independence.

## Hard rules

- Always sync (step 0) before resolving the item. A stale checkout produces a stale recent-commits block, a stale disqualified-session-ID attribution, and — worst — hands the reviewer a spec body that's missing commits already on trunk. The emitted templates also instruct the reviewer to sync; the skill itself must sync too so the resolved values reflect truth.
- Do not perform the review yourself. The skill exists to hand off; doing the work in-session defeats the point of getting a second pair of eyes.
- Do not improvise the templates per call. Adjust pre-filled values; leave the body literal. Drift means each reviewer gets a different rubric.
- The prompt references files in the repo; it does not paste their contents. The reviewing agent reads them in its own session.
- The reviewer-rule guard cannot be bypassed because "the user said so". If the only available reviewer is a session that matches a disqualified session ID, surface it and stop — a different session must perform the review.
- For statuses other than `Spec` or `In Review`, no review handoff applies; tell the user and stop instead of forcing a template.
