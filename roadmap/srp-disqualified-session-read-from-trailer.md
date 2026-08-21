---
id: R780
title: "srp names the reviewer as the disqualified party: the session grep takes the first ID in the body, not the trailer"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# srp names the reviewer as the disqualified party: the session grep takes the first ID in the body, not the trailer

The `srp` skill resolves the reviewer-rule guard's disqualified party by grepping the last
commit's whole message for a session ID and taking the first hit: `git log -1 --format=%B "$sha" |
grep -oE 'session_[A-Za-z0-9]+' | head -1`. The `https://claude.ai/code/session_<id>` trailer is
the identifier the guard compares, and it is the *last* line of a commit message, not the first
match in it. Any commit whose body mentions another session ahead of its own trailer resolves to
the wrong party, and the emitted review prompt then carries that wrong ID as a stated fact the next
reviewer is told not to re-derive.

This is not hypothetical. On the item that standardized reviewer findings, the author's revision
commit opened "Addresses all three findings from session_01AnCqfHKP..." and carried its own
`Claude-Session:` trailer for a different session forty-five lines later. Resolution on that commit
returns the *reviewer's* ID and omits the author's, exactly backwards. Two consequences, and the
second is the serious one. The returning reviewer is told they are disqualified, which defeats the
same-reviewer-returns preference the findings convention is built around. And the party who
actually last committed the spec file is absent from the disqualified list, so a session could hand
the sign-off back to the author and the guard would pass silently. A gate that names the wrong
party is worse than one that names nobody, because the prompt presents it as resolved.

The fragility predates the findings convention, but the convention makes it routine rather than
rare: a revision commit exists precisely to answer a findings round that names its reviewer's
session ID, so citing that ID in the body is the natural thing for an author to write, and the
first commit written under the convention did it unprompted.

`srp-disqualified-session-set.md` is the sibling defect in the same three lines and should probably
be specced alongside this one. It is about *how many* sessions step 4 resolves: `git log -1` yields
only the tip committer where the guard means every session with a reasoning trail on the draft. This
item is about *which* session it resolves, and the two compose badly, since a resolution that is
both singular and anchored on the wrong match can name exactly one party and have it be the only
party that is not disqualified. Neither item subsumes the other: widening to every session with a
trail does not help if each is read off the first body match, and anchoring on the trailer does not
help if only one commit is consulted. Whoever picks up either should read both.

The fix is small: anchor the grep on the trailer rather than on the first match, in both the
Spec-stage resolution and the Implementation-stage loop, which share the shape. Worth deciding at
Spec whether a commit with no trailer should still fall back to the body grep or go straight to the
`<no-trailer>` path the templates already carry, and whether the same anchoring belongs in the
`roadmap` skill's guard step, which reads git author rather than session ID and so answers a
different question than the rule states.
