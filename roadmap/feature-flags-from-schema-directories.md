---
id: R1077
title: "Apply @feature(flags:) to the elements a <schemaInput> entry's files declare, replacing Graphitron 9's features/<flag>/ directories"
status: Spec
bucket: feature
priority: 3
theme: tooling
depends-on: []
created: 2026-10-20
last-updated: 2026-11-02
---

# Apply @feature(flags:) to the elements a <schemaInput> entry's files declare, replacing Graphitron 9's features/<flag>/ directories

## Goal

A `<schemaInput>` entry can name a feature flag. Every field, input field, argument and enum value
declared in the files that entry matches then carries `@feature(flags: ["<flag>"])`, in the schema
`Graphitron.buildSchema(...)` returns and in the federated `_service { sdl }`. This brings back what
Graphitron 9's `<transform><addFeatureFlags>` did for schema files under `features/<flag>/`, so
tooling that filters a consumer's schema by feature flag sees the same applications it saw on v9.
sis lays its schema out that way (`schema/features/stable/`, `beta/`, `experimental/`) and gets one
entry per directory:

```xml
<schemaInput>
    <pattern>src/main/resources/schema/features/beta/**/*.graphqls</pattern>
    <feature>beta</feature>
</schemaInput>
```

With that entry, a file `features/beta/film.graphqls` holding

```graphql
extend type Query {
  filmsByRating(rating: Rating): [Film!]!
}
enum Rating { G PG NC17 }
```

publishes as

```graphql
extend type Query {
  filmsByRating(rating: Rating @feature(flags: ["beta"])): [Film!]! @feature(flags: ["beta"])
}
enum Rating {
  G @feature(flags: ["beta"])
  PG @feature(flags: ["beta"])
  NC17 @feature(flags: ["beta"])
}
```

and an element whose author already wrote `@feature(flags: ...)` keeps exactly what the author wrote.

## Evidence and decision

The sis v9-against-v10 differential test (2026-10-19) found that `@feature(flags:)` applications in
`_service { sdl }` dropped from 8,870 on Graphitron 9 to 94 on v10. The 94 are the ones written by
hand in the SDL. v10 has no counterpart to the transform: nothing in the generator, the Maven plugin
or the manual mentions `@feature`. The user decided (relayed through the sis session, 2026-10-20) to
port an equivalent.

How Graphitron 9 did it (`graphitron-9-head`, `FeatureConfiguration` in `graphitron-schema-transform`,
driven by `SchemaTransformRunner` in the Maven plugin):

- The flag came from the file path. It was the first path segment after a `/features/` segment
  anywhere in the absolute path, and only when another segment followed it, so `features/x.graphqls`
  got none. Nested directories were not recognised: `features/beta/sub/x.graphqls` got `beta`.
- The directive went on field definitions (objects and interfaces), input fields, enum values and
  arguments, skipping arguments of directive definitions. Elements the transform minted itself (the
  `@asConnection` Connection and Edge types, and the `first:`/`after:` arguments) had no source
  location and got nothing. Types never got the directive.
- An element whose `@feature` already named flags kept them.
- The transform supplied `directive @feature(flags: [String!]!) on FIELD_DEFINITION |
  ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE` from its own `directives.graphqls`.
- The same directory convention drove two companions. One put a federation `@tag` beside each
  `@feature`, and on unions, when the `@link` imported `@tag`. The other appended the contents of
  `features/<flag>/description-suffix.md` to each flagged element's description.

v10 already carries both companions as explicit per-entry configuration rather than path
derivation: `<schemaInput><tag>` and `<schemaInput><descriptionNote>`. This item adds the missing
third leg on the same shape. Graphitron 9's other feature-flag functions, filtering a schema by
active flags (`SchemaFeatureFilter`) and splitting it into per-flag output schemas
(`<outputSchemas>`), stay out of scope; no consumer is known to depend on them.

## Design

**Configuration, not path derivation.** `<schemaInput>` gains an optional `<feature>` child, a
single flag name, beside `<tag>` and `<descriptionNote>`. All three are independent. A file takes the
feature of the one entry that matches it; the existing rule that a file claimed by two entries fails
the build (`SchemaInputAttribution.build`, the `SourceInTwoInputs` refusal) already makes that answer
unique. An entry with no `<feature>` applies none, so the behaviour is on exactly where configured
and nothing changes for a consumer who configures nothing.

**What gets the directive.** The configured-tag rule's population minus unions: every field of an
object or interface, every input field, every field argument and every enum value, whose declaring
site is in a file whose entry configures a feature, and which is in the emitted population
(`graphitron_element`). Elements added by `extend type`, `extend input` or `extend enum` take the
feature of the file holding the extension, because `graphql_field.source_name` and its siblings
name the declaring site's file, base or extension. A field argument takes the feature of the file
declaring its field. Types, unions, directive-definition arguments and anything graphitron mints
(Connection and Edge types and their fields, the pagination arguments `@asConnection` adds) get
nothing. That matches Graphitron 9, and it is a deliberate asymmetry with `@tag`, which
`graphitron_carrier_directive` copies from a carrier field onto the types it mints. `@feature`'s
locations do not admit types, so there is nothing to copy at type level, and the members of a
minted type need no flag of their own: a filter that drops the flagged carrier field leaves its
Connection and Edge types unreachable, and they go with it. The same holds for federation's
`_entities` and `_service`, which no consumer file declares.

**Explicit wins.** An element with an authored `@feature` application at its coordinate gets no
configured one, whatever flags the author wrote. `@feature` is not repeatable, so there is never a
second application to order.

**The definition is the consumer's.** Graphitron does not supply a `@feature` definition. Its own
vocabulary, `directives.graphqls`, is stripped from the published schema by
`EmittedRegistry.unpublish`, so a definition there would never reach `_service { sdl }`. A
consumer already applying `@feature` by hand already declares it; sis does, or its 94 hand-written
applications could not have survived into v10's output. Supplying one would collide with that
declaration. This is the opposite of `@nodeType`, whose definition `EmittedRegistry` supplies and
refuses from an author: every `@nodeType` application is one graphitron mints, so graphitron owns
that directive, while `@feature` has authored applications and a meaning in the consumer's own
tooling. A configured feature the schema's `@feature` definition cannot take fails the build:
no definition at all, no `flags` argument accepting a list of strings, or no location for the
kind of element the feature lands on. The check is per landing, so a definition without
`ENUM_VALUE` fails only where a configured feature actually reaches an enum value. The message
names the element and the entry and gives the definition to paste:

```graphql
directive @feature(flags: [String!]!) on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE
```

**Where the application lives.** The configured tag's path, followed exactly. The capture records
each file's feature beside its tag, a rule view derives one row per element that gets a feature, and
the emitted-population views that already union in the configured `@tag` union in the configured
`@feature` too. `EmittedRegistry.directives(store)` already places every `CONFIGURED` application on
its declaring AST site whatever its name, so from there the generated `GraphitronSchema.build`
(through `AppliedDirectiveEmitter.emitApplications`), the printed `schema.graphqls` (through
`SchemaSdlEmitter`) and the runtime `_service { sdl }` (federation's printer over the built schema)
all carry the application with no emitter change.

## Implementation

Recipe and input:

- `SchemaInputBinding`: new `String feature` field, javadoc'd like `tag`.
- `AbstractRewriteMojo.buildSchemaRecipe`: decode it with the same empty-string collapse as `tag`
  and `descriptionNote`.
- `SchemaRecipe.Binding` and `SchemaInput`: new `Optional<String> feature` component, with
  null-checks and the `pattern`/`literal`/`file`/`named` factories passing `Optional.empty()`.
  `SchemaInputAttribution.describe` names it, so the two-entries message shows all three
  attributes.
- `StoredRecipe`, and whatever reads recipe rows back for replay: write and read the new column, so
  a replayed recipe judges the schema the build judged.

Store (`graphitron-model.sql`):

- `store_graph_schema_input.feature` and `store_graph_source_input.feature`, nullable `VARCHAR`,
  commented like `tag`. `GraphQLSourceCapture.writeInputs` writes the latter.
- `graphitron_configured_feature_rule`, a view shaped like `graphitron_configured_tag_rule` without
  the union arm and without the ordinal arithmetic: three `UNION ALL` arms over `graphql_field`,
  `graphql_argument` and `graphql_enum_value`, joined to `store_graph_source_input` on
  `source_name` where `feature IS NOT NULL`, with `NOT EXISTS` an authored `feature` application at
  the coordinate, joined to `graphitron_element`. It projects the element's kind (field, input
  field, argument, enum value), which the definition check needs to pick the location.
- `graphitron_configured_feature`, the anchored table: `(graph_name, coordinate, flag, touched_at)`,
  primary key `(graph_name, coordinate)`, foreign key to `store_graph`. It stores the flag itself,
  not a rendered `["<flag>"]`, so a reader (an LSP hover, say) gets the flag without parsing SDL. No
  `ordinal`: `@feature` is not repeatable, and a key column that protects nothing is not worth
  having. It gets a `meta_relation` row naming its owner, the grain "one row per element the
  configuration flags", and the mark and sweep the tag table uses. `EmittedAnchor` stores it
  beside `GRAPHITRON_CONFIGURED_TAG`.
- `graphitron_directive_application_configured` gains a `UNION ALL` arm emitting
  `(coordinate, 'feature', 0, 'CONFIGURED', NULL, NULL, NULL)` per row, and
  `graphitron_directive_application_arg_configured` one emitting `(coordinate, 'feature', 0,
  'flags', '["' || flag || '"]')`. The literal ordinal and the rendering live in the views, not
  the table. The views' comments stop saying the set is tags alone.

The definition check is a detection view over captured rows: one row per `graphitron_configured_feature`
row whose landing the graph's `@feature` definition cannot take, anti-joined against
`graphql_directive_location` for the element's kind and against `graphql_directive_argument` for
`flags`, with the reason as a typed column. Because it reads exactly the rows the emitter applies,
the check and the emission cannot drift. It surfaces as an error arm of the `diagnostic` view, so
the build, the editor and the MCP `diagnostics` tool report it alike, and the message, the paste
line included, is minted in Java from a typed rejection with a stable LSP code rather than built in
SQL. Two homes are rejected. It is not a `REWRITE`-stage refusal: that stage records what the
loading rewrites hit while running, and the configured directives are deliberately not among the
rewrites (`LoadingRewrites`). And it is not a throw in `EmittedRegistry` reading the live registry,
the way `refuseAuthoredNodeType` does, since the store already holds every fact the check needs.
The implementer confirms the build fails on this error before `EmittedRegistry.derive` or the
generated `GraphitronSchema.build` would hit graphql-java's raw unknown-directive error. (The
`intent_` family's claim-conflict arms show how an error arm of `diagnostic` is wired; per
`CLAUDE.md` they are not precedent for the relation's shape.)

Docs:

- `docs/manual/reference/mojo-configuration.adoc`: a `feature` row in the `<schemaInput>` binding
  table (draft below), and the `schemaInputs` row's "three optional children" count corrected. Fix
  the `tag` row's claim that tags go on "every type" while there: the rule tags unions and members,
  never other types.
- `docs/manual/how-to/migrating-from-legacy.adoc`: a short section mapping a Graphitron 9
  `features/<flag>/` layout to one `<schemaInput>` per directory carrying `<feature>`, `<tag>` where
  the v9 build imported `@tag`, and `<descriptionNote>` carrying the contents of
  `description-suffix.md`.
- `docs/architecture/explanation/fact-model.adoc`: the configured-tag paragraph names the
  configured feature beside it.

## User documentation (first-client check)

Row for the `<schemaInput>` binding table:

> `feature` | `String` | Optional feature flag applied to every field, input field, argument and
> enum value defined in matched files, as `@feature(flags: ["<flag>"])`. An element that already
> carries `@feature` keeps its own. Your schema must declare the directive, `directive
> @feature(flags: [String!]!) on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION |
> ENUM_VALUE`; the build fails and shows that line if it does not. Fields added with `extend type`
> take the feature of the file holding the extension. Unlike `tag`, this has nothing to do with
> federation and does not add an `@link`.

Migration paragraph:

> Graphitron 9 flagged files by where they sat: with `<addFeatureFlags>` on, a file under
> `features/beta/` got `@feature(flags: ["beta"])` on its elements. Graphitron 10 flags files by the
> entry that matches them. Give each feature directory its own `<schemaInput>` with
> `<feature>beta</feature>`, and make sure the entry for the rest of your schema does not also
> match it, since a file belongs to one entry. If your v9 build also wrote `@tag` from the
> directories, add `<tag>beta</tag>` to the same entry. If it read a `description-suffix.md`, put
> its text in `<descriptionNote>`.

## Tests

- `ConfiguredFeatureTest` (graphitron-model, new, modelled on `ConfiguredTagTest`): over one
  captured store, the emitted schema carries `@feature(flags: ["catalog"])` on an object field, an
  interface field, an input field, a field argument, an enum value and a field added by `extend type`;
  carries none on the type, the union, or an element with an authored `@feature(flags: ["own"])`,
  which keeps `["own"]` alone; and the schema as written carries none. A second case puts a feature
  and a tag on the same entry and asserts both land, independently. Needs a
  `CapturedStore` door taking a feature beside its `tagged` one.
- `TaggedInputsPipelineTest` (graphitron), or a sibling: two files in a temp dir, one under a
  feature entry and one not, through `GraphitronStore.captured(ctx)`. Only the first file's
  elements are flagged, and a field the unflagged file adds by `extend type` to a type the flagged
  file declares is not.
- The definition check, in a model-tier test beside `ConfiguredFeatureTest`: one case each for no
  definition, a definition without `flags`, and one without `ENUM_VALUE`. The last fails when a
  configured feature lands on an enum value and passes when the flagged file declares none. Each
  error is read off the `diagnostic` view with its LSP code, and a build over the first case fails
  with the paste line rather than graphql-java's unknown-directive message.
- The registration gates the new relations come under, the ones `graphitron_configured_tag` already
  passes: `RelationRegistrationGateTest`, `StageAnswerAgreementTest` (the rule and table pair),
  `MetaDeclarationGateTest` and `SupertypeSignatureGateTest`.
- Recipe round trip: `SchemaRecipeExpansionTest` and the mojo's `SchemaInputExpansionRenderingTest`
  or `GenerateMojoTest` carry `<feature>` from POM binding to `SchemaInput`, and the stored recipe
  replays it.
- End to end, which is what demonstrates the goal: the sakila federated fixture gains a second
  `<schemaInput>` over a new `graphql/features/beta/` file with `<feature>beta</feature>`, the
  fixture declaring `@feature`. `FederationBuildSmokeTest` asserts that `_service { sdl }` carries
  `@feature(flags: ["beta"])` on that file's field, argument and enum value and on none of the
  existing fixture's elements, and that the field in the built `GraphQLSchema` has the applied
  directive. `emittedSdlMatchesRuntimeSchema` keeps the printed resource and the runtime schema in
  step.
- Outside the build: the sis session reruns the differential count after upgrading. The number of
  `@feature` applications in `_service { sdl }` should return to v9's 8,870, give or take elements
  v10 does not publish.

## Other solutions we've considered

- **Derive the flag from the path, as v9 did** (a plugin switch, or always on when a `features/`
  directory exists). Rejected. v10 already moved the other two products of the same directory
  convention, `@tag` and the description suffix, to explicit per-entry attributes, and a third
  mechanism keyed on paths would split one concept across two configuration styles. v9's rule matched
  `/features/` anywhere in the absolute path, so a checkout under a directory of that name flagged
  everything. And the recipe is what the store records for replay (`store_graph_schema_input`); a
  flag derived from paths would be a second, unrecorded input to the same answer. The cost is one
  `<schemaInput>` per feature directory, which a consumer applying tags per directory already has.
- **Graphitron supplies the `@feature` definition**, as v9 did. Rejected: it would collide with
  the declaration consumers applying `@feature` by hand already have, and Graphitron's own
  directive file is unpublished by design. A failing build that prints the line costs one paste.
- **One `graphitron_configured_directive` relation keyed by directive name**, replacing the tag
  table. Rejected: the two rows have different key shapes. A tag is repeatable, keyed with an
  ordinal, and a union takes one per declaration site; a feature is not repeatable and is keyed on
  the coordinate alone. The populations differ too (unions), and `graphitron_carrier_directive_rule`
  reads tags only. A merged relation would either lose the key that enforces one `@feature` per
  element or carry an ordinal only some rows use. The generic surface already exists one layer up,
  in the `graphitron_directive_application_configured` views the two arms union into.

## Reviewer findings

### Round 1: Spec → Ready (session_01FY5UvtekMBx3FKcETS4837, 2026-11-02)

Verdict: withhold. Question 1 passes. When this lands, a consumer adds `<feature>beta</feature>` to a
`<schemaInput>` and every field, input field, argument and enum value in the matched files gets
`@feature(flags: ["beta"])` in the served schema and in `_service { sdl }`, unless the author already
wrote one. The consumer declares the directive themselves. The emission half is reachable as
described and follows the configured tag path exactly. Question 2 fails, along with that half's
viability, on the definition check. The emission design is fine; findings 1 and 2 are about the
check alone.

**1. The definition check stands a parallel mechanism beside the one the tree documents for exactly
this (question 2).** `docs/architecture/explanation/defects-as-facts.adoc` is the home for a defect
detected from captured rows. A code is declared in `graphitron_defect_type` (severity, `lsp_code`,
the statement an author reads, `owner_gatherer`). Rows go in `graphitron_entry_defect` at a written
position, from the owner's `<family>_entry_defect_rule` view that `EntryDefects` writes in its
anchor phase. The build reads it through a `StoreDetections` reader, the editor and MCP read it
through `diagnostic`'s entry arm, and `DefectOwnerGateTest` holds the ownership together. The spec
instead plans a separate detection view, a new arm of `diagnostic`, and a message "minted in Java
from a typed rejection", wired the way the `intent_` claim-conflict arm is. It does not mention the
defect vocabulary, and it does not reject it in "Other solutions". Its stated payoff is also wrong
for this tree: an arm of `diagnostic` does not reach the build. The build does not read
`diagnostic`; it reads `StoreDetections` (`GraphQLRewriteGenerator.runPipeline`), and
defects-as-facts says outright that a stored defect fails the build only where a `StoreDetections`
reader reads its code. What would satisfy this: either put the check on the defect path (a code per
reason or one code with a typed detail, a position each landing hangs on, the owner gatherer, and a
`StoreDetections` reader), or name that path in "Other solutions" and say why it does not fit. In
the second case, also say how the chosen arm reaches the build.

**2. Wherever the check lives, the run fails before it is read (viability).** The rows the check
flags are, by design, rows the emitter applies. So whenever the check fires, the post-synthesis
registry carries an application that graphql-java refuses: an undeclared directive, an illegal
location, or an unknown argument. `SchemaAssembly.of` records that as `Rejected` without throwing
during capture. Then `GraphQLRewriteGenerator.runPipeline` calls `emittedSchema()`, which throws
`IllegalStateException("the emitted registry derived from the store did not assemble ...")`. That
is the message for graphitron's own defect, and it fires before `StoreDetections.over` is read a few
lines later. The spec's "the implementer confirms the build fails on this error before
`EmittedRegistry.derive` or the generated `GraphitronSchema.build` would hit graphql-java's raw
unknown-directive error" is therefore not a confirmation. In today's order the confirmation fails,
and repairing it is a design choice the spec has to make. Some options: read the detections before
the emitted schema is assembled; keep a configured feature the definition cannot take out of the
emitted relation, so the emitted registry still assembles while the defect is reported (this also
gives the editor a usable schema); or another order the author prefers. What would satisfy this: the
plan states the order, and a test pins that a build over a schema with no `@feature` definition
fails with the paste-line message rather than the graphitron-defect message. The Tests section
already asks for that assertion. It needs a design that can pass it.

**3. The rule view and the table disagree on a column (question 2, minor).**
`graphitron_configured_feature_rule` "projects the element's kind, which the definition check needs".
`graphitron_configured_feature` is `(graph_name, coordinate, flag, touched_at)`, and the check is
"one row per `graphitron_configured_feature` row". `StageAnswerAgreementTest`, which the Tests
section enrolls the pair in, compares table and rule with `EXCEPT` in both directions, so their
columns have to agree. The check also cannot read a kind the table does not hold. Say whether the
table stores the kind, or whether the check gets it again from the coordinate's type and the rule
drops it.

Not blocking: every other symbol, test and relation the spec names exists as named. That includes
`EmittedRegistry.directives` placing any `CONFIGURED` row on its declaring site, extension sites
included (`ElementSite.of`), `unpublish` dropping the bundled vocabulary's definitions, and
`SourceInTwoInputs` (a `LoadingRewrites.Refusal`, raised from `SchemaInputAttribution.build`'s
throw).
