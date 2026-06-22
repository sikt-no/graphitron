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

3. **Hover / `description` onto the source cadence (was R352 part 2).** `CatalogBuilder` no
   longer walks sources at all; the catalog's `description` carries only the build-derivable
   fallback (the jOOQ table's SQL comment; empty for columns and services). A `Descriptions`
   helper overlays the source-derived Javadoc from the LSP-owned index at request time, and
   `Hovers` plus the `FieldCompletions` / `TableCompletions` detail read through it. Hover and
   goto-definition now consult the one index, so they cannot show two snapshots of the same
   declaration mid-edit.

4. **Static `SourceWalker.CACHE` → an instance the LSP owns (was R352 part 3).** With the
   build no longer walking, the LSP is the sole walker. The per-file cache moved from a
   process-wide static onto a `SourceWalker` instance held by `Workspace`, alongside the
   `volatile sourceIndex` it produces; `Workspace.refreshSourceIndex` is the one walk entry
   point, called by the dev goal's source-root watcher. No static state couples the two
   cadences or distinct workspaces.

End-to-end coverage walks real `.java` sources through the real `Workspace` /
`SourceWalker` / `Hovers` / `Definitions` (`SourceCadenceHoverAndDefinitionTest`), asserting
hover and goto move together across a source edit with no catalog rebuild; the build-boundary
decoupling is pinned in `CatalogBuilderSourceTest` (the build does not lift source Javadoc),
and the per-instance cache in `SourceWalkerTest`.
