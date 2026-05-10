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
> `Class.forName(name)` call from the plugin-realm classloader to a Mojo-built
> `URLClassLoader` rooted at the project's compile classpath + every reactor
> sibling's `target/classes`, parented on the plugin loader. Consumers declare
> their service jar once as a normal `<dependency>` and the codegen picks it up.

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
- `ClassAccessorResolver.java:32`
- `CheckedExceptionMatcher.java:77, 124`

Twelve sites, all bare two-arg `Class.forName`.

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

### Thread the loader through `RewriteContext`

`RewriteContext` already carries `classpathRoots` (R18). Add a sibling field
`ClassLoader codegenLoader` (or similar; the existing `classpathRoots` stays
because the LSP catalog still wants directory paths, not a `ClassLoader`).
Default to `Thread.currentThread().getContextClassLoader()` so unit-tier
callers that don't go through the Mojo see no change.

### Switch every callsite to the three-arg form

```java
Class.forName(name, false, ctx.codegenLoader())
```

`initialize = false` matches the existing two-arg semantics (the two-arg form
initializes; switching to `false` is a tiny improvement because the generator
only needs metadata, not static init). One helper on `RewriteContext` (or a
dedicated `ReflectionHelper`) so the convention is one obvious entry point
rather than twelve scattered duplicates.

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

- `AbstractRewriteMojo.java` — build the `URLClassLoader`, install as
  context loader, restore in `finally`; thread through the new field.
- `RewriteContext.java` — add `codegenLoader` field plus a back-compat
  overload (defaults to TCCL) so non-Mojo callers don't break.
- The twelve `Class.forName(name)` sites listed above — switch to the
  three-arg form via the new helper.
- `graphitron-sakila-example/pom.xml` — delete the `<plugin><dependencies>`
  block (lines 303-313).
- `graphitron-maven-plugin/src/it/basic-generate/pom.xml` — verify the IT
  doesn't require a `<plugin><dependencies>` block either (the IT loads
  `graphitron-sakila-db` via `extraArtifacts`; check whether its current
  shape implicitly depends on the plugin-realm path).
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
   connection at class-load time, say).

---

## Non-goals

- Loading classes from external (non-reactor) jars not on the project's
  declared dep graph. The whole point is to align with Maven's existing
  dependency-resolution contract; anything outside that graph stays
  invisible.
- Hot-reloading the classpath during `mvn graphitron:dev`. The DevMojo
  already has a watcher loop; building a fresh URLClassLoader per
  regeneration cycle is straightforward and falls out of the same Mojo
  path, but pinning incremental reload semantics is a separate concern
  worth its own roadmap entry if it turns out to bite.
- Removing the plugin's *own* compile-time deps on `graphitron` and
  `graphitron-lsp` (`graphitron-maven-plugin/pom.xml:24-33`). Those are
  the generator implementation, not consumer artifacts; they stay on the
  plugin realm where they belong.

---

## Open questions for the reviewer

1. **One-helper convention vs inline three-arg.** The twelve callsites
   could each spell `Class.forName(name, false, ctx.codegenLoader())`,
   or a single `ReflectionHelper.loadClass(name)` could read a TCCL
   internally. The helper is one fewer parameter to thread through
   classes that don't already hold a `RewriteContext`
   (`CheckedExceptionMatcher`, `ClassAccessorResolver`), but a static
   TCCL read is one of the patterns the principles doc warns against
   ("ambient state for emitter behaviour"). Leaning toward threading
   `ctx` (or the loader directly) explicitly, but worth a reviewer
   call.

2. **Should `RewriteContext.codegenLoader` default to TCCL or to
   `getSystemClassLoader()`?** TCCL matches what the current
   `Class.forName(name)` two-arg form does for unit-tier callers in
   practice (the test runner sets TCCL = system loader); explicit
   `getSystemClassLoader()` is more deterministic but might surprise a
   non-Mojo caller that has intentionally swapped TCCL. Leaning TCCL.
