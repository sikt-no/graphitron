---
id: R198
title: SchemaWatcher on macOS is polling-only; lift FS-bound tests to synthetic dispatch
status: Ready
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

1. **Lift the five `SchemaWatcherTest` FS-bound tests onto synthetic dispatch.**
   - `writingGraphqlsFile_firesCallback` → fires `entryCreateEvent("schema.graphqls")`.
   - `modifyingGraphqlsFile_firesCallback` → fires `entryModifyEvent("schema.graphqls")`.
   - `deletingGraphqlsFile_firesCallback` → fires `entryDeleteEvent("schema.graphqls")` (new helper alongside `entryCreateEvent` / `entryModifyEvent`).
   - `rapidWrites_firesCallbackOnce` → three synthetic dispatches; assert exactly one trigger; the debounce window is `DebounceExecutor`'s contract, not `SchemaWatcher`'s, but the wire-up that `SchemaWatcher` calls `debounce.schedule(onTrigger)` exactly once per matching suffix on three closely-spaced events is the invariant.
   - `newSubdirectory_isRegisteredAndFiresCallback` → `Files.createDirectory` (real) + synthetic `ENTRY_CREATE` for that directory + a second synthetic `ENTRY_MODIFY` for a file beneath it; assert callback and `watchedDirs()` contains the new directory.

2. **Lift `CatalogRefreshTest.classFileWriteRefreshesWorkspaceCatalog`.** Same
   transformation: synthetic `ENTRY_CREATE` for `Tables.class`, the
   `rebuilder` callback runs, then the workspace-catalog-swap assertion stands.

3. **Delete or fold the green-by-accident tests.**
   - `nonGraphqlsFile_noCallback` duplicates the surviving
     `dispatch_ignoresUnconfiguredSuffix` (synthetic, cross-platform,
     deterministic). Delete.
   - `graphqlsWriteDoesNotFireClasspathWatcher` is `dispatch_ignoresUnconfiguredSuffix`
     parameterised on the classpath-watcher's `.class` suffix; fold into the
     synthetic shape (fire `ENTRY_MODIFY("schema.graphqls")` on a watcher
     configured for `.class`, assert callback count is zero, no sleep needed).

4. **Keep one real-FS integration smoke test, Linux-only by design.** Tag a
   single representative case (likely the lifted
   `writingGraphqlsFile_firesCallback`) with `@EnabledOnOs(OS.LINUX)` and a
   javadoc that says, in one sentence, "this is the inotify integration; the
   logic is covered cross-platform by the synthetic-dispatch tests." Honest
   about what it asserts.

5. **Backend-probe ratchet.** Add a single test that calls
   `FileSystems.getDefault().newWatchService().getClass().getSimpleName()`,
   asserts it equals `"PollingWatchService"` on macOS, fails otherwise. If a
   future JDK ships an FSEvents-backed WatchService, this test fails loudly
   and R198's assumptions get revisited. The invariant the design depends on
   is "Mac's WatchService is polling-only"; encode that, don't encode "we
   ran the suite on Mac and gave up". Pair: assert it equals
   `"LinuxWatchService"` (or the JDK's current name; check in implementation)
   on Linux as a sanity ratchet.

6. **Runtime hint instead of (or in addition to) class javadoc.** At
   `SchemaWatcher` construction time, if `watchService.getClass().getSimpleName()`
   contains `"Polling"`, emit one `LOGGER.info` line: "polling
   WatchService detected; schema file change latency ≈ 10 s. Connect an
   editor with the Graphitron LSP for event-driven regen." This puts the
   surprise where it's felt (developer terminal at dev-mojo startup), not in
   a javadoc only contributors read. Class javadoc still gets a one-line
   note for contributors, but the audience-correct hint is at runtime.

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
