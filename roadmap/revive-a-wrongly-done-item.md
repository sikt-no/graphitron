---
id: R940
title: "An item whose Done verdict was wrong comes back as itself, rather than as a successor that renumbers the work"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-09-09
last-updated: 2026-09-09
---

# An item whose Done verdict was wrong comes back as itself, rather than as a successor that renumbers the work

## Goal

Shipped work that turns out not to have been delivered comes back as the item that shipped it,
carrying its id, its plan body and its reviewer rounds, instead of being renumbered into a
successor that starts from an empty page. Today `Done` is terminal: the item file is deleted, so
there is nothing to transition, `roadmap-tool status` cannot even resolve the id, and the only
move available is to file a fresh item. That is the right answer when the goal was met and a
different goal has appeared. It is the wrong answer when the goal was not met, which is what a
shipped change that breaks a consumer's development process amounts to: the Done gate passed
something undelivered, and the correction is to reopen that verdict, not to renumber the work
behind a new id whose body has to restate a goal that already exists and whose reviewer rounds
start empty.

## What the history already shows

The move exists in practice and is documented nowhere. R571 was taken to Done, and then commit
`bf3d987` restored its 187-line body, removed its `changelog.md` entry because the work had not in
fact shipped, and deleted the two successor items that had been filed to cover the gaps, folding
them back into the revived plan. Three moves, none of them described in `roadmap/workflow.adoc`,
so the next session either re-derives them or takes the successor path by default. R939 took the
successor path against R926 and wrote down why under `## Other solutions we've considered`:
"Reopening R926, which shipped the verdict. Not available, and worth recording so the next person
does not spend the same twenty minutes on it." Twenty minutes is the measured cost of the gap.

Deleting the file at Done is not an obstacle to this. The Item file conventions entry that removes
the file gives its reason as "Git history preserves it; leaving a tombstone file encourages
staleness", so git history is already named as the archive for a deleted plan. Reading a body back
out of it uses that design rather than working around it, and the alternative that would look
tidier, a tombstone file or a persisted `status: Done`, is the thing the convention already
rejects.

Nor does reviving break the never-reuse rule. That rule keeps a number from naming two different
pieces of work; a revived item is the same work under the same number, and the `changelog.md`
entry that would otherwise contradict the board is retracted rather than left standing, because it
asserted a landing that did not hold.

The state machine needs less than it appears to. `Done` is reachable only from `In Review`, so a
restored file always reads `status: In Review`, and `In Review -> Ready` is already in the tool's
transition map. `roadmap-tool status <id> Ready` therefore works on a restored file today, which is
exactly the transition R571's revival commit records. What is missing is the documented edge, a
recipe, and one body rule.

## What the change covers

- A revive entry in `workflow.adoc`, beside `Discarded` and `Backlog tombstone`, naming the
  discriminator: whether the item's own `## Goal` still states an outcome that is not delivered. A
  change that breaks a consumer's development process is undelivered whatever the gate said, and
  the item comes back. A change that met its goal and revealed a different one is a successor.
- The changelog retraction, stated as an invariant: an id is either in `changelog.md` or on the
  board, never both. The second Done writes the entry again.
- A required `## Revived` section on the restored body, naming the landing commit, what the Done
  gate missed, and what remains. Without it the body presents landed work as forthcoming and the
  next implementer rebuilds it. This is the one new convention the item introduces and the only
  thing that makes a revived file honest about its own state.
- The git recipe in the `roadmap` skill: resolve id to slug and deletion commit, restore the body,
  transition. Three sharp edges belong in it. An id search has to exclude `README.md` and
  `changelog.md` from the pathspec, since both carry the id too. A slug can be deleted more than
  once, so the most recent deletion is the wanted one; R571 was deleted twice. And `<sha>^` assumes
  the deletion landed on a single-parent commit.

## Open questions for Spec

- Whether `In Review -> Spec` joins the transition table, for a revive whose plan is what missed
  the cost rather than its implementation. Today that hops through `Ready`, which is legal
  (`Ready -> Spec` is unguarded) but undocumented as a pair.
- Whether `roadmap-tool` grows a `revive` subcommand at all. The module has never run a subprocess
  and has no git dependency; every subcommand is pure filesystem over `roadmap/`. A middle option
  is a subcommand that takes an already-restored file and enforces the `## Revived` section and the
  changelog retraction, keeping git in the skill where `git log` and `git rebase` already live.
- Whether the session that granted the wrong Done verdict is disqualified from granting the next
  one, and against which artifact the reviewer rule is evaluated on a revived item.
- What happens to successors already filed against the revived work. R571 absorbed and deleted two
  of them; whether that is the default or a case-by-case call is worth settling once.

## Provenance

Prompted by R939, filed against R926 as a successor after the reopening it wanted was found not to
exist. Whether R926 is revived and R939 folded into it is that item's call rather than this one's,
but it is the live instance and the obvious first test of the convention.
