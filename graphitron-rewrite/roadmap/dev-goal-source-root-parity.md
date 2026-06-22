---
id: R351
title: "Complete the LSP goto-definition decoupling: source-root parity + jOOQ half on the source index"
status: In Progress
bucket: bug
priority: 4
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-22
---

# Complete the LSP goto-definition decoupling: source-root parity + jOOQ half on the source index

Closes out the goto-definition story R349 started. R349 typed the service-half
outcome (`DefinitionTarget`) and moved service-half positions onto an LSP-owned,
source-cadence `SourceWalker.Index`. This item finishes the job so jump-to-definition
works for the jOOQ half too and the two halves resolve through one shape. It absorbs
what was filed separately as R352 (jOOQ-half decoupling) rather than leaving a thin
parity fix and a dangling architecture item.

## Delivered

1. **Source-root parity, made structural.** `resolveClasspathRoots` (the scan path) and
   `resolveCompileSourceRoots` (the walk path) in `AbstractRewriteMojo` already iterated
   the same `session.getAllProjects()` set, but as two parallel loops that could drift.
   They now share one traversal (`collectExistingDirs`, package-private and unit-tested
   over hand-built projects), so a class scanned for completion provably has its source
   root walked for goto-definition. A startup diagnostic in `DevMojo` logs the
   classpath-root / source-root / external-reference counts, so the
   "completion works but goto-definition returns nothing" report self-diagnoses.

2. **jOOQ half on the source index.** `CompletionData.Table` / `Column` / `Reference` no
   longer carry a `SourceLocation`; `Table` carries the generated table class FQN and
   `Reference` the `Keys` class FQN. `Definitions` resolves the jOOQ `@table` / `@field` /
   `@reference` arms by joining those FQNs against the LSP-owned `SourceWalker.Index` at
   request time and routes them through the same exhaustive `DefinitionTarget` switch the
   service half uses. The jOOQ goto-definition position now rides the `.java` source
   cadence, exactly mirroring R349, and the file-head (`0:0`) synthesis is retired (a known
   table whose source is not on a walked root lands on `SourceAbsent`, a clean non-jump).
   `CatalogBuilder` stops synthesising jOOQ positions; it still lifts Javadoc into the
   build-cadence `description` slots, as it does for the service half.

## Deferred (was R352 parts 2-3), not blocking jump-to-definition

These do not change whether you can jump; they only remove transient hover-vs-goto skew
during a live edit, and the description-cadence change ripples into the completion path
(jOOQ column comments are not recoverable at runtime, so moving `description` onto the
source index means threading the index through `FieldCompletions` / `Hovers`). Left as
follow-up:

- Hover / `description` onto the source cadence so hover and goto-definition cannot read
  two snapshots of the same `Decl` mid-edit.
- Static `SourceWalker.CACHE` → an instance the LSP owns. Only lands cleanly once the above
  removes the last build-cadence walk from `CatalogBuilder`, leaving the LSP the sole walker.
