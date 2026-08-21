---
id: R775
title: "The Spec gate reviewer lands findings, not fixes: half of R769's spec was written by its reviewers"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The Spec gate reviewer lands findings, not fixes: half of R769's spec was written by its reviewers

When this lands, a Spec → Ready reviewer who withholds sign-off writes their findings into a
`## Reviewer findings` section at the bottom of the item file and stops there. The author
addresses them in the plan body, and the same reviewer picks the item back up carrying the context
they already built. Today the reviewer instead edits the plan body themselves, which means design
prose enters the spec that nobody reviews as a draft: the next reviewer inherits it labelled
"settled" and finds the defect in it, which is the bounce loop this item exists to stop.

**Reviewers are already doing this, and the item's job is to standardize it rather than invent
it.** Four items in `roadmap/` carry a bottom-of-file review section today, the earliest dated
2026-08-11, under three different headings: `++##++ Reviewer findings` twice
(`lsp-store-reads-inside-a-time-budget.md`, `web-session-maven-build-log.md`),
`++##++ Review record` once (`upgrade-graphql-java-26.md`) and `++##++ Reviewer decisions` once
(`write-input-deprecated-column-alias.md`). The most recent, on R773, landed the same day as this
item: an independent session appended its findings, left the plan body untouched, and committed
that alone. So the convention is emergent, ten days old, and unwritten, which is why it did not
reach R769's reviewers. What is missing is the rule saying findings are the reviewer's *only*
deliverable, one spelling of the heading, and the handback it implies. `++##++ Reviewer findings`
is the spelling this item picks, on the ordinary grounds: it is the plurality and the most recent,
so the convention extends a shape already in the tree instead of adding a fourth name.

Nothing changes for a consumer of the generated code or the plugin. The consumer here is the
contributor and the reviewer sessions, and what changes for them is which party is allowed to
decide what the implementer will build.

## The evidence, from R769

R769 has bounced three times. The first was on the author's own defect and the gate worked. The
second and third were on prose a reviewer had written minutes earlier.

Three commits inside 37 minutes grew the body 166 → 223 → 273 lines. `git blame` on
`roadmap/seeded-store-boots-per-thread.md` splits as 139 lines from the author, 73 from the
first reviewer, 61 from the second: **49% of the spec under review was written by its
reviewers.**

The chain is exact, and worth reading as the failure mode rather than as three mistakes:

* The **author** wrote the boot-count pin correctly, as a bound: "boots per JVM are bounded by
  the thread count *plus the four direct-boot classes' own opens*." They also wrote "Two
  decisions for the reviewer" into the Tests section, and recommended the wrong arm of the one
  design fork they named.
* **Reviewer A** caught the wrong arm, correctly: the recommended class is package-private in a
  downstream module. They then settled all the deferred decisions by editing the body. One of
  those edits fixed a real problem in the pin (a `ForkJoinPool` may add compensation threads, so
  `boots == 4` can flake) and in doing so replaced the author's bound with an equality: "count
  the distinct thread identities alongside the boots and assert the two are equal." The
  direct-boot term vanished. Reviewer A's own commit message names those four classes correctly,
  so they held the fact and still dropped it from the assertion, because they were reasoning
  about thread pools and not about scope.
* **Reviewer B** found reviewer A's guard decision tautological, correctly, and rescoped it. They
  then settled where the boot counter lives, on `FactStores`, and the argument they gave for that
  home was that *"it sees every boot in the module."* Cited as a virtue. That is the term that
  makes reviewer A's equality false: roughly 24 boots against 4 booting threads.
* **Reviewer C** found the contradiction. Neither A nor B owned it, because each was answering a
  different sub-question and each inherited the other's paragraph as decided text.

Two structural facts produced this, and both are addressable:

1. **Reviewer prose is never reviewed as a draft.** It reaches the next reviewer already carrying
   the word "settled". Reviewer B's commit message opens "Both decisions this section left to the
   reviewer are settled"; the pin sat in a section neither A nor B flagged as open, so nobody
   looked at it again.
2. **Fresh context is the wrong asset for fixing.** The reviewer rule exists so the reviewer has
   no prior reasoning trail. That is right for finding a problem and wrong for repairing one,
   because the repairer has the least context about the parts they are not repairing. It is
   precisely how the direct-boot term got dropped.

For base rate: R759 cleared in one round, R732 took five. R769's count is elevated but inside the
process's observed range, which says the loop is a property of the process rather than of the
item.

## What changes

**The reviewer's deliverable becomes a finding, not a diff.** A reviewer who withholds sign-off
appends one round to a `## Reviewer findings` section at the bottom of the item file and commits that
alone. They do not touch the plan body.

**The author addresses the findings in the plan body.** "The author" is the role, not the
session: a spec author's session is often gone by the time the review lands, and a fresh session
picking up the authoring role is fine and sometimes better, because it reads the whole plan plus
the findings and reconciles them into one voice. What matters is that the party who decides what
the implementer builds is the party writing the plan.

**The same reviewer picks it back up.** They have already verified the spec's claims against the
tree, so the second pass is an audit of a delta rather than a re-derivation, and it closes the
loop the current process leaves open: the fix gets reviewed by someone who knows what it was
fixing. This is a preference and not a guard. Sessions are ephemeral, so the mechanical rule
stays the one we have, and any independent session may take the pass when the original reviewer
is unavailable.

**The reviewer-rule guard is unchanged and keeps working.** Author commits the spec, reviewer
commits findings, author commits the revision, reviewer signs off: at the sign-off the reviewer
is not the last committer, so the existing `reviewer ≠ last committer` comparison passes on its
own terms. Only the prose needs to say that appending findings is not the kind of edit that
disqualifies.

### The line between a fix and a finding

A blanket "reviewers never edit" would make a wrong table count cost two sessions, so the rule is
scoped, and the test is one sentence: **if the edit changes what the implementer will build, it
belongs to the author.**

A reviewer may still land, in the same commit as their findings:

* a factual correction they verified (a stale count, a symbol that does not exist under the name
  the spec gives it, a path that moved),
* formatting, a broken link, a typo.

A reviewer may not land:

* an answer to a design fork the spec left open,
* a choice between arms the spec named,
* new rationale for a decision, or a rewritten section.

Each of those is a judgement the author did not make, and every one of R769's reviewer-authored
defects is in the second list.

### Where the verification narrative goes

Today's review commit messages run to sixty lines of "verified as named" prose. That belongs in
the commit message, which is where it is now and where it is fine. The findings section carries the
findings and the verdict only, so the item file stays readable as a plan with an argument
attached rather than becoming a transcript.

## Implementation

Three files, all prose. The replacement text is given here rather than described, because the
words are the whole change and reviewing a description of them is not reviewing them.

### `roadmap/workflow.adoc`

**1. The state diagram.** The single `Spec --> Spec` edge becomes two, because the two moves now
have different actors and different guards:

```
    Spec --> Spec           : review; findings appended [reviewer ≠ last committer]
    Spec --> Spec           : revise; author addresses findings
```

**2. The reviewer-rule paragraph.** Its last sentence today reads:

> A reviewer session that lands substantive edits on the artifact disqualifies *that session* from
> approving the resulting revision; another session must sign off.

Replace with:

> A reviewer appends findings rather than editing the plan, so this rule rarely bites: the author
> lands the revision, which makes them the last committer and leaves the reviewer free to take the
> next pass. A reviewer session that does land substantive edits on the plan body disqualifies
> *that session* from approving the resulting revision, and another session must sign off.

**3. A new paragraph after "What each gate decides",** carrying the mechanism:

> *Findings, not fixes.* A reviewer who withholds sign-off appends one round to the item's
> `++##++ Reviewer findings` and commits that alone; the author addresses the findings in the plan body.
> The division is not ceremony. A reviewer's edit to the plan is design prose that no one reviews
> as a draft: it reaches the next reviewer labelled settled, and the defect in it surfaces a round
> later as somebody else's finding. Fresh context, which is what the reviewer rule buys, is the
> right asset for finding a problem and the wrong one for repairing it, because the repairer holds
> the least context about the parts they are not repairing. The test for what a reviewer may still
> fix in passing is whether the edit changes what the implementer will build: a stale count, a
> symbol that does not exist under the name the spec gives it, a broken link and a typo are the
> reviewer's to correct in the same commit as the findings; an answer to an open design fork, a
> choice between arms the spec named, and a rewritten section are the author's. The same reviewer
> should take the next pass where the session is still available, having already checked the
> spec's claims against the tree; the guard above does not require it, and any independent session
> may take it instead.

**4. A new bullet under "Item file conventions",** after the `Retired vocabulary` bullet:

> * A reviewer's findings live in a `++##++ Reviewer findings` section at the end of the item file, below
>   every plan section, appended one `++###++` round per pass and never rewritten. A round names
>   the gate, the verdict, the reviewer's session ID and the date, then states the findings; the
>   verification narrative behind them belongs in the review commit's message, not here. The
>   author responds by revising the plan body and noting under each finding what they did, which
>   is what lets the returning reviewer audit a delta instead of re-reading the spec. The whole
>   log dies with the file at Done, so it cannot rot.

**5. The canonical path.** Step 2 today reads "*Reviewer (≠ author)* reads the plan, revises if
needed (stays `Spec`), then signs off by flipping `status:` to `Ready`." Replace with:

> . *Reviewer (≠ author)* reads the plan. Clean: sign off by flipping `status:` to `Ready`.
>   Otherwise: append a round to the item's `++##++ Reviewer findings`, leave `status:` at `Spec`, and
>   hand back to the author, who revises the plan body. The same reviewer then takes the next
>   pass.

The "Minimum four commits by at least two parties" preamble stays true; the typical-path sentence
gains that a revision round is two commits rather than one.

### `.claude/skills/srp/SKILL.md`

**1. The Spec-stage template's Verdict section.** Its second bullet today reads:

> - Request revisions, naming which question failed and what would satisfy it.
>   Either commit spec revisions yourself on a fresh feature branch (status stays
>   Spec, and you become the last committer, so the next pass needs a different
>   session), or leave the notes for the author.

Replace with:

```text
- Request revisions. Append one round to the `## Reviewer findings` at the bottom of
  the spec file, naming which question failed and what would satisfy it, and
  commit that alone on a fresh feature branch; status stays Spec. Do not edit
  the plan body: settling an open fork yourself puts design prose into the spec
  that nobody reviews as a draft, which is what the log exists to stop. A stale
  count, a symbol that does not exist under the name the spec gives it, a broken
  link or a typo you may correct in the same commit; anything that changes what
  the implementer will build is the author's. Keep the round to the findings and
  the verdict. What you verified along the way goes in the commit message.
  The author revises, and you take the next pass with the context you built.
```

**2. The Implementation-stage template's rework bullet,** for one convention rather than two. It
today reads "capture the finding in the spec body for the next pass"; replace "the spec body"
with "the spec's `## Reviewer findings`".

**3. The "Template design intent" section** gains a sentence naming why the templates hand back
findings rather than edits, so a future editor of the templates does not optimise the instruction
away as ceremony.

### `.claude/skills/roadmap/SKILL.md`

The transition table's `Spec | Spec` row becomes two rows, matching the diagram:

```
| Spec          | Spec         | review; findings appended; reviewer ≠ last committer            |
| Spec          | Spec         | revise; author addresses the findings; no guard                 |
```

## Tests

No build gate. Every artifact here is prose that no test parses, and inventing a gate that greps
item files for a `## Reviewer findings` heading would fire on every clean spec, which is the wrong
enforcer for a convention that only applies when a review withholds.

The honest enforcer is the next reviewer, and the honest acceptance is a measurement.

**The acceptance is R769 itself, plus the next three items that bounce.** For each, `git blame`
the item file at sign-off and take the fraction of surviving lines written by a reviewer session.
Today's R769 figure is 49%. Under this change the figure for a bounced item should be the
findings section alone, so a plan body with any reviewer-written line in it is the convention
failing and the reason is worth reading.

**What would falsify the bet.** This trades one extra session per revision round for fewer
rounds: a three-round item goes from three sessions to six if round count holds flat, and pays
for itself only if round count falls. If the next three bounced items each still take three or
more rounds *and* their findings are about author-written prose rather than reviewer-written
prose, the diagnosis in this item is wrong and the loop has another cause. Record the round count
and the blame fraction in this item's own findings section as the items land.

## Roadmap entries

* R769 is the worked example and is mid-flight at three bounces. It should adopt the convention
  for its next pass rather than waiting on this item, since four items already carry a findings
  section and R769's outstanding finding is exactly the kind that wants an author's answer.
* The three items already carrying a review section under another spelling
  (`upgrade-graphql-java-26.md`, `write-input-deprecated-column-alias.md`,
  `web-session-maven-build-log.md`) are not worth renaming in flight. They delete at Done, and a
  rename would touch three specs mid-review to no reader's benefit.

## What this item deliberately does not do

**It does not change the reviewer-rule guard.** The mechanical comparison stays `reviewer ≠ last
committer` by session ID. Making "the same reviewer returns" a requirement would need session
liveness the process cannot promise, and a guard nobody can satisfy gets bypassed.

**It does not touch the four gate questions,** which are working: all three of R769's bounces
found a real defect. The gate is not too strict; the loop is fed by who repairs what it finds.

**It does not add a build gate,** for the reason the Tests section gives.

**It does not address the author-side half of R769's diagnosis,** which is that a draft carrying
"Two decisions for the reviewer" cannot pass a gate that asks whether the solution fits, because
the gate has nothing to evaluate. Whether Backlog → Spec should refuse to promote a draft with
open forks is a separate question with its own trade-off (a spec that must settle everything up
front is a spec that guesses), and it belongs in its own item.

## Reviewer findings

### Spec → Ready gate, 2026-08-21, session_01AnCqfHKP3pRumxwLHKUotV: revisions requested

Withheld on question one, on the acceptance rather than on the goal or the design. This round uses
the convention the item proposes, `++##++` section and one `++###++` round, so the shape it
standardizes is the shape it was reviewed under.

**What passed.** Question two is clear: the item extends a convention already in the tree rather
than standing a parallel mechanism beside one, it picks the plurality and most recent spelling on
stated grounds, and it changes no guard. Every anchor the Implementation section names exists as
named and every quoted sentence matches its source verbatim: the single `Spec --> Spec` edge, the
reviewer-rule paragraph's last sentence, the `Retired vocabulary` bullet under "Item file
conventions", the "Minimum four commits" preamble, canonical-path step 2, the srp Verdict bullet,
the srp rework bullet's "capture the finding in the spec body for the next pass", the "Template
design intent" section, and the `Spec | Spec` row in the roadmap skill's table. Every number in
the evidence section is exact: the blame on R769 splits 139 / 73 / 61 of 273 lines, which is 49%;
the three commits grew the body 166 → 223 → 273 inside 37 minutes and 10 seconds; the four
existing review sections carry the three headings under the four filenames given, earliest dated
2026-08-11; R759 cleared on one spec-review commit and R732 took five, both as recorded in
`roadmap/changelog.md`.

Two claims the item does not make are worth recording, because they are the load-bearing ones and
they hold. First, the convention's precedent is complete, not partial: on R773 one session
appended 81 lines with zero deletions and committed that alone, a second session revised the plan
body, and the first session then signed off. Second, the reviewer-rule guard really does pass on
its own terms under the new flow, because `.claude/skills/srp/SKILL.md` step 4 resolves the
disqualified session from `git log -1` on the spec file alone. After a findings commit and an
author revision, the last committer is the author, so the returning reviewer is not disqualified.
The claim that only the prose needs changing is mechanically true.

**Finding 1, blocking, question one: the acceptance has no reachable home, so the item has no
Done condition.** The Tests section makes the acceptance "R769 itself, plus the next three items
that bounce" and says to "record the round count and the blame fraction in this item's own
findings section as the items land". The convention bullet this item writes says of that same
section: "The whole log dies with the file at Done, so it cannot rot." Both cannot hold. The next
three bounced items land after this one does, so either the measurement is written into a file
that is deleted before the measurement exists, or the item is held at In Review waiting on three
unrelated items, which `depends-on: []` does not express and which no reader would infer. The Done
gate's fourth question asks which named artifact demonstrates the goal is delivered, and as
written the answer is a measurement that cannot be taken before the gate. An item whose whole
purpose is to stop bounce loops should not set up a bounce at its own Done gate.

This is not an argument for adding the build gate the Tests section declines. That reasoning is
sound: a grep for the heading would fire on every clean spec. It is an argument that the
substitute needs a home that outlives the item file. What would satisfy the finding: say plainly
that Done turns on the three prose edits landing, and give the measurement a durable home. The
changelog entry is one candidate and is a permanent artifact that may be cited by path; a
follow-up Backlog item that carries the measurement and the falsification test is another, and
`docs/architecture/explanation/development-principles.adoc` already frames that move as the
expected one ("a review-only label is an invitation: filing the meta-test that pins it is roadmap
material"). Either resolves it. The choice is the author's, which is why this round does not make
it.

**Finding 2, factual correction for the author to land: the "settled" quote is spec body, not a
commit message.** The item attributes "Both decisions this section left to the reviewer are
settled" to reviewer B's commit message, and says it opens it. That sentence is not in either
reviewer's commit message. It is line 166 of `roadmap/seeded-store-boots-per-thread.md`, written
by reviewer B's commit `3b6adca` into the spec body, mid-section. Reviewer B's commit message
opens "Spec -> Ready withheld on question two." The correction strengthens the argument rather
than weakening it, which is why it is worth making: structural fact 1 says reviewer prose "reaches
the next reviewer already carrying the word settled", and a reviewer writing "are settled" into
the plan body is a strictly better illustration of that than the same words in a commit message,
since a commit message is not what the next reviewer inherits as spec text. The rest of the
narrative checks out, including the author's "Two decisions for the reviewer" at lines 112 to 113
of the authoring commit, and reviewer A's commit message naming the four direct-boot classes at
exactly seven sites while proposing the pin that drops the term.

**Finding 3, factual correction for the author to land: one of the four is not bottom-of-file.**
"Four items in `roadmap/` carry a bottom-of-file review section today" is not true of
`web-session-maven-build-log.md`, where the two review sections sit at lines 85 and 142 and a
plan section, `++##++ Explicitly out of scope`, follows at 194. The count of four is right and the
headings are right; only the position claim is. This matters slightly beyond precision, because
the new convention bullet specifies "at the end of the item file, below every plan section", so
the emergent practice is less uniform on placement than the paragraph claims and the item is
standardizing placement too, not only spelling. R773 also diverges on shape, answering findings in
a separate `++##++ Author revision` section rather than under each finding. Neither divergence
weakens the case for standardizing; both are worth naming as things the standard decides rather
than inherits.

**Non-blocking, no action expected.** The `++##++` escapes are correct inside the workflow.adoc
replacement blocks and match `workflow.adoc` line 79, so they should not be "fixed" there. In the
item's own descriptive prose they render literally on GitHub and as monospace through the
roadmap-tool md-to-adoc render; this item is the only one in `roadmap/` using them, and it is
purely presentational. Separately, a spelling gate does become possible once the three
legacy-spelling items drain, since a check that no item file carries `++##++ Review record` or
`++##++ Reviewer decisions` would fire only on the thing the standard forbids rather than on every
clean spec. That is Backlog material and deliberately not this item's scope. Finally, the two
`Spec --> Spec` Mermaid edges and the two duplicate `Spec | Spec` rows in the roadmap skill's
table are both inert: `Main.ALLOWED_TRANSITIONS` stores targets as a `Set`, and the skill's table
is documentation of it rather than input to it.

**Addendum, same round, after a trunk sync during the review.** R769 took a fourth pass while this
round was being written, in commit `81c190e` from a fifth session, and it followed the convention
this item proposes: 81 insertions, zero deletions, into a `++##++ Reviewer findings (Spec → Ready
gate, 2026-08-21)` section with the plan body untouched. Three consequences, and the first two are
in the item's favour.

The premise is stronger than the item claims. The "Roadmap entries" section recommends that R769
adopt the convention for its next pass rather than waiting on this item, and that has now happened
without any prompting from here. Five items carry a bottom-of-file review section, not four, and
`++##++ Reviewer findings` is three of five rather than two of four, so the plurality argument the
spelling choice rests on is firmer than the paragraph makes it.

The bounce count is a moving target and should stop being pinned. The body says "R769 has bounced
three times", which commit `66197ce` had corrected downward from four; it is four now and will be
five if the next pass withholds. Rewriting it to "at least three" or to a figure the sentence does
not depend on would stop the number from rotting under the item, since the argument needs only
that two consecutive bounces landed on reviewer-authored prose.

The third consequence sharpens finding 1 with a counterexample the tree just produced, and is the
reason this addendum blocks rather than merely informs. The acceptance says to `git blame` the
item file at sign-off and take "the fraction of surviving lines written by a reviewer session".
On R769 right now that fraction is 215 of 354 lines, or 61%, up from the 49% the item quotes as the
figure to beat. The rise is not the convention failing; it is the convention working, because the
81 new reviewer-written lines are findings and the plan body is untouched. So the metric as
specified moves the wrong way on a fully compliant item, and would read as regression at exactly
the moment it should read as success. The metric has to be the reviewer-written fraction of the
*plan body*, excluding the findings section, and the item's own 49% figure is already that number
rather than the one its instruction computes. Fixing the acceptance therefore means fixing what it
measures as well as where it is recorded.
