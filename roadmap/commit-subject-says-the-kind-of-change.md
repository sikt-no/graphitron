---
id: R1156
title: "A commit on trunk says what kind of change it is"
status: In Review
bucket: process
priority: 2
theme: tooling
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# A commit on trunk says what kind of change it is

## Goal

Every commit reaching trunk says in its subject what kind of change it is, so a reader of trunk's history tells a new capability, a fix and a breaking change apart the day it lands, and a later item can generate release notes or a changelog from that history. This item enables the changelog and does not deliver it: it lands the subject convention now, ahead of any tooling that reads it.

## Problem

Behaviour reaches consumers long before Done. R876's "For the changelog at Done" section holds changes that landed on trunk, which release candidates are cut from, weeks before the item can close: `@table` naming a table-valued function is refused; `@routine` loses `columnMapping`; the MCP schema tool's `demand` slot is removed; `graphitron:capture` no longer fails over a schema that parses; a reactor sibling is read from its own output; `graphitron:dev` compiles its own module first. A further breaking change, `@mutation(typeName: UPSERT)` beside `@service` refused (`6733135`), is not in that section at all. Trunk's subjects today are `R<n>: <sentence>` or `roadmap: <sentence>`, which say which item a commit belongs to and not whether it adds, fixes or breaks anything, so nothing short of reading every diff recovers that.

## Plan

Commits reaching trunk follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/), the item id as the scope.

* **Subject.** `<type>(<scope>)[!]: <description>`. The description keeps the house style, a sentence stating what is now true. The scope is the item id, `feat(R876): ...`; a commit no single item covers takes its area, `roadmap`, `docs`, `deps`. `!` marks a breaking change, and a `BREAKING-CHANGE:` footer says what a consumer must change. The hyphenated form is the house form, the synonym Conventional Commits accepts, because only it is a git trailer and so reads in one block with `Co-Authored-By:` and `Claude-Session:`.
* **Types.** `feat` (what an author or consumer can do changes: added, removed, refused or relaxed), `fix` (behaviour corrected to what was intended), `refactor` (nothing an author or consumer sees changes), `perf`, `test`, `docs`, `build`, `ci`, `chore`, and `revert`, whose body names the hash it reverts. Roadmap commits are `chore`: `chore(R1130): Ready -> In Progress` for one item, `chore(roadmap): ...` for a regeneration or several items.
* **Not for trunk.** `wip`, `draft` and `spike` are types that never reach trunk.
* **Push check.** The publish skill checks every subject in `origin/claude/graphitron-rewrite..HEAD`, where today it reads only the tip's. It stops on a subject whose type is `wip`, `draft` or `spike`, with or without a scope and `!` (`^(wip|draft|spike)(\(|!|:)`), and on any subject not of the shape (`^(feat|fix|refactor|perf|test|docs|build|ci|chore|revert)(\([^)]+\))?!?: .+`), naming each offending commit. A subject git writes itself, `Revert "..."`, fails the shape, so a revert is reworded `revert(<scope>): ...` before it is pushed.
* **Who.** Whoever puts a commit on trunk gives it its type, rewriting a `wip`, `draft` or `spike` subject on the way; that covers a co-author's commits taken onto trunk by another session.
* **Dependabot.** `.github/dependabot.yml` sets `commit-message: prefix: "build"` for both ecosystems, so its subjects read `build(deps): ...`.
* **Cut-over.** The convention holds from the commit that lands this item. History is not rewritten.

Edit sites: `CLAUDE.md`, Git Workflow (the subject shape and the not-for-trunk types, written with the placeholder `<type>(R<n>)` because `check-transient-citations` fails the build on a concrete id there); `roadmap/workflow.adoc`, Publishing (the subject shape with concrete examples); the publish skill's check, widened from the tip's subject to the range and from a literal prefix to the shape; the roadmap skill, which gains a line under `status` giving a transition's subject as `chore(R<n>): <from> -> <to>`; the `classified-corpus` skill's step 8, whose `corpus: add the <id> example for <verdict>` becomes `test(corpus): add the <id> example for <verdict>`; `.github/dependabot.yml`. `srp` needs no edit: its `\bR<n>\b` grep over subjects matches `chore(R<n>)`.

## Implementation

Shipped as planned, every edit site touched in one commit. Two notes from round 4, taken: Dependabot gets `include: "scope"` beside `prefix: "build"` for both ecosystems, so its subjects read `build(deps): ...`; and a failing range check in the publish skill skips only the trunk fast-forward (step 4), the feature-branch push still going ahead, as the skill did for a not-for-trunk tip. The check runs after step 2's fetch so its range is current. `roadmap/workflow.adoc`'s examples use real R876 and R1158 changes where one exists and a placeholder for `revert`.

## Out of scope

* **Release notes and where a consumer reads them.** Graphitron 10's release candidates are tagged and released in Sikt's internal GitLab, so a release-notes command over two release-candidate tags, and the release body or page it writes into, is a follow-up item that reads this history.
* **A build gate on the subject shape.** The publish skill's range check holds the shape at every push made through the skill; a plain `git push` bypasses it, and Dependabot's commits never meet it, until a gate in the build, a follow-up needing `fetch-depth: 0` in `rewrite-build.yml` or a loud failure when the cut-over is unreachable.
* **`changelog.md`.** Unchanged: it stays the Done narrative. The revive retraction gap round 1 found there is `changelog-prose-first-entries-escape-the-invariant`.

## Reviewer findings

### Round 1: Backlog, pushback before Spec (session_01WkW2JFGZ8sg6MFqgv3yaP3, 2026-11-05)

Not a gate verdict; findings to settle before the item moves to Spec. Read against trunk `075fe6a`.

1. **The premise that every entry begins with prose is false.** 584 of the 761 top-level bullets in `changelog.md` begin `- R<n>`; only the recent entries lead with prose. The invariant held by `Main.changelogDoneIds` is not vacuous over the file as a whole, so "an entry for an item still on the board already passes the tool" holds only for a prose-first entry, not for entries generally.

   *Response:* Accepted: the premise was false, and the Problem no longer rests on it.

2. **The prose-first shift is itself a defect the plan leans on.** The recent prose-first Done entries (R1143, R1144, R1142, R1115 and the rest at the head of the file) are invisible to `changelogDoneIds` and to the revive retraction. For those items the board-or-changelog invariant is unchecked and a revive would leave the Done entry standing. The plan distinguishes a Done entry from a delivery entry by "headed by the id" versus not, a line the file's current shape no longer draws. The Spec should name a marker the tool reads for both kinds, for instance the id in the parenthetical, `(slug, R<n>, ...)` for Done and `(slug, R<n> in progress, <sha>)` for a delivery, with `CHANGELOG_BULLET_HEAD` following it. That also answers the open question about rejecting an in-progress marker for an id off the board. Alternatively file the retraction gap as its own item and make this one depend on it; either way the Problem section should not rest on "already passes the tool".

   *Response:* Accepted as a real defect, filed as `changelog-prose-first-entries-escape-the-invariant`. This item no longer changes `changelog.md`, so it does not depend on that fix.

3. **Two of the three motivating examples do not hold on trunk.**
   * UPSERT beside `@service` is R876 (`6733135`) and real, but it is not in R876's "For the changelog at Done" section, so "each recorded only in the item's own section" is wrong for it.
   * The narrowed same-table `@table` field becoming an error, and `Film.sameTableById` removed: not on trunk. `Film.sameTableById` is still in the sakila example schema, and `SameRowTableChildExecutionTest.narrowedForm_readsTheFilteredRows_theSameUnderEveryParent` still pins it as working.
   * A same-table `@table` child without `@reference` refused is R1115's delivery, already announced by R1115's Done entry, so it belongs to another item and was not withheld.

   R876's section carries better evidence, changes that plainly reached trunk long before Done: `@table` naming a table-valued function refused, `@routine` losing `columnMapping`, the MCP `demand` slot removed, `graphitron:capture` no longer failing over a schema that parses, reactor siblings read from their own output, `graphitron:dev` compiling its module first. Use those for the Problem section and for "The first application".

   *Response:* Accepted. The Problem now uses R876's section as suggested, plus the UPSERT refusal missing from it. The narrowed same-table field was in the harvest queue, not on trunk, and the same-table child refusal is R1115's.

4. **Missing edit sites.** R876's "For the changelog at Done" section opens by restating the rule this item changes ("The entry is written at Done, the board and the changelog holding an id one place at a time"); the plan should say that text changes, and whether such sections remain a convention. The roadmap skill's In Review -> Done step ("append a one-line entry ... capturing the landing commit SHA and the `R<n>` ID") also needs rewording beyond the Done step already listed.

   *Response:* Answered by the retarget: this item no longer changes what a Done entry or an item's "For the changelog at Done" section is, so neither R876's opening sentence nor the roadmap skill's Done step changes.

5. **Concurrent prepends.** Writing an entry in the same push as every behaviour-changing commit means many sessions prepending to the head of `changelog.md` at once. Say how a rebase conflict there is resolved, or that the cost is accepted.

   *Response:* Answered by the retarget: the commit subject is the record, so no push prepends to a shared file.

6. **A revert entry needs a shape.** "A delivery later reverted gets an entry of its own" should state its form (a `Reverted:` prefix, like `Discarded:`) so neither a reader nor the tool can take it for a Done or a delivery entry.

   *Response:* Answered by the retarget: a revert is the `revert` type, its body naming the hash it reverts.

Sound as filed: the goal, the when and who rules, the Done entry pointing back to delivery entries rather than repeating them, and delivery entries surviving a revive.

### Round 2: Backlog, pushback before Spec (session_01WkW2JFGZ8sg6MFqgv3yaP3, 2026-11-05)

Not a gate verdict. Read against trunk `e39ed9e60`. The retarget is the better design: it removes the shared-file contention and the entry-shape problem outright, and round 1 findings 1, 3 to 6 are settled by it. Findings against the new plan:

1. **The goal promises more than the plan delivers.** The goal says a consumer learns of a change when it reaches trunk and can ask what changed between two release candidates. The plan produces a command a contributor can run; nothing puts its output where a consumer reads. Releases are cut as GitHub Releases from `v<MAJOR>.<MINOR>.<PATCH>-RC<n>` tags by `maven-publish.yml`, so the natural surface is that workflow writing `release-notes <previous-tag> <this-tag>` into the Release body (or the docs site rendering it). Name the surface, or narrow the goal to what the command gives. "When it reaches trunk" is then served only by reading `git log`; say so if that is the intent.

   *Response:* Accepted by narrowing the goal. This item enables a changelog and does not deliver one: Graphitron 10's release candidates are tagged and released in Sikt's internal GitLab, so where release notes reach a consumer is decided with the item that generates them, listed under Out of scope.

2. **No release-candidate tag exists to anchor on.** `git ls-remote --tags origin` lists `v8.x` and `v9.x` tags and no `-RC` tag, though the changelog speaks of Graphitron 10 release candidates. "The first release notes start at the first release candidate after [the cut-over]" holds, but the plan should say what `<from-tag>` is for that first one (the cut-over commit itself, or a tag placed on it) and that every later RC is tagged on this repository.

   *Response:* Out of scope with the release notes, for the same reason: the release-candidate tags live in the internal GitLab, not on origin.

3. **The subject gate cannot see history in CI as configured.** `rewrite-build.yml` checks out with `actions/checkout`'s default depth of one; only `sync-main.yml` sets `fetch-depth: 0`. Agent sandboxes are shallow too (this one is grafted). A check walking from a fixed cut-over commit to `HEAD` needs that range present: either the workflow gains `fetch-depth: 0` (an edit site), or the gate states its behaviour when the cut-over is not reachable (fail loudly, never pass silently). It also needs `git` on the build path, which `roadmap-tool` does not invoke today; fine under "no new dependency", but say it.

   *Response:* Out of scope for now. The publish skill's check enforces the shape at every push to trunk; a build gate is a follow-up, and that item states `fetch-depth: 0` or a loud failure when the cut-over is unreachable.

4. **Commits the plan does not author will fail the gate.** `.github/dependabot.yml` targets `claude/graphitron-rewrite` for both ecosystems and sets no `commit-message` prefix, so Dependabot's "Bump x from a to b" subjects fail the gate once one lands. Configure `commit-message: prefix: "build"` (Dependabot then writes `build(deps): ...`) or exempt by author; either is an edit site.

   *Response:* Accepted. `.github/dependabot.yml` gains `commit-message: prefix: "build"` for both ecosystems, so its subjects read `build(deps): ...` from the start.

5. **The not-for-trunk check is a literal prefix match.** The publish skill skips on a subject starting `wip:`, `draft:` or `spike:`. With scopes, `wip(R876): ...` does not start with `wip:` and would ship. The check has to match `wip`, `draft` and `spike` as the type, with or without scope and `!`.

   *Response:* Accepted. The check matches `wip`, `draft` and `spike` as the type, with or without a scope and `!`: `^(wip|draft|spike)(\(|!|:)`.

6. **The house's most frequent commits have no stated type.** Roadmap transitions (`roadmap: R1130 Ready -> In Progress`), filings, review rounds and README regenerations are a large share of trunk. The scope rule says the item id, so these become `<type>(R1130): ...`, but which type? `docs` or `chore` both work; pick one so the gate and the reader agree, and so release notes never list a transition.

   *Response:* Accepted. Roadmap commits are `chore`: `chore(R<n>)` for one item's transition, filing or review round, `chore(roadmap)` for a README regeneration or several items.

7. **`CLAUDE.md` cannot carry a concrete example.** `check-transient-citations` fails the build on `\bR\d+\b` in `CLAUDE.md`, so the Git Workflow text must write `<type>(R<n>)` and never `feat(R876)`. Worth a line in the plan so the implementer does not meet it as a build failure.

   *Response:* Accepted. `CLAUDE.md` writes `<type>(R<n>)`; the concrete examples live in this item and in `roadmap/workflow.adoc`, which the citation check does not read.

8. **"Harvest" is unglossed and its edit site does not exist.** The term appears only inside R876's body; no document in `CLAUDE.md`, `roadmap/workflow.adoc` or `.claude/skills/` describes a harvest practice for co-author sessions. Gloss it on first use, and either name the file that will hold the practice or drop it from the edit sites and fold the rule ("the harvester chooses the type") into the Git Workflow text.

   *Response:* Accepted. The harvest edit site is dropped, and the rule is stated for everyone in `CLAUDE.md`'s Git Workflow: whoever puts a commit on trunk gives it its type, rewriting a `wip`, `draft` or `spike` subject on the way.

9. **The response sits in a section of its own.** `roadmap/workflow.adoc` asks for the author's note directly beneath each finding, leaving the finding's text alone, so the returning reviewer audits a delta in place. Move the round 1 responses under their findings.

   *Response:* Accepted: each response now sits beneath its finding.

Sound: the retarget itself, `changelog.md` staying Done-only, the revive gap filed separately with no dependency, the cut-over that leaves history unrewritten, and listing every `!` in release notes whatever its type.

### Round 3: Backlog, pushback before Spec (session_01WkW2JFGZ8sg6MFqgv3yaP3, 2026-11-05)

Not a gate verdict. Read against trunk `90eb4d31f`. The narrowed goal is right: a convention that lands now and tooling that reads it later, each judged on its own. Round 2's findings are answered, each response beneath its finding. One substantive finding remains; the rest are small and could be settled in the Backlog -> Spec revision.

1. **Nothing in the plan holds the shape at push.** The Out of scope section defers a build gate because "the publish skill's check holds the shape at every push", but the plan only widens that check's `wip`/`draft`/`spike` match; no step checks that a subject is `<type>(<scope>)[!]: <description>`. And the check reads only `git log -1 --pretty=%s`, so a `wip(R876)` commit beneath a conforming tip, or any non-conforming commit that is not the tip, still ships. Either the publish skill checks every subject in `origin/claude/graphitron-rewrite..HEAD` for both the shape and the not-for-trunk types (the "Who" rule then has a place where it is enforced), or the Out of scope sentence drops the claim and says the convention is unenforced until the follow-up gate.

   *Response:* Accepted, the first way. The publish skill checks every subject in `origin/claude/graphitron-rewrite..HEAD` for both the not-for-trunk types and the shape (Plan, Push check), so the "Who" rule is enforced where a push is made. Out of scope now says what that leaves unchecked: a plain `git push`, and Dependabot's commits.

2. **`classified-corpus` prescribes a subject.** Its step 8 tells the author to commit as `corpus: add the <id> example for <verdict>`, which the convention makes non-conforming. Add it to the edit sites (`test(corpus): ...` or `docs(corpus): ...`, whichever the plan means by an area scope).

   *Response:* Accepted. Step 8 becomes `test(corpus): add the <id> example for <verdict>`, listed in the edit sites.

3. **The roadmap skill has no commit subjects to edit.** `.claude/skills/roadmap/SKILL.md` names no subject shape today, so "the roadmap skill's commit subjects" is an edit site with nothing at it. Either say what the skill gains (a line stating `chore(R<n>): <from> -> <to>` for a transition) or drop it. For the record, `srp`'s `grep -E '\bR<n>\b'` over subjects keeps working with `chore(R<n>)`, so it needs no edit.

   *Response:* Accepted: the skill gains a line under `status` giving `chore(R<n>): <from> -> <to>`, and the edit site says so. `srp` is noted as needing no edit.

4. **Prefer the hyphenated footer.** Conventional Commits accepts `BREAKING-CHANGE:` as a synonym for `BREAKING CHANGE:`, and only the hyphenated form is a git trailer. With the space it shares a block with `Co-Authored-By:` and `Claude-Session:` that `git interpret-trailers` and `%(trailers)` may then no longer read as trailers. Name `BREAKING-CHANGE:` as the house form.

   *Response:* Accepted. `BREAKING-CHANGE:` is the house form, with the reason.

5. **The slug no longer names the item.** The file is still `changelog-announces-deliveries-in-progress.md` while the item is now the commit subject convention. A slug is filename only, so this is optional, but a rename before Spec costs nothing and keeps a later grep honest.

   *Response:* Accepted. The file is renamed `commit-subject-says-the-kind-of-change.md`, and the one reference to the old slug, in `changelog-prose-first-entries-escape-the-invariant`, follows.

Sound: the narrowed goal, the type list with roadmap commits as `chore`, the not-for-trunk types matched as types, the Dependabot prefix, the `<type>(R<n>)` placeholder in `CLAUDE.md`, the cut-over without rewriting history, and the out-of-scope split.

### Round 4: Backlog, no blocking findings (session_01WkW2JFGZ8sg6MFqgv3yaP3, 2026-11-05)

Not a gate verdict. Read against trunk `0176960ff`. Round 3 is answered in full: the range check gives the "Who" rule a place where it holds, the out-of-scope section states honestly what that leaves unchecked, and the edit sites are complete. I would take it to Spec; two small precisions, either of which can ride in that revision.

1. **Dependabot needs `include: "scope"` for `(deps)`.** `commit-message: prefix: "build"` alone makes Dependabot write `build: bump ...`; the `(deps)` scope the plan states comes from adding `include: "scope"` beside the prefix. Either add it to both ecosystems or state the subject as `build: ...`, which the shape regex accepts as well.

2. **Say which push the range check gates.** Today a `wip`/`draft`/`spike` tip means "push the feature branch, skip trunk", not "stop". The plan says the check "stops", which read literally also blocks pushing a `wip` branch to its own remote, the very thing the not-for-trunk types exist to allow. State that a failing range skips the trunk fast-forward (step 4) and reports, while the feature-branch push still goes ahead.

Sound: everything round 3 asked for, the two regexes (the shape one correctly excludes the not-for-trunk types and the `Revert "..."` subject git writes), the rename and its one follow-on reference, and `srp` left alone.

### Round 5: Spec → Ready, signed off (session_01WkW2JFGZ8sg6MFqgv3yaP3, 2026-11-05)

Read against trunk `c5cd6c3aa`. Rounds 1 to 4 were this gate's review, labelled "before Spec" in error while the item sat in Backlog; this round gives the verdict. The plan text was last revised by session `01WkW2JJ6ffNizo4FZbeRuoU` (`0176960ff`); this session wrote only findings and the Backlog -> Spec flip.

* **Goal well communicated and viable.** Yes. The first paragraph says what changes on its own terms (a subject that tells a capability, a fix and a breaking change apart on the day it lands) and what is deliberately not delivered (release notes, a changelog). Every edit site exists and the outcome needs nothing the tree lacks.
* **Fits the architecture we have.** Yes. It widens the publish skill's existing not-for-trunk check rather than standing a new mechanism beside it, sets prefixes in the existing `.github/dependabot.yml`, and rewrites subject conventions where skills already state them. The build gate is deferred with the gap it leaves stated.

Round 4's two points carry to the implementer as notes, not conditions: add `include: "scope"` beside the Dependabot prefix if `build(deps):` is the wanted subject, and keep the feature-branch push going ahead when the range check fails, skipping only the trunk fast-forward, as the skill does today for a not-for-trunk tip.

### Round 6: In Review → Ready, rework (session_01WkW2JFGZ8sg6MFqgv3yaP3, 2026-11-05)

Read against trunk `7888b80dc`; implementer session `01WkW2JGHHpmT26aS7nfdXNU`, delivery `59885887b`. Every edit site the plan names is touched and matches it, round 4's two notes are taken, and the delivery is the change the spec approved. The publish skill's range check does what it says: run over fixture commits it flags `wip(R2)!:`, `spike:`, `Revert "..."`, an old-style `R5:` subject and `feat(R1) :`, and passes `feat(R1):`, `fix:`, `chore(roadmap):` and `docs!:`. Completeness is demonstrated where it can be: every subject on trunk after the cut-over conforms, from four sessions, so the convention is being read and followed. One finding blocks.

1. **The item's own motivating changes have no type, and the worked example gives them the wrong one.** The Problem's cases are a removed argument (`@routine` loses `columnMapping`), a removed slot (MCP `demand`) and new refusals (`@table` on a table-valued function, UPSERT beside `@service`). The convention glosses `feat` as "an author or consumer can do something new" and `fix` as "behaviour corrected"; neither covers taking something away or refusing what was accepted, and no other type is glossed. `roadmap/workflow.adoc`'s example fills the gap with `refactor(R876)!: @routine no longer takes columnMapping`, but `refactor` conventionally means no behaviour change, and the follow-up release notes this item enables list `feat`, `fix`, `perf` and `revert` and drop `refactor` unless it carries `!`. So the next author copies the example for a consumer-visible change that is not breaking, a relaxed refusal or a changed message, writes `refactor`, and the release notes lose it. Name the type for a consumer-visible change that is neither new capability nor correction (`feat` with the gloss widened to "what an author or consumer can do changes", which is the common reading, is the smallest fix), gloss `refactor` as changing nothing an author or consumer sees, and change the example to match. Bears on correctness: the delivered convention cannot classify the changes it exists to surface.

   *Response:* Accepted, the smallest fix. `feat` is glossed in `CLAUDE.md` and `roadmap/workflow.adoc` as "what an author or consumer can do changes", naming removals, new refusals, relaxed refusals and changed messages; `fix` as behaviour corrected to what was intended; `refactor` as changing nothing an author or consumer sees. The example is now `feat(R876)!: @routine no longer takes columnMapping`, beside a second, `feat(R876)!: @table naming a table-valued function is refused`, which notes that a relaxed refusal or a changed message is `feat` without `!`.

   The non-blocking note is taken: the publish skill's "When to skip" now opens "Skip the trunk fast-forward if".

Not blocking: the publish skill's "When to skip" still opens "Do NOT run this skill if", while the subject check is now run inside the skill at step 2 and the skill then pushes the feature branch; "Skip the trunk fast-forward if" would read true.
