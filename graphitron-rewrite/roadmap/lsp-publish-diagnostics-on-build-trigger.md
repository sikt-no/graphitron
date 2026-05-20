---
id: R196
title: LSP fails to publish diagnostics after save-triggered build; stale errors persist until next edit
status: In Review
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
3. The six existing public mutators (`didOpen`, `didChange`, `didClose`, `setBuildOutput`, `demoteSnapshot`, `markAllForRecalculation`) each end with one call to `enqueueAndNotify` (which encapsulates the existing `enqueueTouched(...)` / direct adds) so the listener-fire happens uniformly. `toRecalculate` is already field-private; after this change `enqueueAndNotify` is the only writer of it inside `Workspace.java`. That single-writer fact, not a grep convention, is what makes "no public mutator returns without notifying" the structural invariant rather than a six-site discipline. The helper is the seam by the same logic that motivates introducing the listener at all: if we're lifting "drain follows enqueue" out of author-convention, we don't immediately reintroduce it as "fire-listener follows mutation" at six call sites.
4. `GraphitronTextDocumentService.setClient(LanguageClient)` — the existing wire-up entry point fired once by `GraphitronLanguageServer.connect` — additionally calls `workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate)`. The explicit `publishDiagnosticsForRecalculate()` calls inside `didOpen` / `didChange` / `didClose` go away; the listener handles all six paths uniformly.

### Why not Option B (listener fires only from the broken paths)

Considered and rejected. Option B keeps the explicit publish calls in the editor-event arms and adds the listener only to the build-trigger arms. Smaller diff, but it preserves the two-coordination-style asymmetry: the future maintainer reading `didChange` wonders why it calls publish directly when a listener is installed; the future maintainer adding a seventh mutator has to choose which style to follow. Option A (uniform "every mutator notifies") puts the publish concern in one place and makes the invariant a single-writer fact on `toRecalculate` rather than a discipline.

### Why a `Runnable` rather than a richer event shape

A sealed `RecalculateEvent { EditorChange | BuildSwap | SnapshotDemotion | ParseFailure }` carries no information the listener acts on differently — the listener drains the queue and publishes regardless of cause, and the drain itself already names the affected URIs. A sub-taxonomy without a forcing function violates the "each new sealed permit pays for itself" rule. `volatile Runnable` is the right size; lift later if a second consumer surfaces that needs to discriminate causes.

### Why the seam lives on `Workspace`, not `DevMojo` calling the document service directly

Considered and rejected. Having `DevMojo` call a new `TextDocumentService.publishPending()` after `setBuildOutput` couples the maven plugin to the document service, adds a second wiring path for the test harness to mock, and leaks `LanguageClient` lifecycle into the dev mojo. The listener on `Workspace` keeps `LanguageClient` out of the `state/` package entirely — the seam is just a `Runnable` — while letting any consumer that holds a `Workspace` reference register one. Symmetric with how `Workspace` already exposes `markAllForRecalculation()` and `setBuildOutput(...)` as the wiring points for `DevMojo`.

### Threading

`Workspace` is already documented as thread-safe via the internal `lock`. The listener is stored in a `volatile Runnable` (safe publication of the latest registered runnable across threads). Fire-after-lock-release means a build-swap on the watcher thread and an editor event on the lsp4j thread can each run `publishDiagnosticsForRecalculate` without serialising on `lock`; both calls hit `drainRecalculate` (atomic under `lock`), one wins, the other sees an empty list and returns. The "single-extraction" property the listener path depends on is pinned by the new `drainRecalculateIsIdempotentOnEmptyQueue` test arm below (a second `drainRecalculate` after the first has emptied the queue returns an empty list).

## Implementation sites

- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/state/Workspace.java`: add the volatile `recalculateListener` field and the `setRecalculateListener` setter; add the private `enqueueAndNotify(...)` helper; route the six public mutators through it; delete the inline `enqueueTouched(...)` direct adds in favour of the funnel (the existing dependency-fanout logic stays — it just moves into the helper). Field-private `toRecalculate` is already the case; the funnel makes it the only writer.
- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/server/GraphitronTextDocumentService.java`: in `setClient`, also call `workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate)`. Remove the explicit `publishDiagnosticsForRecalculate()` calls from `didOpen`, `didChange`, and `didClose` (the listener now handles them). The `didClose` arm's "clear diagnostics for the closed file" call (`client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()))`) stays — it's not about the queue, it's a direct one-shot for the URI that just left the workspace.
- No `DevMojo` change. `regenerate` / `rebuildCatalog` keep calling `setBuildOutput` / `demoteSnapshot` / `markAllForRecalculation`; the publish now follows automatically.

## Tests

### Unit-tier — `WorkspaceTest`

The invariant is uniform across mutators, so the test expresses it uniformly:

- `everyPublicQueueMutatingMethodFiresTheListener` — `@ParameterizedTest` over a `Stream<Named<Consumer<Workspace>>>` of the six mutators (`didOpen`, `didChange`, `didClose`, `setBuildOutput`, `demoteSnapshot`, `markAllForRecalculation`, each fed the minimum arguments to exercise its enqueue path on a workspace pre-seeded with one open file and, for `demoteSnapshot`, a `Built.Current` snapshot so the demote actually transitions). Register a counting `Runnable`, invoke the mutator, assert listener-fire count delta of exactly 1. One test method, six parametrised cases; a future seventh mutator added to the funnel passes by virtue of being on the funnel.
- `recalculateListenerDefaultsToNoOpForTestHarnesses` — instantiate `Workspace`, immediately call a mutator without `setRecalculateListener`, assert no exception (regression guard against a future implementation that drops the no-op default and NPEs on the listener field).
- `drainRecalculateIsIdempotentOnEmptyQueue` — drive a mutator to populate the queue, drain once, drain again, assert the second drain returns an empty list. Pins the single-extraction property the Threading section depends on; not currently covered, the existing test arms all drain at most once per setup.
- `demoteSnapshotOnNoOpDoesNotFireListener` — `@ParameterizedTest` over the two no-op starting states (`LspSchemaSnapshot.Unavailable` and `LspSchemaSnapshot.Built.Previous`). Pre-seed an open file, register a counting `Runnable`, invoke `demoteSnapshot()`, assert listener-fire count is unchanged. Pins the only public-mutator path that returns without firing the listener — the exception branch of the otherwise-uniform "every public mutator notifies" rule — so a future implementer who lifts the funnel call outside the `instanceof Built.Current` gate trips a test rather than silently broadcasting spurious recalculations on stale snapshots. R139 did not pin this contract directly; the gate was previously single-writer-by-inspection. The funnel makes the gate's reach visible, so the test arm makes its boundary visible too.

These extend the existing `WorkspaceTest` (`graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/WorkspaceTest.java`) and don't need a `LanguageClient` — the counter `Runnable` is sufficient.

### Pipeline-tier — new `BuildTriggerPublishesDiagnosticsTest` in `graphitron-lsp/src/test/...`

The pipeline test for the seam this item adds: drive a `setBuildOutput` end-to-end and assert the wire-level `publishDiagnostics` call arrives at the captured `LanguageClient`. Captured stub records `publishDiagnostics(PublishDiagnosticsParams)` calls into a `List<PublishDiagnosticsParams>`. Test flow:

1. Build a `Workspace`, build a `GraphitronTextDocumentService`, install the stub `LanguageClient` via `setClient`.
2. `workspace.didOpen("file:///a.graphqls", 1, "<sdl>")` — assert one `publishDiagnostics` call for `a.graphqls` with whatever the empty-validation-report diagnostic list is (likely empty).
3. `workspace.setBuildOutput(<artifacts>, <ValidationReport with one error on a.graphqls>)` — assert a subsequent `publishDiagnostics` call for `a.graphqls` with the error present. (Today this assertion fails because the listener does not fire; on the fixed code it passes.)
4. `workspace.setBuildOutput(<artifacts>, ValidationReport.empty())` — assert the next `publishDiagnostics` for `a.graphqls` ships an empty diagnostic list (the wire-level "clear" signal).

`ValidationReport.empty()` and the existing `ValidationReport.from(errors, warnings)` factory already exist (R147); the test does not need a real generator pass.

This also retires the LSP-side half of R149 (the deferred end-to-end `publishDiagnostics` wire-test from R147), which targeted exactly this path. R149's other half — `GraphQLRewriteGeneratorTest` asserting `buildOutput()` populates `BuildOutput.report()` from the validator — exercises the producer side, needs a real jOOQ catalog, and stays under R149.

### Compilation-tier and execution-tier

Not applicable. The change is a wiring fix in the LSP module; no emitted code changes.

## Done means

- The new `WorkspaceTest` arms (`everyPublicQueueMutatingMethodFiresTheListener`, `recalculateListenerDefaultsToNoOpForTestHarnesses`, `drainRecalculateIsIdempotentOnEmptyQueue`, `demoteSnapshotOnNoOpDoesNotFireListener`) pass.
- `BuildTriggerPublishesDiagnosticsTest` passes; manual repro (open a schema file with a validator error, fix the error, save, observe the squiggle clear without typing again) confirms the user-visible bug is gone.
- In `Workspace.java`, `toRecalculate` remains field-private and is written only inside `enqueueAndNotify`; the public mutators each terminate in one call to that helper. The single-writer fact is the reviewer-visible shape of the "no public mutator returns without notifying" invariant.
- `GraphitronTextDocumentService.setClient` carries the sole `workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate)` registration; the explicit `publishDiagnosticsForRecalculate()` calls inside `didOpen` / `didChange` / `didClose` are gone.
- Existing `WorkspaceTest`, `DiagnosticsTest`, `ValidatorDiagnosticsTest`, and the rest of the LSP test suite continue to pass unchanged.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is green.

## Out of scope

- The `GraphQLRewriteGeneratorTest` end-to-end `buildOutput()` report-population test from R149. Stays under R149 — it exercises the producer side (validator pass + warnings into `BuildOutput.report()`) and needs a real jOOQ catalog, separate from this item's listener-seam fix.
- Generalising the listener to a multi-consumer fan-out (`addRecalculateListener` / `List<Runnable>`). There is one consumer today (the document service); lift when a second appears, as its own Backlog item.
- Subscribing the listener to richer event shapes (typed `RecalculateEvent` permits discriminating cause: editor change vs. build swap vs. snapshot demotion). Drain is cause-agnostic; the sub-taxonomy carries no information consumers act on differently. Lift when a forcing function appears, as its own Backlog item.
- Migrating any non-`DevMojo` callers of `markAllForRecalculation` / `demoteSnapshot` / `setBuildOutput`. The only callers today are `DevMojo` and the test harnesses; both keep working as-is.

## Open questions for the reviewer

1. *Test harness setup pattern.* `BuildTriggerPublishesDiagnosticsTest` will need a captured-`LanguageClient` stub class. The minimal version is an inner class with one `List<PublishDiagnosticsParams>` field overriding `publishDiagnostics`. The reusable version is a top-level test fixture under `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/support/`. I lean minimal until a second consumer wants it; if the implementer wants the fixture extracted up-front, that's also fine. Not blocking.
