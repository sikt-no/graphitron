---
name: roadmap
description: Manage graphitron-rewrite roadmap items — add new Backlog entries, transition status (Backlog→Spec→Ready→In Progress→In Review→Done), and regenerate the rolled-up README. Use when the user asks to "add a roadmap item", "move X to Ready", "flip Y to In Review", "mark Z done", or any phrase about roadmap state. Encodes the workflow.md state machine and the reviewer-rule guard.
---

# Roadmap

Operates on `graphitron-rewrite/roadmap/<slug>.md` files and the rolled-up `README.md`. Source of truth is the per-item YAML front-matter; the README is generated.

## Subcommands

The skill recognises three intents from the user's request. Pick one and execute.

### add `<slug>` (optional `<title>`, `<bucket>`, `<priority>`)

Drop a new Backlog item under `graphitron-rewrite/roadmap/<slug>.md`. Slug describes the work, not the phase — `variant-coverage-meta-test`, not `phase-2`. No `plan-` prefix.

Template:

```markdown
---
title: "<Human-readable title>"
status: Backlog
bucket: <architecture | stubs | cleanup>
priority: <integer, lower first>
---

# <Title>

<One-paragraph problem statement: what is broken/missing and why it matters.>

## Notes

<Optional context, links, prior art. Plan body is filled in when the item moves to Spec.>
```

Then regenerate the README (see "Regenerate" below).

### status `<slug>` `<new-state>`

Flip `status:` in the front-matter and run the guards. Valid transitions (see `graphitron-rewrite/docs/workflow.md`):

| From          | To           | Guard                                                       |
|---------------|--------------|-------------------------------------------------------------|
| Backlog       | Spec         | none — author picks up an item and starts a plan body       |
| Spec          | Spec         | revise; reviewer ≠ last committer of the file               |
| Spec          | Ready        | sign-off; reviewer ≠ last committer                         |
| Ready         | In Progress  | none                                                        |
| In Progress   | In Review    | none                                                        |
| In Review     | Ready        | rework; reviewer ≠ implementer                              |
| In Review     | Done         | approve; reviewer ≠ implementer; **delete the item file**   |

For guarded transitions, run `git log -1 --pretty='%an <%ae>' graphitron-rewrite/roadmap/<slug>.md` and tell the user who last touched the file. If the current Claude session would be the same party, surface that and stop — a different party (typically the human user, or an independent agent session) must perform the flip.

For `In Review → Done`, delete the file rather than editing it; if the milestone is worth preserving, append a one-line entry to `graphitron-rewrite/roadmap/changelog.md` capturing the landing commit SHA.

After any successful change, regenerate.

### regenerate

```
mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -q
```

Run after every front-matter edit, every add, every delete. CI verifies the README stays in sync; never edit `graphitron-rewrite/roadmap/README.md` by hand.

## Hard rules

- Front-matter is the source of truth. README is derived. If they disagree, regenerate; do not hand-edit the README.
- Slug = work, not phase. No `plan-` prefix.
- The reviewer-rule guard cannot be bypassed because "the user said so" — if the same session both authored and is approving, that's the rule itself failing. Surface it, stop, ask the user to bring in a different party.
- After any roadmap-changing commit, the publish skill still applies: push your branch, then fast-forward trunk.
