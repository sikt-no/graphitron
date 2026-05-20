---
id: R197
title: LSP didSave drives in-process regen trigger
status: Spec
bucket: feature
priority: 10
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# LSP didSave drives in-process regen trigger

`mvn graphitron:dev` runs the schema watcher and `GraphitronLanguageServer` in
the same JVM (`DevMojo` class-level javadoc, lines 29-50; `DevServer.serve()`
constructs one `GraphitronLanguageServer` per editor connection over the same
shared `Workspace`). Today the regen trigger is wired only off `SchemaWatcher`.
On macOS the WatchService backend is polling-only with a 10 s period (see
R198). For users with an editor attached to the LSP, the language server
already receives `didSave` events that are authoritative and sub-millisecond.
Wiring those events into the same debounced regen path the watcher uses
gives every platform an event-driven fast path and makes the WatchService an
honest fallback rather than the primary mechanism on macOS.

Today `GraphitronTextDocumentService.didSave` is a no-op with a comment that
explicitly defers to the filesystem watcher (lines 97-102). This Spec inverts
that deferral.

## Design

The seam is a `Consumer<String> onSchemaSaved` listener at the
`GraphitronLanguageServer` boundary, propagated by `DevServer` from `DevMojo`.
`Workspace` does not change.

```
DevMojo
  ├─ schemaDebounce: DebounceExecutor                (already exists)
  ├─ regenerate(workspace)                           (already exists)
  ├─ schemaSuffixes: Set<String>                     (from ctx.schemaFileExtensions)
  └─ saveListener: Consumer<String> uri ->
       if (matchesSuffix(uri, schemaSuffixes))
         schemaDebounce.schedule(() -> regenerate(workspace))
DevServer
  └─ holds saveListener, passes to each new GraphitronLanguageServer
GraphitronLanguageServer
  └─ holds saveListener; exposes it to GraphitronTextDocumentService
GraphitronTextDocumentService.didSave(params)
  └─ saveListener.accept(params.getTextDocument().getUri())
```

Why this seam and not `Workspace`:

- `Workspace` today owns parsed files, the recalculation queue, and the
  catalog/snapshot/validator triplet. Every existing field is consumed
  *inside* `Workspace`. A regen scheduler is consumed nowhere inside
  `Workspace`; making it a field there would turn `Workspace` into a
  generic event bus for cross-concern wiring.
- The suffix set lives in `RewriteContext`, owned by `DevMojo`. The LSP
  module has no other reason to know what counts as a schema extension;
  putting the filter inside `Workspace` (or even inside the document
  service) pulls extension-set ownership across a module boundary for one
  consumer. Filter at the listener call site instead, in `DevMojo`'s
  lambda.
- `Consumer<String>` (URI-typed) carries the certainty the call site has,
  better than `Runnable` or `Consumer<Runnable>`. The listener is what
  changes shape if URI-aware fan-out is needed later; the contract on the
  language server stays "an editor saved this URI".

Headless LSP use (no `DevMojo`, no dev loop): the language server is
constructed without a listener (or with a no-op `Consumer`). `didSave`
becomes a no-op, matching today's behaviour.

**On-disk vs in-buffer.** Regeneration reads `.graphqls` files from disk
(unchanged from today). `didSave` is treated as a write-completion
notification, not as a hand-off of buffer content. If an editor sends
`didSave` before flushing, the next FS-watcher tick still catches the
change; the debounce window absorbs the gap when both events fire.

**Multi-editor coalescence.** If two editors both save the same file at
the same moment, two `didSave` events flow through and the debounce
coalesces them. If they save different files, both get scheduled; the
debounce coalesces the actual regen runs. This is the existing FS-watcher
behaviour; nothing new.

## Plan

1. **Listener at the language-server boundary.**
   - `GraphitronLanguageServer` gains a `Consumer<String> onSchemaSaved`
     constructor parameter (with a no-arg / `Consumer.identity()`-style
     no-op default to preserve LSP-only-use sites).
   - `GraphitronTextDocumentService` receives the listener from the server
     via its existing constructor wiring; `didSave` becomes:
     ```java
     public void didSave(DidSaveTextDocumentParams params) {
         onSchemaSaved.accept(params.getTextDocument().getUri());
     }
     ```
   - The current comment in `didSave` (lines 99-101) is deleted; it was
     correct under the old design and would mislead now.

2. **`DevServer` propagates the listener.** Add a `Consumer<String>`
   constructor argument; pass it into each new `GraphitronLanguageServer` at
   `DevServer.serve()` line 91.

3. **`DevMojo` constructs the listener.** In `startSchemaWatcher` (around
   line 156), build:
   ```java
   var suffixes = ctx.schemaFileExtensions();
   Consumer<String> saveListener = uri -> {
       if (suffixes.stream().anyMatch(uri::endsWith)) {
           schemaDebounce.schedule(() -> regenerate(workspace));
       }
   };
   ```
   Pass `saveListener` into `new DevServer(...)` alongside the existing
   workspace.

4. **`DevMojo` javadoc.** Update lines 37-39:
   > Watches `<schemaInputs>` for writes matching the configured
   > `<schemaFileExtensions>` and re-runs the generator on every save
   > (debounced). Editor saves via the LSP fire the same trigger
   > directly, bypassing the filesystem watcher's latency on platforms
   > where it polls (see R198).

5. **User-facing docs.** Update `graphitron-rewrite/docs/getting-started.adoc`
   lines 363-417 (and the Mermaid diagram at lines 427-429) to reflect the
   dual-path model: LSP `didSave` is the primary fast path for
   editor-attached users; the WatchService is the headless fallback. One
   sentence each is enough; the existing prose already names the watchers
   explicitly.

## Test tier

Tests live in the module that owns the contract being asserted.

`graphitron-lsp`:

1. `didSave_invokesListenerWithUri`. Construct a `GraphitronLanguageServer`
   with a counting `Consumer<String>` listener, hand the resulting
   `GraphitronTextDocumentService` a synthetic
   `DidSaveTextDocumentParams` with URI `file:///x.graphqls`, assert the
   listener received that exact URI exactly once. No filter; the LSP
   module is suffix-agnostic.
2. `didSave_noopWhenListenerAbsent`. Server constructed via the no-arg /
   no-listener constructor; `didSave` must not throw. Pins the
   LSP-only-use contract.

`graphitron-maven-plugin`:

3. `saveListener_schemaSuffixSchedulesRegen`. Build the listener
   `DevMojo` constructs (factored into a small static method so the test
   doesn't need a full `MavenSession`), feed it a `.graphqls` URI and
   then a `.md` URI; assert the underlying `DebounceExecutor` (or a
   counting stand-in) scheduled exactly once.

The existing `SchemaWatcher` and `CatalogRefresh` tests stay; the watcher
path is independent of the LSP path.

## Out of scope

- **`didChange` regen.** Not a future direction. Save is the user's
  intentional commit point; mid-typing buffers are partial SDL the
  validator will reject. The LSP-driven `didChange` path already updates
  `Workspace` parsed-file state and republishes diagnostics; regen is
  intentionally save-only.
- **Replacing `SchemaWatcher`.** The watcher stays as the headless fallback
  for users running `mvn graphitron:dev` without any editor attached, and
  for the classpath-watcher path which has no LSP analogue (the editor
  doesn't tell the LSP about `.class` writes from a sibling Maven module
  recompile). Swapping the WatchService backend to a native FSEvents
  library is a separate decision tracked under R198's out-of-scope list.
- **Cross-process IPC.** `DevMojo` already hosts the LSP in-process via
  `DevServer`; there is no new network hop. `DevServer`'s loopback socket
  is the editor → LSP transport, not an internal-component channel.

## Risks and follow-ups

- **Editor `didSave` semantics vary.** Some editors batch or delay
  `didSave`. The on-disk-vs-buffer note above pins the contract: regen
  runs against on-disk files, the FS-watcher remains as a backstop, the
  debounce absorbs the gap. No editor-specific code paths.
- **Debounce-window choice.** Default is 300 ms (`DevMojo.debounceMs`);
  same window applies to LSP saves. If a save is immediately followed by
  the editor's own auto-format-on-save round-trip, the second save lands
  inside the window and coalesces, which is the intended behaviour.
