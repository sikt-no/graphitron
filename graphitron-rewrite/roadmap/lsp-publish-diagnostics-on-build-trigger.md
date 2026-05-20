---
id: R196
title: LSP fails to publish diagnostics after save-triggered build; stale errors persist until next edit
status: Spec
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# LSP fails to publish diagnostics after save-triggered build; stale errors persist until next edit

User-visible symptom: a validation error or warning in an open schema file does not clear after the user fixes it and saves the file; the squiggle disappears only on the *next* edit. Root cause: `Workspace.toRecalculate` is populated by `setBuildOutput` / `demoteSnapshot` / `markAllForRecalculation` (called from `DevMojo.regenerate` and `rebuildCatalog` after the schema-file and classpath watchers fire), but only `publishDiagnosticsForRecalculate()` in `GraphitronTextDocumentService` drains the queue, and that method is invoked only from `didOpen` / `didChange` / `didClose`. So the save-triggered build path enqueues every open file for recalculation and then sits on the queue until the user types again, at which point `didChange` drains it and publishes diagnostics computed against the fresh `ValidationReport` — making the editor look like "the save didn't take, but the next keystroke did". This silently negates the whole R147 validator-into-LSP pipeline whenever a save fixes a diagnostic without itself being followed by another keystroke; the fast-feedback loop that the dev mojo + validator was designed to deliver is broken for the exact happy-path the feature was added for.

## Today's behaviour

`Workspace` has six public methods that mutate `toRecalculate`:

- `didOpen(String, int, String)`, `didChange(String, int, List<...>)`, `didClose(String)` — pushed by lsp4j editor events. The matching arms in `GraphitronTextDocumentService.didOpen` / `didChange` / `didClose` follow each call with an explicit `publishDiagnosticsForRecalculate()` that drains the queue and ships `PublishDiagnosticsParams` per touched URI through `LanguageClient`.
- `setBuildOutput(BuildArtifacts, ValidationReport)`, `demoteSnapshot()`, `markAllForRecalculation()` — driven by `DevMojo`'s schema watcher (`regenerate` success / failure paths) and classpath watcher (`rebuildCatalog` success / failure paths). No publish call is paired. The queue grows; nothing drains it until the next editor event.

The convention "drain after enqueue" is held by author-discipline at three of the six call sites and unenforced at the other three. Adding a seventh enqueue path tomorrow would reintroduce the same bug if the author forgets to drain.

## Design

### Load-bearing invariant: no public mutator returns without notifying

The fix lifts the convention into a structural rule: every public `Workspace` method that touches `toRecalculate` is responsible for ending with a notification, and the notification is the sole way consumers learn the queue has work in it. Concretely:

1. Add a single-slot listener: `private volatile Runnable recalculateListener = () -> {};` with `public void setRecalculateListener(Runnable listener)` (replaces, not adds; there is exactly one consumer — the document service). Default no-op so test callers that drive `Workspace` directly need no setup.
2. Funnel every queue mutation through a private `enqueueAndNotify(...)` helper that performs the mutation under `lock` and then, after releasing the lock, calls `recalculateListener.run()`. The lock-release-before-listener-fire is deliberate: `publishDiagnosticsForRecalculate` calls `Diagnostics.compute` per file, which is heavy enough that holding `lock` across it would serialise build swaps behind diagnostic publishes for no reason. Idempotency on the drain side (a second `drainRecalculate` after the first has emptied the queue returns an empty list) makes the "listener fires twice for two queue mutations interleaved with one drain" race a no-op rather than a correctness hazard.
3. The six existing public mutators (`didOpen`, `didChange`, `didClose`, `setBuildOutput`, `demoteSnapshot`, `markAllForRecalculation`) each end with one call to `enqueueAndNotify` (which encapsulates the existing `enqueueTouched(...)` / direct adds) so the listener-fire happens uniformly. No public mutator may add to `toRecalculate` outside `enqueueAndNotify`; making `toRecalculate` field-private and accessed only through the helper enforces this at file-local-review time. (Reviewer can grep for `toRecalculate.add` after the change and expect exactly one hit, inside `enqueueAndNotify`.)
4. `GraphitronTextDocumentService.setClient(LanguageClient)` — the existing wire-up entry point fired once by `GraphitronLanguageServer.connect` — additionally calls `workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate)`. The explicit `publishDiagnosticsForRecalculate()` calls inside `didOpen` / `didChange` / `didClose` go away; the listener handles all six paths uniformly.

### Why not Option B (listener fires only from the broken paths)

Considered and rejected. Option B keeps the explicit publish calls in the editor-event arms and adds the listener only to the build-trigger arms. Smaller diff, but it preserves the two-coordination-style asymmetry: the future maintainer reading `didChange` wonders why it calls publish directly when a listener is installed; the future maintainer adding a seventh mutator has to choose which style to follow. Option A (uniform "every mutator notifies") puts the publish concern in one place and makes the invariant grep-visible.

### Why a `Runnable` rather than a richer event shape

A sealed `RecalculateEvent { EditorChange | BuildSwap | SnapshotDemotion | ParseFailure }` carries no information the listener acts on differently — the listener drains the queue and publishes regardless of cause, and the drain itself already names the affected URIs. A sub-taxonomy without a forcing function violates the "each new sealed permit pays for itself" rule. `volatile Runnable` is the right size; lift later if a second consumer surfaces that needs to discriminate causes.

### Why the seam lives on `Workspace`, not `DevMojo` calling the document service directly

Considered and rejected. Having `DevMojo` call a new `TextDocumentService.publishPending()` after `setBuildOutput` couples the maven plugin to the document service, adds a second wiring path for the test harness to mock, and leaks `LanguageClient` lifecycle into the dev mojo. The listener on `Workspace` keeps `LanguageClient` out of the `state/` package entirely — the seam is just a `Runnable` — while letting any consumer that holds a `Workspace` reference register one. Symmetric with how `Workspace` already exposes `markAllForRecalculation()` and `setBuildOutput(...)` as the wiring points for `DevMojo`.

### Threading

`Workspace` is already documented as thread-safe via the internal `lock`. The listener is stored in a `volatile Runnable` (safe publication of the latest registered runnable across threads). Fire-after-lock-release means a build-swap on the watcher thread and an editor event on the lsp4j thread can each run `publishDiagnosticsForRecalculate` without serialising on `lock`; both calls hit `drainRecalculate` (atomic under `lock`), one wins, the other sees an empty list and returns. No double-publish — `drainRecalculate` is single-extraction by design.

## Implementation sites

- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/state/Workspace.java`: add the volatile `recalculateListener` field and the `setRecalculateListener` setter; add the private `enqueueAndNotify(...)` helper; route the six public mutators through it; delete the inline `enqueueTouched(...)` direct adds in favour of the funnel (the existing dependency-fanout logic stays — it just moves into the helper). Field-private `toRecalculate` is already the case; the funnel makes it the only writer.
- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/server/GraphitronTextDocumentService.java`: in `setClient`, also call `workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate)`. Remove the explicit `publishDiagnosticsForRecalculate()` calls from `didOpen`, `didChange`, and `didClose` (the listener now handles them). The `didClose` arm's "clear diagnostics for the closed file" call (`client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()))`) stays — it's not about the queue, it's a direct one-shot for the URI that just left the workspace.
- No `DevMojo` change. `regenerate` / `rebuildCatalog` keep calling `setBuildOutput` / `demoteSnapshot` / `markAllForRecalculation`; the publish now follows automatically.

## Tests

### Unit-tier — `WorkspaceTest`

Pin the load-bearing invariant per public mutator:

- `didOpenFiresRecalculateListener` — register a counting `Runnable`, call `didOpen`, assert fired exactly once.
- `didChangeFiresRecalculateListener` — drive a `didOpen` then a `didChange` content edit, assert the counter increments by exactly two.
- `didCloseFiresRecalculateListener` — drive a `didOpen` then a `didClose`, assert fired twice.
- `setBuildOutputFiresRecalculateListener` — open two files, install the listener (reset counter), call `setBuildOutput`, assert fired once.
- `demoteSnapshotFiresRecalculateListenerWhenDemoting` — set up a `Built.Current` snapshot via `setBuildOutput`, install listener, call `demoteSnapshot`, assert fired once; separately, on `Unavailable` and `Built.Previous`, `demoteSnapshot` is a no-op so the listener does not fire (the spec's existing demote-is-no-op-on-non-current contract; covered by the existing `WorkspaceTest` shape).
- `markAllForRecalculationFiresRecalculateListener` — same pattern.
- `recalculateListenerDefaultsToNoOpForTestHarnesses` — instantiate `Workspace`, immediately call a mutator without `setRecalculateListener`, assert no exception (regression guard against a future implementation that accidentally drops the no-op default and NPEs on the listener field).

These are direct extensions of the existing `WorkspaceTest` (`graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/WorkspaceTest.java`) and don't need a `LanguageClient` — the counter `Runnable` is sufficient.

### Pipeline-tier — new `BuildTriggerPublishesDiagnosticsTest` in `graphitron-lsp/src/test/...`

This is the LSP-side half of R149's deferred end-to-end test, folded into this item because the wire shape it pins (`setBuildOutput → listener → publishDiagnosticsForRecalculate → client.publishDiagnostics`) is exactly the path this fix introduces.

Captured `LanguageClient` stub records `publishDiagnostics(PublishDiagnosticsParams)` calls into a `List<PublishDiagnosticsParams>`. Test flow:

1. Build a `Workspace`, build a `GraphitronTextDocumentService`, install the stub `LanguageClient` via `setClient`.
2. `workspace.didOpen("file:///a.graphqls", 1, "<sdl>")` — assert one `publishDiagnostics` call for `a.graphqls` with whatever the empty-validation-report diagnostic list is (likely empty).
3. `workspace.setBuildOutput(<artifacts>, <ValidationReport with one error on a.graphqls>)` — assert a subsequent `publishDiagnostics` call for `a.graphqls` with the error present. (This is the bug-pin: today this assertion fails because the listener does not fire.)
4. `workspace.setBuildOutput(<artifacts>, ValidationReport.empty())` — assert the next `publishDiagnostics` for `a.graphqls` ships an empty diagnostic list (the wire-level "clear" signal).

`ValidationReport.empty()` and the existing `ValidationReport.from(errors, warnings)` factory already exist (R147); the test does not need a real generator pass.

The R149 follow-up's other half — `GraphQLRewriteGeneratorTest` for end-to-end `buildOutput()` report population — stays scoped to R149.

### Compilation-tier and execution-tier

Not applicable. The change is a wiring fix in the LSP module; no emitted code changes.

## Done means

- The seven `WorkspaceTest` assertions above pass.
- `BuildTriggerPublishesDiagnosticsTest` passes; manual repro (open a schema file with a validator error, fix the error, save, observe the squiggle clear without typing again) confirms the user-visible bug is gone.
- `git grep 'toRecalculate.add'` returns exactly one hit, inside the new `enqueueAndNotify` helper in `Workspace.java`. (Reviewer-grepable shape of the load-bearing invariant.)
- `git grep 'publishDiagnosticsForRecalculate'` returns exactly one definition site in `GraphitronTextDocumentService.java` and one registration site (`workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate)` in `setClient`). No more inline callers.
- Existing `WorkspaceTest`, `DiagnosticsTest`, `ValidatorDiagnosticsTest`, and the rest of the LSP test suite continue to pass unchanged.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is green.

## Out of scope

- The `GraphQLRewriteGeneratorTest` end-to-end `buildOutput()` report-population test from R149. Stays under R149 — it exercises the producer side (validator pass + warnings into `BuildOutput.report()`) and needs a real jOOQ catalog, separate from this item's listener-seam fix.
- Generalising the listener to a multi-consumer fan-out (`addRecalculateListener` / `List<Runnable>`). There is one consumer today (the document service). Lift only when a second appears.
- Subscribing the listener to richer event shapes (typed `RecalculateEvent` permits discriminating cause: editor change vs. build swap vs. snapshot demotion). Drain is cause-agnostic; the sub-taxonomy carries no information consumers act on differently. Tracked under *Future evolution*.
- Migrating any non-`DevMojo` callers of `markAllForRecalculation` / `demoteSnapshot` / `setBuildOutput`. The only callers today are `DevMojo` and the test harnesses; both keep working as-is.

## Future evolution

- If a non-LSP consumer of `Workspace` mutations surfaces (e.g. a metrics sidecar, a CLI dry-run, a second LSP client wire), promote `setRecalculateListener` to a multi-consumer registration. Today's single-slot setter is correctly the smaller shape.
- If we ever want to differentiate "fresh build" vs "stale build" vs "editor edit" for telemetry (count save-driven redraws, surface "stale diagnostics" in the UI client), introduce the sealed `RecalculateEvent` then, with each permit carrying the data the new consumer demands. The current `Runnable` is forward-compatible with that change (the listener target just widens its argument list).

## Open questions for the reviewer

1. *Listener placement: per-mutator funnel vs. a single private `enqueueAndNotify` helper.* The spec body picks the helper because it makes the invariant grep-visible. The alternative — inline `recalculateListener.run()` at the bottom of each public mutator — is one line of duplication per site (six sites). I lean helper; reviewer call.
2. *Test harness setup pattern.* `BuildTriggerPublishesDiagnosticsTest` will need a captured-`LanguageClient` stub class. The minimal version is an inner class with one `List<PublishDiagnosticsParams>` field overriding `publishDiagnostics`. The reusable version is a top-level test fixture under `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/support/`. I lean minimal until a second consumer wants it; if the implementer wants the fixture extracted up-front, that's also fine. Not blocking.
