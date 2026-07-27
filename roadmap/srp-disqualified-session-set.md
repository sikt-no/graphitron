---
id: R553
title: "srp resolves one disqualified session where the guard means every session with a trail on the draft"
status: Backlog
bucket: improvement
priority: 5
theme: tooling
depends-on: []
created: 2026-07-27
last-updated: 2026-07-27
---


# srp resolves one disqualified session where the guard means every session with a trail on the draft

The `srp` skill's Spec-stage step 4 resolves the disqualified party with `git log -1` on the spec
file, yielding one session, the tip committer, which it stamps into a singular
`Disqualified session ID:` token. `roadmap/workflow.adoc` states the guard's purpose as fresh
context ("a reviewer session with no prior reasoning trail on the work spots design problems the
authoring or implementing session has already rationalized away") and explicitly anticipates a
reviewer landing revisions, which "disqualifies *that session* from approving the resulting
revision; another session must sign off". Those two readings come apart on the encouraged path: once
a reviewer session commits a revision pass, the session that *wrote* the draft is no longer the tip
committer, so the skill omits it and the emitted prompt affirmatively tells a disqualified author
that it is not the disqualified session. That is worse than saying nothing, because step 4's own
rationale is that "the next reviewer applies the rule by ID, not by re-deriving it".

Observed instance, not hypothetical: R541's draft was written by `session_017mC4g44qxZyaRSYB36Y7wv`
and revised by `session_01NSG1qCW46iJ35CJFZs9uPY`. The Spec-stage prompt emitted for it named only
the second. The authoring session caught the omission and corrected it in prose outside the fenced
block, which the skill's output rules treat as the exception case rather than the norm.

Fix shape (for the Spec pass to confirm): drop the `-1` and collect every session that has authored
a commit touching the spec file since the item's most recent entry into `Spec`, pluralising the
token the way the implementation-stage template already does. The window matters because a
`Ready → Spec` reopen resets the draft, so sessions with a trail on a superseded draft need not be
excluded; if detecting that boundary proves fiddly, prefer the whole file history. The error
asymmetry argues for over-disqualifying: naming an extra session costs one more fresh context,
whereas omitting one voids the gate.

Adjacent question worth settling in the same pass: whether the implementation-stage resolution has
the mirror-image defect. It walks `git log -50` filtered by a bare `R<n>` grep rather than the spec
file's history, so it can both miss implementation commits whose subjects omit the ID and pick up
unrelated commits that happen to mention it.

