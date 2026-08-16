---
id: R685
title: "The class census scans the transitive dependency closure"
status: Backlog
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
| Reactor modules (directories and sibling jars) | 2,060 | 52,215 |
| Third-party jars | 9,477 | 356,960 |
| Total | 11,537 | 409,175 |

The store holds 413,327 rows in total, so **99% of the store is class census** and **87% of the
store is third-party jars**. Everything else the store knows about this graph, every GraphQL type,
field, directive, table, binding and intent row, is about 4,150 rows.

Read cost, `ClasspathScanner` over that entry list, best of three warm runs: 648 ms for the whole
classpath, 51 ms for the reactor directories alone, 516 ms for the third-party jars alone. So the
jars are roughly 92% of the parse time. The dev loop pays it twice per pass, which is R620's
subject and not this item's.

Nothing names most of it. The transitive tail is 42 quarkus jars (1,288 classes), 14 netty jars
(1,137), 34 smallrye jars (782), 7 vertx jars (815), 9 jboss jars (616). A consumer writes
`@service`, `@condition`, `@record` and `@scalarType` against its own code and against the
libraries it chose; it does not name a netty buffer allocator in a GraphQL schema.

## What the tail must not take with it

The census was widened from compile-output directories to the whole classpath to fix a real bug:
`scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")` generated fine and
red-squiggled in the editor, because codegen resolved the constant reflectively while the scan had
never opened a jar. Three measurements say a naive narrowing to reactor modules alone would bring
that back, and all three survive the narrowing proposed here because the classes involved sit in
*direct* dependencies:

- Every one of the 69 `jvm_scalar_type_field` rows comes from a third-party jar: 61 from
  `graphql-java-extended-scalars`, 5 from `graphql-java`, 3 from the federation support jar. Zero
  come from reactor code. `@scalarType` completion exists only because jars are scanned.
- 536 of the 2,060 reactor classes, just over a quarter, declare a supertype that resolves in a
  third-party jar. Assignability is answered from `jvm_class_supertype`, so narrowing past those
  truncates the chain.
- 1,617 distinct reactor method return-type references point at a third-party class. Accessor-hop
  walks follow those into container element types.

All three land in jars the consumer declared itself. That is the reason the cut is drawn at direct
dependencies rather than at the reactor boundary.

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

So `Diagnostics.validateScalarTypeClasspath` and the general unknown-class check keep firing on
absence, keep their confidence, and are *correct* to reject a transitive-only class. What changes
is what they say. "Not found on the compile classpath" would be false in that case, and the message
should name the real problem and its fix: the class is not in this module's reactor or its declared
dependencies, and the repair is to declare the dependency. Both call sites are in
`graphitron-lsp`'s `Diagnostics`.

One consequence to settle rather than assume: codegen keeps resolving over the full classpath, so
a schema naming a transitive class would red-squiggle in the editor and still build. Whether the
build should reject it too, so the editor and the generator state one rule, is a decision for Spec,
not a reason to widen the census.

## What to work out at Spec

- **Where the direct set comes from.** `AbstractRewriteMojo.resolveCompileClasspath` reads
  `project.getCompileClasspathElements()`, which is already flattened. The declared set is
  `project.getDependencies()`, which carries no resolved file, so the two have to be joined on
  groupId/artifactId/classifier. Decide whether that join lives in the mojo or whether the resolver
  API offers the direct set directly.
- **`provided` and `system` scope.** `getCompileClasspathElements` includes them. A directly
  declared provided dependency should stay in; whether its transitives should is the same question
  as for compile.
- **Reactor modules stay whole.** `resolveClasspathRoots` already folds in every reactor project's
  output directory plus the sibling walk-up for a single-project reactor. That half is unchanged;
  a sibling module is in whether or not the current module depends on it.
- **The generated jOOQ package stays excluded**, as it is today.
- **What the numbers become.** Re-measure after the cut: entries, classes, `jvm_` rows and scan
  milliseconds, on the same module, so the item lands with a before and an after rather than a
  projection. The predicted shape is 169 entries down to about 27 and the parse down toward 100 ms,
  but the class and row counts depend on how much of jOOQ and graphql-java survives, and those two
  jars alone are 2,051 classes.
- **A test that pins the boundary.** `JarResidentClassCensusTest` pins that a jar-resident class
  reaches the census; it should keep passing, with the fixture jar wired as a direct dependency. The
  new claim needs its own pin, in both directions: a class reachable only transitively is *not* in
  the census, and the unknown-class diagnostic does report it, with the message that names the
  missing declaration rather than the classpath.
- **Whether codegen enforces the same rule.** Today it would resolve the transitive class and
  generate. Deciding it should not is a change to the generator, not to the census, and could be
  split out; deciding it should stay permissive means the editor is stricter than the build, which
  is defensible but should be said out loud rather than fallen into.

## Not in scope

The duplicate scan per dev-loop pass (R620) is a separate cut at the same cost and neither item
blocks the other. Narrowing the entry list makes both scans cheaper without making the second one
any less redundant.
