---
id: R685
title: "The class census scans the transitive dependency closure"
status: Ready
bucket: architecture
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-16
last-updated: 2026-08-21
---

# The class census scans the transitive dependency closure

The class census, the list of Java classes graphitron knows about, is taken over the consumer's
whole compile classpath, which means the whole *transitive* dependency closure: every jar Maven
drags in behind the jars the consumer actually declared. For `graphitron-sakila-example` that is
157 jars, of which the module's own pom declares 15 and the other 142 arrive transitively. They
bring 9,477 classes and 356,960 rows into the store, which is 87% of everything the store holds,
and about 516 ms of the 648 ms each classpath scan costs. Almost none of it is referenceable in a
GraphQL schema: 42 quarkus jars, 14 netty jars, 34 smallrye jars. This item narrows the census to
the consumer's **reactor modules plus their direct compile-scope dependencies**, and adopts the
matching rule for authors: a schema may not name a class from anywhere else.

The census is what `ClasspathScanner` reads out of `.class` bytes: every public top-level class on
the classpath, with its methods, record components, supertypes and `GraphQLScalarType` constants.
`FactCapture` loads it into the store as the `jvm_` relations, and the editor answers class-name
completion, method completion, hover and the unknown-class diagnostic from it. Scanning the whole
classpath was a deliberate widening, and the part of it that matters is right. What is wrong is
only how far it reaches.

## What the tail costs

Measured against `graphitron-sakila-example`, using the warm store one full reactor build left
behind and `ClasspathScanner` run over that module's exact classpath.

The classpath has 169 entries: 12 reactor `target/classes` directories and the 157 jars above.

| Where the classes come from | Classes | Rows in the `jvm_` relations |
|---|---|---|
| Reactor `target/classes` | 1,768 | |
| Declared dependencies | 2,587 | |
| **Kept by this item** | **4,355** | **212,048** |
| **Dropped by this item** (transitive only) | **7,182** | **197,127** |
| Total | 11,537 | 409,175 |

The store holds 413,327 rows in total, so **99% of the store is class census** and **87% of the
store is third-party jars**. Everything else the store knows about this graph, every GraphQL type,
field, directive, table, binding and intent row, is about 4,150 rows.

Read cost, `ClasspathScanner` over that entry list, best of three warm runs: 648 ms for the whole
classpath, 51 ms for the reactor directories alone, 516 ms for the third-party jars alone. So the
jars are roughly 92% of the read time. The dev loop pays it twice per pass, which is R620's
subject and not this item's.

**Decompression is the bill, not parsing**, and this is what decides that the fix is a narrower
entry list rather than a shallower read. Over the same 156 jars: opening every jar and listing its
entries costs 13 ms; inflating the 20,025 `.class` entries costs 486 ms; parsing everything on top
of that adds roughly 130 ms. Reading fewer members per class is therefore worth about 30% and
nothing more, and a byte-level prefilter is worth less than that, because the bytes have to be
inflated before anything can look at them. Only opening fewer jars moves the number.

Nothing names most of it. The transitive tail is 42 quarkus jars (1,288 classes), 14 netty jars
(1,137), 34 smallrye jars (782), 7 vertx jars (815), 9 jboss jars (616). A consumer writes
`@service`, `@condition`, `@record` and `@scalarType` against its own code and against the
libraries it chose; it does not name a netty buffer allocator in a GraphQL schema.

### The write cost, added by R733's third measurement pass

The figures above are the read: the scan, at 648 ms per pass. They stop at the store's door. Writing
those rows costs more than reading them, and this item's case is stronger for saying so.

Measured with an `ExecuteListener` on the store's own `DSLContext`, over `graphitron-sakila-example`'s
five `graphitron:generate` executions in one build: **1346 statements and 13.3 seconds inside them, of
which the `jvm_` family's deletes and merges are about 8.1 seconds, 62%.** On a second run under
heavier machine load the same set was 12.6 of 15.5 seconds, 81%. The `jvm_` writes are the top nine
statements by total time in both runs, ahead of every derivation and every SDL family. Nothing else on
the write side is within a factor of five, the nearest single item being one derivation insert into
`intent_type_backing_class` at 1.87 seconds across six executions.

One consequence for the numbers above rather than for the plan: the persisted store the plugin leaves
behind is **845 MB on disk**. That is a shared per-workspace cache, rewritten partition by partition on
every capture, and about 400,000 of its rows are census.

The write cost scales with rows kept, so this item's split predicts it directly: dropping 197,127 of
409,175 rows should take roughly half of the 8.1 seconds with it. That is a prediction and not a
measurement, and the re-measurement section below is where it should be checked.

### How much of the census any schema actually names

The figures above count what the census costs. This counts what it is asked for, which is the
sharper form of the same argument and was measured against the persisted workspace store a full
reactor build leaves behind: 169 graph/source pairs, every fixture graph the reactor captures.

**417,225 census rows against 1,437 rows of everything else, so 99.66% of the store is census.** The
census holds 11,782 classes. Across every column in every non-`jvm_` relation that can carry a Java
class or method name, the store references **151 distinct dotted names**, of which 149 are census
rows. So **1.3% of the census is named by anything at all**, and the great majority of those 151 are
jOOQ's own generated `Tables`, `Keys` and `*Record` classes named by the catalog rather than by an
author: `graphitron_service.class_name` across all 169 graph/source pairs holds **one** distinct
class name.

Two cautions on reading that. It is a lower bound on what the census is *for*, not a measure of
waste, and the width cut this item proposes is the right response where a per-name cut is not:
completion has to answer before a name is written, which the "Rejected: extract less per dependency
class" section below settles and this measurement does not reopen. And the 1.3% is the reactor's own
fixture graphs, which name fewer classes than a real consumer would.

What it does establish is the asymmetry the item is built on, and it is larger than the row split
alone suggests: the build writes the whole census on every capture and reads back only what its
schema names, every generator-side read over the `jvm_` family being anchored through a
`graphitron_` or `graphql_` relation rather than enumerative. The enumerative reads are the editor's
and the MCP server's. That is not an argument for a build/editor split in what gets captured, one
store serving both being the point of the design, but it is why the write cost above lands entirely
on the party that benefits least.

Two disk figures for the same store, which is the surface a consumer notices. The store is **858 MB**
on disk for those 417,225 rows. And the store path carries a DDL hash, so a schema edit starts a
fresh segment and orphans the previous one: this sandbox held **6.5 GB across nine segments** in
`~/.cache/graphitron/model/`, plus 2.2 GB of integration-test stores under
`graphitron-maven-plugin/target/`. Neither is this item's subject, and nothing here proposes a
reaper; both are recorded because they scale with the row count this item cuts.

## What the tail must not take with it

The census was widened from compile-output directories to the whole classpath to fix a real bug:
`scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")` generated fine and
red-squiggled in the editor, because codegen resolved the constant reflectively while the scan had
never opened a jar. Three measurements say a naive narrowing to reactor modules alone would bring
that back, and all three survive the narrowing proposed here because the classes involved sit in
*direct* dependencies:

- Every one of the 69 `jvm_scalar_type_field` rows comes from a third-party jar: 61 from
  `graphql-java-extended-scalars`, 5 from `graphql-java`, 3 from the federation support jar. Zero
  come from reactor code. `@scalarType` completion exists only because jars are scanned. All three
  jars are declared.
- 536 of the 2,060 reactor classes, just over a quarter, declare a supertype that resolves in a
  third-party jar. **Reactor supertype edges that land in a transitive-only jar: zero.**
- 1,617 distinct reactor method return-type references point at a third-party class. **Of those,
  three land in a transitive-only jar**, all three being `jakarta.ws.rs.core.Response` returned by
  `graphitron-jakarta-rest`'s own REST resources, which no schema names.

Be precise about what an unscanned entry costs on those last two, because it is less than it looks
and the difference is why the cut is safe. A supertype edge and a type reference are rows on the
*declaring* class carrying the target as a plain name, so `MyService extends org.jooq.X` is produced
by scanning `MyService` and survives however narrow the census gets. What an unscanned target loses
is the *continuation*: the target's own row, its methods, and the next hop out of it. The store
already lives with that, 399 distinct supertype names and 1,301 return-reference targets having no
`jvm_class` row even at today's full width, so truncation is an existing and tolerated condition
rather than something this item introduces.

So the walks do not meaningfully cross the boundary this item draws, while a cut at the reactor
edge would have severed all three surfaces. That gap is why the cut is drawn at direct dependencies
and not one hop earlier.

## What the census claims after the cut

`ClasspathScanner` documents the census as *the set the codegen loader can resolve*. Codegen builds
a `URLClassLoader` over the whole compile classpath, so today the two agree by construction, and a
narrowed census no longer does: a class reachable only through the transitive closure is still
bound by that loader and is no longer in the census.

That divergence is intended, and it is the point of the item rather than a cost of it. **A schema
may not name a class outside the reactor or a direct dependency.** Naming a transitively-reachable
class is an authoring error in its own right, and one every Java project already recognises: it is
the undeclared-dependency antipattern, the thing `maven-dependency-plugin:analyze` exists to flag,
and it breaks the moment an intermediate dependency drops the jar in a patch release. The narrowed
census is therefore not a weaker approximation of the resolvable set. It is the exact statement of
a different and better-defined question: what an author is permitted to name.

So the two class-name arms of `Diagnostics.judge`, `Finding.ClassName` and
`Finding.ScalarClassName`, keep firing on absence and are *correct* to reject a transitive-only
class. What changes is what they say. "Not
found on the compile classpath" is false in that case. But the replacement must not swing to
diagnosing a cause either: after the cut, `ClasspathClasses.Presence.UNKNOWN` merges a typo with a
real class in an undeclared jar, and the LSP holds no fact separating them, so "declare the
dependency" would be wrong for the common case. The message states the census's *scope* and leaves
the cause open. The enforcer below can say more because it holds the classification, and the editor
cannot borrow that: `lsp.server.Launcher` is a stdio process an editor spawns directly, so no
`RewriteContext` reaches it. Store facts are all it has, and origin is deliberately not one of them.

**The rule gets a build-side enforcer, in this item.** An invariant exists only while something
fails when it breaks, and an editor squiggle over a build that generates happily is not that. The
build is the one place the rule can be stated exactly, because it is the only place that holds the
classification.

**The enforcer reads the classification, not the census.** Census absence is a tempting predicate
and it is the wrong one: `jvm_class`'s own table comment enumerates four filters (public,
non-synthetic, top-level, outside the jOOQ package) and says that a resolution detection over the
relation reads those filters as absence. An enforcer keyed on census absence is such a detection, so
it would reject a nested class in the consumer's own module, which `InputBeanResolver.tryLoad`
exists to resolve. It would also reject every JDK class, and every class the codegen loader's parent
chain supplies. And it would stay silent on `SIBLING`, whose whole reason for being a separate arm
is that the build has something to say about it.

The exact question is whether the name is *carried by a non-`TRANSITIVE` entry*, and that is a
resource probe against the classified list rather than a fact about the store:
`name.replace('.', '/') + ".class"` present in an entry, mirroring `ClasspathScanner`'s own
`isJar` / directory dispatch. The probe is exact where the census is filtered, needs no store handle
and no second scan (listing a jar's entries is 13 ms across all 156 jars by the measurement above,
and only the two dozen kept entries are probed), and is unaffected by delegation order, which
matters because `buildCodegenLoader` parents on the plugin's own loader and is therefore parent-first
for anything the plugin also carries. Asking where the loaded class *came from* would answer that
question wrong: a properly declared `graphql-java` class loads from the plugin's realm.

A verdict per arm, plus the two cases no entry accounts for:

- `PROJECT` / `DECLARED`: nameable.
- `SIBLING`: rejected, "declare a dependency on module X". The entry names the module, which is why
  `ClasspathEntry` carries a coordinate.
- Not carried by any kept entry, but the platform loader resolves it: nameable. JDK classes are on
  every consumer's classpath by construction and are not a census question.
- Not carried by any kept entry: rejected. To name the cause the enforcer probes the `TRANSITIVE`
  entries too, and reports the coordinate that carries it. That is the one place a transitive jar
  gets opened, it happens only after a name has already failed, and the build is failing anyway.
- Empty classified list: inert. A unit-tier `RewriteContext` carries no classpath roots, and the
  rule cannot be enforced against a classification nobody supplied.

This is a breaking change for a consumer who today names a transitive class; the migration is one
`<dependency>` block, and it is the change the rule exists to force.

**The `<plugin><dependencies>` route goes with it, and that is the second breaking change.**
`docs/manual/how-to/external-code.adoc` tells consumers to declare the carrying artifact under the
plugin's own `<dependencies>`, twice, with a worked XML block. A class supplied only that way
resolves through the codegen loader's parent chain, is carried by no classpath entry, and is
rejected by the enforcer above. That is the right verdict and the route is withdrawn rather than
preserved: generated code *references* these classes, a `@service` target being called from the
generated resolver, so an artifact that is only on the plugin's classpath already fails the
consumer's own javac at the first generated reference. The route never sufficed on its own for
anything the generator emits a reference to. What changes is that the failure moves from the
consumer's compile to the generate that caused it, and names the cause. The practical migration is
the same one `<dependency>` block, and a consumer whose build works today already has it.

## Rejected: extract less per dependency class

Recorded because the numbers make it look attractive and it does not survive contact with what a
census is for. The alternative was to keep scanning every dependency but store almost nothing from
each: only what has a required signature, which in practice means the `public static
GraphQLScalarType` fields `@scalarType` binds. Thirty classes out of 9,477 carry one, so the store's
`jvm_` family would fall to roughly 42,000 rows instead of the 212,048 this item's width cut leaves,
and the read would fall to about 160 ms instead of 235 ms. Every scalar-carrying class is inside the
declared set, so the two cuts do compose.

It fails on completion. A census exists to answer *before* the author has written anything: an empty
schema, or a new type in an existing one, is exactly when class-name and method completion earn
their keep, and at that moment nothing in the document names the class the author is reaching for.
Any scheme that derives what to read from what the schema already mentions (resolving
`graphitron_service.class_name` and friends on demand through the codegen loader, which is otherwise
an appealing shape, since it is bounded by the schema and independent of which jar a class sits in)
can only ever confirm a name that has already been typed. It cannot offer one. So the enumerative
read stays, and the lever is which entries get enumerated.

The narrower point inside it is still true and is not a reason to revisit: reading fewer members per
class buys about 30%, because decompression rather than parsing is the cost. That is an argument for
this item's width cut, not against it.

**R762 proposes a third option this rejection does not reach, and a Spec pass here should reconcile
them rather than leaving both standing.** The alternative rejected above keeps almost nothing per
class, scalar constants only, so it drops class *names* and the rejection above is right that this
fails completion. R762 keeps every class name and drops only what sits *beneath* one, which this
rejection's own argument permits: nothing below a class name is ever asked before that name exists.
Measured, every read of the seven member-level relations pins a class name already written in the
document, those relations are 97% of the census, and one class's members resolve from the classfile
in about 0.1 ms.

Two corrections to the paragraph above follow from that, and both are narrow. The 30% figure governs
the *scan*, where bytes must be inflated whatever is kept; it does not govern the *write*, which is
the larger cost here (roughly 1.6 s per execution against the 648 ms scan) and which scales with rows
rather than with bytes inflated. And the two cuts compose rather than competing: this item stops
opening transitive jars at all, R762 stops storing members from the jars that are opened.

## One classified list, not two lists

The obvious implementation is to leave `AbstractRewriteMojo.resolveCompileClasspath` whole and add a
second, narrower resolve method beside it. Do not do that. Two sibling `List<Path>` values are
structurally interchangeable: nothing stops a call site handing the census list to the loader, and
the subset relation would live only in prose. `buildCodegenLoader`'s own javadoc records that these
two sets were assembled separately once, "agreed by coincidence, and the coincidence had already
broken". A second resolve method rebuilds exactly that topology.

The narrowing does not need two lists. One producer emits one list whose elements carry the decision:

```java
record ClasspathEntry(Path path, Origin origin, String coordinate) {}

enum Origin {
    PROJECT,     // this module's own build output
    DECLARED,    // on the compile classpath, coordinate appears in project.getDependencies()
    SIBLING,     // a reactor module's output that this module does not declare a dependency on
    TRANSITIVE   // on the compile classpath, coordinate not declared
}
```

`coordinate` is `groupId:artifactId` where one exists and null for `PROJECT`. It is there for the
enforcer's messages: "declare a dependency on module X" and "the class is in org.foo:bar, which this
module does not declare" are the two sentences the rule has to be able to say, and neither is
derivable from a `target/classes` path.

The loader, javac and the execution loader project every entry. The census projects
`origin != TRANSITIVE`. Census ⊆ loader is then a derivation over one classified list rather than a
promise about two, and the decision survives into the interior instead of being consumed at a filter.

`SIBLING` is its own arm rather than folded into `DECLARED` because the two earn different verdicts.
`resolveClasspathRoots` folds in *every* reactor project's output, so a sibling this module does not
depend on is offerable (an author can name it and then add the dependency, which is the dev loop
working) but is not yet buildable: generated code referencing it fails the consumer's own javac, for
the same undeclared-dependency reason the item is built on. Four arms is what lets the census say yes
and the build say "declare a dependency on module X".

## Implementation

**`graphitron`: the entry type.** `ClasspathEntry` and `Origin` live beside `RewriteContext`, not in
the plugin: they are plain records that cross the seam, and no Maven type comes with them.
`RewriteContext.classpathRoots` changes from `List<Path>` to `List<ClasspathEntry>`. The unit-tier
overloads that pass bare paths get a helper that wraps them as `PROJECT`, so those call sites stay
one argument long.

**`graphitron`: the scan.** `ClasspathScanner.scan(List<ClasspathEntry>, String)` skips a
`TRANSITIVE` entry before opening it, so the rule is stated once, in the code that would otherwise
read the entry, and no transitive jar is opened at all. The `isJar` / directory dispatch, the
dedup-by-FQN across entries and the jOOQ-package exclusion are all unchanged. Update the class
javadoc: its "Directories and jars alike" paragraph argues the widening this item narrows, and its
opening sentence claims the census "is the set the codegen loader can resolve".

**`graphitron-maven-plugin`: the classification.** `resolveCompileClasspath` becomes the producer of
the classified list. Follow the `decodeDependencyVersions` precedent in the same class: a static,
package-private decode taking the untyped Maven input and returning typed output, so `Artifact` and
`Dependency` never cross into `graphitron`. Two notes on the join:

- Try `Artifact.getDependencyTrail()` first. The trail's second element is the direct dependency the
  artifact arrived through, which states directness in one place rather than reconstructing it from
  two collections that must agree. Measure whether Maven 3.9.14 populates it in a real reactor build
  before committing; if it does not, fall back to joining `project.getArtifacts()` against
  `project.getDependencies()` on groupId, artifactId, **type and classifier**, never version, since
  dependencyManagement rewrites versions.
- Do not mint a scope allow-list. `GENERATED_CODE_SCOPES` already exists in this class and already
  argues which scopes generated code is built against. Intersecting the declared coordinates with
  what `getCompileClasspathElements()` returned leaves the scope question answered by Maven and by
  that existing constant; two hand-written scope sets in one file that must agree is the shape to
  avoid.

**`graphitron`: the nameability check.** A small type beside `ClasspathEntry`, built once over the
classified list and asked for a verdict per name, implementing the probe and the six cases in "What
the census claims after the cut". It holds each kept entry's class-resource index, built lazily on
first probe and reused, so a build pays one listing per entry however many names it checks. Nothing
about it is census-shaped: it reads no store and calls no scanner.

**`graphitron`: the enforcer's sites.** The criterion is *a class name an author wrote in the
schema*, which is narrower than "a `Class.forName` against the codegen loader". Of the seven
`forName` sites in `ServiceCatalog`, three take an author-written name and are the sites to check:
`decodeServiceMethod` (`@service`), `reflectTableMethod` (`@condition`), and `reflectExternalField`
(`@externalField`). The other four must stay exempt, and for reasons that generalise:
`resolveTableByRecordClassName` resolves a jOOQ record class, which is a catalog concept the census
excludes by design; `legacyArgExtraction` and `argExtraction` resolve a declared parameter type read
off a reflected signature, not a name anyone wrote; `reflectSessionHook` resolves a
`<sessionState>` `<mount>` / `<unmount>` target, which is plugin configuration rather than schema
text and which the `ClasspathScanner` javadoc already records as needing no census row. Outside
`ServiceCatalog`, the `@scalarType` constant path in `ScalarTypeResolver` is an author-written name
and is checked; the author-written `className` sites in `RecordBindingResolver` and `TypeBuilder`
are checked on the same criterion, and `InputBeanResolver.tryLoad`'s signature-derived names are
not.

Reject through the channels those coordinates already use: `Rejection.structural`, as
`ServiceDirectiveResolver` routes an unresolvable `@service` through, and a new
`ScalarResolution.Rejected` arm beside `ClassNotFound` for `@scalarType`. Reuse the existing channels
rather than minting a parallel failure path; the reason string is what differs.

**`graphitron`: a guard so the site list cannot rot.** Enumerating sites is how enforcement goes
stale: the next `Class.forName(..., codegenLoader)` someone adds is silently unchecked, and no test
fails. A source-scanning meta-test in the `RoadmapReferenceGuardTest` mould fails the build on a
`forName`-against-the-codegen-loader site in `graphitron` main sources that neither routes through
the nameability check nor carries an explicit exemption marker. That turns "did we cover every site"
from a review question into a build gate, which is the only form in which the answer stays true.

**`graphitron-lsp`: the message.** The `Finding.ClassName` and `Finding.ScalarClassName` arms of
`Diagnostics.judge` keep their `DiagnosticFacts.Resolution.UNKNOWN` guard and change their wording to
state the scope, not the cause. Something in the shape of: *Unknown class 'X'. The census covers this module
and its declared dependencies; a class reachable only through a transitive dependency must be declared
before it can be named here.* `ClasspathClasses`'s class javadoc asserts "a name the census does not
carry is a name that will not resolve at codegen either", which the narrowing falsifies on its own and
the enforcer then makes true again by a different route; rewrite it to say which route.

**`graphitron-model`: the DDL comments.** The DDL is the model's statement of what it holds, and two
comments describe the population this item changes. `jvm_class`'s table comment opens "A class exists
on the compile classpath, as the codegen loader would resolve it" and then enumerates its filters
*because* a resolution detection reads those filters as absence. The narrowing adds a filter, so it
belongs in that enumeration. The `jvm_` family definition in the `meta_family` view reads "The compile
classpath census". Both need to say declared-classpath rather than compile-classpath.

State the new silence while you are there: `ClasspathSources.record` writes a `store_source` row
lazily, on the first class read from an entry, so a skipped transitive jar produces **no row at all**
and nothing in the store records that the entry existed and was deliberately not read. That is the
intended design (provenance is a use-site fact and would be wrong on a store-global, definition-keyed
relation: a jar direct for module A is transitive for module B), but it must be stated rather than
left as an absence someone later reads as a bug.

## Tests

- `JarResidentClassCensusTest` keeps passing, with its fixture jar classified `DECLARED`. Its subject
  is unchanged: a jar-resident class reaches the census.
- The negative direction is this item's new claim and needs a real pin: a class reachable only
  transitively is absent from the census. A unit test over hand-built `Dependency` objects cannot
  pin it, for the reason `decodeDependencyVersions`'s javadoc already concedes about its own test:
  what a hand-built artifact set cannot pin is Maven's resolution. Put it in the plugin's integration
  tier beside `basic-generate` and `dependency-version-lag`, which are real reactor builds.
- A unit-tier pin on the classification decode itself, in the `DependencyVersionDecodeTest` mould:
  the four `Origin` arms from a hand-built declared list plus resolved set, including the `SIBLING`
  case.
- `ClasspathScannerTest` (unit tier) gains the skip: a `TRANSITIVE` entry contributes nothing, and
  is not opened.
- Two live tests encode the current claim and move with it: `FactCaptureAgreementTest.classCensusIsPartitionedBySource`,
  and `WarmStartRefreshTest`, which counts `STORE_SOURCE` rows of kind `JAR`.
- `FactSchemaGateTest.commentCoverageIsTotal` keeps DDL comments present, never true, so the comment
  rewrites above are the implementer's responsibility rather than a gate's.
- An LSP diagnostic pin on the new wording, in `DiagnosticsTest`.
- The enforcer needs an execution-visible pin: a schema naming a transitive-only class fails the
  build with the declared-dependency reason, naming the coordinate that carries the class.
- The nameability check's verdicts, unit tier, one case per arm of "What the census claims after the
  cut". Four of the six are the ones that make it a rule rather than a census read, and each is a
  build outcome rather than a message: a nested class in a kept entry is nameable, a JDK class is
  nameable, a `SIBLING` class is rejected naming the module, and a class carried only by the plugin's
  own classpath is rejected. The nested case is the one to write first, because it is where the
  discarded census-absence predicate would have failed silently.
- The enforcer is inert on an empty classified list. This is the pin that keeps the whole unit tier
  alive: `ServiceCatalogTest`'s six-arg overload and every `RewriteContext` built without classpath
  roots resolve real class names against a classification nobody supplied.
- The site guard fails when a `Class.forName` against the codegen loader is added with neither a
  check nor an exemption. A guard nobody has seen fail is a guard nobody should trust, so the test
  pins both directions against a fixture source rather than only the clean tree.

## What to re-measure at implementation

The item lands with a before and an after, not a projection. On `graphitron-sakila-example`, after the
cut: classpath entries, census classes, `jvm_` row count, and `ClasspathScanner` wall-clock. Predicted
from the store as it stands: 169 entries to 27, 11,537 classes to 4,355, 409,175 rows to 212,048, and
648 ms toward 200 ms (jOOQ and graphql-java survive the cut and are 2,051 classes between them, so the
parse does not fall as far as the entry count suggests). If the measured numbers differ materially,
record them rather than the predictions.

Add the write side, which the cost section above now carries a baseline for: the `jvm_` statements'
total time across a module's `graphitron:generate` executions, 8.1 seconds of 13.3 on trunk, predicted
to roughly halve. Take it with an `ExecuteListener` on the store's `DSLContext`, dumping per-statement
totals to a file named by PID at JVM exit; the PID matters because a Maven build has at least two JVMs
executing store statements and only the build's own one performs captures. And record the persisted
store's size on disk before and after, which is the number a consumer notices.

### Measured at implementation

All numbers from the implementing sandbox, a slower machine than the one the predictions were taken
on; shapes are what transfer.

- **The trail question is settled: Maven populates it.** In a real `graphitron-sakila-example` build,
  all 157 resolved artifacts carried a populated `Artifact.getDependencyTrail()`, and a two-element
  trail identified exactly the pom's 15 declared dependencies. The trail is the directness predicate;
  the declared join (groupId, artifactId, type, classifier) is the fallback for an unpopulated trail
  only, and is unit-pinned separately.
- **Classified entries: 169 to 27, exactly as predicted.** The real classified list for
  `graphitron-sakila-example` is 1 `PROJECT` + 11 `SIBLING` + 15 `DECLARED` + 142 `TRANSITIVE`; the
  census reads 27 entries and never opens the other 142.
- **Scan, best of three warm runs over that real list, same machine both ways:** all entries scanned,
  672 ms and 11,704 classes; after the cut, 281 ms and 4,524 classes. The predicted 648 to ~200 ms
  and 11,537 to 4,355 classes hold in shape; the absolute numbers are this sandbox's.
- **Workspace store after one full reactor build:** 222,448 census rows, 4,667 `jvm_class` rows,
  446 MB on disk, against the pre-cut segment's 424,344 census rows, 12,025 classes and 796 MB. The
  pre-cut segment had accumulated more graphs across builds, so only the census axis is comparable,
  and it roughly halves as predicted.
- **The write side moved less than predicted, and is recorded as measured per this section's own
  rule: 28%, not half.** Same machine, `ExecuteListener` on the store's `DSLContext`, the module's
  five `graphitron:generate` executions in one build: the `jvm_` family's deletes and merges total
  12.8 s per build with the wide census against 9.2 s with the cut (two narrow runs within 10 ms of
  each other). Two caveats before treating 28% as the truth of the mechanism: the narrow runs
  executed against store tables still holding the wide partitions a preceding wide run left behind
  (a skipped source's partition is deliberately not deleted, per the provenance note above), which
  keeps the delete statements' table scans large; and the member-level merges R762 targets dominate
  what remains, four `JVM_METHOD*` statements carrying about 5.0 of the 9.2 s. The row cut this item
  makes and the member cut R762 makes compose, and the write side is where R762's half lives.

## User documentation (first-client check)

The rule is user-facing, so the doc draft is part of the design. `docs/manual/how-to/external-code.adoc`
is its home: it already has a "Make the class reachable" section, and that section is currently wrong
in a way this item has to fix anyway. It says the class "has to be on the *plugin's* classpath, not the
consumer module's compile classpath", repeats it in the Constraints bullet, and carries a worked
`<plugin><dependencies>` XML block as the recipe. `buildCodegenLoader` builds the codegen loader over
the module's compile classpath parented on the plugin's loader, so the module's own classpath has
worked for some time; the manual understates what is reachable while this item narrows it.

The XML block goes with the sentences, not just the wording around it. Per "The
`<plugin><dependencies>` route goes with it" above, that route is withdrawn: it is not a second way
to make a class reachable, and the same block moved to the module's own `<dependencies>` is the
replacement recipe. Say plainly in the section that a plugin-block dependency does not make a class
nameable, because a reader who did it that way needs to recognise their own setup, and the closing
bullet becomes the same statement rather than its opposite. All three statements collapse into one
rule:

> A named class must live in this module, in another module of the same Maven project, or in a
> dependency this module declares itself. A class that is only reachable through another
> dependency's dependencies is not nameable: declare it, and it becomes nameable. The generator
> rejects a schema that names an undeclared class, and the editor marks it before you build.

`docs/manual/reference/directives/scalarType.adoc` carries the same reachability question for the
constant form and gets an xref rather than a second copy of the rule. If the rule does not read simply
in these two places, the design is wrong and changes before implementation.

## Retired vocabulary

For the Done-gate retirement sweep. These claims are all currently true and all become false:

- "a class the loader resolves is a class the census holds" (`buildCodegenLoader` javadoc,
  `RewriteContext`'s `@param classpathRoots`)
- "the census is the set the codegen loader can resolve" (`ClasspathScanner` class javadoc)
- "a name the census does not carry is a name that will not resolve at codegen either"
  (`ClasspathClasses` class javadoc)
- "Not found on the compile classpath" (both `Diagnostics` message sites)
- "A class exists on the compile classpath, as the codegen loader would resolve it"
  (`jvm_class` table comment)
- "The compile classpath census" (`meta_family`'s `jvm_` definition)
- "has to be on the *plugin's* classpath, not the consumer module's compile classpath"
  (`docs/manual/how-to/external-code.adoc`)
- "The carrying artifact must be on the *plugin's* classpath (the `<plugin>...<dependencies>` block),
  not the module's outer `<dependencies>`" (the Constraints bullet in the same file, plus the
  `<plugin><dependencies>` XML block the section walks through)

## Not in scope

The duplicate scan per dev-loop pass (R620) is a separate cut at the same cost and neither item
blocks the other. Narrowing the entry list makes both scans cheaper without making the second one
any less redundant.

Storing entry provenance as a fact is deliberately excluded. If it ever lands it is keyed on
(graph, entry) and not on the entry: directness is a property of a use site, not of a jar, and one
workspace store holds many modules' graphs, so a jar that is `DECLARED` for one module is
`TRANSITIVE` for another. An `origin` column on the store-global, definition-keyed `store_source`
would be silently wrong the first time two modules in one reactor disagree. Nothing in this item
needs it: the classification is consumed inside the run that produces it.

## Reviewer findings

### Spec → Ready gate, first pass: findings, status stays Spec (session_015LoscQmkhUekgAkJHMqRCB, 2026-08-21)

The measurement work is the strongest part of the item and I am not reopening any of it. The width
cut itself, the four-arm classified list over two sibling `List<Path>` values, the rejection of the
per-class depth cut, the `SIBLING` arm's existence, the composition with R762, the test list and the
re-measurement section all hold up against the tree. Every symbol the plan names exists where it says
except the two corrected below, and every "today reads" quotation is verbatim: the `ClasspathScanner`
javadoc's two claims, `buildCodegenLoader`'s coincidence paragraph, the seven `Class.forName` sites in
`ServiceCatalog` with six through `ctx.codegenLoader()`, the `jvm_class` table comment, the
`meta_family` row, both `Diagnostics` message strings, `ScalarResolution.Rejected.ClassNotFound`,
`Rejection.structural` in `ServiceDirectiveResolver`, the seven named tests, `src/it/basic-generate`
and `src/it/dependency-version-lag`, and both wrong statements in
`docs/manual/how-to/external-code.adoc`.

What blocks the gate is the enforcer, which is the half of the item that turns the narrowing from a
performance cut into a rule. Two findings, both on that section.

**1. "Resolves, but no census row" is not the rule the item states, and it is not decidable where the
item puts it.** The rule is "a schema may not name a class outside the reactor or a direct
dependency". The predicate is census absence. Those are different sets in three directions, and each
one is a wrong build outcome rather than a wrong message.

- *It over-rejects on the census's other four filters.* `jvm_class`'s own table comment enumerates
  them and says why: public, non-synthetic, top-level (`ClasspathScanner` skips any simple name
  containing `$`), and outside the jOOQ package, "so a resolution detection over this relation reads
  those filters as absence". The proposed enforcer is exactly such a detection. A `@record` or
  `@service` naming a nested class in the consumer's own module resolves through
  `recordClass.reflectionName()` and has no census row today, so it would be rejected with a
  declare-your-dependency reason for a class sitting in the module's own `target/classes`. No fixture
  in the reactor names a nested class, so this is latent rather than currently broken, which is
  precisely the kind of thing a build-side enforcer converts into a consumer's failed build.
- *It over-rejects everything the parent loader supplies.* `buildCodegenLoader` parents the
  `URLClassLoader` on the plugin's own loader, so a class on the plugin's classpath resolves and has
  no census row. Finding 2 is what that costs.
- *It under-rejects `SIBLING`.* A sibling module's class has a census row under
  `origin != TRANSITIVE`, so the enforcer stays silent on it. But the "One classified list" section
  says four arms exist so "the census say[s] yes and the build say[s] declare a dependency on module
  X". As specified, the build never says it, and `SIBLING` earns no verdict anywhere. That is the arm
  whose existence the section argues for, so the gap is in the load-bearing part of the design.

The plumbing claim under it does not hold either. "The census is already computed in the same pass"
is true of the run but not of the site: `ServiceCatalog` holds no store handle, `RewriteContext`
carries none, and nothing hands a scan result to `ServiceCatalog`. The only census available from
`ctx` alone is `CatalogBuilder.buildExternalReferences(ctx)`, which runs `ClasspathScanner.scan`
again, so "with no second scan and no heuristic" is unbacked as written.

There is a predicate that is the rule rather than a proxy for it, and this item's own type change puts
it within reach: after `classpathRoots` becomes `List<ClasspathEntry>`, a resolved class's
`getProtectionDomain().getCodeSource()` names the entry it came from, and the entry carries its
`Origin`. That reads the classification directly, distinguishes `SIBLING` from `DECLARED` so the
module-X message becomes writable, is silent on nested and jOOQ-package classes because they are not
a census question at all, and needs no second scan and no store read. It also has to answer what a
null `CodeSource` means (a parent-loaded class, which is finding 2's subject) and what the four
`Class.forName` sites in `JooqCatalog` and the ones in `ClassAccessorResolver` /
`RecordBindingResolver` do, since the item scopes the enforcer to `ServiceCatalog` without saying why
the others are exempt. Whichever predicate the revision picks, name it in terms of the classification
and say what happens at each of the four arms.

*Author response.* Taken, and the predicate is replaced rather than patched. "What the census claims
after the cut" now states the enforcer as a resource probe against the classified list, with a
verdict for each of the four arms plus the two cases the probe does not cover (a platform-loader
class is nameable, an empty classified list is inert). The `CodeSource` route the finding suggested
is *not* what landed, and the finding's own second bullet is why: the codegen loader is parent-first,
so a correctly declared `graphql-java` class loads from the plugin's realm and would be rejected by
anything that asks where a loaded class came from. Probing the entries answers the question the rule
actually asks, which is whether a *name* is carried, and it is exact where the census is filtered.
`ClasspathEntry` gains a `coordinate` so the two rejection sentences can name a module and a jar.

The site scoping was wrong too, in the direction the finding did not reach: "the seven `forName`
sites in `ServiceCatalog`" is not the author-written set. Three of the seven are, and the four
exemptions each have a reason that generalises (a jOOQ catalog class, two signature-derived types,
one plugin-config hook), while author-written names outside `ServiceCatalog` in `ScalarTypeResolver`,
`RecordBindingResolver` and `TypeBuilder` were missing from the set. The Implementation section now
carries the criterion, the enumeration, and a source-scanning guard test so a later `forName` cannot
join the tree unchecked, because an enumeration defended only by review is the thing that rots.

**2. The item withdraws the `<plugin><dependencies>` route without declaring it.** The item reads
`docs/manual/how-to/external-code.adoc`'s "has to be on the *plugin's* classpath" as an understatement
to be corrected, and it is one. But that section is not only wrong about the module classpath, it also
documents a route that works: it carries a worked `<plugin><dependencies>` XML block, and repeats the
instruction in the Constraints bullet, and a class supplied that way resolves today through the
codegen loader's parent chain. The replacement rule drafted in the first-client check ("this module,
another module of the same Maven project, or a dependency this module declares itself") excludes it,
and the enforcer would reject it, so the item makes a second breaking change to a documented route,
larger than the transitive one it is built to make, while presenting the doc edit as a correction.

Take a position and write it into the item, either way. If the route is being withdrawn, say so where
the breaking change is discussed, say what the migration is (the artifact moves from the plugin's
`<dependencies>` to the module's), and note that the manual's XML example is replaced rather than
reworded. If it stays supported, the rule statement has to admit it and the enforcer must not fire on
a parent-loaded class, which is the same design fork finding 1 names.

*Author response.* Position taken: the route is withdrawn, and the item now says so in its own
section rather than leaving it inside a doc edit. The argument that decides it is one the item had
not made: generated code *references* these classes, a `@service` target being called from the
generated resolver, so an artifact sitting only on the plugin's classpath already fails the
consumer's javac at the first generated reference. The plugin block never sufficed on its own for
anything the generator emits a reference to, which makes this a correction of a wrong instruction
rather than the removal of a working one, and it is why the practical migration is empty for any
consumer whose build currently passes. What the enforcer changes is when they hear about it and what
it says. The first-client check now replaces the XML block rather than rewording the prose around it,
the section has to name the plugin-block setup so a reader recognises their own, and the Constraints
bullet joins the retired vocabulary.

**Corrected in this commit, not for the author.** The LSP section named
`Diagnostics.validateScalarTypeClasspath` and `Diagnostics.validateClassName`, neither of which
exists. The two message sites are the `Finding.ClassName` and `Finding.ScalarClassName` arms of
`Diagnostics.judge`, and their guard is `DiagnosticFacts.Resolution.UNKNOWN`, not
`ClasspathClasses.Presence.UNKNOWN`. Both mentions are repointed. The `Presence.UNKNOWN` sentence in
"What the census claims after the cut" is left alone: it is about what the LSP can and cannot tell
apart, and it is true of that enum. One note the item may want while it is in that file:
`ClasspathClasses.presenceOf` has a third caller, `Definitions` at goto-definition, which returns
nothing rather than a message and so needs no wording change, but it is the reason the class javadoc
rewrite the item asks for should describe the census's scope rather than any one surface's message.

*Author response.* Noted and left as guidance for the implementer rather than a plan edit: the
`ClasspathClasses` javadoc rewrite the item already asks for is the place it lands, and it is one
sentence about scope rather than a third surface to change.

### In Review → Done gate, first pass: rework, status back to Ready (session_019XrhmZGmYuihYNLWVBdTxL, 2026-08-21)

`mvn install -Plocal-db` is green on the delivered tree rebased onto trunk, the new
`transitive-not-nameable` invoker IT included.

The implementation is the change this spec approved, and I am not reopening any of it. The
classified list replaced `List<Path>` at one producer; `ClasspathScanner` skips a `TRANSITIVE`
entry before opening it; `ClasspathNameability` is the resource probe the revision specified,
not the `CodeSource` route the first gate suggested, with all four `Origin` arms plus the
platform-loader and empty-list cases each pinned by a unit case and the nested case written
first; `CodegenClassForNameGuardTest` closes the site enumeration in both directions; the
`jvm_class` and `meta_family` comments, the two `Diagnostics` arms, the `ClasspathClasses`
javadoc and the `ClasspathSources` silence note all landed as drafted; and the re-measurement
section records the write side at 28% rather than repeating the predicted half, with its
caveats, which is what that section asked for. Question 1 passes.

**What blocks the gate is question 2, on the user-facing half, and it is the Retirement sweep
rather than a scope gap.** The `Retired vocabulary` section above declares "has to be on the
*plugin's* classpath, not the consumer module's compile classpath" a claim that becomes false.
The first-client check attributed that claim to `docs/manual/how-to/external-code.adoc` and to
the Constraints bullet in the same file, and both were fixed well: the `make-the-class-nameable`
section states the one rule, replaces the XML block with a module-level `<dependency>`, and names
the plugin-block setup so a reader recognises their own. But the claim is not confined to that
file. It survives in five more manual pages, nine sites, and the sweep's remit is all prose
surfaces including the user manual:

- `how-to/add-custom-conditions.adoc:41` and `how-to/computed-fields.adoc:39` are numbered recipe
  steps reading "Add the carrying artifact to the rewrite plugin's `<dependencies>` block, not
  your consumer module's compile classpath". A reader following either verbatim now gets the
  build failure this item's own enforcer emits, at `@condition` and `@externalField`, both gated
  sites.
- `how-to/computed-fields.adoc:158` carries the retired sentence near-verbatim;
  `how-to/add-custom-conditions.adoc:173` paraphrases it ("The class must be on the rewrite
  plugin's classpath, not the consumer module's").
- `reference/directives/service.adoc:28`, `reference/directives/externalField.adoc:92` and
  `reference/directives/enum.adoc:36` are the per-directive reference entries for three of the
  four directives `external-code.adoc`'s own opening sentence enumerates as sharing these
  mechanics. Each still requires the plugin classpath, two of them naming the `<plugin>` block
  explicitly.
- `reference/mojo-configuration.adoc:368` is already right that the module classpath works, but
  its closing sentence keeps `<plugin><dependencies>` as "the rare legitimate case" for version
  pinning, which the withdrawal removes for any author-written name.
- Four `xref` lead-ins (`add-custom-conditions.adoc:7`, `:186`, `computed-fields.adoc:7`, `:164`,
  and `enum.adoc:50`) send the reader to `external-code.adoc` for "the plugin-classpath setup",
  which is no longer what that page teaches.

So the manual now states the rule and its opposite, and the pages carrying the opposite are the
step-by-step recipes and the per-directive reference entries a consumer is most likely to reach.
That is not a phrasing preference: it is the documented contract contradicting the build outcome
at three gated directives, on an item whose stated goal is that the rule "read simply" to a
consumer. `how-to/custom-scalars.adoc:28` is the counter-example worth copying, already correct
and consistent without this item touching it.

**What would satisfy the gate.** Bring those nine sites onto the one rule, pointing at
`external-code.adoc#make-the-class-nameable` rather than restating it, and re-run the sweep over
the manual. Nothing else is outstanding; no code change is implied.

Two non-blocking notes, neither bearing on either question. `classifyElement` adds a fifth arm
the plan does not enumerate, a path no artifact and no reactor project accounts for classified
`DECLARED` with no coordinate; it fails open, is argued in the javadoc and is pinned by
`ClasspathClassificationDecodeTest.anUnattributablePathStaysInTheCensus`, so it reads as the
right call rather than a silent substitution. And the plan said `RecordBindingResolver`'s
author-written `className` sites are checked, where the delivery marks them exempt because
`ServiceCatalog` has already gated the same `@service` / `@externalField` names before that
observation pass runs; that is the better placement, and the two sites the plan had missed
(`@error` in `TypeBuilder.validateExceptionClass`, `@sourceRow` in `SourceRowDirectiveResolver`)
are gated instead.

### Author handoff (2026-08-21)

The item's original author is unavailable, so the reviewer session above took over the author role
and landed the revision to findings 1 and 2 in the plan body. That disqualifies
`session_015LoscQmkhUekgAkJHMqRCB` from signing this item off: the next `Spec → Ready` pass needs a
session that has neither written nor reviewed this plan. Recorded here because the guard resolves the
disqualified party from `git log` on this file, which now returns the reviewer as last committer and
is correct but says nothing about why.
