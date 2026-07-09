---
id: R456
title: "Guard WorkspaceFile source/tree reads against concurrent didChange edit/swap/close (use-after-free and torn reads)"
status: Backlog
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

## Fix direction (for Spec)

The clean fix is to make each `WorkspaceFile` read observe one internally consistent (`source`, `tree`) pair that is never mutated or freed while a reader holds it. Options to weigh at Spec: (a) make `WorkspaceFile` immutable and have `didChange` swap a fresh instance behind one `volatile` reference in `Workspace` (readers keep the instance they got; the old tree is closed only once no reader can hold it — a lifecycle/refcount question), vs. (b) copy-on-read the `(source, tree)` pair under the lock, vs. (c) serialize requests against edits per-document. Note that native `Tree` lifetime interacts with **R347 Slice 5**, which adds `WorkspaceFile.close()` on `didClose`: whichever option wins must define when the native tree is safe to free relative to in-flight readers, or `didClose` becomes a second use-after-free path.

## Relationship to R347

R347 (`lsp-structural-consolidation`, In Progress) Slice 5 addresses two *different* Workspace concurrency issues: the **build-output torn read** (`catalog`/`catalogFacts`/`snapshot`/… read non-atomically; fixed by bundling into one immutable `BuildOutput` behind a single `volatile`) and a **native-memory leak** (`WorkspaceFile` `Tree`/`Parser` not freed on `didClose`; fixed by adding `close()`). Neither touches the per-`WorkspaceFile` `source`/`tree` **read-vs-`didChange` race** described here, which is a live-access correctness/use-after-free bug on different fields. It is a natural candidate to fold into R347's concurrency slice, but is filed standalone so it is not lost; that scoping is a Spec decision. This item is also a prerequisite consideration for R347's `didClose` `close()`, per the note above.

Confirmed high severity by the architecture-trap audit (mechanics verified: off-lock live-object handout, non-volatile fields, ForkJoinPool read sites, and the dispatch-thread edit/swap/close all confirmed in code). Highest user-facing blast radius of the audit's findings because a mis-offset `WorkspaceEdit` can corrupt the user's file.
