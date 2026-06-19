---
id: R342
title: "Surface schema parse failures as clean dev-watch diagnostics, not infrastructure stack traces"
status: Spec
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Surface schema parse failures as clean dev-watch diagnostics, not infrastructure stack traces

In the `graphitron:dev` watch loop, a schema file in an invalid intermediate state (the common, expected case while a developer is mid-edit, e.g. a half-typed `type` or an unclosed brace) dumps a multi-frame stack trace into the build log. `RewriteSchemaLoader.load` (`RewriteSchemaLoader.java:74-75`) already builds a clean, file-attributed one-line message via `attributedMessage(...)` ("Schema parse failed in `<file>` at line N column M: `<brief>`"), but then wraps it in a plain `RuntimeException` carrying the original `InvalidSyntaxException` as cause. `DevMojo.runGeneratorPass` (`DevMojo.java:296-300`) catches that as a bare `RuntimeException`, classifies it as the "infrastructure" bucket, and logs it *with* the throwable via `getLog().error(label + " failed (infrastructure)", e)`, so the entire graphql-java + executor call stack prints. The useful line (the attributed message) is buried under ~30 frames of noise on every keystroke that leaves the schema temporarily unparseable. By contrast, `ValidationFailedException` gets clean tree formatting through `WatchErrorFormatter` in the same method (`:291-295`), no stack trace. A syntactically invalid schema is just as expected and author-correctable during dev; it deserves the same clean, file-attributed one-liner, not a stack trace.

## Current behavior (as found)

`RewriteSchemaLoader.load` is the producer. On `InvalidSyntaxException` it throws `new RuntimeException(attributedMessage(e), e)` (`:74-75`); on `IOException` / file-not-found / unreadable it also throws bare `RuntimeException` (`:76-77`, `openSource` `:174-181`). Those latter cases are genuine infrastructure failures and *should* keep the full trace.

Three generator entry points call the loader through `GraphQLRewriteGenerator.loadAttributedRegistry()` (`GraphQLRewriteGenerator.java:156-167`):

- `generate()` (`:86-88`) and `validate()` (`:135-143`) are the build-time pipeline; in the dev loop they run via `runGeneratorPass`, whose `catch (RuntimeException)` arm produces the unsightly trace.
- `buildOutput()` (`:107-117`) is the LSP triple (catalog + snapshot + `ValidationReport`); it also calls `loadAttributedRegistry()` first, so a parse failure propagates out of it before any catalog can be built. `DevMojo.regenerate` (`:215-223`), `rebuildCatalog` (`:242-251`), and `buildOutputQuietly` (`:265-274`) already catch that `RuntimeException` and log `e.getMessage()` only (no trace), demoting the snapshot to `Built.Previous`; they are already quiet, **and `e.getMessage()` today is the attributed one-liner** (`buildOutput` propagates the same `RuntimeException(attributedMessage(e), …)` the loader threw). The gap is specifically the `generate()` pass in `runGeneratorPass`.

So the fix has one hard constraint beyond "stop the trace in `runGeneratorPass`": whatever the loader throws, its `getMessage()` must stay the attributed one-liner, because three already-quiet paths print `e.getMessage()` verbatim and would otherwise regress to a content-free message. The brief and the offending file's `SourceLocation` are both available at the throw site: `MultiSourceReader.trackData(true)` (already enabled in the loader) populates `InvalidSyntaxException.getLocation()` with the source name + line + column that `attributedMessage(...)` already formats into the message.

## Design

The fix introduces one typed exception at the producer and gives it a dedicated, message-only catch arm in the one place that currently dumps a trace. It does **not** route the parse failure through the validator's `ValidationError` / `ValidationReport` channel (see the rejected alternative below for why).

1. `RewriteSchemaLoader.load` throws a small typed `SchemaParseException` (new, package `no.sikt.graphitron.rewrite`, next to `ValidationFailedException`; `graphitron` module so both the plugin and the LSP are downstream) from the `InvalidSyntaxException` arm instead of a bare `RuntimeException`. `getMessage()` is the existing `attributedMessage(...)` string (preserving the one-liner the three quiet paths print), and it carries the `SourceLocation` from `e.getLocation()` as a structured field for the deferred LSP follow-on. The `IOException` / file-not-found arms keep throwing bare `RuntimeException` (still infrastructure).
2. `SchemaParseException` propagates unchanged through `loadAttributedRegistry()` and out of all three entry points; it is a `RuntimeException`, so no signature changes.
3. `DevMojo.runGeneratorPass` adds a `catch (SchemaParseException e)` arm **ahead of** the generic `catch (RuntimeException e)` arm (`:296-300`), logging message-only: `getLog().error("graphitron:dev: " + label + " failed: " + e.getMessage())`, no throwable, then resetting `previousErrorKeys = null` and returning `false` like the infrastructure arm. The "infrastructure" arm below it then only fires for genuine infrastructure failures, which is what it is for.

The three quiet paths (`regenerate`, `rebuildCatalog`, `buildOutputQuietly`) need **no change** and genuinely keep their current output: they catch `RuntimeException`, `SchemaParseException` is one, and its `getMessage()` is still the attributed one-liner. Attribution is preserved everywhere for free.

### Rejected alternative: route through the validation channel

The first draft of this spec translated the parse failure into a `ValidationFailedException(List.of(new ValidationError(null, Rejection.invalidSchema(brief), location)))` inside `loadAttributedRegistry`, banking "no `DevMojo` change" by reusing the existing `catch (ValidationFailedException)` arm. Rejected on review for three costs the propagate design avoids:

- **Attribution regression in the quiet paths.** `regenerate` / `rebuildCatalog` / `buildOutputQuietly` log `e.getMessage()`, and `ValidationFailedException.getMessage()` is the count string `"N schema validation error(s)"` (`ValidationFailedException.java:22`); they do not unpack `errors()`. So those three paths would degrade from the attributed one-liner to "keeping previous: 1 schema validation error(s)", losing exactly the `file:line:col` this item exists to surface.
- **False invariant.** `ValidationFailedException`'s javadoc asserts it is thrown when `GraphitronSchemaValidator` returns errors, and that the generator logs every error clang-style before throwing. A parse failure is upstream of the classifier and the validator; throwing it from `loadAttributedRegistry` satisfies neither, making the javadoc lie unless separately patched.
- **Semantic stretch.** It masquerades a pre-classification parse failure as a validator verdict. (It does *not* break "validator mirrors classifier": the parse error is upstream of the classifier and merely borrows the diagnostic transport. But borrowing it buys nothing here once the LSP squiggle is deferred.)

The propagate design costs one small catch arm in `DevMojo` instead of zero, and in exchange keeps attribution in every path, keeps `ValidationFailedException` honest, and never fakes a verdict. For a dev-log-only fix that is the better trade.

### Design fork: LSP red squiggle (recommend defer)

The architecture consult flagged that parse-failure is the canonical `InvalidSchema` case and asked whether it should also surface as an editor diagnostic (red squiggle at the offending `file:line:col`), not just clean the dev log. It cannot fall out for free, and it runs against an existing deliberate policy. `Diagnostics.validatorDiagnostics` (`Diagnostics.java:148-152, 169-179`) emits validator diagnostics **only** on a `Built.Current` snapshot; on parse failure the snapshot is demoted to `Built.Previous` and all squiggles are silenced (R139 freshness-aware silence: "a red squiggle the developer cannot fix by rewriting their schema is the noise we are trying to avoid"). A *syntax error* is the one diagnostic the developer can fix by rewriting, so it arguably warrants a carve-out, but delivering it means reshaping `buildOutput()` to return a report-only output (empty/previous artifacts + a `ValidationReport` carrying the parse error) instead of throwing, and adding a parse-error exception to the silence policy. That is a separate policy change with its own surface.

Recommendation: keep this item to the reported complaint, the dev-log noise, which the design above fixes cleanly. The `SchemaParseException`'s structured `SourceLocation` field is the on-ramp for the follow-on: file the LSP-squiggle reshape as a separate Backlog item referencing this one, where it would convert the `SchemaParseException` into a `ValidationError` at the `buildOutput` boundary and carve it out of the silence policy. (If the reviewer wants the squiggle folded in here, it is a bounded addition to `buildOutput` + the silence policy, but it changes `buildOutput`'s throw-vs-report contract and should be decided deliberately.)

## Implementation

- `graphitron/.../rewrite/SchemaParseException.java` (new): `extends RuntimeException`, constructed with the attributed full message (becomes `getMessage()`), a nullable `SourceLocation location` accessor, and the `InvalidSyntaxException` as cause; `@SuppressWarnings("serial")` like `ValidationFailedException`.
- `graphitron/.../rewrite/schema/RewriteSchemaLoader.java`: in the `InvalidSyntaxException` arm (`:74-75`), throw `new SchemaParseException(attributedMessage(e), e.getLocation(), e)` instead of `new RuntimeException(attributedMessage(e), e)`. `attributedMessage` is reused unchanged; the `IOException` / file-not-found arms are untouched.
- `graphitron/.../rewrite/maven/DevMojo.java`: add a `catch (SchemaParseException e)` arm in `runGeneratorPass` (`:296`, ahead of the generic `RuntimeException` arm) that logs `e.getMessage()` message-only (no throwable), sets `previousErrorKeys = null`, and returns `false`. No change to `loadAttributedRegistry`, `regenerate`, `rebuildCatalog`, or `buildOutputQuietly`.

## Tests

- Unit (`graphitron`): assert `RewriteSchemaLoader.load` throws `SchemaParseException` (not bare `RuntimeException`) for a syntactically broken schema, that its `getMessage()` carries the offending file name + line + column, and that its `location()` is non-null; assert it still throws a bare `RuntimeException` for a missing/unreadable file (the infrastructure path is unchanged). Confirm `SchemaParseException` propagates out of `loadAttributedRegistry()` / `generate()` unchanged (not translated to anything else).
- `DevMojo`-tier (`graphitron-maven-plugin`): in whatever harness already exercises `runGeneratorPass` / the watch loop, assert a parse failure is logged message-only (the attributed one-liner present, no stack-trace frames) and that the generator pass reports failure. If no such harness exists, the message-only rendering is a one-line `getLog().error(msg)` verified by inspection; do not stand up a Mojo harness solely for this. (Confirm during implementation which applies.)
- Confirm the one-shot `validate` / `generate` mojo path still fails the build on a syntactically broken schema. The thrown type changes from a bare `RuntimeException` to `SchemaParseException` (still a `RuntimeException`), so the build still fails; the surfaced message is the attributed one-liner. No clang-style validator logging is involved (the parse failure never enters the validator's error stream), so there is no `ValidationFailedException` contract to mirror or javadoc to amend.

## Out of scope

- The LSP red-squiggle for parse failures (see the fork above); deferred to a follow-on item.
- Any change to the wire/attribution mechanics (`MultiSourceReader.trackData`, the `terminated(...)` newline shim); the `SourceLocation` it produces is consumed as-is.
