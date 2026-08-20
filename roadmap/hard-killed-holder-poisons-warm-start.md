---
id: R757
title: "A hard-killed dev session leaves a store file that never warms again"
status: Backlog
bucket: cleanup
priority: 4
theme: dev-loop
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# A hard-killed dev session leaves a store file that never warms again

A `graphitron:dev` session ended with `kill -9` (an OOM kill, a container stop, a hard IDE
shutdown) leaves its `store.mv.db` on disk with the schema half-written and no `store_stamp` row in
it. `GraphitronModelStore.openAt` then finds a file at the stamped path whose stamp does not match,
correctly refuses to repair or delete a file it did not write, and falls back to memory. It does so
on *every* subsequent run, so the workspace loses warm start permanently and silently until somebody
deletes the cache directory by hand. Reproduced during the store-contention diagnosis: the next
opener after a `kill -9` reports `Table "STORE_GRAPH" not found (this database is empty)`.

Deliberately split off from the contention work, which fixed the lock-file half of the same accident
(H2 writes no lock file now, so a stale one can no longer wedge an opener) and left this half
untouched. It is a genuine defect rather than a warmth preference: the never-discard rule exists to
protect a file some other run might still be warm on, and a file with no stamp row is one no run can
ever be warm on, so the rule is protecting nothing here. What it needs is a way to tell "a file I
have no business touching" from "a file nothing can ever read", and a decision about which of the
two the store may act on, without opening the door to a cache that deletes state of record.

Whatever the answer is, it has to stay silent about correctness: a run that cannot use the store
boots cold and generates identical output, so the cost being fixed is speed, plus a user who has no
way of knowing their warm start is gone.
