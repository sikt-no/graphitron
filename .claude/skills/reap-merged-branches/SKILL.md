---
name: reap-merged-branches
description: Delete remote branches whose commits are all contained in the claude/graphitron-rewrite trunk. Use when cleaning up dead/merged Claude branches off the rewrite trunk.
---

# Reap merged branches

Identify remote `claude/**` branches on `origin` whose tip is reachable from `origin/claude/graphitron-rewrite` — i.e. every commit on the branch is already contained in trunk, so the branch is dead. The skill prepares the list and emits a deletion command for the user to run; it does not delete branches itself, because the sandboxed git proxy rejects `git push --delete`.

## 1. Unshallow fetch

The working clone is typically shallow. `--merged` reachability checks need full history, so unshallow first and prune stale remotes:

```bash
if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  git fetch --unshallow origin
fi
git fetch origin --prune
```

## 2. List candidate branches

Remote `claude/**` branches whose tip is reachable from trunk, excluding trunk itself:

```bash
git for-each-ref --format='%(refname:short)' --merged origin/claude/graphitron-rewrite refs/remotes/origin/claude/ \
  | grep -v '^origin/claude/graphitron-rewrite$'
```

Show the output to the user. Anything listed is safe to delete in the sense that no unique commits will be lost — every commit is already in trunk. Non-`claude/**` branches (`main`, `dependabot/...`, legacy `GG-...`, etc.) are intentionally excluded; this skill only reaps Claude-authored branches.

## 3. Emit deletion command

Do **not** run the deletion. Instead, print the following command in a fenced bash block for the user to copy-paste and run themselves:

```bash
git for-each-ref --format='%(refname:short)' --merged origin/claude/graphitron-rewrite refs/remotes/origin/claude/ \
  | grep -v '^origin/claude/graphitron-rewrite$' \
  | sed 's|^origin/||' \
  | xargs -r -n1 git push origin --delete
```

`xargs -n1` deletes one branch per `git push` call so a single protected/missing branch doesn't abort the rest.

## Notes

- `--merged <commit>` matches refs whose tip is an ancestor of `<commit>`. Squash-merged or rebased branches will **not** match — their commits exist under different SHAs in trunk. Use `git cherry` or PR state to reap those.
- Don't run this against the current branch; `git push origin --delete` of your checked-out branch will still succeed remotely but leaves you on a detached upstream.
