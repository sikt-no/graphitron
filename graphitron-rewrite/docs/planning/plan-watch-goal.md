# Plan: `graphitron:watch` goal

> **Status:** Backlog
>
> Adds a `watch` goal to `graphitron-rewrite-maven` (the rewrite-owned
> Maven plugin). Depends on two prerequisites: the Maven plugin plan
> must ship first to provide the clean `RewriteContext` construction
> path, and the content-idempotent-writes plan must ship first so that
> watch-triggered regeneration is cheap (only changed files are
> written; unchanged files keep their mtimes and the IDE recompiles
> only the types that actually changed).
>
> Without idempotent writes, a watch trigger rewrites the full output
> tree on every save, defeating the purpose. With them, the trigger
> cost is proportional to the diff.

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
idempotent-writes) already tells consumers to run `mvn
generate-sources` or their own watch-trigger wiring. This plan closes
the wiring gap for consumers who want a first-party solution.

## Scope boundaries

**In scope**

- A `WatchMojo` class in `graphitron-rewrite-maven` bound to no
  lifecycle phase, invoked as `mvn graphitron:watch`.
- `WatchService`-based directory monitoring scoped to the directories
  that contain the resolved `<schemaInputs>` files.
- Debounce via `ScheduledExecutorService` (configurable window,
  default 300 ms).
- Recursive directory registration: if the glob includes `**`,
  register all existing subdirectories at startup and newly created
  subdirectories at runtime.
- Error recovery: schema validation failures and `IOException` on
  re-generation are caught, printed with `getLog().error(...)`, and
  the watch loop resumes.
- One run of the generator on startup before the watch loop begins,
  so the output tree is fresh when the loop starts.
- A `--no-initial-run` skip flag (`-Dgraphitron.watch.skipInitial`)
  for consumers whose build already ran `generate-sources` in the same
  session.

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
  src/main/java/no/sikt/graphitron/maven/
    WatchMojo.java          -- @Mojo(name = "watch", requiresDependencyResolution = COMPILE)
    watch/
      SchemaWatcher.java    -- WatchService registration + event loop
      DebounceExecutor.java -- ScheduledExecutorService debounce helper
```

`WatchMojo` extends the same `AbstractRewriteMojo` that `GenerateMojo`
will extend, so `RewriteContext` construction is shared. The Mojo:

1. Builds `RewriteContext` from plugin parameters (same path as
   `GenerateMojo.execute()`).
2. Unless `skipInitial` is set, runs `new GraphQLRewriteGenerator(ctx).run()` once.
3. Resolves the watch directory set from `ctx.schemaInputs()` (parent
   directories of every resolved `SchemaInput.sourceName()`; deduplicated).
4. Starts `SchemaWatcher` with that directory set and a callback that
   calls the generator and re-expands `<schemaInputs>` to pick up new
   files added mid-session.
5. Blocks on `SchemaWatcher.run()` until the JVM receives SIGINT or
   SIGTERM.

### `SchemaWatcher`

Wraps `FileSystems.getDefault().newWatchService()`. On construction:

- Registers each directory in the watch set for
  `ENTRY_CREATE`, `ENTRY_MODIFY`, `ENTRY_DELETE`.
- If the glob set includes any `**` pattern, performs a
  `Files.walk(dir)` to register all existing subdirectories and
  re-registers each new `ENTRY_CREATE` event that resolves to a
  directory.

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

1. Re-expands `<schemaInputs>` globs (picks up new files).
2. Rebuilds `RewriteContext` with the updated input set.
3. Calls `new GraphQLRewriteGenerator(ctx).run()`.
4. Catches `RuntimeException`; prints via `getLog().error(...)`;
   resumes.
5. Re-registers any new directories discovered in the expanded input
   set.

### Thread safety

`GraphQLRewriteGenerator` is constructed fresh on each trigger with
its own `RewriteContext` instance. After the rewrite-maven-plugin
plan lands and `RewriteConfig` statics are deleted, the generator has
no shared mutable state. The debounce executor's single thread
serialises triggers so two generator runs cannot overlap.

### Parameters

| Parameter | XML element | System property | Default |
|---|---|---|---|
| Inherited from `AbstractRewriteMojo` | `<schemaInputs>`, `<outputPackage>`, etc. | (same as `generate`) | (same as `generate`) |
| Skip initial run | (flag only) | `graphitron.watch.skipInitial` | `false` |
| Debounce window | (flag only) | `graphitron.watch.debounceMs` | `300` |

Neither `skipInitial` nor `debounceMs` is a `@Parameter`-exposed XML
element; they are developer-session overrides passed on the command
line, not project configuration.

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
- `OVERFLOW` event causes a callback.

Uses `WatchService` against the real filesystem (temp dirs); no
mocking needed since the API surface is small and the behaviour under
test is the OS-level notification contract.

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

Extend the `## Dev loop` section in
`graphitron-rewrite/docs/getting-started.md` (introduced by the
idempotent-writes plan) with a short `### Watch mode` subsection:

- Command: `mvn graphitron:watch`
- What it does: runs the generator once on startup, then watches
  `<schemaInputs>` directories and re-runs on any `.graphqls` change.
- What the developer observes: only changed files are written; the
  IDE recompiles only the affected classes (same three-clause contract
  as a manual `generate-sources` run).
- How to stop: Ctrl+C.
- Note on jOOQ changes: changing the jOOQ schema requires restarting
  the watch session (the compiled jOOQ classes are not watched).

## Rollout

Single-commit landing in `graphitron-rewrite-maven` after both
prerequisites are merged to `claude/graphitron-rewrite`. No consumer
migration required: the goal is additive and opt-in. Consumers who
already use `mvn generate-sources` in a shell loop or IDE file watcher
can migrate at their convenience; both work correctly alongside the
idempotent-writes contract.

## Roadmap integration

Standalone item under Architecture / structural in the roadmap.
Prerequisites are noted in the roadmap entry. On landing, move the
entry to `## Done` with a one-line summary citing the commit sha and
the `SchemaWatcherTest` location.
