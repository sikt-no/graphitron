---
name: roadmap
description: Manage graphitron-rewrite roadmap items — add new Backlog entries (auto-allocates an `R<n>` ID), transition status (Backlog→Spec→Ready→In Progress→In Review→Done), and regenerate the rolled-up README. Use when the user asks to "add a roadmap item", "move R24 to Ready", "flip R12 to In Review", "mark R7 done", or any phrase about roadmap state. Items are referred to by `R<n>` (lookup by id is fast); slug is the filename only. Encodes the workflow.md state machine and the reviewer-rule guard.
---

# Roadmap

Operates on `roadmap/<slug>.md` files and the rolled-up `README.md`. Source of truth is the per-item YAML front-matter; the README is generated.

## Item identity: `R<n>`

Every item has an `id:` of the form `R<n>` (literal `R` + positive integer). IDs are **monotonic across the whole roadmap and never reused** — when an item ships and its file is deleted, the number stays a gap so references in `changelog.md` and commit messages keep their meaning. The user typically references items by ID alone; you should resolve ID→slug by grepping front-matter:

```bash
grep -lE "^id: R24$" roadmap/*.md
```

When echoing items back to the user, prefer `R24: <slug>` so both ID and human-readable name are visible.

## Subcommands

The skill recognises four intents from the user's request. Pick one and execute.

### add `<slug>` `--title "<title>"` `[--bucket <b>]` `[--priority <n>]` `[--theme <t>]`

Use the tool's `create` subcommand — it picks the next free `R<n>`, writes the file with the ID baked in, and refreshes the README atomically. Never construct the file by hand: the validator now requires `id:` and would fail the build, and a hand-rolled file races other sessions on number allocation.

```bash
mvn -pl roadmap-tool exec:java -q \
  -Dexec.args='create roadmap <slug> --title "<title>" \
               --bucket <bucket> --priority <n> --theme <theme>'
```

Slug rules (enforced by the tool): lowercase kebab-case, describes the work and not the phase (`variant-coverage-meta-test`, not `phase-2`), no `plan-` prefix.

The created file carries a `## Goal` heading with a placeholder paragraph. Edit it inline to state the real goal (what changes for graphitron or its consumers when the item lands) before the user reviews; `roadmap/workflow.adoc` § Item file conventions owns the body shape.

If the user only wants to know the next free number without writing a file yet:

```bash
mvn -pl roadmap-tool exec:java -q \
  -Dexec.args='next-id roadmap'
```

### status `<R<n>-or-slug>` `<new-state>`

**Sync trunk before transitioning.** We work trunk-based and many sessions edit `roadmap/` concurrently, so fast-forward trunk into your branch *before* touching the item's `status:`. This makes the transition start from the roadmap's true current state: you don't clobber another session's edit to the same file, and the reviewer-rule guard below is evaluated against the latest history (a stale `git log -- <slug>.md` produces stale attribution and can miss a sign-off or reopen another session already landed). Run:

```bash
git fetch origin claude/graphitron-rewrite
git rebase origin/claude/graphitron-rewrite
```

If the rebase reports conflicts, surface and stop; the user resolves before the transition is meaningful. Working-copy dirt is the user's call (don't auto-stash).

Then use the tool's `status` subcommand. It resolves the slug or ID, validates the transition against the state table below, atomically writes the new `status:` value plus a fresh `last-updated: <today>` (leaving `created:` strictly untouched, never invented), and regenerates the README. Then run the reviewer-rule guards.

```bash
mvn -pl roadmap-tool exec:java -q \
  -Dexec.args='status roadmap <R<n>-or-slug> <new-state>'
```

Accepted target states: `Backlog`, `Spec`, `Ready`, `In Progress`, `In Review`. `Done` and `Discarded` are file-deletion transitions per `roadmap/workflow.adoc` and remain manual; the subcommand rejects them.

Valid transitions (see `roadmap/workflow.adoc`):

| From          | To           | Guard                                                       |
|---------------|--------------|-------------------------------------------------------------|
| Backlog       | Spec         | none — author picks up an item and starts a plan body       |
| Spec          | Spec         | review; findings appended; reviewer ≠ last committer            |
| Spec          | Spec         | revise; author addresses the findings; no guard                 |
| Spec          | Ready        | sign-off; reviewer ≠ last committer                         |
| Ready         | Spec         | reopen for re-review (spec needs substantive redesign)      |
| Ready         | In Progress  | none                                                        |
| In Progress   | In Review    | none                                                        |
| In Review     | Ready        | rework; reviewer ≠ implementer                              |
| In Review     | Done         | approve; reviewer ≠ implementer; **delete the item file**   |
| Done          | Spec         | none — the file is restored, not transitioned; see `revive` |

For guarded transitions, run `git log -1 --pretty='%an <%ae>' roadmap/<slug>.md` and tell the user who last touched the file. If the current Claude session would be the same party, surface that and stop — a different party (typically the human user, or an independent agent session) must perform the flip. The reviewer-rule guard is the skill's responsibility; the tool performs the mechanical edit unconditionally once invoked.

For `In Review → Done`, delete the file rather than editing it; if the milestone is worth preserving, append a one-line entry to `roadmap/changelog.md` capturing the landing commit SHA and the `R<n>` ID.

The `status` subcommand regenerates the README as part of its run; no separate regenerate step needed.

### revive `<R<n>>`

For an item whose Done verdict turned out to be wrong. It comes back as itself: same id, same plan body, same reviewer rounds. **Read the discriminator first** (`roadmap/workflow.adoc` § Revive): revive when the item's own `## Goal` still states an outcome that is not delivered; file a successor (the `add` intent above) when the goal was met and a different goal has appeared. A change that breaks a consumer's development process is undelivered, whatever the Done round said.

Sync trunk first, exactly as `status` above does and for the same reasons. Then restore the body out of git history and hand the restored file to the tool:

```bash
id=R<n>
sha=$(git log -1 -G"^id: $id\$" --diff-filter=D --format=%H \
      -- roadmap ':(exclude)roadmap/README.md' ':(exclude)roadmap/changelog.md')
path=$(git show --format= --name-only --diff-filter=D "$sha" -- roadmap \
      | grep -vE 'README|changelog')
git cat-file -e "$sha^:$path"          # stop and surface if this fails
git restore --source="$sha^" -- "$path"
# author ## Revived directly under ## Goal; collapse shipped plan sections; dispose of successors
mvn -pl roadmap-tool exec:java -q -Dexec.args="revive roadmap $id --retracted $sha"
```

The sharp edges, each next to the line that handles it:

- The pathspec **excludes `README.md` and `changelog.md`** because both carry the id, and a `-G` over them finds the wrong commit.
- `-1` picks the **most recent** deletion. A slug can be deleted more than once and the latest body is the wanted one.
- `$sha^` is the **first parent**, which carries the file: a pathspec-restricted `git log` without `-m` never attributes a deletion to a merge commit. The `cat-file -e` line turns that residual assumption into a stop rather than a silent restore of nothing.
- The restored file reads `status: In Review`, which is a **precondition of the command, not a state to commit**. Don't hand-edit it; the tool moves it to `Spec`.

What the tool does: checks the preconditions (status, a non-empty `## Revived` section, a well-shaped SHA), retracts every `changelog.md` bullet the id heads, writes `status: Spec` plus `revived-from: <sha>` and a fresh `last-updated:`, and regenerates the README. What it does **not** do, and you own: authoring the `## Revived` section, collapsing the shipped plan sections into "shipped at `<sha>`" notes, disposing of any successors already filed (`Discarded` for total supersession, Backlog tombstone for a live redirect, `depends-on:` for one with a goal of its own), and the revive commit itself.

The `Spec → Ready` guard then applies as usual, and it bites here: you are the last committer of the plan, so a different session signs off the revive.

### regenerate

```bash
mvn -pl roadmap-tool exec:java -q
```

Run after every front-matter edit and every delete. (`add` regenerates as part of `create`.) CI verifies the README stays in sync; never edit `roadmap/README.md` by hand.

## Hard rules

- Front-matter is the source of truth. README is derived. If they disagree, regenerate; do not hand-edit the README.
- IDs are monotonic and never reused. Don't guess one — call the tool. The validator will reject malformed or duplicate IDs at build time.
- Slug = work, not phase. No `plan-` prefix. Lowercase kebab-case.
- The reviewer-rule guard cannot be bypassed because "the user said so" — if the same session both authored and is approving, that's the rule itself failing. Surface it, stop, ask the user to bring in a different party.
- Fast-forward trunk before *and* after every transition. Before: sync trunk into your branch (the "Sync trunk before transitioning" step above) so you transition from the true current state. After: run the publish skill on the roadmap-changing commit — push your branch, then fast-forward trunk — so the new state is visible before anyone acts on it. See `roadmap/workflow.adoc` § Publishing.
