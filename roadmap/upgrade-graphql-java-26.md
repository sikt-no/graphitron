---
id: R467
title: "Upgrade graphql-java 25.0 -> 26.0"
status: Spec
bucket: tech-debt
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-08-03
---

# Upgrade graphql-java 25.0 -> 26.0

Bump `graphql-java` from 25.0 to 26.0 in the root `pom.xml` dependency-management block, together
with the federation release that unblocks it.

## External blocker: cleared

`federation-graphql-java-support` 7.0.0 is on Maven Central (published 2026-08-03) and declares
`com.graphql-java:graphql-java:26.0` as a `compile` dependency, which is the release
apollographql/federation-jvm#454 was tracking. Nothing external is outstanding; this item is
implementable.

`graphql-java-extended-scalars` stays at 24.0. 24.0 is still the newest release (the `<latest>`
marker in its Maven metadata points at a stale dated snapshot, not a newer release), and it resolves
and behaves correctly against graphql-java 26.

## Scope, as verified by a full dry run

The whole change was exercised end to end on a throwaway working tree: the two version bumps plus
the fixes below produce a green `mvn install -Plocal-db` across all 13 modules, tests, docs render
and the javadoc reference gate included. The findings below are measured, not predicted.

### 1. Two version bumps (root `pom.xml` dependency-management)

`graphql-java` 25.0 -> 26.0 and `federation-graphql-java-support` 6.2.0 -> 7.0.0. These must move
together; either alone is incoherent.

### 2. `DirectiveInfo` is gone; `Directives` replaces it

`graphql.schema.idl.DirectiveInfo` no longer exists in 26.0 (the class file is absent from the jar,
and no relocated equivalent carries `isGraphqlSpecifiedDirective`). Its role moved onto
`graphql.Directives`, which now publishes `BUILT_IN_DIRECTIVES`, `BUILT_IN_DIRECTIVES_MAP` and
`isBuiltInDirective(...)` in both the name and `GraphQLDirective` overloads. The membership set is
identical to 25.0's `GRAPHQL_SPECIFICATION_DIRECTIVE_MAP` (`@include`, `@skip`, `@deprecated`,
`@specifiedBy`, `@oneOf`, `@defer`, `@experimental_disableErrorPropagation`), so this is a rename,
not a behaviour change. graphql-java's own `SchemaPrinter` already routes its
`ExcludeGraphQLSpecifiedDirectivesPredicate` through `Directives.isBuiltInDirective`, which
confirms the intended replacement.

One call site, in `SchemaSdlEmitter.includeSchemaElement`: swap the
`graphql.schema.idl.DirectiveInfo` import for `graphql.Directives` and the call for
`Directives.isBuiltInDirective(directive)`.

`OneOfDirectiveSdl`'s class javadoc also names
`{@code graphql.schema.idl.DirectiveInfo.isGraphqlSpecifiedDirective}` in prose when explaining why
the federation printer strips the `@oneOf` definition. That is a `{@code}` reference, so no gate
catches it, but it goes stale with this bump and should be repointed to
`Directives.isBuiltInDirective` in the same commit.

### 3. `additionalType` narrowed to `GraphQLNamedType`

`GraphQLSchema.Builder.additionalType` narrowed its parameter from `GraphQLType` to
`GraphQLNamedType`, and `additionalTypes` correspondingly to `Set<? extends GraphQLNamedType>`;
`GraphQLSchema.getAdditionalTypes()` now returns `Set<GraphQLNamedType>`.

One call site, in `ConnectionPromoter`'s post-walk rebuild. The fix is a strict simplification
rather than a workaround: the local `pinned` map is declared `LinkedHashMap<String, GraphQLType>`
but every value is already downcast to `GraphQLNamedType` to read its name, so declaring the map
`LinkedHashMap<String, GraphQLNamedType>` and hoisting that existing cast to the `putIfAbsent` makes
the narrower static type carry what the code already assumed.

Also worth a reviewer's eye, though it did not bite: 26.0 adds a `FastBuilder` path and documents
that `getAdditionalTypes()` under it returns *all* non-root types rather than only explicitly-added
ones. Graphitron does not use `FastBuilder`, so the semantic split is latent, but the assembled
schema's additional-type set is load-bearing for classification and it is worth confirming nothing
in the rebuild path reaches `getAdditionalTypes()` expecting the narrow meaning.

### 4. New: the `-Werror` warning ratchet trips, and the fix is a policy call

Not predicted at filing time, and the only part of this item that is a genuine decision rather than
a mechanical edit. With graphql-java 26.0 on the classpath, compiling the `graphitron` module emits
nine `[classfile]` warnings and `-Werror` turns them into a build failure:

```
graphql-java-26.0.jar(/graphql/com/google/common/collect/ImmutableList.class): warning: [classfile]
  Cannot find annotation method 'serializable()' in type 'GwtCompatible':
  class file for com.google.common.annotations.GwtCompatible not found
```

The warnings originate entirely inside graphql-java's own jar, in its relocated
`graphql.com.google.common.collect.ImmutableList`, whose retained annotations still reference the
unrelocated `com.google.common.annotations` and `com.google.errorprone.annotations` packages that
the shaded jar does not bundle. This is not graphitron code and cannot be fixed in graphitron code.
It is also not a regression in graphql-java's shading: 25.0's relocated `ImmutableList` carries
byte-identical dangling annotation references. What changed is that some resolution path in the
`graphitron` module now reaches that class file where under 25.0 it did not, so javac reads its
annotations and reports what it cannot resolve. Only `graphitron` is affected; every other module
compiles clean.

Two remedies, both verified to produce a fully green reactor:

**A. Exclude the `classfile` lint category** (`-Xlint:all,-classfile` in the root pom's
`compilerArgs`, with the reason recorded in the comment block that already sits there for exactly
this purpose). This is the escape hatch the ratchet documents. The argument for it is that
`classfile` is structurally incapable of serving the ratchet's stated goal: it fires only when javac
reads a *dependency's* class files, never on Java that graphitron emits, so excluding it does not
weaken the cross-module backstop that a generator change emitting warning-producing code fails the
build. The cost is losing a genuine signal about malformed dependency jars tree-wide.

**B. Put the annotation artifacts on the compile classpath** (`com.google.guava:guava` and
`com.google.errorprone:error_prone_annotations`, `provided` scope, on `graphitron` only). Verified:
this clears all nine warnings and `graphitron` main compiles clean under the untouched
`-Xlint:all -Werror`. It preserves the `classfile` category. The cost is dragging a multi-megabyte
library onto a module's compile classpath purely so javac can resolve annotation methods it will
then discard, plus the standing risk that Guava becomes casually reachable in `graphitron` source.

**Recommendation: A**, on the reasoning that the excluded category cannot detect the class of defect
the ratchet exists to catch, and that a recorded one-line exclusion is a smaller standing liability
than a dependency added as a shim for another project's shading artifact. The Spec reviewer should
treat this as the item's one real decision and overrule if they weigh the lost `classfile` signal
higher.

### 5. Emitted-code surface (surface A): clean, no generator change

This was the filing's open question, and it resolves in graphitron's favour. Once the generator
compiles, the full pipeline (generate -> `release 17` compile of emitted sources -> execute) passes:
`graphitron-sakila-example` runs 704 tests across 90 classes with zero failures against a live
database.

None of the three emitted APIs the coupling map flagged as highest-risk needed any change: the
`graphql.execution.instrumentation.*` Instrumentation SPI that
`GraphitronConnectionInstrumentationGenerator` emits against, `DataFetchingEnvironmentImpl`, and
`ValuesResolver.valueToLiteral` in `AppliedDirectiveEmitter` all still resolve as emitted.

The `additionalType` narrowing also needs no generator change, which is worth stating explicitly
because it is the non-obvious outcome: the generator emits `additionalType(XType.type())` and
`additionalType(graphql.Scalars.GraphQLString)`, and every such argument is already a
`GraphQLNamedType`, so the emitted call sites bind the narrowed overload as written. Confirmed at
the bytecode level: emitted classes compile to
`additionalType:(Lgraphql/schema/GraphQLNamedType;)`. No `GraphitronSchemaClassGeneratorTest`
expectation changes, since they assert the emitted argument text, which is unchanged.

### 6. Not affected

JDK floor is unchanged (26 keeps a Java 21 runtime floor; generated output stays Java 17). No test
expectations change anywhere in the reactor.

## Implementation note

A `NoSuchMethodError` cascade on `additionalType(GraphQLType)` across the execution tier is the
signature of stale bytecode, not a real break: a non-`clean` build after the version bump leaves
`graphitron-sakila-example` class files compiled against 25.0, because regeneration alone does not
force recompilation. Use `mvn clean install -Plocal-db` when verifying this item; a warm incremental
build will produce dozens of misleading failures.
