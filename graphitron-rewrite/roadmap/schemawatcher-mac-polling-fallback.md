---
id: R198
title: SchemaWatcher on macOS is polling-only; disable latency-bound tests there
status: Spec
bucket: bug
priority: 15
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# SchemaWatcher on macOS is polling-only; disable latency-bound tests there

`mvn install -Plocal-db` on macOS fails six tests in `SchemaWatcherTest` and one
in `CatalogRefreshTest`. The cause is JDK behaviour, not a bug in the watcher:
`FileSystems.getDefault().newWatchService()` returns the polling implementation
on macOS (no kqueue/FSEvents WatchService backend exists), with a hardcoded 10 s
period since `SensitivityWatchEventModifier` was removed in JDK 21. The tests
wait `DEBOUNCE_MS + 1500` ≈ 1.6 s, so the poller never gets a chance to fire.
Linux uses inotify and the same tests pass in milliseconds. R89 already calls
out that the macOS path is unverified in CI; this item is the tactical fallout
of someone actually running the build on macOS.

## Plan

1. Annotate the six watch-driven tests in
   `graphitron-maven-plugin/src/test/.../SchemaWatcherTest.java` and the one in
   `CatalogRefreshTest.java` with `@DisabledOnOs(OS.MAC)` and a comment
   pointing at R197. The remaining tests in those classes
   (`overflowEvent_firesCallback`, `addRootRacesWithDispatch...`,
   `dispatch_triggersOnDotGraphql_whenConfigured`,
   `dispatch_ignoresUnconfiguredSuffix`, `constructor_emptySuffixSet_rejected`)
   drive `dispatch(...)` synthetically and stay enabled on every platform.
2. Add a class-level javadoc note on `SchemaWatcher` documenting the macOS
   polling fallback (~10 s latency) and the recommended path: R197's
   LSP-driven regen trigger.
3. No production code change. The watcher works on macOS, just slowly; users
   with the LSP attached do not feel it once R197 ships.

## Out of scope

- Swapping the WatchService backend to `io.methvin:directory-watcher` or
  similar native FSEvents library. That is a real option but should be sized
  separately: it adds a dep (JNA), and the LSP-driven path (R197) covers the
  primary user experience for free.
- Linux aarch64 / Windows verification. R89 owns that surface.
