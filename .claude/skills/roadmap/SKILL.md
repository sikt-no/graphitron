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

The skill recognises three intents from the user's request. Pick one and execute.

### add `<slug>` `--title "<title>"` `[--bucket <b>]` `[--priority <n>]` `[--theme <t>]`

Use the tool's `create` subcommand — it picks the next free `R<n>`, writes the file with the ID baked in, and refreshes the README atomically. Never construct the file by hand: the validator now requires `id:` and would fail the build, and a hand-rolled file races other sessions on number allocation.

```bash
mvn -pl roadmap-tool exec:java -q \
  -Dexec.args='create roadmap <slug> --title "<title>" \
               --bucket <bucket> --priority <n> --theme <theme>'
```

Slug rules (enforced by the tool): lowercase kebab-case, describes the work and not the phase (`variant-coverage-meta-test`, not `phase-2`), no `plan-` prefix.

The created file has a single-paragraph TODO body. Edit it inline to add the real problem statement before the user reviews.

If the user only wants to know the next free number without writing a file yet:

```bash
mvn -pl roadmap-tool exec:java -q \
  -Dexec.args='next-id roadmap'
```

### status `<R<n>-or-slug>` `<new-state>`

Use the tool's `status` subcommand. It resolves the slug or ID, validates the transition against the state table below, atomically writes the new `status:` value plus a fresh `last-updated: <today>` (leaving `created:` strictly untouched, never invented), and regenerates the README. Then run the reviewer-rule guards.

```bash
mvn -pl roadmap-tool exec:java -q \
  -Dexec.args='status roadmap <R<n>-or-slug> <new-state>'
```

Accepted target states: `Backlog`, `Spec`, `Ready`, `In Progress`, `In Review`. `Done` and `Discarded` are file-deletion transitions per `roadmap/workflow.adoc` and remain manual; the subcommand rejects them.

Valid transitions (see `roadmap/workflow.adoc`):

| From          | To           | Guard                                                       |
|---------------|--------------|-------------------------------------------------------------|
| Backlog       | Spec         | none — author picks up an item and starts a plan body       |
| Spec          | Spec         | revise; reviewer ≠ last committer of the file               |
| Spec          | Ready        | sign-off; reviewer ≠ last committer                         |
| Ready         | Spec         | reopen for re-review (spec needs substantive redesign)      |
| Ready         | In Progress  | none                                                        |
| In Progress   | In Review    | none                                                        |
| In Review     | Ready        | rework; reviewer ≠ implementer                              |
| In Review     | Done         | approve; reviewer ≠ implementer; **delete the item file**   |

For guarded transitions, run `git log -1 --pretty='%an <%ae>' roadmap/<slug>.md` and tell the user who last touched the file. If the current Claude session would be the same party, surface that and stop — a different party (typically the human user, or an independent agent session) must perform the flip. The reviewer-rule guard is the skill's responsibility; the tool performs the mechanical edit unconditionally once invoked.

For `In Review → Done`, delete the file rather than editing it; if the milestone is worth preserving, append a one-line entry to `roadmap/changelog.md` capturing the landing commit SHA and the `R<n>` ID.

The `status` subcommand regenerates the README as part of its run; no separate regenerate step needed.

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
- After any roadmap-changing commit, the publish skill still applies: push your branch, then fast-forward trunk.
