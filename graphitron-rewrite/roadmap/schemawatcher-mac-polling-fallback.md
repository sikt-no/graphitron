---
id: R198
title: SchemaWatcher on macOS is polling-only; lift FS-bound tests to synthetic dispatch
status: In Review
bucket: bug
priority: 15
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# SchemaWatcher on macOS is polling-only; lift FS-bound tests to synthetic dispatch

`mvn install -Plocal-db` on macOS fails five tests in `SchemaWatcherTest` and
one in `CatalogRefreshTest`. The cause is JDK behaviour, not a bug in the
watcher: `FileSystems.getDefault().newWatchService()` returns
`PollingWatchService` on macOS (no kqueue/FSEvents backend exists), with a
hardcoded 10 s period since `SensitivityWatchEventModifier` was removed in
JDK 21. The tests wait `DEBOUNCE_MS + 1500` ≈ 1.6 s, so the poller never gets
a chance to fire. Linux uses inotify and the same tests pass in milliseconds.

A simple `@DisabledOnOs(MAC)` annotation was the first reach, but that
preserves a tier mismatch the failing tests already had: the assertions they
make ("suffix filter fires", "OVERFLOW reschedules", "directory creation
triggers re-registration") are unit-tier invariants on `SchemaWatcher.dispatch`,
delivered through a real filesystem and a sleep. The surviving tests
(`dispatch_triggersOnDotGraphql_whenConfigured`, `overflowEvent_firesCallback`,
`addRootRacesWithDispatch_bothRegistrationsLand`, etc.) already drive
synthetic `WatchEvent` values directly into `dispatch(...)`. The failing
tests are the same logic dressed up as an integration test of the JDK's
WatchService. Right move is to lift them onto the same synthetic-dispatch
shape, not gate them on OS.

Two further tests pass on macOS only by coincidence: `nonGraphqlsFile_noCallback`
and `graphqlsWriteDoesNotFireClasspathWatcher` both assert "no callback within
1.6 s" against a real filesystem. On macOS 1.6 s is shorter than the poll
period, so the assertion is vacuously true. On Linux they pass because the
event fires and `dispatch(...)` rejects it. Two different invariants, one
masquerading as the other.

## Plan

All six steps shipped together (single In Progress commit). Summary:

1. **Lifted `SchemaWatcherTest` FS-bound cases to synthetic dispatch.**
   `modifyingGraphqlsFile_firesCallback`, `deletingGraphqlsFile_firesCallback`,
   `rapidWrites_firesCallbackOnce`, `newSubdirectory_isRegisteredAndFiresCallback`
   now drive synthetic `WatchEvent` values directly into
   `SchemaWatcher.dispatch(...)`. Added `entryDeleteEvent(Path)` helper alongside
   the existing `entryCreateEvent` / `entryModifyEvent` / `overflowEvent`. The
   `newSubdirectory` case retains a real `Files.createDirectory` (so the
   dispatcher's `Files.isDirectory` branch resolves) but injects the events
   synthetically.

2. **Lifted `CatalogRefreshTest.classFileWriteRefreshesWorkspaceCatalog`.**
   Synthetic `ENTRY_CREATE` for `Tables.class`; the workspace-catalog-swap
   assertion stands unchanged.

3. **Folded `graphqlsWriteDoesNotFireClasspathWatcher`** to synthetic
   `ENTRY_MODIFY("schema.graphqls")` on a `.class`-configured watcher. Stayed
   in `CatalogRefreshTest` to keep classpath-watcher coverage adjacent to the
   catalog-rebuilder integration. Deleted `nonGraphqlsFile_noCallback` ;
   duplicate of `dispatch_ignoresUnconfiguredSuffix`.

4. **Kept `writingGraphqlsFile_firesCallback` as the Linux-only inotify
   smoke** (`@EnabledOnOs(OS.LINUX)`), with a javadoc pointing to the
   synthetic cross-platform coverage.

5. **Added `watchServiceBackend_matchesExpectedPerOs` probe** in
   `SchemaWatcherTest`. Asserts `PollingWatchService` on macOS,
   `LinuxWatchService` on Linux; silent on other OSes (R89's surface).

6. **Runtime hint** wired on the first iteration of `SchemaWatcher.run()`:
   two `LOGGER.info` lines when `watchService.getClass().getSimpleName()`
   contains `"Polling"` ; one stating the JDK fact (latency ≈ 10 s), one
   recommending the LSP for event-driven regen. Fires once per watcher
   lifetime in production; silent in synthetic-dispatch tests that do not
   start the loop.

Fork resolved during implementation, then revised on self-review:
`SchemaWatcher.dispatch` stays package-private. A test-only
`DispatchTestSupport` class under `src/test/java/.../maven/watch/` exposes a
typed bridge (`dispatch(SchemaWatcher, Path, WatchEvent<?>)`) that any
package can call; `CatalogRefreshTest` in `..maven.dev` routes through it.
Keeps the "production callers go through `run()`" invariant at the type
system rather than the comment level, and matches the existing
`watchedDirs()` precedent in the same class.

Also revised on self-review: the polling-detection log emit moved from the
constructor to the first iteration of `run()`, so it fires once per watcher
lifetime in production rather than per construction in tests. The message
was split into two lines (JDK fact, LSP recommendation) so the JDK fact
stands alone if the LSP recommendation is later rephrased.

## Out of scope

- Swapping the WatchService backend to `io.methvin:directory-watcher` or a
  similar native FSEvents library. That is a real option but adds JNA to the
  plugin's classpath; size it separately. The runtime hint in step 6 makes
  the gap visible without requiring it.
- The LSP-driven regen path itself. That is R197. R198 does not depend on
  R197 landing first; the runtime hint mentions the LSP path as guidance,
  not as a load-bearing reference.
- Linux aarch64 / Windows verification. R89 owns that surface.

## Test count after this lands

- `SchemaWatcherTest`: 11 today, 11 after (5 lifted to synthetic, 1 deleted,
  1 new backend-probe). Linux-only count: 1 (the kept FS smoke test).
- `CatalogRefreshTest`: 2 today, 2 after (1 lifted, 1 folded into a
  synthetic suffix-filter assertion).
- Mac CI when it lands: cross-platform tests cover every `SchemaWatcher`
  invariant the suite asserted before; the kept Linux-only smoke skips on
  Mac with `@EnabledOnOs(LINUX)`; the backend-probe ratchet runs everywhere.
