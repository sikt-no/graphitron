---
id: R572
title: "Warn at build time when consumer graphql-java / jOOQ versions lag"
status: Spec
bucket: feature
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Warn at build time when consumer graphql-java / jOOQ versions lag

Graphitron generates code against two foundational runtime dependencies, graphql-java and jOOQ
(`docs/dependencies.adoc`), but tells a consumer nothing about which versions of them it expects.
Subgraphs consequently drift: a project pins graphql-java 22 or jOOQ 3.19, generation succeeds, and
the mismatch only surfaces later as a `NoSuchMethodError` or a subtly wrong runtime behaviour with
no thread back to the real cause. Graphitron already knows the answer at the moment of generation
and stays silent about it. This item makes the generator say so.

## Scope

Build-time only. The `Artifact` decode lands in `graphitron-maven-plugin`; the comparison and the
advisory land in `graphitron` core, riding the existing `BuildWarning` channel (see "Placement" and
"Routing" below). A runtime startup check emitted into the generated facade is deliberately out of
scope; see "Runtime alternative, and why not".

## What the check does

Read the consumer's resolved `com.graphql-java:graphql-java` and `org.jooq:jooq` versions off
`MavenProject`, compare each against graphitron's declared floors, and surface the lag as a
suppressible build advisory.

`project.getArtifacts()` gives post-mediation group/artifact/version as structured data, which is
strictly better than any classpath introspection: no reflection, no manifest parsing, and it reports
the version Maven actually resolved after mediation rather than whatever the POM happens to declare.
All three mojos already declare `requiresDependencyResolution` (`GenerateMojo.java:17`,
`ValidateMojo.java:19`, `DevMojo.java:88`), so the artifact set is populated at execute time. The
Spec must pin which scopes count, and what an absent coordinate means (a consumer with jOOQ at
`provided` or test scope is a real shape); both are arms of the sealed verdict below, not
afterthoughts. An absent coordinate is not a lagging one.

## Two floors on two axes: tested-against and minimum-supported

"The version graphitron was built against" and "the oldest version graphitron supports" are
orthogonal facts, and splicing them into one number is the central design trap here.

*Tested-against* is **derived**: a materialized view of `version.org.jooq` and a
promoted-to-property graphql-java version, regenerated every build, never stale. Promoting
graphql-java's hardcoded `25.0` (`pom.xml:161`) to a property is therefore a prerequisite of this
item, not a nicety; without it there is no single source to filter from.

*Minimum-supported* is a **base fact nothing else carries**: it encodes which API surface the
emitted code actually uses, which is not mechanically derivable from anything. R467 names those
surfaces for graphql-java exactly (the `Instrumentation` SPI, `DataFetchingEnvironmentImpl`,
`ValuesResolver.valueToLiteral`).

Using tested-against as the floor does not merely nag every consumer one patch behind. It makes the
floor **move as a side effect of a version bump rather than as a decision**: R466 or R467 landing
would silently, retroactively declare every consumer on the older line unsupported. Both numbers
therefore live as adjacent root-pom properties (`version.org.jooq` / `version.org.jooq.minimum`, and
the graphql-java pair), so a bump commit physically touches the line carrying the floor and the
reviewer sees the unchanged floor in the same diff.

The implementer picks the initial floor values and justifies them in the PR. If the oldest
exercised version is unknown, set the floor at the current tested-against version and say so,
accepting the nag until someone does the compatibility work to lower it.

For R466 and R467 the rule is: whoever bumps decides, and the floor moves only when the *emitted*
surface forces it, with a one-line rationale naming the API. That obligation belongs in those items'
plan bodies, not in a code comment.

### The floor relation needs an enforcer

A hand-maintained floor is an invariant, and an invariant exists only while something fails when it
breaks. Nothing currently fails if a bump leaves the floor untouched when it should have moved, and
nothing fails if someone sets a floor *above* tested-against, an impossible state that would make
the plugin reject builds it is itself built for. Add a unit-tier test asserting
`minimum <= testedAgainst` per dependency.

## Severity: suppressible advisory between the floors

Between `minimum` and `testedAgainst`, the consumer is on a supported but not-current version: a
`BuildWarning`, suppressible by rule id, never fatal. The message carries the observed version, the
floor, and the coordinate to bump, so it is actionable in one read.

**No new mojo parameter for suppression.** `<lint><disabledRules>` already owns the "do I want to
hear about it" axis, and it applies to this channel for free (see below). A dedicated
`<dependencyVersionCheck>` knob would be a second configuration axis for a concern already served,
and would pre-empt the deferred per-rule-severity follow-on that `LintRule.java:11-12` explicitly
scopes out of v1 with a one-off flag on one advisory.

Whether falling *below* `minimum` should hard-fail rather than warn is the item's one genuinely open
design question; see the open questions below.

## Routing: a `Source.CODEGEN` lint advisory, not a bare `getLog().warn`

An earlier draft of this Spec argued that `BuildWarning`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildWarning.java`) was the wrong home because
every arm requires a `SourceLocation` and a dependency advisory has no schema coordinate. That is
wrong, and the counter-example is graphitron's own code.

`SessionStateWarnings`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/session/SessionStateWarnings.java`) is this
exact shape already built once: a whole-build advisory derived purely from mojo POM config, emitted
as `BuildWarning.LintFinding` with a **`null` location** and a `LintRule` tagged `Source.CODEGEN`,
folded into the report at `GraphQLRewriteGenerator.withLintFindings` (`GraphQLRewriteGenerator.java:515-538`).
Its class javadoc states the rationale verbatim: a `<sessionState>` posture is a "`pom.xml` /
whole-build fact with no SDL coordinate." `logWarnings` branches on `loc != null` explicitly, so the
location-less case is already carried end to end.

Routing through `getLog().warn` instead would open a second diagnostic channel with no rule id, no
`<lint><disabledRules>` suppression, no LSP replay, and no MCP `diagnostics` projection: three views
the `BuildWarning` channel serves for free. The one counter-precedent, the `<schemaInput pattern>`
empty-match warn at `AbstractRewriteMojo.java:167`, fires before a `RewriteContext` exists and
reports a defect in the mojo's own input expansion, so it is not a model for this.

Concretely: two new `LintRule` values with `Source.CODEGEN`, and a
`DependencyVersionWarnings.forVersions(...)` in core mirroring `SessionStateWarnings.forConfig`,
folded into `withLintFindings`. `LintRuleRegistryCoverageTest` then becomes the enforcer that the new
rules are a deliberate registry edit rather than an orphan.

### Cadence

Riding `withLintFindings` means the advisory re-fires on every `graphitron:dev` regeneration. That is
the same cadence `no-session-state` already has, so it is precedent-consistent rather than new noise.
Stating it here so it is a decision rather than a discovery.

## Placement: decision in core, decode at the mojo

`graphitron`'s pom carries no Maven API dependency, and `org.apache.maven.artifact.Artifact` /
`MavenProject` must not cross into core: they are the external untyped input at this boundary, the
Maven twin of `Table<?>` and `java.lang.reflect.Type`. Boundaries decode; the interior is typed.

So: the mojo decodes `project.getArtifacts()` into a small typed carrier and threads it through
`RewriteContext`, the same route `SessionStateConfig` and `LintConfig` already take. The comparison
itself is a pure, unit-testable decision function in core.

Putting the fact on `RewriteContext` resolves the "`AbstractRewriteMojo` or `GenerateMojo`" question
by construction: the advisory lands on `generate`, `validate`, **and** `dev` with no per-mojo
duplication. That is independently the right answer, because `mvn graphitron:validate` is the goal a
consumer runs to ask "is my setup sound"; an advisory that `generate` raises but `validate` misses
would be exactly the enforcement gap the validator-mirrors-classifier rule exists to close.

### The comparison result is a sealed verdict, not a boolean

With two floors the outcome is at least five-way, and the arms carry different data: at-or-above
(nothing), below-tested (observed plus tested), below-minimum (observed plus minimum, and the arm
that would fail the build if we take that option), absent from the resolved graph, and unparseable.
A boolean or a nullable "lagging version" field forces the caller to re-derive which situation it is,
and the warn-versus-fail fork becomes an `if`-chain rather than a compile-checked exhaustive switch.
Model it as a sealed `DependencyVersionVerdict` so adding an arm later is a compile error.

If the below-minimum arm does become a hard failure, it must **not** be a `ValidationError`: that
record is bound to a `Rejection` and a schema coordinate (`ValidationError.java:29`), which a pom
fact does not have. The established path for a pom defect is the core decision throwing and the mojo
wrapping it as `MojoExecutionException`, exactly as `SessionStateConfig.from(...)` and
`AbstractRewriteMojo.buildSessionStateConfig` (`AbstractRewriteMojo.java:206-230`) already do.

### Which side does the comparing

The decision-in-core rule and the version-comparison library pull against each other, and the Spec
resolves it rather than leaving it to be discovered. Version comparison must handle the real shapes
these coordinates take (`25.0`, `3.20.11`, `3.21.6`, and qualifiers such as `-SNAPSHOT`), and it must
not be a hand-rolled `String.compareTo`: a lexical compare gets `3.9` versus `3.20` backwards, which
is exactly the jOOQ line we care about. The right tool is Maven's own
`org.apache.maven.artifact.versioning.ComparableVersion`, available via `maven-artifact`, a
transitive of the `provided` `maven-core` dependency (`graphitron-maven-plugin/pom.xml:50-54`). But
that is a Maven type, and core cannot see it.

So the split is: **the mojo parses and compares, producing the sealed verdict; core turns verdicts
into advisories.** `ComparableVersion` stays on the Maven side of the boundary where it belongs, the
verdict that crosses into core carries only plain strings, and `DependencyVersionWarnings` in core
maps verdict arms to `BuildWarning.LintFinding`s with the rule tagging and message text. Both halves
stay unit-testable, and no Maven type reaches core. The alternative, adding `maven-artifact` to
`graphitron`'s pom, is exactly the boundary violation the placement rule exists to prevent.

`ComparableVersion` is total and never throws on odd input, so "unparseable" is not really an arm it
produces; the implementer should confirm what shapes actually reach it and drop that arm if it is
vacuous rather than carry a case that cannot occur.

## Runtime alternative, and why not

A startup check emitted into the generated `Graphitron` facade was considered and rejected for this
item. The facade already emits an SLF4J `LOGGER` and a one-shot `AtomicBoolean` warn-once
(`GraphitronFacadeGenerator.java:47-48,125-157`), so the hook exists, but runtime version detection
for these two libraries is genuinely hostile. Probed empirically:

| Probe | Result |
|---|---|
| `graphql.GraphQL.class.getPackage().getImplementationVersion()` | `null` |
| `org.jooq.DSLContext.class.getPackage().getImplementationVersion()` | `null` |
| `org.jooq.Constants.VERSION`, read reflectively | works |
| graphql-java version API | none exists |
| OSGi `Bundle-Version` in each jar's `MANIFEST.MF` | works, fragile |

Two specific traps. jOOQ's `Constants.VERSION` carries a `ConstantValue` attribute, so javac inlines
it; generated code referencing it directly would compare the build-time string against itself and
silently always pass, and it must be read reflectively to see the deployed jar. graphql-java exposes
no version accessor at all, and its shaded jar omits even its own
`META-INF/maven/.../pom.properties`, leaving only a classloader-wide `MANIFEST.MF` scan matched on
`Bundle-SymbolicName`, which breaks under the uber-jar packaging several consumers use.

The runtime check does cover one case the build-time check cannot: classpath drift where a platform
BOM downgrades a dependency at assembly time relative to what generation saw. That is a real gap and
a legitimate follow-up item, but it is a strictly smaller and much more expensive win, and it should
be filed separately rather than bundled here. If it is pursued, capability probing (`Class.forName`
or `getMethod` on the specific symbol the generated code needs) is likely a better predicate than a
version string, because it tests what actually breaks and survives shading.

## Testing

There is no pipeline-tier surface here: nothing about classification or emitted code changes. The
honest placement is two unit tiers, and the plan says so explicitly rather than inventing a pipeline
test to satisfy the "behaviour is pinned at the pipeline tier and above" rubric.

- Core unit tier, precedent `SessionStateWarningsTest`: the pure decision function over a table of
  observed-versus-floor pairs, covering every sealed verdict arm including qualifiers
  (`-SNAPSHOT`), the absent coordinate, and the `3.9` versus `3.20` ordering trap.
- Core unit tier: the `minimum <= testedAgainst` guard described above.
- Plugin-module unit tier: the `Artifact` decode over hand-built `MavenProject`s. Precedent for
  making a mojo internal package-private for exactly this purpose is
  `AbstractRewriteMojo.collectExistingDirs` (`AbstractRewriteMojo.java:594-602`), whose javadoc says
  so in as many words.

The existing invoker harness (`graphitron-maven-plugin/pom.xml:93-125`) is optional here and the
implementer should decide whether it earns its wall-clock. If used, the valuable case is the
negative one: an assertion in `basic-generate`'s `verify.groovy` that an up-to-date consumer emits
no such line. A diagnostic that fires when it should not is the failure mode that erodes trust in
every other warning graphitron emits.

## Documentation

`docs/dependencies.adoc` describes both dependencies without stating any version policy, and is
where a reader would look. The trap is that hand-typing a version table there makes a third copy of
the same numbers alongside the pom properties, with only one of the three build-checked.

Two acceptable resolutions, and the Spec does not force one: either generate the table from the same
properties into `docs/manual/_generated/` and `--verify`-gate it the way `DirectiveSupportReport`
gates `supported-directives.adoc`, or leave the page pointing at the build-time advisory as the
authoritative surface and state that the numbers deliberately live in one place. What is not
acceptable is a hand-maintained third copy added silently.

## Open questions for the reviewer

1. **Should below-`minimum` hard-fail the build, or only warn?** The user's original ask was a
   warning, and this Spec's default is warn-everywhere. The argument for failing: a version below
   the declared floor is outside what graphitron supports at all, and generating code that will fail
   at runtime with a `NoSuchMethodError` is worse service than refusing. The argument against: the
   floor marks *unknown*, not *known-broken*, and a hard failure makes graphitron upgrades
   unshippable for consumers on a slower cadence. The sealed verdict makes this a one-arm change
   either way, so it can be deferred, but the reviewer should rule on it rather than let it default.
2. Initial floor values. Deliberately left to implementer plus reviewer rather than invented here;
   is that the right call, or should the Spec pin them?
3. Scope of coordinates checked. This item names graphql-java and jOOQ only. The reactor also pins
   `federation-graphql-java-support` and `graphql-java-extended-scalars` (`pom.xml:163-172`), which
   federation consumers will have. Include now, or follow up?
