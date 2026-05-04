---
name: reap-merged-branches
description: Delete remote branches whose commits are all contained in the claude/graphitron-rewrite trunk. Use when cleaning up dead/merged Claude branches off the rewrite trunk.
---

# Reap merged branches

Delete remote branches on `origin` whose tip is reachable from `origin/claude/graphitron-rewrite` — i.e. every commit on the branch is already contained in trunk, so the branch is dead.

## 1. Unshallow fetch

The working clone is typically shallow. `--merged` reachability checks need full history, so unshallow first and prune stale remotes:

```bash
if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  git fetch --unshallow origin
fi
git fetch origin --prune
```

## 2. List candidate branches

Remote branches whose tip is reachable from trunk, excluding trunk itself, `main`, and `HEAD`:

```bash
TRUNK=origin/claude/graphitron-rewrite
git for-each-ref --format='%(refname:short)' --merged "$TRUNK" refs/remotes/origin/ \
  | grep -vE '^origin/(HEAD|main|claude/graphitron-rewrite)$'
```

Review the output before step 3. Anything listed is safe to delete in the sense that no unique commits will be lost — every commit is already in trunk.

## 3. Delete the branches

```bash
TRUNK=origin/claude/graphitron-rewrite
git for-each-ref --format='%(refname:short)' --merged "$TRUNK" refs/remotes/origin/ \
  | grep -vE '^origin/(HEAD|main|claude/graphitron-rewrite)$' \
  | sed 's|^origin/||' \
  | xargs -r -n1 git push origin --delete
```

`xargs -n1` deletes one branch per `git push` call so a single protected/missing branch doesn't abort the rest.

## Notes

- `--merged <commit>` matches refs whose tip is an ancestor of `<commit>`. Squash-merged or rebased branches will **not** match — their commits exist under different SHAs in trunk. Use `git cherry` or PR state to reap those.
- Don't run this against the current branch; `git push origin --delete` of your checked-out branch will still succeed remotely but leaves you on a detached upstream.
