---
id: R456
title: "Guard WorkspaceFile source/tree reads against concurrent didChange edit/swap/close (use-after-free and torn reads)"
status: In Progress
bucket: bug
priority: 1
theme: lsp
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Guard WorkspaceFile source/tree reads against concurrent didChange edit/swap/close (use-after-free and torn reads)

## Symptom

The LSP hands the live, mutable `WorkspaceFile` out of its lock and lets request handlers walk its tree-sitter tree and read its source bytes on pool threads while `didChange` concurrently edits the byte array, swaps the tree, and `close()`s the old native tree on the dispatch thread. A user typing while hover/completion/codeAction/definition/inlayHint requests are in flight can hit: a walk of a **freed native tree** (arena `IllegalStateException` / use-after-free killing the request), a **torn read** pairing a stale `source` byte array with a new tree (or vice versa) so `Nodes.text` extracts garbage and hover/diagnostic ranges are wrong, and worst, a `WorkspaceEdit` computed against mismatched offsets that the client applies and **corrupts the user's schema file**.

## Mechanics

- `Workspace.get(uri)` (`Workspace.java:114-118`) `synchronized (lock)` only around the `files.get` map lookup, then returns the live `WorkspaceFile`. Callers read it off-lock.
- `WorkspaceFile.tree()` / `source()` (`WorkspaceFile.java:55-60`) are plain getters over plain, non-`volatile`, non-synchronized fields `private byte[] source` / `private Tree tree` (`:41-42`).
- All five request handlers run their bodies on `ForkJoinPool.commonPool` via `CompletableFuture.supplyAsync` (`GraphitronTextDocumentService.java`: codeAction :127, definition :132, inlayHint :151, hover :161, completion :176) and read `file.source()` / `file.tree()` there. Completion reads source at :182, tree at :185, and source again later, so it can even tear against *itself* across an interleaved edit.
- On the LSP dispatch thread, `applyEdit` / `setContent` mutate `this.source` (`:100` / `:117`), swap `this.tree` (`:102` / `:119`), and `previous.close()` the old native tree (`:104` / `:120`). Nothing synchronizes the reader against this writer.

## Design: copy-on-read snapshots, scoped by the workspace

Two facts make a cheap, fully consistent copy-on-read design available:

1. **The `byte[] source` is never mutated in place.** Both `applyEdit` and `replaceContent` build a fresh array and reassign the field, so a published array is immutable; the race is only about which (`source`, `tree`, `version`) triple a reader pairs up.
2. **jtreesitter `Tree` has a public `clone()`** wrapping tree-sitter's `ts_tree_copy`, which is the officially documented way to use a syntax tree on more than one thread: a cheap refcounted copy that stays valid after the original is closed and is closed independently.

So: each read takes an immutable per-file snapshot under the existing `Workspace` lock, with a *cloned* tree whose native lifetime is independent of the live file's. The eager `previous.close()` in `applyEdit` / `replaceContent` stays exactly as it is (preserving the prompt-release performance contract in `WorkspaceFile`'s javadoc), because no reader ever holds the original tree. `tree.edit(...)` mutating the live tree in place is likewise safe: it happens under the lock, and readers only walk clones taken under that same lock.

### The read surface

- **New type `FileSnapshot`** (in `lsp/state`, final, immutable): carries `tree()` (the clone), `source()`, and `version()`. It implements `AutoCloseable`; `close()` closes the cloned tree. It deliberately does *not* carry `declaredTypes()` / `dependsOnDeclarations()`: those are consumed only by `Workspace`'s own mutators under the lock (`enqueueTouched`), never by an off-thread reader. Add them later only when a concrete off-thread reader appears.
- **`Workspace` exposes lambda-scoped accessors, not the snapshot's lifetime:**
  - `workspace.withView(uri, view -> ...)`: takes the lock, snapshots the one file (or short-circuits to an absent result if the URI is not open), releases the lock, runs the lambda, and closes the clone in `finally`.
  - `workspace.withAllViews(views -> ...)`: same, but snapshots *every* open file under one lock acquisition and hands the lambda an ordered `Map<String, FileSnapshot>`. This is what the cross-document paths use, so a composed `WorkspaceEdit` is computed against one consistent generation of the whole workspace, not a per-file mix.

  The lambda-scoped shape is load-bearing: jtreesitter registers no `Cleaner`, so an unclosed clone leaks native memory until process exit. Returning an `AutoCloseable` would create a per-call-site close obligation with no enforcer; scoping the resource inside `Workspace` makes leak-by-omission structurally impossible and keeps the native lifecycle in the imperative shell. Exact signatures (return value vs. `Optional`, absent-file handling) are the implementer's call.
- **The live `WorkspaceFile` stops escaping `Workspace`.** `Workspace.get(uri)` (the off-lock live handout) is removed from the public surface; anything `Workspace` needs internally reads the file under its own lock. `FileSnapshot` stays a distinct type from `WorkspaceFile`, with no shared read interface: the type itself is what guarantees "safe to read off the dispatch thread", and a shared interface would let a call site bind the mutable instance again without the compiler objecting.
- **Memory visibility is by the lock, not `volatile`:** snapshots are taken under the same `lock` the mutators already run under (`enqueueAndNotify`), which gives the happens-before edge for the plain fields. No field-level changes to `WorkspaceFile` are needed beyond adding a `snapshot()` method (clone tree, capture fields) that `Workspace` calls while holding the lock.

### Reader migration

All paths that today read the live file move to views; feature `compute()` signatures that take `WorkspaceFile` switch to `FileSnapshot` (mechanical; accessor names match):

- The five async handlers in `GraphitronTextDocumentService` (codeAction, definition, inlayHint, hover, completion) wrap their `supplyAsync` bodies in `withView` / `withAllViews`. This also fixes completion's self-tear (it reads `source` at the top and again inside `Completions.at`).
- `publishDiagnosticsForRecalculate` (which can run on the DevMojo watcher thread via `markAllForRecalculation`) snapshots per drained URI via `withView`.
- `CodeActions.compute` uses `withAllViews` for the whole request: the cursor file's per-site and file-bulk actions and the workspace-bulk action all read the same generation. `IntraSchemaDefinitions.compute` (iterates `openUris()` + per-file `get`) likewise moves to `withAllViews`.
- Tests that construct `WorkspaceFile` directly keep doing so and call `snapshot()` where the migrated signatures require it (single-threaded, so direct use stays fine); a test must close what it opens or go through a small shared helper.

### Alternatives rejected

- *(a) Immutable `WorkspaceFile` swapped behind a `volatile` per `didChange`:* needs a home for the mutable reused `Parser` (the incremental-parse perf contract), and old-tree lifetime still needs refcounting or a `Cleaner` (violating the prompt-release contract) or clone-on-construct anyway. More churn, same result.
- *(c) Serializing requests against edits per document:* holds the lock across potentially slow feature computation, kills request concurrency, and still leaves the eager-close vs. in-flight-walk problem unless the whole walk finishes under the lock.

## Tests

The safety property needs a named enforcer, not just correct-by-construction code, because R347 Slice 5's `didClose` `close()` lands on the same lifecycle and could silently regress it. New tests live in `graphitron-lsp`'s module test suite (plain JUnit; the generator's four-tier taxonomy does not cover LSP concurrency, and these need no catalog):

1. **Snapshot survives edit + eager close (the enforcer):** take a snapshot of a file, apply a `didChange`-driven `applyEdit` (which closes the previous tree), then walk the snapshot's tree and extract text via `Nodes.text` against the snapshot's source; assert the walk succeeds, the extracted text matches the *pre-edit* content, and `version()` is the pre-edit version. Repeat with `replaceContent`. This pins both "clone outlives original close" and "triple is internally consistent".
2. **Snapshot close is independent:** close the snapshot, then keep editing and reading the live file; assert no interference either way.
3. **Consistent multi-file generation:** with two files open, take `withAllViews` and assert both snapshots reflect the same generation (edit one file after the views are taken; the views must not see it).
4. Existing handler/feature tests keep passing after the signature migration; they are the behaviour oracle for the mechanical part.

A racing stress test (edit loop vs. read loop) is optional and non-blocking; the deterministic tests above pin the invariant.

## Relationship to R347 and landing order

R347 (`lsp-structural-consolidation`, In Progress) Slice 5 addresses two *different* Workspace concurrency issues: the **build-output torn read** (`catalog`/`catalogFacts`/`snapshot`/… read non-atomically; fixed by bundling into one immutable `BuildOutput` behind a single `volatile`) and a **native-memory leak** (`WorkspaceFile` `Tree`/`Parser` not freed on `didClose`; fixed by adding `close()`). Neither touches the per-`WorkspaceFile` `source`/`tree` **read-vs-`didChange` race** described here, which is a live-access correctness/use-after-free bug on different fields.

**Scoping decision: R456 stays standalone.** R347 is already In Progress with a large scope; this item has its own behavioural test surface and a user-facing severity that should not queue behind a consolidation grab-bag.

**Ordering constraint:** R347's `didClose` `close()` (freeing the file's current `Tree` and `Parser`) must land *on top of* this design, where it is safe by construction: closing the live tree cannot invalidate any snapshot (clones have independent native lifetime), and readers never touch the `Parser`. If it lands first, it adds a second use-after-free path against in-flight readers. Test 1 above is the mechanical enforcer that keeps this true regardless of which item's commits move first; R347's implementer should be pointed at this section when picking up Slice 5.

## Scope

In scope: the `FileSnapshot` type, the `withView` / `withAllViews` accessors, removal of the public live-file handout, migration of all reader call sites and the affected feature `compute()` signatures, and the tests above. All under `graphitron-lsp/`. No user-visible surface (no protocol, goal, or directive change), so no user-doc draft is required.

Out of scope: R347 Slice 5's `BuildOutput` bundling (different fields, different bug), the `didClose` `close()` itself (R347's; only its safety precondition is established here), any change to mutator behaviour or the incremental-parse/eager-close performance contract, and the MCP server (it reads build-output fields, not `WorkspaceFile`s).

## Lineage

Confirmed high severity by the architecture-trap audit (mechanics verified: off-lock live-object handout, non-volatile fields, ForkJoinPool read sites, and the dispatch-thread edit/swap/close all confirmed in code). Highest user-facing blast radius of the audit's findings because a mis-offset `WorkspaceEdit` can corrupt the user's file. Design reviewed against the development principles via the principles-architect consult at Spec drafting; the lambda-scoped view accessors (rather than returned `AutoCloseable`s) and the distinct-type read surface came out of that consult.
