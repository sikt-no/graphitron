---
id: R307
title: "Retire stale @record references: error messages recommending the dead directive and @record-as-jargon vocabulary"
status: In Progress
bucket: cleanup
priority: 5
theme: legacy-migration
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Retire stale @record references: error messages recommending the dead directive and @record-as-jargon vocabulary

The `@record` directive is **DEPRECATED and IGNORED** (`directives.graphqls:288-297`):
it binds no type to a Java class and drives no behaviour, declared only so existing
schemas keep parsing. A reachable type carrying it gets a build warning telling the
author to remove it. The generator honours this fully: the only live reads of the
directive name (`DIR_RECORD`, `BuildContext.java:78`) are the schema-declaration
assert (`GraphitronSchemaBuilder.java:561`), the warning emitter
(`TypeBuilder.emitDirectiveIgnoredWarnings`, `:337`), and `readRecordClassName`
(`:403`), which reads the `className` arg *only* to phrase the warning. Nothing
reads `@record` to drive binding.

The residue is everything else still mentioning `@record`, in two flavours, neither
of which is the deprecation machinery:

1. **Error messages that tell authors to *add* `@record`.** Five rejection strings
   steer authors toward the dead directive; following the advice produces a type the
   build then warns them to remove, and two of the five use an argument form
   (`@record(class: ...)`) that was never valid (the arg is `record: ExternalCodeReference`
   with a `className` field):
   - `MutationInputResolver.java:184` ("author a carrier with `@record(record: {className: ...})`")
   - `FieldBuilder.java:5057` ("back the parent with a typed jOOQ TableRecord (`@record(record: {className: ...})`)")
   - `SourceRowDirectiveResolver.java:448-449` ("declare a backing class via `@record(record: {className: ...})`")
   - `FieldBuilder.java:4991` (`@record(class: ...)`, bogus arg)
   - `FieldBuilder.java:5071` (`@record(class: ...)`, bogus arg)

   These are actively wrong: they should instead point authors at the reflected
   backing path (a producing `@service` return type, `@table`, `@tableMethod`, a
   parent-accessor chain, or `@sourceRow` for the batch-key lift), since that is what
   actually drives the binding now.

2. **`@record` used as jargon for "record-backed type."** Pervasively, comments,
   javadoc, rejection text, and at least one sealed-type label use "@record" as
   shorthand for a Pojo / JavaRecord / jOOQ `Record` / `TableRecord`-backed type:
   "@record parent", "@record child", "@record-element", "@record-typed parent",
   "a @record type". Sampling: `FieldBuilder` (854, 864, 868, 3422, 3482, 3701,
   4204, 4318/4324, 4423, 4791, 4943, 4986, 5020, 5028, 5054, 5056, 5068, 5093,
   5113, 5203), `EntityResolutionBuilder.java:209-212` (literally returns the string
   `"a @record type"`), `MutationField`, `Mapping`, `OutputField`, `FetcherEmitter`,
   `RecordBindingResolver`, `ClasspathScanner`, `BuildContext.java:642`,
   `GeneratorUtils.java:230`, `ServiceCatalog.java:927`, `MutationInputResolver`
   (208, 211, 212), `TypeBuilder` (640, 1130), `TypeBackingShape.java:125`,
   `GraphitronSchemaBuilder.java:369`, `SourceRowDirectiveResolver.java:444`. Since
   R96/R276 moved binding to reflection, this vocabulary is a misnomer: it reads as
   if a directive creates the binding when nothing does, which is exactly the
   confusion the deprecation was meant to remove.

The production-shaped `graphitron-sakila-example/schema.graphqls` carries no applied
`@record` (only comments, some explicitly noting "@record-typed parents no longer
exist (R276)"). Applied `@record` survives only in test-fixture SDL, in two shapes:
some fixtures exercise the deprecation-warning path directly (e.g.
`R96RecordBindingPipelineTest:85`, `GraphitronSchemaBuilderTest:4165`, both asserting
"the @record directive is ignored"); the rest is legacy binding-hint decoration on
reachable types in fetcher/shape fixtures (e.g. `FetcherPipelineTest`,
`SingleRecordPayloadPipelineTest`, `GraphitronSchemaClassGeneratorTest:464`). All
applied `@record` in fixture SDL stays; removing the directive itself is out of scope
(below). What this item *does* scrub from test source is the `@record`-as-jargon
vocabulary in comments, section labels, and assertion strings (see Scope).

## Scope

- **Rewrite the five recommend-`@record` rejection messages** to point at the
  reflected-backing path instead of the dead directive. Update any pipeline/unit
  tests asserting on the old message text.
- **Rename the `@record`-as-jargon vocabulary** to "record-backed" (use this single
  term consistently; "class-backed" / "reflected-backing" were considered and dropped
  in favour of the existing dominant phrasing), across comments, javadoc, rejection
  text, and the `EntityResolutionBuilder` sealed label. Where a message names the *type
  variant*, prefer the variant name (Pojo / JavaRecord / JooqRecord / JooqTableRecord)
  over "@record".
- **Extend the vocabulary scrub to test source.** Test-fixture comments, section
  labels (e.g. the `FetcherPipelineTest` "@record parent" headers,
  `GraphitronSchemaBuilderTest:2105`), and assertion strings that pin the renamed
  message/jargon text (`EntityResolutionBuilderTest:294,337` "is classified as a
  @record type"; `RejectionRenderingTest:66,69` and `GraphitronSchemaBuilderTest:2160`
  "@record-typed parent") get the same "record-backed" treatment, in lockstep with the
  main-source rename so the build stays green. Exceptions that stay verbatim: the
  deprecation-warning fixtures and any assertion on the warning's own "the @record
  directive is ignored" wording, and the applied `@record` directive in fixture SDL
  (directive removal is out of scope).
- **Leave the deprecation machinery untouched**: the `directives.graphqls`
  declaration, the `DIR_RECORD` assert, `emitDirectiveIgnoredWarnings`,
  `readRecordClassName`, and the warning's own "remove the `@record` directive"
  wording all stay, and the test fixtures that apply `@record` to exercise the
  warning stay.

## Out of scope

- Removing the `@record` directive declaration entirely (a separate, later
  hard-removal once schemas have been scrubbed; this item is wording/vocabulary only).
- The legacy modules at the repo root.

## Done when

- No rejection/error message recommends authoring `@record`; the replacement advice
  names the reflected-backing mechanism. No message uses the invalid `@record(class:)`
  form.
- "@record" no longer appears as a label for a record-backed *type* anywhere in main
  or test source (comments, javadoc, messages, section labels, assertion strings);
  remaining mentions are confined to the deprecation machinery, the warning-path
  fixtures and their assertions, the applied `@record` directive in fixture SDL, and
  documentation of the directive itself.
- Full pipeline build green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`),
  including any updated message-assertion tests.
