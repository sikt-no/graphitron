---
id: R119
title: "LSP directive registry sourced from directives.graphqls"
status: Backlog
bucket: architecture
priority: 5
theme: lsp
depends-on: []
---

# LSP directive registry sourced from directives.graphqls

The LSP module's directive vocabulary lives in a hand-written registry at
`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/DirectiveDefinitions.java`
(`ENTRIES = List.of(new DirectiveDef(...))`). Production directives are defined
in `graphitron/src/main/resources/.../schema/directives.graphqls`, and the LSP
registry has to be updated by hand to track changes there. This is a real drift
surface: R110 renamed `@batchKeyLifter` → `@sourceRow` and changed its argument
shape from `lifter: ExternalCodeReference + targetColumns: [String!]!` to flat
`(className: String!, method: String!)`, but the LSP registry was not updated.
The build stayed green because the LSP's own tests (`DirectiveDefinitionsTest`,
`DiagnosticsTest`, `ClassNameCompletionsTest`) are self-consistent against the
hand-written registry — the registry's drift from the actual directive surface
is invisible to the build. IDE consumers will surface "unknown directive" on
`@sourceRow` and continue to autocomplete a removed `@batchKeyLifter` with
non-existent args.

The fix the user asked for: source the registry from `directives.graphqls`
itself so the LSP can never drift again. The class-level Javadoc on
`DirectiveDefinitions` already names this as a "tractable follow-on" (added
during Phase 1 of R93): "Parsing `directives.graphqls` at LSP startup is a
tractable follow-on but adds startup cost and a runtime SDL dependency without
changing the consumer surface; the spec's 'open architectural decisions' leaves
the population strategy as an implementation detail." This item makes that
follow-on real.

The work splits into two independent slices that the spec body should address:

1. **Acute parity** (small): land a `@sourceRow` entry on the existing hand-
   written registry and remove `@batchKeyLifter`, so IDE users immediately stop
   getting bad diagnostics. Note: `@sourceRow` uses flat top-level args, not the
   `ExternalCodeReference` wrapper, so this requires extending `DirectiveDef` /
   `ArgDef` / `InputTypeBinding` to express non-`ExternalCodeReference` arg
   shapes (the existing types assume the input type is `ExternalCodeReference`
   and the binding semantics are the unwrap-into-`(className, method, ...)`
   shape). Sweep the three test files (`DirectiveDefinitionsTest`,
   `DiagnosticsTest`, `ClassNameCompletionsTest`) and the `VALIDATE_METHOD` set
   in `Diagnostics.java:45`. This slice closes the immediate user-facing gap
   left by R110.

2. **Architectural fix** (the real ask): replace the hand-written `ENTRIES`
   list with a parser that reads `directives.graphqls` and emits the same
   `DirectiveDef` records. Decide where the parse runs (LSP startup vs. build-
   time generation into a resource), how the LSP module gets to the SDL file
   (classpath resource ship from the `graphitron` module vs. project-relative
   path lookup at LSP startup), and how `argMapping:` / `nestedPath` semantics
   are expressed in SDL or layered on top. The Javadoc flags startup cost and
   runtime SDL dependency as the two trade-offs the design has to address.

Once the architectural fix lands, the consumer surface (`all()`, `byName()`,
`argsByInputType()`) should be unchanged so existing call sites (Diagnostics,
ClassNameCompletions, DirectiveDefinitionsTest) ride through unchanged.

Surfaced during the R110 In Review → Done approval (commit `5176f2f8` on
`claude/r110-approval`). See the R110 changelog entry's "Findings noted at
approval" section for the original observation.
