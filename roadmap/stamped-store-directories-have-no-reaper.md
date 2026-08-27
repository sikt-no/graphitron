---
id: R858
title: "Stamped store directories accumulate one per DDL hash and nothing ever removes them"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Stamped store directories accumulate one per DDL hash and nothing ever removes them

The fact store lives under a per-user cache home, in a per-workspace directory, in a subdirectory
stamped with the DDL hash and generator version. Editing the schema DDL changes the hash, so it opens
a different file rather than meeting a store other modules are warm on. Nothing deletes the one it
stopped using.

On one contributor machine this had reached 49 GB across 87 stamped directories, one workspace of
which held 13, the largest single file 2.7 GB. Every one of them is a cache with no state of record.

## This is by design, and the design is the reason there is no reaper

`mvn clean` does not remove these, deliberately: the store stopped being build output when it moved
to the cache home, and the resolver's own comment says the remedy for a damaged store is deleting the
directory by hand. So this is not a bug in `clean` and should not be filed against it. What is
missing is the other half of the decision. Making the store a cache rather than build output was
right; a cache with no eviction is the part that was never added.

The stamped path is what makes discarding safe in principle. A directory whose stamp names a DDL hash
no installed jar computes any more can never be opened again by any build, which is a stronger
statement than a heuristic about age.

## Why it bites more than a stale cache normally would

The size is a function of how often the schema DDL changes, and on this repository it changes
constantly: the fact schema is where the work happens. A contributor doing model work mints a fresh
several-hundred-megabyte store every time they edit the DDL and rebuild, and every one of those stays.

A store left by a run that failed part-way is the worst case and is not rare: a capture that does not
reach commit leaves a file of the full size holding almost nothing. One measured during the sibling
hang investigation was 124 MB and held 67 rows.

## What is not settled

What triggers the reaping, and this is the whole question. Candidates, none obviously right: on
store open, discard sibling stamps under the same workspace that this generator version cannot
produce; a size or age ceiling per workspace; a `graphitron:clean-store` goal a person runs; or
nothing automatic at all and a documented path plus a warning when the directory grows past some
size.

The constraint that rules out the naive version: concurrent sessions and concurrent module builds
share these directories, and a build that reaps a stamp another process is warm on has taken away
that process's cache while it is running. Reaping on open is the shape most exposed to that and needs
to be argued rather than assumed. Whatever is chosen must also never be able to fail a build, which
is the rule the store already holds itself to everywhere else: cache trouble costs warmth, never
correctness.

That constraint already bites today, without any reaper, and the mechanism is worth stating because
it makes the accumulation worse rather than merely risking it. A `graphitron:dev` session holds the
file, so a concurrent build of the same workspace is refused the file and falls back to a private
in-memory store, which is correct and never persists. The next run is therefore cold again, and mints
or reopens a file, while the long-running session keeps its own. A reaper that assumed one live
holder per stamp would be wrong about exactly this case.

## Related

The sibling hang item is where the empty-store case came from, and its transaction-boundary finding
explains why a failing consumer accumulates full-size stores holding nothing.
