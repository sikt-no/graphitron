# Plan: `graphitron-rewrite:watch` goal

> **Status:** In Review
>
> Adds a `watch` goal to `graphitron-rewrite-maven` (the rewrite-owned
> Maven plugin). Both prerequisites are in place on trunk:
> `AbstractRewriteMojo` / `RewriteContext` landed with the
> rewrite-maven-plugin work, and content-idempotent writes (SHA-256
> short-circuit in `JavaFile.writeToPath`, orphan sweep in
> `GraphQLRewriteGenerator`) landed alongside the dev-loop docs (see
> [`changelog.md`](changelog.md)).
>
> Without idempotent writes a watch trigger would rewrite the full
> output tree on every save, defeating the purpose. With them, the
> trigger cost is proportional to the diff: unchanged files keep
> their mtimes and the IDE recompiles only the types the schema edit
> actually touched.

## Goal

Add a `graphitron:watch` goal to `graphitron-rewrite-maven` that
watches the configured `<schemaInputs>` directories for `.graphqls`
changes and re-runs the rewrite generator on each trigger. The goal
blocks the Maven process (like `quarkus:dev`), debounces rapid saves,
and prints schema validation errors without exiting the watch loop.

Zero new compile dependencies. `java.nio.file.WatchService` is a JDK
built-in (JDK 7+).

## Motivation

The content-idempotent-writes plan ships the three-clause generator
contract: determinism, minimal-change writes, and clean removal. That
contract makes a watch loop genuinely valuable: a `.graphqls` save
triggers regeneration, only the changed files are written, and the IDE
recompiles only the affected classes. Without the idempotent-writes
prerequisite, this plan would be wasteful; with it, the watch loop
becomes a natural complement to the dev-loop guarantee already
documented in `getting-started.md`.

The `getting-started.md` `## Dev loop` section (landed with
idempotent-writes) currently states that Graphitron ships no watch
goal and points consumers at `mvn generate-sources` plus their own
file-watcher wiring. This plan closes that gap with a first-party
solution and replaces the "no watch goal" disclaimer with a
`### Watch mode` subsection.

## Scope boundaries

**In scope**

- A `WatchMojo` class in `graphitron-rewrite-maven` bound to no
  lifecycle phase, invoked as `mvn graphitron:watch`.
- `WatchService`-based directory monitoring scoped to the directories
  that contain the resolved `<schemaInputs>` files.
- Debounce via `ScheduledExecutorService` (configurable window,
  default 300 ms).
- Recursive directory registration at startup (via `Files.walk`
  over each watched root) plus on-the-fly registration of newly
  created subdirectories discovered via `ENTRY_CREATE`.
- Error recovery: schema validation failures and `IOException` on
  re-generation are caught, printed with `getLog().error(...)`, and
  the watch loop resumes.
- One run of the generator on startup before the watch loop begins,
  so the output tree is fresh when the loop starts.
- `-Dgraphitron.watch.skipInitial=true` skip flag for consumers
  whose build already ran `generate-sources` in the same session.

**Out of scope**

- Watching jOOQ-generated classes. The generator reads jOOQ classes
  as compiled classpath entries; if jOOQ regenerates, the consumer
  restarts the watch session.
- Watching the generator's own JAR or plugin configuration. Config
  changes require a restart; this is standard Maven behaviour.
- Parallel watch sessions or multi-module aggregation. The Mojo runs
  per-module; consumers with multi-module schemas use the standard
  Maven reactor and restart if the module layout changes.
- IDE plugin or LSP integration. The watch goal is a terminal process;
  IDE incremental compilation handles the generated-sources side
  without Graphitron-specific tooling.

## Design

### Mojo structure

```
graphitron-rewrite-maven/
  src/main/java/no/sikt/graphitron/rewrite/maven/
    WatchMojo.java          -- @Mojo(name = "watch",
                                      requiresDependencyResolution = COMPILE,
                                      threadSafe = true)
    watch/
      SchemaWatcher.java    -- WatchService registration + event loop
      DebounceExecutor.java -- ScheduledExecutorService debounce helper
```

`WatchMojo` extends `AbstractRewriteMojo` (same superclass as
`GenerateMojo` / `ValidateMojo`). `packagesRequired()` returns `true`,
matching `GenerateMojo`, because the watch loop performs real output
writes. Context construction reuses the superclass's
`protected final RewriteContext buildContext()` helper; the
`protected final runGenerator(GeneratorCall)` helper is deliberately
**not** reused because it wraps `RuntimeException` into
`MojoExecutionException`, and the watch loop needs to catch and
resume rather than abort the Maven process.

The Mojo:

1. Calls `buildContext()` once to validate plugin configuration (a
   misconfigured `<schemaInputs>` or missing required package is a
   fatal `MojoExecutionException`, not a watch-loop event).
2. Unless `skipInitial` is set, runs `new GraphQLRewriteGenerator(ctx).generate()` once.
3. Resolves the watch directory set: for each `SchemaInput` in
   `ctx.schemaInputs()`, take `Paths.get(input.sourceName()).getParent()`
   and deduplicate.
4. Installs a JVM shutdown hook that closes the `SchemaWatcher`
   (which in turn closes the `WatchService` and shuts down the
   `DebounceExecutor`).
5. Starts `SchemaWatcher` with that directory set and a callback that
   re-invokes `buildContext()` (to re-expand `<schemaInputs>` globs
   and pick up new files) and then runs the generator.
6. Blocks on `SchemaWatcher.run()` until the shutdown hook fires.

### `SchemaWatcher`

Wraps `FileSystems.getDefault().newWatchService()`. On construction:

- For each directory in the watch set, walks it via `Files.walk(dir)`
  and registers every directory (root + all existing subdirectories)
  for `ENTRY_CREATE`, `ENTRY_MODIFY`, `ENTRY_DELETE`.
  Always walking recursively is simpler than inspecting raw
  `SchemaInputBinding` glob patterns for `**` markers, and costs
  nothing on a flat schema directory (one registration, zero extra
  walks).
- Any runtime `ENTRY_CREATE` event that resolves to a directory is
  registered on the fly so newly-created subtrees are watched.

Event loop:

```
loop:
  key = watchService.take()            // blocks
  for event in key.pollEvents():
    if event.context() ends with .graphqls:
      debounce.schedule(triggerCallback)
  key.reset()
  if !key.isValid(): remove from registry
```

Non-`.graphqls` events (build artifacts, IDE temp files) are silently
ignored. `OVERFLOW` events reschedule a trigger so a burst of events
does not silently skip regeneration.

### `DebounceExecutor`

A `ScheduledExecutorService` with one thread. Each incoming event
cancels any pending task and schedules a new one 300 ms in the future
(configurable via `-Dgraphitron.watch.debounceMs`). When the task
fires, it:

1. Calls `buildContext()` on the Mojo (re-expands `<schemaInputs>`
   globs via `SchemaInputExpander`, producing a fresh
   `RewriteContext`).
2. Calls `new GraphQLRewriteGenerator(ctx).generate()`.
3. Catches `RuntimeException` and `MojoExecutionException`; prints
   via `getLog().error(...)`; resumes. Validation errors are already
   surfaced via `getLog().error(...)` inside the generator's
   `validateAndLogErrors` helper, so the catch block handles only
   structural failures (I/O, config drift).
4. Diffs the new watch-directory set against the current
   `SchemaWatcher` registry and registers any directories added by
   new matches.

### Thread safety

`GraphQLRewriteGenerator` is constructed fresh on each trigger with
its own `RewriteContext` instance; confirmed free of static/singleton
state in the current tree. The debounce executor's single thread
serialises triggers so two generator runs cannot overlap. The
`WatchService` loop runs on its own thread and hands events to the
debounce executor via the single `schedule(...)` seam.

On shutdown (SIGINT/SIGTERM → JVM shutdown hook): the hook closes
the `WatchService` (causing the event-loop thread's `take()` to
throw `ClosedWatchServiceException`, which it treats as graceful
exit) and calls `shutdownNow()` on the debounce executor. A
best-effort `awaitTermination(1, SECONDS)` gives any in-flight
generator run a chance to finish its current file write.

### Parameters

| Parameter | XML element | System property | Default |
|---|---|---|---|
| Inherited from `AbstractRewriteMojo` | `<schemaInputs>`, `<outputDirectory>`, `<outputPackage>`, `<jooqPackage>`, `<namedReferences>` | (same as `generate`) | (same as `generate`) |
| Skip initial run | (intentionally undocumented) | `graphitron.watch.skipInitial` | `false` |
| Debounce window | (intentionally undocumented) | `graphitron.watch.debounceMs` | `300` |

`skipInitial` and `debounceMs` are declared as
`@Parameter(property = "graphitron.watch.skipInitial")` /
`@Parameter(property = "graphitron.watch.debounceMs")` so Maven can
inject `-D` overrides, but they are omitted from `getting-started.md`
and the plugin README: they are developer-session knobs, not project
configuration. (Maven doesn't offer a "`-D` only, no XML" mode; the
convention is to document them only as system properties.)

## Tests

Two tiers, matching the unit / pipeline split.

**Unit: `SchemaWatcherTest`** (new, in `graphitron-rewrite-maven`
under `src/test`).

- Registers a temp directory, writes a `.graphqls` file, asserts the
  callback fires within debounce window + margin.
- Writes two `.graphqls` files in rapid succession (< debounce window
  apart), asserts the callback fires exactly once.
- Writes a non-`.graphqls` file, asserts no callback.
- Creates a subdirectory and writes a `.graphqls` file into it,
  asserts the callback fires (recursive registration).
- `OVERFLOW` event causes a callback. Since `WatchService` does not
  expose a public knob for triggering `OVERFLOW`, this case is
  tested by posting a synthetic `WatchEvent` of kind
  `StandardWatchEventKinds.OVERFLOW` into `SchemaWatcher`'s event
  dispatch method directly (the dispatch method is package-private
  for this reason).

Tests use `WatchService` against the real filesystem (temp dirs).
Timing-sensitive assertions use `CountDownLatch.await(timeout,
unit)` with a generous margin (e.g. `debounceMs + 500 ms`) rather
than fixed `Thread.sleep`, to avoid the flakiness pattern that
motivated the `IdempotentWriterTest` mtime-backdate fix
(`5176dc2`).

**Unit: `DebounceExecutorTest`** (new, same module).

- Schedule three events 50 ms apart; assert task runs once, 300 ms
  after the last event.
- Schedule one event; cancel before firing (via `close()`); assert no
  task runs.

No pipeline-tier test for the watch loop itself: the pipeline tier
tests the generator contract (already covered by
`GeneratorDeterminismTest` and the mtime-preservation test from the
idempotent-writes plan). The watch loop adds only the
`WatchService`-to-generator wiring, which the unit tests cover.

## Documentation

The current `## Dev loop` section in
`graphitron-rewrite/docs/getting-started.md` contains an explicit
sentence stating "Graphitron ships no watch goal." That sentence is
removed and replaced with a `### Watch mode` subsection:

- Command: `mvn graphitron:watch` (plugin groupId/artifactId same as
  `generate`; the `graphitron` prefix is resolved by the standard
  Maven plugin-prefix mechanism).
- What it does: runs the generator once on startup, then watches
  `<schemaInputs>` directories and re-runs on any `.graphqls` change.
- What the developer observes: only changed files are written; the
  IDE recompiles only the affected classes (same three-clause
  contract as a manual `generate-sources` run, pinned by
  `IdempotentWriterTest` and `GeneratorDeterminismTest`).
- How to stop: Ctrl+C (the JVM shutdown hook closes the
  `WatchService` and the debounce executor).
- Note on jOOQ changes: changing the jOOQ schema requires restarting
  the watch session (the compiled jOOQ classes are not watched).

System-property knobs (`graphitron.watch.skipInitial`,
`graphitron.watch.debounceMs`) are **not** documented in
getting-started.md; they are session overrides for developers
debugging the watch loop itself.

## Rollout

Single-commit landing in `graphitron-rewrite-maven` (both
prerequisites are already on trunk). No consumer migration
required: the goal is additive and opt-in. Consumers who already use
`mvn generate-sources` in a shell loop or IDE file watcher can
migrate at their convenience; both work correctly alongside the
idempotent-writes contract.

## Roadmap integration

Standalone item under Architecture / structural in the roadmap.
Prerequisites are noted in the roadmap entry. On landing, move the
entry to `## Done` with a one-line summary citing the commit sha and
the `SchemaWatcherTest` location.
