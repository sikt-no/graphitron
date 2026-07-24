---
id: R494
title: "Reconcile SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES with BuildContext DIR_* (routine, asFacet, pivot)"
status: In Progress
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-16
last-updated: 2026-07-24
---

# Reconcile SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES with BuildContext DIR_* (routine, asFacet, pivot)

`SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES` documents itself as "kept in sync with the `DIR_*` constants in `BuildContext`", with the invariant that adding a generator-only directive means adding both a `DIR_*` constant and an entry here. `BuildContext` now declares `DIR_ROUTINE` ("routine") and `DIR_AS_FACET` ("asFacet") that are absent from the set, so the stated invariant is already false. This is potentially a correctness bug, not only doc drift: if those two directives are meant to be generator-only, `isSurvivor("routine")` / `isSurvivor("asFacet")` currently return the wrong answer and the directives can leak into the emitted schema; if they are genuinely survivors, the doc overstates the coupling. Resolving it requires deciding the intended classification of `routine` and `asFacet`, then either extending the set (with a mechanical sync check so it cannot silently drift again) or correcting the prose to match reality.

Surfaced by the R483 javadoc drift audit.

## Assessment (2026-07-24): confirmed real bug, three directives leak

Verified against the current build outputs; this is an actual leak, not only doc drift. Three
`DIR_*` names are missing from `GENERATOR_ONLY_DIRECTIVES`: `routine`, `asFacet`, and `pivot`
(`pivot` was added after this item was filed and drifted the same way). All three are declared in
`directives.graphqls`, are pure build-time directives with no runtime meaning, and `isSurvivor`
returns true for each, so both emission arms pass them through:

- The emitted `schema.graphqls` (sakila example, `target/generated-resources/.../schema.graphqls`)
  contains the `directive @routine(...)`, `directive @asFacet ...`, and `directive @pivot(...)`
  definitions plus the applications, e.g.
  `films(minLength: Int!): [ActorFilm!] @routine(argMapping : "pMinLength: minLength", columnMapping : "pActorId: actor_id", name : "films_for_actor")`.
- The generated programmatic schema classes carry the same leak:
  `GraphitronSchema.java` registers all three via `additionalDirective(...)`, and per-type
  emitters (`ActorType`, `FilmType`, `FilmFacetFilterType`) attach the applications via
  `withAppliedDirective(...)`.

Impact beyond cosmetics: the applications expose build-time database internals (routine names,
column mappings such as `pActorId: actor_id`) to any consumer that introspects the schema or reads
the published SDL. The `SchemaDirectiveRegistryTest` sync test only spot-checks a hardcoded name
list, so it could not catch the drift.

---

## Design

> Stop hand-maintaining `GENERATOR_ONLY_DIRECTIVES`. Derive it at class-init from the bundled
> `directives.graphqls`, following the in-module precedent `DirectiveSupportTypes` (which derives
> the support-*type* set from the same resource via `RewriteSchemaLoader.directivesSdl()` and a
> `SchemaParser` parse). The derivation lives at the parse boundary (`rewrite.schema`), exposed
> as a plain `Set<String>` that the registry consumes; `generators/util` stays string-only. The
> invariant becomes definitional: a directive is generator-only iff it is declared in
> Graphitron's own `directives.graphqls`. The drift class this item reports becomes
> unrepresentable rather than merely detected.

### Why derivation, not a wider literal set plus a sync test

A verified fact settles the shape: after adding the three missing names, the hand-maintained set
is exactly equal to the set of directive names declared in `directives.graphqls` (32 names). All
Graphitron-declared directives are build-time-only; none has runtime meaning in the emitted
schema. So the resource is the source of truth already, and the literal set is a cache of it that
has demonstrably gone stale once. `DirectiveSupportTypes` faced the same choice for support types
and settled on derive-plus-pinning-test; this item applies the same pattern to the directive
names, reading the parsed registry's directive-definition name set instead of its type names.

The alternative (keep `Set.of(...)`, add the three names, add a unit test asserting set equality
against the parsed resource) detects drift at test time but keeps two copies of the same fact.
Rejected in favor of the established derive-not-hand-maintain precedent.

The deeper reason derivation wins: the leak already had a pipeline-tier guard,
`SchemaSdlEmissionTest.emittedSdlCarriesNoGraphitronInternalSurface`, which re-parses every
emitted SDL and asserts each directive definition and application is a survivor. It passed
throughout because its oracle is `isSurvivor` itself, the very set under repair: a name missing
from the set is trivially a "survivor" to both the emitter and the test, so the enforcer and the
thing enforced shared one source and the test was blind to exactly this drift class (empirical
inspection of generated output caught it, not the suite). Derivation dissolves the tautology:
`isSurvivor` becomes sourced from `directives.graphqls`, independent of the emitter's behavior,
so the same pipeline test gains teeth with no new test needed. A leaked `@routine` now fails
"must be a survivor". The unit-tier pinning test below is a change-consciously guard, not the
behavioral enforcer; the pipeline sweep is.

### Derivation at the parse boundary

The `SchemaParser` parse does not go into `generators/util`; that package is string-only today
and stays that way. The directive-name set is derived in `rewrite.schema`, beside
`DirectiveSupportTypes`, which already parses the same resource: "support types" and "declared
directive names" are two views of one parse of `directives.graphqls`. A small sibling accessor
(working name `DeclaredDirectives`, final shape implementer's choice) exposes
`Set<String> names()`; sharing the single parse with `DirectiveSupportTypes` through a
package-private holder is preferred but is implementation latitude, since the resource is small
and both derive at class init.

`SchemaDirectiveRegistry` then consumes the boundary's set:

```java
public static final Set<String> GENERATOR_ONLY_DIRECTIVES = DeclaredDirectives.names();
```

`isSurvivor` is unchanged. The class javadoc drops the stale "kept in sync with the `DIR_*`
constants in `BuildContext`" paragraph and states the derivation intent instead ("generator-only
iff declared in `directives.graphqls`"), linking the live symbols (`{@link}` to the boundary
accessor and the `DirectiveSupportTypes` precedent) rather than narrating the severed `DIR_*`
relationship. The "adding a new generator-only directive" instruction reduces to "declare it in
`directives.graphqls`", which authors must do anyway for the classifier to see it.

Class-init failure mode: `RewriteSchemaLoader.directivesSdl()` already throws
`IllegalStateException` when the resource is missing, and the resource is same-module and parsed
by every build path long before the emitters run, so an init failure here cannot be the first
loud failure in any realistic run. `DirectiveSupportTypes` accepts the identical failure mode.

### Test changes

- `SchemaDirectiveRegistryTest.generatorOnlySet_containsAllGraphitronDirectiveNames` (a
  spot-check of a hardcoded subset, which is how the drift went unnoticed) is replaced by a
  membership-pinning test mirroring `DirectiveSupportTypesTest`: `containsExactlyInAnyOrder` of
  all 32 declared names, so an edit to `directives.graphqls` changes the survivor filter
  consciously rather than silently.
- The survivor-side tests (`isSurvivor` true for unknown custom directives, `@deprecated`,
  federation names; false for every member of the set) stay as they are.
- No new leak test is needed: derivation turns the existing
  `SchemaSdlEmissionTest.emittedSdlCarriesNoGraphitronInternalSurface` sweep into the behavioral
  enforcer, across the plain, federated, and multischema plugin executions (see "Why derivation"
  above for the tautology it dissolves).

### Settled design notes

1. *No built-in-injection filter is needed, verified empirically.* `DirectiveSupportTypes`
   filters graphql-java's injected built-in scalars via `getSourceLocation() != null`; the
   analogous risk here would be `getDirectiveDefinitions()` including `@deprecated` / `@skip` /
   `@include` / `@specifiedBy`, which would flip `isSurvivor("deprecated")` to false and
   silently drop `@deprecated` from emitted schemas. A jshell probe against the current
   graphql-java pin confirms the parsed registry's directive definitions are exactly the 32
   declared names with no injected built-ins, so no filter is required. Two tests pin this
   against a future graphql-java behavior change: the 32-name membership test (would see the
   extra names) and the existing `isSurvivor("deprecated")` survivor assertion.
2. *`DIR_*` stays hand-maintained, deliberately.* After this item, Graphitron directive names
   live in `directives.graphqls` (source of truth), the derived survivor set (this fix), and
   the `BuildContext` `DIR_*` constants. The constants are classifier lookup keys, not a
   must-equal-the-SDL enumeration, so they are a much weaker drift surface than the survivor
   set was; re-sourcing them is out of scope here and would be its own item if ever warranted.

## Implementation sites

- New: a small directive-name accessor in `graphitron/src/main/java/no/sikt/graphitron/rewrite/schema/`
  (working name `DeclaredDirectives`), deriving from `RewriteSchemaLoader.directivesSdl()`,
  ideally sharing one parse with `DirectiveSupportTypes`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/SchemaDirectiveRegistry.java`:
  replace the literal `Set.of(...)` with the boundary accessor's set; rewrite the class and
  field javadoc.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/util/SchemaDirectiveRegistryTest.java`:
  replace the spot-check with the 32-name pinning test.

No other call site changes: `DirectiveDefinitionEmitter`, `AppliedDirectiveEmitter`, and
`SchemaSdlEmitter` consume `isSurvivor` and are correct once the set is. The generated sakila
outputs (SDL and programmatic schema classes) lose the three directive definitions and their
applications on the next build; that diff is the user-visible fix.

## Acceptance

- `mvn install -Plocal-db` green.
- The sakila example's emitted `schema.graphqls` files and generated `GraphitronSchema` /
  per-type classes contain no `routine`, `asFacet`, or `pivot` definitions or applications
  (pinned by the now-non-circular `SchemaSdlEmissionTest`).
- The pinning test fails if a directive is added to or removed from `directives.graphqls`
  without touching the test, making survivor-filter changes conscious.

## Non-goals

- Reclassifying any directive as a survivor: all 32 Graphitron-declared directives are
  build-time-only, and none is exempted.
- Touching `BuildContext` `DIR_*` constants; they serve the classifier and are not the
  registry's source of truth.
- A build-time schema-diff gate over emitted SDL; the pipeline-tier sweep already covers the
  surface.
