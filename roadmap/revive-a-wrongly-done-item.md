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

A roadmap item whose Done verdict turns out to have been wrong comes back onto the board as itself:
same id, same plan body, same reviewer rounds, with the work still to do stated on top of the work
that landed. Today the only move is a *successor*, a fresh item filed to cover the gap, which
renumbers work whose goal already has a number, restates a goal that already exists, and starts the
reviewer rounds empty. The successor stays the right move when the goal was met and a different
goal appeared. It is the wrong move when the goal was not met, which is what a shipped change that
breaks a consumer's development process amounts to.

When this lands there is a documented move named *revive*, a recipe in the `roadmap` skill that
performs it in one commit, a `roadmap-tool revive` subcommand that enforces what a revived file
owes, and two build gates: an id is either a Done entry in `changelog.md` or on the board, never
both, and a revived item carries the section that says what landed and what remains. Both halves
of the move already have precedent without a line of workflow text behind them. R571 was taken to
Done and commit `bf3d987` restored its 187-line body, removed its changelog entry because the work
had not shipped, and deleted two successors filed to cover the gaps. R939 then took the successor
path against R926 and recorded twenty minutes spent establishing that no reopening existed. Those
twenty minutes, spent again by every session that meets the case, are the cost this item removes.

## The revive convention

Terms glossed once. The *board* is the set of item files under `roadmap/`, rendered into
`README.md`. The *Done gate* is the `In Review -> Done` review, whose approving commit deletes the
item file and, when the milestone is worth keeping, writes an entry to `changelog.md`. The
*reviewer rule* requires the session granting a gate verdict to differ from the session that last
changed the artifact; sessions are identified by the `session_<id>` trailer on their commits.

**Discriminator.** Revive when the item's own `## Goal` still states an outcome that is not
delivered. File a successor when the goal was met and a different goal has appeared. Two cases
decide the same way whatever the Done round said: a change that breaks a consumer's development
process is undelivered, and a change whose named acceptance evidence turns out not to demonstrate
the goal is undelivered. Reading the goal paragraph is the whole test; it was written to be judged
on its own, and a revive is a second judgment of it against the tree as shipped.

**Edge.** `Done -> Spec` is the one new transition, unguarded like `Ready -> Spec`: it withdraws a
verdict rather than granting one, so the implementing session may revive its own shipped work. The
revive lands at `Spec`, not `Ready`, because a retracted Done verdict is evidence about the plan as
well as the implementation: the acceptance evidence its `## Tests` named was not enough to show the
goal delivered, and the `## Revived` section is new plan prose the original `Spec -> Ready`
reviewer never saw. The existing `Spec -> Ready` guard then supplies the independent judgment on
both the discriminator (was this a revive or a successor?) and the rework scope before
implementation resumes. Where the plan needs no change that costs one sign-off commit, the
cheapest form of the fresh judgment the retracted verdict shows was missing. That the restored file
reads `status: In Review` (Done is reachable only from there) is a precondition of the `revive`
command, not a state the workflow passes through: no commit ever shows a revived item at
`In Review`, and the tool's transition map, keyed on a file's current `status:`, cannot hold an
edge from `Done` because `Done` has no file.

**Archive.** The restored body is read out of git. The Item file conventions entry that deletes the
file at Done names git history as the archive and rejects the tombstone; a revive uses that design
rather than working around it, and neither a tombstone file nor a persisted `status: Done` enters
the tree. The never-reuse rule is untouched: it keeps a number from naming two pieces of work, and
a revived item is the same work under the same number.

**Changelog invariant.** An id is either a Done entry in `changelog.md` or on the board, never
both. The revive retracts the entries, because they asserted a landing that did not hold; the
second Done writes an entry again. `next-id:` in the changelog front-matter is untouched, and ids
allocated by the retracted verdict's commit (successors it filed) stay burned as gaps, as R571's
revival left R575 and R576. Retraction is total over the id: every top-level bullet the id heads
goes, each as a span from its `- R<n>` line through the indented continuation paragraphs beneath it
to the next top-level bullet. Both halves of that rule come from the tree. Entries are
multi-paragraph in places (R916's runs three), so the span, not the line, is the unit; and an id
can head several bullets (R43 heads seven, R68 and R563 six each), so a retraction that removed
one span would leave the very invariant the tool's own regenerate step then checks failing, with a
message telling the session to run the command that just ran.

**Front-matter.** A revived item carries two keys the tool writes and never a hand invents:
`revived-from: <sha>`, the retracted Done commit, and `revived-verdict-session: session_<id>`, the
session that granted it, read off that commit's trailer. They are the machine-read facts of the
revive. The `srp` skill reads the session slot directly when it builds the disqualified set for the
second Done gate, rather than re-deriving it from `git log`, whose current resolution takes the
first `session_` token in a commit body and, on a Done-gate commit that names the implementer's
session in prose ahead of its own trailer, returns the implementer (R780 owns that defect; this
item does not depend on it because the slot sidesteps it). The keys also give the build something
to check: an item carrying `revived-from:` without a `## Revived` section fails `validate` on every
build, not only at the moment the command ran.

**`## Revived` section.** The one new body convention. A restored body without it presents landed
work as forthcoming, and the next implementer rebuilds it. The section sits directly after
`## Goal`, since it reframes every plan section after it, and states the human-read facts: what
the gate missed, as the undelivered part of the goal; what remains, which is the rework scope; and
the disposition of any successors already filed, by id. The landing commits belong here too, as
the "shipped at `<sha>`" notes the plan sections below collapse their delivered parts into, which
is the existing multi-phase convention, so the body once again carries only the current plan and
the facts it rests on. The section is plan, not argument: it does not litigate the Done round,
whose text stays in the restored `## Reviewer findings` where the next reviewer can read it.

**Reviewer rule on a revived item.** The reviving session is the last committer of the plan, so
the `Spec -> Ready` guard already excludes it from signing off its own revive. The second Done gate
is taken by a session that is neither an implementing session (the original landing commits or the
rework) nor the one named by `revived-verdict-session:`; the rule's reason, fresh context, applies
with more force to a session that has already rationalised the gap than to one that wrote the code.

**Successors already filed.** The workflow has two disposals for a plan whose work moves into
another item, and a revive uses them rather than a third. A successor the revived plan supersedes
wholesale is `Discarded` in the revive commit, with the `Discarded:` changelog entry current
practice writes naming the revived id as the absorber, so the record sits on a permanent surface
whatever the revived item's own fate. A successor that still serves as a redirect while the revived
item is in flight is a *Backlog tombstone*, deleting when the revived item reaches Done. The
criterion is the one the `Discarded` entry already states: total supersession prefers `Discarded`.
A successor with a goal of its own stays on the board and gains a `depends-on:` edge if it needs
the revived work. R939 against R926 is the live case: whether R926 is revived and R939 discarded
into it is decided by the discriminator above, in that item's own commit, not here.

## Implementation

- `roadmap/workflow.adoc`. The state diagram gains `Done --> Spec : revive; restore file, retract
  changelog entries`, one edge that agrees with the commit sequence and with what the tool's map
  can hold. A `*Revive:*` paragraph lands beside `*Discarded:*` carrying the discriminator, the
  edge and why it lands at `Spec`, the archive statement, the changelog invariant, the successor
  disposals, and the reviewer rule for the second gate; the `*Reviewer rule:*` paragraph gets one
  sentence pointing at it. Under Item file conventions, the front-matter example gains the two
  optional `revived-*` keys with a comment, a bullet for `## Revived` (position and content) joins
  the body-shape rules, the skeleton shows it as an optional line after `## Goal`, the
  deletion-at-Done bullet gains "and is the archive a revive restores from", and the never-reuse
  bullet gains the same-work sentence.
- `roadmap-tool` `Main.java`. A `revive <roadmap-dir> <R<n>-or-slug> --retracted <sha>
  --verdict-session <session_id>` subcommand, dispatched beside `status`. It works on a file
  already restored to disk, so the module stays free of git and subprocesses. Preconditions, each a
  named error on failure: the file resolves through `resolveItemFile`; its `status:` is
  `In Review`, the error otherwise naming the state found and that Done is reachable only from
  `In Review`; a `## Revived` heading is present with a non-empty body; both options are given and
  shaped (7 to 40 hex, `session_` prefix). Then it retracts every Done entry the id heads in
  `changelog.md` under the span rule and asserts before writing that the id heads no bullet
  afterwards (a file with no entry is a no-op the tool reports, since routine completions never
  wrote one); writes `status: Spec`, `last-updated:` and the two `revived-*` keys in one
  `patchFrontMatter` call, with the stamp logic factored out of `applyStatusTransition` so the two
  paths cannot drift; and regenerates the README through `runGenerate`, which validates.
- `roadmap-tool` `Main.validate`. Takes the board as a pair, items plus the set of ids heading a
  `- R<n>` bullet in `changelog.md`, read once beside `readChangelogNextId`, and all four callers
  (`runGenerate`, `runVerify`, `runCreate`, `runRenderAdoc`) pass it, so no entry point can omit
  the check. Two new errors. An on-board id in the changelog set names both places and both
  remedies: a revived item retracts its entries with `roadmap-tool revive`, a shipped one has no
  file. An item with `revived-from:` and no `## Revived` heading, or with one `revived-*` key and
  not the other, names the missing piece. The changelog invariant holds on today's tree; the
  word-boundary regex that matches the 438 bullets reading `- R<n> (` also matches the 18 reading
  `- R<n> ` bare (re-measure at pickup). `Discarded:` entries are out of scope: they name the
  discarded id in prose, and un-discarding is not this item.
- `.claude/skills/roadmap/SKILL.md`. The state table gains the `Done -> Spec` row, guard "none;
  the file is restored, not transitioned". A `### revive <R<n>>` subcommand section carries the
  recipe, sync-trunk-first like `status`:
  ```bash
  id=R<n>
  sha=$(git log -1 -G"^id: $id\$" --diff-filter=D --format=%H \
        -- roadmap ':(exclude)roadmap/README.md' ':(exclude)roadmap/changelog.md')
  path=$(git show --format= --name-only --diff-filter=D "$sha" -- roadmap \
        | grep -vE 'README|changelog')
  session=$(git log -1 --format=%B "$sha" | grep -oE 'session_[A-Za-z0-9]+' | tail -1)
  git cat-file -e "$sha^:$path"          # stop and surface if this fails
  git restore --source="$sha^" -- "$path"
  # author ## Revived directly under ## Goal; collapse shipped plan sections; dispose of successors
  mvn -pl roadmap-tool exec:java -q \
    -Dexec.args="revive roadmap $id --retracted $sha --verdict-session $session"
  ```
  The sharp edges are stated next to the lines that handle them. The pathspec excludes `README.md`
  and `changelog.md` because both carry the id and a `-G` over them finds the wrong commit. `-1`
  picks the most recent deletion, because a slug can be deleted more than once and the latest body
  is the wanted one: R571 was deleted at `2178df3` and again at `11d5daf`, and the recipe run
  against R571 resolves `11d5daf`. `tail -1` on the session tokens picks the trailer, which is last
  in the body, over any session a Done-gate message names in prose. And `$sha^` is the first
  parent, which carries the file because a pathspec-restricted `git log` without `-m` never
  attributes a deletion to a merge commit; the `cat-file -e` line turns the residual assumption
  into a stop rather than a silent restore of nothing. The section ends by naming what the recipe
  does not do: the revive commit itself and the successor disposals are the session's.
- `.claude/skills/srp/SKILL.md`. Two edits. The "No matches" branch of the id lookup stops at
  "the item shipped; tell the user" today; it gains "if the goal is undelivered, the `roadmap`
  skill's `revive` is the move". The implementation-stage template's disqualified-party list gains
  the value of `revived-verdict-session:` when the front-matter carries it.

## Tests

- `ReviveTest` in `roadmap-tool`, over a temp roadmap directory. A restored file at `In Review`
  with a `## Revived` section ends at `Spec` with a fresh `last-updated:`, an untouched `created:`,
  and both `revived-*` keys; its single-line changelog entry is gone; a three-paragraph entry with
  indented continuation lines (R916's shape) is gone whole, the neighbours on either side are
  byte-identical, and exactly one blank line separates them; an id heading three bullets (R43's
  shape) has all three gone; an id with no changelog entry revives with the reported no-op;
  `status: Ready` on entry fails naming the state; a body without `## Revived` fails naming the
  section; a missing or malformed option fails naming it.
- `validate` coverage beside `NextIdAllocationTest`: an on-board id that also heads a changelog
  bullet fails `verify` with the two-remedy message, for both the `- R<n> (` and the bare
  `- R<n> ` bullet shapes; an id that appears only in prose inside another entry does not; an item
  with `revived-from:` and no `## Revived` heading fails naming the section; one key without the
  other fails naming the absent key. Each of the four `validate` callers is exercised through at
  least one of these, so the check cannot be entry-point dependent.
- `mvn verify -pl roadmap-tool,docs` on the tree: the workflow page renders with the new edge and
  the `check-adoc-xrefs` and README-sync gates pass.
- Not mechanically enforced: the git half of the recipe. No test in a git-free module can run it,
  and nothing re-runs it after the implementing session does. The implementer runs it against R571
  and R926 into a scratch checkout and records the resolved SHAs, paths and sessions in the
  implementing commit message; that is a one-time check on the skill text's claims, not coverage,
  and the skill section says so where the recipe is stated.

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

**Landing the revive at `Ready`, as R571's revival did.** Implementable immediately, and the flip
the tool's map already holds from `In Review`. Rejected because it leaves the discriminator and
the rework scope judged by no one but the session that decided them until the second Done gate,
after the rework is built, while the workflow's own reopening rule says a `Ready` sign-off no
longer covers a plan that has materially diverged from what its reviewer approved. One sign-off
commit is the price of the judgment the retracted verdict shows was missing.

**A `Done -> In Review` edge, with `In Review -> Ready` as the second hop.** Mirrors what the
restored file says. Rejected because no commit ever observes that state, the tool's map cannot
hold an edge from a state with no file, and R506's statechart driver would have two edges and a
continuation pair to fold where one edge says the same thing.

**Machine-read facts in the `## Revived` prose.** One section, no new front-matter. Rejected
because the retracted verdict's session is consumed by the `srp` skill, whose `git log` resolution
is known to return the implementer on exactly this commit shape (R780), and because a prose-only
obligation is enforced once, when the command runs, where a front-matter key lets `validate`
enforce it on every build.

**A git-aware `revive` that restores the file itself.** One command instead of a recipe plus a
command. Rejected because the module has never run a subprocess and has no git dependency; every
subcommand is pure filesystem over `roadmap/`, and `git log`, `git rebase` and the session-trailer
lookups already live in the skills. The split puts each half where its kind of logic already is.

**No tool change at all, recipe only.** Cheapest. Rejected because the things a revived file owes
are the ones a hurried session skips: the changelog retraction is fiddly by hand on multi-paragraph
and multi-bullet entries, and a missing `## Revived` is invisible until the next implementer
rebuilds shipped work.

## Provenance

Prompted by R939, filed against R926 as a successor after the reopening it wanted was found not to
exist, and by R571's revival commit `bf3d987`, which made the moves this item documents without a
line of workflow text to lean on. Whether R926 is revived and R939 discarded into it is that item's
call rather than this one's, but it is the live instance and the obvious first test of the
convention.
