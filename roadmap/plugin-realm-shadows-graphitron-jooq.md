---
id: R951
title: "A consumer pom that adds a dependency to the plugin realm can shadow the jOOQ graphitron parses with, and the store will not open"
status: Spec
bucket: dx
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-15
last-updated: 2026-10-08
---

# A consumer pom that adds a dependency to the plugin realm can shadow the jOOQ graphitron parses with, and the store will not open

## Goal

A consumer whose build puts a second jOOQ where graphitron can see it gets told so, in their own
terms, before anything else fails: which jar, which pom entry brought it, and what to change. Today
the same mistake surfaces as a fact-store view that "did not parse" or as an `IllegalAccessError`
between two jOOQ internals, neither of which mentions jOOQ editions, Maven, or the author's pom, so
the first reading an author reaches for is that their schema broke the generator.

Two places can carry the second jOOQ, and they get different answers. The *plugin realm* (the
classloader Maven builds for graphitron from graphitron's own dependencies plus anything the
consumer lists under `<plugin><dependencies>`) must hold exactly the jOOQ graphitron was built
against; a second one there is refused before the fact store opens, and the message names the
plugin-block entry to move or exclude. The *codegen loader* (the classloader graphitron builds over
the consumer module's compile classpath, with the plugin realm as parent, to reflect the author's
service, condition, record and `<sessionState>` classes) always sees the consumer's own jOOQ under
graphitron's, by design. When a class graphitron reflects names a jOOQ type graphitron's jOOQ cannot
host (in practice, a commercial-edition-only class such as `ArrayRecordImpl`), the build fails with a
typed rejection naming the class graphitron was reflecting, the type it could not load, both jOOQ
jars, and the workaround, instead of a raw `LinkageError`.

These acceptance criteria replace the Backlog stub's, which asked that both cases *work*. The realm
case is a configuration the manual already withdraws, with a one-line fix on the consumer side. The
codegen case is a current limitation, not a rule for authors: R1004 removes it for sibling methods by
reading signatures from classfiles. This item delivers the legible failure that holds either way.

## What happens

### Symptom one: a second jOOQ in the plugin realm, the store will not open

Found while pricing the refresh pass on the `sis` consumer. The module's pom adds `sis-service` to
the plugin block's `<dependencies>`. That dependency brings `org.jooq.pro:jooq:3.19.18` into the
plugin realm, where graphitron's own `org.jooq:jooq:3.20.11` already is. The two artifacts carry the
same package and class names under different group ids, so Maven's version mediation never compares
them, both land in the realm, and which one answers a given class load is URL order.

When the older one answers, the failure is this:

```
graphitron: could not open the fact store at <cache>/...-dev:
the stored definition of view intent_field_scope_table_live did not parse,
and a definition walk reads it: Token ')' expected: [69:9]
```

`ViewReferences.parse` reads each registered rule's stored definition back with
`dsl.parser().parseQuery` so `MaterializeDependencies.populate` can derive the refresh order, and
H2 renders that view's derived table with its `UNION ALL` arms parenthesised. jOOQ 3.20.11 parses
that; 3.19.18 does not. The parse is only the first casualty: with the older jar answering, the whole
fact store (graphitron's internal H2 database of captured facts, driven through jOOQ) runs on a jOOQ
graphitron was not compiled against, and any 3.20-only call fails the same way later.

The `<plugin><dependencies>` route for service jars is already withdrawn in the manual
(`docs/manual/how-to/external-code.adoc` § Make the class nameable, and `mojo-configuration.adoc`
§ Codegen classpath): the codegen loader reads the module's own compile classpath, so mirroring a
service jar into the plugin block buys nothing. The `sis` pom predates the withdrawal. Nothing
enforces it today, which is why the failure is so far from its cause.

### Symptom two: reflecting a `<sessionState>` mount

Found 2026-10-08 on the same consumer, when it added
`<sessionState><mount>no.fellesstudentsystem.kjerneapi_service.tilgang.FsSesjon#mount</mount>…`:

```
Execution generate of goal no.sikt:graphitron-maven-plugin:10-SNAPSHOT:generate failed:
An API incompatibility was encountered while executing ...:generate:
java.lang.IllegalAccessError: class org.jooq.impl.ArrayRecordImpl cannot access its abstract
superclass org.jooq.impl.AbstractStore (org.jooq.impl.ArrayRecordImpl is in unnamed module of
loader java.net.URLClassLoader @55cb0354; org.jooq.impl.AbstractStore is in unnamed module of
loader org.codehaus.plexus.classworlds.realm.ClassRealm @72018ba5)
```

The mount class had a public static helper returning `TRollelisteRecord`, a jOOQ-generated UDT
array record. Moving that helper into a nested class made the build pass with nothing else changed.

This is a different mechanism from symptom one, and the error text proves it.
`org.jooq.impl.ArrayRecordImpl` does not exist in the open-source `org.jooq:jooq:3.20.11` jar
(checked: the jar has `AbstractStore` and `UDTRecordImpl`, no `ArrayRecordImpl`); it is
commercial-edition only. It was defined by the `URLClassLoader`, that is the codegen loader, so the
plugin realm did not have it, so the realm did not hold the pro jar on that run. What happened is
the codegen loader's ordinary layering:

* `AbstractRewriteMojo.buildCodegenLoader` builds a `URLClassLoader` over the module's compile
  classpath with the plugin realm as parent. Delegation is parent-first, so every `org.jooq` class
  the realm has comes from graphitron's jOOQ, and only the classes it lacks come from the consumer's.
  This is deliberate and load-bearing: `JooqCatalog` reads the consumer's generated `Table` objects
  through graphitron's `org.jooq` interfaces, and `ServiceCatalog` recognises seam parameters by
  `Class` identity (`p.getType() == org.jooq.Configuration.class`).
* `ServiceCatalog.reflectSessionHook` loads the mount class and calls `pickMethod`, which through `candidateMethods` calls
  `cls.getDeclaredMethods()`. Materialising a class's declared-method table resolves the parameter
  and return types of *every* declared method, not only the picked one, so the sibling helper's
  `TRollelisteRecord` is loaded, then its superclass `ArrayRecordImpl` (consumer jar), whose
  package-private superclass `AbstractStore` resolves to the realm. Same package name, two loaders,
  two runtime packages: the JVM refuses the access.

So symptom two needs no plugin-block entry at all. Any consumer on a commercial jOOQ edition can hit
it, from any class graphitron reflects (`ServiceCatalog.candidateMethods` sits under `@service`,
`@condition` and the session hooks; `RecordBindingResolver`, `ClassAccessorResolver`, `InputBeanResolver`,
`LifterMethodResolver`, `FieldBuilder` and `JooqCatalog`'s routines read call `getMethods` or
`getDeclaredMethods` too), whenever any method on that class names a commercial-only jOOQ type.
Service classes whose signatures name `TableRecordImpl`-derived records do not trip it because
`TableRecordImpl` and its whole superclass chain exist in the open-source jar and load from the realm
together.

Both symptoms share one silence: nothing in either message names the consumer's pom, an edition, or
the class graphitron was reflecting. That silence is what this item fixes.

## Implementation

### Plugin realm: exactly one jOOQ, and it is ours

**One decode of the realm.** `referenceVersionsOf` already walks `pluginDescriptor.getArtifacts()`,
and `WatchedDependency.JOOQ` already matches `org.jooq.pro`, so in the `sis` case it sees both jOOQs
today and keeps whichever comes first. Instead of a second walk beside it, the mojo boundary decodes
the plugin artifacts once into rows of `(coordinate, version, file, trail)`, where the trail is
`Artifact.getDependencyTrail()`. Three readers project off those rows: the realm check below,
`referenceVersionsOf` (whose "the realm resolves one of each" stops being an assumption and becomes a
precondition the check enforces), and the codegen backstop's jar listing. The jOOQ reference version
the dependency advisory compares against becomes the compiled-against version below, not the first
realm row, so there is one notion of "the jOOQ graphitron is built against".

**The check.** A pure decision over those rows plus one piece of loader evidence, joined by file. It
lives in the mojo module beside `decodeDependencyVersions`, not in `DependencyVersionWarnings`: that
class is an advisory that runs after the store opens, goes through the warning channel, and can be
suppressed by lint rule id, none of which a precondition may be. It refuses when either holds:

* **More than one jar in the realm provides `org/jooq/Constants.class`**, read off the plugin's own
  loader with `getResources`. Coordinate-agnostic on purpose: it catches an edition under another
  group id, a same-coordinate copy Maven failed to mediate, and a fat jar that embeds jOOQ, none of
  which a coordinate match would see all of.
* **The one jar's `Constants.VERSION` is not the version graphitron was compiled against.** The
  compiled-against version is `org.jooq.Constants.VERSION` written as a plain expression in
  graphitron's source: it is a compile-time constant, so javac inlines `"3.20.11"` into graphitron's
  bytecode. The runtime version is the same field read reflectively off the realm's `Constants`
  class. This arm catches what the first cannot: a plugin-block entry that brings `org.jooq:jooq` at
  another version, where Maven mediates to a single jar that is not ours.

**Where it runs.** Once per mojo execution, before the execution's first store open, since the realm
is fixed for the life of the execution. One `final` method on `AbstractRewriteMojo`, called at the
start of `runGenerator` and `runCapture` and at the top of `DevMojo.execute`, which opens
`sessionStore` with `GraphitronModelStore.openAt` before its first `withCodegenScope`. Not inside
`withCodegenScope`: dev rebuilds that scope on every save, and the dev store open would already have
failed, rewrapped by `openAt`'s `catch (RuntimeException)` as "another graphitron process holding
it", which reads worse than today's message.

**Attribution.** The offending row's trail, whose second element is the plugin-block entry that
brought the jar, when Maven populates trails for plugin-realm artifacts under 3.9. Otherwise the
consumer-added entries, read off `pluginDescriptor.getPlugin().getDependencies()` (the `Plugin` model
element is the consumer pom's `<plugin>` block), listed as candidates. Whether trails are populated is
the one fact to verify first at pickup; the entry list is always available and is the floor. The
rendered message, for the `sis` case:

```
graphitron runs on org.jooq:jooq:3.20.11, but its plugin classloader also holds
org.jooq.pro:jooq:3.19.18 (brought in by no.fellesstudentsystem:sis-service, declared under
<plugin><dependencies> for graphitron-maven-plugin). Two jOOQs in the plugin classloader make
graphitron run on whichever answers first.

Move no.fellesstudentsystem:sis-service from the plugin's <dependencies> to the module's own
<dependencies>; graphitron already reads the module's compile classpath. If it must stay in the
plugin block, exclude org.jooq.pro:jooq from it.
```

### Codegen loader: one decode for a consumer class that cannot link

**One decode, sealed outcome.** The live `Class` of a consumer class is read at two points: loading
it (`Class.forName(name, false, ctx.codegenLoader())`, where an edition-only *supertype* already
fails) and materialising its declared methods (`ServiceCatalog.candidateMethods`, which runs
`getDeclaredMethods()` and is behind both `pickMethod`, for `@service` and the `<sessionState>`
hooks, and `reflectTableMethod`, for `@condition`). Both go through one decode returning a sealed
outcome, `Loaded` / `NotLoaded` / `Unlinkable`. `NotLoaded` is what today's
`catch (ClassNotFoundException)` sites produce as `ReflectionError.ClassNotLoaded`, so the decode
subsumes those catches rather than standing beside them. `Unlinkable` becomes a new
`ReflectionError.ClassUnlinkable(className, cause)` arm.

**A typed cause, decoded once at the catch,** following the `AmbiguousMethod.Ambiguity` precedent,
with `message()` rendering from it:

* `SplitPackage(type, packageName)`, from an `IllegalAccessError` naming a class and a superclass in
  the same package under two loaders. Remedy: graphitron loads your classes against its own jOOQ
  (`org.jooq:jooq:3.20.11`), which does not carry this type; keep methods that name it off classes
  graphitron reflects, for example in a nested or separate class.
* `TypeMissing(type)`, from a `NoClassDefFoundError`: a signature names a type not on the compile
  classpath (a runtime-scoped dependency, say). Remedy: the classpath one, nothing about jOOQ.
* `Unrecognised(detail)`, everything else in the `LinkageError` family, the error's own text.

The arm carries no caller role, matching the other `ReflectionError` arms (`prefixedWith` is a no-op;
the orchestrator adds which coordinate was being resolved). New arm obligations: a
`RejectionSeverityCoverageTest.sampleFor` branch in `graphitron-lsp`, and a paragraph in
`typed-rejection.adoc`, which `SealedHierarchyDocCoverageTest` drift-guards.

**Sites that already swallow `LinkageError`,** each decided explicitly:

* `BuildContext.loadForSlot` and `InputBeanResolver.tryLoad` load a type named in a signature and
  return `null` on `LinkageError`, which their callers read as "no such type" and reject later with
  text about the wrong thing. They adopt the decode and surface `Unlinkable` as `ClassUnlinkable`.
* `FieldBuilder.keyColumnJavaType` stays: `Object.class` deliberately stands the comparison aside
  when a key column's class cannot be read, and javac still sees the encode call.
* `ClasspathNameability.platformResolves` stays: it probes the platform loader, where no jOOQ split
  can occur.
* `JooqCatalog`'s routine probe stays: a `null` there only withholds a better "not table-valued"
  hint, never misdiagnoses.
* `ClassAncestry.isEnum` and `ClassAncestry.reflect` stay: they are capture-side, where a class that
  cannot be read is a missing fact rather than a refusal, and the generator-side decode above is where
  an unusable class is refused.

**A backstop for whatever is not classified.** `withCodegenScope` catches `LinkageError` escaping
`body.run` and rethrows a `MojoExecutionException` rendered by the same cause decode, followed by
every jar visible to the codegen loader that provides `org/jooq/Constants.class`, with coordinates
from the realm rows above and the project's artifacts joined by file. It rethrows only, never
continues the round, and catches nothing outside `LinkageError`. The two layers do different jobs:
the arm is a located rejection that reaches the LSP and becomes a dev-loop diagnostic, while the
backstop keeps an unclassified `Error` from escaping `DevMojo.regenerate`'s
`catch (MojoExecutionException)` as a raw stack trace.

### Documentation

* `docs/manual/how-to/external-code.adoc` § Make the class nameable: the withdrawn-route paragraph
  gains one sentence, that a plugin-block entry which brings its own jOOQ now fails the build with a
  message naming it.
* `docs/manual/reference/mojo-configuration.adoc` § Codegen classpath: a paragraph on the layering,
  framed as a current limitation. Your classes are loaded against graphitron's jOOQ; on a commercial
  edition, a class graphitron reflects currently must not name an edition-only jOOQ type in any of
  its method signatures, and the build names the class and type when one does.

## Tests

* **Realm check, unit tier** (`graphitron-maven-plugin`, beside `DependencyVersionDecodeTest`): the
  pure decision over hand-built rows. Cases: one jOOQ at our version (passes); two jars (fails, names
  both, names the trail's plugin-block entry); two jars with no trail (fails, lists the
  consumer-added entries); one jar at another version (fails, names both versions). The function
  takes the compiled-against version as a parameter, so these cases do not depend on the inlined
  constant. `DependencyVersionDecodeTest` keeps passing over the shared decode, with the jOOQ
  reference now the compiled-against version.
* **Realm check, invoker ITs.** A setup project installs two stub jars, each carrying only an
  `org/jooq/Constants.class`: one at `org.example.it:second-jooq` (`VERSION = "3.19.0"`), one at
  `org.jooq:jooq:3.19.99-it` (`VERSION = "3.19.99"`). `plugin-block-second-jooq` lists the first under
  `<plugin><dependencies>` and asserts the two-jar arm's message; `plugin-block-mediated-jooq` lists
  the second, which Maven mediates over graphitron's deeper `org.jooq:jooq` so the realm holds one
  jar that is not ours, and asserts the version arm's message. That second IT is what pins the
  inlined-constant wiring. Both are offline and exercise Maven's real realm construction, which no
  in-process test can; they are the acceptance evidence for symptom one.
* **Dev ordering**: a `DevMojoTest` case asserting the check fires before `GraphitronModelStore.openAt`,
  by handing `execute` a realm decode that fails and asserting no store directory was created.
* **Codegen loader, pipeline tier** (`graphitron`): a test stages, with the ClassFile API, a class
  `org.jooq.impl.StagedEditionOnly extends org.jooq.impl.AbstractStore` (package-private abstract in
  the open-source jar, so it reproduces the exact split) and three consumer classes on a
  `URLClassLoader` over a temp directory, parent the test's loader:
  * a mount class with a well-formed `mount` and a sibling static helper returning
    `StagedEditionOnly`: resolving the `<sessionState>` hooks yields `ClassUnlinkable` with a
    `SplitPackage` cause, not a thrown `IllegalAccessError`;
  * the same with the helper moved to a nested class: the hooks resolve, pinning the documented
    workaround;
  * a `@condition` class with the same sibling helper: `ClassUnlinkable` again, pinning that the
    decode sits under `reflectTableMethod` as well as `pickMethod`.

  These are the acceptance evidence for symptom two. A `TypeMissing` case (sibling naming a class
  absent from the loader) pins that the jOOQ remedy is not given for an ordinary missing type.
* **Backstop**: `CodegenLoaderTest` gains a case running a body that throws `IllegalAccessError`
  inside `withCodegenScope` and asserts a `MojoExecutionException` rendered from the cause decode and
  listing the staged jOOQ jars.

## Other solutions we've considered

**Derive the view read sets at build time instead of parsing at boot.** The parse is the first thing
symptom one breaks, not the cause; with a 3.19 jar answering, the store's every jOOQ call runs on a
version graphitron was not compiled against. Removing the parse would move the failure, not fix it.

**Make a second realm jOOQ work.** Maven gives a plugin no way to exclude what the consumer adds to
its realm. A self-isolating launcher (the mojo rebuilding its own classloader from its declared
artifacts minus the foreign jOOQ) or relocating graphitron's jOOQ into a shaded package would both
work, at a cost far out of proportion to a configuration the manual already withdraws and that has a
one-line fix on the consumer side.

**Make the consumer's classes reflect without loading sibling signatures.** Every
`java.lang.reflect` view of a class materialises the whole method table, so the only way to look at
one method without linking its siblings' types is to read the signature from the classfile, which
`ClassfileCensus` already does. That is the store-shaped answer, and it is filed as R1004 rather than
folded in here: it rewrites the reflection path across a dozen sites and overlaps R72, and it still
cannot load a *picked* method whose own signature names an edition-only type, so the legible failure
this item ships is needed either way.

**Child-first codegen loader for `org.jooq`.** Consumer classes would then link against the
consumer's own jOOQ and never split, but graphitron's `org.jooq.Table` and the consumer's would be
different classes, breaking `JooqCatalog` and every identity check in `ServiceCatalog`. That is the
isolation `DevQueryExecutor` already uses for dev execution (platform parent, consumer jars only), and
it works there because nothing crosses back but strings; codegen hands jOOQ objects across, so it is
not available here.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01MuRsGbZ7TsJp4FSVoh9FyD, 2026-10-08)

Question 2 (fit) passes. The realm check projects off the decode `decodeDependencyVersions` already
does, instead of adding a second walk beside it. The codegen decode subsumes the
`ClassNotFoundException` catches into a new `ReflectionError` arm on the `AmbiguousMethod.Ambiguity`
pattern. The `LinkageError` sites are each decided. I would hand the Implementation and Tests sections
to an implementer as they stand. Every code symbol the plan names exists as named, with one
exception, below. Question 1 fails on that one claim: it is about what happens today, and the goal's
first paragraph and the item's title rest on it.

1. **Symptom one describes a boot path trunk no longer has.** The goal says "Today the same mistake
   surfaces as a fact-store view that 'did not parse'". The title says "the store will not open".
   "What happens" says `ViewReferences.parse` reads stored definitions back "so
   `MaterializeDependencies.populate` can derive the refresh order", and that "the parse is only the
   first casualty". R955 (`4110fea`, 2026-09-23) dropped that register. `MaterializeDependencies`
   is gone from the tree. `ViewReferences` has no caller left in any `src/main`, only gate tests.
   No other `parser()` / `parseQuery` call remains in main sources. So on trunk, a second jOOQ in
   the realm no longer fails at store open. Nobody has established what it does instead: fail
   later on some 3.20-only call, or run silently on a jOOQ graphitron was not compiled against.
   R955's own changelog entry records `graphitron:capture` completing on `sis`. The "Other
   solutions" entry "Derive the view read sets at build time instead of parsing at boot" argues
   against a change that has, in effect, already landed for a different reason.

   This does not change what the implementer builds. Both realm-check arms stand on the invariant
   (graphitron must run on the jOOQ it was compiled against), and the invoker ITs do not depend on
   the parse. It does change the claim a reader judges the goal by. "Before anything else fails" is
   measured against a failure the tree no longer produces.

   What would satisfy this: restate symptom one, and the goal's "Today" sentence, against current
   trunk. Either give the failure a second realm jOOQ now produces, or state that it is now silent
   or unobserved and argue the check from the invariant. Retitle the item to match. Then drop the
   "derive the view read sets" alternative, or reframe it as already landed.
