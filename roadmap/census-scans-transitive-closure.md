---
id: R685
title: "The class census scans the transitive dependency closure"
status: Spec
bucket: architecture
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-16
last-updated: 2026-08-16
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

So `Diagnostics.validateScalarTypeClasspath` and `Diagnostics.validateClassName` keep firing on
absence and are *correct* to reject a transitive-only class. What changes is what they say. "Not
found on the compile classpath" is false in that case. But the replacement must not swing to
diagnosing a cause either: after the cut, `ClasspathClasses.Presence.UNKNOWN` merges a typo with a
real class in an undeclared jar, and the LSP holds no fact separating them, so "declare the
dependency" would be wrong for the common case. The message states the census's *scope* and leaves
the cause open.

**The rule gets a build-side enforcer, in this item.** An invariant exists only while something
fails when it breaks, and an editor squiggle over a build that generates happily is not that. The
build is the one place both sets are in hand: `ServiceCatalog`'s `Class.forName(..., ctx.codegenLoader())`
sites resolve against the whole classpath while the census is already computed in the same pass, so
"resolves, but no census row" is decidable there with no second scan and no heuristic. That is also
the only place the honest wording lives, because it is the only place the two populations are told
apart. This is a breaking change for a consumer who today names a transitive class; the migration is
one `<dependency>` block, and it is the change the rule exists to force.

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
record ClasspathEntry(Path path, Origin origin) {}

enum Origin {
    PROJECT,     // this module's own build output
    DECLARED,    // on the compile classpath, coordinate appears in project.getDependencies()
    SIBLING,     // a reactor module's output that this module does not declare a dependency on
    TRANSITIVE   // on the compile classpath, coordinate not declared
}
```

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

**`graphitron`: the enforcer.** The `Class.forName(..., codegenLoader)` sites in `ServiceCatalog`
(seven at the time of writing, six of them through `ctx.codegenLoader()`) are where a class name
becomes a resolved class. A class that resolves but carries
no census row is rejected there, through the rejection channels those coordinates already use:
`Rejection.structural` as `ServiceDirectiveResolver` routes an unresolvable `@service` through, and a
new `ScalarResolution.Rejected` arm beside `ClassNotFound` for `@scalarType`. Reuse the existing
channels rather than minting a parallel failure path; the reason string is what differs.

**`graphitron-lsp`: the message.** `Diagnostics.validateScalarTypeClasspath` and
`Diagnostics.validateClassName` keep their `Presence.UNKNOWN` guard and change their wording to state
the scope, not the cause. Something in the shape of: *Unknown class 'X'. The census covers this module
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
  build with the declared-dependency reason.

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

## User documentation (first-client check)

The rule is user-facing, so the doc draft is part of the design. `docs/manual/how-to/external-code.adoc`
is its home: it already has a "Make the class reachable" section, and that section is currently wrong
in a way this item has to fix anyway. It says the class "has to be on the *plugin's* classpath, not the
consumer module's compile classpath", and repeats it in the section's closing bullet. `buildCodegenLoader`
builds the codegen loader over the module's compile classpath parented on the plugin's loader, so the
module's own classpath has worked for some time; the manual understates what is reachable while this
item narrows it. Both statements are replaced by one rule:

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
