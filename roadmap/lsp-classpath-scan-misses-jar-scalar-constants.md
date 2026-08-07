---
id: R605
title: "Class census reads the compile classpath, not just reactor output directories"
status: Spec
bucket: bug
theme: lsp
priority: 3
depends-on: []
created: 2026-08-07
last-updated: 2026-08-07
---

# Class census reads the compile classpath, not just reactor output directories

## Problem

`scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")` generates fine but red-squiggles in the editor: `Unknown class 'graphql.scalars.ExtendedScalars' on @scalarType. Not found in compiled target/classes.` The two paths read different classpaths. Codegen resolves the constant reflectively through `RewriteContext.codegenLoader`, a `URLClassLoader` the mojo builds over `project.getCompileClasspathElements()` (jars included, `AbstractRewriteMojo.buildCodegenLoader`). The LSP catalog instead reads `CompletionData.externalReferences()`, built by `CatalogBuilder.buildExternalReferences` from `RewriteContext.classpathRoots()` — reactor compile-output *directories* only, and `ClasspathScanner.scan` hard-skips any root that fails `Files.isDirectory`, so no jar is ever opened. `graphql.scalars.ExtendedScalars` lives in the `graphql-java-extended-scalars` jar, so it is structurally absent from the scan and `Diagnostics.validateScalarTypeClasspath` reports every reference to it as unknown. The pre-compile empty-scan carve-out does not help: the scan is non-empty, just incomplete.

The same gap silently disables completion at that coordinate. `ScalarTypeCompletions` sources its candidates from `ExternalReference.scalarConstants()`, which `ClasspathScanner.readScalarConstants` fills from `.class` files under the same directory roots, so the library constants that are the documented primary use case (`docs/manual/how-to/custom-scalars.adoc`) can never be offered. The scan's directories-only scope is a deliberate premise, recorded on `RewriteContext.classpathRoots`: "External jars (from `~/.m2`) are not scanned: services live in reactor source, not third-party libraries." That premise holds for `@service` / `@condition` / `@record` and fails exactly for `@scalarType`, whose canonical target *is* a third-party library constant.

## Decision

The limitation goes. The directories-only premise was never argued from a property of the schema language, only from a guess about where consumer vocabulary lives, and `@scalarType` falsifies the guess outright. Narrowing the fix to a scalar-constant-only jar census (considered and rejected) would keep the same class of bug latent at every other class-bearing coordinate: a `@record` naming a DTO from a shared internal library, a `@service` naming an interface published as a jar, an `@enum` naming a library enum, all resolve at codegen and all red-squiggle today. The census becomes the set of classes on the compile classpath, which is exactly what `codegenLoader` can resolve.

## What R595 already settles

Most of what this item first proposed belongs to `graphitron-model-captures-facts` (R595) and is cut from the plan below rather than duplicated.

The *two mechanisms* framing is R595's to dissolve. Its leave-out on resolution facts states the rule directly: whether a written table, constraint, or class name denotes a real one is a **detection over the catalog and extension families**. Once the LSP's unknown-class arm and the build-tier `ScalarTypeResolver` rejection are both that one detection over `extension_class`, they cannot read different classpaths, because there is only one query. The completion side has its relation waiting too: `extension_scalar_constant`, keyed to `extension_class`, is exactly what `ScalarTypeCompletions` reads once it migrates.

R595 also decides the question this spec had left open. The draft asked whether the fact store should take a narrower view of the census than the LSP if the `extension_` insert cost proved material. It should not, and not as a judgement call: capture is total with no reachability pruning, and the agreement driver registers the scanner censuses under its **equality** arm, so the store *is* the scanner's emission by test. A scoped view for capture would fail that anchor. The slice is deleted, not deferred.

What R595 does **not** settle is the scope of the census itself. It fills `extension_` "from the `ClasspathScanner` emission" and inherits whatever that scan covers. Left alone, R595 unifies the two paths onto a single *wrong* answer: `graphql.scalars.ExtendedScalars` is still absent from `extension_class`, the detection still fires, and it now fires consistently in both places. Unification removes the divergence; it does not make the answer right. R595 is additionally explicit that nobody reads the store and no behavior changes, so it cannot clear the reported diagnostic on its own.

This item is therefore reduced to the residue: the scanner's scope. That residue is small, and it is worth landing early rather than behind R595, because both consumers route through one function. Fixing `ClasspathScanner` once fixes the store's `extension_` family, today's LSP, and every detection that later migrates onto the store, with no second migration and no window in which the store ships a relation known to be missing rows.

## Cost, measured

Measured against `graphitron-sakila-example`'s resolved compile classpath (282 jars), replicating `ClasspathScanner`'s existing filter over jar entries: 65,261 class entries, of which 29,656 pass the public / non-synthetic / no-`$` filter, carrying 213,118 public methods and 74 `GraphQLScalarType` constants. 156 MB of classfile bytes parsed, 4.0 s cold / 1.4 s warm page cache, single-threaded.

For scale: the entire reactor's compile-output directories hold 1,825 candidate classes today, so this is a ~16x increase in census size and a per-build cost that is currently ~0. Two consequences that shape the plan below:

- `graphitron:dev` rebuilds the catalog on every schema edit. An unconditional 1.4 s jar re-walk per keystroke-driven rebuild is not acceptable, so the scan needs a cache. Jars are content-addressed and immutable in `~/.m2`; a per-jar cache keyed on (path, size, last-modified) makes every rebuild after the first free, and the cold cost is paid once per process.
- The fact store's `extension_` family is filled from the same census (`CatalogFactCapture.captureExtensions`). `extension_class` goes ~1.8k → ~31k rows and `extension_method` to ~213k, with `extension_method_parameter` larger again. Per R595 the store takes the census whole, so this is an insert-throughput question, not a scoping one: measure it, and if the load dominates a build, the answer is a faster load (batched inserts) rather than a narrower census.

## Plan

1. **Plumb the classpath.** `AbstractRewriteMojo.buildContext` passes `resolveCompileClasspath()` where it passes `resolveClasspathRoots()` today. `resolveCompileClasspath` already unions `project.getCompileClasspathElements()` with the reactor roots and is already used for `buildCodegenLoader` and the incremental compiler, so the two paths become the same list by construction rather than by coincidence. Keep the `RewriteContext` component name (`classpathRoots` still describes a list of classpath entries) and rewrite its javadoc: the "external jars are not scanned" premise is now false and must not survive as a stale claim.
2. **Teach `ClasspathScanner` to read jars.** `scan(List<Path>, String)` currently `continue`s on anything failing `Files.isDirectory`. Split the per-entry walk: directories keep the `Files.walk` path, `.jar` files open a `ZipFile` and feed the same `readIfCandidate` filter over each entry's bytes. `readIfCandidate` itself is already byte-oriented and needs only its `Path`-typed signature loosened. Existing FQN dedup across roots carries over unchanged and gives classpath-order precedence, which matches how the classloader would resolve a duplicated class.
3. **Cache per jar.** A static map keyed on (absolute path, size, last-modified) → scanned references, so repeated catalog builds in one `graphitron:dev` process pay the walk once per jar. Directory roots stay uncached: they change on every compile, which is the case the cache would get wrong.
4. **Ordering, not filtering, for completion.** `ClassNameCompletions` and friends will now see ~30k candidates. Do not filter them back out; the point of the item is that they are legitimately referenceable. Rank reactor-resident classes ahead of jar-resident ones so the common case stays first in the list, and let the client's prefix filter do the rest. `ScalarTypeCompletions` already ranks by field-name match against the enclosing `scalar X` and needs no change beyond the wider input.
5. **Re-time R595's extension load** against the widened census and report the number on that item. No decision rides on it here (see above), but R595's agreement anchor now compares a 31k-class census and its owner should know the cost.

## Tests

- LSP diagnostics: a jar-resident `@scalarType` reference (`graphql.scalars.ExtendedScalars.Date`) raises no diagnostic. This is the reported bug and the regression guard.
- LSP completion: `scalar LocalDate @scalarType(scalar: "|")` offers `graphql.scalars.ExtendedScalars.Date`, ranked ahead of the other `ExtendedScalars` constants by the existing field-name preference.
- `ClasspathScanner` unit tier: a fixture jar is scanned, its public classes and methods surface, and a class present in both a jar and a directory root surfaces once with classpath-order precedence.
- Cache behaviour: scanning the same jar twice reads it once; a jar whose last-modified changes is re-read.
- `graphitron-sakila-example` already carries the live build-through fixture (`scalar LocalDate` / `scalar BigDecimal` bound to `ExtendedScalars` constants), so the codegen half needs no new coverage; what is new is the LSP tier asserting the two classpaths agree.
- R595's extension-census agreement anchor keeps passing over the widened census; it compares the store against the scanner emission, so a scanner change it did not follow is a failure there.

## Relationships

- **`graphitron-model-captures-facts` (R595):** owns the `extension_` family this scan fills, and owns the eventual collapse of the LSP diagnostic and the build-tier rejection into one detection over `extension_class`. Not a blocking dependency in either direction: this item changes `ClasspathScanner` and the mojo's context wiring only, and R595's capture inherits the wider census through the same function it already reads. Sequencing it before or during R595 avoids the store ever shipping a census with known-missing rows.
