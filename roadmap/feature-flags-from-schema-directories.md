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
tooling. A configured feature the schema's `@feature` definition cannot take is not applied, and
it fails the build: no definition at all, a `flags` argument missing or not a list of strings,
another required argument the configured application would not supply, or no location for the
kind of element the feature lands on. Those are exactly the checks graphql-java makes on an
applied directive, so what is applied always assembles and what would not is reported instead.
The check is per landing (one element a configured feature reaches), so a definition without
`ENUM_VALUE` fails only where a configured feature actually reaches an enum value. The message
names the element and the flag and gives the definition to paste:

```graphql
directive @feature(flags: [String!]!) on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE
```

**Where the application lives.** The configured tag's path, followed exactly. The capture records
each file's feature beside its tag, a rule view derives one row per element that gets a feature, and
the emitted-population views that already union in the configured `@tag` union in the configured
`@feature` too. The one addition is the split in front of the rule: every landing is either applied
or refused with a reason, never both and never neither, so the emitted schema and the defects
cannot drift apart. `EmittedRegistry.directives(store)` already places every `CONFIGURED` application on
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
- `graphitron_configured_feature_landing`, a view of every place a configured feature would land,
  shaped like `graphitron_configured_tag_rule` without the union arm and without the ordinal
  arithmetic: three `UNION ALL` arms over `graphql_field`, `graphql_argument` and
  `graphql_enum_value`, joined to `store_graph_source_input` on `source_name` where
  `feature IS NOT NULL`, with `NOT EXISTS` an authored `feature` application at the coordinate,
  joined to `graphitron_element`. It projects `(graph_name, coordinate, flag, location,
  source_name, source_line, source_column, refusal)`. `location` is the directive location the
  element needs, spelled as `graphql_directive_location` spells it: `FIELD_DEFINITION` or
  `INPUT_FIELD_DEFINITION` by the declaring type's `graphql_type.kind`, `ARGUMENT_DEFINITION`,
  `ENUM_VALUE`. The
  position is the element's declaring site, the `source_line` and `source_column` its relation
  already carries, which `graphql_ast_element_entry` holds as a declaration. `refusal` is NULL
  where the graph's `@feature` definition takes the landing, and otherwise the first that applies of
  `UNDEFINED` (no row in `graphql_directive` for `feature`), `ARGUMENTS` (no `flags` row in
  `graphql_directive_argument` with `named_type` `String` and `list_depth` 1, or another argument
  with `non_null` and no `default_value_sdl`), and `LOCATION` (no `graphql_directive_location` row
  for `location`).
- `graphitron_configured_feature_rule`: the landing view's rows with `refusal IS NULL`, projected to
  the table's columns, so the rule and the table agree in shape for `StageAnswerAgreementTest`.
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

The definition check is a stored defect, added the way
`docs/architecture/explanation/defects-as-facts.adoc` § Adding a defect says, with `@outbox`'s
configuration faults (`OUTBOX_NO_TARGET`, read by `OutboxDefects`) as the worked precedent: a
configuration has no SDL position, so the fault is reported at each element it affects.

- `graphitron_defect_type` gains three codes, one per refusal: `FEATURE_UNDEFINED`,
  `FEATURE_ARGUMENTS` and `FEATURE_LOCATION`, each `error`, basis `GRAPHITRON`, owner `graphitron`,
  with `lsp_code` `GRAPHITRON_` plus the code. Each statement says what the definition lacks and
  ends with the definition to paste, so the paste line is the vocabulary's and every reader quotes
  the same one.
- `graphitron_entry_defect_rule` gains an arm reading `graphitron_configured_feature_landing` where
  `refusal IS NOT NULL`: one row per refused landing at the element's declaring site, the code
  picked by the refusal, the detail naming the flag and, for `FEATURE_LOCATION`, the missing
  location. `EntryDefects.derive` writes it in the graphitron gatherer's anchor phase
  (`GraphitronAssemblyCapture.resolve`), which runs after `EmittedAnchor`, and the existing sweep
  removes a refusal the next reading no longer finds. `graphitron_entry_defect_site` resolves the
  position to the element's coordinate, a declaration being its own element, so the editor and the
  MCP `diagnostics` tool show it through `diagnostic`'s entry arm with no change there.
- `ConfiguredFeatureDefects`, a new `StoreDetections` component on `OutboxDefects`' shape:
  `READS` `graphitron_entry_defect`, `graphitron_defect_type` and `graphql_ast_element_entry` (for
  the coordinate, keeping the read on tables), selects the `FEATURE\_%` codes, and mints each as a
  `ValidationError` at the element, the statement with the detail. This is what fails the build. A
  schema with no definition at all refuses every landing, which for sis is thousands, so the
  reader collapses rows sharing a code and a flag into one error at the first landing in
  position order, ending "and N more elements in M files". The store keeps one row per landing,
  which is what the editor underlines.

Refused landings are not applied, which settles the order of the run. The post-synthesis registry
carries only applications the definition takes, so `EmittedRegistry.derive` assembles,
`GraphQLRewriteGenerator.runPipeline`'s `emittedSchema()` returns, and `StoreDetections.over`, read
just after it, fails the build with the defect's message. Nothing in `runPipeline` moves. The
editor and the dev loop keep an assembled schema while the defect is shown.

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
- The definition check, pinned in a fact document per defects-as-facts: one case per refusal (no
  definition; `flags` missing; `flags: String`; an extra `owner: String!`; no `ENUM_VALUE`), each
  drawing its `FEATURE_*` row at the element's position, read off `diagnostic` with its LSP code,
  and no `graphitron_configured_feature` row for that landing. Beside them the near misses that draw
  nothing: the definition without `ENUM_VALUE` over a flagged file that declares no enum value
  (its field still flagged), and an extra argument that is nullable or has a default.
- The run order, in the graphitron tier through `GraphitronStore.captured(ctx)`: over the
  no-definition case the post-synthesis assembly is `SchemaAssembly.Assembled`, and the build fails
  with `ValidationFailedException` carrying the paste line, one collapsed error per flag, not
  `emittedSchema()`'s `IllegalStateException`.
- The registration gates the new relations come under, the ones `graphitron_configured_tag` and the
  `OUTBOX_*` codes already pass: `RelationRegistrationGateTest`, `StageAnswerAgreementTest` (the
  rule and table pair), `MetaDeclarationGateTest`, `SupertypeSignatureGateTest`,
  `DefectOwnerGateTest` (the three codes owned by `graphitron` and written by its rule view) and
  `DetectionReadReachGateTest` (the new component's `READS`).
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
- **Apply every landing and report the bad ones**, reading `StoreDetections` before
  `emittedSchema()` in `runPipeline` so the defect wins the race. Rejected: the post-synthesis
  registry would still fail to assemble, which the editor, the dev loop and the generated
  `GraphitronSchema.build` all consume, and the reorder only moves which error a build shows first.
  Withholding a refused landing keeps every product assembling and costs nothing, since the build
  fails on the defect anyway.
- **Check the definition somewhere other than the defect vocabulary.** A `REWRITE`-stage refusal
  records what the loading rewrites hit while running, and the configured directives are
  deliberately not among the rewrites (`LoadingRewrites`). A throw in `EmittedRegistry`, the way
  `refuseAuthoredNodeType` reads the live registry, would reach the build alone, never the editor,
  when the store already holds every fact the check needs. A detection view with its own arm of
  `diagnostic` would never reach the build, which reads `StoreDetections` rather than `diagnostic`.
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

> Author: moved onto the defect path. Three `FEATURE_*` codes in `graphitron_defect_type`, an arm
> of `graphitron_entry_defect_rule` at each refused landing's declaring site (the `OUTBOX_NO_TARGET`
> precedent for a configuration fault), and a `ConfiguredFeatureDefects` component in
> `StoreDetections` that fails the build. The detection view and the new `diagnostic` arm are gone,
> and "Other solutions" says why a separate arm would not reach the build.

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

> Author: refused landings are withheld. `graphitron_configured_feature_landing` splits every
> landing into applied (`refusal IS NULL`, which feeds the rule and the emitted relation) or
> refused (which feeds the defect arm). The refusals are exactly graphql-java's applied-directive
> checks, the extra-required-argument case included, so the post-synthesis registry always
> assembles and `runPipeline` reaches `StoreDetections.over` unchanged. The Tests section pins
> `Assembled` plus the defect's `ValidationFailedException` over the no-definition case. The
> reorder alternative is under "Other solutions".

**3. The rule view and the table disagree on a column (question 2, minor).**
`graphitron_configured_feature_rule` "projects the element's kind, which the definition check needs".
`graphitron_configured_feature` is `(graph_name, coordinate, flag, touched_at)`, and the check is
"one row per `graphitron_configured_feature` row". `StageAnswerAgreementTest`, which the Tests
section enrolls the pair in, compares table and rule with `EXCEPT` in both directions, so their
columns have to agree. The check also cannot read a kind the table does not hold. Say whether the
table stores the kind, or whether the check gets it again from the coordinate's type and the rule
drops it.

> Author: the kind (now `location`, spelled as `graphql_directive_location` spells it) lives on the
> landing view. Both the rule and the defect arm read that view. The rule projects
> `(graph_name, coordinate, flag)`, which matches the table, so the stage pair compares cleanly.

Not blocking: every other symbol, test and relation the spec names exists as named. That includes
`EmittedRegistry.directives` placing any `CONFIGURED` row on its declaring site, extension sites
included (`ElementSite.of`), `unpublish` dropping the bundled vocabulary's definitions, and
`SourceInTwoInputs` (a `LoadingRewrites.Refusal`, raised from `SchemaInputAttribution.build`'s
throw).
