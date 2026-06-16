---
id: R311
title: "Bind a jOOQ TableRecord (scalar and List<…>) @service input param: column-axis @field + @nodeId scalar-key decode"
status: In Review
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-06-15
last-updated: 2026-06-16
---

# Bind a jOOQ TableRecord (scalar and List<…>) @service input param: column-axis @field + @nodeId scalar-key decode

A top-level `@service` parameter whose Java type is a generated jOOQ `TableRecord` — singular
(`Record`) or batched (`List<Record>`) — is accepted on the *type* side but cannot be *bound* at the
call site.

## Implementation status (landed, In Review)

Implemented as specified; all four tiers green. The sealed `CallSiteExtraction.JooqRecord` +
`ColumnBinding` / `RecordKeyDecode` records, the `ValueShape.JooqRecordInput` path-carrying leaf, the
`InputBeanResolver.enrich` branch (after the shared input-object gates, before `buildInputBean`), the
walker `ListOf` wrap, both call-site emitters (root `ServiceMethodCallEmitter`, child `ArgCallEmitter`
real arm), the new `JooqRecordInstantiationEmitter`, and the `TypeFetcherGenerator` dual-walk dedup
queue all landed. The compiler-forced arms resolved exactly as the spec predicted (real arms in
`ServiceMethodCallEmitter.valueShapeExpression`/`listExpression`, `ArgCallEmitter.buildArgExtraction`,
`TypeFetcherGenerator.collectFromValueShape`; defensive/throw arms in `outerArgOf`, the bean-field-walk
switches, `FieldBuilder.javaTypeFor`, and `InputBeanInstantiationEmitter.perFieldValueExpr`).

Tests: `JooqRecordServiceParamPipelineTest` (11 cases: singular, composite, list, the regression pin,
the child-`@service` coordinate, and the rejection set) is the primary tier; the
`graphitron-sakila-example` compile tier type-checks the emitted `create<Record>` / `create<Record>List`
helpers and the child `ArgCallEmitter` call against the real catalog; four `GraphQLQueryTest` cases
round-trip the identity decode + column SET (singular, list, composite, wrong-type-throws).

One fixture-shape note (no design impact): the pipeline child-coordinate fixture uses
`List<Row1<Integer>>` keys → `List<LanguageRecord>`, while the sakila child fixture
(`FilmRecordService.modifiedLanguage`) uses the sakila-native `Set<Record1<Integer>>` keys →
`Map<Record1<Integer>, LanguageRecord>` shape (modeled on the existing `languageByService`). Both are
valid child rows-method shapes; the `JooqRecord` arg binding is identical across them.

Per the reviewer rule, the implementing session is disqualified from the `In Review → Done` approval;
a fresh session gates it.

## The problem

The shape (a real consumer case, `EndreUtdanningsspesifikasjonsstatusInput` ->
`UtdanningsspesifikasjonsstatusRecord`): the param's SDL input type names jOOQ **columns** through
`@field(name: "DATO_FRA")`, and carries a `@nodeId` id that decodes into the record's scalar key
columns. The consumer's actual method is **`endreUtdanningsspesifikasjonsstatus(List<…Record>
inputs)`** against an SDL arg `[…Input!]!` — i.e. the *list* shape; the singular `Record` param is
the same binding over one element. (An earlier draft of this spec scoped itself to the singular
param and deferred the list, which left its own motivating consumer case uncovered; this revision
folds both in — see the 2026-06-16 review-history note. The two differ only by a `ValueShape.ListOf`
wrap that the walker already applies for the `InputBean` sibling.)

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

That message misdiagnoses: the fields *do* match, on the `@field(name:)` **column** axis. Bean-binding
does read `@field(name:)` (via `InputBeanResolver.bindingKey`, `:315`), but only as a Java-**member**
key; it never resolves it to a `ColumnRef`. So a `@field(name: "DATO_FRA")` naming a column is matched
against member names, finds nothing, and the build correctly stops (no broken code emitted). On a
coincidental member-name overlap the same path silently partial-populates or throws an R150-family
`ClassCastException` at execution instead of rejecting.

**Both cardinalities hit the same wall.** `enrich` peels `List<…>` via `peelJavaListSet` and binds on
the *element* class, so a `List<TableRecord>` param reaches `buildInputBean` on the record element
exactly as the singular param does — the cardinality-parity check (`elt.list() == sdl.list()`,
`:180-188`) passes either way, and the same empty-bindings rejection fires. Commenting out the
`@field` column does not change the outcome: the two `@nodeId` fields also fail member-axis binding,
so `bindings` is empty regardless. This confirms the diagnosis is a *binding-axis* gap (column +
`@nodeId` identity vs. Java member), not a missing-field-name gap — and it is independent of
cardinality.

(Stale premises this corrects: R200's spec claimed the case never reaches `buildInputBean` because
`looksLikeBeanCandidate` rejects `org.jooq.*` -- false for *generated* records. R195's changelog
deferred the param-position case as "tangled with R97" -- now untangled. This item owns only the
param-IS-a-jOOQ-record half; R97 owns `@table`-on-the-input-type.)

## The fix

`InputBeanResolver.enrich` runs after `TypeBuilder.buildTypes()` (`GraphitronSchemaBuilder.buildSchema`,
`:225` then field classification) and already holds `BuildContext ctx`, whose `types` map
(`BuildContext.java:165`) carries every classified type. So `enrich` can read the answer the type
pass already computed: when the `Direct` head arg's SDL input type classifies as
`JooqTableRecordInputType`, build the call-site binding from that classified type just before the
`buildInputBean` call -- after the arm's shared input-object gates (loadable / `Map` /
cardinality-parity, `:150-188`), which a jOOQ record passes, so the record reuses those gates and
only the member-axis bean instantiation is replaced.

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

**Both `@service` coordinates, one binding.** `enrich` runs unconditionally for every `@service`
field, root and `@table`-parent child alike (`ServiceDirectiveResolver.resolve` calls it before the
`isRoot` gate, `:155`), so the `JooqRecord` rewrite is coordinate-agnostic exactly as the `InputBean`
rewrite it parallels. What differs by coordinate is only which call-site emitter renders the param:
a root field emits through the R238 carrier path (`ServiceMethodCallEmitter`, switching on
`ValueShape`), while a child field's DataLoader rows-method emits through the legacy method-backed
path (`ArgCallEmitter.buildMethodBackedCallArgs`, `TypeFetcherGenerator:4978`, switching on
`CallSiteExtraction`). Both already carry a live `InputBean` arm; `JooqRecord` gets a real arm in
each (not an "unreachable" throw), so a jOOQ-record arg binds identically whether the `@service`
field is root or child. The construction itself is one helper (`create<Record>` / `create<Record>List`),
reached from either emitter. (`@tableMethod` is still untouched: its params are never enriched, so they
never become `JooqRecord`.)

## Model: one new `CallSiteExtraction` arm + two column-axis records

```java
// CallSiteExtraction permit, added:
record JooqRecord(TableRef table,
                  List<ColumnBinding> columnBindings,
                  Optional<RecordKeyDecode> keyDecode) implements CallSiteExtraction { /* compact ctor below */ }

// nested records (column axis; siblings to FieldBinding, which is member axis):
record ColumnBinding(String sdlFieldName, ColumnRef column) {}
record RecordKeyDecode(String sdlFieldName, ClassName encoderClass, String typeId,
                       List<ColumnRef> keyColumns) {}
```

The record `ClassName` is `table.recordClass()`, not a separate component: a `JooqTableRecordInputType`'s `table` is resolved *by matching* `Table.getRecordType()` against the backing class, so `jtr.fqClassName()` and `jtr.table().recordClass()` denote the same class by construction; carrying both would be redundant model state. R195's `NodeIdDecodeRecord` sets the precedent (it carries only `table` and reads `table.recordClass()` for the helper name and `new <T>Record()`; `InputBeanInstantiationEmitter.buildRecordDecodeHelper`). The record class reaches the emitter and walker through `table.recordClass()` everywhere below.

- **One fully-resolved arm, produced in `enrich`.** Every `JooqRecord` is complete (non-null
  `table`, resolved `columnBindings` and `keyColumns`); there is no partial/unbound intermediate
  state. `enrich` replaces the catalog's `Direct` with a finished `JooqRecord`, exactly as it
  replaces `Direct` with a finished `InputBean` today.
- **The `@nodeId` identity decode gets its own carrier (`RecordKeyDecode`), not R195's
  `NodeIdDecodeRecord`.** `NodeIdDecodeRecord`'s javadoc (`CallSiteExtraction.java:166-225`, the
  `NodeIdDecodeRecord` record and its doc) pins
  "produced only by `InputBeanResolver` ... consumed only by `InputBeanInstantiationEmitter`" and
  documents its `nonNull` / `list` fields in *member*-axis terms (list-ness lives on the enclosing
  `FieldBinding`, absent at the param position). Reusing it would falsify that invariant. What
  survives in the model is not "a NodeId decoder" but "these key columns load onto this record's
  identity"; `RecordKeyDecode` says exactly that. The wire mechanism is still R195's
  (`encoderClass.decodeValues(typeId, nodeId)` -> `record.fromArray(values, Tables.T.<keyCol>...)`);
  only the projection target differs. `RecordKeyDecode` also carries its own `sdlFieldName` (the Map
  key for the wire NodeId), where `NodeIdDecodeRecord` does not: `NodeIdDecodeRecord` rides as a
  `FieldBinding.leaf` and inherits the key from `FieldBinding.sdlFieldName` (`CallSiteExtraction.java:295`),
  but `RecordKeyDecode` is a bare `Optional` on `JooqRecord` with no enclosing `FieldBinding`, so the
  emitter can write `raw.get("<idField>")` only if the carrier names the field (the same reason
  `ColumnBinding` carries `sdlFieldName`). Detaching the decode from the member axis is exactly what
  forces the key back onto the carrier. It carries no `nonNull`: a `@nodeId` at the record's identity
  always decodes the key that *is* the record, so a null or type-mismatched id throws (R195
  `ThrowOnMismatch`) whether the SDL field is `ID!` or `ID`, leaving nothing for a nullability flag to
  vary (`NodeIdDecodeRecord.nonNull` exists only to let a *member* yield null on a nullable field).
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
is resolved against the one `TableRef` on the record. The constructor enforces a non-null
`table` and an at-least-one-binding floor (`columnBindings` non-empty OR `keyDecode`
present; an empty input is rejected, like `InputBean`'s empty-bindings rejection). "All columns
belong to `table`" is a producer guarantee whose hard backstop is the compilation tier (a
`Tables.FILM.NOPE` reference fails javac against the real catalog).

## Enrichment, re-projection, emit

1. **`InputBeanResolver.enrich`** (the existing post-processor, `:109`). Add a branch in the `Direct`
   + head + input-object arm, after the shared input-object gates (`:150-188`) and *before* the
   `buildInputBean` call: look up `ctx.types.get(iot.getName())`; if it
   is a `JooqTableRecordInputType jtr`, build `JooqRecord`:
   - **Cardinality parity is inherited, not re-derived.** The branch sits after the existing
     `elt.list() == sdl.list()` check (`:180-188`), so a Java `List<Record>` against a singular
     input-object arg (or a singular `Record` against `[Input!]`) is rejected there before any
     `JooqRecord` is built -- the same guarantee the bean path relies on, and what lets the walker
     read list-ness off the Java type alone (step 2) with no second SDL cross-check. Skipping it would
     let a cardinality-mismatched param reach `create<Record>List` / `create<Record>` against the
     opposite wire shape and throw a runtime `ClassCastException` instead of failing at validate time
     (against "Validator mirrors classifier invariants").
   - `table` comes from `jtr.table()`; the record class is `table.recordClass()` (the two name the
     same class, see Model above, so there is no separate `jtr.fqClassName()` read). (A
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
     of a member): the resolved node table's `recordClass()` must equal the param record's
     `table.recordClass()`. A
     foreign-table `@nodeId` is rejected with the R195-shaped message naming the param record, the
     foreign record, and both fixes. Project to `RecordKeyDecode`, capturing the field's own SDL
     name as the `sdlFieldName` Map key alongside the decoded `encoderClass` / `typeId` / `keyColumns`.
   - **At most one `@nodeId` field** (two would each claim the record's identity); a second is
     rejected.
   - Rewrite the param to `new ParamSource.Arg(jooqRecord, arg.path())`.

2. **`ValueShape` + walker** (`ServiceMethodCallWalker.deriveValueShape`, `:133-176`). Add
   `ValueShape.JooqRecordInput(CallSiteExtraction.JooqRecord carrier, ArgPath sdlPath)`;
   `case CallSiteExtraction.JooqRecord jr` -> `new JooqRecordInput(jr, path)`. This is a
   *path-carrying leaf*, not a pathless composite: unlike `RecordInput` / `JavaBeanInput` (which hang
   their paths on per-field `Scalar` leaves), a `JooqRecordInput` has no per-field `ValueShape`
   children, so it carries its own `sdlPath` exactly as `ValueShape.Scalar` does. It also carries the
   whole `CallSiteExtraction.JooqRecord` (the same way `Scalar` carries its raw
   `CallSiteExtraction leafTransform`), so the carrier-walk helper collector can register the
   `create<Record>` helper from the `ValueShape` alone (see Helper emitter below). `javaType()`
   returns `carrier.table().recordClass()`. **Cardinality is handled exactly as the `InputBean` arm
   does it** (`:158-162`), and is sound for the same reason: the walker reads list-ness from the Java
   type alone, which step 1's `:180-188` parity check has already aligned with the SDL arg. The walker
   builds the element `JooqRecordInput`, then -- when
   `isListType(javaType, carrier.table().recordClass())` (`:222-229`, the existing `List<X>` test) --
   wraps it in the existing `ValueShape.ListOf(path, shape)`. No list flag on the arm and no new list
   carrier: `ListOf` already exists and the singular param is the unwrapped element.
   (`Set<TableRecord>` is not covered, mirroring `InputBean`'s own `List`-only `isListType`; see Out
   of scope.)

3. **Service-call argument expression** (`ServiceMethodCallEmitter`, the root-coordinate emitter).
   Two real arms plus one forced-but-unreachable arm, mirroring how `RecordInput` / `JavaBeanInput`
   are rendered. The record class is `jr.carrier().table().recordClass()` throughout:
   - `valueShapeExpression` (`:143-150`, singular): a `case ValueShape.JooqRecordInput jr` arm
     emitting `create<Record>(env.getArgument("<arg>"))`. This is the singular-helper shape that
     `compositeHelperCall` (`:320-323`) produces; `JooqRecordInput` reads the arg name straight off
     its own `sdlPath`, so it does not reuse `compositeHelperCall` verbatim (that method takes a
     `fields` list only to recover the arg name via `outerArgOf`, which `JooqRecordInput` does not
     need).
   - `listExpression` (`:289-310`, the `ListOf` element switch): a `case ValueShape.JooqRecordInput`
     arm rendered `create<Record>List(env.getArgument("<arg>"))` (the helper name from
     `jr.carrier().table().recordClass()`), byte-for-byte the shape of the existing `RecordInput` /
     `JavaBeanInput` list arms (`:293-298`).
   - `outerArgOf(ValueShape)` (`:346-353`) is a third exhaustive `ValueShape` switch the sealed
     addition forces; its `JooqRecordInput` arm is unreachable-by-construction (a `JooqRecordInput`
     is only ever a top-level param shape or a `ListOf` element, never an `InputBean` field shape, so
     `outerArgOf` is never called with one) and takes a defensive `jr.sdlPath().outerArgName()`.

   The sealed `ValueShape` addition flags *every* exhaustive switch over it at compile time, so no
   arm can be silently forgotten. Beyond the three above, the forced arms in
   `TypeFetcherGenerator`'s `ValueShape` switches are: `collectFromValueShape` (`:1622`), which gets
   **real** handling (see Helper emitter below); and the bean-field-walk switches
   (`leafForFieldBinding` `:1659`, `innerElementTypeNameOf` `:1691`, the field-path `outerArgOf`
   `:1707`), which take defensive arms for the same never-an-`InputBean`-field-shape reason. The
   reachable trio is `valueShapeExpression`, `listExpression`, and `collectFromValueShape`.

4. **Child-coordinate argument expression** (`ArgCallEmitter`, the legacy method-backed emitter). A
   child `@service` field's DataLoader rows-method renders its call args through
   `ArgCallEmitter.buildArgExtraction` (the `switch (param.extraction())` at `:272`), reached via
   `buildMethodBackedCallArgs` (`TypeFetcherGenerator:4978`). Add a **real** `case
   CallSiteExtraction.JooqRecord jr` arm there, paralleling the live `InputBean` arm
   (`:296`, `buildInputBeanCallExtraction`): emit `create<Record>(env.getArgument("<name>"))` or
   `create<Record>List(...)`, picking the helper by `isListShaped(param)` (`:329-333`, the existing
   `List<…>` / `Set<…>` test) and naming it from `jr.table().recordClass().simpleName()`. This emits
   the identical expression the root emitter does; the construction lives in the one shared helper.
   (The sibling `NodeIdDecodeRecord` arm at `:298` stays a throw — that leaf is *only* ever an
   `InputBean` field leaf, never a top-level `param.extraction()`, so it is genuinely unreachable
   here. `JooqRecord` is a top-level `param.extraction()`, like `InputBean`, so its arm is real, not
   a throw.)

5. **Helper emitter** (`JooqRecordInstantiationEmitter`, new, sibling to
   `InputBeanInstantiationEmitter`; driven by a `CallSiteExtraction.JooqRecord` dedup queue, keyed by
   record class, collected in `TypeFetcherGenerator` by the same dual walk the `InputBean` helper
   queue uses (`:580-619`). The `MethodBackedField.callParams()` walk (`:588-594`) — which sees both
   child `@service` fields and the four root permits during the additive cutover — picks up the full
   `CallSiteExtraction.JooqRecord` directly; the `ServiceField`-carrier walk
   (`collectBeanHelpersFromCarrier` -> `collectFromValueShape`, `:596-600` / `:1622`) reads it off
   `ValueShape.JooqRecordInput.carrier()`. Both feed one map, so a record reached by either coordinate
   emits its helper exactly once. There is no transitive collection: a jOOQ record param never nests
   another, so no `collectTransitively` analogue is needed.) A deduped pair per record class, emitted
   together unconditionally (exactly as
   `InputBeanInstantiationEmitter` emits its singular + plural pair, `:47-116`): a singular
   `private static <Record> create<Record>(Map<String, Object> raw)` and a plural
   `private static List<Record> create<Record>List(Object raw)` that delegates to it. The singular:

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

   **Two helpers, one construction site, both cardinalities.** The singular `create<Record>(Map)`
   holds the construction. The plural `create<Record>List(Object raw)` is emitted alongside it
   unconditionally and delegates: it casts the `Object` to `List<?>`, rejects null elements, and maps
   each through the singular helper (the per-element `Map<String,Object>` cast carries the same
   `@SuppressWarnings("unchecked")` as `InputBeanInstantiationEmitter.buildPluralHelper`, `:94-116`).
   The singular param calls `create<Record>(env.getArgument(arg))`; the `List<Record>` param calls
   `create<Record>List(env.getArgument(arg))` (the wire value for a `[Input!]` arg is a
   `List<Map<String,Object>>`). Burying the `List<Map>` downcast in the plural helper keeps the call
   site cast-free, where an inline `.stream()` in `listExpression` would need an explicit unchecked
   cast; the construction logic still lives in one place, the same way R195's `decode<Record>List`
   delegates to `decode<Record>` per element (`InputBeanInstantiationEmitter.buildRecordDecodeHelperList`).

**Validator.** All rejections above are `Rejection.structural` -> `Resolved.Rejected` ->
`UnclassifiedField` (`ServiceDirectiveResolver.java:155-158`), which `ValidateMojo` fails on by
default -- the same validate-time surface R195's gate uses. Adding the new permit makes the sealed
compiler flag every exhaustive `CallSiteExtraction` switch: `InputBeanInstantiationEmitter.perFieldValueExpr`
-> `notALeaf` throw (genuinely unreachable -- `JooqRecord` is never an `InputBean` field leaf, only a
top-level `param.extraction()`); `ArgCallEmitter.buildArgExtraction` -> a **real** arm (the child
`@service` call site, step 4 above), *not* a throw, since a child `@service` reaches it exactly as
the live `InputBean` arm does; the walker -> real `JooqRecordInput` handling.

## Files

(The sealed-arm addition forces most of the rest via compile errors; one commit unless the seams add
review value.)

- `model/CallSiteExtraction.java`: add the `JooqRecord` permit (carrying `table`; the record class is
  `table.recordClass()`, no separate component) and the nested `ColumnBinding` / `RecordKeyDecode`
  records, with the compact-constructor invariants. Javadoc producer (`InputBeanResolver`) and
  consumers (the helper emitter, the walker, the child-coordinate `ArgCallEmitter` arm), keeping the
  cross-references live per "Documentation names only live tests/code".
- `model/ValueShape.java`: add the `JooqRecordInput(CallSiteExtraction.JooqRecord carrier, ArgPath sdlPath)`
  permit (a path-carrying leaf that also carries its construction carrier, like `Scalar`;
  `javaType()` is `carrier.table().recordClass()`).
- `InputBeanResolver.java`: the `JooqTableRecordInputType` branch -> `JooqRecord` (table off the
  classified type, column-axis field walk, single-`@nodeId` decode with the lifted mismatch gate,
  the rejections). Factor the column/key resolution so the message discipline matches R195's
  `buildJooqRecordLeaf`.
- `walker/ServiceMethodCallWalker.java`: `case CallSiteExtraction.JooqRecord jr` ->
  `new JooqRecordInput(jr, path)`, wrapped in `ValueShape.ListOf` when
  `isListType(javaType, jr.table().recordClass())` (the `InputBean`-arm idiom at `:158-162`).
- `generators/ServiceMethodCallEmitter.java` (root coordinate): `case ValueShape.JooqRecordInput` in
  `valueShapeExpression` (singular, `create<Record>`) and `listExpression` (the `ListOf` element
  switch, `create<Record>List`); plus the forced defensive arm in `outerArgOf(ValueShape)`.
- `generators/ArgCallEmitter.java` (child coordinate): a **real** `case CallSiteExtraction.JooqRecord`
  arm in `buildArgExtraction` emitting the same `create<Record>` / `create<Record>List` call,
  paralleling the live `InputBean` arm (`:296`). (The genuinely-unreachable `CallSiteExtraction`
  switch — `InputBeanInstantiationEmitter.perFieldValueExpr` — takes a `notALeaf` throw.)
- `generators/JooqRecordInstantiationEmitter.java` (new): the singular `create<Record>(Map)` helper
  plus the plural `create<Record>List(Object)` helper that delegates to it, emitted together
  (mirroring `InputBeanInstantiationEmitter` `:47-116`).
- `generators/TypeFetcherGenerator.java`: collect `CallSiteExtraction.JooqRecord` carriers into a
  record-class-keyed dedup queue via the same dual walk as the `InputBean` queue (`:580-619`) — the
  `MethodBackedField.callParams()` walk and the `ServiceField`-carrier walk
  (`collectFromValueShape` gets a real `JooqRecordInput` arm reading `.carrier()`; the bean-field-walk
  `ValueShape` switches take defensive arms) — and drive the new emitter. No `collectTransitively`
  analogue (jOOQ record params don't nest).

## Tests

Per "Pipeline tests are the primary behavioural tier"; compilation and execution tiers backstop the
type- and behaviour-correctness. No generated-body string assertions at any tier.

- **Pipeline** (`JooqRecordServiceParamPipelineTest`, modeled on `NodeIdRecordInputBeanPipelineTest`):
  - Single-key: `ModifyFilmInput { filmId: ID! @nodeId(typeName:"Film"), title: String @field(name:"title"), rentalRate: Float @field(name:"rental_rate") }`
    -> param classifies to `CallSiteExtraction.JooqRecord`; assert the arm, the resolved
    `table.recordClass`, the two `ColumnBinding`s with their `ColumnRef`s, the `RecordKeyDecode`
    `sdlFieldName` + typeId + single key column (the arm assertion is itself the pin that the param
    did not bean-ify or stay `Direct`). Assert `createFilmRecord` lands on the `*Fetchers` class.
  - Composite-key: a `film_actor`-backed record param with `id: ID! @nodeId(typeName:"FilmActor")`
    -> `RecordKeyDecode` carries both key columns (arity 2).
  - **List param** (`modifyFilms(List<FilmRecord>)` against `[ModifyFilmInput!]!`): the same
    `CallSiteExtraction.JooqRecord` arm, but the derived `ValueShape` is `ListOf(JooqRecordInput)`;
    assert the `ListOf` wrap (the pin that cardinality is handled, not dropped) and that the list call
    site is `createFilmRecordList`, which delegates per element to the *same* singular `createFilmRecord`
    (one construction site, mapped). This is the consumer's real shape
    (`endreUtdanningsspesifikasjonsstatus`), so it is a first-class case here, not a follow-up.
  - **Regression pin for the original bug**: a `List<FilmRecord>` param whose input type carries only
    `@nodeId` + `@field` (no member-name overlap) must classify to `JooqRecord`, *not* an
    `UnclassifiedField` "has no fields matching" — the red-test that fails on `main` today and the
    proof the list shape no longer reaches `buildInputBean`.
  - **Child `@service` coordinate** (the parity pin for the `ArgCallEmitter` arm): a `@table`-parent
    field whose nested `@service` method takes the parent key (`Sources`) plus a `FilmRecord` arg
    against `ModifyFilmInput`. Assert the arg classifies to `CallSiteExtraction.JooqRecord` and the
    child rows-method's call site emits `createFilmRecord` through `ArgCallEmitter` (real arm), not a
    throw and not the old "has no fields matching". This is the pin that the binding is
    coordinate-agnostic — `enrich` runs for child `@service` too — and that the `ArgCallEmitter` arm
    is not the "unreachable" throw an earlier draft assumed.
  - Rejections (on `UnclassifiedField.reason()`): foreign-table `@nodeId` (R195-mismatch message
    naming both records); two `@nodeId` fields; a `@field` / bare field resolving to no column on
    the record's table (the honest replacement for "has no fields matching", with the candidate
    hint); a cardinality mismatch (Java `List<Record>` against a singular input-object arg, and a
    singular `Record` against `[Input!]`) rejected at the shared `:180-188` parity gate rather than
    emitted as a `create<Record>List` / `create<Record>` call that throws `ClassCastException` at
    runtime. Assert on both the singular and `List<…>` param so neither path regresses to the old
    misleading message.
- **Compilation** (`graphitron-sakila-example`): add the fixtures below; the `-Plocal-db` compile
  against the real catalog verifies the emitted `new FilmRecord()` + `fromArray(..., Tables.FILM.<col>...)`
  references type-check (the hard backstop for "columns belong to `table`"), and that the singular
  `create<Record>` helper uses `fromArray` (R195 coercion discipline) rather than the deprecated
  `DataType.convert(Object)`, so it needs no `@SuppressWarnings`. (The plural helper's per-element
  `@SuppressWarnings("unchecked")` is the same one the `InputBean` plural helper already carries and
  is expected.) The child-coordinate fixture compiles the `ArgCallEmitter` arm's emitted call, so a
  mismatch there fails the cross-module build the same way the root arms do.
- **Execution** (`GraphQLQueryTest`-family, alongside the R195 `assignFilmRecord` cases): `modifyFilm`
  round-trip -- encode a `Film` NodeId, pass `title` + `rentalRate`, assert the service reads back the
  decoded `film_id` plus the set `title` / `rental_rate` (identity decode + column coercion together).
  A wrong-type NodeId throws (lifted R195 throw-on-mismatch behaviour). Plus a `modifyFilms`
  list round-trip -- two `[ModifyFilmInput!]` elements with distinct NodeIds -> two constructed
  records, asserting the per-element decode + the one mapped helper (the consumer's `List` shape).
- **Fixtures**: `graphitron-sakila-service` gains `FilmRecordService.modifyFilmRecord(FilmRecord)` and
  `modifyFilmRecords(List<FilmRecord>)` (and a composite `FilmActorRecord` variant); `schema.graphqls`
  gains `ModifyFilmInput` + `modifyFilm` (singular) and `modifyFilms` (`[ModifyFilmInput!]!`), no
  `@table` on the input (the table comes from the record param). For the child-coordinate parity, a
  `@table`-parent field carries a nested `@service` taking the parent key plus a `FilmRecord` arg
  against `ModifyFilmInput`, so the compile and execution tiers exercise the `ArgCallEmitter` arm, not
  only the pipeline tier. Pipeline SDL fixtures live inline as in `NodeIdRecordInputBeanPipelineTest`;
  the `List<FilmRecord>` `TestServiceStub` method needed by the pipeline tier is added there (the
  existing `List<FilmRecord>` stubs are SOURCES-context, not root `@service` input params).

## User documentation

No new directive, Mojo goal, output format, or wire-protocol surface; this is a newly *bindable*
shape for the existing `@service` + `@field` + `@nodeId` directives. The *classification*
(`JooqTableRecordInputType`) is already documented (`docs/code-generation-triggers.adoc:194`) and in
the R281 corpus, so it is not re-recorded. What is new is the **call-site param binding**
(`CallSiteExtraction.JooqRecord` -> `create<Record>` helper); its triggers row goes on the `@service`
call-site surface near the `QueryServiceRecordField` rows (`:425-426`), not the type-classification
table. Follows the R195 / R200 accepted-shape precedent (no user-manual chapter).

## Out of scope

- **`Set<TableRecord>` param**: the root walker's `isListType` (`:222-229`) tests `List<X>` only, so
  a `Set<…>` param is not list-wrapped at the root coordinate (where it would misfire as a singular
  helper call). At the child coordinate `isListShaped` (`:329-333`) *does* match `Set<…>`, so a `Set`
  arg there emits `create<Record>List` and fails the consumer compile on `List` vs `Set`. Either way
  `Set` is deferred, inheriting the `InputBean` path's existing imperfect `Set` handling rather than a
  clean build-time rejection; a cheap follow-up if a consumer needs it (widen both tests once, both
  arms inherit it), no current consumer case. In scope by contrast: the jOOQ-record `@service` *input param* at **either**
  coordinate — root (emitted via `ServiceMethodCallEmitter`) and `@table`-parent child (emitted via
  `ArgCallEmitter`), singular or `List<…>`. The root list shape was this item's own motivating case;
  the child coordinate falls out of the same `enrich` rewrite (full `InputBean` parity). This is
  distinct from the child-coordinate `List<TableRecord>` *SOURCES* batch-key param, a different path
  entirely: that classifies to `SourceKey.Wrap.TableRecord` (`ServiceCatalog.java:824-831`),
  untouched here.
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

**Scope broadened to `List<TableRecord>`, 2026-06-16 (pre-Ready revision).** Driven by the consumer
case `endreUtdanningsspesifikasjonsstatus(List<UtdanningsspesifikasjonsstatusRecord>)`: the prior
draft scoped itself to the *singular* `TableRecord` param and deferred `List<TableRecord>` to Out of
scope — but the item's own motivating example is the list shape, so as written it would have shipped
without fixing the case that prompted it. Confirmed against the code that both cardinalities reach
the same `buildInputBean` rejection (`enrich` peels `List<…>` and binds on the element class), and
that the list arm is nearly free: the walker already wraps the `InputBean` element in
`ValueShape.ListOf` via `isListType`, and `listExpression` already maps a composite helper over the
list. Folded in: title, "The problem" (both-cardinalities paragraph), the walker `ListOf` wrap, the
emitter's second (`listExpression`) arm, the one-helper-serves-both note, list pipeline + execution
cases, the `List<FilmRecord>` fixture/stub, and the Out-of-scope flip (`List` in, only `Set` — the
shared `isListType` `List`-only boundary — deferred). The carrier model is unchanged from the
endorsed 2026-06-15 design; this is a cardinality-coverage widening, not a redesign.

**Reviewer revision, 2026-06-16 (pre-Ready; model + emitter fixes folded in).** A fresh review session
(distinct from the original review and the `List` broadening) endorsed the design and the carrier
model, and folded in two implementability fixes plus three clarifications, all surfaced by checking
the cited seams against the code:

1. *`RecordKeyDecode` could not be emitted as written.* The helper reads the wire NodeId by SDL field
   name (`raw.get("filmId")`), but the carrier named no field. `NodeIdDecodeRecord` never needed one
   because it rides as a `FieldBinding.leaf` and inherits `FieldBinding.sdlFieldName`; `RecordKeyDecode`
   is detached from the member axis (a bare `Optional` on `JooqRecord`), so it must carry its own key.
   Added `sdlFieldName` as the first component, mirroring `ColumnBinding` (the same argument that rules
   out reusing `NodeIdDecodeRecord`, applied to the Map key).
2. *List-helper shape was self-contradictory.* The emitter section said both "exactly as the existing
   `RecordInput` / `JavaBeanInput` arms" (a separate plural helper) and "no separate list helper" (an
   inline map). Resolved toward the established pattern: a deduped `create<Record>List` plural helper
   emitted alongside the singular and delegating per element, exactly as `InputBeanInstantiationEmitter`
   (`:94-116`) and R195's `decode<Record>List` (`:174-176`) do. The plural buries the `List<Map>`
   downcast, keeping the call site cast-free; the inline `.stream()` would have needed an explicit
   unchecked cast the sibling arms avoid.
3. *Clarifications.* (a) The problem statement no longer says bean-binding "never reads" `@field(name:)`;
   it reads it (via `bindingKey`, `:315`) but only as a member key, never resolving it to a column.
   (b) `RecordKeyDecode` carries no `nonNull`: the identity decode throws on a null/mismatched id
   regardless of SDL nullability (R195 `ThrowOnMismatch`). (c) The singular emitter arm no longer claims
   to reuse `compositeHelperCall` verbatim (`JooqRecordInput` has no `fields`; it reads the arg name off
   its own `sdlPath`), and the compilation-tier assertion is scoped to the singular helper's
   no-deprecated-`convert` discipline (the plural helper legitimately carries the same per-element
   `@SuppressWarnings("unchecked")` as the `InputBean` plural helper).

The carrier model and overall design are otherwise unchanged from the endorsed 2026-06-15 shape.

**Reviewer revision, 2026-06-16 (child-coordinate parity + model/citation fixes).** A fresh review
session (distinct from the original review, the `List` broadening, and the prior reviewer revision)
re-verified every cited seam against the live code, endorsed the carrier model and the diagnosis (the
latter corroborated by `ServiceCatalog.looksLikeSourcesShape`'s own comment, `:744-753`: root
`List<XRecord>` is "the canonical `InputBeanResolver` shape ... must fall through"), and surfaced one
substantive reachability gap plus four model/citation fixes. The author directed full `InputBean`
parity. All folded in:

1. *The `ArgCallEmitter` arm was wrongly marked "unreachable".* `enrich` runs unconditionally for
   child `@service` too (`ServiceDirectiveResolver:155`, before the `isRoot` gate), and a child
   field's DataLoader rows-method emits via `ArgCallEmitter.buildMethodBackedCallArgs`
   (`TypeFetcherGenerator:4978`) — the same switch whose `InputBean` arm (`:296`) is *live*. So a
   child `@service` jOOQ-record arg reaches that switch exactly as `InputBean` does; an "unreachable"
   throw would crash generation on a valid schema (against "Validator mirrors classifier invariants").
   Resolved toward **full `InputBean` parity**: a real `case CallSiteExtraction.JooqRecord` arm in
   `ArgCallEmitter` (step 4), emitting the same `create<Record>` / `create<Record>List` call as the
   root emitter; child `@service` is now in scope (Out of scope updated), with a pipeline parity pin
   plus a child compile/execution fixture. The genuinely-unreachable switch
   (`InputBeanInstantiationEmitter.perFieldValueExpr`) keeps its `notALeaf` throw —
   `JooqRecord` is never an `InputBean` field leaf.
2. *`JooqRecord.recordClass` was redundant with `table.recordClass()`.* A `JooqTableRecordInputType`'s
   `table` is resolved by matching `getRecordType()` against the backing class, so `fqClassName` and
   `table.recordClass()` name the same class by construction; R195's `NodeIdDecodeRecord` carries only
   `table` and derives the class. Dropped `recordClass`; the record class now reaches every consumer
   via `table.recordClass()`.
3. *The "forces *both* switches" count was wrong.* Adding `ValueShape.JooqRecordInput` flags several
   exhaustive `ValueShape` switches; only three are reachable for a top-level shape
   (`valueShapeExpression`, `listExpression`, `collectFromValueShape`), the rest (`outerArgOf`, the
   bean-field-walk switches) take compiler-forced defensive arms since a `JooqRecordInput` is never an
   `InputBean` field shape. Section 3 and the Files list now say so.
4. *`JooqRecordInput` reframed.* It is a path-carrying leaf (like `Scalar`), not a pathless composite
   like `RecordInput`; it now carries its construction carrier (`CallSiteExtraction.JooqRecord`) — the
   `Scalar`-carries-`leafTransform` precedent — so the carrier-walk collector can register the helper
   from the `ValueShape` alone, giving the helper queue true `InputBean` dual-walk parity.
5. *Citation drift.* Re-anchored the `NodeIdDecodeRecord` javadoc and `decode<Record>List` cites on
   their symbols rather than drifted line ranges.

Status stays Spec. This reviewer session landed substantive edits, so per the reviewer rule it is
disqualified from approving the resulting revision; a fresh session (different from the original
review, the `List` broadening, the prior reviewer revision, and this one) gates Spec -> Ready.

**Reviewer revision, 2026-06-16 (cardinality-parity guard + Set-deferral framing).** A fresh review
session (distinct from the original review, the `List` broadening, and the two prior reviewer
revisions) re-verified every cited seam against the live code and endorsed the design and carrier
model unchanged. It surfaced one substantive gap plus one framing fix, both folded in:

1. *The jOOQ-record branch dropped the cardinality-parity guard.* The prior draft sited the branch
   "before `looksLikeBeanCandidate`", which is also before the `elt.list() == sdl.list()` check
   (`:180-188`). The walker derives list-ness from the Java type alone (`isListType`, `:158-162` /
   `:222-229`); that is sound for the bean path only because `:180-188` already guaranteed Java/SDL
   cardinality parity upstream. Without it, a `List<Record>` param against a singular input-object
   arg (or a singular `Record` against `[Input!]`) would reach `create<Record>List` / `create<Record>`
   against the opposite wire shape and throw a runtime `ClassCastException` instead of failing at
   validate time (against "Validator mirrors classifier invariants"). Resolved: the branch now sits
   after the shared input-object gates (`:150-188`) and just before `buildInputBean`, inheriting the
   parity check; step 1 carries an explicit bullet, step 2 notes the walker's reliance on it, and a
   cardinality-mismatch rejection joins the pipeline-tier rejection set.
2. *The `Set` out-of-scope justification was root-only.* "The same `List`-only boundary" described
   the root walker's `isListType`, but the child emitter picks the helper via `isListShaped`
   (`:329-333`), which matches `Set<…>` too. Reworded so the deferral notes both coordinates' actual
   (imperfect) `Set` handling rather than implying a clean `List`-only gate.

The carrier model and overall design are unchanged from the endorsed shape. This session landed
substantive edits, so per the reviewer rule it is disqualified from approving the resulting revision;
a fresh session (different from the original review, the `List` broadening, and all three reviewer
revisions) gates Spec -> Ready.
