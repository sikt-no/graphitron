---
id: R342
title: "Surface schema parse failures as clean dev-watch diagnostics, not infrastructure stack traces"
status: Backlog
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Surface schema parse failures as clean dev-watch diagnostics, not infrastructure stack traces

In the `graphitron:dev` watch loop, a schema file in an invalid intermediate state (the common, expected case while a developer is mid-edit, e.g. a half-typed `type` or an unclosed brace) produces a multi-frame stack trace in the build log. `RewriteSchemaLoader.load` (`RewriteSchemaLoader.java:74-75`) already builds a clean, file-attributed one-line message via `attributedMessage(...)` ("Schema parse failed in &lt;file&gt; at line N column M: &lt;brief&gt;"), but then wraps it in a plain `RuntimeException` carrying the original `InvalidSyntaxException` as cause. `DevMojo.runGeneratorPass` (`DevMojo.java:296-300`) catches that as a bare `RuntimeException`, classifies it as the "infrastructure" bucket, and logs it with the throwable via `getLog().error(label + " failed (infrastructure)", e)`, so the entire graphql-java + executor call stack is dumped. The useful information (the attributed message) is buried under ~30 frames of noise on every keystroke that leaves the schema temporarily unparseable. By contrast, `ValidationFailedException` gets clean tree formatting through `WatchErrorFormatter` in the same method (`:291-295`); a syntactically invalid schema is just as expected and user-correctable during dev, and should be surfaced the same way: the attributed one-liner, no stack trace, ideally distinguished from genuine infrastructure failures (which legitimately warrant the full trace). The natural shape is a dedicated checked-or-typed exception thrown by `RewriteSchemaLoader` (e.g. `SchemaParseException`) that `runGeneratorPass` catches ahead of the generic `RuntimeException` arm and logs as a message-only diagnostic; the existing `attributedMessage` content is reused verbatim. Worth confirming the same clean-vs-noisy split for the other two call sites that already tolerate bad mid-edit schemas, `DevMojo.regenerate`'s catalog-refresh (`:218-223`) and `rebuildCatalog` (`:242-251`), both of which currently log `e.getMessage()` only and so are already quiet, plus `buildOutputQuietly` (`:269-273`); the gap is specifically the `generate()` pass in `runGeneratorPass`.
