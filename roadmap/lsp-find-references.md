---
id: R818
title: "textDocument/references: find every SDL site that uses a name"
status: Spec
bucket: feature
priority: 4
theme: lsp
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# textDocument/references: find every SDL site that uses a name

> The language server answers `textDocument/definition`: put the cursor on a
> name in a `.graphqls` file and the editor jumps to what that name denotes. It
> does not answer `textDocument/references`, the request behind "Find Usages" in
> every editor, so the opposite question has no answer at all. An author who is
> about to rename a table binding, retire a `@service` class, or change a type
> cannot ask "what in this schema uses it?" and has to fall back to text search,
> which cannot tell a `@table(name: "film")` binding from the word `film` in a
> description. This item adds the surface and fills it from the fact store, which
> already holds every fact the answer needs.

The naming here is worth pinning before anything else, because "reverse of jump
to definition" admits two readings and only one of them is this item.

Jump-to-definition leaves SDL: from `@table(name: "film")` it lands in
`FilmTable.java`. The literal reverse of that trip would start in a `.java`
buffer and come back to SDL, and that is **not** this item: the server answers
for `.graphqls` documents, and a Java-side cursor is a different feature with a
different client registration. What editors actually mean by the reverse is
`textDocument/references`: the cursor stays on the same SDL name, and the answer
is every *other* site in the schema that uses that name. So the trip is
SDL-to-SDL, and it fans out where definition converges: one cursor, many
locations, `List<Location>` rather than `Optional<Location>`.

## What exists today

- `GraphitronLanguageServer.initialize` registers five request capabilities
  (`hoverProvider`, `completionProvider`, `definitionProvider`,
  `codeActionProvider`, `inlayHintProvider`) plus pushed diagnostics.
  `referencesProvider` is not among them, and
  `GraphitronTextDocumentService` does not override `references`.
- The definition surface chains three providers with `.or()` inside one store
  read (`StoreRead.DEFINITION`): `Definitions` (cursor inside a directive
  argument, resolving into the consumer's Java tree), `IntraSchemaDefinitions`
  (cursor on an SDL type reference, resolving to the declaring type), and
  `DeclarationDefinitions` (cursor on an SDL declaration name, resolving to the
  Java the model bound it to). Each returns at most one `Location`.
- `LspSurface` enumerates the answering surfaces, and `TriggerDispatch.MATRIX`
  states, per `Trigger` leaf, which surfaces answer it and where the gaps are.
  Cursor classification is therefore already shared: `LspVocabulary.locateAt` +
  `behaviorAt` resolve a cursor to a coordinate and a `Behavior`, and every
  surface switches over the same sealed set.
- The store already holds the reverse index, in two families:
  - **SDL type usage.** `graphql_field` and `graphql_argument` each carry
    `named_type` alongside `source_name` / `source_line` / `source_column`;
    `graphql_implements` and `graphql_union_member` carry the rest of the
    type-reference population. "Who references `Film`?" is a filter on
    `named_type`, not a walk.
  - **Directive-target usage.** The decoded `graphitron_` family is keyed by SDL
    coordinate with the bound target as payload: `graphitron_table`
    (coordinate to table name), `graphitron_field_binding` (to column),
    `graphitron_service` / `graphitron_external_field` / `graphitron_enum` (to
    class and method), `graphitron_field_reference_step` (to FK key). Asking
    "which coordinates bind table `film`?" is a filter on the payload column.
    `graphql_directive_site` gives the position of any application, whatever
    site kind it sits on.

So the work is a surface and a set of reverse-direction reads, not new facts.

## Two questions about positions, and they are not the same question

Every result this surface returns is a position in an SDL file, and positions
here come off the store, which rides the capture cadence rather than the
keystroke. That raises two separate questions, and an earlier draft of this spec
ran them together. **Freshness** asks which cadence a result's line number rides.
**Precision** asks which span within that line a result points at. They have
different answers and are settled separately below.

### Freshness: the whole answer rides the capture cadence, and says so

Decision: **captured positions uniformly, in Slice A and Slice B alike.** No
buffer refresh. The consequence is stated rather than hidden: this surface
answers with the last capture's view of the schema. A usage typed since the last
capture is absent from the list, and a site in a buffer edited since the last
capture reports the line it was captured at.

`IntraSchemaDefinitions` made the opposite call for its own SDL positions, and
its javadoc is explicit that the open-buffer scan is first and authoritative. That
choice does not carry here, and the difference is the argument. There, the buffer
holds the *whole* answer: one declaration, and if it sits in an open buffer the
live parse knows everything the store would have said and more. Here the buffer
holds a *fraction* of the answer, because usages are spread across the workspace
and most of them sit in files nobody has open. Refreshing the fraction that
happens to be open produces a list that is partly live and still missing rows,
which is harder to explain than a list that is uniformly the last capture's. One
cadence, one sentence in the docs.

If the skew proves painful in practice, the escalation is a fresher answer, not
fresher positions: that is a capture-cadence question, and it improves the row
set and the line numbers together. Buffer-refreshed positions remain available as
a narrower fallback, and it is worth knowing the cost before choosing it, since
no coordinate-to-live-node primitive exists today. `DeclarationKind.findDefinition`
resolves a type name to its declaration node and nothing resolves a field,
argument, or directive application, so that arm builds a new primitive rather
than reusing one.

### Precision: which span, and one relation short of an answer

`graphql_*_directive_arg` carries `value_sdl` but **no** source position; the
position lives on the owning `graphql_*_directive` application row. The store can
therefore say "this `@table` application at line 42 binds `film`" but not "the
`film` literal spans columns 19 to 25". `Definitions` never met this, because it
reads positions out of the *target's* `.java` parse rather than out of SDL. This
is a fact about `Definitions` alone: `IntraSchemaDefinitions` reads SDL positions
and did meet the neighbouring freshness question, which is why the two are
separated above.

For Slice B this is cheaper than the paragraph above suggests, because the
decoded relations do not need the join at all: `graphitron_table`,
`graphitron_field_binding`, `graphitron_service`, `graphitron_external_field` and
`graphitron_enum` each carry their own `source_name` / `source_line` /
`source_column`. `graphitron_field_reference_step` is the one exception and
inherits its position from `graphitron_field_reference`.

Decision: **Slice A and Slice B point at the site the store positions**, which is
the declaration row for the type population and the decoded row (or, for a
reference step, its parent) for the directive population. Growing a value-level
position in capture is Slice C, deliberately last, because it is a model change
across five relations and the surface is useful without it.

## Slices

### Slice A: the surface plus the SDL type arm

The reverse of `IntraSchemaDefinitions`, and the arm that needs no new facts.

- `capabilities.setReferencesProvider(true)`; override
  `references(ReferenceParams)` on `GraphitronTextDocumentService`, mirroring the
  definition handler's shape (one span, one store read, one `StoreAnswer` switch,
  empty list when out of budget).
- New `StoreRead.REFERENCES` constant, so the out-of-budget warning names this
  read rather than borrowing the definition one. A references answer fans out
  over several relations where definition does a point lookup, so which
  `StoreAccess` door it goes through (interactive or bulk) is a Slice A decision,
  not an afterthought.
- New `LspSurface.REFERENCES`, and reach stated for it in every
  `TriggerDispatch.MATRIX` row. Two existing guards catch part of this and it is
  worth being exact about which part, because the gap decides the work:
  - `TriggerDispatchMatrixTest.everySurfaceAnswersSomething` iterates
    `LspSurface.values()` and requires a positive answered count, so a
    `REFERENCES` constant named in *no* row fails the build immediately.
  - The unguarded seam is narrower: once **one** row names `REFERENCES` that test
    goes green, and the remaining 20 rows default to `NO_ANSWER` unreviewed. A
    surface that declines 20 of 21 triggers is indistinguishable, to the build,
    from one that meant to.
  - A second seam: `theCursorAndSweepAxesDoNotCross` hand-lists
    `cursorSurfaces = Set.of(COMPLETION, HOVER, DEFINITION)`, so a cursor-keyed
    `REFERENCES` sits outside that guard until somebody adds it by hand.

  Both are closed in this slice rather than left to review discipline. `Reach`
  requires every surface to be named, so an omitted cell is a build failure; and
  the cursor-surface set is derived rather than listed, so a new cursor-keyed
  surface joins that guard on declaration.
- The arm itself: cursor on a type declaration name (`type Film`) or on a type
  reference (`films: [Film!]!`) yields every field and argument whose
  `named_type` is `Film`, every `implements` naming it, and every union member.
  Honour `ReferenceContext.isIncludeDeclaration` for whether the declaration
  sites join the list.
- Tests in the LSP tier, per population, plus one that a type nothing references
  answers with an empty list rather than declining.

### Slice B: the directive-target arms

The reverse of `Definitions`, one arm per `Behavior` leaf, each a filter on the
decoded family's payload column:

- `CatalogTableBinding`: every coordinate bound to the same table.
- `CatalogColumnBinding`: every coordinate bound to the same column.
- `ClassNameBinding` / `MethodNameBinding`: every coordinate naming the same
  class, or the same method on it.
- `CatalogFkBinding`: every `@reference` path step using the same key.
- `NodeTypeBinding`: every `@nodeId(typeName:)` naming the same type, which folds
  into Slice A's type population rather than standing alone.
- `ArgMappingBinding` / `ScalarTypeBinding`: state the verdict, gap or decline,
  in the matrix rather than resolving to nothing silently.

Dispatch is the existing exhaustive switch over `Behavior`, so a new binding arm
forces a references decision at compile time, the property `Definitions` already
has. There is no `SourceAbsent` analogue here: the reverse direction never asks
whether a declaration was positioned, so an empty answer means "nothing uses
this", full stop.

### Slice C (optional, separable): argument-value positions in capture

Add source position to the `graphql_*_directive_arg` relations and populate it in
capture, then narrow every Slice B result from the application to the value span.
Independently useful: diagnostics on directive argument values want the same
column, and it removes the open-versus-closed precision question entirely.

## User documentation (first-client check)

Draft of what the manual says when this ships, written first because a surface
whose behaviour does not read simply is a surface designed wrong.

> **Find usages.** With the language server attached, ask your editor for
> references (Find Usages) on a name in a `.graphqls` file and it lists every
> other site in the schema that uses it. On a type name you get every field and
> argument declared with that type, every `implements`, and every union member.
> On a directive value you get every coordinate bound to the same thing: the
> other types mapped to a table, the other fields bound to a column, every
> coordinate naming a service class or method.
>
> Two things to know about the answer. It is the last capture's view of your
> schema, so a usage you typed a moment ago appears once `graphitron:dev` has
> picked the file up, and a result in a file you have been editing points at the
> line it was captured at. And a result lands on the directive application rather
> than on the value inside it, so the editor highlights `@table(name: "film")`
> and not the word `film`.

Where this lands, and the sentences that go stale on the way.
`docs/manual/how-to/dev-loop.adoc` spells the surface list out three times, once
per context (client setup, why to attach an editor at all, and the IntelliJ
walkthrough), each reading "diagnostics, hover, completion, and
go-to-definition"; all three become wrong when this ships.
`docs/architecture/how-to/dev-loop-internals.adoc` carries the same list once in
its LSP-server bullet, and separately names the surfaces that resolve through the
store, which the new one joins. Grep the phrase rather than trusting these
locations; the point is that the list is spelled out in prose in four places and
none of them is generated.

## Open questions for the Spec review

1. Does a references answer belong on the interactive `StoreAccess` door, given
   it fans out over more relations than any other cursor-keyed read?
2. Scope check: field-name and enum-value declaration names as reference
   subjects (who uses this field?) are deferred out of Slice A and B. Confirm
   that is the right cut, or fold the field case into Slice B.

Two questions the first review round settled, recorded here so they are not
reopened: the freshness and precision decisions above, and tightening `Reach`
plus deriving the cursor-surface set, which are now Slice A work rather than a
Ready-gate choice.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested

Reviewer session `session_01BodkET7NMt4McBLg1C55Dg`, 2026-08-24.

The goal reads clearly and the naming section earns its place: an author with the
language server attached gets Find Usages on SDL names, so before renaming a
table binding or retiring a `@service` class they can see every schema site that
uses it instead of grepping for a word that a description might also contain.
Every symbol, relation and column the spec names exists as named, the matrix
really has 21 rows, there really are five `graphql_*_directive_arg` relations
carrying `value_sdl` and no position, and the plan extends existing shapes (a new
`LspSurface` constant, a new `StoreRead` constant, readers under `facts/`, one
override on the text document service) rather than standing a parallel mechanism.
Three findings, in descending weight.

**Finding 1 (question 2, architectural fit): Slice A has no freshness policy,
and the section that would have set one rests on a false premise.**

"The one asymmetry the facts impose" says "Definition never met this, because it
reads positions out of the *target's* `.java` parse, not out of SDL." That is true
of `Definitions` and false of `IntraSchemaDefinitions`, which is the provider
Slice A calls itself the reverse of. `IntraSchemaDefinitions` reads SDL positions
and its javadoc states the policy explicitly: "The open-buffer scan stays first
and authoritative: a type being edited resolves to its live tree-sitter span, not
the position the last capture recorded", with `SdlDeclarations`' captured sites as
the fallback. The reason is in `Workspace`'s own javadoc: the store rides the
capture cadence, not the keystroke, so "what a buffer shows between captures is
the last capture's judgement of it".

Slice A builds its entire result list out of `graphql_field`, `graphql_argument`,
`graphql_implements` and `graphql_union_member` captured `source_line` /
`source_column`. Between captures, every result in a file the author has edited
points at a stale line. That is the load-bearing failure mode for find-usages
ahead of a rename, and it is a different failure from the one the position fork
discusses: the fork weighs *precision* (application position versus value span)
while this is *freshness* (captured line versus live buffer line). Collapsing the
two into one section is what lets the recommendation argue against buffer
awareness on precision grounds without noticing that the neighbouring provider
already adopted it on freshness grounds. The same question applies to Slice B,
whose positions come from the same captured columns.

What would satisfy this: state a freshness policy for Slice A and Slice B, as its
own decision separate from the precision fork. Accepting captured positions
uniformly is a defensible answer (diagnostics accept exactly that skew), but it
has to be argued rather than inherited, because a wrong squiggle in the file you
are looking at costs less than a jump to the wrong line in a file you are not.
Whichever arm is chosen, drop or repair the "Definition never met this" sentence,
since the position fork's recommendation currently leans on it.

**Finding 2 (question 1, a checkable claim that does not hold): the dispatch
matrix trap is real but not the trap the spec describes.**

Slice A says adding the enum constant alone "would silently record 'declines
everything' for all 21 rows without failing a single test."
`TriggerDispatchMatrixTest.everySurfaceAnswersSomething` ("no registered surface
is inert") iterates `LspSurface.values()` and asserts `answeredBy(surface).size()`
is positive for each, so a `REFERENCES` constant with no row naming it fails the
build on the first run. The genuinely unguarded hazard is narrower: once *one*
row names `REFERENCES` that test goes green, and the remaining 20 rows default to
`NO_ANSWER` unreviewed. There is a second unguarded seam the spec does not
mention: `theCursorAndSweepAxesDoNotCross` hand-lists
`cursorSurfaces = Set.of(COMPLETION, HOVER, DEFINITION)`, so a cursor-keyed
`REFERENCES` sits outside that guard until somebody adds it by hand.

This is a finding rather than a nit because open question 2 asks the Ready gate to
decide whether to tighten `Reach` on the strength of the claim, and the claim is
what makes the choice look urgent. Answering question 2 as reviewer: yes, tighten
it, and derive the cursor-surface set rather than listing it. But the hazard
paragraph needs restating first so the guard is justified by the case it actually
closes.

**Finding 3 (question 1, lightest): no user-documentation draft, and three
sentences already in the manual go stale.**

`roadmap/workflow.adoc`, "Item file conventions", requires a plan with a
user-visible surface to carry a user-docs draft as the first client of the design,
and names the LSP plan's own `## User documentation (first-client check)` section
as the canonical example. A new registered editor capability is such a surface,
and the observable behaviour has author-facing choices in it (what
`includeDeclaration` does, whether a result lands on the directive application or
the value, what an empty list means). Concretely,
`docs/manual/how-to/dev-loop.adoc` enumerates the surfaces in prose three times
("diagnostics, hover, completion, and go-to-definition", lines 25, 94 and 146);
each becomes wrong when this ships. A short draft section naming the new surface
and where those sentences change is enough.

**Non-blocking, for the author's use rather than a gate condition.** The decoded
`graphitron_` relations already carry their own `source_name` / `source_line` /
`source_column` (`graphitron_table`, `graphitron_field_binding`,
`graphitron_service`, `graphitron_external_field`, `graphitron_enum`), so position
option (1) needs no join back to `graphql_*_directive` at all;
`graphitron_field_reference_step` is the one exception and inherits its position
from `graphitron_field_reference`. That makes the recommended arm cheaper than the
spec's framing suggests.

