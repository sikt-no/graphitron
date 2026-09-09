---
id: R940
title: "An item whose Done verdict was wrong comes back as itself, rather than as a successor that renumbers the work"
status: Spec
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

When this lands, a session that finds shipped work undelivered has a documented move named
*revive*, a recipe in the `roadmap` skill that performs it in one commit, a tool subcommand that
enforces the two things a revived file owes (a `## Revived` section and the retraction of its
`changelog.md` entry), and a build gate that fails when an id is on the board and in the changelog
at once. The move has been made before without any of this: R571 was taken to Done, and commit
`bf3d987` restored its 187-line body, removed its changelog entry because the work had not shipped,
and folded two successor items back into the revived plan. R939 then took the successor path
against R926 and recorded twenty minutes spent establishing that no reopening existed. Those
twenty minutes, spent again by every session that meets the case, are the cost this item removes.

## The revive convention

Terms glossed once. The *board* is the set of item files under `roadmap/`, rendered into
`README.md`. The *Done gate* is the `In Review -> Done` review, whose approving commit deletes the
item file and, when the milestone is worth keeping, writes an entry to `changelog.md`. A
*successor* is a fresh item filed to cover a gap found in shipped work.

**Discriminator.** Revive when the item's own `## Goal` still states an outcome that is not
delivered. File a successor when the goal was met and a different goal has appeared. Two cases
decide the same way whatever the Done round said: a change that breaks a consumer's development
process is undelivered, and a change whose named acceptance evidence turns out not to demonstrate
the goal is undelivered. Reading the goal paragraph is the whole test; it was written to be judged
on its own, and a revive is a second judgment of it against the tree as shipped.

**Edge.** `Done -> In Review` is the one new transition. The restored file always reads
`status: In Review`, because `Done` is reachable only from there, and the item then moves through
`In Review -> Ready` (already in the tool's map) in the same commit. A revive whose plan is what
missed the cost, rather than its implementation, continues `Ready -> Spec`, which is unguarded
today; the revive entry documents the pair rather than adding an `In Review -> Spec` edge. Revive
itself is unguarded, like `Ready -> Spec`: it withdraws a verdict rather than granting one, so the
implementing session may revive its own shipped work. What is guarded is the re-grant, below.

**Archive.** The restored body is read out of git. The Item file conventions entry that deletes the
file at Done names git history as the archive and rejects the tombstone; a revive uses that design
rather than working around it, and neither a tombstone file nor a persisted `status: Done` enters
the tree. The never-reuse rule is untouched: it keeps a number from naming two pieces of work, and
a revived item is the same work under the same number.

**Changelog invariant.** An id is either in `changelog.md` as a Done entry or on the board, never
both. The revive retracts the entry, because it asserted a landing that did not hold; the second
Done writes it again. `next-id:` in the changelog front-matter is untouched, and ids allocated by
the retracted verdict's commit (successors it filed) stay burned as gaps, as R571's revival left
R575 and R576. The retraction removes exactly one entry: the `- R<n>` bullet line and every
indented continuation paragraph beneath it, up to the next top-level bullet. Entries are
multi-paragraph in places (R916's runs three paragraphs), so the span, not the line, is the unit.

**`## Revived` section.** The one new body convention. A restored body without it presents landed
work as forthcoming, and the next implementer rebuilds it. The section sits directly after
`## Goal`, since it reframes every plan section after it, and states four facts: the landing
commits and the retracted Done commit by SHA; what the gate missed, stated as the undelivered part
of the goal; what remains, which is the rework scope; and any successors folded, by id. The plan
sections below it collapse their shipped parts into "shipped at `<sha>`" notes, which is the
existing multi-phase convention, so the body once again carries only the current plan and the
facts it rests on. The section is plan, not argument: it does not litigate the Done round, whose
text stays in the restored `## Reviewer findings` where the next reviewer can read it.

**Reviewer rule on a revived item.** The second Done gate is taken by a session that is neither an
implementing session (the original landing commits or the rework) nor the session that granted the
retracted verdict. The retracted commit's SHA in `## Revived` is how the `srp` skill resolves that
third disqualified party; the rule's reason, fresh context, applies with more force to a session
that has already rationalised the gap than to one that wrote the code.

**Successors already filed.** A successor whose goal is a part of the revived goal is folded: its
still-useful content moves into the revived plan, its file is deleted in the revive commit, and
`## Revived` names it. The revive commit message records the fold while the item lives, and the
second Done entry names the folded ids so the record outlives the file; no `Discarded` changelog
entry is written, matching what R571's revival did with R575 and R576. A successor with a goal of
its own stays on the board and gains a `depends-on:` edge if it needs the revived work. R939
against R926 is the live case: whether R926 is revived and R939 folded into it is decided by the
discriminator above, in that item's own commit, not here.

## Implementation

- `roadmap/workflow.adoc`. The state diagram gains `Done --> InReview : revive; restore file,
  retract changelog entry`. A `*Revive:*` paragraph lands beside `*Discarded:*` carrying the
  discriminator, the edge and its continuation pair, the archive statement, the changelog
  invariant, the successor rule, and the reviewer rule for the second gate; the `*Reviewer rule:*`
  paragraph gets one sentence pointing at it. Under Item file conventions, a bullet for
  `## Revived` (position and the four facts) joins the body-shape rules, the `## Goal` skeleton
  shows it as an optional line after `## Goal`, the deletion-at-Done bullet gains "and is the
  archive a revive restores from", and the never-reuse bullet gains the same-work sentence.
- `roadmap-tool` `Main.java`. A `revive <roadmap-dir> <R<n>-or-slug>` subcommand, dispatched
  beside `status`. It works on a file already restored to disk, so the module stays free of git and
  subprocesses. Preconditions, each a named error on failure: the file resolves through
  `resolveItemFile`; its `status:` is `In Review`; a `## Revived` heading is present with a
  non-empty body that names at least one commit SHA. Then it retracts the id's Done entry from
  `changelog.md` (the span rule above; a missing entry is a no-op the tool reports, since routine
  completions never wrote one), applies `In Review -> Ready` through `applyStatusTransition` so the
  `last-updated:` stamp is the same code path every transition uses, and regenerates the README
  through `runGenerate`, which validates.
- `roadmap-tool` `Main.validate`. Gains the changelog invariant. `validate` currently sees items
  only; `runGenerate` and `runVerify` pass it the set of ids that head a `- R<n>` bullet in
  `changelog.md` (the same file `readChangelogCounter` already reads), and an on-board id in that
  set is an error naming both places and both remedies: a revived item retracts its entry with
  `roadmap-tool revive`, a shipped one has no file. The invariant holds on today's tree; the
  16 entries whose bullet reads `- R<n> ` with no parenthesis are matched by the same word-boundary
  regex as the 438 that read `- R<n> (`. `Discarded:` entries are out of scope: they name the
  discarded id in prose, and un-discarding is not this item.
- `.claude/skills/roadmap/SKILL.md`. The state table gains the `Done -> In Review` row, guard
  "none; the file is restored, not transitioned". A `### revive <R<n>>` subcommand section carries
  the recipe, sync-trunk-first like `status`:
  ```bash
  id=R<n>
  sha=$(git log -1 -G"^id: $id\$" --diff-filter=D --format=%H \
        -- roadmap ':(exclude)roadmap/README.md' ':(exclude)roadmap/changelog.md')
  path=$(git show --format= --name-only --diff-filter=D "$sha" -- roadmap \
        | grep -vE 'README|changelog')
  git cat-file -e "$sha^:$path"          # stop and surface if this fails
  git restore --source="$sha^" -- "$path"
  # author ## Revived directly under ## Goal; collapse shipped plan sections; fold successors
  mvn -pl roadmap-tool exec:java -q -Dexec.args="revive roadmap $id"
  ```
  The three sharp edges are stated next to the lines that handle them. The pathspec excludes
  `README.md` and `changelog.md` because both carry the id and a `-G` over them finds the wrong
  commit. `-1` picks the most recent deletion, because a slug can be deleted more than once and
  the latest body is the wanted one: R571 was deleted at `2178df3` and again at `11d5daf`, and the
  recipe run against R571 resolves `11d5daf`. And `$sha^` is the first parent, which carries the
  file because a pathspec-restricted `git log` without `-m` never attributes a deletion to a merge
  commit; the `cat-file -e` line turns the residual assumption into a stop rather than a silent
  restore of nothing. The section ends by naming what the recipe does not do: the revive commit
  itself, the `Ready -> Spec` continuation, and the successor fold are the session's.
- `.claude/skills/srp/SKILL.md`. Two edits. The "No matches" branch of the id lookup stops at
  "the item shipped; tell the user" today; it gains "if the goal is undelivered, the `roadmap`
  skill's `revive` is the move". The implementation-stage template's disqualified-party list gains
  the retracted verdict's session, resolved from the SHA in `## Revived`, for items carrying that
  section.

## Tests

- `ReviveTest` in `roadmap-tool`, over a temp roadmap directory. A restored file at `In Review`
  with a conforming `## Revived` section ends at `Ready` with a fresh `last-updated:` and an
  untouched `created:`; its single-line changelog entry is gone; a three-paragraph entry with
  indented continuation lines (R916's shape) is gone whole, the neighbours on either side are
  byte-identical, and exactly one blank line separates them; an id with no changelog entry
  revives with the reported no-op; `status: Ready` on entry fails naming the state; a body without
  `## Revived` fails naming the section; a `## Revived` body with no SHA fails naming the
  requirement.
- `validate` coverage beside `NextIdAllocationTest`: an on-board id that also heads a changelog
  bullet fails `verify` with the two-remedy message, for both the `- R<n> (` and the bare
  `- R<n> ` bullet shapes; an id that appears only in prose inside another entry does not.
- The recipe is run by the implementer against R571 and R926 into a scratch checkout, the resolved
  SHAs and paths recorded in the implementing commit message. This is the check on the git half
  that no unit test in a git-free module can carry, and it is what makes "R571 was deleted twice"
  a verified premise of the skill text rather than a remembered one.
- `mvn verify -pl roadmap-tool,docs` on the tree: the workflow page renders with the new edge and
  the `check-adoc-xrefs` and README-sync gates pass.

## Other solutions we've considered

**The successor path as the only path.** What R939 did, and what the workflow documented by
omission. Rejected because it renumbers work whose goal already has a number, forces the new body
to restate a goal that exists, and starts the reviewer rounds empty; the gap it leaves is also the
one that cost twenty minutes to rediscover. It remains the right move when the goal was met, which
is why the discriminator, not the mechanism, is the load-bearing part of this item.

**A tombstone file or a persisted `status: Done`.** Would give `roadmap-tool status` something to
resolve. Rejected because the deletion-at-Done convention already names and rejects exactly this
("leaving a tombstone file encourages staleness"), and because git history already is the
archive; reading from it is the design, not a workaround.

**An `In Review -> Spec` edge.** For a revive whose plan is what missed the cost. Rejected in
favour of documenting the `In Review -> Ready -> Spec` pair: both hops exist and are unguarded,
R506's statechart driver has one edge fewer to fold, and a revived item at `Ready` is the honest
intermediate state whether or not the plan is then reopened.

**A git-aware `revive` that restores the file itself.** One command instead of a recipe plus a
command. Rejected because the module has never run a subprocess and has no git dependency; every
subcommand is pure filesystem over `roadmap/`, and `git log`, `git rebase` and the session-trailer
lookups already live in the skills. The split puts each half where its kind of logic already is.

**No tool change at all, recipe only.** Cheapest. Rejected because the two things a revived file
owes are the two a hurried session skips: the changelog span retraction is fiddly by hand on
multi-paragraph entries, and a missing `## Revived` is invisible until the next implementer
rebuilds shipped work. The validator makes the first a build failure and `revive` makes the second
a refused command.

## Provenance

Prompted by R939, filed against R926 as a successor after the reopening it wanted was found not to
exist, and by R571's revival commit `bf3d987`, which made all three moves this item documents
without a line of workflow text to lean on. Whether R926 is revived and R939 folded into it is
that item's call rather than this one's, but it is the live instance and the obvious first test of
the convention.
