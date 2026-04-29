---
name: publish
description: Push the current feature branch and fast-forward `claude/graphitron-rewrite` trunk in one step. Use whenever you would otherwise run `git push` on committed work in graphitron-rewrite. Trunk-based development requires the fast-forward to follow every feature-branch push (see CLAUDE.md "Git Workflow"); this skill does both and verifies success.
---

# Publish

Trunk-based, fast-forward-only flow for `graphitron-rewrite`. One invocation = branch push + trunk fast-forward.

## When to skip

Do NOT run this skill if any of the following hold (the work is not trunk-bound):

- The most recent commit message starts with `wip:`, `draft:`, or `spike:`.
- The current branch is named `wip/...`, `draft/...`, or `spike/...`.
- The user has said out-loud "don't ship this to trunk yet".

If any apply, push the feature branch only and tell the user trunk was deliberately skipped.

## Procedure

1. **Inspect state.** Run `git status --porcelain` and `git branch --show-current`.
   - If the working tree is dirty, stop and ask the user whether to commit, stash, or abort.
   - If the current branch is `claude/graphitron-rewrite`, stop. This skill never pushes from trunk directly; the user is on the wrong branch.
   - Read the most recent commit subject (`git log -1 --pretty=%s`). If it starts with one of the skip prefixes above, follow the "When to skip" rules.

2. **Sync trunk first.** `git fetch origin claude/graphitron-rewrite`. If `origin/claude/graphitron-rewrite` has commits not reachable from `HEAD`, the fast-forward will fail. Tell the user, suggest `git rebase origin/claude/graphitron-rewrite`, and stop.

3. **Push the feature branch.** `git push -u origin <branch>`. On network failure (not on rejection), retry up to 4 times with 2s, 4s, 8s, 16s back-off. On non-network failure (rejection, hook failure), stop and report.

4. **Fast-forward trunk.** `git push origin HEAD:claude/graphitron-rewrite`. Same retry rules. If this push is rejected, the user's branch must be rebased on trunk first; do NOT force-push trunk under any circumstance.

5. **Confirm.** Report both refs at their new SHA in one line: `<branch> @ <sha>; trunk @ <sha>`.

## Hard rules

- Never `--force` or `--force-with-lease` against `claude/graphitron-rewrite`.
- Never `--no-verify`. If a pre-push hook fails, surface the failure and stop.
- Never push to any branch other than the current feature branch and `claude/graphitron-rewrite`. If the user wants to push to `main` or anywhere else, they must say so explicitly.
