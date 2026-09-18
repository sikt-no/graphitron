---
id: R959
title: "An execution case reads a film another case inserted, so its two queries disagree"
status: Backlog
bucket: cleanup
priority: 6
theme: testing
depends-on: []
created: 2026-09-18
last-updated: 2026-09-18
---

# An execution case reads a film another case inserted, so its two queries disagree

## Goal

`OptionalNodeIdProjectionExecutionTest.anOmittedNodeIdReachesAConditionMethodAsNull` asserts that two
queries against the same database return the same films, and it fails intermittently because another
case inserts a film between them. The execution tier runs classes in parallel against one PostgreSQL
instance seeded by `init.sql`, which creates ten films, and several mutation cases create more; the
observed failure compared `[1, 2, 3, 4, 5]` against `[1, 2, 3, 4, 5, 151]`, a film id far outside the
seeded range. When this lands the case reads only rows it owns, so a green build means what it says
rather than meaning no mutation case happened to run in the window.

The fix is the case's to make and not the tier's: this is one assertion comparing two whole-table
reads, and the tier's shared database is deliberate. Narrowing what the case selects, or scoping it to
the films it seeds, is the shape; widening the fixture's isolation is not.

Filed in passing while implementing another item, on a failure seen once in a full verification build
and not reproduced on a re-run of the class alone, which is what a race between parallel classes looks
like from the outside.
