---
description: Fetch origin/main, diff the current branch, and research the changes
model: opus
---

# Research Changes from Main

You are tasked with fetching the latest `origin/main`, diffing the current branch against it, and producing a research document of the changes.

## Output Contract

When you finish, return these fields as plain text in your final message — the caller parses them. There is no structured return.

- `research_doc_path` — absolute path to the research document under `thoughts/shared/research/`
- `ticket` — the detected ticket ID (see Step 2) or `null`
- `branch` — the current branch name
- `diff_stat` — the `git diff --stat` output

Do not print the full diff back to the caller.

## Step 1: Fetch and Compare

```
git fetch origin main
git branch --show-current
git rev-list --left-right --count origin/main...HEAD
git diff origin/main...HEAD --stat
git log origin/main..HEAD --oneline
```

The `--left-right --count` output is `<behind>\t<ahead>`. If `<behind>` is greater than 0:
- Tell the user how many commits behind `origin/main` they are.
- **Stop** and ask whether to rebase first.
- Only continue if the user explicitly opts to skip the rebase.
- After the user rebases and asks you to continue, re-run the commands above to confirm `<behind>` is now `0` before proceeding to Step 2.

## Step 2: Determine the Ticket

Extract a ticket ID from the branch name using the pattern `GG-\d+` (case-insensitive). If no match, set `ticket = null` and use the branch name for filenames instead.

## Step 3: Collect the Diff

Do not dump the full diff into context. Use the `--stat` output from Step 1 as a file inventory, then read individual file diffs only for files you need to understand:

```
git diff origin/main...HEAD -- <path>
```

Prioritize source files over generated/approval files. Skip large approval JSON and generated output unless they are the substance of the change.

## Step 4: Check for Existing Research

Look in `thoughts/shared/research/` for a document whose filename contains the ticket (or branch name if no ticket). If one exists:
- Read its YAML frontmatter. If `git_commit` equals the current `HEAD` commit, the document is already current — return the output contract immediately. No user prompt, no re-research.
- Otherwise, read the document fully, summarize it briefly for the user, and ask whether to reuse it as-is or re-research.
- If reusing, return the output contract (see top) immediately.

If none exists, proceed to Step 5.

## Step 5: Research the Changes

### 5a. Choose inline vs. delegated research

- **Small diff** — ≤ 10 files changed AND ≤ 500 lines changed (per `git diff --stat`): do the research inline (5b). Reading a small diff directly is much faster than spawning `/research_codebase`.
- **Larger diff**: delegate to `/research_codebase` (5c).

### 5b. Inline research (small diffs)

1. Read each changed file's diff via `git diff origin/main...HEAD -- <path>`, prioritizing source files.
2. For any file whose diff you cannot interpret in isolation, read the full post-change file contents.
3. Write a research document directly to `thoughts/shared/research/YYYY-MM-DD-<ticket-or-branch>-<kebab-description>.md`. Keep it focused on the diff itself — do not expand into broader codebase exploration.
4. Use YAML frontmatter with these fields (matching the convention used by `/research_codebase`): `date`, `researcher`, `git_commit` (set to current `HEAD`), `branch`, `repository`, `topic`, `tags`, `status`, `last_updated`, `last_updated_by`.
5. Return the output contract.

### 5c. Delegated research (larger diffs)

Invoke `/research_codebase` with the research question and the diff inventory **passed inline in the same message**. `/research_codebase` normally prompts for input on invocation — include the full query up-front so it proceeds without a second turn.

Query to pass:
- Branch: `<branch>`, ticket: `<ticket-or-null>`
- Files changed (from Step 1 `--stat`): `<paste stat output>`
- Research goals:
  - What was changed and why
  - What new functionality was added
  - How the changed components connect to the rest of the codebase
  - Existing patterns and conventions relevant to the changed code
- **Scope constraint**: Document only the diff and files it directly depends on. Do not explore the broader codebase unless a changed file imports from, or is imported by, files outside the diff. Favor depth over breadth.

Capture the research document path from `/research_codebase`'s output and return the output contract.