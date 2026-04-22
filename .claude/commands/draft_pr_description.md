---
description: Draft a PR description with ticket header, summary tailored to change type (refactor, bug fix, feature, improvement), and optional notes
model: opus
---

# Draft PR Description

You are tasked with generating a structured pull request summary for the current branch. The output has an `## Overview` metadata block, a `## Summary` shaped by the change type, and an optional `## Notes` section that appears only when it adds value.

Execute all seven steps in order. Pause for user input only at these three points: the rare `AskUserQuestion` call in Step 2 if the classification is genuinely ambiguous, the `AskUserQuestion` call in Step 3 (metadata), and the `Anything to add?` prompt in Step 4's Notes section. Step 1 invokes another skill (`/research_changes`) that produces its own output and may look like a completed task, but it is not: it is the first step of this command. The command is complete only after Step 7 has printed the full summary and saved path.

## Example Output

```
## Overview
**Related issue**: GG-123
**Issue coverage**: closes
**Backwards compatibility**: breaks only invalid usage

## Summary
<Summary prose shaped by the classification from Step 2. Length varies by type: 2 to 4 sentences for refactor, bug fix, and improvement; up to about 6 for new feature. No bullets.>

## Notes
- <Confirmed follow-up, limitation, or breaking-change callout. One per bullet.>
- <Another bullet if applicable; omit the section entirely when the list is empty.>
```

The example above shows the overall shape. The header values come from Step 3; the Summary length and content depend on the classification from Step 2, as specified in Step 4.

## Step 1: Research the Changes

Invoke `/research_changes`. It returns `{ research_doc_path, ticket, branch, diff_stat }`. Use those values directly; do not re-detect the ticket here.

When `/research_changes` returns, **do not stop**. Do not summarise the research back to the user, do not ask whether to continue — immediately move on to Step 2. The research skill's completion is not the command's completion.

## Step 2: Classify the Change

Decide whether the branch is primarily a **refactor**, **bug fix**, **new feature**, or **improvement**:

- **Refactor**: internal restructuring that preserves observable behavior.
- **Bug fix**: a defect in existing behavior is corrected.
- **New feature**: a capability is added, or an existing one extends to new cases.
- **Improvement**: the tool becomes stricter or clearer about what it supports without adding features or fixing defects. Tightened preconditions, better error messages on existing failure modes, and surfacing previously silent misconfigurations all belong here. Canonical example: a new validator that rejects a previously accepted but unsupported configuration, so misuse fails fast with a clear message instead of producing inconsistent output.

Use the commit messages, branch name, and research document to decide. If the branch mixes types, pick the dominant one and cover the secondary aspect in one extra sentence.

If the type is genuinely ambiguous (close call between two categories that would produce materially different Summary shapes), ask the user via a single `AskUserQuestion` call. Pass the four categories as selectable options, each with a one-line description matching the definitions above. Use the returned label as the classification. Only invoke this when you cannot pick a dominant category with reasonable confidence; do not use it as a default checkpoint.

## Step 3: Determine Header Metadata

Collect the two header values that the author must confirm: **issue coverage** and **backwards compatibility**. Ask both in a single `AskUserQuestion` call (one question per field) so the choices render as single-select lists rather than free text. Do not infer either value from the diff or commit messages; the author is the only reliable source.

If `ticket` is null, skip the issue-coverage question but still ask backwards compatibility. In that case the header omits the `**Related issue**` and `**Issue coverage**` lines and contains only `**Backwards compatibility**`.

**Issue coverage** — pass these three options verbatim, each with its label and description:

- `closes`: this PR alone fully resolves the ticket.
- `partial`: this PR addresses part of the ticket; more work is needed before the ticket can be closed.
- `related`: the ticket is relevant context but this PR is not expected to close it (e.g., a prerequisite refactor or cleanup).

**Backwards compatibility** — pass these three options verbatim, each with its label and description:

- `yes`: existing valid configurations continue to build and behave identically.
- `no`: previously valid configurations now fail or change behavior.
- `breaks only invalid usage`: builds may fail, but only for configurations or calls that were already invalid or unsupported (e.g., previously silent misconfigurations now caught by new validation, removed undocumented APIs); valid usage is unaffected.

Use the labels returned by the user directly in the header. Do not accept any other value; if the user's response is ambiguous, re-ask.

If backwards compatibility is `no` or `breaks only invalid usage`, the Notes candidate scan in Step 4 must surface at least one bullet explaining what breaks and how upgraders can find or fix it. The user's response to the `Anything to add?` prompt remains authoritative; if they remove every candidate and the final list is empty, the Notes section is omitted as normal.

## Step 4: Write the Output

The output has up to three parts, in this order: `## Overview`, `## Summary`, `## Notes` (conditional).

### Overview

The first heading of the output is exactly:

```
## Overview
```

Immediately followed (no blank line) by the metadata block. The block contains one bolded label per line, in this order:

```
**Related issue**: <TICKET>
**Issue coverage**: <coverage>
**Backwards compatibility**: <value>
```

Then a blank line before `## Summary`.

Rules:

- Labels are bolded with `**...**` and followed by a literal colon-space.
- `<coverage>` is one of `closes`, `partial`, or `related` from Step 3. Do not invent other values.
- `<value>` is one of `yes`, `no`, or `breaks only invalid usage` from Step 3. Do not invent other values.
- The `## Overview` section contains only this metadata block — no narrative prose, no bullets beyond the three labeled lines.
- If `ticket` is null, omit both the `**Related issue**` and `**Issue coverage**` lines; the Overview then contains only the `**Backwards compatibility**` line.
- If `ticket` is null **and** backwards compatibility is `yes`, omit the `## Overview` section entirely and start the output at `## Summary`.

### Summary (always present)

Write a `## Summary` section shaped by the classification from Step 2:

- **Refactor**: briefly describe what moved or changed and why it was needed. Skip the mechanical edits themselves. 2 to 4 sentences, no bullets.
- **Bug fix**: explain the root cause and how the fix addresses it. Skip the repro path and debugging story. 2 to 4 sentences, no bullets.
- **New feature**: describe what the feature does and how a caller uses it, then call out any limitations. To find limitations, look only at validation code **newly added in this diff** (schema validators, precondition checks, guards, directive-argument checks that appear as additions, not in unchanged context). These encode what the new feature does *not* support. Ignore pre-existing validation that the diff merely touches or moves; those limitations predate this PR and don't belong here. Up to about 6 sentences, no bullets.
- **Improvement**: describe the configuration or case that is now explicitly unsupported (or the behavior that is now stricter), and why it was problematic before. Do not frame it as a new feature. Make clear that valid usage is unaffected; only invalid or unsupported usage is now rejected. 2 to 4 sentences, no bullets.

Do not restate the ticket ID inside the Summary; it already appears in the Overview. Do not start the Summary with the classification label (no "Refactor: ..." or "This is a bug fix that ..."). The reader can infer the type from the prose.

Before drafting, read the `branch` name as a hint about intent. Branch names often capture scope or framing the diff alone does not make obvious. Treat the branch name as a tie-breaker when the diff could reasonably be described in multiple ways, and ignore generic names that carry no signal.

### Notes (conditional)

1. Scan the diff and research document for candidate notes: TODO comments newly added in this PR, obvious follow-up work implied by the changes (e.g., a partial migration, a stubbed code path), deliberate-looking omissions, or breaking changes.
2. Always present what you found to the user as a short bullet list, followed by a very short prompt — exactly `Anything to add?` and nothing longer. If you found nothing, still show the list with a single `- (none)` entry so the shape is consistent; do not skip the prompt.
3. The user's response is authoritative: apply their additions, removals, or corrections to the candidate list.

If the final list is empty, omit the `## Notes` section entirely. Otherwise, include a `## Notes` section covering only the confirmed content. Valid uses:

- Known limitations or explicit follow-up work.
- Deliberate out-of-scope decisions, with the reasoning in one sentence.
- Migration, deployment, or rollout considerations.
- Breaking change callouts.

Do **not** use `## Notes` as a conclusion, recap, or "in summary" section.

## Step 5: Formatting Rules

Apply these to every part of the output:

- Ticket ID is plain text. No backticks, bold, italics, or link.
- Code identifiers (column names, table names, directives, class names, config keys, enum values) use backticks: `FILM_ID`, `@splitQuery`, `FederationMode`.
- No em-dashes (`—`), no en-dashes (`–`), no double hyphens (`--`), no arrow characters (`→`, `⇒`, `->`, `=>`). Use commas, colons, parentheses, or full sentences. Hyphens are acceptable only inside established compound words (e.g., `non-null`, `type-safe`) and inside backticked code identifiers.
- Every sentence must add information. Cut anything that restates the title, previews the next sentence, or wraps up with "in summary" / "overall" / "this change". If a sentence could be deleted without losing information, delete it.
- Sentence and bullet counts are ceilings, not targets. Go shorter when the change warrants it.
- No file paths or line numbers in the output. Low-level implementation details belong in the diff, not the PR description.

## Step 6: Save the Output

Target path: `thoughts/shared/pr-descriptions/<ticket-or-branch>.md` (use `ticket` if non-null, otherwise `branch`).

Always overwrite the file if it exists. Do not prompt for confirmation and do not offer an append option; the previous contents are expected to be a stale draft.

## Step 7: Print the Output

Print the full output back to the user so it can be copy-pasted directly without opening the file. After the output, print the saved path on its own line at the very end of the response, rendered as a clickable absolute file URL:

```
file:///absolute/path/to/thoughts/shared/pr-descriptions/<name>.md
```

Do not omit the file URL even if the path appeared earlier in the response.