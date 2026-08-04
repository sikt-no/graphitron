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
Subgraphs consequently drift onto old versions and stay there: nothing in the build ever mentions it,
so there is no moment at which anyone notices. Graphitron knows both numbers at generation time and
says nothing.

**This is a usability nudge, not a compatibility gate.** The correctness bar is the compiler: if the
generated code compiles against the consumer's versions, the consumer is fine. The harm being
addressed is not breakage, it is silent drift, consumers missing upstream fixes and improvements and
quietly diverging from the versions graphitron is exercised against, which makes every later upgrade
larger than it needed to be. The whole value of this item is that it makes staying current the
default by mentioning it once per build, and nothing more.

That framing is load-bearing for the rest of the plan. It removes any hard-failure arm, removes the
need for a separately maintained support floor, and makes noise control (rather than accuracy) the
main design risk.

## Scope

Build-time only, advisory only. The `Artifact` decode lands in `graphitron-maven-plugin`; the
comparison and the advisory land in `graphitron` core, riding the existing `BuildWarning` channel
(see "Placement" and "Routing" below). Nothing this item adds can fail a build.

A runtime startup check emitted into the generated facade is out of scope, and under the
compile-is-the-bar framing it is not merely deferred but actively pointless; see "Runtime
alternative, and why not".

## What the check does

Read the consumer's resolved `com.graphql-java:graphql-java` and `org.jooq:jooq` versions off
`MavenProject`, compare each against the version graphitron itself builds against, and surface a
lagging one as a suppressible build advisory.

`project.getArtifacts()` gives post-mediation group/artifact/version as structured data, which is
strictly better than any classpath introspection: no reflection, no manifest parsing, and it reports
the version Maven actually resolved after mediation rather than whatever the POM happens to declare.
All three mojos already declare `requiresDependencyResolution` (`GenerateMojo.java:17`,
`ValidateMojo.java:19`, `DevMojo.java:88`), so the artifact set is populated at execute time. The
Spec must pin which scopes count, and what an absent coordinate means (a consumer with jOOQ at
`provided` or test scope is a real shape). An absent coordinate is not a lagging one, so it is one of
the silence cases the tests pin explicitly.

## One reference version: what graphitron is built against

There is a single number per dependency, and it is derived, not maintained: the version the reactor
itself builds against (`version.org.jooq` at `pom.xml:33`, and graphql-java's `25.0` at
`pom.xml:161`). Promoting the graphql-java version from a hardcoded literal to a property is a
prerequisite of this item, so there is one source to filter the reference value from.

Derived is exactly right here, and this is where the nudge framing overrides an instinct worth naming
explicitly so a later reader does not "fix" it. A separately maintained *minimum supported version*
would be the right design for a compatibility gate: it encodes which API surface the emitted code
actually needs, it is not mechanically derivable, and it must not move as a side effect of an upgrade.
But this item is not a gate. Nothing fails, so nothing hangs on the number being a promise. Adding a
second hand-maintained floor would buy a support commitment nobody asked for, plus an invariant
needing its own enforcer, in exchange for nothing the nudge requires.

The consequence is deliberate and desirable: when R466 (jOOQ 3.21) or R467 (graphql-java 26) lands,
the reference version moves automatically and consumers start being nudged toward the new line with
no further work. That is the feature, not a trap. Neither upgrade item takes on any obligation here.

### Noise control is the real design risk

Because the reference is "current", the advisory fires for everyone who is behind, on every build.
That is the point, but it is also the one way this item can fail: a line that fires for everyone
forever gets filtered out, and it discredits the other warnings sharing the channel.

Two mitigations, and the Spec recommends both:

1. **Nudge on the lines that matter, not on patch drift.** Recommended predicate: warn when the
   consumer's *minor* line is behind (jOOQ `3.19` versus `3.20`, graphql-java `22` versus `25`), and
   stay silent on patch-level lag within the current minor (`3.20.9` versus `3.20.11`). A consumer one
   patch behind is materially current and telling them so every build is pure noise, whereas a minor
   line behind is the drift actually worth a nudge. This matters more for jOOQ than graphql-java,
   since jOOQ's minor bump is its effective major boundary (R466 makes that point).
2. **Suppression already exists**, and routing through the lint channel is what buys it; see below.

The message names the observed version, the current version, and the coordinate to bump, so it is
actionable in one read and needs no follow-up digging. Keep it to one line per lagging dependency.

## Severity: advisory, always

A `BuildWarning`, suppressible by rule id, never fatal, at any distance behind. The compiler is the
correctness bar; graphitron has no business failing a build over a version number when the code it
generated compiles fine.

**No new mojo parameter for suppression.** `<lint><disabledRules>` already owns the "do I want to
hear about it" axis, and it applies to this channel for free. A dedicated `<dependencyVersionCheck>`
knob would be a second configuration axis for a concern already served, and would pre-empt the
deferred per-rule-severity follow-on that `LintRule.java:11-12` explicitly scopes out of v1 with a
one-off flag on one advisory. For a warning that fires by design rather than on a defect, having the
established suppression path is not a nicety; it is what makes the nudge tolerable in a build a
consumer runs fifty times a day.

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

Riding `withLintFindings` means the advisory re-fires on every `graphitron:dev` regeneration, which is
the same cadence `no-session-state` already has. Precedent-consistent, so the Spec accepts it, but
worth flagging as the one place the nudge framing and the routing choice pull against each other: an
always-on advisory re-emitted on every save in a watch loop is more repetition than a config-defect
advisory that most consumers never see. If it grates in practice, the fix is the dev loop suppressing
unchanged codegen-config advisories across regenerations, which would improve `no-session-state`
identically and belongs in a dev-loop item rather than here. Do not special-case this one rule.

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

### Keep the result type as small as the decision

An earlier draft of this Spec specified a five-arm sealed `DependencyVersionVerdict`. With the
failure arm and the second floor both gone, that is over-modelling: the decision is now "say
something, or say nothing", and the only data a nudge carries is the observed version and the current
one.

The honest shape is a single `Optional<BuildWarning>` (or an empty list) per dependency, with the
silent cases (at current, patch-only lag, coordinate absent from the resolved graph) all collapsing
to empty. No sealed hierarchy earns its place over a two-outcome decision; the sealed-variant
discipline is for information that downstream code must switch over exhaustively, and there is no
such switch here. If a later item does add a failure arm, that is the moment to introduce the sealed
type, and the change would be small.

Worth recording for whoever contemplates that later item: a hard failure must **not** be a
`ValidationError`, because that record is bound to a `Rejection` and a schema coordinate
(`ValidationError.java:29`) and a pom fact has neither. The established path is the core decision
throwing and the mojo wrapping it as `MojoExecutionException`, as `SessionStateConfig.from(...)` and
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

So the split is: **the mojo parses and compares; core turns the outcome into an advisory.**
`ComparableVersion` stays on the Maven side of the boundary where it belongs, what crosses into core
carries only plain strings, and `DependencyVersionWarnings` in core owns the rule tagging and the
message text. Both halves stay unit-testable and no Maven type reaches core. The alternative, adding
`maven-artifact` to `graphitron`'s pom, is exactly the boundary violation the placement rule exists to
prevent.

Note that the recommended minor-line predicate needs the version *decomposed*, not just ordered, so
the mojo side compares minor lines rather than doing a bare `ComparableVersion` ordering. That is
still comfortably inside "the boundary decodes"; it just means the decode extracts a
`(major, minor)` pair alongside the raw string.

`ComparableVersion` is total and never throws on odd input, so there is no unparseable case to model;
a version string that reaches it always yields an ordering.

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

An earlier draft of this Spec kept the runtime check alive as a legitimate follow-up, on the grounds
that it covers one case build-time cannot: classpath drift where a platform BOM downgrades a
dependency at assembly time relative to what generation saw. Under the nudge framing that
justification does not survive. A nudge belongs where someone can act on it, which is the build, in
front of the developer who edits the pom. Emitting it at application startup would put a "you could
be more current" line in production logs, aimed at an operator who cannot fix it, on a code path
where graphitron has no business spending cycles. And since the compiler is the correctness bar, the
assembly-time-drift case is a genuine-breakage concern, not a currency concern; if it is ever worth
addressing it is a different item with a different rationale, not a variant of this one.

Recording the probe results anyway, because they are what makes the runtime route expensive and
someone will otherwise re-derive them: if that item is ever filed, capability probing (`Class.forName`
or `getMethod` on the specific symbol the generated code needs) is a better predicate than a version
string, because it tests what actually breaks and survives shading.

## Testing

There is no pipeline-tier surface here: nothing about classification or emitted code changes. The
honest placement is two unit tiers, and the plan says so explicitly rather than inventing a pipeline
test to satisfy the "behaviour is pinned at the pipeline tier and above" rubric.

- Core unit tier, precedent `SessionStateWarningsTest`: the message-shaping decision, asserting the
  advisory names the observed version, the current version, and the coordinate.
- Plugin-module unit tier: the decode plus the comparison, which is where the real behaviour lives.
  Table-driven over observed-versus-current pairs and covering the silence cases explicitly, since
  under this design silence is the interesting output: at current, patch-only lag within the current
  minor, and the coordinate absent from the resolved graph. Include the `3.9` versus `3.20` ordering
  trap and a `-SNAPSHOT` qualifier. Precedent for making a mojo internal package-private for exactly
  this purpose is `AbstractRewriteMojo.collectExistingDirs` (`AbstractRewriteMojo.java:594-602`),
  whose javadoc says so in as many words.

The existing invoker harness (`graphitron-maven-plugin/pom.xml:93-125`) is optional and the
implementer decides whether it earns its wall-clock. If used, the valuable case is the negative one:
an assertion in `basic-generate`'s `verify.groovy` that an up-to-date consumer emits no such line. For
an advisory that fires by design, a false positive is the whole failure mode, and it erodes trust in
every other warning sharing the channel.

## Documentation

`docs/dependencies.adoc` describes both dependencies without mentioning versions at all, which is
mostly fine now: with no support floor there is no compatibility promise to publish, and the advisory
itself reports the current numbers at the moment a consumer needs them.

What the page should gain is one short paragraph of *policy*, not numbers: graphitron tracks current
graphql-java and jOOQ, staying current is recommended, and the build will say so when you are a minor
line behind. Naming the mechanism sets the expectation before a consumer meets the warning and
wonders whether it means something is broken. Deliberately no version table: hand-typing the numbers
there would make a third copy alongside the pom properties with only one of the three build-checked,
and generating and `--verify`-gating a table the way `DirectiveSupportReport` gates
`supported-directives.adoc` is more machinery than two numbers justify when the warning already
carries them.

## Settled by the requester

Recorded so the reviewer does not reopen them: this is a usability nudge, the compiler is the
correctness bar, nothing here fails a build, and there is no separately maintained minimum supported
version. An earlier draft of this plan carried a two-floor model with a hard-failure arm below the
lower floor; that is retired, along with the floor-relation enforcer test it needed.

## Open questions for the reviewer

1. **The minor-line predicate.** The Spec recommends nudging on minor-line lag and staying silent on
   patch drift, which is a judgement call about where "materially current" sits rather than a derived
   fact. A reviewer who thinks any lag deserves the nudge would simplify the comparison to a plain
   ordering; a reviewer who thinks jOOQ and graphql-java deserve different thresholds would make it
   per-dependency. Worth a ruling because it is the main determinant of how often the line fires.
2. Scope of coordinates checked. This item names graphql-java and jOOQ only, the two the docs call
   foundational. The reactor also pins `federation-graphql-java-support` and
   `graphql-java-extended-scalars` (`pom.xml:163-172`), which federation consumers will have. Include
   now, or follow up? Leaning follow-up: two lines is a nudge, four is a report.
3. Whether the `docs/dependencies.adoc` policy paragraph should ship in this item or separately. It is
   small enough to bundle, but it is also the only part of this item a consumer reads before hitting
   the warning.
