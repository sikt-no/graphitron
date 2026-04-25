# Plan: Rebase and squash rewrite branch onto main

> **Status:** Ready

## Goal

Replace the 566-commit history on `claude/graphitron-rewrite` with two
clean commits rebased onto current `origin/main`. The working branch for
this operation is `claude/rebase-squash-graphitron-Rv4u2`.

## Context (facts at plan-writing time)

| Fact | Value |
|---|---|
| Commits ahead of `origin/main` | 595 (as of sync on 2026-04-24) |
| Merge base with `origin/main` | `ab3daff2` (Bump quarkus-rest-common 3.34.2→3.34.3) |
| `origin/main` commits since branch diverged | 16 (bug fixes, bumps, GG-343/387/451/452) |
| Repo shallow | Was shallow; unshallowed in the session that wrote this plan |
| New commits since plan drafting | `ac3df0b7` + `f7d2be49` deleted legacy integration code; `76754b33` added standalone `graphitron-rewrite-maven` inside `graphitron-rewrite/`; `7df7638f` removed `graphitron-rewrite` from root reactor (aggregator now standalone); `7da16e79` + `aa0f0b78` completed the standalone aggregator build |

The 566 commits break down as roughly 303 `docs(...)` planning entries
and 263 code commits. The rewrite module (`graphitron-rewrite/`) is
entirely new; the only non-rewrite changes we keep are docs and the
module registration line in the root `pom.xml`.

## Commit structure

**Commit 1: `feat(rewrite): add graphitron-rewrite module`**

Files included:
- Everything under `graphitron-rewrite/`

Root `pom.xml` is entirely dropped (see Drop table below). The module entry
that once existed there was removed by `7df7638f` once the rewrite aggregator
became standalone; the only remaining diff is a Java 21 version bump that is
a legacy concern.

**Commit 2: `docs: CLAUDE.md, project docs, and tooling updates`**

Files included:
- `.gitignore` (added `.mvn/`, `scratch/` entries)
- `CLAUDE.md` (rewritten for rewrite workflow)
- `README.md` (doc restructuring)
- `VISION.md` (one bullet added)
- `docs/README.md` (new)
- `docs/dependencies.md` (new)
- `docs/graphitron-principles.md` (new)
- `docs/security.md` (new)
- `docs/vision-and-goal.md` (new)
- `graphitron-codegen-parent/graphitron-java-codegen/README.md`
- `graphitron-common/README.md`

**Dropped (not staged in any commit):**

These changes exist on `origin/claude/graphitron-rewrite` but are not
carried forward. They are reverted by checking out `HEAD` (main's version)
after the squash merge.

| File | Why dropped |
|---|---|
| `graphitron-codegen-parent/graphitron-java-codegen/pom.xml` | Java 21 + `-parameters` flags; legacy concern |
| `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/.../ExternalMojoClassReference.java` | `fullyQualifiedClassName()` accessor for old Mojo bridge |
| `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/.../GenerationSourceField.java` | `@condition` guard added for dual-pipeline mode |
| `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/.../Generator.java` | Blank line only; drop for cleanliness |
| `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/.../GraphQLGenerator.java` | Generator ordering fix for dual-pipeline mode |
| `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/.../JavaPoetClassName.java` | String-ref fix for `RecordValidator` needed for dual-pipeline compile |
| `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/.../dummyreferences/TestRecordDto.java` | Test fixture for old integration path |
| `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java` | `getTenantId()` default method; rewrite emits its own `GraphitronContext` interface and has zero import of this class |
| `graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java` | Added to common but never referenced from anywhere (dead code) |
| `graphitron-common/src/main/resources/directives.graphqls` | `@asConnection` added; rewrite injects its own copy via `RewriteSchemaLoader` |
| `graphitron-maven-plugin/src/main/java/.../GenerateMojo.java` | Minor `runGenerators()` wrapper refactor; unnecessary with no rewrite bridge |
| `graphitron-maven-plugin/src/main/java/.../ValidateMojo.java` | Call reordering only; take main's version |
| `graphitron-maven-plugin/src/main/java/.../WatcherMojo.java` | New dev-convenience goal; unreviewed legacy addition |
| `graphitron-maven-plugin/src/test/java/.../WatcherMojoTest.java` | Test for the above |
| `graphitron-schema-transform/src/main/java/.../MakeConnections.java` | Stopped removing `@asConnection`; only needed for dual-pipeline schema-transform path |
| `graphitron-schema-transform/src/test/resources/addTagsOnConnectionTypes/expected/schema.graphql` | Test fixture for `MakeConnections.java` change |
| `graphitron-schema-transform/src/test/resources/createNestedPagination/expected/schema.graphql` | Test fixture for `MakeConnections.java` change |
| Root `pom.xml` | Java 21 version bump + comment; the module entry that once existed was already removed on trunk by `7df7638f` (aggregator is standalone); take main's version entirely |

## Procedure

### Step 0: sync

```bash
git fetch origin main claude/graphitron-rewrite
```

Verify the trunk tip looks right before touching anything:

```bash
git log --oneline -5 origin/claude/graphitron-rewrite
```

Expected top commit: `8a8c5efe rewrite-maven: fix output directory and
plexus-utils dependency` (or later if more trunk commits have landed).

### Step 1: reset working branch to current main

```bash
git checkout claude/rebase-squash-graphitron-Rv4u2
git reset --hard origin/main
```

Working tree is now identical to `origin/main`. Verify:

```bash
git status        # should be clean
git log --oneline -1   # should show the top main commit
```

### Step 2: squash merge

```bash
git merge --squash origin/claude/graphitron-rewrite
```

All 566 commits collapse into one staged working-tree change. Conflicts
are resolved once here rather than commit-by-commit.

**If conflicts are reported:** the most likely conflicts are in files on
the "Dropped" list above (both sides changed them). Resolve all dropped
files by taking main's version:

```bash
git checkout HEAD -- \
  graphitron-codegen-parent/graphitron-java-codegen/pom.xml \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/configuration/externalreferences/ExternalMojoClassReference.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/definitions/fields/GenerationSourceField.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generate/Generator.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generate/GraphQLGenerator.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/mappings/JavaPoetClassName.java \
  graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java \
  graphitron-common/src/main/resources/directives.graphqls \
  graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/GenerateMojo.java \
  graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java \
  pom.xml
```

Then `git add` each resolved file and continue. New-file conflicts
(files added on the rewrite branch that don't exist on main) do not
arise; git simply adds them to the working tree.

### Step 3: unstage everything

After the merge --squash, git stages all changes. Unstage so we can
build the two commits manually:

```bash
git reset HEAD
```

Working tree now holds all the desired and dropped changes as
_unstaged_ modifications. Nothing is committed yet.

### Step 4: commit the rewrite module

```bash
git add graphitron-rewrite/
git commit -m "feat(rewrite): add graphitron-rewrite module"
```

Sanity check before committing:

```bash
git diff --cached --name-only | sort
# Should list only files under graphitron-rewrite/
# Should NOT list anything outside graphitron-rewrite/
```

### Step 5: commit the docs

```bash
git add .gitignore CLAUDE.md README.md VISION.md docs/
git add graphitron-codegen-parent/graphitron-java-codegen/README.md
git add graphitron-common/README.md
git commit -m "docs: CLAUDE.md, project docs, and tooling updates"
```

### Step 6: drop the remaining legacy changes

The working tree still has the "Dropped" files in their rewrite-branch
state. Revert them all to main's version (new files get deleted,
modified files get restored):

```bash
# Modified files: restore to main
git checkout HEAD -- \
  graphitron-codegen-parent/graphitron-java-codegen/pom.xml \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/configuration/externalreferences/ExternalMojoClassReference.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/definitions/fields/GenerationSourceField.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generate/Generator.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generate/GraphQLGenerator.java \
  graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/mappings/JavaPoetClassName.java \
  graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java \
  graphitron-common/src/main/resources/directives.graphqls \
  graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/GenerateMojo.java \
  graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java \
  pom.xml \
  graphitron-schema-transform/src/main/java/no/fellesstudentsystem/schema_transformer/transform/MakeConnections.java \
  graphitron-schema-transform/src/test/resources/addTagsOnConnectionTypes/expected/schema.graphql \
  graphitron-schema-transform/src/test/resources/createNestedPagination/expected/schema.graphql

# New files (added only on rewrite branch): delete
git rm -f \
  graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/codereferences/dummyreferences/TestRecordDto.java \
  graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java \
  graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/WatcherMojo.java \
  graphitron-maven-plugin/src/test/java/no/sikt/graphitron/maven/WatcherMojoTest.java 2>/dev/null || true
```

### Step 7: verify clean state

```bash
git status
```

Expected: `nothing to commit, working tree clean`. If any untracked or
modified files remain, they are either missed drops (add them to the
`checkout HEAD` or `rm` commands above) or a sign that the merge
introduced something unexpected. Investigate before pushing.

Double-check the commit graph:

```bash
git log --oneline origin/main..HEAD
# Expected: exactly 2 commits
```

Confirm no outside-graphitron-rewrite code files crept into commit 1:

```bash
git show --name-only HEAD~1 | grep -v "^graphitron-rewrite/" | grep "\.java\|\.xml" | grep -v "^pom\.xml$"
# Expected: no output
```

### Step 8: push and fast-forward trunk

```bash
git push --force-with-lease origin claude/rebase-squash-graphitron-Rv4u2
git push origin claude/rebase-squash-graphitron-Rv4u2:claude/graphitron-rewrite
```

If the trunk push is rejected because `origin/main` has moved again
since the squash merge, repeat Steps 0-9 from scratch. The whole
operation takes a few minutes; stale trunk fast-forwards are caught
by the server before any damage is done.

## Known snag locations

**Conflict in `pom.xml`**: both the 16 new main commits and the rewrite
branch touched `pom.xml`. The rewrite branch's remaining diff is only a
Java 21 version bump plus a comment change in the `maven-compiler-plugin`
block (the module entry was already removed on trunk by `7df7638f`). After
`merge --squash`, if a conflict marker appears in `pom.xml`, resolve it by
taking main's version entirely:

```bash
git checkout HEAD -- pom.xml
git add pom.xml
```

Then continue as normal; `pom.xml` lands in the Step 6 drop list, not in
any commit.

**WatcherMojo.java doesn't exist on main**: `git checkout HEAD -- ...`
will fail for it. Use `git rm -f` as shown in Step 7 instead.

**`graphitron-rewrite-test/pom.xml` is inside `graphitron-rewrite/`**:
it is correctly included in commit 1. The `<plugin>` declaration there
now references `graphitron-rewrite-maven` (landed in `76754b33`), not
the legacy plugin.

**`plan-rewrite-maven-plugin.md`**: implementation landed on trunk and
the plan was subsequently marked Done and deleted per the delete-on-done
rule. No action needed.

## Branches invalidated by this squash

The squash rewrites the entire `claude/graphitron-rewrite` ancestry, so
any branch whose merge-base with the rewrite trunk sits after `ab3daff2`
(where trunk diverged from main) will dangle. Branches based solely on
`origin/main` history are unaffected.

**Branches with unique commits (actual work lost):**

| Branch | Unique commits | Content |
|---|---|---|
| `alf/graphitron-rewrite` | 1 | `graphitron-rewrite/generator-schema.graphql` — a 20,678-line draft schema added by Alf Lervag on 2026-04-23 to check for override problems. It is a scratch file, not wired into any build target. If the content is still needed, cherry-pick `d84d42f8` onto the new trunk tip after the squash. |

**Branches with no unique commits (bookmarks only — nothing lost):**

These branches point to old trunk commits and carry no work of their own.
They become dangling refs after the squash. Safe to delete.

| Branch |
|---|
| `claude/add-service-code-vision-93U9X` |
| `claude/fix-service-parameter-source-7YyEm` |
| `claude/fix-splitquery-reference-path-O0jnd` |
| `claude/improve-code-quality-v7kcC` |
| `claude/move-fetchers-package-M7bNu` |
| `claude/plan-next-tasks-FXsMR` |
| `claude/plan-next-tasks-XM4Ee` |
| `claude/review-nestingfield-plan-VijHA` |
| `claude/review-plan-service-fetchers-DK9jg` |
| `claude/review-plan-split-query-dGXBz` |
| `claude/review-wiring-plan-CqlgR` |

## Verification after landing

Run the rewrite-module build to confirm the squash is self-consistent:

```bash
/opt/maven/bin/mvn clean install -f graphitron-rewrite/pom.xml -Pquick
```

Expected: BUILD SUCCESS. Any compile failure means a file was
accidentally dropped from `graphitron-rewrite/` or the root `pom.xml`
module entry was omitted.
