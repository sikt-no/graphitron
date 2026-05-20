---
id: R197
title: LSP didSave/didChange drives in-process regen trigger
status: Spec
bucket: feature
priority: 10
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# LSP didSave/didChange drives in-process regen trigger

`mvn graphitron:dev` runs the schema watcher and `GraphitronLanguageServer` in
the same JVM (see `DevMojo` lines 31-35 and `DevServer`). The two reach the same
`Workspace` but the regen trigger is wired only off `SchemaWatcher`, which on
macOS uses the JDK's `PollingWatchService` (10 s polling, no longer tunable
since `SensitivityWatchEventModifier` was removed in JDK 21). For users with an
editor attached to the LSP, the language server already receives `didSave`
events that are authoritative and sub-millisecond. Wiring those events into the
same `Runnable onTrigger` the watcher hands to `DebounceExecutor.schedule(...)`
gives every platform an event-driven regen path and makes the WatchService an
honest fallback rather than the primary on macOS.

## Plan

1. Surface a `Runnable onSchemaTouched` (or similar) on `GraphitronLanguageServer`,
   constructor-injected the same way `DevMojo` already wires `onTrigger` into
   `SchemaWatcher`. `DevServer` propagates it per connection.
2. In the language server's `TextDocumentService` implementation, call
   `onSchemaTouched.run()` from the `didSave` handler. Decide whether `didChange`
   should also fire (with debounce already provided by `DebounceExecutor`); the
   conservative default is `didSave`-only since live-regen on every keystroke is
   a separate UX choice and can be a follow-up.
3. `SchemaWatcher` stays in place as the headless fallback — when no editor is
   attached, no `didSave` arrives, and the polling watcher's 10 s latency is
   tolerable for the no-editor case. Document this expectation in `DevMojo`'s
   javadoc.
4. Test tier: a `PipelineTier`-class test in `graphitron-maven-plugin` that
   constructs a `GraphitronLanguageServer` with a counting trigger, sends a
   synthetic `DidSaveTextDocumentParams`, and asserts the trigger fired. No
   filesystem, no real WatchService, no platform-dependent timing.

## Out of scope

- Live regen on `didChange` (separate UX decision; queue if requested).
- Replacing `SchemaWatcher` entirely or swapping its backend to
  `io.methvin:directory-watcher`. That belongs under R196 if it gets picked up.
- Cross-process IPC. `DevMojo` already hosts the LSP in-process; there is no
  network hop to design.
