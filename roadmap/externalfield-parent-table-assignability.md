---
id: R646
title: "Enforce @externalField helper parameter assignability against the parent table"
status: In Review
bucket: architecture
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Enforce @externalField helper parameter assignability against the parent table

`ServiceCatalog.reflectExternalField` takes a `parentTableClass` argument and documents its
contract as "the method must be `public static`, take exactly one parameter assignable from the
parent's jOOQ `Table<?>` class, and return parameterised `org.jooq.Field<X>`". The static and
return-type halves are enforced. The assignability half is not: the parameter check is
`org.jooq.Table.class.isAssignableFrom(p.getType())`, which admits *any* jOOQ table, and
`parentTableClass` is never read.

The consequence is a generated-code compile failure rather than a located rejection. A helper
declared `public static Field<Boolean> isEnglish(Film film)` referenced from an `@externalField`
on a type backed by a different table classifies clean, and `ProjectionUnitRenderer`'s
`SelectTerm.HelperCall` arm emits `FilmExtensions.isEnglish(table)` into that type's `$project`
unit with the wrong table type. The consumer sees a javac error inside generated sources with no
line back to the SDL that caused it, which is the failure mode the classify-time reflection checks
exist to prevent.

Reachable today at ordinary depth: two `@table` types on different tables can each declare an
`@externalField` naming the same helper. Found while specifying the nested-depth admission of
`ComputedField` (`roadmap/nested-depth-projected-reference-and-computed-leaves.md`), which makes it
materially easier to hit: a plain-object nesting type shared across two `@table` parents carries
*one* SDL declaration served by both parents, so the divergence needs no duplicated SDL to appear.

## The invariant

The check has an objectively correct form, because the emitted call site is javac's own rule. The
helper call is rendered by `ProjectionUnitRenderer`'s `SelectTerm.HelperCall` arm as
`<Helper>.<method>(table).as(...)` inside a `$project` unit whose signature is
`$project(Map<String, List<SelectedField>>, <ParentTable> table, DataFetchingEnvironment)`, and
that parameter's type is rendered from `TableRef.tableClass()`, which is
`ClassName.get(entry.table().getClass())` on the live catalog table. So the invariant is: *the
helper's sole parameter type must accept an argument statically typed as the parent's generated
jOOQ table class*, and the class the check compares against must be the same one the `$project`
signature is rendered from, not a re-derived name.

That resolves the direction question the problem statement left open: assignability, not equality,
and in the direction that keeps a helper widened to `Table<?>` working.

The enforcer is deliberately not a literal `isAssignableFrom` call, because assignability needs a
live `Class<?>` for the parent and the model side already carries the two facts that answer the
question. Both layers are value comparisons on `TableRef`:

**Layer 1 (table identity).** The negation of the invariant is "the parameter is typed on a
*different* catalog table", which the catalog answers by class identity:
`ctx.catalog.findTableByClass(p.getType())` is empty for `org.jooq.Table`, `TableImpl`, and any
non-catalog class (all of which accept the parent, so admit), and present exactly when the
parameter names a generated table, in which case that entry must denote the parent's table.
`findTableByClass`'s javadoc states the exactness ("class identity maps to exactly one catalog
entry across the whole schema set … wildcard types and non-table classes are caller
responsibility"), and the same-table comparison goes through `TableRef.denotesSameTableAs`, which
names itself the model-side identity home for this question and says not to grow a third
mechanism. That also gets the multi-schema case right for free: two same-named `Widget` tables in
different schemas are distinct `tableClass` values.

**Layer 2 (record type).** When the parameter's generic type is `X<R>` with `R` a concrete `Class`,
require `ClassName.get(R).equals(parentTable.recordClass())`. `TableRef` already carries
`recordClass` (`TableEntry.recordClass()` is `ClassName.get(table.getRecordType())`, populated by
`toTableRef`), so this needs no reflection on the parent at all. `Table<FilmRecord>` and
`TableImpl<FilmRecord>` pass on a `film` parent; `Table<ActorRecord>` and `Table<Record>` are
rejected, which is again exactly what javac does at the emitted call, since a generated `Film`
implements `Table<FilmRecord>` and nothing else.

The two layers are one invariant at two erasure levels and both produce the same generated-code
javac failure when violated. They are ordered, and layer 1 carries the non-generic case alone: a
parameter typed plainly `Film` has no `ParameterizedType` for layer 2 to inspect. The existing
`org.jooq.Table.class.isAssignableFrom(p.getType())` check stays in front of both as the "is this
a table at all" gate with its own message.

## Where the enforcer is looser than the invariant

Every gap fails *open*: the check admits, and javac at the `graphitron-sakila-example` compile is
the backstop. None of them can produce a false rejection, which is the asymmetry to preserve.

* A parameter typed on a generated table class from a codegen output the catalog does not hold (a
  foreign tables jar on the plugin classpath) is invisible to `findTableByClass` and is admitted.
* A hand-written `abstract class MyBase<R> extends TableImpl<R>` used as the parameter type is
  admitted, which is correct: it genuinely accepts the parent table.
* Layer 2 skips wildcards (`Table<?>`), raw `Table`, and type variables. That includes a
  concretely-bounded variable (`<R extends ActorRecord> Field<X> h(Table<R> t)`), which javac
  rejects at the emitted call while this check admits it. Accepted: closing it means bound
  analysis, for a signature nobody writes by accident.

## Implementation

`ServiceCatalog.reflectExternalField`: widen the unread `ClassName parentTableClass` argument to
the parent's `TableRef` and read it. `TableRef` carries both facts the layers need (`tableClass`
for identity via `denotesSameTableAs`, `recordClass` for layer 2), so nothing has to be resolved,
loaded, or re-derived: no live jOOQ handle crosses a class boundary, no `Class.forName`, no
lookup that can miss, and therefore no skip-the-check branch to get wrong. Add layers 1 and 2
after the existing `Table`-subtype check, each returning `Rejection.structural(...)`. Layer 1's
catalog side is `ctx.catalog.findTableByClass(p.getType())`, which `ServiceCatalog` can reach
directly (it already holds `ctx`), minting the comparison ref with
`entry.toTableRef(entry.table().getName())`.

`ExternalFieldDirectiveResolver.resolve`: pass `parentTable` instead of `parentTable.tableClass()`.
That is the whole caller-side change, and it keeps the resolver free of `org.jooq` imports.

Two constraints this shape is protecting, both worth stating because the obvious implementations
break them:

* **The raw-handle containment boundary.** Live `org.jooq` objects stay behind `JooqCatalog`;
  `ServiceCatalog` deliberately never holds a `Table` instance (it fully-qualifies
  `org.jooq.Table.class` at each use), and `ExternalFieldDirectiveResolver` is a pure
  `TableRef` to `Resolved` projector with no jOOQ imports at all. Threading a live `Table<?>`
  through either of them to read `getClass()` / `getRecordType()` would export the handle to
  answer a question the model refs already answer.
* **No third same-table mechanism.** `TableRef.denotesSameTableAs` is the documented model-side
  identity home and says so in its javadoc. Comparing raw `Class` objects, or `ClassName`s by
  hand, past the capture boundary is the third mechanism it forbids.

Javadoc at both ends is currently ahead of the code and becomes true rather than needing a rewrite:
`reflectExternalField`'s contract paragraph ("one parameter assignable from the parent's jOOQ
`Table<?>` class") and `resolve`'s claim that the parent ref "gates the `reflectExternalField`
parent-table-class invariant". Both stay; the contract paragraph gains the enforcement shape and a
pointer to the fail-open residuals above.

Message shape, mirroring its siblings in the same method:

```
method 'rating' in class 'no.sikt.graphitron.rewrite.TestExternalFieldStub' takes parameter type
'Film', which does not accept the parent table 'actor' (jOOQ class
'no.sikt.graphitron.rewrite.test.jooq.tables.Actor'); type the parameter as the parent's table
class or widen it to org.jooq.Table<?>
```

The remedy is in the message because the two fixes are the whole option set. Layer 2's message
follows the same shape and names the record type instead of the table class.

## Corpus scan, done

Every `@externalField` reference in the reactor already satisfies the tightened check, so this is
expected to be corpus-neutral:

* `TestExternalFieldStub` (classifier tier) types every method on `Film`, and every fixture SDL
  referencing it declares `type Film @table(name: "film")`: the `ComputedFieldCase` rows in
  `GraphitronSchemaBuilderTest`, the `reference-and-computed` example in `ClassifiedCorpus`, and
  the two `@externalField` schemas in `AuthoredClaimConflictsTest`. `wrongParam(String)` and
  `notAField(Film)` exercise other rejections and are unaffected (`wrongParam` still fails the
  existing `Table`-subtype gate, ahead of the new layers).
* `FilmExtensions.isEnglish(Film)` backs `Film.isEnglish` on `@table(name: "film")`;
  `InventoryExtensions.{filmRef,filmCardData,filmCardDataMaybeMissing}(Inventory)` back the three
  `Inventory` fields on `@table(name: "inventory")`.
* `ComputedFieldValidationTest` constructs `ComputedField` model objects directly and never
  reaches reflection, so it is out of the blast radius.

No existing fixture uses a widened (`Table<?>`) or parameterised (`Table<FilmRecord>`) helper
parameter on this path, so layer 1's and layer 2's accept-supertypes behaviour is not witnessed by
the current corpus and needs new coverage. Confirm corpus-neutrality with a full
`mvn install -Plocal-db` rather than trusting this list.

## Tests

Classifier tier, new rows on `GraphitronSchemaBuilderTest.ComputedFieldCase`. The rejection row
needs no new stub method: reusing `rating(Film)` from an `Actor` parent *is* the reachable
two-parents-one-helper case the problem statement describes.

* `PARENT_TABLE_MISMATCH`: `type Actor @table(name: "actor")` with
  `computedRating: String @externalField(reference: {className: "...TestExternalFieldStub", method: "rating"})`
  classifies to `UnclassifiedField` / `AUTHOR_ERROR`, with the reason naming both the parameter
  type and the parent table.
* `PARENT_TABLE_WIDENED`: a new stub method `public static Field<String> anyTable(Table<?> table)`
  referenced from the `Film` parent classifies to `ComputedField`. This is the over-rejection
  guard for layer 1.
* `PARENT_TABLE_PARAMETERISED`: a new stub `Field<String> filmRecordTable(Table<FilmRecord> table)`
  classifies clean from the `Film` parent (layer 2 accept), and a new stub
  `Field<String> actorRecordTable(Table<ActorRecord> table)` is rejected from the `Film` parent
  (layer 2 reject). Two rows.

Compilation tier, one fixture. The classifier rows above assert that a widened helper *classifies*,
which is not the claim that matters: the claim is that a widened helper still emits a `$project`
body that compiles, and the `graphitron-sakila-example` compile is the named backstop for exactly
that class of claim. Add a `Table<?>`-parameterised helper to the existing `FilmExtensions` (whose
`@table(name: "film")` parent is already wired, so no new SDL type) plus the one `@externalField`
field pointing at it. A widened helper cannot use typed column accessors, so the body should be
something a bare `Table<?>` can produce; that limitation is itself the reason the widened form is
rare, and worth a sentence in the how-to. Note for the implementer: neighbouring fields in that
schema carry `# R<n> fixture:` comments, a convention that predates the current javadoc rule.
Describe the new fixture's purpose without an item id.

No new pipeline or execution coverage. The item removes emissions rather than adding any, the
rejection's located-diagnostic plumbing (`UnclassifiedField` to the validator surface) is shared
with the existing `@externalField` rejections and already covered, and once the widened form
compiles there is nothing about its runtime that differs from the concrete form.

## User documentation

The directive already documents the rule this item enforces ("The static method's sole formal
parameter is the parent table's jOOQ class"), so the delta is sharpening, not new surface.

* `docs/manual/reference/directives/externalField.adoc`, Constraints: the sole parameter must be
  the parent table's jOOQ class or a supertype of it (`org.jooq.Table<?>`), and a helper typed on a
  different table is rejected at build time with the parent table named.
* `docs/manual/how-to/computed-fields.adoc`, the "sole parameter is the parent table's jOOQ class"
  bullet under *Write the Java method*, plus the matching Constraints bullet: add the
  cross-parent consequence, since that is the mistake the check catches. One helper cannot serve
  two `@table` parents on different tables; write one per table, or widen the parameter to
  `Table<?>` and address columns by name.
* No `diagnostics-glossary.adoc` entry. The glossary's enumerated sections are gated by
  `DiagnosticsDocCoverageTest` on `RejectionKind` and `Rejection.AttemptKind` values, and this is
  an `AuthorError.Structural` message, which that page explicitly declares non-enumerable. The
  editorial *Named structural errors* section is for errors whose remedy is not in the message;
  this message carries both remedies.

## Scope boundaries

**The `@condition` analogue stays out, filed separately.** `ServiceCatalog.reflectTableMethod` has
the identical looseness on the `@condition` path (it accepts any `Table<?>`-typed parameter as the
reserved table slot and never compares it to the anchor), and the emitted call passes a concretely
typed local (`InputFieldConditionFixtures.addressDistrictAlberta(table_fkt0_0, addressId)`), so the
same javac failure is reachable. It is materially more work than this item: a condition attaches at
several coordinates, and the reference-path form takes two table parameters
(`customerToAddress(Table<?> customerTable, Table<?> addressTable)`), so "the parent table" needs
defining per arm before a check can exist. Filed as its own Backlog item.

**LSP completions stay loose.** `ExternalFieldCompletions` narrows the method list by arity and
`Field` return type only, and its javadoc already declares the table-parameter confirmation out of
scope. LSP *diagnostics* pick the new rejection up for free, since they read the real
classification rather than re-deriving it.

**Multi-parent is not this item's problem, but this item is multi-parent's precondition.** The
check runs per classify call against the parent whose table that coordinate projects into, which
is well defined today because `validateNestingParentCompat` still gates multi-parent
`ComputedField` (see `roadmap/nested-depth-projected-reference-and-computed-leaves.md`). When
multi-parent is admitted, the per-anchor unit minting that item describes means the check runs once
per parent, which is the behaviour that makes admission safe; that is why the nested-depth item
records this one as a precondition.

**No dependency edge with the `@service` fold.** `roadmap/deprecate-externalfield-fold-into-service.md`
(Ready) moves the computed-field contract behind a return-type dispatch inside `ServiceCatalog`.
Whichever of the two lands second inherits the other: if the fold lands first, its new
computed-contract reflection entry must thread the parent table through in place of today's unread
argument; if this item lands first, the fold's dispatch keeps passing it. A cross-note goes into
that item's body so its implementer does not re-cement the loose form.

## Verification

* `mvn install -Plocal-db` for the corpus-neutrality claim; a scoped run cannot support it.
* `mvn test -pl :graphitron -Plocal-db -DexcludedGroups=execution` for the inner loop.
