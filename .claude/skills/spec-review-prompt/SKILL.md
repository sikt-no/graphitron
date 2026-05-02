---
name: spec-review-prompt
description: Produce a single copy-pasteable code block that prompts another agent to perform a workflow-gate review on a graphitron-rewrite roadmap item. Use when the user asks for a "spec review prompt", "review prompt for R<n>", "Spec → Ready handoff", "In Review → Done handoff", or otherwise wants a hand-off prompt for the workflow-gate review on a specific roadmap item. The emitted prompt encodes the relevant reviewer-rule and pre-fills recent commit history so the next reviewer can apply the rule without digging.
---

# Spec Review Prompt

Generates a self-contained prompt the user copies into another agent to perform a workflow-gate review on a graphitron-rewrite roadmap item. The reviewer either signs off (advancing the gate) or requests revisions (item stays in current state).

The skill's job is the *hand-off*. Do not perform the review yourself.

## When to use

The user names a roadmap item `R<n>` and asks for a hand-off prompt for the next reviewer. Two gates need this:

- **Spec → Ready** (item is `status: Spec`). Reviewer reads the spec body and either signs off or requests revisions. Reviewer rule: ≠ last committer of the spec file.
- **In Review → Done** (item is `status: In Review`). Reviewer reads the shipped implementation against the spec and either approves (delete the spec file, optionally entry the changelog) or requests rework. Reviewer rule: ≠ implementer.

For other statuses (Backlog, Ready, In Progress), no formal review handoff applies; tell the user and stop. For paired sibling skills, see `roadmap` (state machine + ID lookup) and `reviewer-prompt` (architecture-focused code-diff review handoff).

## Procedure

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

4. **Resolve the disqualified party.** This is the load-bearing piece — the next reviewer applies the rule by name, not by re-deriving it.

   Spec stage:
   ```bash
   git log -1 --pretty='%an' -- graphitron-rewrite/roadmap/<slug>.md
   ```

   Implementation stage: the implementer is whoever authored the implementation commits between the most recent `Ready → In Progress` and `In Progress → In Review` status flips. Approximate by listing unique authors of the recent commits referencing `R<n>`:
   ```bash
   git log --pretty='%h %an %s' -20 | grep -E '\b<R<n>>\b'
   ```

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

Per graphitron-rewrite/docs/workflow.adoc, "Spec → Ready: sign off [reviewer ≠
last committer]". Recent spec-touching commits (most recent first):

{{recent-commits}}

Disqualified as reviewer: {{disqualified-author}} (last committer of the spec
file). You should be a different party — a fresh Claude Code session, the human
user, or an unrelated agent.

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
   branch (status stays Spec; reviewer ≠ last committer for the next pass), or
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

Per graphitron-rewrite/docs/workflow.adoc, "In Review → Done: approve [reviewer
≠ implementer]". Recent commits referencing {{Rn}} (most recent first):

{{recent-commits}}

Disqualified as reviewer: {{disqualified-authors}} (authors of the implementation
commits between the most recent Ready → In Progress and In Progress → In Review
flips). You should be a different party.

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
   Ready and capture review feedback in the spec body for the next pass.
   Reviewer ≠ implementer rule applies again next cycle.

# Output

For each material finding (if any): one line summarising, the file:line it
touches, the principle or spec section it bears on, and the suggested fix.
Then a final line: "Approve: yes/no (and what to do next)". Don't pad.
````

## Output rules

- Exactly one fenced block. No "Here's the prompt:" preamble, no trailing notes — those break one-click copy.
- Pre-fill `{{Rn}}`, `{{repo-root}}`, `{{spec-path}}`, `{{title}}`, `{{recent-commits}}`, and `{{disqualified-author}}` (Spec stage, singular) or `{{disqualified-authors}}` (Implementation stage, plural). The user should not have to edit the block.
- `{{recent-commits}}` is the literal output of `git log --oneline -10 -- <spec-path>`, indented four spaces.
- If the resolved disqualified party is the same session that's about to invoke this skill, surface that fact in a short line *outside* the fenced block, but still emit the prompt — the user may want to forward it to a fresh agent.

## Hard rules

- Do not perform the review yourself. The skill exists to hand off; doing the work in-session defeats the point of getting a second pair of eyes.
- Do not improvise the templates per call. Adjust pre-filled values; leave the body literal. Drift means each reviewer gets a different rubric.
- The prompt references files in the repo; it does not paste their contents. The reviewing agent reads them in its own session.
- The reviewer-rule guard cannot be bypassed because "the user said so". If the resolved disqualified party would be the only available reviewer, surface it and stop — a different party (typically the human user, or an independent agent session) must perform the review.
- For statuses other than `Spec` or `In Review`, no review handoff applies; tell the user and stop instead of forcing a template.
