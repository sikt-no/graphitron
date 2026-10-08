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
* `ServiceCatalog.reflectSessionHook` loads the mount class and calls `pickMethod`, which calls
  `cls.getDeclaredMethods()`. Materialising a class's declared-method table resolves the parameter
  and return types of *every* declared method, not only the picked one, so the sibling helper's
  `TRollelisteRecord` is loaded, then its superclass `ArrayRecordImpl` (consumer jar), whose
  package-private superclass `AbstractStore` resolves to the realm. Same package name, two loaders,
  two runtime packages: the JVM refuses the access.

So symptom two needs no plugin-block entry at all. Any consumer on a commercial jOOQ edition can hit
it, from any class graphitron reflects (`pickMethod` is shared by `@service`, `@condition` and the
session hooks; `RecordBindingResolver`, `ClassAccessorResolver`, `InputBeanResolver`,
`LifterMethodResolver`, `FieldBuilder` and `JooqCatalog`'s routines read call `getMethods` or
`getDeclaredMethods` too), whenever any method on that class names a commercial-only jOOQ type.
Service classes whose signatures name `TableRecordImpl`-derived records do not trip it because
`TableRecordImpl` and its whole superclass chain exist in the open-source jar and load from the realm
together.

Both symptoms share one silence: nothing in either message names the consumer's pom, an edition, or
the class graphitron was reflecting. That silence is what this item fixes.

## Implementation

### Plugin realm: exactly one jOOQ, and it is ours

A new check at the top of `AbstractRewriteMojo.withCodegenScope`, before the codegen loader is built
and before any goal opens the fact store, so it covers `generate`, `validate`, `capture` and `dev`
alike. It fails the goal with a `MojoExecutionException` when either holds:

* **More than one jar in the realm provides `org/jooq/Constants.class`**, read off the plugin's own
  loader with `getResources`. Coordinate-agnostic on purpose: it catches an edition under another
  group id, a same-coordinate copy Maven failed to mediate, and a fat jar that embeds jOOQ, none of
  which a coordinate match would see all of.
* **The one jar's `Constants.VERSION` is not the version graphitron was compiled against.** The
  compiled-against version is `org.jooq.Constants.VERSION` written as a plain expression in
  graphitron's source: it is a compile-time constant, so javac inlines `"3.20.11"` into graphitron's
  bytecode. The runtime version is the same field read reflectively off the realm's `Constants`
  class. This arm catches the case the first cannot: a plugin-block entry that brings
  `org.jooq:jooq` at another version, where Maven mediates to a single jar that is not ours.

The message is built by a pure function over plain data, following the precedent
`decodeDependencyVersions` sets: the mojo boundary decodes Maven's `Artifact` objects into
`(coordinate, file, trail)` rows and the interior decides and renders. Attribution, in order of
preference:

1. The offending jar's `Artifact.getDependencyTrail()`, when Maven populates it for plugin
   artifacts, whose second element is the plugin-block entry that brought it.
2. Otherwise the consumer-added entries themselves, read off `pluginDescriptor.getPlugin()
   .getDependencies()` (the `Plugin` model element is the consumer pom's `<plugin>` block), listed
   as the candidates.

Whether (1) is populated for plugin-realm artifacts under Maven 3.9 is the one fact to verify first
at pickup; (2) is always available and is the floor. The rendered message, for the `sis` case:

```
graphitron runs on org.jooq:jooq:3.20.11, but its plugin classloader also holds
org.jooq.pro:jooq:3.19.18 (brought in by no.fellesstudentsystem:sis-service, declared under
<plugin><dependencies> for graphitron-maven-plugin). Two jOOQs in the plugin classloader make
graphitron run on whichever answers first.

Move no.fellesstudentsystem:sis-service from the plugin's <dependencies> to the module's own
<dependencies>; graphitron already reads the module's compile classpath. If it must stay in the
plugin block, exclude org.jooq.pro:jooq from it.
```

`referenceVersionsOf`'s javadoc ("the realm is graphitron's own build, resolving one of each")
becomes a stated precondition enforced by this check rather than an assumption; it gains a
`{@link}` to the check.

### Codegen loader: a reflected class that cannot link is a typed rejection

Two layers, so the common site is precise and nothing slips through as a raw `Error`:

* **`ServiceCatalog.pickMethod`** (both overloads) catches `LinkageError` around
  `getDeclaredMethods()` and returns `MethodPick.Rejected` carrying a new
  `ReflectionError.ClassUnlinkable(className, failingType, detail)` arm. `pickMethod` is the one
  site behind `@service`, `@condition` and the `<sessionState>` hooks, which is where an author's own
  class is most likely to carry database-adjacent helpers. The rendered text names the class and
  role ("the `<sessionState>` mount class `…FsSesjon`"), the type that failed (`ArrayRecordImpl`,
  parsed off the `IllegalAccessError`/`NoClassDefFoundError` message where the JVM gives one, else
  the error's own text verbatim), and the workaround: "graphitron loads your classes against its own
  jOOQ (`org.jooq:jooq:3.20.11`), which does not carry this type; keep methods that name it off
  classes graphitron reflects, for example in a nested or separate class." Adding a
  `ReflectionError` arm means mapping its severity where `RejectionSeverityCoverageTest` demands.
* **A backstop in `withCodegenScope`** catches `LinkageError` escaping `body.run` and rethrows a
  `MojoExecutionException` whose message names the error, lists every jar visible to the codegen
  loader that provides `org/jooq/Constants.class` with its coordinate (decoded from
  `project.getArtifacts()` and the plugin artifacts by file), and says the same thing about
  graphitron's jOOQ hosting the consumer's classes. This covers the reflection sites outside
  `pickMethod` without touching each of them; the cause stays on the chain for `-e`.

The backstop is narrower than it looks: it rethrows only, it does not continue the round, and it is
not a catch-all for `Error`. `LinkageError` is the family the cross-loader split produces
(`IllegalAccessError`, `NoClassDefFoundError`, `IncompatibleClassChangeError`, `VerifyError`), and
every member of it means "this class cannot be used from here", which is the sentence the message
adds context to.

### Documentation

* `docs/manual/how-to/external-code.adoc` § Make the class nameable: the withdrawn-route paragraph
  gains one sentence, that a plugin-block entry which brings its own jOOQ now fails the build with a
  message naming it.
* `docs/manual/reference/mojo-configuration.adoc` § Codegen classpath: a paragraph on the layering,
  stated for authors. Your classes are loaded against graphitron's jOOQ; on a commercial edition,
  a class graphitron reflects must not name an edition-only jOOQ type in any of its method
  signatures, and the build tells you which class and type when one does.

## Tests

* **Realm check, unit tier** (`graphitron-maven-plugin`, beside `DependencyVersionDecodeTest`):
  the pure decide-and-render function over hand-built rows. Cases: one jOOQ at our version (passes);
  two jars (fails, names both, names the trail's plugin-block entry); two jars with no trail (fails,
  lists the consumer-added entries); one jar at another version (fails, names both versions). The
  function takes the compiled-against version as a parameter, so these cases do not depend on the
  inlined constant; the IT below covers the wiring.
* **Realm check, invoker IT** `graphitron-maven-plugin/src/it/plugin-block-second-jooq`: a setup
  project installs a tiny jar carrying a stub `org/jooq/Constants.class`, the IT pom lists it under
  `<plugin><dependencies>`, and `verify.groovy` asserts the build failed with the message naming that
  coordinate and the move/exclude remedy, and carries no fact-store error. This is the
  acceptance evidence for symptom one: it exercises Maven's real realm construction, which no
  in-process test can.
* **Codegen loader, pipeline tier** (`graphitron`): a test stages, with the ClassFile API, a class
  `org.jooq.impl.StagedEditionOnly extends org.jooq.impl.AbstractStore` (package-private abstract in
  the open-source jar, so it reproduces the exact split) and a mount class whose `mount` method is
  well-formed and whose sibling static helper returns `StagedEditionOnly`, in a temp directory on a
  `URLClassLoader` whose parent is the test's loader. Resolving the `<sessionState>` hooks against it
  yields `ReflectionError.ClassUnlinkable` naming the mount class and `StagedEditionOnly`, not a
  thrown `IllegalAccessError`. A second case moves the helper to a nested class and asserts the
  hooks resolve, pinning the documented workaround. This is the acceptance evidence for symptom two.
* **Backstop**: `CodegenLoaderTest` gains a case running a body that throws `IllegalAccessError`
  inside `withCodegenScope` and asserts a `MojoExecutionException` listing the staged jOOQ jars.

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
`java.lang.reflect` view of a class (`getDeclaredMethods`, `getDeclaredMethod`, `getMethods`)
materialises the whole method table, so the only way to look at one method without linking its
siblings' types is to read the signature from the classfile, which `ClassfileCensus` already does for
the classpath census. Moving `ServiceCatalog`'s reflection onto census rows would make symptom two's
workaround unnecessary for sibling methods, but it is a rewrite of the reflection path across a dozen
sites (and overlaps the slimming `ServiceCatalog` already has a Backlog item for), and it still could
not load a *picked* method whose own signature names an edition-only type. This item makes the
failure legible; that one would make most of it go away. Not filed as a successor yet: worth filing
when a second consumer hits it with the workaround in hand.

**Child-first codegen loader for `org.jooq`.** Consumer classes would then link against the
consumer's own jOOQ and never split, but graphitron's `org.jooq.Table` and the consumer's would be
different classes, breaking `JooqCatalog` and every identity check in `ServiceCatalog`. That is the
isolation `DevQueryExecutor` already uses for dev execution (platform parent, consumer jars only), and
it works there because nothing crosses back but strings; codegen hands jOOQ objects across, so it is
not available here without the census rewrite above.
