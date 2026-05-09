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
   grep -lE "^id: R<n>$" graphitron-rewrite/roadmap/*.md
   ```

   Multiple matches: roadmap-tool bug; stop and surface. No matches: ID is unallocated, or the item shipped (file deleted on Done) and only its changelog entry remains; tell the user.

2. **Read the front-matter.** Pick `status:` and `title:` from the YAML block.

3. **Pick the template.**
   - `Spec` → Spec-stage template (gate: Spec → Ready)
   - `In Review` → Implementation-stage template (gate: In Review → Done)
   - Anything else → stop, tell the user no review handoff applies at status `<X>`.

4. **Resolve the disqualified party.** This is the load-bearing piece — the next reviewer applies the rule by ID, not by re-deriving it. Per `graphitron-rewrite/docs/workflow.adoc` § "States and transitions" (Reviewer rule paragraph), the comparison identifier is the Claude Code session ID recorded as the `https://claude.ai/code/session_<id>` trailer on each commit. Resolve both the session ID (primary) and the git author name (fallback for trailer-less commits) so the emitted template carries both.

   Spec stage:
   ```bash
   sha=$(git log -1 --format=%H -- graphitron-rewrite/roadmap/<slug>.md)
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
   git log --oneline -10 -- graphitron-rewrite/roadmap/<slug>.md
   ```

6. **Emit the prompt.** Output exactly one fenced block, pre-filled with the resolved values. Use the appropriate template below verbatim.

## Spec-stage template

Emit this as a fenced ```text``` block, replacing the `{{...}}` tokens.

````text
You are an independent reviewer doing the Spec → Ready sign-off on roadmap item
{{Rn}} in graphitron-rewrite. Goal: a go/no-go. Either flip the spec's status
to Ready (so the implementer can start), or request specific revisions (stays
in Spec for another pass).

Repo:    {{repo-root}}
Spec:    {{spec-path}}  (id: {{Rn}}, title: {{title}}, status: Spec)

# Workflow rule

Per graphitron-rewrite/docs/workflow.adoc § "States and transitions", the
Spec → Ready guard is "reviewer ≠ last committer". The "Reviewer rule"
paragraph below the state diagram pins the identifier: the comparison is by
Claude Code session ID, recorded as the `https://claude.ai/code/session_<id>`
trailer on each commit, not by git author or human identity.

Recent spec-touching commits (most recent first):

{{recent-commits}}

Disqualified session ID: {{disqualified-session-id}}
(Fallback identifier — git author of the same commit: {{disqualified-author}}.
Used only when the disqualified session ID resolves to `<no-trailer>`, meaning
the spec-file commit predates the trailer-tracking convention; in that case
defer to the user's judgment that you are an independent session.)

Your own session ID is in your system prompt, embedded in the trailer URL
Claude Code stamps on every commit. If it matches the disqualified ID, hand
off to a different session.

# Sync first

Before reading the spec, sync with trunk — the spec body may live in a commit
that hasn't reached your local branch:

    git fetch origin claude/graphitron-rewrite
    git rebase origin/claude/graphitron-rewrite

If the rebase conflicts, surface and stop until resolved. Don't review a
13-line stub that should be a 100-line plan.

# Read first (in this order)

1. {{spec-path}}  (the spec under review)
2. graphitron-rewrite/docs/rewrite-design-principles.adoc  (technical principles)
3. graphitron-rewrite/docs/workflow.adoc  (esp. "Documentation names only live tests/code")
4. graphitron-rewrite/docs/README.adoc  (architectural orientation)

# What to assess

Spec-stage framing: "is this plan sound enough to hand to an implementer", not
"is this code correct".

- **Architectural soundness against principles.** Does the proposed shape align
  with the rewrite-design-principles? Sealed hierarchies, generation-thinking,
  classifier guarantees, validator-mirrors-classifier, etc. Surface any place
  the spec pushes against a principle without justifying the trade.
- **Plan completeness.** Decisions left implicit that the implementer would have
  to invent. Hedges where the spec should pick a side.
- **Test coverage adequacy.** Pipeline-tier, execution-tier, audit, resolver-tier
  coverage as called for. Does the named coverage exercise what the spec
  changes? Any scenarios missing?
- **Scope.** Is the "Out of scope" list honest?
- **Stale-reference rule.** Per "Documentation names only live tests/code":
  every concrete code/test/symbol the spec names must exist as named, with the
  cited paths and line numbers. Spot-check liberally; FQN-aware grep
  (`grep -rn LoadBearingClassifierCheck` rather than `grep '@LoadBearing...'`)
  catches false negatives that a simple-name search misses.

# Two acceptable outcomes

1. **Sign off.** Use the `roadmap` skill to flip status from Spec → Ready, then
   `publish` to push and fast-forward trunk.
2. **Request revisions.** Either commit revisions yourself on a fresh feature
   branch (status stays Spec; the next pass's reviewer-session must be
   different from yours, since you've now become the last committer), or
   leave a note for the original author. Use the `roadmap` skill if you need a
   `Spec → Spec` revise transition recorded.

# Output

For each material finding (if any): one line summarising, the spec section / line
it touches, the principle or workflow rule it bears on, and the suggested
revision shape. Then a final line: "Sign off: yes/no (and what to do next)". A
clean review can be three sentences. Don't pad.
````

## Implementation-stage template

Emit this as a fenced ```text``` block, replacing the `{{...}}` tokens.

````text
You are an independent reviewer doing the In Review → Done approval on roadmap
item {{Rn}} in graphitron-rewrite. Goal: a go/no-go. Either approve (delete the
spec file, optionally entry the changelog), or request rework (status flips
back to Ready for another implementation pass).

Repo:    {{repo-root}}
Spec:    {{spec-path}}  (id: {{Rn}}, title: {{title}}, status: In Review)

# Workflow rule

Per graphitron-rewrite/docs/workflow.adoc § "States and transitions", the
In Review → Done guard is "reviewer ≠ implementer". The "Reviewer rule"
paragraph below the state diagram pins the identifier: the comparison is by
Claude Code session ID, recorded as the `https://claude.ai/code/session_<id>`
trailer on each commit, not by git author or human identity.

Recent commits referencing {{Rn}} (most recent first):

{{recent-commits}}

Disqualified session IDs (any session that authored a commit in the
implementation range): {{disqualified-session-ids}}
(Fallback identifiers — git authors of the same commits: {{disqualified-authors}}.
Used only for entries that resolve to `<no-trailer>`, meaning the commit
predates the trailer-tracking convention; in those cases defer to the user's
judgment that you are an independent session.)

Your own session ID is in your system prompt, embedded in the trailer URL
Claude Code stamps on every commit. If it matches any disqualified ID, hand
off to a different session.

# Sync first

Before reading anything, sync with trunk — implementation commits may live on
trunk and not yet on your local branch:

    git fetch origin claude/graphitron-rewrite
    git rebase origin/claude/graphitron-rewrite

If the rebase conflicts, surface and stop until resolved. Reviewing a partial
diff is worse than waiting for a clean checkout.

# Read first (in this order)

1. {{spec-path}}  (the contract; what the implementer was building)
2. The implementation diff: `git log --oneline -20 -- graphitron-rewrite/` and
   `git show <sha>` on the implementation commits.
3. graphitron-rewrite/docs/rewrite-design-principles.adoc  (technical principles)
4. graphitron-rewrite/docs/README.adoc  (architectural orientation)

# What to assess

Implementation-stage framing: spec is the contract, diff is the delivery.

- **Spec → diff alignment.** Does the implementation deliver what the spec
  promised? Anything the spec marked out-of-scope but landed anyway, or
  anything in scope that didn't ship?
- **Architectural soundness in the diff.** Apply the principles from
  rewrite-design-principles.adoc; surface any place the diff weakens or
  contradicts them. The `reviewer-prompt` skill's "What to look for" list is
  the canonical taxonomy if you want a checklist.
- **Test coverage.** Spec-named pipeline-tier, execution-tier, audit, and unit
  tests are present and assert what the spec said they would assert. No
  code-string assertions on generated method bodies.
- **Build green.** `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes.
  A failing build is automatic rework.
- **Plan housekeeping.** The spec body should be marked up to reflect what
  shipped: phases collapsed to one-line `shipped at <sha>` notes, remaining
  work clearly named.

# Two acceptable outcomes

1. **Approve.** Delete the spec file (`rm {{spec-path}}`); if the milestone is
   worth preserving, append a one-line entry to graphitron-rewrite/roadmap/
   changelog.md naming the {{Rn}} ID and landing commit SHAs. Regenerate the
   README via the `roadmap` skill, commit on a fresh feature branch, then
   `publish` to push and fast-forward trunk.
2. **Request rework.** Use the `roadmap` skill to flip status from In Review →
   Ready and capture review feedback in the spec body for the next pass. The
   reviewer-session ≠ implementer-session rule applies again next cycle.

# Output

For each material finding (if any): one line summarising, the file:line it
touches, the principle or spec section it bears on, and the suggested fix.
Then a final line: "Approve: yes/no (and what to do next)". Don't pad.
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
