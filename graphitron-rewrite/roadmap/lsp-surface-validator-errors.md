---
id: R147
title: "Surface GraphitronSchemaValidator errors and warnings as LSP diagnostics"
status: Spec
bucket: feature
priority: 5
theme: lsp
depends-on: []
---

# Surface GraphitronSchemaValidator errors and warnings as LSP diagnostics

`GraphitronSchemaValidator.validate()` and `GraphitronSchema.warnings()` are the build pipeline's voice for every rejection that survives parse and classification: `paginationRequiresOrdering`, multi-table participant PK arity, nested-parent compat, `@reference` shape errors, deferred-variant rejections, `UnclassifiedType` / `UnclassifiedField` fallbacks, the connection-`totalCount` type check, and the `BuildWarning` channel (`-parameters` missing, directive shadowing, etc.). Today these reach the developer only as `path:line:col: <Author error|Invalid schema|Deferred>: <message>` lines in the SLF4J log (`GraphQLRewriteGenerator.validateAndLogErrors` / `logWarnings`) plus the `WatchErrorFormatter` per-file tree. A developer driving SDL edits through the LSP at `localhost:8487` gets no editor-side signal for any of it; the watch-mode dev console is the sole surface.

The LSP's own `Diagnostics.compute` is independent and SDL-only: unknown directive args, missing required args, catalog table/column/FK existence, classpath class/method existence, and the unknown-directive arm wired to the R139 snapshot. It does not call the validator and has no access to its output. The seam is small and already cut.

## Design

### Build side: extend `BuildOutput`

`GraphQLRewriteGenerator.buildOutput()` already classifies the schema (`bundle.model()` at line 105) to produce the catalog and snapshot the LSP consumes. The validator walks that same model; running it here is essentially free on top of work the watch loop already does. The original "skips validation deliberately" rationale at line 84 was about not *failing* the LSP write path on validation errors (a half-edited buffer should still get autocomplete). That intent is preserved: `buildOutput` runs the validator but never throws on its output, it just packages the result alongside the catalog and snapshot.

`BuildOutput` splits along the two lifecycle steps it now spans. R139 produced a `(catalog, snapshot)` pair from classification; R147 adds a validator pass that runs *after* classification on the same `bundle.model()`. Flattening the four into one record drops that seam; pairing them keeps it visible:

```java
public record BuildOutput(BuildArtifacts artifacts, ValidationReport report) {}

public record BuildArtifacts(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot) {}

public record ValidationReport(
    List<ValidationError> errors,
    List<BuildWarning> warnings,
    Set<String> sourceUris
) {
    public static ValidationReport empty() { return new ValidationReport(List.of(), List.of(), Set.of()); }
    public boolean isEmpty() { return errors.isEmpty() && warnings.isEmpty(); }
}
```

`ValidationReport` lives in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/` next to `ValidationError` and `BuildWarning`; the LSP module imports it directly. The `sourceUris` field is computed once at construction (the canonical URI set of every `SourceLocation` on the errors and warnings), so `Diagnostics.compute` can short-circuit per file with a single `Set.contains`.

`buildOutput()` body adds three lines after the existing snapshot construction:

```java
var errors = new GraphitronSchemaValidator().validate(bundle.model());
var warnings = bundle.model().warnings();
var report = ValidationReport.from(errors, warnings);  // computes sourceUris
return new BuildOutput(new BuildArtifacts(catalog, snapshot), report);
```

`buildCatalog()` (the public method that today exists only to extract `buildOutput().catalog()`) drops in this item: every call site routes through `buildOutput()` (see Implementation § below), so the narrow accessor has no callers left.

Pre-existing redundancy footnote: `DevMojo.regenerate` calls `runGeneratorPass(ctx, "regenerate")` (which runs the full pipeline including the validator) *and* `buildOutput()` (which will now also run the validator). Both build the same `GraphitronSchema` from the same parse. This double-build is unrelated to R147's classpath-watcher unification; eliminating it requires routing the watch loop's pipeline pass and its LSP-state refresh through a single schema build, which is a separate refactor. Filed mentally; not blocking.

### Workspace: new volatile field

`Workspace` carries the validation report next to `catalog` and `snapshot`. `ValidationReport` is the same record `BuildOutput` exposes; the LSP imports it from the `graphitron` module rather than re-declaring it.

```java
private volatile ValidationReport validationReport = ValidationReport.empty();

public ValidationReport validationReport() { return validationReport; }

public void setBuildOutput(BuildArtifacts artifacts, ValidationReport report) {
    this.catalog = artifacts.catalog();
    this.snapshot = artifacts.snapshot();
    this.validationReport = report;
    markAllForRecalculation();
}
```

The setter takes the two records `BuildOutput` produces so callers do not destructure the pair: `DevMojo.regenerate`'s call becomes `workspace.setBuildOutput(output.artifacts(), output.report())`. There is no narrower overload. Both write triggers (`regenerate` on schema save, `rebuildCatalog` on classpath change) route through `setBuildOutput` and pay the same validator walk; classpath-induced rejections (the canonical case: unresolved `@service` class after a Java rename) surface in the editor on the next `mvn compile` without waiting for a schema save. The previous `setCatalog(CompletionData)` overload is dropped.

`demoteSnapshot()` does not touch the validation report. The R139 rationale ("don't punish the user for what we cannot reliably see") suggests silencing the report under `Built.Previous` at *read* time, not erasing it at *write* time. Two states diverge cleanly: a snapshot demoted because a fresh parse failed still has the previous build's validator output sitting there, ready to re-publish if the user reverts; erasing it would flap.

### LSP side: `Diagnostics.compute` reads the report

`Diagnostics.compute` adds a new step at the end of its existing per-directive walk:

```java
out.addAll(validatorDiagnostics(file, workspace.validationReport(), snapshot));
```

`validatorDiagnostics`:

1. Returns `List.of()` under `LspSchemaSnapshot.Unavailable` (no report yet) and `Built.Previous` (stale). Mirrors the R139 freshness-aware silence policy and lets the existing `@DependsOnClassifierCheck` annotation pattern document the decision in code.
2. Walks `report.errors()` and `report.warnings()`. For each entry whose `location.getSourceName()` canonicalises to the open file's URI, emits one LSP `Diagnostic`.
3. Sets `Diagnostic.source` to `"graphitron-validator"` (distinct from the existing `"graphitron-lsp"` source so the wire-shape tests can tell who emitted what, and the editor can show source attribution).

The plumbing into `GraphitronTextDocumentService.publishDiagnosticsForRecalculate` is zero-touch: that method already drains the recalculate queue and calls `Diagnostics.compute` per file. `setBuildOutput` calls `markAllForRecalculation()`, so the recalculate triggers on every successful build, with or without errors.

**Clearing obligation when the report goes empty.** LSP clients persist `PublishDiagnosticsParams` until told otherwise; when a fresh `Built.Current` arrives carrying zero validator errors (the developer fixed everything), the previously-published red squiggles must clear, not linger. `publishDiagnosticsForRecalculate` already publishes `Diagnostics.compute`'s full result per file on every recalculate, so a now-empty validator slice plus an empty LSP-side slice produces an empty `PublishDiagnosticsParams.diagnostics`, which the wire spec defines as "clear all diagnostics for this URI". The clearing is automatic, but the test surface must pin it (see Tests § below) since the principle "don't punish for what we cannot reliably see" is silent on the clearing obligation and a future refactor that filters out empty publishes would silently regress.

### Severity mapping

Routed through an exhaustive switch on the sealed `Rejection`, not a flat per-kind table:

```java
DiagnosticSeverity severity = switch (error.rejection()) {
    case Rejection.AuthorError ignored   -> DiagnosticSeverity.Error;
    case Rejection.InvalidSchema ignored -> DiagnosticSeverity.Error;
    case Rejection.Deferred ignored      -> DiagnosticSeverity.Warning;
};
```

`BuildWarning` maps unconditionally to `DiagnosticSeverity.Warning`.

The exhaustive-switch shape is load-bearing: adding a new `Rejection` permit (the sealed hierarchy is the canonical extension point) becomes a compile error in the LSP module, forcing the maintainer to make the severity decision rather than fall through to a default. Matches the R139 pattern of switching on the `LspSchemaSnapshot.Built.{Current,Previous}` permits to surface the freshness-policy decision at extension time.

`Deferred` is `Warning`, not `Error`: the schema is structurally valid but the generator has not shipped the requested shape yet. The watch-mode formatter's "Deferred" label and roadmap planSlug carry the same nuance; `Error` would imply "you wrote something wrong" which is misleading for stubbed-variant rejections. The build still throws `ValidationFailedException` on Deferred rejections (the pipeline cannot emit code), so it remains blocking at *build* time; the LSP softens it at *edit* time on the principle that the developer cannot fix it by rewriting their schema in any straightforward way. The build-time / edit-time asymmetry is intentional and load-bearing: build-time is "can I emit code from this", edit-time is "is this the user's bug to fix"; the two questions have different answers for `Deferred`.

`messageLabel()` (`"Author error"` / `"Invalid schema"` / `"Deferred"`) is not threaded into the LSP message; the kind is implicit in the `severity` and the `source` field, and prepending it doubles the LSP client's own kind indicator.

### Range and file matching

`SourceLocation.getLine()` / `getColumn()` are 1-based; LSP `Position.line` / `character` are 0-based. The mapping:

```java
var start = new Position(loc.getLine() - 1, loc.getColumn() - 1);
var end = new Position(loc.getLine() - 1, Integer.MAX_VALUE);
```

End-of-line for the range width: matches gcc/AsciiDoctor and most compiler conventions, and is forgiving when the validator's column points at the start of a multi-line declaration (a zero-width range is too subtle to find in editors; full-line is too noisy; column-to-EOL hits the right balance). LSP clients clamp `Integer.MAX_VALUE` to the actual line end.

The "no usable location" gate is broader than a strict null check: `loc == null || loc.getLine() <= 0` covers both `null` and the `(0, 0)` form some `SourceLocation`s carry for programmatically attributed nodes. Routing both through the same handling (see "Schema-wide errors" below) prevents `loc.getLine() - 1 = -1` from reaching the wire.

File matching: a single static helper on `ValidationReport` produces the canonical `file://` URI form, and both the producer (`ValidationReport.from` building the `sourceUris` set) and the consumer (`Diagnostics.validatorDiagnostics` filtering by open-file URI) call it. One method, one canonical form:

```java
public static String canonicalUri(String sourceName) {
    return Path.of(sourceName).toUri().toString();
}
```

Centralising the helper closes the silent-drift hole where producer and consumer canonicalise independently and diverge on (say) URL-encoding of spaces. The cached `Set<String> sourceUris` on `ValidationReport` lets `Diagnostics.compute` short-circuit when the open file has no entries (most files most of the time).

Canonical equality between the LSP's open-file URI (whatever the client sent, normalised by lsp4j) and `canonicalUri(sourceName)` holds because `RewriteSchemaLoader` populates `SourceLocation.sourceName` with the absolute path via `MultiSourceReader.trackData(true)`, and lsp4j's URI normalisation for absolute file paths matches `Path.toUri()`. Symlink divergence is an out-of-scope corner case for v1; the helper makes it a future single-site change.

The cross-module invariants the project marks with paired classifier-check annotations:

- `source-location.absolute-path-source-name` — `RewriteSchemaLoader` (`@LoadBearingClassifierCheck`) produces absolute paths; `ValidationReport.canonicalUri` (`@DependsOnClassifierCheck`) reads them.
- `validation-report.canonical-uri` — `ValidationReport.canonicalUri` (`@LoadBearingClassifierCheck`) defines the canonical form; `Diagnostics.validatorDiagnostics` (`@DependsOnClassifierCheck`) consumes it for the open-file filter.

A future change to either invariant surfaces as an orphan key rather than diagnostics that silently stop matching open buffers.

### Schema-wide errors (no usable location)

A `ValidationError` whose `location` is null or `(0, 0)` cannot be pinned to a buffer. v1 drops them silently in the LSP path. Three considerations drive this:

- Every `ValidationError` instance in the current rule set carries `field.location()` or `type.location()`; the no-location path fires zero times today.
- Adding an LSP surface (`window/showMessage` notifications, or a synthetic schema-root URI) before there is a concrete producer is the kind of speculative scope the project declines: "land what's needed, not what's projected".
- The console / `WatchErrorFormatter` path already prints schema-wide errors; a developer hitting this case is not signal-starved.

When the first real producer of a no-location error lands, it ships in the same commit as its LSP surface, so the contract has a worked example. Until then, the comment in `validatorDiagnostics` names the conditions under which we would add `window/showMessage` (rate-limited server-to-client `MessageType.Error`, one per recalculate). No code, just the contract.

Warnings with no usable location are silenced the same way and for the same reason; the dev console covers them.

### Dedup with existing LSP-side diagnostics

`Diagnostics.compute` today emits its own SDL-only diagnostics for the cases it can detect cheaply: unknown catalog table, unknown column, unknown FK, unknown class, unknown method. The validator emits an `UnclassifiedField` / `UnclassifiedType` for the same root cause once the build runs. v1 does not dedupe: both fire, both visible, distinguished by `Diagnostic.source`.

The framing is freshness tiers, not duplicated work. The LSP-side checks are the *buffer-instant* tier (analogous to R139's tree-sitter parse: every keystroke, no build needed); the validator output is the *build-pipeline* tier (every save, after classification). Both fire because they cover different freshness regimes, and a developer who has not yet hit save sees only the buffer-instant tier; one who has, sees both. The R139 `externalReferences().isEmpty()` gate is the precedent for tolerating temporary divergence on the cheap path so the expensive path can converge later.

A follow-up item could decide whether to retire the buffer-instant tier for the cases the validator covers, once usage shows whether developers actually save often enough to make the build-tier signal sufficient. That is a different question than "remove duplicate diagnostics"; the duplication is architectural, not accidental. Filed-mentally; not blocking.

## Implementation

File-by-file diff sites this item touches:

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ValidationReport.java` — new record (errors, warnings, sourceUris) with `from(...)` factory and `canonicalUri(String)` static helper. `@LoadBearingClassifierCheck(key = "validation-report.canonical-uri")` annotation on `canonicalUri`.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphQLRewriteGenerator.java` — restructure the existing `BuildOutput` record to `(BuildArtifacts, ValidationReport)`, add new `BuildArtifacts(CompletionData, LspSchemaSnapshot.Built.Current)` record, extend `buildOutput()` to run validator + collect warnings + construct `ValidationReport.from(...)`, drop `buildCatalog()` (now unused).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/RewriteSchemaLoader.java` — add `@LoadBearingClassifierCheck(key = "source-location.absolute-path-source-name", ...)` annotation documenting that `SourceLocation.sourceName` is populated as an absolute path via `MultiSourceReader.trackData(true)`.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/state/Workspace.java` — new `volatile ValidationReport validationReport` field, `validationReport()` accessor, new `setBuildOutput(BuildArtifacts, ValidationReport)` setter, drop `setCatalog(CompletionData)` and `setCatalogAndSnapshot(CompletionData, LspSchemaSnapshot.Built)` overloads.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java` — new `validatorDiagnostics(file, report, snapshot)` step appended in `compute(...)`; freshness-aware silence under `Unavailable` / `Built.Previous`; `@DependsOnClassifierCheck` annotations for both classifier-check keys.
- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/DevMojo.java` — four call-site migrations:
  - `:106` (startup `setCatalogAndSnapshot` call) → `setBuildOutput(initial.artifacts(), initial.report())`.
  - `:201-202` (`regenerate` body) → `setBuildOutput(output.artifacts(), output.report())`.
  - `:217-225` (`rebuildCatalog` body) → re-route through `new GraphQLRewriteGenerator(ctx).buildOutput()` + `setBuildOutput`, mirroring `regenerate`'s parse-failure handling (`demoteSnapshot` + `markAllForRecalculation`). This is the classpath-watcher unification.
  - `:245-253` (`buildOutputQuietly` + `InitialOutput` record) → extend `InitialOutput` to `(BuildArtifacts, ValidationReport)`; fallback path uses `ValidationReport.empty()`.

Migration-only test sites:

- `graphitron-rewrite/graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/CatalogRefreshTest.java:62` — migrate from `workspace.setCatalog(newCatalog)` to `workspace.setBuildOutput(...)`.

New test files / sections enumerated in the Tests § below.

## User documentation (first-client check)

`graphitron-rewrite/docs/getting-started.adoc:366-374` currently reads:

> What the loop does on each schema save:
> . Re-runs the generator. Idempotent writes mean only the files whose rendered content actually changed are written...
> . Validation failures are logged to the console with grouped per-file trees; the loop keeps running so a typo does not kill the session.
> . Open editor buffers see refreshed diagnostics on the next request.

Bullet 2 and bullet 3 currently sit at different abstraction levels. After this item, validation errors are *also* delivered as LSP diagnostics to the connected editor. The replacement reads:

> . Validation failures land in two places: the console gets the grouped per-file tree, and the LSP delivers the same errors as editor diagnostics on the open buffer. The loop keeps running so a typo does not kill the session; the editor's squiggle and the console's tree carry the same `(file, location, message)` triple. The editor softens deferred-variant rejections from error to warning (the schema is valid, the generator just has not shipped the path yet); everything else uses the same severity as the build log.

`getting-started.adoc:390` (the `* *LSP server*` bullet under "How this is wired") currently lists `diagnostics, hover, completion, and go-to-definition`. The diagnostics surface widens; no list-edit needed, the bullet is generic enough. The contributor-facing paragraph at line 393 names the `GraphitronSchema` as the data the LSP serves off; this widens to "the GraphitronSchema *and the validator's report on it*". One-sentence amendment.

If those edits do not read simply, the design is wrong. They do: the new bullet 2 names the parity directly and the redundancy is the point ("same triple").

## Tests

- **`DiagnosticsTest`** (`graphitron-rewrite/graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/DiagnosticsTest.java`): new test sections.
  - `ValidatorErrorsTest`: drives a `Workspace` with a hand-built `ValidationReport` and asserts the per-file filtering, severity mapping (one test per `Rejection` sealed permit — `AuthorError`, `InvalidSchema`, `Deferred`), range mapping (1-based → 0-based, EOL extension), and the `source: "graphitron-validator"` attribution.
  - `StaleSnapshotSilencesValidatorTest`: pins the freshness gate — `Built.Previous` and `Unavailable` produce zero validator diagnostics regardless of report content.
  - `NoUsableLocationDropTest`: pins the `loc == null || loc.getLine() <= 0` gate produces zero diagnostics on the wire.
  - `EmptyReportClearsPreviousDiagnosticsTest`: drives two successive `setBuildOutput` calls — first with errors, second with an empty report — and asserts the second `PublishDiagnosticsParams` carries an empty `diagnostics` list for the affected URI (the wire-level "clear" signal).
- **`RejectionSeverityCoverageTest`** (new class next to `DiagnosticsTest`): meta-test asserting the severity-mapping switch covers every permit of `Rejection` reachable from a `ValidationError`. Walks the sealed hierarchy reflectively (`Rejection.class.getPermittedSubclasses()`) and checks each maps to a non-null `DiagnosticSeverity`. The exhaustive switch in the production code already makes "missing permit" a compile error; this test pins the runtime invariant against a `default` branch sneaking in via refactor.
- **`WorkspaceTest`**: new test for `setBuildOutput(BuildArtifacts, ValidationReport)` swapping all three refs atomically and triggering `markAllForRecalculation`. (No narrow-overload test: `setCatalog(CompletionData)` is dropped.)
- **End-to-end LSP test** (one file under `graphitron-lsp/src/test/...`): drive a schema with a known validator-only rejection (e.g. pagination-without-ordering), call `setBuildOutput` with the validator's output, and assert the published `PublishDiagnosticsParams` carries the expected diagnostic.
- **`GraphQLRewriteGeneratorTest`** (if one exists, else inline in an existing generator test): assert `buildOutput()` populates `BuildOutput.report().errors()` and `.warnings()` from the validator and the schema's warning list.
- **Classifier-check key coverage**: the existing `@DependsOnClassifierCheck` orphan-detection test (mentioned in `Diagnostics.java:81`'s pattern) sees the two new keys (`source-location.absolute-path-source-name` and `validation-report.canonical-uri`); the matching `@LoadBearingClassifierCheck` annotations on `RewriteSchemaLoader` and `ValidationReport.canonicalUri` keep both pairs balanced.

No changes to `WatchErrorFormatter` or its tests: that path stays exactly as it is, the validator's output is shared between the two consumers.

## Non-goals

- Running the validator inside the LSP module on the buffer's tree-sitter parse. The LSP has neither a freshly classified `GraphitronSchema` nor the resources to build one per keystroke; the build pipeline writes, the LSP reads.
- Collapsing the LSP-side catalog-existence checks into the validator-driven channel. v1 keeps both; a follow-up item revisits.
- Eliminating the double schema build in `DevMojo.regenerate` (full pipeline + `buildOutput`). Separate refactor.
- Workspace-level diagnostics (LSP `workspace/diagnostic` requests). v1 uses the per-file push model only.
- Any LSP surface for no-usable-location errors. v1 drops them; the surface lands with its first real producer.
