---
id: R467
title: "Upgrade graphql-java 25.0 -> 26.0"
status: Ready
bucket: tech-debt
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-08-08
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

26.0 also adds a `FastBuilder` path and documents that `getAdditionalTypes()` under it returns *all*
non-root types rather than only explicitly-added ones. The draft left confirming graphitron's
exposure to the reviewer; settled at the gate: `getAdditionalTypes()` has zero call sites in the
reactor, main and test alike, so nothing depends on either meaning and the semantic split stays
latent. Graphitron does not use `FastBuilder` either. Nothing to do here.

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

The comment block is not append-only here. It currently asserts "No category is excluded: the full
`mvn install -Plocal-db` build is clean across all of them", which remedy A makes false. Rewrite
that sentence to name `classfile` as the one exclusion and why, rather than adding a reason
underneath a claim that now contradicts it.

**B. Put the annotation artifacts on the compile classpath** (`com.google.guava:guava` and
`com.google.errorprone:error_prone_annotations`, `provided` scope, on `graphitron` only). Verified:
this clears all nine warnings and `graphitron` main compiles clean under the untouched
`-Xlint:all -Werror`. It preserves the `classfile` category. The cost is dragging a multi-megabyte
library onto a module's compile classpath purely so javac can resolve annotation methods it will
then discard, plus the standing risk that Guava becomes casually reachable in `graphitron` source.

**Decision: A**, recommended by the draft and ratified at the Spec gate, on the reasoning that the
excluded category cannot detect the class of defect the ratchet exists to catch, and that a recorded
one-line exclusion is a smaller standing liability than a dependency added as a shim for another
project's shading artifact. Implement A; B needs no further consideration.

A third shape was weighed at the gate and rejected: scoping the exclusion to the `graphitron` module
rather than the root pom, which `graphitron/pom.xml` could carry since it already declares a
maven-compiler-plugin `<configuration>` block. That would erase A's only stated cost, since the spec
establishes that no other module is affected. It was rejected because Maven replaces a child
`<compilerArgs>` list rather than merging it, so the module would have to restate `-Xlint` and
`-Werror` in a second place, and the ratchet's arguments would then drift apart silently the next
time the root block changes. One source of truth for the ratchet is worth more than the narrower
blast radius of an exclusion that, by the argument above, gives up no signal the ratchet can act on.

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

One narrow consumer exposure the dry run could not reach, recorded at the gate as a known
consequence rather than as work for this item. The `@scalarType(scalar: "FQN.FIELD")` path emits the
consumer's constant by reference, and `ScalarTypeResolver` gates it on the reflected *value* being a
`GraphQLScalarType`, never on the field's *declared* type. A consumer whose constant is declared
`public static final GraphQLType MONEY = ...` therefore passes codegen and emits
`additionalType(ScalarConstants.MONEY)`, which compiled under 25.0 and does not under 26.0. Every
graphitron fixture declares the constant as `GraphQLScalarType`, which is why nothing in the reactor
sees it. The declaration is unusual and the break is graphql-java's own, so it does not change this
item's scope; tightening the gate to the declared type, so the invariant has an enforcer at the
parse boundary instead of in a consumer's javac, belongs in a separate Backlog item.

### 6. Not affected

JDK floor is unchanged: 25.0 and 26.0 both publish class-file major version 55, so neither the
runtime floor nor the generator's Java 25 build moves, and generated output stays Java 17. No test
expectations change anywhere in the reactor. The `graphql-java` version the consumer-lag nudge
reports is resolved off graphitron's own plugin realm rather than hardcoded, so the `25.0` strings in
`DependencyVersionWarningsTest` and `DependencyVersionDecodeTest` are fixture inputs and stay as
they are; consumers still on 25.0 start seeing `graphql-java-version-lag` after this ships, which is
the mechanism working as documented in `docs/dependencies.adoc`.

## Implementation note

A `NoSuchMethodError` cascade on `additionalType(GraphQLType)` across the execution tier is the
signature of stale bytecode, not a real break: a non-`clean` build after the version bump leaves
`graphitron-sakila-example` class files compiled against 25.0, because regeneration alone does not
force recompilation. Use `mvn clean install -Plocal-db` when verifying this item; a warm incremental
build will produce dozens of misleading failures.

## Review record

One Spec-review pass, by a session independent of the drafting one. No blocking defect; the item
goes to Ready.

Every external claim was re-verified against the published artifacts rather than taken on report:
`graphql.schema.idl.DirectiveInfo` is absent from the 26.0 jar; `graphql.Directives` publishes
`BUILT_IN_DIRECTIVES`, `BUILT_IN_DIRECTIVES_MAP` and both `isBuiltInDirective` overloads, and its
membership printed from a live 26.0 classpath is exactly the seven names the body lists, identical to
25.0's `GRAPHQL_SPECIFICATION_DIRECTIVE_MAP` printed the same way; `additionalType`,
`additionalTypes` and `getAdditionalTypes` carry the narrowed signatures; the relocated
`ImmutableList` is byte-identical between the two jars and its retained annotations do dangle on
`GwtCompatible` and the errorprone package, neither of which the jar bundles; federation 7.0.0
declares `graphql-java:26.0` at `compile` and still ships every one of the eight federation classes
graphitron references, `ServiceSDLPrinter.generateServiceSDLV2` included; and 24.0 is the newest
semantic release of `graphql-java-extended-scalars`, as is 26.0 of `graphql-java`. In the tree, the
`DirectiveInfo` import and call in `SchemaSdlEmitter.includeSchemaElement`, the `{@code}` mention in
`OneOfDirectiveSdl`'s javadoc, the `pinned` map and its existing downcast in `ConnectionPromoter`,
the ratchet comment block, and the emitted-surface symbols in
`GraphitronConnectionInstrumentationGenerator` and `AppliedDirectiveEmitter` all exist as named.

The gate closed the item's one open decision in favour of remedy A and recorded the rejected
module-scoped variant alongside it, settled the `getAdditionalTypes()` question the draft left open,
and added the `@scalarType` consumer exposure and the comment-block rewrite requirement. The
federation bump is a major version and the body reasons about graphql-java only; that rests on the
green dry run rather than on prose, and the guards that make it safe are already in place:
`SchemaSdlEmitterTest` asserts byte-identity against `ServiceSDLPrinter.generateServiceSDLV2` and
`FederationBuildSmokeTest` pins the runtime surface at the execution tier.
