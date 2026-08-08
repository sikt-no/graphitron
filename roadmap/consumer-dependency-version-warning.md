---
id: R572
title: "Warn at build time when consumer graphql-java / jOOQ versions lag"
status: In Review
bucket: feature
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-03
last-updated: 2026-08-08
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
`ValidateMojo.java:19`, `DevMojo.java:88`), so the artifact set is populated at execute time.

**Which scopes count: an allow-list of `compile`, `provided`, and `system`.** Not `runtime`, not
`test`. This has to be a ruling rather than an implementer's choice, because the resolution scope
differs per goal: `generate` and `validate` declare `ResolutionScope.COMPILE`, `dev` declares
`ResolutionScope.TEST`, and Maven expands those to different scope sets. `MojoExecutor.toScopes` maps
`COMPILE` to `{compile, system, provided}` and `TEST` to `{compile, system, provided, runtime, test}`,
so `project.getArtifacts()` is larger under `dev` by *two* scopes, not one.

That is why the ruling is an allow-list and not a deny-list of `test`. Admitting exactly the three
scopes `COMPILE` resolves makes the observed set identical across all three goals, which is what the
placement section's uniformity argument needs. Excluding `test` alone would leave `runtime` leaking
through, so a consumer whose jOOQ is runtime-scoped would be nudged under `graphitron:dev` and silent
under `graphitron:generate`: the same project saying two different things depending on which goal ran,
which is precisely the outcome the ruling exists to prevent.

The allow-list is also right on its own terms. Generated code refers to `org.jooq` and `graphql.schema`
types directly, so a consumer who compiles graphitron's output necessarily carries both at `compile` or
`provided`; a coordinate visible only at `runtime` or `test` is not one the generated sources are built
against. A consumer carrying jOOQ at `provided` is a real shape and a real nudge target.

An absent coordinate is not a lagging one, so it is one of the silence cases the tests pin explicitly.

## One reference version: what graphitron is built against

There is a single number per dependency, and it is derived, not maintained: the version graphitron
itself builds against, pinned once in the root pom (`version.org.jooq` at `pom.xml:34`, graphql-java's
`25.0` at `pom.xml:174`).

**How that number reaches the running mojo: the plugin's own resolved dependency graph.** Those pom
pins are facts of *graphitron's* build and are invisible at consumer build time, and the reactor has no
resource-filtering precedent to copy (there is no `<filtering>` anywhere in it), so the delivery has to
come from something the shipped artifact already carries. It does: a mojo can take
`@Parameter(defaultValue = "${plugin}", readonly = true) PluginDescriptor`, and
`PluginDescriptor.getArtifacts()` carries graphql-java and jOOQ because `graphitron-maven-plugin`
depends on `graphitron`, whose pom declares both at compile scope (`graphitron/pom.xml:36,44`). That is
exactly symmetric with the `project.getArtifacts()` decode chosen for the consumer side: same API
shape, same boundary, plain strings by the time either crosses into core. It needs no property
promotion, no resource filtering, and no new build wiring, and the number stays derived in the way this
section wants, so R466 and R467 still move the reference for free.

The one way the reference can be something other than graphitron's own pin is a consumer overriding
`<plugin><dependencies>` to force a different graphql-java or jOOQ onto the plugin's realm. That is
rare, deliberate, and self-consistent (the advisory then reports the version the plugin actually ran
with), so it needs no handling beyond this sentence.

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
folded into the report at `GraphQLRewriteGenerator.withLintFindings` (`GraphQLRewriteGenerator.java:538-561`).
Its class javadoc states the rationale verbatim: a `<sessionState>` posture is a "`pom.xml` /
whole-build fact with no SDL coordinate." `logWarnings` branches on `loc != null` explicitly, so the
location-less case is already carried end to end.

Routing through `getLog().warn` instead would open a second diagnostic channel with no rule id, no
`<lint><disabledRules>` suppression, no LSP replay, and no MCP `diagnostics` projection: three views
the `BuildWarning` channel serves for free. The one counter-precedent, the `<schemaInput pattern>`
empty-match warn at `AbstractRewriteMojo.java:163`, fires before a `RewriteContext` exists and
reports a defect in the mojo's own input expansion, so it is not a model for this.

Concretely: two new `LintRule` values with `Source.CODEGEN`, and a
`DependencyVersionWarnings.forVersions(...)` in core mirroring `SessionStateWarnings.forConfig`,
folded into `withLintFindings`. `LintRuleRegistryCoverageTest.everyCodegenAdvisoryRuleExists` hard-lists
the `Source.CODEGEN` rule ids, so it is the enforcer that the new rules are a deliberate registry edit
rather than an orphan; the implementer extends that list.

### Widen the `Source.CODEGEN` documentation with the change

The axis is currently documented as a `<sessionState>` axis and would go stale the moment this lands.
`LintRule.Source.CODEGEN`'s javadoc reads "a codegen-config advisory derived at report assembly from the
`<sessionState>` config"; the enum-constant block comment above `NO_SESSION_STATE` says the same; and
two tests repeat it in comments (`LintRuleRegistryCoverageTest.everyCodegenAdvisoryRuleExists` and
`FixtureWarningsGateTest`, which segregates `Source.CODEGEN` findings out of its expected-warning set
and so absorbs the new rules silently). A dependency version is not `<sessionState>`, and it is not mojo
config at all: it is resolved-dependency-graph data. Widen all four sites to what the axis actually
partitions, a whole-build fact with no SDL coordinate, folded in at report assembly, no visitor and no
classifier site.

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

So: the mojo decodes both artifact sets into a small typed carrier of plain strings and threads it
through `RewriteContext`, the same route `SessionStateConfig` and `LintConfig` already take. Concretely
a `withDependencyVersions(...)` wither, the way `tenantColumn` was added (`withTenantColumn`), so the
canonical constructor's five convenience overloads stay untouched and the pipeline-tier test can set the
fact in one line. The comparison itself is a pure, unit-testable decision function in core; the next
subsection is why that is affordable.

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
silent cases (at current, patch-only lag, coordinate absent from the resolved graph, version string
that does not decompose) all collapsing to empty. No sealed hierarchy earns its place over a two-outcome decision; the sealed-variant
discipline is for information that downstream code must switch over exhaustively, and there is no
such switch here. If a later item does add a failure arm, that is the moment to introduce the sealed
type, and the change would be small.

Worth recording for whoever contemplates that later item: a hard failure must **not** be a
`ValidationError`, because that record is bound to a `Rejection` and a schema coordinate
(`ValidationError.java:29`) and a pom fact has neither. The established path is the core decision
throwing and the mojo wrapping it as `MojoExecutionException`, as `SessionStateConfig.from(...)` and
`AbstractRewriteMojo.buildSessionStateConfig` (`AbstractRewriteMojo.java:207-225`) already do.

### Which side does the comparing

Either side could plausibly own it, and the Spec resolves it rather than leaving it to be discovered.
Version comparison must handle the real shapes these coordinates take (`25.0`, `3.20.11`, `3.21.6`, and
qualifiers such as `-SNAPSHOT`), and it must not be a hand-rolled `String.compareTo`: a lexical compare
gets `3.9` versus `3.20` backwards, which is exactly the jOOQ line we care about.

**Core compares; the mojo only decodes.** Under the settled minor-line predicate the comparison is not
an ordering over full version strings at all. It is an integer compare of the `(major, minor)` pair, and
`3.9` versus `3.20` comes out right precisely because `9 < 20` as integers. That is plain-string work
with no Maven type in it, so it sits in core behind the boundary exactly where the placement rule wants
the decision, and the mojo is left with the one job a boundary has: turning `Artifact` objects into
`(coordinate, version-string)` pairs.

That is why the item does *not* pull `org.apache.maven.artifact.versioning.ComparableVersion` across.
It is available (via `maven-artifact`, a transitive of the `provided` `maven-core` dependency,
`graphitron-maven-plugin/pom.xml:50-54`) and it is the right tool for a full ordering, but the adopted
predicate needs the version *decomposed*, not ordered, and `ComparableVersion` orders without
decomposing. Reaching for it would mean splitting the decision across the boundary, the mojo comparing
and core only shaping the message, to buy an ordering the predicate never asks for. Adding
`maven-artifact` to `graphitron`'s own pom instead is the boundary violation the placement rule exists
to prevent, and is not on the table either.

The cost of declining `ComparableVersion` is that its totality goes with it: it never throws on odd
input, whereas a `(major, minor)` decode has to say what it does with a version whose first two
dot-separated segments are not both integers. **Rule: a version that does not decompose is silent.**
This is an advisory; failing to read a version string is not worth a build message, let alone a
failure. It joins the other silence cases and is pinned by a test row.

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

Classification and emitted code do not change, so there is no *classifier* pipeline surface here. But
the report channel this item deliberately rides is one, and the entire case for `BuildWarning` over
`getLog().warn` is that suppression, LSP replay, and MCP projection come free; two unit tiers on the
halves would leave that claim unpinned by anything. Three tiers:

- Core unit tier, precedent `SessionStateWarningsTest`: the decision and the message shaping together,
  which is where the real behaviour now lives. Table-driven over observed-versus-current pairs,
  asserting the advisory names the observed version, the current version, and the coordinate to bump,
  and covering the silence cases explicitly, since under this design silence is the interesting output:
  at current, patch-only lag within the current minor, the coordinate absent from the resolved graph,
  and a version that does not decompose into `(major, minor)`. Include the `3.9` versus `3.20` trap, a
  major-line gap, and a `-SNAPSHOT` qualifier.
- Plugin-module unit tier: the decode only, over hand-built `Artifact` stubs. What it pins is the scope
  allow-list, one row per scope: `compile`, `provided`, and `system` observed; `runtime` and `test` not.
  The `runtime` row is the load-bearing one, because that is the scope a deny-list of `test` alone would
  let through, and the one that would make the advisory differ between `dev` and `generate`. Plus the
  absent-coordinate case. Note what this tier can and cannot pin: it covers the filter, not Maven's
  resolution, so the goal-invariance claim rests on the allow-list naming exactly the scopes
  `ResolutionScope.COMPILE` resolves. Precedent for making a mojo internal package-private for exactly this
  purpose is `AbstractRewriteMojo.collectExistingDirs` (`AbstractRewriteMojo.java:590-597`), whose
  javadoc says so in as many words.
- Pipeline tier, one case in `LintSuppressionPipelineTest`: a lagging version surfaces as a
  `BuildWarning.LintFinding` on the `ValidationReport` that the real
  `GraphQLRewriteGenerator.buildOutput()` returns, and the same build with that rule id in
  `<lint><disabledRules>` does not. That test already drives `buildOutput()` and asserts on the report,
  and the `withDependencyVersions(...)` wither makes the setup one line alongside the `withLintConfig`
  it already uses. This is the case that pins the routing argument rather than restating it.

The existing invoker harness (`graphitron-maven-plugin/pom.xml:93-125`) is optional and the
implementer decides whether it earns its wall-clock. If used, the valuable case is the negative one:
an assertion in `basic-generate`'s `verify.groovy` that an up-to-date consumer emits no such line. For
an advisory that fires by design, a false positive is the whole failure mode, and it erodes trust in
every other warning sharing the channel. That IT happens to exercise both silence shapes for free: it
depends on `graphitron-sakila-db`, which carries jOOQ at the reactor's own version, and it names
graphql-java nowhere, so a clean run pins "at current" and "coordinate absent" in one assertion.

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

## Reviewer rulings on the open questions

The Spec → Ready review answered the three questions the draft raised. They are settled; do not
reopen them.

1. **The minor-line predicate: adopt it, uniformly across both dependencies.** Warn when the
   observed `(major, minor)` pair orders below the current one, stay silent on patch-level lag
   within the current minor. Uniform rather than per-dependency: a per-dependency threshold is a
   second judgement with no derivable basis and no enforcer, and the draft's own observation that
   jOOQ's minor bump is its effective major boundary is exactly why the uniform minor-line rule
   already does the right thing for the case that matters. Note that major-line lag falls out for
   free under a `(major, minor)` comparison, so the predicate is one comparison with no
   special-casing.
2. **Scope of coordinates: graphql-java and jOOQ only; the other two are a follow-up.** The draft's
   leaning is right, and there is a second reason to hold: `federation-graphql-java-support` and
   `graphql-java-extended-scalars` are not in the foundational set `docs/dependencies.adoc`
   describes, so nudging on them would make the advisory broader than the policy paragraph paired
   with it. File a Backlog item if it is still wanted after this lands.
3. **The `docs/dependencies.adoc` policy paragraph ships in this item.** It is the only part of the
   item a consumer reads *before* meeting the warning, so splitting it means the advisory's first
   release has no explanatory page behind it. The draft already specifies its content precisely
   (policy, no numbers), so the cost is one paragraph.

## Implementation record

Landed as specified. The decision, the predicate, and the message shaping live in
`DependencyVersionWarnings` in core; the mojo's whole job is
`AbstractRewriteMojo.decodeDependencyVersions`, turning both artifact sets into
`(coordinate, version-string)` pairs behind the `GENERATED_CODE_SCOPES` allow-list. The carrier is
`DependencyVersions`, threaded through a `RewriteContext.withDependencyVersions(...)` wither and
folded into `withLintFindings` next to `SessionStateWarnings.forConfig`. The coordinates live in a
`WatchedDependency` enum in core, so the boundary asks which coordinates are interesting rather than
carrying its own copy of the list, and a third dependency is one enum constant plus one `LintRule`.

Where the implementer exercised the judgement the review left open:

1. **The two rule ids are `graphql-java-version-lag` and `jooq-version-lag`.** Separate rules rather
   than one shared id, because suppression is per id: a consumer held on an old jOOQ line by a
   licence or a platform BOM can silence that nudge and keep the graphql-java one.
2. **A single-segment version is read as minor `0`, not as undecodable.** The third pass raised it
   and the fourth judged it close to moot; taking it costs one line, `25` does mean `25.0`, and it
   cannot produce a false positive because an equal line is silent either way. The undecodable case
   (`RELEASE`, `3.x`, empty) stays silent as ruled.
3. **A missing reference version is silent**, as the fourth pass's reasoning already settled. Pinned
   by a test row rather than left to the reader.
4. **The invoker harness earns its wall-clock, and gains a positive case as well as the negative
   one.** The spec left the IT to the implementer and named the negative case. A negative-only IT
   would pass just as green with the feature entirely dead, and the reference-delivery route
   (`${plugin}` / `PluginDescriptor.getArtifacts()`) is the one claim in this design with no
   precedent anywhere in the reactor: nothing else uses `${plugin}`. So `basic-generate` gains the
   negative assertion (an up-to-date consumer, and one carrying no graphql-java, are both silent) and
   a new `dependency-version-lag` IT declares jOOQ `3.19.24` directly, beating the transitive current
   version by nearest-wins, and asserts the nudge fires naming both versions and the coordinate. That
   is the only tier where the reference side runs for real; every other tier hands the decision plain
   strings. The downgrade does not disturb generation, because the codegen classloader is parented on
   the plugin realm and `org.jooq` types resolve there parent-first.

The four `<sessionState>`-flavoured `Source.CODEGEN` documentation sites are widened to what the axis
actually partitions, and `LintRuleRegistryCoverageTest.everyCodegenAdvisoryRuleExists` carries the two
new ids. `docs/dependencies.adoc` gains the policy paragraph (policy, no version table).

## Review record

Four Spec-review passes, each by a session independent of the drafting one and of the others. The first
settled the three open questions the draft raised (recorded in the section above) and requested four
revisions. The second re-verified the plan's factual claims against the tree and folded those revisions
into the body. The third re-verified them again and corrected the scope ruling. The fourth signed the
item off to Ready.

All passes confirm the load-bearing claims hold as written: the `SessionStateWarnings` precedent and
its `null`-location `BuildWarning.LintFinding` shape, the `Source.CODEGEN` routing through
`withLintFindings`, the `LintRuleRegistryCoverageTest` enforcement, the `ValidationError` / `Rejection`
coupling, the single shared `RewriteContext` construction in `AbstractRewriteMojo` that makes one wither
reach all three goals, `ComparableVersion`'s availability on the plugin's `provided` classpath, and the
runtime-probe results. Every symbol this body names exists as named.

What the second pass changed, beyond refreshing drifted line anchors:

1. **Reference-version delivery is specified.** It reaches the mojo through `${plugin}` /
   `PluginDescriptor.getArtifacts()`, not through an unstated route out of a reactor pom property. The
   property-promotion prerequisite is gone, and so is the missing-mechanism gap it papered over: the
   reactor has no resource filtering to carry a filtered value into the shipped artifact.
2. **Scopes are ruled on**, so the advisory is goal-invariant across `generate`, `validate`, and `dev`,
   whose resolution scopes differ. (The third pass corrected which scopes; see below.)
3. **`Source.CODEGEN`'s documentation is widened as part of the change** rather than left describing a
   `<sessionState>` axis it no longer partitions; four sites named.
4. **The routing claim is pinned by a test.** A fires-then-suppressed case in
   `LintSuppressionPipelineTest`, and the `RewriteContext` carrier is named as a
   `withDependencyVersions(...)` wither.
5. **"Which side does the comparing" resolved an internal contradiction.** The placement section
   promised the decision in core while that subsection handed the comparison to the mojo. The settled
   minor-line predicate needs the version decomposed rather than ordered, which is plain-string work, so
   `ComparableVersion` is dropped, core compares, and the unparseable case that decision newly creates
   is ruled silent.

What the third pass changed:

1. **The scope ruling is an allow-list, not "every scope except `test`".** The old ruling did not
   deliver the goal-invariance it was justified by. `ResolutionScope.COMPILE` and `ResolutionScope.TEST`
   differ by two scopes, not one: `MojoExecutor.toScopes` expands `COMPILE` to `{compile, system,
   provided}` and `TEST` to `{compile, system, provided, runtime, test}` (read off `maven-core` 3.9.14
   directly, not inferred). Excluding `test` alone would still let a runtime-scoped coordinate be
   observed under `dev` and not under `generate`, reproducing the exact split the section forbids. The
   ruling now admits `compile`, `provided`, and `system` and nothing else, and the plugin-tier test gains
   a `runtime` row, which is the one that would have caught this.

Two non-blocking opportunities the third pass raised, left to the author and the implementer:

- **Single-segment versions.** `25` with no minor falls into "does not decompose, therefore silent",
  which loses the nudge for a consumer who pins `<version>25</version>`, a legal and not-unheard-of
  pin for graphql-java. Reading a missing minor as `0` would keep it alive at no real cost. The silence
  rule is defensible as written, so this is a judgement call rather than a defect.
- **The two rule ids are unnamed.** The plan says "two new `LintRule` values" without proposing their
  ids, where the `<sessionState>` precedent named `no-session-state` and
  `session-state-convention-fence` in prose. The ids are user-visible: a consumer types one into
  `<lint><disabledRules>`, `LintRuleRegistryCoverageTest` hard-lists them, and the MCP `diagnostics`
  tool projects them. Naming them in the Spec rather than at the keyboard is cheap.

The fourth pass found no blocking defect and signed the item off. It re-verified every anchor and
symbol the body names, and closed the one link in the scope argument no prior pass had checked
directly: `project.getArtifacts()` really is narrowed per goal, because `MojoExecutor` calls
`MavenProject.setArtifactFilter(...)` with a filter built from the *executing* mojo's
`getDependencyResolutionRequired()` immediately before each execution, rather than leaving one
session-wide resolved set in place. Read off `maven-core` 3.9.14 alongside `toScopes`. So the
allow-list ruling rests on verified behaviour, not on a plausible reading.

Three further non-blocking opportunities, all the author's call:

- **The silence-case list omits "consumer ahead of the reference".** A consumer on jOOQ `3.21` while
  graphitron still builds against `3.20.11` must be silent, and it falls out of "orders below" with no
  extra logic, but it is not among the silence rows the core-tier test enumerates. It is the state every
  consumer enters the moment R466 or R467 is in flight, and it is what an implementer who writes `!=`
  instead of `<` gets wrong while every enumerated row still passes. One more table row.
- **A missing *reference* version has no ruling.** The silence cases cover the consumer side
  (coordinate absent, version undecodable) but the plan does not say what happens when
  `PluginDescriptor.getArtifacts()` carries no entry for a coordinate, which the plan itself makes
  reachable by acknowledging consumers who override `<plugin><dependencies>`. The reasoning already in
  "Which side does the comparing" settles it (an advisory does not fail, and does not speak when it
  cannot read its own reference); it just needs the sentence, so an implementer does not reach for a
  throw or an `Optional.orElseThrow`.
- **The single-segment opportunity above is close to moot.** The observed version comes off a
  *resolved* artifact, so it is a version that exists on the repository, and neither coordinate
  publishes single-segment versions (graphql-java ships `25.0`, `24.1`, ...; jOOQ ships `3.x.y`). The
  author can close it as won't-fix with more confidence than the third pass had.

One imprecision in an implementation note, worth 30 seconds at the keyboard rather than a revision
round: "the canonical constructor's five convenience overloads stay untouched" holds for four of the
five. `RewriteContext` is a record, so a new component means the twelve-arg overload passes one more
`null` (exactly as it does for `tenantColumn` today, `RewriteContext.java:144`) and the three
existing withers each thread the new component through. The other four overloads chain through the
twelve-arg one and genuinely do not move.
