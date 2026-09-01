---
id: R813
title: "The derive package names three jobs and describes none of them"
status: Backlog
bucket: codegen
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# The derive package names three jobs and describes none of them

`no.sikt.graphitron.rewrite.derive` holds three unrelated jobs under a name that describes none
of them, and the name is an attractor for exactly the mistake the rewrite is trying to avoid. Call a
package `derive` and the next contributor with a derivation to place will put it there, when the
standing rule is that a fact about the schema belongs in the DDL and Java holds only query and
rendering.

Nothing currently in the package breaks that rule. The problem is entirely the label.

## What is actually in there

Classified by what each file does, over 17 files:

[cols="3,1,5"]
|===
| Job | Lines | What it is

| Verdict to prose
| 1078
| A view carries a closed `verdict` vocabulary; these turn a row into a located rejection with its
  English text. `ArgmappingProjectionDefects`, `AuthoredClaimConflicts`, `NodeIdDecodeDefects`,
  `StoreDetections`.

| Store read to value
| 420
| Read relations and assemble a value object for the plan tier. `StoreNodeTables` builds a
  `TableRef` from `sql_table`, `sql_column` and `sql_primary_key`. `ResolvedKeyProjections`,
  `NodeIdMessages`.

| Capture-cadence fixpoint writers
| 392
| Fill a materialized relation by iterating a closure to a fixed point. `TypeBackingRows`,
  `InputOccurrencePaths`, `AuthoredClaimRejectionRows`.

| Support records
| 351
| Types the above pass around. `AuthoredClaim`, `ClaimDomain`, `ClassifiedRun`, `DemandResidue`,
  `FieldClaim`, `TypeBackingClasses`.
|===

The largest file is 439 lines of which 187 are comments, and its only branching is a switch over the
view's own verdict plus a helper choosing "a" or "an" by first letter. That is rendering, and it is
where rendering belongs.

## Why two of the three are permanent

A reader could take "derive shrinks" to mean the package eventually empties. It will not.

Projecting a verdict into a message is rendering. The prose is not a captured fact of any graph, and
a relation has no business holding an author-facing sentence, so this job stays in Java however far
the fact model goes.

A fixpoint writer exists because its closure runs over a cyclic graph and H2 has no safe recursive
view form for one. The rule stays in the joins; what is in Java is the loop and its termination.
That is a database limitation, not a design preference, and it does not go away.

The third job, the store reads, is the one that leaves, and where it goes is already settled
elsewhere: a producer's own run-scoped query lives beside the producer, and schema-grain fact
assembly becomes views.

## What this item does

Split by job and rename, so each package says what its files are for. The obvious shape is a home
for the fixpoint writers and a home for the message projection, with the store reads leaving under
their own item. The naming is the deliverable; nothing about behaviour changes.

One question to settle while splitting: the fixpoint writers are the only code that can fill their
relations, and those relations are declared in `graphitron-model`. Right now the DDL and its writer
live in different modules, which is how a writer ends up somewhere nobody looks for it. Whether the
writers should move to the module that owns the schema is this item's to decide, and it is the part
with real consequences beyond the label.

## Why it is worth doing

A package name is the cheapest piece of documentation in a codebase and the one every contributor
reads first. This one currently misdirects, and it misdirects toward the specific error the rewrite
exists to eliminate. The fix costs a rename and a file move.
