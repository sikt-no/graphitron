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

A top-level `@service` parameter whose Java type is a generated jOOQ `TableRecord` is accepted on
the *type* side but cannot be *bound* at the call site.

## The problem

The shape (a real consumer case, `EndreUtdanningsspesifikasjonsstatusInput` ->
`UtdanningsspesifikasjonsstatusRecord`): the param's SDL input type names jOOQ **columns** through
`@field(name: "DATO_FRA")`, and carries a `@nodeId` id that decodes into the record's scalar key
columns.

**Already works (type side).** `TypeBuilder` (`:1225-1227`) reflects the param's class, sees
`org.jooq.TableRecord`, resolves the table via `svc.resolveTableByRecordClass`, and classifies the
input type as `GraphitronType.JooqTableRecordInputType` (`GraphitronType.java:357-364`) with both
`table` and `fqClassName` resolved. Pinned by `GraphitronSchemaBuilderTest.JOOQ_TABLE_RECORD_CLASS`
and the R281 `input-backing` corpus example (`ClassifiedCorpus.java:530-543`). R195 separately ships
the `@nodeId`-jOOQ-record decode for records that are *members* of an input bean
(`buildJooqRecordLeaf`, `:506`).

**Missing (call site).** Nothing turns that param into a constructed record. The catalog emits
`CallSiteExtraction.Direct` for it (no jOOQ-record case in `ServiceCatalog.argExtraction`,
`:735-742`). `InputBeanResolver.enrich` then takes the `Direct` + input-object branch and treats the
param as a *bean*: `looksLikeBeanCandidate` (`:637-646`) is package-based, so a generated record
(which lives in the consumer's `*.tables.records`, not `org.jooq.*`) passes the gate, reaches
`buildInputBean`, and is bound on the Java-**member** axis. The fields name **columns**, not members,
so nothing matches and the build rejects:

    parameter 'inputs' ... bean class '...UtdanningsspesifikasjonsstatusRecord'
    has no fields matching the SDL input type 'EndreUtdanningsspesifikasjonsstatusInput'

That message misdiagnoses: the fields *do* match, on the `@field(name:)` column axis that
bean-binding never reads. The build correctly stops (no broken code emitted), but on a coincidental
member-name overlap the same path silently partial-populates or throws an R150-family
`ClassCastException` at execution instead of rejecting.

(Stale premises this corrects: R200's spec claimed the case never reaches `buildInputBean` because
`looksLikeBeanCandidate` rejects `org.jooq.*` -- false for *generated* records. R195's changelog
deferred the param-position case as "tangled with R97" -- now untangled. This item owns only the
param-IS-a-jOOQ-record half; R97 owns `@table`-on-the-input-type.)

## The fix

`InputBeanResolver.enrich` runs after `TypeBuilder.buildTypes()` (`GraphitronSchemaBuilder.buildSchema`,
`:225` then field classification) and already holds `BuildContext ctx`, whose `types` map
(`BuildContext.java:165`) carries every classified type. So `enrich` can read the answer the type
pass already computed: when the `Direct` head arg's SDL input type classifies as
`JooqTableRecordInputType`, build the call-site binding from that classified type, **before** the
bean branch, so a jOOQ record never reaches `looksLikeBeanCandidate` / `buildInputBean`.

That binding:

- reads the resolved `table` and record `ClassName` straight off the `JooqTableRecordInputType` (no
  second `resolveTableByRecordClass` call, no second `TableRecord.isAssignableFrom` check -- both
  already done in `TypeBuilder`),
- binds each plain input field on the **column axis** via `@field(name:)` (the R97 axis read, lifted
  to the param position) into a resolved `ColumnRef`,
- decodes the single `@nodeId` field into the record's own key columns (R195's wire-decode mechanism,
  projected onto the param record's identity),
- emits one `create<Record>(Map)` helper that constructs the record.

The misleading "has no fields matching" outcome becomes honest, R195-shaped rejections that surface
at validate time as `UnclassifiedField`.

**No `ServiceCatalog` change, no structural marker.** An earlier draft routed a total
`UnboundJooqRecord` marker from `ServiceCatalog` into `enrich`, mirroring the `Direct` -> `InputBean`
two-step. That mirror is unnecessary: the jOOQ-record decision is inherently SDL-aware (it depends on
the arg's classified input type), so it belongs in `enrich`, the existing SDL-aware post-processor;
and the structural fact the marker would carry ("this param is a jOOQ `TableRecord`") is already
encoded, table and all, in `JooqTableRecordInputType`. Re-deriving it in `ServiceCatalog` is the
"same predicate/resolution in two consumers" smell. Reading the classified type in `enrich` is the
single resolution site. (It also leaves the `argExtraction` path shared with `@tableMethod` (`:573`)
untouched, so a `@tableMethod` `TableRecord` arg is unaffected by construction.)

## Model: one new `CallSiteExtraction` arm + two column-axis records

```java
// CallSiteExtraction permit, added:
record JooqRecord(ClassName recordClass, TableRef table,
                  List<ColumnBinding> columnBindings,
                  Optional<RecordKeyDecode> keyDecode) implements CallSiteExtraction { /* compact ctor below */ }

// nested records (column axis; siblings to FieldBinding, which is member axis):
record ColumnBinding(String sdlFieldName, ColumnRef column) {}
record RecordKeyDecode(ClassName encoderClass, String typeId, List<ColumnRef> keyColumns) {}
```

- **One fully-resolved arm, produced in `enrich`.** Every `JooqRecord` is complete (non-null
  `table`, resolved `columnBindings` and `keyColumns`); there is no partial/unbound intermediate
  state. `enrich` replaces the catalog's `Direct` with a finished `JooqRecord`, exactly as it
  replaces `Direct` with a finished `InputBean` today.
- **The `@nodeId` identity decode gets its own carrier (`RecordKeyDecode`), not R195's
  `NodeIdDecodeRecord`.** `NodeIdDecodeRecord`'s javadoc (`CallSiteExtraction.java:206-225`) pins
  "produced only by `InputBeanResolver` ... consumed only by `InputBeanInstantiationEmitter`" and
  documents its `nonNull` / `list` fields in *member*-axis terms (list-ness lives on the enclosing
  `FieldBinding`, absent at the param position). Reusing it would falsify that invariant. What
  survives in the model is not "a NodeId decoder" but "these key columns load onto this record's
  identity"; `RecordKeyDecode` says exactly that. The wire mechanism is still R195's
  (`encoderClass.decodeValues(typeId, nodeId)` -> `record.fromArray(values, Tables.T.<keyCol>...)`);
  only the projection target differs.
- **`columnBindings` (the SET payload) and `keyDecode` (the identity) are orthogonal axes on one
  record.** Same pattern as `SourceKey` carrying `Wrap` + `Reader` + `Cardinality`: each accessor has
  one fixed meaning, no variant-dependent overload.
- **`ColumnBinding` is column-axis, a genuinely different axis from member-axis `FieldBinding`**
  (`CallSiteExtraction.java:295-312`), so a new record, not a clone. It carries a *resolved*
  `ColumnRef` (not a raw `@field(name:)` string), so the emitter reaches `table.COL.getDataType()`
  with no re-parsing. No list flag: a scalar column cannot take a list, and the absence documents
  that.

**Compact-constructor invariant on `JooqRecord`.** `ColumnRef` carries no table of its own
(`ColumnRef.java:20`), so every `ColumnBinding.column` and every `RecordKeyDecode.keyColumns` entry
is resolved against the one `TableRef` on the record. The constructor enforces non-null
`recordClass` / `table` and an at-least-one-binding floor (`columnBindings` non-empty OR `keyDecode`
present; an empty input is rejected, like `InputBean`'s empty-bindings rejection). "All columns
belong to `table`" is a producer guarantee whose hard backstop is the compilation tier (a
`Tables.FILM.NOPE` reference fails javac against the real catalog).

## Enrichment, re-projection, emit

1. **`InputBeanResolver.enrich`** (the existing post-processor, `:109`). Add a branch in the `Direct`
   + head + input-object arm, *before* the bean path: look up `ctx.types.get(iot.getName())`; if it
   is a `JooqTableRecordInputType jtr`, build `JooqRecord`:
   - `table` and `recordClass` come from `jtr.table()` / `jtr.fqClassName()`. (A
     `JooqTableRecordInputType` with a null `table` -- backing class from a catalog not loaded at
     build time -- is rejected: "param record type X is not in the jOOQ catalog".)
   - For each SDL input field, the **binding key** is `argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName())`
     (the column-axis idiom at `BuildContext.java:1649` and `InputBeanResolver.bindingKey`). Resolve
     it to a `ColumnRef` via `JooqCatalog.findColumn(table, key)` (case-insensitive, Java-name then
     SQL-name). Unresolvable -> reject naming the field, the key, and the table, with a Levenshtein
     `candidateHint` over `columnSqlNamesOf(table)` (the house error-quality contract; this is the
     honest replacement for "has no fields matching").
   - A field carrying `@nodeId(typeName:)` is the identity field: resolve through
     `BuildContext.resolveNodeIdRecordDecode(typeName)` (`:2104`), then apply the **lifted
     record-type-mismatch gate** (R195 `InputBeanResolver.java:537-545`, sourced from `jtr` instead
     of a member): the resolved node table's `recordClass()` must equal the param `recordClass`. A
     foreign-table `@nodeId` is rejected with the R195-shaped message naming the param record, the
     foreign record, and both fixes. Project to `RecordKeyDecode`.
   - **At most one `@nodeId` field** (two would each claim the record's identity); a second is
     rejected.
   - Rewrite the param to `new ParamSource.Arg(jooqRecord, arg.path())`.

2. **`ValueShape` + walker** (`ServiceMethodCallWalker.deriveValueShape`, `:133-176`). Add
   `ValueShape.JooqRecordInput(ClassName recordClass, ArgPath sdlPath)`, parallel to `RecordInput` /
   `JavaBeanInput`; `case CallSiteExtraction.JooqRecord` -> `JooqRecordInput` (head-only).

3. **Service-call argument expression** (`ServiceMethodCallEmitter.valueShapeExpression`,
   `:143-148`). `case ValueShape.JooqRecordInput jr -> compositeHelperCall(jr.recordClass(), …)` ->
   `create<Record>(env.getArgument("<arg>"))`, mirroring `RecordInput`.

4. **Helper emitter** (`JooqRecordInstantiationEmitter`, new, sibling to
   `InputBeanInstantiationEmitter`; driven by the `CallSiteExtraction.JooqRecord` queue collected in
   `TypeFetcherGenerator` from `callParams()`, parallel to the `InputBean` helper queue at
   `:580-619`). One deduped `private static <Record> create<Record>(Map<String, Object> raw)` per
   record class:

   ```java
   private static FilmRecord createFilmRecord(Map<String, Object> raw) {
       if (raw == null) return null;
       FilmRecord rec = new FilmRecord();
       // plain @field columns, batched through fromArray so each coerces via its column's
       // DataType/Converter (R195 coercion discipline, no deprecated DataType.convert(Object)):
       rec.fromArray(new Object[]{ raw.get("title"), raw.get("rentalRate") },
                     Tables.FILM.TITLE, Tables.FILM.RENTAL_RATE);
       // identity (@nodeId) key columns, decoded then loaded (R195's call on this record):
       String[] keyValues = NodeIdEncoder.decodeValues("Film", (String) raw.get("filmId"));
       if (keyValues == null || keyValues.length != 1) {
           throw GraphqlErrorException.newErrorException().message("...").build();
       }
       rec.fromArray(keyValues, Tables.FILM.FILM_ID);
       return rec;
   }
   ```

   Statement form, explicit types, named locals (per "Generated code is read and debugged"). The two
   `fromArray` calls touch disjoint column sets; either group may be absent (no plain columns, or no
   identity field). The decode-and-throw block stays visually grouped.

**Validator.** All rejections above are `Rejection.structural` -> `Resolved.Rejected` ->
`UnclassifiedField` (`ServiceDirectiveResolver.java:155-158`), which `ValidateMojo` fails on by
default -- the same validate-time surface R195's gate uses. Adding the new arm makes the sealed
compiler flag every exhaustive `CallSiteExtraction` switch that needs handling
(`InputBeanInstantiationEmitter.perFieldValueExpr` -> `notALeaf`; `ArgCallEmitter` -> unreachable
arm; the walker -> real `JooqRecordInput` handling).

## Files

(The sealed-arm addition forces most of the rest via compile errors; one commit unless the seams add
review value.)

- `model/CallSiteExtraction.java`: add the `JooqRecord` permit and the nested `ColumnBinding` /
  `RecordKeyDecode` records, with the compact-constructor invariants. Javadoc producer
  (`InputBeanResolver`) and consumers (the helper emitter, the walker), keeping the cross-references
  live per "Documentation names only live tests/code".
- `model/ValueShape.java`: add the `JooqRecordInput` permit.
- `InputBeanResolver.java`: the `JooqTableRecordInputType` branch -> `JooqRecord` (table/class off
  the classified type, column-axis field walk, single-`@nodeId` decode with the lifted mismatch gate,
  the rejections). Factor the column/key resolution so the message discipline matches R195's
  `buildJooqRecordLeaf`.
- `walker/ServiceMethodCallWalker.java`: `case CallSiteExtraction.JooqRecord` -> `JooqRecordInput`.
- `generators/ServiceMethodCallEmitter.java`: `case ValueShape.JooqRecordInput` -> `create<Record>`
  call.
- `generators/JooqRecordInstantiationEmitter.java` (new): the `create<Record>(Map)` helper.
  `generators/TypeFetcherGenerator.java`: collect the `JooqRecord` carriers into a dedup queue and
  drive the new emitter (parallel to the `InputBean` helper queue plus `collectTransitively`).
- `generators/ArgCallEmitter.java` and any other exhaustive `CallSiteExtraction` switch the compiler
  flags: an explicit unreachable arm.

## Tests

Per "Pipeline tests are the primary behavioural tier"; compilation and execution tiers backstop the
type- and behaviour-correctness. No generated-body string assertions at any tier.

- **Pipeline** (`JooqRecordServiceParamPipelineTest`, modeled on `NodeIdRecordInputBeanPipelineTest`):
  - Single-key: `ModifyFilmInput { filmId: ID! @nodeId(typeName:"Film"), title: String @field(name:"title"), rentalRate: Float @field(name:"rental_rate") }`
    -> param classifies to `CallSiteExtraction.JooqRecord`; assert the arm, the resolved
    `table.recordClass`, the two `ColumnBinding`s with their `ColumnRef`s, the `RecordKeyDecode`
    typeId + single key column (the arm assertion is itself the pin that the param did not bean-ify
    or stay `Direct`). Assert `createFilmRecord` lands on the `*Fetchers` class.
  - Composite-key: a `film_actor`-backed record param with `id: ID! @nodeId(typeName:"FilmActor")`
    -> `RecordKeyDecode` carries both key columns (arity 2).
  - Rejections (on `UnclassifiedField.reason()`): foreign-table `@nodeId` (R195-mismatch message
    naming both records); two `@nodeId` fields; a `@field` / bare field resolving to no column on
    the record's table (the honest replacement for "has no fields matching", with the candidate
    hint).
- **Compilation** (`graphitron-sakila-example`): add the fixtures below; the `-Plocal-db` compile
  against the real catalog verifies the emitted `new FilmRecord()` + `fromArray(..., Tables.FILM.<col>...)`
  references type-check (the hard backstop for "columns belong to `table`"), and that the helper
  carries no `@SuppressWarnings` (no deprecated `convert`).
- **Execution** (`GraphQLQueryTest`-family, alongside the R195 `assignFilmRecord` cases): `modifyFilm`
  round-trip -- encode a `Film` NodeId, pass `title` + `rentalRate`, assert the service reads back the
  decoded `film_id` plus the set `title` / `rental_rate` (identity decode + column coercion together).
  A wrong-type NodeId throws (lifted R195 throw-on-mismatch behaviour).
- **Fixtures**: `graphitron-sakila-service` gains `FilmRecordService.modifyFilmRecord(FilmRecord)`
  (and a composite `FilmActorRecord` variant); `schema.graphqls` gains `ModifyFilmInput` + `modifyFilm`
  (no `@table` on the input; the table comes from the record param). Pipeline SDL fixtures live inline
  as in `NodeIdRecordInputBeanPipelineTest`.

## User documentation

No new directive, Mojo goal, output format, or wire-protocol surface; this is a newly *bindable*
shape for the existing `@service` + `@field` + `@nodeId` directives. The *classification*
(`JooqTableRecordInputType`) is already documented (`docs/code-generation-triggers.adoc:194`) and in
the R281 corpus, so it is not re-recorded. What is new is the **call-site param binding**
(`CallSiteExtraction.JooqRecord` -> `create<Record>` helper); its triggers row goes on the `@service`
call-site surface near the `QueryServiceRecordField` rows (`:425-426`), not the type-classification
table. Follows the R195 / R200 accepted-shape precedent (no user-manual chapter).

## Out of scope

- **`List<TableRecord>` / `Set<TableRecord>` root param**: a distinct shape (building N records), the
  canonical `InputBeanResolver` fall-through per `ServiceCatalog.java:262-265`. R311 is the singular
  param per its title; the list arm is a cheap follow-up that wraps the same `create<Record>` helper
  (as R195's list variant wrapped its scalar decode). Note the *child*-coordinate `List<TableRecord>`
  SOURCES case already classifies to `SourceKey.Wrap.TableRecord` (`ServiceCatalog.java:824-831`) --
  a different path again.
- **FK-reference `@nodeId`** (a `@nodeId` pointing at a *different* table to set an FK column): the
  lifted record-type-mismatch gate rejects it. That is `@reference` / R97 territory, not the record's
  own identity.
- **`@table` on the input type**: R97 owns `@table`-on-input. R311 sources the table from the record
  param's class, not from a directive on the input.

## Spec review history

**Spec -> Ready, 2026-06-15 (revisions requested, now folded in).** The reviewer endorsed the
direction and the carrier model (the `JooqRecord` arm, the `ColumnBinding` / `RecordKeyDecode` split,
the `NodeIdDecodeRecord` non-reuse, the orthogonal-axes and column-vs-member arguments) and confirmed
the cited seams. Three revisions, all addressed here:

1. *Framing premised on "unsupported / undetected" -- false.* The shape already classifies as
   `JooqTableRecordInputType` with its table resolved. -> "The problem" now leads with what already
   works (type side) vs. the actual gap (call site).
2. *Re-resolving the table in a marker flow duplicates `TypeBuilder`'s resolution.* -> Dropped the
   `UnboundJooqRecord` marker and the `ServiceCatalog` detection entirely; `enrich` reads `table` +
   `recordClass` off the already-classified `JooqTableRecordInputType` (single resolution site). Pass
   ordering confirmed: `buildTypes()` precedes field classification (`GraphitronSchemaBuilder.buildSchema:225`).
3. *Doc target mis-aimed / stale line cite.* -> The row now targets the call-site param-binding
   capability near `code-generation-triggers.adoc:425-426`, not the already-documented type
   classification.

Status stays Spec; a fresh reviewer session (different from both the original review and this
revision) gates Spec -> Ready.
