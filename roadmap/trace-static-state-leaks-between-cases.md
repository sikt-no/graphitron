---
id: R809
title: "LspTraceTest cases share the trace seams static state and fail each other"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# LspTraceTest cases share the trace seams static state and fail each other

`LspTraceTest.doubleCloseIsIgnored` failed one full `mvnd install -Plocal-db` and passed the next on
a tree where no commit since the previous green build had touched anything outside `roadmap/`. It
expected 1 and got 2, so something was counted twice rather than not at all, and re-running the
class alone puts all fourteen cases green.

The mechanism is almost certainly the seam's own shape rather than the case's. `LspTrace` keeps its
whole configuration in static mutable fields, `enabled`, `sink`, `slowNanos` and the
`headerWritten` guard, which is the right design for a diagnostic seam a launcher configures once
and every thread then reads; it also means one test's sink, header guard or enablement is visible to
the next, and the class has a test-only setter (`slowMsForTesting`) that says as much. A count that
reads 2 where 1 was written is what a header or a close arriving from a neighbouring case looks
like.

So the question is not which case is wrong but where the reset belongs: a per-case restore of every
static the seam owns, or a seam that hands a test its own instance to configure. The second is the
larger change and the one that would stop the class needing discipline at all, and which of the two
is right depends on whether anything besides tests ever wants a second trace configuration in one
JVM.

Worth knowing while sizing this: a second, unrelated order-dependent failure surfaced in the same
session, in `graphitron-sakila-example`'s execution tier, filed beside this one. Two in three full
builds is enough to say the suite has order-dependent cases rather than one unlucky test, even
though the two have nothing in common beyond that.

Found while holding the In Review gate on the inlay enforcer item, which touched neither this class
nor the trace seam; filed rather than folded into that verdict.
