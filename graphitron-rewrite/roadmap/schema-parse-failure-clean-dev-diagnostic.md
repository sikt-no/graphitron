---
id: R344
title: "Surface schema parse failures as clean dev-watch diagnostics, not infrastructure stack traces"
status: In Progress
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Surface schema parse failures as clean dev-watch diagnostics, not infrastructure stack traces

In the `graphitron:dev` watch loop, a schema file in an invalid intermediate state (the common, expected case while a developer is mid-edit, e.g. a half-typed `type` or an unclosed brace) dumps a multi-frame stack trace into the build log. `RewriteSchemaLoader.load` (`RewriteSchemaLoader.java:74-75`) already builds a clean, file-attributed one-line message via `attributedMessage(...)` ("Schema parse failed in `<file>` at line N column M: `<brief>`"), but then wraps it in a plain `RuntimeException` carrying the original `InvalidSyntaxException` as cause. `DevMojo.runGeneratorPass` (`DevMojo.java:296-300`) catches that as a bare `RuntimeException`, classifies it as the "infrastructure" bucket, and logs it *with* the throwable via `getLog().error(label + " failed (infrastructure)", e)`, so the entire graphql-java + executor call stack prints. The useful line (the attributed message) is buried under ~30 frames of noise on every keystroke that leaves the schema temporarily unparseable. By contrast, `ValidationFailedException` gets clean tree formatting through `WatchErrorFormatter` in the same method (`:291-295`), no stack trace. A syntactically invalid schema is just as expected and author-correctable during dev, and it is the most basic `Rejection.InvalidSchema` case there is; it should ride an equally clean surface.

## Current behavior (as found)

`RewriteSchemaLoader.load` is the producer. On `InvalidSyntaxException` it throws `new RuntimeException(attributedMessage(e), e)` (`:74-75`); on `IOException` / file-not-found / unreadable it also throws bare `RuntimeException` (`:76-77`, `openSource` `:174-181`). Those latter cases are genuine infrastructure failures and *should* keep the full trace.

Three generator entry points call the loader through `GraphQLRewriteGenerator.loadAttributedRegistry()` (`GraphQLRewriteGenerator.java:156-167`):

- `generate()` (`:86-88`) and `validate()` (`:135-143`) are the build-time pipeline; in the dev loop they run via `runGeneratorPass`, whose `catch (RuntimeException)` arm produces the unsightly trace.
- `buildOutput()` (`:107-117`) is the LSP triple (catalog + snapshot + `ValidationReport`); it also calls `loadAttributedRegistry()` first, so a parse failure propagates out of it before any catalog can be built. `DevMojo.regenerate` (`:215-223`), `rebuildCatalog` (`:242-251`), and `buildOutputQuietly` (`:265-274`) already catch that `RuntimeException` and log `e.getMessage()` only (no trace), demoting the snapshot to `Built.Previous` (or an empty catalog at startup); they are already quiet, and because the loader's message is `attributedMessage(e)` today they already print the file-attributed one-liner. The gap is specifically the `generate()` pass in `runGeneratorPass`.

The clean surface depends on a `SourceLocation` being available for the parse failure, and it is: `ValidationError` (`ValidationError.java`) carries a `SourceLocation` (file + line + col), and `MultiSourceReader.trackData(true)` (already enabled in the loader) populates `InvalidSyntaxException.getLocation()` with exactly that source name + line + column. So the location the dev surface (and the deferred LSP squiggle, below) needs is already on the exception.

## Design

The fix gives `runGeneratorPass` a *typed handle* to tell an expected, author-correctable parse failure apart from a genuine infrastructure failure, so the former rides the clean dev-loop surface and the latter keeps its diagnostic stack trace. Today both arrive as bare `RuntimeException`, which is exactly why parse failures land in the infrastructure-trace arm.

1. `RewriteSchemaLoader.load` throws a small typed `SchemaParseException` (new, package `no.sikt.graphitron.rewrite`, next to `ValidationFailedException`; `graphitron` module so both the plugin and the LSP are downstream) from the `InvalidSyntaxException` arm (`:74-75`) instead of a bare `RuntimeException`. It carries the `SourceLocation` from `e.getLocation()` and the brief (first-sentence) reason; `getMessage()` keeps the existing attributed full string, so any `catch (RuntimeException)` site prints the same attributed line it does today. The `IOException` / file-not-found arms keep throwing bare `RuntimeException` (still infrastructure).
2. `SchemaParseException` propagates unchanged through `loadAttributedRegistry()` (`:156-167`) and out of all three entry points (`generate`, `validate`, `buildOutput`). There is no translation step; `loadAttributedRegistry()` is **not** modified.
3. `runGeneratorPass` (`:285-300`) gains a `catch (SchemaParseException e)` arm ordered **before** the generic `catch (RuntimeException)` infrastructure arm. It logs the attributed one-liner (`e.getMessage()`) at error level *without* the throwable, so the dev log shows the single clean `file:line:col` line instead of ~30 frames. It sets `previousErrorKeys = null` (as the infrastructure arm does): a parse failure is not a validator verdict, so it must not feed `WatchErrorFormatter`'s delta tracker, and the next successful validation should report its full error set fresh rather than diffing against a phantom.

The tradeoff this design accepts: one `DevMojo` change (the new catch arm) that the rejected alternative below would avoid. What it buys back is decisive: attribution is preserved in every quiet path for free (they catch `RuntimeException` and print `getMessage()`, which stays the attributed line); `ValidationFailedException`'s documented invariants are left intact (no parse failure masquerades as a validator verdict); and no `ValidationError` / `Rejection` is fabricated for a pre-classification failure.

### Design fork: exception channel (decided: `SchemaParseException`, not `ValidationFailedException`)

An earlier draft of this item translated the parse failure into `ValidationFailedException(List.of(new ValidationError(null, Rejection.invalidSchema(brief), location)))` inside `loadAttributedRegistry()`, so `runGeneratorPass`'s *existing* `ValidationFailedException` arm rendered it through `WatchErrorFormatter` with **no `DevMojo` change at all**. That route is rejected; the "no `DevMojo` change" upside is false economy against three real costs:

- **It regresses the three already-quiet paths.** `regenerate` (`:219`), `rebuildCatalog` (`:247`), and `buildOutputQuietly` (`:270`) catch `RuntimeException` and log `e.getMessage()`. Today the loader's `RuntimeException` carries `attributedMessage(e)`, so they print "Schema parse failed in `<file>` at line N column M: ...". `ValidationFailedException.getMessage()` is the count string `"N schema validation error(s)"` (`ValidationFailedException.java:22`) and does not unpack `errors()`; those paths would degrade to "keeping previous: 1 schema validation error(s)", losing exactly the `file:line:col` attribution this item exists to surface. The `SchemaParseException` route keeps `getMessage()` = the attributed string, so those paths are genuinely unchanged.
- **It falsifies `ValidationFailedException`'s javadoc.** That javadoc (`ValidationFailedException.java:5-14`) asserts the exception is thrown when `GraphitronSchemaValidator` returns a non-empty error list, and that the generator logs every error in clang-style `file:line:col` form before throwing. A parse failure runs no validator pass and adds no clang logging, so routing through it would make the class's documented invariant false, forcing a javadoc rewrite to stay honest. The `SchemaParseException` route leaves `ValidationFailedException` untouched and honest.
- **It is a semantic stretch:** stamping a pre-classification parse failure as a validator verdict (`ValidationError` + `Rejection.InvalidSchema`). It does not break "validator mirrors classifier" (the parse error is upstream of the classifier and borrows the diagnostic transport rather than faking a verdict), but the `SchemaParseException` route avoids the stretch entirely.

Rendering the parse error through `WatchErrorFormatter` for visual uniformity with validation errors was also considered for the new arm, but it would require constructing a single throwaway `ValidationError` + `Rejection` purely as a rendering vehicle, reintroducing the same semantic stretch for no real gain on a single-line syntax error. The attributed one-liner is the cleaner surface for one parse error.

### Design fork: LSP red squiggle (recommend defer)

The architecture consult flagged that parse-failure is the canonical `InvalidSchema` case and asked whether it should also surface as an editor diagnostic (red squiggle at the offending `file:line:col`), not just clean the dev log. It cannot fall out for free, and it runs against an existing deliberate policy. `Diagnostics.validatorDiagnostics` (`Diagnostics.java:148-152, 169-179`) emits validator diagnostics **only** on a `Built.Current` snapshot; on parse failure the snapshot is demoted to `Built.Previous` and all squiggles are silenced (R139 freshness-aware silence: "a red squiggle the developer cannot fix by rewriting their schema is the noise we are trying to avoid"). A *syntax error* is the one diagnostic the developer can fix by rewriting, so it arguably warrants a carve-out, but delivering it means reshaping `buildOutput()` to return a report-only output (empty/previous artifacts + a `ValidationReport` carrying the parse error) instead of throwing, and adding a parse-error exception to the silence policy. That is a separate policy change with its own surface, and it is the consumer the `SchemaParseException`'s carried `location` + `brief` fields are positioned to feed.

Recommendation: keep this item to the reported complaint, the dev-log noise, which the design above fixes cleanly. File the LSP-squiggle reshape as a follow-on Backlog item referencing this one. (If the reviewer wants the squiggle folded in here, it is a bounded addition to `buildOutput` + the silence policy, but it changes `buildOutput`'s throw-vs-report contract and should be decided deliberately.)

## Implementation

- `graphitron/.../rewrite/SchemaParseException.java` (new): `extends RuntimeException`, fields `SourceLocation location` (nullable) and `String brief`; constructor takes the attributed full message, the brief, the location, and the cause, calling `super(attributedFullMessage, cause)` so `getMessage()` is the attributed string. `@SuppressWarnings("serial")` like `ValidationFailedException`. `location` / `brief` are carried for the deferred LSP-squiggle follow-on; the dev-loop arm only reads `getMessage()`.
- `graphitron/.../rewrite/schema/RewriteSchemaLoader.java`: throw `SchemaParseException` from the `InvalidSyntaxException` arm (`:74-75`), passing `attributedMessage(e)` (full), `firstSentence(...)` (brief), `e.getLocation()`, and `e` as cause. `attributedMessage` / `firstSentence` are reused. The `IOException` arm (`:76-77`) is unchanged (bare `RuntimeException`).
- `graphitron/.../rewrite/GraphQLRewriteGenerator.java`: **no change.** `SchemaParseException` propagates through `loadAttributedRegistry()` (`:156-167`) unmodified, uniformly out of all three entry points.
- `graphitron-maven-plugin/.../maven/DevMojo.java`: add a `catch (SchemaParseException e)` arm to `runGeneratorPass` (`:285-300`), ordered before `catch (RuntimeException)`; it logs `"graphitron:dev: " + label + " failed: " + e.getMessage()` at error level *without* the throwable, and sets `previousErrorKeys = null`. The three quiet paths (`regenerate` / `rebuildCatalog` / `buildOutputQuietly`) are **not** touched; their `catch (RuntimeException)` continues to print the now-`SchemaParseException`'s attributed `getMessage()`.

## Tests

- Unit (`graphitron`): `RewriteSchemaLoader.load` alone throws `SchemaParseException` (not bare `RuntimeException`) for a syntax error, with `getLocation()` carrying the offending file's `SourceLocation` (source name + line + col) and `getMessage()` the attributed string; and still throws bare `RuntimeException` for a missing/unreadable file. A malformed schema fed through `loadAttributedRegistry()` / `generate()` propagates that same `SchemaParseException` (assert type + attributed message), confirming there is no translation.
- DevMojo (`graphitron-maven-plugin`): `runGeneratorPass` on a malformed schema logs the attributed one-liner and does **not** log a throwable / stack trace, i.e. the infrastructure arm is not taken. If `runGeneratorPass` is awkward to drive directly, assert the discrimination at the catch-ordering level: a `SchemaParseException` is handled by the parse arm and a non-parse `RuntimeException` by the infrastructure arm.
- One-shot path: the `validate` / `generate` mojo still fails the build on a syntactically broken schema (the exception type changed from bare `RuntimeException` to `SchemaParseException`, still a `RuntimeException`, so the build must still fail). **Decision (pinning the prior hedge):** no separate clang-style `file:line:col` logging step is added for the one-shot path. `SchemaParseException.getMessage()` is already the attributed `file:line:col` one-liner, so the build-failure message carries the location without an extra SLF4J emission. This deliberately does **not** mirror `ValidationFailedException`'s per-error clang logging, because there is exactly one parse error and its attributed message already names the site.
- Regression guard for the quiet paths: assert `SchemaParseException.getMessage()` equals the `attributedMessage(...)` output, so the `regenerate` / `rebuildCatalog` / `buildOutputQuietly` arms keep printing an attributed line (not a count string) on parse failure. A direct assertion on the exception covers this without driving the Mojo.

## Out of scope

- The LSP red-squiggle for parse failures (see the fork above); deferred to a follow-on item, which is the consumer the carried `location` / `brief` are positioned to feed.
- Any change to the wire/attribution mechanics (`MultiSourceReader.trackData`, the `terminated(...)` newline shim); the `SourceLocation` it produces is consumed as-is.
- Any change to `ValidationFailedException`; it is intentionally left untouched (routing parse failure through it is the rejected fork above).

## Risks / notes

- Catch ordering in `runGeneratorPass` is load-bearing: the `SchemaParseException` arm must precede the generic `RuntimeException` arm, or parse failures fall straight back into the infrastructure-trace bucket this item removes them from.
- `SchemaParseException` is a sibling of `ValidationFailedException`, not a subtype; the two ride parallel clean surfaces in `runGeneratorPass` (validator verdict vs parse failure) and must not be merged, on pain of re-falsifying the `ValidationFailedException` invariant.
