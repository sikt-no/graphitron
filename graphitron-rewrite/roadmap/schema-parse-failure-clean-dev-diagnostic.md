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

In the `graphitron:dev` watch loop, a schema file in an invalid intermediate state (the common, expected case while a developer is mid-edit, e.g. a half-typed `type` or an unclosed brace) dumps a multi-frame stack trace into the build log. `RewriteSchemaLoader.load` (`RewriteSchemaLoader.java:74-75`) already builds a clean, file-attributed one-line message via `attributedMessage(...)` ("Schema parse failed in `<file>` at line N column M: `<brief>`"), but then wraps it in a plain `RuntimeException` carrying the original `InvalidSyntaxException` as cause. `DevMojo.runGeneratorPass` (`DevMojo.java:296-300`) catches that as a bare `RuntimeException`, classifies it as the "infrastructure" bucket, and logs it *with* the throwable via `getLog().error(label + " failed (infrastructure)", e)`, so the entire graphql-java + executor call stack prints. The useful line (the attributed message) is buried under ~30 frames of noise on every keystroke that leaves the schema temporarily unparseable. By contrast, `ValidationFailedException` gets clean tree formatting through `WatchErrorFormatter` in the same method (`:291-295`), no stack trace. A syntactically invalid schema is just as expected and author-correctable during dev, and it is the most basic `Rejection.InvalidSchema` case there is; it should ride the same surface.

## Current behavior (as found)

`RewriteSchemaLoader.load` is the producer. On `InvalidSyntaxException` it throws `new RuntimeException(attributedMessage(e), e)` (`:74-75`); on `IOException` / file-not-found / unreadable it also throws bare `RuntimeException` (`:76-77`, `openSource` `:174-181`). Those latter cases are genuine infrastructure failures and *should* keep the full trace.

Three generator entry points call the loader through `GraphQLRewriteGenerator.loadAttributedRegistry()` (`GraphQLRewriteGenerator.java:156-167`):

- `generate()` (`:86-88`) and `validate()` (`:135-143`) are the build-time pipeline; in the dev loop they run via `runGeneratorPass`, whose `catch (RuntimeException)` arm produces the unsightly trace.
- `buildOutput()` (`:107-117`) is the LSP triple (catalog + snapshot + `ValidationReport`); it also calls `loadAttributedRegistry()` first, so a parse failure propagates out of it before any catalog can be built. `DevMojo.regenerate` (`:215-223`) and `rebuildCatalog` (`:242-251`) already catch that `RuntimeException` and log `e.getMessage()` only (no trace), demoting the snapshot to `Built.Previous`; they are already quiet. The gap is specifically the `generate()` pass in `runGeneratorPass`.

The validation channel that the clean surface rides is already file-attributed end to end: `ValidationError` (`ValidationError.java`) carries a `SourceLocation` (file + line + col) and a typed `Rejection`; `MultiSourceReader.trackData(true)` (already enabled in the loader) populates `InvalidSyntaxException.getLocation()` with exactly that source name + line + column. So a parse failure has everything a `ValidationError` needs.

## Design

The fix routes parse failure onto the existing typed validation channel rather than inventing a parallel one:

1. `RewriteSchemaLoader.load` throws a small typed `SchemaParseException` (new, package `no.sikt.graphitron.rewrite`, next to `ValidationFailedException`; `graphitron` module so both the plugin and the LSP are downstream) instead of a bare `RuntimeException`. It carries the `SourceLocation` from `e.getLocation()` and the brief (first-sentence) message; `getMessage()` keeps the existing attributed full string. The `IOException` / file-not-found arms keep throwing bare `RuntimeException` (still infrastructure).
2. `GraphQLRewriteGenerator.loadAttributedRegistry()` catches `SchemaParseException` and rethrows it as `ValidationFailedException(List.of(new ValidationError(null, Rejection.invalidSchema(brief), location)))` (coordinate `null` = schema-wide; `Rejection.invalidSchema(...)` is the existing `InvalidSchema.Structural` factory, so **no new `Rejection` arm** and no `typed-rejection.adoc` doc-coverage obligation). Doing the translation here means all three entry points get uniform `ValidationFailedException` behavior.

With (2), **no `DevMojo` change is required**: `runGeneratorPass`'s existing `catch (ValidationFailedException)` arm (`:291-295`) renders the parse error through `WatchErrorFormatter`, no stack trace. The "infrastructure" arm then only fires for genuine infrastructure failures, which is what it is for. `buildOutput()` now throws `ValidationFailedException` (a `RuntimeException` subtype) where it threw a plain `RuntimeException`; `regenerate` / `rebuildCatalog` / `buildOutputQuietly` catch `RuntimeException`, so their message-only behavior is unchanged.

### Design fork: LSP red squiggle (recommend defer)

The architecture consult flagged that parse-failure is the canonical `InvalidSchema` case and asked whether it should also surface as an editor diagnostic (red squiggle at the offending `file:line:col`), not just clean the dev log. It cannot fall out for free, and it runs against an existing deliberate policy. `Diagnostics.validatorDiagnostics` (`Diagnostics.java:148-152, 169-179`) emits validator diagnostics **only** on a `Built.Current` snapshot; on parse failure the snapshot is demoted to `Built.Previous` and all squiggles are silenced (R139 freshness-aware silence: "a red squiggle the developer cannot fix by rewriting their schema is the noise we are trying to avoid"). A *syntax error* is the one diagnostic the developer can fix by rewriting, so it arguably warrants a carve-out, but delivering it means reshaping `buildOutput()` to return a report-only output (empty/previous artifacts + a `ValidationReport` carrying the parse error) instead of throwing, and adding a parse-error exception to the silence policy. That is a separate policy change with its own surface.

Recommendation: keep this item to the reported complaint, the dev-log noise, which the design above fixes cleanly while putting parse failures on the shared typed channel. File the LSP-squiggle reshape as a follow-on Backlog item referencing this one. (If the reviewer wants the squiggle folded in here, it is a bounded addition to `buildOutput` + the silence policy, but it changes `buildOutput`'s throw-vs-report contract and should be decided deliberately.)

## Implementation

- `graphitron/.../rewrite/SchemaParseException.java` (new): `extends RuntimeException`, fields `SourceLocation location` (nullable) and the brief reason; `@SuppressWarnings("serial")` like `ValidationFailedException`.
- `graphitron/.../rewrite/schema/RewriteSchemaLoader.java`: throw `SchemaParseException` from the `InvalidSyntaxException` arm (`:74-75`), passing `e.getLocation()`, the `firstSentence(...)` brief, the attributed full message, and `e` as cause. `attributedMessage` / `firstSentence` are reused.
- `graphitron/.../rewrite/GraphQLRewriteGenerator.java`: in `loadAttributedRegistry()` (`:156-167`), wrap the `RewriteSchemaLoader.load(...)` call to translate `SchemaParseException` into `ValidationFailedException`. When `location` (or its `sourceName`) is `null`, emit a `ValidationError` with a `null` location so `WatchErrorFormatter` still groups it sanely.

## Tests

- Unit (`graphitron`): a malformed schema fed through `loadAttributedRegistry()` (or `generate()`) throws `ValidationFailedException` whose single `ValidationError` carries the offending file's `SourceLocation` and an `InvalidSchema.Structural` rejection. Assert `RewriteSchemaLoader.load` alone throws `SchemaParseException` (not bare `RuntimeException`) for a syntax error, and still throws bare `RuntimeException` for a missing/unreadable file.
- Confirm `WatchErrorFormatter` renders a coordinate-`null`, `SourceLocation`-bearing parse error without NPE and without a stack trace (the format the dev loop now prints). A pure rendering test on `WatchErrorFormatter.format(List.of(parseError), ...)` is enough; no need to drive the Mojo.
- Confirm the one-shot `validate` / `generate` mojo path still fails the build on a syntactically broken schema (the exception type changed from `RuntimeException` to `ValidationFailedException`, but the build must still fail). Decide whether the one-shot path should also log the parse error in clang-style `file:line:col` (mirroring `ValidationFailedException`'s existing per-error SLF4J contract) or whether the exception message suffices.

## Out of scope

- The LSP red-squiggle for parse failures (see the fork above); deferred to a follow-on item.
- Any change to the wire/attribution mechanics (`MultiSourceReader.trackData`, the `terminated(...)` newline shim); the `SourceLocation` it produces is consumed as-is.
