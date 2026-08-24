---
id: R822
title: "A Java-source watcher test times out under load instead of waiting for the event it needs"
status: Backlog
bucket: bug
priority: 4
theme: testing
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# A Java-source watcher test times out under load instead of waiting for the event it needs

> `CatalogRefreshTest.javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass` failed one
> verification build with `source refresher must fire on .java write / Expecting value to be true
> but was false`, and passed on an immediate standalone rerun of the same tree. The assertion is a
> `CountDownLatch.await` with a fixed millisecond budget behind a debounce; when the machine is
> loaded the debounce plus the refresher walk can outrun the budget, and the test reports a
> missing filesystem event rather than a slow one.

---

## Observed failure

One occurrence, during a verification build on a machine also running two other Maven
reactors. 125 tests in `graphitron-maven-plugin`, one failure:

```
CatalogRefreshTest.javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass(Path)
  [source refresher must fire on .java write]
  Expecting value to be true but was false
  at CatalogRefreshTest.java:129
```

An immediate `mvn test -pl :graphitron-maven-plugin -Dtest=CatalogRefreshTest` on the same tree
ran 3/3 green, which is what makes this a timing fault rather than a defect in the watcher.

## Why it is worth fixing rather than tolerating

The failure mode is indistinguishable from the real regression the test exists to catch: "the
refresher did not fire". A reader of a red build cannot tell a loaded machine from a broken
dispatch without rerunning, and the natural reflex on a timing-shaped failure is to raise the
budget, which weakens the pin permanently in exchange for one green build.

The two sibling cases in the same class share the pattern, so whatever the fix is, it applies to
all three rather than to the one that happened to lose the race.

## Sketch

Not designed yet. The shape to look for is a wait that ends when the condition is decidable rather
than when a clock expires: the latch already exists, so the question is whether the budget can
come from the debounce interval it is actually racing (a multiple of `DEBOUNCE_MS`) instead of a
flat constant, and whether the failure message can distinguish "no event arrived" from "the event
arrived after the budget" by checking the latch once more after the store assertion.

Worth checking first whether the wider suite has a shared idiom for this. If several tiers wait on
filesystem or debounce events with hand-rolled latches, the fix is one helper, not a patch per
call site.

## Acceptance

* The three cases in `CatalogRefreshTest` pass under artificial load (the reproduction the fix is
  developed against, not merely a clean machine).
* A failure that really is a missing dispatch still reports as one, with a message that does not
  read as a timeout.
* No budget is raised without the message distinguishing the two causes.
