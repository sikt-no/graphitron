---
id: R124
title: "Source codegen classpath from project + reactor, not plugin realm"
status: In Review
bucket: cleanup
priority: 8
theme: service
depends-on: []
---

# Source codegen classpath from project + reactor, not plugin realm

Stops requiring consumers to wire service / catalog jars into
`<plugin><dependencies>`: the Mojo builds a `URLClassLoader` over the
project's compile classpath plus every reactor sibling's `target/classes`,
parented on the plugin loader, and threads it through `RewriteContext` to the
22 in-process reflection sites. The loader is also installed as TCCL for the
duration of `runGenerator` (defense-in-depth for third-party transitive
callees), restored in `finally`, and closed to release JAR file descriptors.

## Shipped

- `RewriteContext` carries `ClassLoader codegenLoader`; a six-arg and a
  seven-arg back-compat overload default it to TCCL for unit-tier callers.
- `BuildContext.codegenLoader()` is a thin passthrough mirroring the
  existing `nodeIdLeafResolver()` shape. `BuildContext.ctx` is now
  `@NonNull` (enforced by `Objects.requireNonNull` in the constructor);
  the three unit-tier tests that previously passed `(null, _, null)` now
  supply a deterministic `stubRewriteContext()` via the 6-arg
  back-compat overload.
- `JooqCatalog`'s public constructor takes `(String, ClassLoader)`; the
  one-arg constructor is the back-compat overload defaulting to TCCL. The
  two `static` helpers (`verifyTablesClassPresent`,
  `loadDefaultCatalog`) thread the loader through the four
  `Class.forName` sites.
- 22 consumer-class `Class.forName(name)` sites switched to the three-arg
  form (`Class.forName(name, false, loader)`), with `initialize = false`
  except `loadDefaultCatalog`. The lone `DataFetchingEnvironment` site in
  `ClassAccessorResolver` stays unchanged.
- `CheckedExceptionMatcher.unmatched` / `covers` and
  `ServiceCatalog.argExtraction` gain a `ClassLoader` parameter;
  `TypeBuilder.validateExceptionClass` is no longer `static` so it can
  use `ctx.codegenLoader()`; `FieldBuilder.checkDeclaredCheckedExceptions`
  is no longer `static` for the same reason.
- `AbstractRewriteMojo.runGenerator` is rebuilt around
  `withCodegenScope(CodegenScopeBody)`: builds the `URLClassLoader`,
  installs TCCL, threads through the new `RewriteContext`, restores
  TCCL, and closes the loader in a `try-with-resources`. `DevMojo`'s
  `execute` / `regenerate` / `rebuildCatalog` go through the same
  helper, so the loader rebuilds per regeneration cycle (with
  `close()` per cycle) per the dev-loop spec.
- Migration: `graphitron-sakila-example/pom.xml` and the
  `basic-generate` IT pom no longer carry `<plugin><dependencies>`;
  the IT now declares `graphitron-sakila-db` as a top-level
  `<dependency>`, locking the contract.
- Docs: new "Codegen classpath" section in
  `docs/manual/reference/mojo-configuration.adoc` names the new contract.
- New `CodegenLoaderTest` (pipeline-tier) stages a real `.class` file off
  the test JVM's classpath, confirms `withCodegenScope` resolves it,
  asserts TCCL is installed inside the scope and restored after.
