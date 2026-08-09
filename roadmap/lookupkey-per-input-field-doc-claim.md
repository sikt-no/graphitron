---
id: R614
title: "lookupKey.adoc claims a per-input-field shape the Query side rejects"
status: Backlog
bucket: docs
priority: 4
theme: docs
depends-on: []
created: 2026-08-09
last-updated: 2026-08-09
---

# lookupKey.adoc claims a per-input-field shape the Query side rejects

`docs/manual/reference/directives/lookupKey.adoc`'s Constraints list states: "`@lookupKey` on an
individual input field applies only to that field; the rest of the input behaves normally." On the
Query side that shape is rejected outright. A `@table` input argument whose leaf fields carry
`@lookupKey` individually classifies as `UnclassifiedField` with a `DirectiveConflict` reading
"@lookupKey on a mutation input field is no longer supported; remove it ... On Query-side @table
input args, move @lookupKey to the surrounding ARGUMENT_DEFINITION instead". The preceding sentence
in the same bullet is correct and measured: `@lookupKey` on the argument does promote every leaf
scalar of the input to a key, and all of them ride the VALUES join.

So the page documents a per-field granularity the generator does not offer at that location, and
the rejection an author hits instead is phrased for the mutation case, which reads as a non-sequitur
on a Query field. Decide whether the fix is documentation only (state the argument-level rule and
drop the per-field sentence, or scope it explicitly to the mutation location where it still holds)
or whether the rejection message also wants a Query-side phrasing. Surfaced while measuring the
lookup neighbourhood for R613; not that item's to fix, and filed rather than folded in so R613's
scope stays the filter axis.
