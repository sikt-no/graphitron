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

   The first command yields the disqualified session ID (e.g. `session_01Kc8d1cyEHM1rxZXpbm8QyE`); the second yields the git author. Both feed the plural `{{disqualified-session-ids}}` / `{{disqualified-authors}}` tokens as a list of one; the template is plural at both stages so a wider resolution drops in without a template change. If the trailer grep is empty, the commit predates the trailer-tracking convention; surface that as `<no-trailer>` in the emitted template so the reviewing agent knows to defer to the user's judgment that they are an independent session.

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

Both templates are gate questions + hard invariants + pointers, deliberately, and in that order. They lead with the questions the gate turns on, then name what is out of scope, then say where the judgment materials live, and only then carry the mechanical facts a fresh session cannot infer (sync-first, the reviewer rule, the two outcomes and their state-machine actions, non-inferable project facts). The order is load-bearing: a reviewer weights what the prompt weights, so a prompt that opens with commit bookkeeping comes back with bookkeeping-shaped findings. The gate questions are restated from `roadmap/workflow.adoc` § "What each gate decides", which owns them; changing them there is the way to change them, and the templates paraphrase rather than quote so the questions can carry the observable that answers each one.

Scope is stated as a permission, not an invitation. The reviewer may report a non-blocking observation, and is told explicitly that saying nothing about naming, phrasing, and formatting is a valid outcome; new scope goes to a fresh Backlog item rather than into the verdict. That is a deliberate narrowing of an earlier framing that asked for opportunities alongside problems, which is what let the prompts fill up with nits.

The templates do not prescribe a reading order, an assessment rubric, or a per-finding output format: rubrics get completed rather than thought about, and the fresh-context reviewer exists to apply independent judgment. Four questions across two gates are close to the shape that rots into a form to fill in, so each is phrased as a decision the reviewer owns. When editing the templates, prefer deleting instructions over adding them; do not re-accrete checklists, and keep process mechanics to at most a third of each body (they were near half before the reorder, which is what produced the nits).

## Spec-stage template

Emit this as a fenced ```text``` block, replacing the `{{...}}` tokens.

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

## Implementation-stage template

Emit this as a fenced ```text``` block, replacing the `{{...}}` tokens.

````text
You are an independent reviewer doing the In Review → Done approval on roadmap
item {{Rn}} in graphitron-rewrite, "{{title}}".

Repo: {{repo-root}}
Spec: {{spec-path}}

Sync before you read anything. Implementation commits may live on trunk and not
yet on your local branch, and reviewing a partial delivery voids the gate:

    git fetch origin claude/graphitron-rewrite
    git rebase origin/claude/graphitron-rewrite

If the rebase conflicts, surface and stop.

# The two questions this gate decides

roadmap/workflow.adoc, "What each gate decides", is the definition. Both
questions are restated here so you can work from them directly.

1. Is the implementation correct, and is it the change the spec approved?
   Correct code can still be a different change. Read the spec as the contract
   and the delivered tree as what arrived, then say where the two part company:
   scope the spec named and the delivery skipped, scope the delivery added that
   nobody approved, a design the implementer substituted along the way. A
   substitution made for good reasons is still a finding; it belongs in the spec
   body or back through the gate, not in a silent diff.

2. How do we know the item is complete? Name the test, fixture, generated
   output, or documented behaviour that demonstrates the stated goal is
   delivered, then check that it does. A green build answers a weaker question,
   because it is compatible with the goal being half-delivered. Where the spec
   named its own completeness evidence, hold the delivery to that; where the
   evidence cannot exist yet, say so plainly rather than pointing at the build.

A delivery that answers both is Done. A delivery that fails either goes back to
Ready, and your finding is the reason.

# What is out of scope

Naming preferences, phrasing, formatting, micro-refactors, and restating the
diff are not what this gate decides. Raise one only when it bears on question 1
or 2, and say which. An improvement the contract does not demand belongs in a
fresh Backlog item (any session can file one), not in this verdict. Anything
else you noticed goes in a short non-blocking section, or goes unsaid.

# Materials

- {{spec-path}}: the contract; what the implementer promised to deliver.
- The delivered state of the tree: the files the item touched as they now stand.
  That is the deliverable, not the path taken to it. The commit list under
  Bookkeeping is provenance if you want it, but reading forward through it
  spends attention on states a later commit already replaced.
- docs/architecture/explanation/development-principles.adoc and
  docs/graphitron-principles.adoc: where "good" is defined here.
- roadmap/workflow.adoc: this gate carries obligations of its own; its
  "User-facing-doc check" and "Retirement sweep" paragraphs apply before
  approval.
- docs/architecture/index.adoc: orientation when the touched area is unfamiliar.
- The `reviewer-prompt` skill's "What to look for" section: a diff-level
  architectural taxonomy, useful for reading the code and never sufficient on
  its own to hold this gate. A completed checklist answers neither question.

Depth and order are your call. You are an agent, not a reader: run the build,
grep the tree, read the code the spec claims to have changed.

# Verdict

End with one of two, unambiguously:

- Approve. Delete the spec file (`rm {{spec-path}}`); if the milestone is worth
  preserving, append a one-line entry to roadmap/changelog.md naming {{Rn}} and
  the landing SHAs. Regenerate the README with the `roadmap` skill, commit on a
  fresh feature branch, then `publish`.
- Request rework, naming which question failed and what would satisfy it. Flip
  In Review → Ready with the `roadmap` skill and capture the finding in the spec
  body for the next pass.

A clean delivery is a valid outcome. Say so plainly rather than inventing
findings.

## Approval preconditions (non-inferable project facts)

- `mvn install -Plocal-db` passes. A failing build is automatic rework.
- No code-string assertions on generated method bodies anywhere in the
  delivered tests; development-principles.adoc pins this test-tier rule.
- The spec body reflects what shipped: phases collapsed to one-line
  "shipped at <sha>" notes, remaining work clearly named.

# Bookkeeping (hard requirement, settle it before you approve)

Per roadmap/workflow.adoc, "States and transitions", In Review → Done requires a
reviewer session different from every session that authored an implementation
commit, compared by Claude Code session ID (the
`https://claude.ai/code/session_<id>` trailer on each commit), not by git author
and not by human identity. Your own ID is in your system prompt. If it matches a
disqualified ID, hand off to a different session.

Disqualified session IDs: {{disqualified-session-ids}}
Git-author fallback, for `<no-trailer>` entries: {{disqualified-authors}}

Recent commits referencing {{Rn}}, most recent first:

{{recent-commits}}
````

## Output rules

- Exactly one fenced block. No "Here's the prompt:" preamble, no trailing notes — those break one-click copy.
- Pre-fill `{{Rn}}`, `{{repo-root}}`, `{{spec-path}}`, `{{title}}`, `{{recent-commits}}`, plus the disqualified-identifier pair `{{disqualified-session-ids}}` + `{{disqualified-authors}}`. Both tokens are plural at both stages. The Spec stage's step-4 resolution currently yields a single session, so it fills a list of one; that is honest, and it means the resolver can widen to the set the guard actually means without re-pluralising the template. The user should not have to edit the block. When the trailer is absent, fill the session-ID slot with `<no-trailer>` literally; do not omit it.
- `{{recent-commits}}` is the literal output of `git log --oneline -10 -- <spec-path>`, indented four spaces.
- If any resolved disqualified session ID matches the invoking session's own ID (visible to the agent in its system prompt's commit-trailer URL), surface that fact in a short line *outside* the fenced block, but still emit the prompt — the user may want to forward it to a fresh agent. If every disqualified entry resolves to `<no-trailer>`, surface that the gate has no signal and the user must vouch for reviewer independence.

## Hard rules

- Always sync (step 0) before resolving the item. A stale checkout produces a stale recent-commits block, a stale disqualified-session-ID attribution, and — worst — hands the reviewer a spec body that's missing commits already on trunk. The emitted templates also instruct the reviewer to sync; the skill itself must sync too so the resolved values reflect truth.
- Do not perform the review yourself. The skill exists to hand off; doing the work in-session defeats the point of getting a second pair of eyes.
- Do not improvise the templates per call. Adjust pre-filled values; leave the body literal. Drift means each reviewer gets a different rubric.
- The prompt references files in the repo; it does not paste their contents. The reviewing agent reads them in its own session.
- The reviewer-rule guard cannot be bypassed because "the user said so". If the only available reviewer is a session that matches a disqualified session ID, surface it and stop — a different session must perform the review.
- For statuses other than `Spec` or `In Review`, no review handoff applies; tell the user and stop instead of forcing a template.
