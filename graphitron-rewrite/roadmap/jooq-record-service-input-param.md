---
id: R311
title: "Bind a jOOQ TableRecord @service input param: column-axis @field + @nodeId scalar-key decode"
status: Spec
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Bind a jOOQ TableRecord @service input param: column-axis @field + @nodeId scalar-key decode

A top-level `@service` parameter whose Java type **is** a generated jOOQ `TableRecord`
(the `EndreUtdanningsspesifikasjonsstatusInput @table` -> `UtdanningsspesifikasjonsstatusRecord`
shape) is unsupported on the rewrite. Its SDL input fields name jOOQ **columns** via
`@field(name: "DATO_FRA")` and decode `@nodeId` ids into scalar key columns, but
`InputBeanResolver` only knows how to bind a consumer-authored bean/record by Java-member
name (R200, shipped). The live consumer error is a hard Author rejection:

    parameter 'inputs' ... bean class '...tables.records.UtdanningsspesifikasjonsstatusRecord'
    has no fields matching the SDL input type 'EndreUtdanningsspesifikasjonsstatusInput'

That message **misdiagnoses**: the cause is not "no matching fields", it is "this is a jOOQ
table record, bound on the wrong axis". The build correctly stops (no broken code is emitted),
but the shape needs real support, and on a coincidental property-name match the same path
silently partial-populates or throws an R150-family `ClassCastException` at execution instead
of rejecting.

**The seam, and a correction to R200's framing.** R200's spec claimed this case "never enters
`buildInputBean` at all" because `looksLikeBeanCandidate` rejects `org.jooq.*` classes. That
premise is false for *generated* records: `looksLikeBeanCandidate`
(`InputBeanResolver.java:637-646`) is package-based, and generated records live in the
consumer's `*.tables.records` package, so they pass the gate at `:171`, land on the JavaBean
arm, and reach `bindJavaBean` (which then comes up empty). The decisive classification seam is
in `ServiceCatalog`, not `InputBeanResolver`: `classifySourcesType` (`ServiceCatalog.java:794-832`)
already holds raw `org.jooq.TableRecord` and classifies the *child*-coordinate `List<TableRecord>`
case into `SourceKey.Wrap.TableRecord`, while the *root* coordinate deliberately falls through
to `InputBeanResolver` as `Direct` (the comment at `ServiceCatalog.java:260-267` names this the
"canonical InputBeanResolver shape"). The structural detector `isJooqRecord`
(`InputBeanResolver.java:559-566`) already exists and is wired on the R195 member-leaf path
(`bindField`, `:464`); the root-param path never got it.

**Direction (chosen): support it.** The full design is in [Design](#design) below. In one line:
`ServiceCatalog` (which already holds the raw `org.jooq.TableRecord` reflection type) structurally
detects the root jOOQ-record param and emits a *distinct, total* marker so `InputBeanResolver`
never sees it as a bean candidate; an SDL-aware enrichment step resolves the record's table from
the param's record class, binds each input field on the **column axis** via `@field(name:)` (the
R97 axis read, lifted to the param position) into a generation-ready carrier, and decodes a single
`@nodeId` identity field into the record's own key columns (R195's wire-decode mechanism, expressed
as a decoded column tuple rather than a member-position record leaf). The misdiagnosing "has no
fields matching" outcome is replaced by honest, R195-shaped rejections that surface at validate
time via `UnclassifiedField`.

**Scope boundary (disjoint producer seams, so this is a new item rather than folded into R97).**
R97 (`consumer-derived-input-tables.md`) owns `@table`-on-the-input-*type* -> `TableInputType`
(the `TypeBuilder` column-deprecation axis); none of its consumers is `ServiceCatalog` or
`InputBeanResolver`, and its redesign produces column bindings, not `NodeIdDecodeRecord`-on-a-
record-param, so it neither subsumes nor closes this case. R195 (shipped) handled jOOQ-record
`@nodeId` decode for *members*; this is that mechanism lifted to the *param* position, plus the
column-axis read. R195's changelog deferred exactly this ("top-level `@service` param is a jOOQ
record ... deferred, tangled with R97"); the deferral rationale ("gated out anyway") is now void.
This item owns only the param-IS-a-jOOQ-record half: `ServiceCatalog` root classification, the
`@field`-names-a-column read at the param position, and the `@nodeId` scalar-key decode at the
param position. Filed off the R200 In Review -> Done review (2026-06-15), which surfaced the false
`looksLikeBeanCandidate` gating premise.

## Design

Reviewed against the design principles (principles-architect, 2026-06-15). The forks that shaped
the carrier:

### Model: two new `CallSiteExtraction` arms + two column-axis records

A new generation-ready arm carries the resolved record-build plan, and a separate *total* marker
carries the structural fact the catalog knows before SDL enrichment runs:

```java
// CallSiteExtraction permits, added:
record UnboundJooqRecord(ClassName recordClass) implements CallSiteExtraction {}

record JooqRecord(ClassName recordClass, TableRef table,
                  List<ColumnBinding> columnBindings,
                  Optional<RecordKeyDecode> keyDecode) implements CallSiteExtraction { /* compact ctor below */ }

// nested records (column axis; siblings to FieldBinding, which is member axis):
record ColumnBinding(String sdlFieldName, ColumnRef column) {}
record RecordKeyDecode(ClassName encoderClass, String typeId, List<ColumnRef> keyColumns) {}
```

Why this shape (each point ties to a principle the alternative pushed against):

- **`UnboundJooqRecord` is a distinct, total marker, not an incomplete `JooqRecord`** (per
  "Classification belongs at the parse boundary" + "Narrow component types"). The `InputBean`
  precedent is *not* an incomplete `InputBean`: the catalog emits a fully-valid `Direct`, and
  `enrich` *replaces* it with a fully-valid `InputBean`; no consumer ever sees a partial bean.
  Mirror that exactly. `Direct` is too ambiguous to be the marker here (a scalar arg is also
  `Direct`), so the catalog emits `UnboundJooqRecord(recordClass)`, a zero-extra-payload arm
  carrying the one fact reflection knows ("this root param is a jOOQ `TableRecord`"). `enrich`
  replaces it wholesale with a fully-populated `JooqRecord`. Every `JooqRecord` instance is
  fully resolved (no nullable `table`, no empty-then-filled `columnBindings`). The marker passes
  the "Sub-taxonomies for resolution outcomes" one-line test: it carries a distinct classification
  fact a sibling (`Direct`) cannot express; an *incomplete* `JooqRecord` would carry the same
  information as the finished one but with holes, which fails the test.
- **The `@nodeId` identity decode gets its own column-tuple carrier (`RecordKeyDecode`), it does
  not reuse R195's `NodeIdDecodeRecord`** (per "Wire-format encoding is a boundary concern" +
  "Documentation names only live tests/code"). `NodeIdDecodeRecord`'s javadoc pins an invariant:
  "Produced only by `InputBeanResolver` ... consumed only by `InputBeanInstantiationEmitter` ...
  Any other exhaustive `CallSiteExtraction` switch treats this arm as unreachable-by-construction."
  Reusing it at the param position silently falsifies that sentence, and its `nonNull` / `list`
  fields are documented purely in *member*-axis terms (list-ness lives on the enclosing
  `FieldBinding`, absent here). Per the boundary principle, what survives in the model is not "a
  NodeId decoder" but "these key columns get loaded onto this record's identity"; `RecordKeyDecode`
  says exactly that. The *wire-decode mechanism* is still identical to R195
  (`encoderClass.decodeValues(typeId, nodeId)` → `record.fromArray(values, Tables.T.<keyCol>...)`),
  only the projection target differs (the param record's own key, not a freshly-materialized member
  record).
- **`columnBindings` (SET payload) and `keyDecode` (identity) are orthogonal axes on one record,
  not a god-record** (per "Sealed hierarchies ... separate records orthogonal to the variant
  axis"). This is the same pattern as `SourceKey` carrying `Wrap` + `Reader` + `Cardinality`:
  `columnBindings` always means "columns to set", `keyDecode` always means "the identity columns".
  The smell the principle warns about (one shared accessor whose meaning depends on the variant)
  is absent.
- **`ColumnBinding` is a genuinely different axis from `FieldBinding`, so a new record is right,
  not a clone** (per "Sub-taxonomies"). `FieldBinding` binds to a Java *member*
  (`javaFieldName`, `javaElementTypeName`, a recursive `CallSiteExtraction leaf`); none of that
  applies to a column. `ColumnBinding` carries a *resolved* `ColumnRef` (not a raw `@field(name:)`
  string) per "Generation-thinking": the emitter reaches `table.COL.getDataType()` through the
  resolved `javaName` with no re-parsing. It has no list flag: a scalar column cannot take a list,
  and the absence documents that.

**Compact-constructor invariant on `JooqRecord`** (the table-context watch-point the architect
flagged): `ColumnRef` does not carry its own table (`ColumnRef.java:20`), so the resolver must
resolve every `ColumnBinding.column` and every `RecordKeyDecode.keyColumns` entry against the one
`TableRef` on the record. The compact constructor enforces non-null `recordClass` / `table` and
the at-least-one-binding floor (`columnBindings` non-empty OR `keyDecode` present; an empty input
is rejected, like `InputBean`'s empty-bindings rejection); the "all columns belong to `table`"
fact is a producer guarantee whose hard backstop is the compilation tier (a `Tables.FILM.NOPE`
reference fails javac against the real catalog).

### Classification flow (two boundaries, no new boundary-crosser)

1. **`ServiceCatalog`, structural detection (reflection boundary).** The root-param arg classifier
   (`argExtraction`, called at `:241` for `@service` params) gains a jOOQ-`TableRecord` check: when
   the (scalar, non-list) param type is `org.jooq.TableRecord`-assignable, emit
   `new ParamSource.Arg(new CallSiteExtraction.UnboundJooqRecord(recordClassName), path)` instead of
   `Direct`. This is the same reflection read `ServiceCatalog.classifySourcesType` (`:794-832`)
   already does for the *child*-coordinate `List<TableRecord>` SOURCES case, at a coordinate the
   catalog already inspects (the root fall-through noted at `:260-267`); **no class gains new
   reflection reach**, and the SDL-aware work stays out of `ServiceCatalog` (which has no
   `GraphQLFieldDefinition`). Scope: the singular `TableRecord` param. `List<TableRecord>` /
   `Set<TableRecord>` at root stay on their current path (see Out of scope).

2. **`InputBeanResolver.enrich`, SDL + catalog enrichment (the existing post-processor).** `enrich`
   today rewrites only `instanceof CallSiteExtraction.Direct` head-only arms (`:122`, `:130`). Add a
   branch *before* the `Direct` branch: `if (arg.extraction() instanceof CallSiteExtraction.UnboundJooqRecord marker)`
   → build the `JooqRecord`:
   - Resolve the table from the param's record class via the existing
     `ServiceCatalog.resolveTableByRecordClass(Class)` (`ServiceCatalog.java:61-64`, wrapping
     `JooqCatalog.findTableByRecordClass`) → `TableRef`. (A record class not in the catalog →
     reject: "param record type X is not in the jOOQ catalog".)
   - Walk the SDL input-object type's fields. For each field, the **binding key** is
     `argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName())`, the same column-axis idiom as
     `BuildContext.java:1648-1650` and `InputBeanResolver.bindingKey`. Resolve it to a `ColumnRef`
     via `JooqCatalog.findColumn(table, key)` (case-insensitive, Java-name then SQL-name; no
     special-casing needed). Unresolvable → reject naming the field, the key, and the table, with a
     Levenshtein `candidateHint` over `columnSqlNamesOf(table)` (the house error-quality contract).
   - A field carrying `@nodeId(typeName:)` is the identity field: resolve it through the existing
     `BuildContext.resolveNodeIdRecordDecode(typeName)` (`:2104`), then apply the **lifted
     record-type-mismatch gate** (R195 `InputBeanResolver.java:537-545`, sourced from the param's
     record class instead of a member's): the resolved node table's `recordClass()` must equal the
     param `recordClass`. A foreign-table `@nodeId` is rejected with an R195-shaped message naming
     the param record, the foreign record the `@nodeId` resolved to, and both fixes. Project to
     `RecordKeyDecode(encoderClass, typeId, keyColumns)`.
   - **At most one `@nodeId` field** (two would each claim to decode the record's identity). A
     second `@nodeId` is rejected.
   - Rewrite the param to `new ParamSource.Arg(new CallSiteExtraction.JooqRecord(...), arg.path())`.

   `enrich` already loads classes via `ctx.codegenLoader()` and runs `isJooqRecord` on the R195
   member path, so it stays the natural SDL-aware home; the marker means it never re-derives the
   structural decision the catalog already made (avoiding the "same predicate in two consumers"
   smell).

### Re-projection + emit

3. **`ValueShape` + walker** (`ServiceMethodCallWalker.deriveValueShape`, `:133-176`). Add a thin
   composite arm `ValueShape.JooqRecordInput(ClassName recordClass, ArgPath sdlPath)`, parallel to
   `RecordInput` / `JavaBeanInput`. The walker gets an explicit `case CallSiteExtraction.JooqRecord`
   → `JooqRecordInput` (head-only, so the param is the outer arg). `UnboundJooqRecord` is
   unreachable here by construction (always rewritten in `enrich`) → defensive error like the
   existing `ParameterUnbindable` arms.

4. **Service-call argument expression** (`ServiceMethodCallEmitter.valueShapeExpression`, `:143-148`).
   Add `case ValueShape.JooqRecordInput jr -> compositeHelperCall(jr.recordClass(), …)` →
   `create<Record>(env.getArgument("<arg>"))`, mirroring the `RecordInput` arm.

5. **Helper emitter** (sibling to `InputBeanInstantiationEmitter`, driven by the
   `CallSiteExtraction.JooqRecord` queue collected in `TypeFetcherGenerator` from `callParams()`,
   parallel to the existing `InputBean` helper queue). Emits one deduped
   `private static <Record> create<Record>(Map<String, Object> raw)` per record class:

   ```java
   private static FilmRecord createFilmRecord(Map<String, Object> raw) {
       if (raw == null) return null;
       FilmRecord rec = new FilmRecord();
       // plain @field columns, batched through fromArray so each coerces via its column's
       // DataType/Converter, the same R195 coercion discipline, no deprecated DataType.convert(Object):
       rec.fromArray(new Object[]{ raw.get("title"), raw.get("rentalRate") },
                     Tables.FILM.TITLE, Tables.FILM.RENTAL_RATE);
       // identity (@nodeId) key columns, decoded then loaded, R195's call applied to this record:
       String[] keyValues = NodeIdEncoder.decodeValues("Film", (String) raw.get("filmId"));
       if (keyValues == null || keyValues.length != 1) {
           throw GraphqlErrorException.newErrorException().message("...").build();
       }
       rec.fromArray(keyValues, Tables.FILM.FILM_ID);
       return rec;
   }
   ```

   Statement form, explicit types, named locals (per "Generated code is read and debugged"). The
   two `fromArray` calls touch disjoint column sets; either group may be absent (no plain columns,
   or no identity field). The decode-and-throw block stays visually grouped, the readability reason
   to prefer two `fromArray` calls over one assembled mixed array.

### Validator (mirrors classifier invariants)

All rejections above are typed `Rejection.structural` → `Resolved.Rejected` →
`UnclassifiedField` (`ServiceDirectiveResolver.java:155-158`), which `ValidateMojo` fails on by
default; the same validate-time surface R195's gate uses, stated here explicitly rather than left
implicit. Additionally, a `CallSiteExtraction.UnboundJooqRecord` that *survives* `enrich` (the SDL
arg was not the input-object the catalog assumed) is an unimplemented-branch leak: the walker's
defensive arm records it as a parameter-unbindable error so it fails at build time, never reaching
the emitter. Adding the two new `CallSiteExtraction` arms makes the sealed compiler check flag every
exhaustive switch that needs an arm (`InputBeanInstantiationEmitter.perFieldValueExpr` → both new
arms are `notALeaf`; `ArgCallEmitter` → unreachable arm; the walker → real `JooqRecord` handling).

## Implementation

File-by-file (the sealed-arm additions force the rest via compile errors; a single commit unless
the seams add review value):

- `model/CallSiteExtraction.java`: add the `UnboundJooqRecord` and `JooqRecord` permits and the
  nested `ColumnBinding` / `RecordKeyDecode` records, with `JooqRecord`'s compact-constructor
  invariants. Javadoc each: producer (`ServiceCatalog` for the marker, `InputBeanResolver` for the
  resolved arm) and consumer (the new helper emitter plus the walker), keeping the cross-references
  live per "Documentation names only live tests/code".
- `model/ValueShape.java`: add the `JooqRecordInput` permit.
- `ServiceCatalog.java`: jOOQ-`TableRecord` detection in the root `@service` arg path, emitting
  `UnboundJooqRecord`. (Reuse `org.jooq.TableRecord.isAssignableFrom`; the catalog already imports
  it for `classifySourcesType`.)
- `InputBeanResolver.java`: the `UnboundJooqRecord` → `JooqRecord` enrichment branch: table
  resolution, column-axis field walk, the single-`@nodeId` identity decode with the lifted
  record-type-mismatch gate, and the rejections. Factor the column/key resolution so the message
  discipline matches R195's `buildJooqRecordLeaf`.
- `walker/ServiceMethodCallWalker.java`: `case CallSiteExtraction.JooqRecord` → `JooqRecordInput`;
  `UnboundJooqRecord` defensive arm.
- `generators/ServiceMethodCallEmitter.java`: `case ValueShape.JooqRecordInput` → `create<Record>`
  call.
- `generators/JooqRecordInstantiationEmitter.java` (new, sibling to `InputBeanInstantiationEmitter`):
  the `create<Record>(Map)` helper. `generators/TypeFetcherGenerator.java`: collect the
  `JooqRecord` carriers into a dedup queue and drive the new emitter (parallel to the `InputBean`
  helper queue plus `collectTransitively`).
- `generators/ArgCallEmitter.java` and any other exhaustive `CallSiteExtraction` switch the compiler
  flags: explicit unreachable arms for the `@service`-only arms.

## Tests

Per "Pipeline tests are the primary behavioural tier", a pipeline test leads; compilation and
execution tiers layer the type- and behaviour-correctness backstops. No generated-body string
assertions at any tier.

- **Pipeline tier** (`JooqRecordServiceParamPipelineTest`, modeled on
  `NodeIdRecordInputBeanPipelineTest`): SDL → classified model → `TypeSpec`.
  - Positive single-key: `ModifyFilmInput { filmId: ID! @nodeId(typeName:"Film"), title: String @field(name:"title"), rentalRate: Float @field(name:"rental_rate") }`
    → param classifies to `CallSiteExtraction.JooqRecord` (walk the model and assert the arm, the
    resolved `TableRef.recordClass`, the two `ColumnBinding`s with their resolved `ColumnRef`s, and
    the `RecordKeyDecode` typeId / single key column); the arm assertion is itself the pin that the
    param did not bean-ify or fall to `Direct`. Assert the `createFilmRecord` helper lands on the
    `*Fetchers` class.
  - Composite-key identity: a `film_actor`-backed record param with `id: ID! @nodeId(typeName:"FilmActor")`
    → `RecordKeyDecode` carries both key columns (arity 2).
  - Rejections (all on `UnclassifiedField.reason()`): foreign-table `@nodeId` (R195-mismatch message
    naming both records); two `@nodeId` fields; a `@field`/bare field resolving to no column on the
    record's table (the honest replacement for "has no fields matching", with the candidate hint).
- **Compilation tier** (`graphitron-sakila-example`): add the fixtures below; the `-Plocal-db`
  compile against the real catalog verifies the emitted `new FilmRecord()` + `fromArray(..., Tables.FILM.<col>...)`
  references type-check (the hard backstop for the "columns belong to `table`" invariant), and that
  the helper carries no `@SuppressWarnings` (no deprecated `convert`).
- **Execution tier** (`GraphQLQueryTest`-family, alongside the R195 `assignFilmRecord` cases):
  `modifyFilm` round-trip: encode a `Film` NodeId, pass `title` + `rentalRate`, assert the service
  reads back the decoded `film_id` plus the set `title` / `rental_rate` (proves identity decode and
  column coercion together). A wrong-type NodeId throws (lifted R195 throw-on-mismatch behaviour).
- **Fixtures**: `graphitron-sakila-service` gains `FilmRecordService.modifyFilmRecord(FilmRecord)`
  (and a composite `FilmActorRecord` variant); `schema.graphqls` gains `ModifyFilmInput` +
  `modifyFilm` (no `@table` on the input; the table comes from the record param, deliberately
  exercising the consumer-derived-table path R97 owns generally but R311 sources from the record
  class). Test-tier SDL fixtures for the pipeline cases live inline as in `NodeIdRecordInputBeanPipelineTest`.

## User documentation

No new directive, Mojo goal, output format, or wire-protocol surface; this is a newly *accepted
shape* for the existing `@service` + `@field` + `@nodeId` directives (the param's Java type may be a
generated jOOQ `TableRecord`; its input fields name the record's columns and identify it). It
follows the R195 / R200 precedent (accepted-shape extensions, no user-manual chapter). The
spec-by-example corpus `docs/code-generation-triggers.adoc` gains a row for the jOOQ-record
`@service` param under its `@service` / input-classification tables, recording the new
classification alongside the existing `InputType` (non-`@table` input reflected as a developer
class) row at `:174`.

## Out of scope

- **`List<TableRecord>` / `Set<TableRecord>` root param**: a distinct shape ("list of input
  objects mapped to records", the canonical `InputBeanResolver` fall-through per
  `ServiceCatalog.java:262-265`); building N records is a separate arm. R311 is the singular param
  per its title; the list arm is a cheap follow-up once the singular shape lands (it wraps the same
  `create<Record>` helper, as R195's list variant wrapped its scalar decode).
- **FK-reference `@nodeId`** (a `@nodeId` pointing at a *different* table to set an FK column): the
  lifted record-type-mismatch gate rejects it. That is `@reference` / R97 territory, not the
  record's own identity.
- **`@table` on the input type**: R97 owns `@table`-on-input. R311 sources the table from the
  record param's class, not from a directive on the input.

## Spec review: Spec to Ready (2026-06-15)

Revisions requested; status stays Spec. The chosen direction (give the jOOQ-record `@service`
param a real call-site binding) is right, and the carrier-model half is sound: the two new
`CallSiteExtraction` arms, the `ColumnBinding` / `RecordKeyDecode` split, the `NodeIdDecodeRecord`
non-reuse rationale (its javadoc invariant at `CallSiteExtraction.java:190-195` does pin exactly
what the spec quotes), the orthogonal-axes argument, the `ColumnBinding`-vs-`FieldBinding`
distinction (`FieldBinding` is member-axis at `CallSiteExtraction.java:295-312`; `ColumnRef`
carries no table at `ColumnRef.java:20`), and the validator-mirrors-classifier section all hold up
against the principles, and the cited seams (`enrich:109/122/130`, `bindField:464`,
`buildJooqRecordLeaf:506`, the R195 mismatch gate `:537-545`, `bindingKey:315`,
`looksLikeBeanCandidate:637-646` being package-based, `resolveNodeIdRecordDecode:2104`, the
`argString(field, DIR_FIELD, ARG_NAME).orElse(name)` idiom at `BuildContext.java:1649`, the walker
`deriveValueShape:133` + `ParameterUnbindable` arms, the emitter `valueShapeExpression:143-148`, the
`TypeFetcherGenerator` InputBean helper queue at `:580-619`) all check out as named.

The blocker is that the classification-flow half is premised on the shape being undetected and
"unsupported on the rewrite," which is only half true and drives a duplicated-resolution design.

1. **The spec never mentions `GraphitronType.JooqTableRecordInputType`, the live classification of
   the identical shape** (intro, "unsupported on the rewrite"; Scope boundary; Classification flow
   point 1). A `@service` param whose reflected type is a jOOQ `TableRecord` already classifies as
   `JooqTableRecordInputType` (`GraphitronType.java:357-364`) with its `table` resolved, pinned by
   `GraphitronSchemaBuilderTest.JOOQ_TABLE_RECORD_CLASS` and the R281 `input-backing` corpus example
   (`ClassifiedCorpus.java:530-543`), against the exact fixture R311 plans to add
   (`DummyService.consumeFilmRecord(FilmRecord)`). The type-side detection and table resolution are
   done; only the param-binding / call-site emission is the gap. Bears on plan completeness (the
   implementer would hit `JooqTableRecordInputType` mid-flight and have to invent the reconciliation)
   and on the accuracy of the motivating framing. Revision: re-frame as "the input type classifies
   as `JooqTableRecordInputType` today; the missing piece is the call-site binding," and locate the
   new work against that existing classification.

2. **Re-resolving the table in `enrich` duplicates an existing resolution** (Classification flow
   point 2). `TypeBuilder.java:1226-1227` already calls `svc.resolveTableByRecordClass(cls)` for the
   same record class and stores the `TableRef` on `JooqTableRecordInputType.table`. Calling
   `resolveTableByRecordClass` again in `enrich` is the "same resolution evaluated by multiple
   consumers" smell that "Generation-thinking" and "Classification belongs at the parse boundary"
   warn against. The same applies to the proposed new `org.jooq.TableRecord.isAssignableFrom`
   detection in `ServiceCatalog.argExtraction`: that predicate is already evaluated in `TypeBuilder`
   to produce `JooqTableRecordInputType`. Revision: source the `TableRef` (and the record `ClassName`)
   from the already-classified `JooqTableRecordInputType` for the arg's SDL input type
   (`InputBeanResolver` holds a `BuildContext ctx`, so a classified-type lookup is plausibly reachable
   at the field pass), or, if pass ordering makes the classified type genuinely unavailable in
   `enrich`, say so and justify the second resolution site explicitly. Pick a side.

3. **The doc-update target is mis-aimed and the cited line is stale** (User documentation).
   `code-generation-triggers.adoc:174` is the `ResultType` row; the `InputType` (non-`@table`) row is
   `:177`. More substantively, the classification (`JooqTableRecordInputType`) is *already* documented
   (`:194`) and in the corpus, so "gains a row recording the new classification" double-records an
   existing fact. What is genuinely new is the call-site param-binding capability
   (`CallSiteExtraction.JooqRecord` to `create<Record>` helper), which belongs on the `@service`
   field / call-site surface (near the `QueryServiceRecordField` rows at `:425-426`), not the
   type-classification table. Revision: retarget the row to the param-binding capability and fix the
   line cite.

Minor (fold into the above, not separately blocking): `argExtraction` is shared between `@service`
(`:241`) and `@tableMethod` (`:573`). Classification flow point 1 says "`argExtraction` gains a
check" while Implementation says "root `@service` arg path"; reconcile, and pin that a `@tableMethod`
`TableRecord` arg (the `:573` callsite) is unaffected. This folds into finding 2's "where does
detection live" question.

Next pass's reviewer-session must differ from this one (the disqualified set now includes this
review's session).
