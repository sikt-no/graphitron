---
id: R605
title: "LSP unknown-class diagnostic misses jar-resident classes on @scalarType"
status: Backlog
bucket: bug
theme: lsp
priority: 3
depends-on: []
created: 2026-08-07
last-updated: 2026-08-07
---

# LSP unknown-class diagnostic misses jar-resident classes on @scalarType

`scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")` generates fine but red-squiggles in the editor: `Unknown class 'graphql.scalars.ExtendedScalars' on @scalarType. Not found in compiled target/classes.` The two paths read different classpaths. Codegen resolves the constant reflectively through `RewriteContext.codegenLoader`, a `URLClassLoader` the mojo builds over `project.getCompileClasspathElements()` (jars included, `AbstractRewriteMojo.buildCodegenLoader`). The LSP catalog instead reads `CompletionData.externalReferences()`, built by `CatalogBuilder.buildExternalReferences` from `RewriteContext.classpathRoots()` — reactor compile-output *directories* only, and `ClasspathScanner.scan` hard-skips any root that fails `Files.isDirectory`, so no jar is ever opened. `graphql.scalars.ExtendedScalars` lives in the `graphql-java-extended-scalars` jar, so it is structurally absent from the scan and `Diagnostics.validateScalarTypeClasspath` reports every reference to it as unknown. The pre-compile empty-scan carve-out does not help: the scan is non-empty, just incomplete.

The same gap silently disables completion at that coordinate. `ScalarTypeCompletions` sources its candidates from `ExternalReference.scalarConstants()`, which `ClasspathScanner.readScalarConstants` fills from `.class` files under the same directory roots, so the library constants that are the documented primary use case (`docs/manual/how-to/custom-scalars.adoc`) can never be offered. The scan's directories-only scope is a deliberate premise, recorded on `RewriteContext.classpathRoots`: "External jars (from `~/.m2`) are not scanned: services live in reactor source, not third-party libraries." That premise holds for `@service` / `@condition` / `@record` and fails exactly for `@scalarType`, whose canonical target *is* a third-party library constant.

Design fork for Spec: (a) widen the scan to the full compile classpath, which fixes both surfaces at once but pulls every dependency class into `@service` / `@enum` / `@record` completion and diagnostics and costs a jar walk per catalog build; (b) keep the class census directory-scoped and add a narrow jar-resident *scalar-constant* census keyed on the `Lgraphql/schema/GraphQLScalarType;` field descriptor `readScalarConstants` already matches on, so only the coordinate with the third-party premise gets the wider classpath; (c) drop the unknown-class arm of `validateScalarTypeClasspath` and leave the class check to the build-tier `ScalarTypeResolver`, accepting no completion. (b) looks right: it fixes the false diagnostic and the missing completions together, keeps the widened scope confined to the one coordinate whose vocabulary genuinely lives in libraries, and the descriptor filter bounds the jar walk's output to a handful of fields per jar. Whichever arm is chosen, the fix needs the mojo to plumb `resolveCompileClasspath()` (or its jar subset) onto `RewriteContext` alongside `classpathRoots`, and the premise sentence on `RewriteContext.classpathRoots` needs rewriting to say which coordinates it does and does not cover.

Regression guards: an LSP diagnostics test asserting a jar-resident `@scalarType` reference raises nothing, and a completion test asserting `graphql.scalars.ExtendedScalars.Date` is offered on `scalar LocalDate @scalarType(scalar: "|")`. `graphitron-sakila-example` already carries the live fixture (`scalar LocalDate` / `scalar BigDecimal` bound to `ExtendedScalars` constants), so the build-through path is covered; what is missing is a test at the LSP tier that pins the two classpaths agreeing.
