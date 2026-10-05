---
id: R980
title: "An authored redeclaration of a built-in directive fails the whole capture instead of drawing a diagnostic"
status: Spec
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-09-28
last-updated: 2026-09-29
---

# An authored redeclaration of a built-in directive fails the whole capture instead of drawing a diagnostic

## Goal

A schema that declares a directive graphql-java already ships, such as `directive @oneOf on
INPUT_OBJECT`, captures normally, and the fact store (the H2 database `graphitron:dev` and the
language server read the schema from) holds the author's declaration in place of the built-in one,
which is what graphql-java does when it builds the schema. Today a first capture of such a schema
fails outright as an infrastructure error, so `graphitron:dev` has no store and the author's editor
loses every diagnostic instead of getting none about a line that is legal. A later capture over a
store that already holds the row does not fail, but it can leave the built-in's shape standing
where the author's declaration should be.

The minimal pair, from the field report and the federated sakila fixture:

```graphql
# Captures today, and after this item.
input FilmOneOfFilter @oneOf {
  filmId: Int
  title: String
}
```

```graphql
# Fails the whole capture on a cold store today. Captures after this item, and the store's
# @oneOf is this declaration, at this line.
directive @oneOf on INPUT_OBJECT

input FilmOneOfFilter @oneOf {
  filmId: Int
  title: String
}
```

Authors write the second form on purpose. Federation's `_service.sdl` printer drops the definitions
of built-in directives while keeping their applications, and Apollo composition then rejects the
subgraph with "Unknown directive @oneOf"; the comment above `Query.filmsByOneOf` in
`graphitron-sakila-example/src/main/resources/graphql/federated-schema.graphqls` records the same
fault from graphitron's side. A consumer whose schema also feeds other tooling declares the
directive in the SDL itself.

A redeclaration that contradicts how the schema uses it (narrowing `@deprecated` to `ENUM_VALUE` and
then applying it to a field) is still an error, and graphql-java reports it with a position. That
report reaches the editor through `graphql_schema_problem`, the store's relation for what
graphql-java refused, only if the capture that writes it survives; this item is what lets it
survive.

## Decision: tolerate, with the authored declaration winning

Measured against graphql-java 25.0, the version the root pom pins, on 2026-09-29, by parsing and
building an executable schema over each redeclaration:

- Each of the five specified directives (`@deprecated`, `@include`, `@oneOf`, `@skip`,
  `@specifiedBy`) redeclared once builds without error.
- The authored declaration replaces the built-in. `directive @oneOf on INPUT_OBJECT | OBJECT`
  builds a `@oneOf` valid on `OBJECT` and `INPUT_OBJECT`; `directive @deprecated(reason: String) on
  FIELD_DEFINITION` builds a `@deprecated` valid on `FIELD_DEFINITION` alone, with a nullable
  `reason` where the built-in's is `String!`.
- A redeclaration that a use contradicts is refused with a positioned error: `'f' [@1:14] tried to
  use a directive 'deprecated' in the 'FIELD_DEFINITION' location but that is illegal`.
- Declaring one directive twice, in one document or across two merged registries, is refused with
  `tried to redefine existing directive 'oneOf'`. That is the authored-against-authored collision
  the anchors already settle by rank, oldest declaration first, and graphql-java's refusal lands in
  `graphql_schema_problem` beside them.

So refusing would make graphitron reject a schema that graphql-java, graphitron's own generator
pipeline and federation practice all accept. Tolerating is what the engine does, and the store
takes the engine's rule: a specified directive stands in the anchors only where no document of the
graph declares that name, and a document that declares it replaces it. No new diagnostic is minted;
the positioned refusals above are graphql-java's and already have a relation.

## The fault

`GraphQLAstCapture` writes the `graphql_` anchors (the relations that state what the corpus
declares, one row per coordinate, derived from the per-document `graphql_ast_` entries) once every
document has been transcribed. Three of its statements add the specified directives to what the
documents declare with `UNION ALL` and no rank between the two arms:

- `elements`, into `graphql_element` keyed `(graph_name, coordinate)`: the rank settles authored
  candidates against each other, then the specified directives are appended outside it.
- `directiveElements`, into `graphql_directive_element` keyed `(graph_name, directive_name)`:
  a distinct select over authored definitions, the specified directives appended.
- `directives`, into `graphql_directive` keyed `(graph_name, directive_name)`: the ranked authored
  declaration, the specified directives appended with a null site, `repeatable` false and no
  description.

An authored `@oneOf` therefore arrives twice in each upsert (`onDuplicateKeyUpdate`, which jOOQ
renders as a `MERGE` on H2). On a cold store both source rows insert and the key refuses the
second. `elements` runs first in `anchor`, which is why the field report's stack names it; fixing it
alone moves the failure to `directiveElements`. On a warm store both source rows update the
existing one and the one applied last stands. For `graphql_directive` that can be the specified row,
and `directiveLocations` and `directiveArguments` reach a declaration through `graphql_directive`'s
site, so a redeclared directive's locations and arguments can drop out of the anchors with nothing
failing.

The class comment on `GraphQLAstCapture` already names this shape: "a statement that unions with
`ALL` and then does not rank is a statement that has not decided anything, and the key will decide
for it by refusing a row". The comments beside the specified-directive arms in `elements` and
`directives` say a document redeclaring one "is refused before any of this", which is not so for
directives: `GraphQLAstEntries.directives` transcribes every authored definition, built-in name or
not.

The specified scalars do not have the fault. `GraphQLAstEntries.scalars` filters the five
specification scalar names out of the transcription, so no authored row can meet the specified arm
in `elements` or `typeElements`.

## Implementation

All in `graphitron-model`'s `GraphQLAstCapture`; no DDL change and no new relation.

- A per-graph table expression for the specified directives the graph's documents do not declare:
  `SPECIFIED_DIRECTIVES` with a `NOT EXISTS` against `GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY` on
  graph name and directive name. It takes the graph, so it is a method beside the
  `SPECIFIED_DIRECTIVES` constant rather than a second constant; its name is the implementer's.
- `elements`, `directiveElements` and `directives` select their specified arm from that expression
  instead of from `SPECIFIED_DIRECTIVES`. A redeclared name then has exactly one candidate in each,
  the authored one, carrying its site; `directiveLocations` and `directiveArguments` find it through
  `graphql_directive` unchanged. `graphql_directive_element` and `graphql_directive` keep holding
  the same population, because both take the same expression.
- `directiveArgumentElements` reads only authored entries and needs no change.
- The comments beside the three arms, and the `SPECIFIED_DIRECTIVES` javadoc, state the rule instead
  of the refusal that does not happen: a specified directive stands where no document declares it,
  and an authored declaration replaces it, as graphql-java's does. The sentence beside the
  specified-scalar arm in `elements` says a redeclared `String` is "refused"; it is filtered out of
  the transcription, and the sentence says so while the statement is open.

Sweeping needs nothing new. When a document stops redeclaring `@oneOf`, the next reading's specified
arm offers `@oneOf` again with that reading's instant and a null site, and the location and argument
rows the authored declaration justified carry the older instant and are swept.

No reader tells a built-in apart by its null site: nothing outside the DDL reads
`graphql_directive.source_name`. The language server's `DirectiveSurface` reads directive names
only, and `SdlDescriptions` will show the author's description for a redeclared built-in, which is
what the author wrote. No emitter reads these anchors.

## Tests

- `GraphQLAnchorTest`, "an authored redeclaration of a built-in directive stands in its place": one
  document with `directive @oneOf on INPUT_OBJECT`, `directive @deprecated(reason: String) on
  FIELD_DEFINITION` and an input applying `@oneOf`, read into a fresh store. The anchor completes;
  `graphql_element` holds exactly one `@oneOf` and one `@deprecated`, both `DIRECTIVE`;
  `graphql_directive` for each carries the document's source name and line; `graphql_directive_location`
  holds `INPUT_OBJECT` for `@oneOf` and `FIELD_DEFINITION` for `@deprecated` (a built-in has no
  location rows, so any row proves the authored declaration won); `graphql_directive_argument` holds
  `reason` with `type_sdl` `String`; `@include`, `@skip` and `@specifiedBy` still stand with a null
  site. The same corpus read a second time leaves every one of those assertions true, which is the
  warm-store half.
- `GraphQLAnchorTest`, "a built-in stands again when its redeclaration goes away": read the corpus
  above, then a second reading without the two declarations. `@oneOf` and `@deprecated` are back
  with a null site, and their location and argument rows are gone.
- `GraphQLAnchorTest.capturingTwiceRestampsRatherThanDuplicating`: add `directive @oneOf on
  INPUT_OBJECT` to its corpus, so the census over `DERIVED` covers a redeclaration.
- A capture-tier case through `CapturedStore.withCapturedStore`, which drives `ModelCapture`, the
  pass `graphitron:dev` runs: an SDL fixture carrying `directive @oneOf on INPUT_OBJECT` captures
  into a fresh store without throwing. This is the field report's path and the case that fails
  today.

## Out of scope

- Whether an authored `scalar String` should be dropped from the transcription, as
  `GraphQLAstEntries.scalars` does today. It does not fail the capture, and it is a different
  question from the one this item settles.
- A consumer redeclaration that makes graphitron's own bundled directives invalid (narrowing
  `@deprecated` so it no longer admits `ARGUMENT_DEFINITION`, where the bundled vocabulary applies
  it). graphql-java refuses that with a position in the bundled file, which is correct, if unhelpful
  about whose line caused it.

## Other solutions we've considered

- Refuse the redeclaration with a positioned diagnostic. graphql-java accepts it, graphitron's
  generator accepts it, and federation practice writes it deliberately, so a refusal would be
  graphitron inventing an error the engine does not have.
- Filter authored redeclarations out of the transcription by name, as `GraphQLAstEntries.scalars`
  does for scalars. The capture would survive, but the store would then hold the built-in's shape
  while the built schema holds the author's, and go-to-definition and hover would lose the line the
  author wrote.
- Put the specified directives inside each statement's rank with a precedence that sorts after
  every authored site. It reaches the same rows, but the specified arm has no site or modification
  time to rank by, so every rank grows a precedence column to carry a rule that a `NOT EXISTS` states
  directly.

## Seen on

A consumer schema's `schema/features/stable/common/directives.graphqls`, line 6, on 2026-09-28,
reproduced on a cold store with and without the chain-resolution change. The stack is
`GraphQLAstCapture.elements`, from `GraphQLAstCapture.anchor`, from `ModelCapture.capture`. The
violated key is `graphql_element (graph_name, coordinate)`. The specified-directive union arrived
with `1644b314b`.

## Reviewer findings

### Round 1: Spec → Ready, withheld (session_01ShDxSCBoNJzdDFcZSoayF6, 2026-10-05)

The fault analysis, the three statements named, the `NOT EXISTS` shape, the sweep argument, the
reader census and the scalar filter all check out against the tree, and the fix keeps the
`graphql_directive_element`/`graphql_directive` population equality that
`RelationRegistrationGateTest` asserts. Two premises do not hold, and the first one bears on the
design.

1. **graphql-java does not replace `@include` or `@skip`; the Decision's rule holds for three of the
   five.** (Gate question 2: the rule the store adopts is stated as the engine's.) Building an
   executable schema on graphql-java 25.0 over `"mine" directive @include(if: Boolean) on FIELD`
   gives a `@include` with the built-in's description, locations `[FIELD, FRAGMENT_SPREAD,
   INLINE_FRAGMENT]`, argument `if: Boolean!` and no definition node. `@skip` behaves the same way.
   `@deprecated`, `@oneOf` and `@specifiedBy` do take the author's declaration (description,
   locations, argument nullability and source location all the author's). The measurement in the
   Decision tested only `@oneOf` and `@deprecated`, and its first bullet, that all five build
   without error, is true, but it does not show which declaration won. As planned, the store would
   hold the author's `@include` while the built schema holds the built-in one, which is the inverse
   of the divergence the spec rejects under "Other solutions". The author has two ways to go:
   (a) keep the plan and say plainly that the two executable directives are the exception: no
   type-system location can apply them, so the divergence reaches hover and go-to-definition only;
   or (b) follow the engine exactly, which means excluding authored `include`/`skip` from the
   ranked arms of `elements`, `directiveElements` and `directives`, and also from
   `directiveArgumentElements`, or its rows lose their `graphql_directive_argument` partners. The
   spec currently says that statement "needs no change". I lean towards (a), since it costs a
   sentence where (b) costs four exclusions, but the Decision section has to stop claiming that
   all five are replaced either way, and the test list should pin whichever answer is chosen.

2. **A warm store fails too; it does not silently keep the built-in's row.** (Gate question 1: the
   Goal misstates what the consumer sees today.) On H2 2.4.240, the version the root pom pins,
   jOOQ 3.20.11's `onDuplicateKeyUpdate` renders `MERGE INTO … USING (… UNION ALL …) ON … WHEN
   MATCHED THEN UPDATE … WHEN NOT MATCHED THEN INSERT`. With two source rows for one key, the
   cold store fails on the primary key, as the spec says. The warm store also fails, with
   `Merge using ON column expression, duplicate _ROWID_ target record already processed`. So once a
   schema declares a built-in directive, every capture fails, not just the first. The Goal's "A
   later capture over a store that already holds the row does not fail, but it can leave the
   built-in's shape standing", and the warm-store paragraph of "The fault" (the last-applied row
   stands, and locations and arguments drop out with nothing failing), describe something that
   does not happen. That makes the item's case stronger, and the plan and tests stand unchanged;
   the second-read assertion in the first test still covers the warm path, which now fails today
   as well.
