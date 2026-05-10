---
id: R124
title: "Source codegen classpath from project + reactor, not plugin realm"
status: Spec
bucket: cleanup
priority: 8
theme: service
depends-on: []
---

# Source codegen classpath from project + reactor, not plugin realm

> Stop requiring consumers to wire their service / catalog jars into
> `<plugin><dependencies>`. The generator already runs inside a Maven Mojo
> that has the full project compile classpath resolved
> (`requiresDependencyResolution = ResolutionScope.COMPILE`); switch every
> consumer-class `Class.forName(name)` call from the plugin-realm classloader
> to a Mojo-built `URLClassLoader` rooted at the project's compile classpath
> + every reactor sibling's `target/classes`, parented on the plugin loader.
> Consumers declare their service jar once as a normal `<dependency>` and the
> codegen picks it up.
>
> **Downstream beneficiary: R101.** The custom-scalar resolver
> (`custom-scalar-java-types.md`) loads consumer-declared
> `GraphQLScalarType` constants off the project classpath via the same
> reflection path; R101 declares `depends-on:` on this item and consumes
> `RewriteContext.codegenLoader()` from day one.

---

## Problem

Today every consumer pom carries a block like this (verbatim from
`graphitron-rewrite/graphitron-sakila-example/pom.xml:303-313`):

```xml
<plugin>
  <groupId>no.sikt</groupId>
  <artifactId>graphitron-maven-plugin</artifactId>
  ...
  <dependencies>
    <dependency>
      <groupId>no.sikt</groupId>
      <artifactId>graphitron-sakila-service</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
</plugin>
```

The `-service` artifact is *already* declared as a normal `<dependency>` of the
same module (lines 49-53); the `<plugin><dependencies>` block is pure
boilerplate that duplicates it. Every graphitron consumer hits this, every
service module a consumer adds means another entry, and the failure mode when
omitted is a `Class.forName` `ClassNotFoundException` deep inside the
generator with no hint that the fix is a pom edit.

## Root cause

The generator reflects on consumer service / record / condition / jOOQ-catalog
classes via `Class.forName(name)`. The two-arg form resolves through the
*caller's* classloader, which for a Mojo is the plugin-realm loader. The
plugin realm only contains the plugin's own deps plus whatever sits under
`<plugin><dependencies>`; the project's `<dependency>` graph is in a separate,
sibling realm.

Reflection callsites (current main):

- `ServiceCatalog.java:197, 469, 572, 686`
- `JooqCatalog.java:62, 403, 641, 665`
- `SourceRowDirectiveResolver.java:197, 205`
- `TypeBuilder.java:545, 746, 945`
- `CheckedExceptionMatcher.java:77, 124`

Fifteen consumer-class sites, all bare two-arg `Class.forName`.

`ClassAccessorResolver.java:32` also calls bare `Class.forName`, but its
target is `graphql.schema.DataFetchingEnvironment` — a graphql-java class
that's always present on the plugin's own compile classpath. That one site
stays unchanged; switching it would also require either threading a
`ClassLoader` into a `static` initializer or relaxing the const, neither
worth the churn for a class that's never been at risk.

## What's already in place

Each Mojo already declares `requiresDependencyResolution = ResolutionScope.COMPILE`
(`GenerateMojo.java:16`, `ValidateMojo.java:19`, `DevMojo.java:49`), so Maven
hands the Mojo a fully resolved project compile classpath via
`project.getCompileClasspathElements()`.

`AbstractRewriteMojo.resolveClasspathRoots()` (`:111-125`) already walks
`session.getAllProjects()` and collects every reactor sibling's
`target/classes`. R18 added it for the LSP catalog's filesystem-level scan
(see `roadmap/changelog.md:155`); the reflection path was left on the plugin
classloader at the time. This item closes that asymmetry: the same set of
directories that the LSP catalog walks for class-file scanning becomes the
codebase the reflection path resolves against.

## Design

### Build a project-aware classloader inside the Mojo

In `AbstractRewriteMojo.runGenerator`, build a `URLClassLoader` whose URLs are:

1. Every entry in `project.getCompileClasspathElements()` (the consumer's
   declared compile dep graph plus its own `target/classes`).
2. Every reactor sibling's `target/classes` (already collected by
   `resolveClasspathRoots()`; the LSP path uses these directly, the reflection
   path piggybacks).

The parent is the plugin's own classloader (`getClass().getClassLoader()`), so
the generator's own classes still resolve and any consumer-side override under
`<plugin><dependencies>` (the rare legitimate case: version-pinning a service
jar above the project dep) still wins because it sits in the parent realm.

The Mojo swaps this loader in as the thread-context classloader for the
duration of the `runGenerator(...)` call and restores the previous loader in a
`finally` block. Standard pattern, used by jOOQ codegen, the JAXB-XJC plugin,
the Hibernate codegen plugin, etc.

**Why both explicit threading *and* the TCCL install.** Our 15 reflection
sites switch to the three-arg form so the loader is named at the call site
(`RewriteContext` carries it; no ambient lookup). The TCCL install is the
escape hatch for third-party code we transitively call into (graphql-java
schema parsing, jOOQ runtime helpers, ServiceLoader-based JDBC discovery
inside any consumer-class static initializer that gets triggered) — code we
don't own and can't pass a `ClassLoader` to. Dropping the TCCL install would
turn currently-working consumer setups into hard-to-diagnose
`ClassNotFoundException`s the moment any transitive callee reads TCCL. The
two layers serve different audiences: explicit threading is the in-process
generator's contract, TCCL is the defense for everyone else. The
`RewriteContext` invariant ("never held in a static or `ThreadLocal`") still
holds — the context itself stays explicit; only the loader it carries gets
republished onto the running thread for the duration of one call.

**Resource hygiene: close the loader.** `URLClassLoader` opens a `JarFile`
handle for every JAR on its URL list and does not release them on GC.
`runGenerator` uses a `try-with-resources` (or explicit `close()` in
`finally`) so each invocation releases its handles. This is invisible for a
single `mvn graphitron:generate` but matters for `DevMojo`, which invokes
`runGenerator` once per file-change cycle: without the close, descriptors
leak across the watcher's lifetime.

### Thread the loader through `RewriteContext`

`RewriteContext` already carries `classpathRoots` (R18). Add a sibling field
`ClassLoader codegenLoader` (the existing `classpathRoots` stays because the
LSP catalog still wants directory paths, not a `ClassLoader`). The existing
6-arg back-compat overload now defaults *both* `classpathRoots` (to
`List.of()`, unchanged) and `codegenLoader` (to
`Thread.currentThread().getContextClassLoader()`); unit-tier callers that go
through the 6-arg form see no behavior change because TCCL in a JUnit JVM
equals the system loader, which is what bare `Class.forName(name)` already
resolves through.

### Switch every callsite to the three-arg form

```java
Class.forName(name, false, ctx.codegenLoader())
```

Inline at each of the 15 sites; thread the loader from
`RewriteContext.codegenLoader()` where the call site already holds the
context, or as an explicit `ClassLoader` parameter where it doesn't
(`CheckedExceptionMatcher.unmatched(...)` gains one parameter; its single
caller in `FieldBuilder.java:2241` already has the context). No static
helper that reads TCCL: the whole point is to keep the loader explicit and
the `RewriteContext` invariant ("never held in a static or `ThreadLocal`")
honest at the in-process boundary.

`initialize = false` matches the existing two-arg semantics minus static
initializer execution. The generator reads class metadata (declared methods,
generic return types, annotations); it never invokes a consumer's static
block. The one site that needs initialization, `JooqCatalog.java:665` —
`cls.getField("DEFAULT_CATALOG").get(null)` — keeps it: per JLS §12.4.1, a
reflective static-field read triggers initialization regardless of the
`initialize` flag passed to `Class.forName`. The flip is purely a hygiene
win for the 14 sites that don't need init and a no-op for the one that does.

### Migration: drop the `<plugin><dependencies>` block in sakila-example

Delete `graphitron-sakila-example/pom.xml:303-313`. The existing
`<dependency>graphitron-sakila-service</dependency>` block (lines 49-53)
covers the codegen classpath automatically once the loader is project-aware.
The build should pass byte-identical generated sources before and after.

### Document the new contract

Plugin README (or the equivalent docs entry under `docs/`): one sentence
documenting that any class reachable from the project's compile-classpath is
available at codegen time, and that `<plugin><dependencies>` is no longer
required for service / catalog wiring.

---

## Implementation sites

- `AbstractRewriteMojo.java` — build the `URLClassLoader` from
  `project.getCompileClasspathElements()` + reactor-sibling
  `target/classes`, install as TCCL with `try-with-resources` (or
  explicit `close()` in `finally`), restore prior TCCL on exit; thread
  the loader through the new `RewriteContext` field.
- `RewriteContext.java` — add `codegenLoader` field; the 6-arg back-compat
  overload now defaults both `classpathRoots` (empty) and `codegenLoader`
  (TCCL) so non-Mojo callers don't break.
- The 15 `Class.forName(name)` sites listed above — switch to the
  three-arg form inline, threading the loader from the context (or as an
  explicit parameter for `CheckedExceptionMatcher`).
- `graphitron-sakila-example/pom.xml` — delete the `<plugin><dependencies>`
  block (lines 303-313).
- `graphitron-maven-plugin/src/it/basic-generate/pom.xml` — declare
  `graphitron-sakila-db` as a top-level `<dependency>` (currently only
  declared under `<plugin><dependencies>`) and remove the
  `<plugin><dependencies>` block. Pre-change the IT passes via the plugin
  realm; post-change it passes via the project classpath, locking the
  contract.
- Plugin docs / `docs/` entry — name the new contract.

---

## Tests

### Pipeline-tier

- A new unit test under `graphitron-maven-plugin` that exercises
  `AbstractRewriteMojo.runGenerator` against a project whose `<dependency>`
  graph carries a service class the schema references, with no
  `<plugin><dependencies>` block: assert the codegen resolves the service
  class via the project classpath. The existing `GenerateMojoTest` is the
  closest model.

### Integration-tier (Maven Invoker)

- Extend or replace `graphitron-maven-plugin/src/it/basic-generate` so the
  consumer pom carries the service / catalog deps only under
  `<dependencies>`, never under `<plugin><dependencies>`. Pre-change this
  IT would fail with `ClassNotFoundException`; post-change it passes.
  Locks the contract.

### Compilation- and execution-tier

- `graphitron-sakila-example` is the proof. After deleting the
  `<plugin><dependencies>` block, the full reactor build (`mvn install
  -Plocal-db`) passes including the 116 execution-tier tests, with
  generated sources unchanged. This is the load-bearing migration test:
  if the loader composition is wrong, sakila-example breaks loudly.

---

## Settled design notes

1. **Plugin-realm overrides still work.** Parenting the new
   `URLClassLoader` on the plugin loader means any class declared under
   `<plugin><dependencies>` still resolves through the parent chain and
   shadows the project-classpath copy. The rare legitimate use case
   (pinning a service jar to a different version from the project's
   compile dep) is preserved without any extra code; consumers who don't
   need it stop paying for it.

2. **Bootstrap is fine.** `generate-sources` runs before `compile`, but
   `project.getCompileClasspathElements()` returns the *resolved compile
   classpath* (declared `<dependency>` artifacts from `~/.m2` plus the
   current module's own not-yet-populated `target/classes`). For
   reactor siblings, Maven's build order guarantees declared compile
   deps build first; the URLClassLoader gets their `target/classes` (and
   their jars in `~/.m2`) by the time codegen runs. This is the standard
   layout already used by sakila-example.

3. **No change to unit-tier tests.** Tests reflecting on classes from
   their own module resolve via the system classloader, which is the
   parent of TCCL in JUnit-launched JVMs. Passing the explicit
   three-arg form with TCCL is equivalent. No expected test churn beyond
   the new pipeline-tier coverage.

4. **`initialize = false` is deliberate.** The generator reads class
   metadata (declared methods, generic return types, annotations); it
   never invokes static initializers. Switching from the two-arg form
   (which initializes) to the three-arg form with `initialize = false`
   is a small correctness win and removes a class of hard-to-diagnose
   side effects (a service class with a static block that opens a JDBC
   connection at class-load time, say). The one site that *does* need
   initialization, `JooqCatalog.loadDefaultCatalog` (`:665`), still gets
   it: the immediately-following `cls.getField("DEFAULT_CATALOG").get(null)`
   triggers initialization per JLS §12.4.1 regardless of the flag.

5. **Inline three-arg, no helper, no TCCL ad-hoc reads.** The
   `RewriteContext` invariant — "never held in a static or
   `ThreadLocal`" — extends to the loader it carries: a static
   `ReflectionHelper.loadClass(name)` that reads TCCL internally would
   re-introduce the ambient pattern we're trying to leave behind for
   in-process callers. Each of the 15 sites spells the three-arg form
   with the loader sourced from the context; the two sites that don't
   already hold a context (`CheckedExceptionMatcher.unmatched`) gain a
   `ClassLoader` parameter. The TCCL install on the running thread is
   strictly a defense for third-party callees and does not back any
   in-process generator code path.

---

## Non-goals

- Loading classes from external (non-reactor) jars not on the project's
  declared dep graph. The whole point is to align with Maven's existing
  dependency-resolution contract; anything outside that graph stays
  invisible.
- Hot-reloading the classpath during `mvn graphitron:dev`. The DevMojo
  rebuilds the URLClassLoader every regeneration cycle (with `close()`
  per cycle, see *Resource hygiene* in the design), so a consumer
  recompiling a service class between cycles picks it up; pinning
  incremental-reload semantics beyond that is a separate concern worth
  its own roadmap entry if it turns out to bite.
- Removing the plugin's *own* compile-time deps on `graphitron` and
  `graphitron-lsp` (`graphitron-maven-plugin/pom.xml:24-33`). Those are
  the generator implementation, not consumer artifacts; they stay on the
  plugin realm where they belong.
