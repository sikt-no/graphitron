---
id: R222
title: "Input model: dimensional pivot under visitor-driven classification"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-23
---

# Input model: dimensional pivot under visitor-driven classification

The input-type classification surface has been the most-churned model area in the rewrite over the last quarter: R94, R96, R150, R155, R178, R191, R205, R210, R211, R215 all landed surgical patches on it, and Backlog still carries R171, R97, R213, R209, R200, R195, R98, R220, R193, R172 on the same surface. R164 (`field-model-two-axis-pivot`) names the underlying disease one layer over: a sealed hierarchy that tries to encode a multi-dimensional space as a one-dimensional permit set. Input types are the same disease in a smaller organ; this item performs the pivot on inputs first, as the proof-of-concept for R164 and as the natural home for an R166-style visitor walk on the smaller surface.

## What is

`GraphitronType` permits two sibling input-like roots: `InputType` (four leaves at `GraphitronType.java:323-385`) and `TableInputType` (one record at `GraphitronType.java:400-407`). The five permits today encode three independent axes onto one identity slot:

| Permit | Backing-class kind | Table-bound? | Eager column classification? |
|---|---|---|---|
| `PojoInputType` | POJO (or null) | no | no |
| `JavaRecordInputType` | Java record | no | no |
| `JooqRecordInputType` | jOOQ `Record<?>` | no | no |
| `JooqTableRecordInputType` | jOOQ `TableRecord<?>` | yes, from backing class | no |
| `TableInputType` | (graphitron-emitted from R94) | yes, from `@table` directive **or** consumer chain | yes, `List<InputField>` |

Three axes (*backing-class kind*, *table-binding and where the binding came from*, *whether fields are eagerly column-classified*) collapsed onto five permit identities. The cross product is sparse and lopsided:

- The "table-bound × non-jOOQ-TableRecord-backed × eager-classified" cell is `TableInputType`.
- The "table-bound × jOOQ-TableRecord-backed" cell is `JooqTableRecordInputType`, and **the table comes from a different source** (reflection on the backing class vs `@table` directive vs consumer inference).
- The "unbound" row has four permits that differ only on backing-class kind.

Two further signals point at the encoding being the wrong shape:

1. `TypeBuilder.buildInputType` (`TypeBuilder.java:1000-1026`) already classifies a directiveless input as `TableInputType` when exactly one consumer's return type is a `@table` type, via `findReturnTablesForInput` (an O(N) back-scan over all schema fields). Whether an SDL input is "table-bound" is *already* a property of the consumer chain, not the input declaration; it is encoded today as a back-scan that runs once per input rather than once per use.

2. R215's lift (In Review) admits `InputField.UnboundField` into `TableInputType.inputFields()`, so the column-coverage invariant that previously distinguished `TableInputType` from `InputType` (one classifies columns eagerly, the other doesn't) has eroded. The classifier-side commitment is now "best-effort eager, deferred to consumption on miss", which collapses the third axis.

The capability marker `HasValidationShape` (`HasValidationShape.java:17-22`) already has to list five permits to span both branches; R171 (Backlog) names this as the immediate symptom and proposes a `sealed interface InputLikeType` parent. That fix is structural and leaves the cross-product encoding intact.

Nine consumer sites discriminate on the split today:

- `GraphitronSchemaValidator.java:80-81`: case arms on `InputType` and `TableInputType`.
- `MutationInputResolver.java:368`: `instanceof GraphitronType.TableInputType` gate.
- `EnumMappingResolver.buildLookupBindings(GraphitronType.TableInputType, ...)` (`EnumMappingResolver.java:303`).
- `CatalogBuilder.java:206 / 498 / 570 / 639`: four switch sites on `TableInputType`.
- `FieldBuilder.java:967`: `instanceof GraphitronType.TableInputType` arm in `classifyInputFieldOnArg`.
- `TypeBuilder.java:209-210`: the type-pivot in the synthesis pass.

Each consumer asks "does the input have a table?" by switching on the permit identity. None of them care about the backing-class kind on the unbound side.

## What's to be: input-side building blocks; R164 owns the field-level form

The pivot ships input-side building blocks, not a field-level form. The field-level EITHER/OR — does this output field emit SQL (paths 1 and 2 of §"Three consumer paths" below) or hand off to a domain method (path 3)? — is R164's territory; R222 names the seam and reserves the building blocks both arms reuse, without shipping the field-level discriminator. What R222 ships:

- `Input` — the SDL declaration, consumer-independent.
- `BackingClass` — a three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) naming the *user's declared materialization target* for a domain-form method param. One `BackingClass` per service-method param, not one per input; the per-param attachment lives on R164's domain-form arm. Not a slot on any R222-introduced model record.
- `InputFieldDecl` — the SDL field declaration, pre-binding. Wraps the `GraphQLInputObjectField` so applied directives (`@nodeId`, `@reference`, `@field(name:)`, `@condition`) are readable at consumption time.
- `InputField` arms — the post-classification SQL-side carriers (`ColumnField` and friends, `NestingField`, R215's `UnboundField`, R122's incoming `TableTargetField`).
- `InputUsage` — the SQL-side recursive classified-state carrier. Produced only on SQL-form fields; carries `(input, table, classifiedFields)` with no Optional slots. The recursion is structural: `InputField.NestingField.nested` is a child `InputUsage`.
- `InputTypeArg` — the SQL-form arg-site wrapper, carrying a root `InputUsage`.

What R222 explicitly does *not* ship: the field-level `FieldInputForm` sealed family (working names `SqlForm` / `DomainForm`), the domain-form arg carrier, the method-signature mapping subsystem. Those land in R164. R222's Phase 3 visitor consumes R164's discriminator to decide whether to construct an `InputUsage` for each output field's args; until R164 lands, the visitor uses the existing field-level signals (the legacy `ServiceField` / `ExternalField` / `TableMethod` arms on the output side) as the discriminator (see Phase 3 for the bridge).

### Three consumer paths and the R164 seam

Graphitron's input data serves three diverging consumer paths. The first two are SQL-emission siblings; the third is mutually exclusive at the field level:

1. **WHERE generation (queries).** Graphitron emits `SELECT ... WHERE`. Reads `usage.table()` and `usage.classifiedFields()`. Walks `InputField` arms (`ColumnField`, `ColumnReferenceField`, `NestingField`, `TableTargetField` once R122 adds it, `UnboundField`). `@condition` contributes method-supplied predicates *interior to SQL emission* (see "@condition is SQL-side" below). `@reference`'s `joinPath` materialises JOINs to filter on referenced tables.

2. **DML generation (mutations).** Graphitron emits `INSERT` / `UPDATE` / `DELETE`. Same `(table, classifiedFields)` carrier as path 1. R144's WHERE-vs-SET partition (relocated to the consumer; see §"Why R144's partition reverses") operates on the same classified list plus the table's PK column set. R122's compound mutations recurse through `TableTargetField` arms; the parent INSERT captures the PK and threads it into child FK columns.

3. **Service handoff (`@service` / `@externalField` / `@tableMethod`).** Graphitron reflects the method's signature, populates the params via uniform reflection-mapping rules (see "Uniform method invocation" below), and calls. *Does not* read `classifiedFields`; *does not* construct an `InputUsage`. The whole field's input data is mapped to the method's signature; `BackingClass` lives per method-param on R164's domain-form arm. R164 owns the carrier shape; R222 reserves the seam.

At each output field, exactly one of {SQL-emission, service-handoff} is active — the field-level EITHER/OR. R164 ships the discriminator (the `FieldInputForm` sealed family with `SqlForm` and `DomainForm` arms, or whatever shape R164 lands on); R222's visitor reads it to decide whether to construct an `InputUsage` for the field's args. The field-level EITHER/OR is at the args-as-a-whole granularity, not per-arg: a domain method call doesn't spread across args (graphitron either maps the whole field's input shape to the method's signature or fails); SQL emission converts the whole field's input shape to a SQL-suited projection.

**@condition is SQL-side.** `@condition` lives interior to SQL emission, not as a fourth consumer. It is valid only on SQL-emitting fields and contributes method-supplied predicates the SQL emitter invokes as part of WHERE construction (with args bound by name). `override:true` suppresses graphitron's implicit predicates; the method's return is the WHERE. `@condition` on a domain-form field's arg is a `Rejection.AuthorError`; the form-discriminator (R164) enforces this when classifying the field.

**Uniform method invocation.** `@service`, `@externalField`, `@tableMethod`, and `@condition` all follow the same discipline at the method-call seam: type/shape matching with nominal fallback, with primitives as the degenerate case. R222's model assumes uniformity even where today's producer-side implementation is ad-hoc; unifying the producer is a separate task (see R164 and adjacent items). The principle: graphitron's input model bridges from the SDL author's declared field signature (one input arg, multiple args, deeply nested, flat) to whatever the method's signature demands. The SDL author has freedom; graphitron accommodates.

**@nodeId / @reference are universal.** Both directives' facts live on `InputFieldDecl`. The SQL-side classifier reads `@reference` to drive column/FK resolution (producing `ColumnReferenceField` / `CompositeColumnReferenceField` carriers, materialising JOINs via the path) and `@nodeId` to mark leaves for FK-target resolution. R164's domain-form reflection populator reads the same directives to drive per-field transforms (NodeID decode before assignment, etc.). The fact lives on the SDL declaration; each consumer interprets it through its own lens.

**No @service filter inputs.** An `@service` field whose arg's input fields carry SQL-side directives intended to drive WHERE generation (`@condition`, `@reference(joinPath:)` for filter-side joins, anything that implies "graphitron synthesises a WHERE from this") is architecturally invalid: domain-form fields do not flow through SQL emission, so the SDL author cannot ask graphitron to build a `Condition` from input fields and pass it to the user's method. R222 surfaces this as `Rejection.AuthorError` at the form-discriminator site when a domain-form field's arg carries directives whose only consumer is the SQL path. The theoretical "pass a generated Condition to a service" alternative is not implemented; do not design speculatively for it.

**@tableMethod's exact categorisation.** `@tableMethod` is grouped with `@service` and `@externalField` here as "the whole field's input data is mapped to the method's signature." It also emits SQL using the method-returned `Table`. Whether some args drive WHERE generation alongside the method call (hybrid) or all args go to the method (uniform with `@service`) is an open question this spec defers; the resolution belongs in R164's domain-form spec. R222's commitment is the building blocks reused either way.

### Records (the model)

```java
sealed interface Input extends GraphitronType, EmitsPerTypeFile permits Input.Of {

    ValidationShape validationShape();          // R94, always present, SDL-derived
    GraphQLInputObjectType schemaType();
    List<InputFieldDecl> fields();           // SDL field declarations, pre-binding
}

record Of(
    String name,
    SourceLocation location,
    GraphQLInputObjectType schemaType,
    ValidationShape validationShape,
    List<InputFieldDecl> fields
) implements Input {
    public Of {
        fields = List.copyOf(fields);
    }
}

sealed interface BackingClass {
    String fqClassName();
}
record Pojo(String fqClassName) implements BackingClass {}
record JavaRecord(String fqClassName) implements BackingClass {}
record JooqTableRecord(String fqClassName, TableRef table) implements BackingClass {}

/**
 * The SDL field declaration on an {@link Input}, before any binding has been
 * applied. Wraps the {@link GraphQLInputObjectField} so consumers can read the
 * applied directives ({@code @nodeId}, {@code @reference}, {@code @field(name:)},
 * {@code @condition}) at consumption time. The R222 SQL-side classifier and
 * R164's domain-form reflection populator read this carrier through different
 * lenses; the directive facts themselves are universal across both paths.
 *
 * <p>Distinct from {@link InputField}: that family is the post-classification
 * SQL-side carrier (column-bound / reference / nesting / target / unbound),
 * produced only on SQL-form arg occurrences.
 */
record InputFieldDecl(GraphQLInputObjectField rawField) {
    public String name()              { return rawField.getName(); }
    public GraphQLInputType type()    { return rawField.getType(); }
    public SourceLocation location()  { return rawField.getDefinition().getSourceLocation(); }
}

/**
 * An {@link Input} used at an SQL-emitting site: the recursive carrier of the
 * SQL-side classified state. Holds the SDL declaration, the effective
 * {@link TableRef} for column resolution at this site, and the classified
 * field list the SQL-side classifier produced against that pair.
 * {@link InputField.NestingField} arms in {@code classifiedFields} hold a
 * child {@code InputUsage}, so the SQL-side use-site context is explicit at
 * every tree node rather than inherited implicitly from a parent.
 *
 * <p><b>Scope.</b> An {@code InputUsage} exists only on SQL-form fields
 * (paths 1 and 2 of §"Three consumer paths"). On domain-handoff fields
 * (path 3) no {@code InputUsage} is constructed; R164's domain-form arm
 * consumes the field's args via uniform reflection-mapping against the
 * method's signature. The field-level EITHER/OR is R164's discriminator;
 * R222's visitor reads it to decide whether to build an {@code InputUsage}
 * for the field's args.
 *
 * <p><b>No backing-class slot.</b> {@link BackingClass} is the user's
 * declared materialization target on R164's domain-form arm (one per
 * service-method param), not graphitron's internal SQL carrier. For SQL
 * emission the emitter walks {@code classifiedFields} directly (no DTO
 * target needed); INSERT/UPDATE construction may use a jOOQ
 * {@code TableRecord} internally, but that is an emitter implementation
 * detail, not a model slot.
 *
 * <p><b>No Optional table.</b> An SQL-emitting use site has a target table
 * by construction (path 1: the consumer's {@code @table} return; path 2:
 * the mutation verb's target). If the visitor cannot resolve a table at a
 * notionally-SQL-form arg, that is a {@code Rejection.AuthorError} and no
 * {@code InputUsage} is constructed for the arg.
 *
 * <p>The same {@link Input} can produce many {@code InputUsage}s: one per
 * SQL-form arg-site occurrence plus one per nested position transitively.
 * Equality is structural; identity is not load-bearing. The single producer
 * is the SQL-side classifier (visitor in Phase 3 onward); consumers read
 * {@code InputUsage} without rebuilding it.
 */
record InputUsage(
    Input input,
    TableRef table,
    List<InputField> classifiedFields
) {
    public InputUsage {
        classifiedFields = List.copyOf(classifiedFields);
    }
}
```

The `Input` record carries what's true of the SDL declaration in isolation: name, location, the assembled-schema form, the SDL-derived per-input-type emit shape (R94's `ValidationShape`), and the pre-binding SDL field declarations. `Input` carries no backing-class slot.

`BackingClass` is a three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) naming *the user's declared materialization target* for a domain-form service-method param. It exists in R222 as a vocabulary the domain-form arm (R164) can attach to its per-param slot; R222 itself does not put `BackingClass` on any model record. The directives that used to carry "the deserialization target for this input" semantics (`@record(class:)` on input types) drop at this pivot — the materialization target is the user's declared service-method param type, read by reflection at the domain-form classification site, not an input-side directive. See §"`@table` and `@record(class:)` on inputs are dropped" below. A service-method param typed as a jOOQ `Record` subclass that is *not* a `TableRecord` (UDT records, embeddable records, custom Record subclasses) surfaces `Rejection.AuthorError("backing class %s is a jOOQ Record but not a TableRecord; supported non-table backings are Java record or POJO")`; the embedded-record case lands as a follow-up (R234).

`InputFieldDecl` is the SDL declaration (name, type, location, applied-directive access) before any binding is applied. It is consumer-independent: both R222's SQL-side classifier and R164's domain-form reflection populator read it.

`InputUsage` is the SQL-side recursive classified-state carrier: it pairs an `Input` with the effective table and the classified fields produced for that pair. The legacy "use-site application" framing collapsed two consumers into one carrier (SQL projection and reflection-populate-and-call as interpretations of the same data); the corrected model scopes `InputUsage` to SQL emission alone and lets the domain-form arm (R164) own its own arg carrier.

`InputField.NestingField` flips from `List<InputField> fields` to `InputUsage nested` (see Phase 1 below). The recursion that was implicit in the legacy "nested fields inherit parent table" prose becomes a structural property of the model: a nested input's use-site context is whatever the parent's SQL-side classifier produced for it, carried explicitly on the nested `InputUsage`. `NestingField` is reserved for the same-table SDL-grouping case (per `InputField.java:186-195`); the cross-table case — where the nested input crosses a table boundary via `@reference(path:)` — is R122's `TableTargetField` arm, not a `NestingField` variant.

```java
record InputTypeArg(
    String name,
    InputUsage usage,                             // SQL-side classified-state carrier, root of the recursive tree
    boolean nonNull,
    boolean list,
    Optional<ArgConditionRef> argCondition
) implements ArgumentRef {
    @Override public String typeName() { return usage.input().name(); }
    public Input input() { return usage.input(); }
    public TableRef table() { return usage.table(); }
    public List<InputField> classifiedFields() { return usage.classifiedFields(); }
}

// Visit-site coordinate lives on a thin wrapper, not on InputTypeArg itself, so
// the arg record cannot be constructed with a wrong coordinate at a non-visit-time
// call site (e.g. a test fixture). R164's field-level form may absorb the
// coordinate (the field is the natural carrier); the wrapper exists today as a
// transition shape.
record ArgumentOccurrence(SchemaCoordinate consumer, InputTypeArg arg) {}
```

`InputTypeArg` is the SQL-form arg-site wrapper: a name, the nullability/list flags, the optional arg condition, and the root `InputUsage`. The convenience accessors (`input()`, `table()`, `classifiedFields()`) delegate to the usage carrier. `InputTypeArg` exists only on SQL-form fields; on domain-form fields R164's domain-form arm carries the args directly to its method-signature mapper without an SQL-shaped per-arg wrapper.

The `Optional<ArgConditionRef> argCondition` slot is intentionally per-arg, not per-field: `@condition` can decorate an SDL arg directly (an arg-condition directive that contributes a predicate scoped to that arg's classified projection). It is *not* the same surface as `@condition` on an input *type*, which lives on `Input.schemaType()` and contributes a field-wide predicate. Per-arg `argCondition` does not violate Principle 1 (EITHER/OR at args-as-a-whole): the field-level form discriminator decides SQL-vs-domain for the whole field; once SQL-form is chosen, per-arg `argCondition` is interior to SQL emission, not a competing discriminator.

`ArgumentOccurrence` is the thin wrapper R222's visitor produces at the visit site (on SQL-form fields). It carries the coordinate (`Query.films` etc.) and the `InputTypeArg`. The wrapper exists so the coordinate cannot drift from the arg. **Retirement criterion**: when R164's field-level form lands carrying the field's `SchemaCoordinate` on its sealed-family parent, `ArgumentOccurrence` retires and consumers read the coordinate off the field-level form. The trigger is concrete (R164 ships its form-level coordinate slot), not "R164 may decide": if R164's form doesn't carry the coordinate, `ArgumentOccurrence` stays. The retirement PR is a mechanical accessor swap.

No `ConsumerBinding` record exists. The table fact lives on `InputUsage.table()`; the consumer coordinate that drove the inference lives on the enclosing `ArgumentOccurrence` (or, post-R164, on the field-level form). One slot for "the table at this use site," not two halves to compose.

### `@table` and `@record(class:)` on inputs are dropped

The pivot drops two directives as binding sources on `INPUT_OBJECT`. Each is read once at classification, surfaced as a `BuildWarning` via `ctx.addWarning(...)` (the existing channel `TypeBuilder.emitDirectiveIgnoredWarnings` already uses for the legacy "shadowed by `@table`" case), and then ignored for binding purposes. Phase 6 narrows both SDL directive declarations to drop `INPUT_OBJECT` and sweeps every decorated SDL input across fixtures.

**`@table` on input.** Table-binding sources collapse to one: the enclosing field's `@table` return (consumer-derivation). The realistic consequence: an SDL input decorated `@table` used at a `@table`-returning consumer keeps the same effective `InputUsage.table()` via consumer-derivation; the warning surfaces the now-redundant decoration. An input decorated `@table` and used only at consumers that do *not* return a `@table` type loses its (SQL-side) binding entirely — those consumers are either domain-form (no `InputUsage` is constructed at all) or architectural errors (an SQL-form field with no resolvable table). Sakila's 16 `@table`-decorated inputs all consume at `@table`-returning SQL-form consumers (`Query.films(filter: FilmConditionInput!): [Film @table]` etc.); the Phase 6 sweep confirms no orphans.

**`@record(class:)` on input.** The directive's "type the deserialization target" function collapses entirely. The materialization target is the user's declared service-method param type (`BackingClass`), read at the domain-form classification site by reflection on the method signature — not an input-side directive. The per-`Input` emit produced by `InputRecordGenerator` is for Jakarta-validation use only, not a DTO target — graphitron's SQL-side emitter walks `InputUsage.classifiedFields` directly without materialising the input into a Java object, and graphitron's domain-form arm hands the args to the user's method without producing a graphitron-side DTO. The directive's secondary "skip codegen, use my class" role is dropped: graphitron never produced DTOs as a binding target, only as a Jakarta-validation type, and the validation type is consistently emitted from the SDL shape.

The realistic consequence: a `@record(class:)` annotation on a table-bound input was always redundant for SQL-side classification (the SQL emitter doesn't materialise the input into a Java class), so removing the directive is a no-op for those sites. A `@record(class:)` annotation on a domain-form input either matches the user's service-method param type (in which case the directive is redundant — the param type wins via reflection) or contradicts it (in which case the directive was masking an inconsistency that the domain-form arm surfaces as `Rejection.AuthorError`). The fixture sweep in Phase 6 confirms each removal lands cleanly.

### Directives the new model carries

The pivot affects how `@table` and `@record` resolve into the model; other input-related directives stay on their existing resolution paths. Mapping for the reviewer:

| Directive | Pre-pivot home | Post-pivot home |
|---|---|---|
| `@record(class: ...)` on input type | identity of the four `*InputType` permits (carried as the SDL hint that selected the permit) | **ignored at classification**; the visitor emits a `BuildWarning` at the directive's `SourceLocation` ("@record(class:) on input types is deprecated; the materialization target for domain-form fields is the user's declared service-method parameter type"). No model record on the R222 side carries a per-input "backing class" slot; `BackingClass` lives per service-method param on R164's domain-form arm. The per-`Input` Jakarta-validation type is emitted from the SDL shape regardless |
| `@table` on input type | `TableInputType.table` (legacy) / `JooqTableRecordInputType.table` (reflected) | **ignored at classification**; the visitor emits a `BuildWarning` at the directive's `SourceLocation` ("@table on input types is deprecated; binding is derived from the consumer's @table return"). Binding resolves from the enclosing field's `@table` return (consumer-derivation) and lands on `InputUsage.table()` |
| `@condition` on input *type* | read from the assembled `GraphQLInputObjectType` via `TypeBuilder.isUsedWithOverrideCondition` | unchanged: read from `Input.schemaType()` (the assembled-schema slot is preserved). `@condition` is interior to SQL emission; the consumer that reads it is the WHERE-construction path, not a separate model carrier |
| `@inputBean` / `@enumMap` / `@field(name:)` on input *fields* | read by `InputBeanResolver`, `EnumMappingResolver` from the assembled-schema field | unchanged: those resolvers continue to consult the assembled-schema; R200 / R195 / R98 stay scoped where they are |
| `@reference(path:)` on input *fields* | read by `NodeIdLeafResolver` for `@nodeId` leaves (FK-target resolution onto a `ColumnReferenceField` / `CompositeColumnReferenceField` carrier) | unchanged on the `@nodeId` path; **R122 extends the directive's reach to nested-input slots** (the path's terminal element drives the nested `InputUsage.table` distinct from the parent's, wrapped in R122's new `TableTargetField` arm — *not* `NestingField`, which stays reserved for same-table SDL grouping). R222 preserves the directive's existing applicability (`INPUT_FIELD_DEFINITION` stays in the declaration) and does not touch the `@nodeId`-path resolver. Phase 6's directive-narrowing sweep covers `@table` and `@record(class:)` only; `@reference` is out of scope |
| `@nodeId(typename: ...)` on input *fields* | read by `NodeIdLeafResolver` on the SQL-side path | universal: SQL-side classifier reads it to mark FK-target leaves; R164's domain-form reflection populator reads the same directive to decode the NodeID before assigning to the method param. The fact lives on `InputFieldDecl.rawField`'s applied-directive list; each consumer interprets it through its own lens |

Anything not in this table is read from `Input.schemaType()` exactly as today, since the pivot preserves the assembled-schema reference on the model record.

### What collapses

| Pre-pivot | Post-pivot |
|---|---|
| `GraphitronType.InputType` 4-arm sealed root | `Input` (no backing-class slot). The `BackingClass` three-arm family (`Pojo`, `JavaRecord`, `JooqTableRecord`) names the *user's declared service-method param type* on R164's domain-form arm; it is not attached to any R222-introduced model record. The legacy `JooqRecordInputType` arm (jOOQ `Record<?>` non-table subclass at a domain-form param) is rejected with `Rejection.AuthorError`; the embeddable-record and UDT-record cases land as R234 |
| `GraphitronType.TableInputType` sibling root | `Input` + `InputUsage.table()` populated by consumer-derivation at the SQL-form arg-visit site |
| `JooqTableRecordInputType.table` slot, `TableInputType.table` slot | Both collapse to `InputUsage.table` on SQL-form arg occurrences. Domain-form occurrences (where the legacy `JooqTableRecordInputType` matched a `TableRecord` service-method param) move to R164's domain-form arm; the table fact, when relevant there, lives on the param's `BackingClass.JooqTableRecord.table` slot — *the user's declared param type*, not graphitron's SQL projection |
| `TableInputType.inputFields` (stored, eager-classified) | `Input.fields()` carries the SDL declarations (`List<InputFieldDecl>`); `InputUsage.classifiedFields()` carries the classified list the SQL-side classifier produced at construction. Slot retires. See "Why InputUsage is the classified-state carrier" below |
| `HasValidationShape` capability marker (5 permits) | direct slot on `Input` |
| `ArgumentRef.InputTypeArg.TableInputArg` | merged into `InputTypeArg` (SQL-form only; the merge is feasible because the pivot's `InputUsage.table` is non-Optional by construction) |
| `ArgumentRef.InputTypeArg.PlainInputArg` | **structurally retires.** The non-table-bound arg pre-pivot was either (a) a domain-form arg (now an R164 domain-form occurrence; no `InputTypeArg` is constructed) or (b) a notionally-SQL-form arg with no resolvable table (now a `Rejection.AuthorError` at the visit site). Neither case lands in `InputTypeArg` post-pivot |
| `TableInputArg.lookupKeyFields` / `setFields` slots (R144 DmlKind-driven partition) | retired from the model; `MutationInputResolver` derives the partition on demand from `usage.classifiedFields()` plus the consuming verb's `DmlKind`. See "What this absorbs" below |
| 9 consumer-side discriminations on `TableInputType` (instanceof gates and switch arms) | retire; SQL-side consumers receive an `InputTypeArg` (table guaranteed) and read `arg.table()` / `arg.classifiedFields()` directly. Domain-form consumers do not appear on the SQL paths |
| `TypeBuilder.findReturnTablesForInput` back-scan | resolved at the visit site under the R166 walk |
| `InputField.NestingField.fields` (`List<InputField>`, parent-table-inherited) | `InputField.NestingField.nested` (`InputUsage`): same-table SDL grouping, structural recursion. The cross-table case (`@reference(path:)` on a nested-input slot terminal at a different table) is R122's new `TableTargetField` arm; it is not a `NestingField` variant |

The cross-cutting `GraphitronSchemaValidator.validateTableInputType` arm becomes a `walkInputs` pass that visits every SQL-form `InputTypeArg`; the per-permit-identity dispatch retires. The walk recurses into `InputField.NestingField.nested()` (and, post-R122, into `TableTargetField.nested()`) so nested-input validation uses the same machinery as root-level validation, rather than the parent-table-inheritance side path it walks today.

## Visitor-driven classification (R166 absorption for inputs)

Today's classifier is iteration-driven: `TypeBuilder` walks `schema.getAllTypesAsList()` once and classifies each `INPUT_OBJECT` in isolation. The table-binding decision is made per-input-type via the `findReturnTablesForInput` back-scan, which scans every schema field asking "who returns a `@table` type and consumes this input?" The decision is the wrong shape for two reasons: it's O(N) per input rather than O(1) at the use site, and it collapses divergent consumers (the `tables.size() > 1` arm at `TypeBuilder.java:1022` demotes the input to `InputType` even when each consumer has a coherent local table).

Under the pivot, classification is driven by `graphql.schema.SchemaTraverser.depthFirst(visitor, roots)` seeded with the consumer-reachable surface (root operation types). The visitor visits each field with its arguments in scope: when it reaches `Query.films(filter: FilmConditionInput!)`, the *visiting context* already carries the consumer's return type (`[Film @table]`) and the field's form (R164's discriminator: SQL-form or domain-form). On SQL-form fields, the root `InputUsage` for each arg is constructed at the visit site with `table` populated from the enclosing field's `@table` return. Nested input fields recurse: the classifier constructs a child `InputUsage` for each nested-typed slot, carrying the parent's effective table forward. On domain-form fields, no `InputUsage` is constructed; R164's domain-form arm handles the args. No back-scan.

Five consequences fall out structurally:

1. **Consumer-derived table resolution is local.** With `@table` on input dropped, consumer-derivation is the *only* per-arg table source on the SQL side. R97 Phase 2 becomes the default, not a flag flip; the consumer coordinate that drove the inference is carried on the enclosing `ArgumentOccurrence` (for the root) and is recoverable from the tree-walk for nested nodes. R97's Phase 1 (`argMapping` grouping) remains as follow-up; R97's Phase 3 (narrow directive scope + fixture sweep) lands as this item's Phase 6.
2. **Cross-consumer reuse works correctly.** `FilmConditionInput` used by two queries returning different tables produces two `InputTypeArg` carriers, each with its own root `InputUsage` (and its own `table`). The `tables.size() > 1` collapse retires.
3. **R213 dissolves.** Plain-input field rejections currently lose source location because the classifier is detached from the use site. The visitor sits *at* the input field when constructing the `InputUsage`; the `SourceLocation` it carries is the input field, not the consumer.
4. **Reachability falls out.** An SDL input never referenced from any reachable consumer produces no `InputTypeArg` (and no `InputUsage`). The `Input` record still exists for the per-type emit, but `InputUsage` construction is gated on visit. Subset of R166 Phase 1's reachability discipline. **Federation seed set:** the visitor seeds from operation roots only, not from `_Entity`-bearing types. Federation entity-fetch inputs decode at the `representations: [_Any!]!` wire boundary (handled by `EntityFetcherDispatch.resolveByReps` outside the model) and intentionally do not flow through the per-arg classification path. An SDL input used *only* as an argument to an entity-fetch service method without appearing on a reachable operation field is therefore unclassified by this walk; today's `schema.types()`-driven classifier produces the same effective outcome (the input has no classified consumer), but the spec names the decision explicitly so a future reader does not treat it as a regression.
5. **R209 re-homes.** The typed-rejection lift `FieldRegistry.classifyInput` defers today (because `InputFieldResolution.Unresolved` doesn't carry a typed `Rejection` payload) becomes structurally trivial: column-miss is detected at the `InputUsage` construction site, where the `TableRef` and the candidate-column list are both in scope. The `RejectionKind.AUTHOR_ERROR` default arm at `FieldRegistry.java:108-110` retires; typed `Rejection.AuthorError.UnknownName` with Levenshtein hint is the only path.

## What this absorbs from the open roadmap

| Item | Absorption mode |
|---|---|
| R171 (sealed `InputLikeType` parent) | Dissolves: capability declarations land on `Input` directly; the parent-root fix becomes moot. |
| R97 (deprecate `@table` on input types) | Phase 2 falls out structurally; Phase 3's runtime ignore + deprecation warning lands at Phase 3 of this item; Phase 3's SDL-narrowing + fixture sweep lands at this item's Phase 6. `argMapping` grouping (R97 Phase 1) remains as separate follow-up. |
| R213 (rejections at consumer field) | Visit-time source location. |
| R209 (FieldRegistry classify-input trace) | Typed rejection lift becomes trivial at the per-arg visit site. |
| R166 Phase 2 (`InputTypeGenerator` under visitor) | Subset: the input-side slice of R166's broader visitor-driven emission. |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves: `GraphitronSchemaValidator.validateTableInputType` becomes `validateInputArg` (Phase 4) that walks every `InputTypeArg.classifiedFields` uniformly. The `TableInputType.inputFields()` vs `PlainInputArg.fields()` split that motivated R221 retires under the pivot's unified `InputTypeArg`. |
| R144 (lookup-key / set-field partition stored on `TableInputArg`; `@value` directive marker on input fields) | Two reversals. (1) The partition leaves the classifier-side model and moves to the consumer. `arg.usage().classifiedFields()` is the single sealed list (produced by the classifier at `InputUsage` construction); `MutationInputResolver` derives the WHERE-vs-SET partition at consumption from that list plus the verb's `DmlKind`. (2) The `@value` directive is dropped. The partition mechanism becomes PK-derivation against the target table's primary-key column set (a fact the jOOQ catalog already carries); no per-input-field marker is needed. R144's cardinality-safety surface (`multiRow`, PK-coverage check) survives unchanged in shape. See the R144 rationale paragraph below. |

Items adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`): same disease (one-dimensional encoding of multi-dimensional space), different file. The pivot primes the pattern; those items apply it on the consumer-side surface independently.
- **R164**: a contract partner, not just downstream. R222 ships input-side building blocks (`Input`, `BackingClass`, `InputUsage`, `InputField` arms); R164 ships the field-level form discriminator (`FieldInputForm.SqlForm` / `DomainForm` or equivalent) that R222's visitor consults to decide whether to construct `InputUsage`. The dimensional pattern transfers; the contract along the field-level seam is novel to the pair.
- **R98** (multi-source input validation): once `HasValidationShape` becomes a direct slot on `Input`, attaching `ConstraintSet` to it is a one-site change. Lands as a follow-up.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`): these are about *naming binding* between SDL fields and Java members on the backing class. The pivot doesn't change naming resolution; both items stay in scope on `InputBeanResolver`.
- **R122** (compound-entity-mutations): the pivot is structurally enabling. R122 today carries open questions about how the SDL declares parent + children + FK relationships; under R222 the answers fall out: the root `InputUsage` is the parent (by construction); the child is a nested-input slot whose `@reference(path:)` names the FK; the recursive `InputUsage` tree already carries the parent table, the nested child table, and the column-classified projections both sides need. R222 does not implement the visitor arm or the mutation emitter (those stay R122's), but R122's design space narrows substantially. See §Phase 3's nested-resolution paragraph and §"Recursion through nested inputs" for the seam.
- **R226** (classification dimensional pivot: diagnostics off the model): forward-compatible. R222's `ctx.addWarning(...)` call shape and its `Rejection.AuthorError` emissions are R226-compatible by design: R222 leads its classification-failure language with "a `Rejection` is signalled through the classifier-output channel," treating today's `UnclassifiedArg` carrier as the surfacing rather than the contract. R226's eventual rewrite will retire the `Unclassified*` carriers in favour of `Diagnostic` events on a sink, touching the R222-introduced producers (the `@table`-on-input and `@record(class:)`-on-input deprecations in Phase 3, the no-resolvable-table rejection, the `@condition`-on-domain-arm rejection) and the `UnclassifiedArg` constructions in R222's Phase 3 implementation. No structural front-load of R226 in R222: the post-R222 rework is mechanical and R226's own scope is comparable to R164's, which the budget for R222 cannot absorb.

### Why R144's partition reverses

R144 committed the WHERE-vs-SET partition at classify time, on the carrier itself: `TableInputArg.lookupKeyFields()` and `TableInputArg.setFields()` are populated by a DmlKind-aware factory (`ArgumentRef.java:284-312`), and `MutationInputResolver` reads the partition as fact. Two problems show up under the pivot.

First, the partition is a property of the *consumer*, not of the *arg*: the same input used by `Mutation.updateFilm` (UPDATE) and `Mutation.deleteFilm` (DELETE) belongs in different partitions at each site. Today's model handles this because the partition lives on `TableInputArg` (per-arg-occurrence), which masks the underlying mistake. The pivot's dimensional principle ("one slot per dimension; the consumer's concern is on the consumer") makes the shape visible: WHERE-vs-SET is the mutation emitter's concern, not the classifier's.

Second, the partition is *denormalized*: `InputField.LookupKeyField` and `InputField.SetField` are intersection-marker interfaces on the same concrete `ColumnField`/`CompositeColumnField`/etc., which means the typed lists store the same fields, partitioned. R144's factory does the partitioning; consumers read it. Reverting the commitment is cheap (the factory logic moves into `MutationInputResolver`, the only consumer of `lookupKeyFields()` / `setFields()`), and it eliminates the intersection-marker leak at the model-shape level.

The intersection-marker pattern on `InputField` (ColumnField IS-A both LookupKeyField and SetField) stays in place under R222 since making those disjoint is a separate model refactor; this item only moves *where* the partition decision is made, not *how* the underlying types express eligibility.

### Why R144's `@value` directive retires

Once the partition lives on the consumer (above), the marker that fed it surfaces as the same disease one layer down. `@value` on an input field is rejected on DELETE / INSERT / UPSERT, admitted on UPDATE, and mutually exclusive with `@condition` on the same field. The directive's validity is a function of the consumer's verb, which is exactly the "if two consumers evaluate the same predicate over a model field, the branch belongs in the model" smell the principles list names. A per-input-field directive encoding per-consumer-verb semantics is dimensionally inverted.

The dimensionally honest mechanism, once the catalog is in scope: the WHERE-vs-SET partition on UPDATE is `(arg.classifiedFields(), targetTable.primaryKey())` derivable; PK-column carriers go to WHERE, the rest go to SET. The jOOQ catalog already carries the PK column set; no per-input-field marker is needed to recover the partition. R144's cardinality-safety surface (`multiRow: true` opt-in, the PK-coverage check on UPDATE / DELETE) is orthogonal to the directive and survives unchanged in shape: the trigger is the catalog's PK column set, not the `@value` complement.

Sakila has two `@value` annotations (`FilmUpdateInput.title`, `FilmUpdateInput.description`); both are non-PK columns, so the partition outcome is identical with or without the directive. The fixture sweep that drops them is a one-line edit per site. `docs/manual/reference/directives/value.adoc` retires. Two `@LoadBearingClassifierCheck` keys swap on the same `MutationInputResolver` site: `mutation-input.update-set-fields-equal-value-marked` retires; `mutation-input.update-partition-by-pk-membership` replaces it (PK-column carriers in `classifiedFields` go to WHERE, the rest to SET, on UPDATE; the existing PK-coverage check key stays).

### Why InputUsage is the classified-state carrier

Two earlier drafts wrestled with where the classified field list lives. Draft A stored `List<InputField> classifiedFields` directly on `InputTypeArg`; Draft B demoted it to a derived view computed by `Input.classifyAgainst(table)`. Draft A denormalises (the field list is a function of inputs, not an independent fact); Draft B hides the recursion (nested-input classification re-enters `Input.classifyAgainst` from inside the legacy `InputField.NestingField` carrier, which then has no slot to hold its own use-site context).

`InputUsage` resolves both. It is the carrier of `(input, table, classifiedFields)` for one SQL-form use site. The classified fields are stored on it because they are produced once at construction (by the visitor in Phase 3 onward) and read many times by consumers; recomputing them on every read is the alternative the legacy eager-classification model already chose, and the recursion through nested inputs makes the recompute non-trivial. Storing them on the recursive node makes the classified state a structural property of the model rather than an accessor.

Each `InputUsage` is produced exactly once by the SQL-side classifier: the root by the visitor at an SQL-form arg-visit site, the nested by the classifier when it walks an `InputField.NestingField`-shaped declaration. The producer-side commitment that `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (key `per-type-emit-ignores-binding-context`) keeps the per-type Jakarta-validation emit independent of any use site.

Per-arg consumers (`MutationInputResolver`'s R144-relocated partition logic, `GraphitronSchemaValidator.validateInputArg`, `EnumMappingResolver.buildLookupBindings`) read `arg.usage().classifiedFields()` directly; consumers that descend into nested inputs read `nestedUsage.classifiedFields()`. The single classified-state shape works for both depths. The cost is one classified-field list per `InputUsage` (one per root SQL-form use site plus one per nested position transitively); each is a lookup per SDL field against the table's column set, typically low tens. If profiling shows a hotspot, identical `InputUsage`s (same `input`, same `table`) can be deduplicated by a side cache keyed on that pair; the spec does not bake the cache in.

### Recursion through nested inputs

The SQL-side classifier constructs an `InputUsage` for each nested input field structurally: `InputField.NestingField.nested` is a child `InputUsage`, not a `List<InputField>` that re-inherits parent context implicitly. The use-site context (the table) is explicit at every tree node, and the recursion is visible in the type system rather than hidden in a method body.

**GraphQL allows input cycles; graphitron does not.** Per the GraphQL spec (October 2021 §3.6.1), an input object's references can recurse, including indirectly, *provided at least one field in the cycle is nullable* (otherwise no finite input value is constructible). `graphql-java` enforces the nullable-link rule at schema-build time, so `TreeFilter { children: [TreeFilter] }` parses but `Cycle { c: Cycle! }` does not. Graphitron is stricter: it rejects *all* cycles in nested-input recursion, because the SQL-side classifier enumerates nested fields at classification time to produce fixed SQL projections, and arbitrary-depth nesting has no fixed lowering. This stance is graphitron-specific and pre-dates R222; the pivot preserves it.

**Recursion mechanic.** The classifier carries a `ClassifyContext` (`ClassifyContext.java`, pre-existing) holding a `Set<String> expandingTypes`. The entry point is `Classifier.classify(input, table)` which returns an `InputUsage`; internally it walks `input.fields()` once, classifying each `InputFieldDecl` and pushing the input's type name onto the visited set before recursing into nested-input fields. When the recursive call sees a type name already in the set, the nested slot resolves to `UnboundField` with the "circular input type reference detected while expanding 'X'" reason (the same `Unresolved` reason today's `BuildContext.classifyInputFieldInternal` at `BuildContext.java:1640-1664` emits, surfaced through R215's deferred-rejection rail). The visited-set mechanic transfers from the legacy classifier unchanged; the change is where the result lands (on an `InputUsage` tree rather than a flat `List<InputField>` that hides depth).

**Per-field classification rules.** For each `InputFieldDecl` in `input.fields()`:

- **Scalar / enum type, resolves to a column on the use-site table:** produces `ColumnField` / `CompositeColumnField` or their `Reference` variants (the existing arms).
- **Scalar / enum type, no column resolves:** produces `UnboundField` per R215's admit set.
- **Nested input type, same table (SDL grouping):** recursive `Classifier.classify` call with the parent's table forwarded. The result wraps in `InputField.NestingField(parentTypeName, name, location, nonNull, list, nestedUsage, condition)`. When `ctx.isExpanding(nestedTypeName)` returns true, the recursive call short-circuits and the slot resolves to `UnboundField` with the circular-reference reason.
- **Nested input type that crosses a table boundary (`@reference(path:)` terminal at a different table):** R122 (`compound-entity-mutations`) territory. This is *not* an extension of `InputField.NestingField`: that arm is specifically the SDL-grouping case where the nested fields stay on the parent's table (see `InputField.java:186-195`). The moment the nested input crosses a table boundary, the model shape changes. R122 adds a new `InputField` arm — working name `TableTargetField`, mirroring `ChildField.TableTargetField` on the output side (`ChildField.java:317`) — carrying the nested `InputUsage` (with the child's own `table`) plus the FK descriptor the mutation emitter uses to thread the parent's captured PK into the child rows. R222 does *not* add this arm; today's classifier produces `Unresolved("no column 'X' reachable")` at the parent's level for the nested-table case, which lifts to `UnboundField` under R215, and the pivot inherits that outcome verbatim. R222's contributions for R122 are the recursive `InputUsage` (the new arm has a slot to put its child state), the `@reference(path:)` directive seam (the classifier trigger), and the existing `UnboundField` baseline (the before-after test anchor). R122 lands as: (a) the new `TableTargetField` `InputField` arm; (b) the visitor arm that recognises the trigger and produces it; (c) the mutation-emitter arm that consumes it.

**Cycle detection ordering vs the deduplication escape hatch.** The Risk section names structural-key deduplication (cache keyed on `(Input, TableRef)`) as the profile-driven optimisation. If a cache is introduced, the lookup must happen *before* the visited-set check: a cached `InputUsage` for an Input not currently expanding is safe to return, but probing the cache after the visited-set check would silently miss any recursive call that legitimately resolves (a nested input is allowed to reference its parent indirectly through a separately-classified Input, just not into the *currently expanding* type set). The ordering is: cache → visited-set check → classify → cache write. The spec calls this out so a future implementer adding the cache does not invert it.

**Test surface.** Today's coverage in `GraphitronSchemaBuilderTest.java` exercises both the direct self-referential case (line 1358, "self-referential nested type → UnclassifiedField with circular-reference message") and the indirect chain (line 5812, "circular A → B → A chain rejects loudly as UnclassifiedField; build terminates"). Both assert on the classifier's "circular input type reference detected" / "circular type reference" reason strings. Under the pivot, the producer moves from `BuildContext.classifyInputField` to the new `Classifier.classify`; the reason string and the surfacing path through `UnboundField` (R215) stay verbatim. Both tests carry over with no behavioural change.

## Cross-axis invariants

The dimensional split admits states the old model could not. Two load-bearing invariants pin those states explicitly, each carried as a `@LoadBearingClassifierCheck` key. Producer: the SQL-side classifier (the visitor under Phase 3 onward; the adapter under Phases 1-2). Consumers: the per-arg SQL-form emitters plus the per-type generators as noted.

1. **`input-usage.classified-fields-against-effective-table`**: `InputUsage.classifiedFields()` is the result of the per-field classifier running over `Input.fields()` against `InputUsage.table()`. Pinned by construction: `InputUsage` has no `classifiedFields` setter other than the constructor argument, and the classifier is the sole producer. The `@LoadBearingClassifierCheck` annotation lands on `Classifier.classify`; consumers (`MutationInputResolver`, `GraphitronSchemaValidator.validateInputArg`, `EnumMappingResolver.buildLookupBindings`, the four `CatalogBuilder` sites) read via `arg.usage().classifiedFields()` and the audit walker pins the chain through that single producer. The R215 admit set governs which arms appear in the list (column-bound carriers plus `UnboundField` for the override-cascade case); the list may be empty when an Input has no SDL fields (parser-level rare but reachable).

2. **`per-type-emit-ignores-binding-context`**: `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (never `InputUsage`); per-arg SQL-form consumers read only `InputUsage` (never bypass to `Input` to redo classification work). The reachability degenerate-state (an `Input` with no `InputUsage` occurrences emits per-type-for-Jakarta-validation but has no classified consumption) is consistent by construction once this key holds. Pinned with `@DependsOnClassifierCheck` on the two per-type generator entry points.

**Retirements:**

- The legacy `input.record-shape-derived-from-backing` key retires entirely with the `Input.declaredBackingClass()` slot. `ValidationShape` is purely SDL-derived now (the emit produces a Jakarta-validation type from the SDL declaration); `Input` carries no backing-class slot and no relationship between the two exists to pin.
- The earlier draft's `input-usage.backing-table-aligns-with-effective-table` invariant retires with the `InputUsage.backingClass` slot it pinned. The state space it guarded — a service-method param typed as a `TableRecord` for one table at a use site whose consumer-derived table is a different one — moves to R164's domain-form arm: it is a property of the per-param `BackingClass` and the SDL declaration's field signature, not an SQL-side carrier-shape concern. R164's domain-form spec owns the producer-side check (the resolver-param-class-shape-matches-SDL check, plus the table-agreement check on `BackingClass.JooqTableRecord` params at consumer-derived-table fields).
- The earlier draft's `input-usage.empty-table-implies-empty-classified-fields` invariant retires with `InputUsage.table`'s Optional sprawl. `InputUsage.table` is non-Optional by construction; the empty-table state space is structurally unreachable.

Invariant #1 holds by construction; the only producer is `Classifier.classify`. Invariant #2 is a producer-side commitment; the adapter shims in Phases 1-2 must satisfy it as part of their "lossless round-trip" criterion, otherwise consumers that construct the new types directly bypass the boundary. The audit walker (`LoadBearingGuaranteeAuditTest`) is the safety net.

## Phasing

Six phases, each independently shippable and individually reversible.

### Phase 1: introduce `Input`, `BackingClass`, `InputUsage` alongside existing permits

- Add `sealed interface Input`, the `BackingClass` family, `InputFieldDecl`, and the `InputUsage` record to `model/`. `BackingClass` is introduced as the vocabulary R164's domain-form arm will attach per service-method param; R222 itself does not put it on any model record. The R164 attachment site lands in R164, not here. **Audit shape for R222 alone**: `BackingClass` compiles, is exported from the model package, and has no producer or consumer until R164 ships. `LoadBearingGuaranteeAuditTest` recognises the no-producer state as "awaiting R164" via a `@LoadBearingPlaceholder("R164 domain-form arm")` annotation on the sealed family, so the audit does not flag the dead carrier between R222 and R164.
- **Rename R94's `InputRecordShape` → `ValidationShape`** (and the slot `recordShape` → `validationShape` on every input variant). The old name carried framing from when graphitron's emitted record was a deserialization target; with the emit narrowed to Jakarta validation, "record shape" reads like a generic DTO descriptor and invites future drift back into "the input's DTO type" semantics. The capability marker renames from `HasInputRecordShape` → `HasValidationShape` in lockstep (it retires entirely in Phase 5 when the slot moves to `Input` directly, but the rename happens here so the lockstep is exact). Mechanical change touching the type declaration, the marker, and every legacy `*InputType` permit; no behavior change.
- Flip `InputField.NestingField` from `List<InputField> fields` to `InputUsage nested`. This is a body-only model change: the `NestingField` record stays in tree under the same name, with its slot type changed. Callers in `GraphitronSchemaValidator.validateInputNestingField`, `EnumMappingResolver` (line 333, ignores), `MutationInputResolver.java:529`, and `ContextArgumentClassifier.java:139` migrate to `nf.nested().classifiedFields()` for the field walk; `NestingField`'s same-table semantics (the parent's table forwards to the nested `InputUsage`) carry over.
- Implement an adapter `Classifier.adaptLegacy(GraphitronType.InputType, TableRef)` that constructs an `Input` plus an `InputUsage` for an SQL-form use site. The `Input` adapter is lossless on the SDL-side facts (name, location, validationShape, fields). The `InputUsage` adapter is lossless against the legacy classified-field shape (the body lifts `BuildContext.classifyInputFieldInternal` onto the new classifier entry point). Legacy use sites that today do not produce a table-bound classified list (`PlainInputArg`-shaped occurrences) do not enter the adapter; they remain on the legacy path until Phase 3 routes them via R164's discriminator (SQL-form → `InputUsage`, domain-form → legacy `PlainInputArg` until R164 lands).
- `Input.fields()` is populated from `inputType.getFieldDefinitions()` at `Input` construction: one `InputFieldDecl` per SDL field.
- Add a `Map<String, Input>` slot on `GraphitronSchema` populated from the existing types map.
- No consumer migrates yet; the adapter is the bridge.

Acceptance: every classified input today produces an equivalent `Input` via the adapter (lossless on the SDL-side facts). Every SQL-form use site that legacy classification today produces a classified field list for produces an equivalent `InputUsage` via the adapter. No other behaviour change.

### Phase 2: introduce `InputTypeArg` carrying `InputUsage`

- Add the unified `InputTypeArg` record carrying a root `InputUsage` plus the convenience accessors (`input()`, `table()`, `classifiedFields()`) that delegate to it. `InputTypeArg` is SQL-form-only; domain-form arg occurrences continue to use the legacy `PlainInputArg` carrier in Phases 1-3 (R164 lands the domain-form replacement).
- Adapter from existing SQL-form `TableInputArg` → new `InputTypeArg`. The adapter constructs the root `InputUsage` from the legacy classification context: the consumer-derived `TableRef` (from `findReturnTablesForInput`'s back-scan) populates `usage.table`; legacy `TableInputArg.inputFields` translates 1:1 onto `usage.classifiedFields`. For `TableInputArg` produced from the `@table` directive on the input itself, the adapter emits a `BuildWarning` (the Phase 3 visitor inherits this contract) and uses the consumer-derived table when the consumer is also `@table`-bound; the directive-only-at-non-`@table`-consumer case is the one acceptance gap the Phase 6 fixture sweep covers.
- New record sits alongside the existing arg types; no consumer migrates yet.

Acceptance: every SQL-form `TableInputArg` today produces an equivalent `InputTypeArg` (and a structurally-equivalent root `InputUsage`) via the adapter, modulo the `@table`-on-input directive translation called out above; `arg.usage().classifiedFields()` produces a list equal-by-value to the legacy `TableInputArg.inputFields()` for the same `(input, table)`.

### Phase 3: visitor-driven per-arg classification

- Add `GraphQLSchemaVisitor`-based walker seeded from root operations.
- At each `argument` visit on an `INPUT_OBJECT`-typed arg, the visitor consults the field-level form (R164's discriminator; until R164 lands, the existing field-level signals — does the enclosing output field have a `@service` / `@externalField` / `@tableMethod` directive — stand in as the discriminator). On **SQL-form** fields, the visitor produces the `ArgumentOccurrence` with an `InputTypeArg` carrying a root `InputUsage`. The root's `table` is populated from the enclosing field's `@table` return; if no `@table` return resolves at a notionally-SQL-form arg, that surfaces as `Rejection.AuthorError` through the standard classifier-output channel and no `InputUsage` is constructed for the arg. The visitor recurses into each nested-input field declaration in `Input.fields()`: by default the parent's table is forwarded into the child `InputUsage` (the same-table SDL-grouping case → `InputField.NestingField`). R122's `@reference(path:)`-on-nested-input case (terminal at a different table) is the seam R222 names but does not implement; R122 ships the `TableTargetField` arm + visitor arm + mutation emitter. On **domain-form** fields, the visitor does not construct an `InputUsage`; the args flow to R164's domain-form arm (or, until R164 lands, the legacy `PlainInputArg` carrier).
- **`@table` and `@record(class:)` on input are dropped at this phase.** The visitor reads each directive once at the input's classification site, emits a `BuildWarning` via `ctx.addWarning(...)` at the directive's `SourceLocation` ("@table on input types is deprecated; binding is derived from the consumer's @table return" / "@record(class:) on input types is deprecated; the materialization target for domain-form fields is the user's declared service-method parameter type"), and otherwise ignores both. Neither directive contributes to `InputUsage.table()` or to any model slot. The companion fixture sweeps that drop both directives across SDL fixtures land at Phase 6.
- **`@condition` is interior to SQL emission.** The visitor's form-discriminator rejects `@condition` on a domain-form field's arg with `Rejection.AuthorError`. On SQL-form fields, `@condition` flows through the `InputField` / `WhereCondition` carriers as today; no model-shape change.
- Retire `TypeBuilder.findReturnTablesForInput`. Per-input-type table classification disappears; per-`InputUsage` construction replaces it.
- **Annotation continuity:** migrate R215's `input-field.unbound-implies-no-column` `@LoadBearingClassifierCheck` key from its legacy producer site at `BuildContext.classifyInputFieldInternal` (`BuildContext.java:1558`) to the new `Classifier.classify` body (the new home for the column-binding decision). The second R215 key, `input-field.unbound-with-override-condition-admits-on-mutation-update-delete`, lives on the mutation-time admission check at `MutationInputResolver.java:314` and stays put: it pins the verb-admission rule, not the column-binding decision, and its producer site is not affected by the Phase 3 classifier move. The legacy `input.record-shape-derived-from-backing` key retires entirely (its premise — that the Input declaration carries a backing class — disappears with `@record(class:)`-on-input). Run `LoadBearingGuaranteeAuditTest` before and after to confirm no orphaned consumers and no stale-producer references. The three keys introduced under Cross-axis invariants and at the discriminator-stand-in seam land on their named producer sites: `input-usage.classified-fields-against-effective-table` on `Classifier.classify`; `per-type-emit-ignores-binding-context` on the two per-type generator entry points; `r222-stand-in-form-from-directive-set` on the visitor's field-level-form decision (the gate that picks SQL-form vs domain-form from today's directive signals). The stand-in key retires when R164's discriminator producer ships, with R164's substitution swapping the producer site under the same key — the audit catches a silent producer rename via the key-equality check.
- Wire R97 Phase 2's consumer-derived inference here. Test: cross-consumer divergence fixture (one input used by two consumers with different tables) classifies successfully per-arg.
- **Anchor one consumer in the same phase** so the visitor's load-bearing producer-consumer chain is exercised before the phase boundary. The chosen anchor is `EnumMappingResolver.buildLookupBindings`: smallest signature lift in the Phase 4 list, no behavioural cousins on the same surface (unlike `MutationInputResolver`'s R215-adjacent admission rules). Without an in-phase consumer, the audit detects neither producer drift nor missing producers on the new keys; with one, the chain is live from the day Phase 3 ships.

  **Bridge shape.** The legacy method is not rewritten in Phase 3; it gains a sibling overload. The two legacy callers (`MutationInputResolver.java:433`, `FieldBuilder.java:974`) keep their existing `(GraphitronType.TableInputType, ...)` call site and stay on the legacy walk; the new visitor calls the new `(InputTypeArg, ...)` overload at the visit site, which reads `arg.usage().table()` and `arg.usage().classifiedFields()`. Both overloads delegate into a shared private body that reads `(table, classifiedFields)` so the binding logic lives in one place. Phase 4 deletes the legacy overload when its callers migrate. The pattern generalises if a future phase needs to anchor a different consumer earlier than its caller chain migrates: add an overload, point the new producer at it, keep the legacy overload until its callers migrate.

Acceptance: every Sakila and `graphitron-fixtures-codegen` fixture compiles unchanged (each `@table`-decorated SDL input surfaces one `BuildWarning` but classifies identically via consumer-derivation; each `@record(class:)`-decorated input surfaces one `BuildWarning` and otherwise classifies identically). The cross-consumer divergence fixture works. `EnumMappingResolver.buildLookupBindings` reads the visitor's output, and the three new keys (`input-usage.classified-fields-against-effective-table`, `per-type-emit-ignores-binding-context`, `r222-stand-in-form-from-directive-set`) are pinned by `LoadBearingGuaranteeAuditTest`.

### Phase 4: migrate the remaining consumers

Move each remaining consumer off the legacy permit discrimination onto the new slots. Order chosen to keep blast radius small per PR; the R215 follow-ups deferred during R215's review fold in at the listed slots:

- `MutationInputResolver`: reads `arg.usage().table()` directly (non-Optional) and `arg.usage().classifiedFields()` for the per-arg list. **R144 partition relocated + `@value` retired:** the DmlKind-aware WHERE-vs-SET partition logic moves from `ArgumentRef.java:284-312` (the legacy `TableInputArg` factory) into this resolver, operating on the result of `arg.usage().classifiedFields()` plus the target table's PK column set (sourced from `JooqCatalog`). The two readers of `lookupKeyFields()` / `setFields()` (`MutationInputResolver.java:543` and the cousin sites the same file already owns) consume the partition at the site that decides the verb. The `@value`-marked-name-set parameter on `TableInputArg.of` / `EnumMappingResolver.buildLookupBindings` disappears; the verb-validity arms in `MutationInputResolver` that reject `@value`-on-DELETE / `@value`-on-INSERT / `@value`+`@condition` retire with the directive. Load-bearing key swap on the same producer site: `mutation-input.update-set-fields-equal-value-marked` retires, `mutation-input.update-partition-by-pk-membership` replaces it. R144's `mutation-input.where-columns-cover-pk` key stays (the cardinality-safety check is orthogonal to the directive). **R215 follow-up:** the deferred `MutationField.{Value, Condition}` projection lands here if it has not already shipped standalone.
- `FieldBuilder.classifyInputFieldOnArg`: drops the `instanceof TableInputType` arm.
- `GraphitronSchemaValidator.validateTableInputType`: becomes `validateInputArg`; receives an `InputTypeArg` (SQL-form, table guaranteed by construction) and walks `arg.usage().classifiedFields()`. Nested-input recursion goes through `InputField.NestingField.nested()` (and, post-R122, through `TableTargetField.nested()`) so nested-input validation is uniform with root validation. **R221 absorbed:** the validator now walks `classifiedFields` uniformly across every SQL-form `InputTypeArg`; the `PlainInputArg.fields()` vs `TableInputType.inputFields()` split that motivated R221 has retired in Phase 2-3.
- `CatalogBuilder` four sites: each reads `arg.usage()` for use-site facts; `arg.input()` only when an SDL-side fact is needed (name, source location, validation shape).
- `InputRecordGenerator`, `InputTypeGenerator`: read `Input.validationShape()` and `Input.schemaType()` directly; permit-identity dispatch retires. These two consumers do *not* touch `InputUsage`; the `per-type-emit-ignores-binding-context` invariant pins that boundary.

Compiler exhaustiveness on the sealed `Input` interface is the safety net for each migration.

Acceptance: no consumer references `GraphitronType.InputType`, `TableInputType`, or any of the four `*InputType` permits directly.

### Phase 5: delete the legacy model

- Remove `InputType`, `TableInputType`, `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType` from `GraphitronType.permits`.
- Delete the `HasValidationShape` capability marker (the slot lives on `Input` directly).
- Delete `ArgumentRef.InputTypeArg.TableInputArg`; the legacy `PlainInputArg` stays on tree until R164's domain-form arm replaces it (the legacy carrier is the domain-form stand-in during R222's runtime, see Phase 3). R222 leaves a `TODO(R164)` marker at the `PlainInputArg` definition so the deletion site is clear once R164 lands.
- Delete the Phase 1-2 adapter shims.

Acceptance: build green; one PR worth of deletions; nothing references the old SQL-form permits.

### Phase 6 (picks up R97's residue + retires `@value` + retires `@record(class:)` on inputs)

Three per-input directives whose semantics belong elsewhere come out in one sweep: `@table` (handled by Phase 3's runtime ignore, finished here on the schema-declaration side), `@record(class:)` on input types (Phase 3 runtime ignore, finished here), and `@value` (mechanism replaced by PK-derivation in Phase 4, finished here on the directive-declaration side).

- Migrate every `@table`-decorated SDL input across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive. Each removal silences one `BuildWarning`; nothing else changes.
- Narrow the SDL `@table` directive's scope from `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`. The directive declaration becomes structurally unable to land on `INPUT_OBJECT`; any consumer schema that still carries `@table` on an input fails to parse with the standard graphql-java location error.
- Closes R97.
- Migrate every `@record(class:)`-decorated SDL input type across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive. For each removed annotation, behaviour is preserved: SQL-form occurrences never read the directive (the SQL emitter walks `classifiedFields` directly, no DTO target), and domain-form occurrences derive their materialization target (`BackingClass`) from the user's declared service-method param type — the directive either matched that type (redundant) or contradicted it (the contradiction surfaces as `Rejection.AuthorError` on R164's domain-form arm). The codegen-opt-out role is dropped; the per-`Input` Jakarta-validation emit was the only graphitron-side artifact and survives unchanged in shape.
- Narrow the SDL `@record` directive's scope to exclude `INPUT_OBJECT` (it remains valid on `OBJECT`-typed declarations where it has different semantics). Any consumer schema that still carries `@record` on an input fails to parse with the standard graphql-java location error.
- Migrate every `@value`-decorated SDL input field across Sakila (`FilmUpdateInput.title`, `FilmUpdateInput.description`) and any fixture schemas to drop the directive. Both Sakila annotations sit on non-PK columns; the partition outcome is identical with or without them.
- Delete the `@value` directive declaration from `directives.graphqls`; delete `BuildContext.DIR_VALUE` / `ARG_VALUE` and the `assertDirective(ctx, DIR_VALUE)` registration in `GraphitronSchemaBuilder`. Delete `docs/manual/reference/directives/value.adoc`. After this delete, any consumer schema still carrying `@value` fails to parse with the standard graphql-java "unknown directive" error.

Can land independently after Phase 5; not a blocker for the structural pivot. The "optional" framing from earlier drafts is dropped: with the runtime ignore live from Phase 3 (for `@table` and `@record(class:)`) and the partition mechanism already PK-derived in Phase 4 (for `@value`), leaving the warnings and the now-redundant directive declarations indefinitely is its own cost.

## Dependencies and sequencing

- **R215** (`column-binding-at-classification-not-usage`, Done at `131e0df`): the `UnboundField` deferral R215 introduced is the same column-coverage relaxation the pivot generalises to "no per-input-type column classification at all." Of the two `@LoadBearingClassifierCheck` keys R215 shipped, `input-field.unbound-implies-no-column` names `BuildContext.classifyInputFieldInternal` as its producer (the column-binding decision) and moves into the visitor walk under this pivot's Phase 3; `input-field.unbound-with-override-condition-admits-on-mutation-update-delete` is produced at `MutationInputResolver` (the verb-admission decision) and is not affected by the producer-site move. `LoadBearingGuaranteeAuditTest` does not detect producer drift (only missing producers), so Phase 3 carries an explicit commitment to migrate both annotation producer-sites to the new visitor entry point and to re-run the audit per phase. With R215 in tree the precondition work is done; the annotation continuity is the only remaining build-order concern.
- **R94** (shipped): the `HasValidationShape` slot R94 lifted onto every input variant becomes a direct field on `Input`, sourced from the SDL declaration. No new prerequisite; the R94 invariant (validationShape derived from backing class) retires because `Input` no longer carries a backing class — the per-`Input` emit is purely SDL-derived now and exists for Jakarta-validation use only.
- **R166 Phase 1** (reachability slot): orthogonal. The visitor scaffolding this item lands can be reused by R166 Phase 1, or R166 Phase 1 can land standalone first and this item piggybacks. Spec-stage call.
- **R164** (field-model dimensional pivot): the *contract partner*, not a downstream consumer. R164 ships the field-level `FieldInputForm` discriminator (SQL-form vs domain-form) that R222's Phase 3 visitor consults; R222 ships the building blocks (`Input`, `InputUsage`, `InputField` arms, `BackingClass` vocabulary) that R164's two arms reuse. The split is along a real seam: input-side building blocks are SDL-shape-driven; the field-level form is consumer-driven. Sequencing: R222 can ship ahead of R164 using the existing field-level signals as the discriminator stand-in (see Phase 3); the swap to R164's eventual discriminator is mechanical. R164's authors model on R222's dimensional and phasing halves; what does **not** transfer is the two-level split (`Input` consumer-independent / `InputTypeArg` per-occurrence), which is specific to input usage.

Likely scope: 1-2 weeks of focused work, somewhat smaller than R164's 2-4 weeks because the consumer surface is narrower and the existing model is already mid-erosion (R215 did the precondition work).

## Vocabulary

- **`Input`** (the SDL declaration), **`InputUsage`** (the SQL-side recursive classified-state carrier for an SQL-form use site), and **`InputTypeArg`** (the SQL-form arg-site wrapper). The naming explicitly excludes domain-form occurrences from `InputUsage` / `InputTypeArg`: those flow through R164's domain-form arm and do not appear on the R222 carriers.
- **`InputUsage`** is the SQL-side load-bearing carrier: `(Input input, TableRef table, List<InputField> classifiedFields)`. Produced by the SQL-side classifier (visitor under Phase 3 onward) at every SQL-form use site; consumed by every SQL-form per-arg consumer. Recursive through `InputField.NestingField.nested` (same-table SDL grouping) and, post-R122, through `InputField.TableTargetField.nested` (cross-table FK-linked nesting). Equality is structural; identity is not load-bearing. No Optional slots; an SQL-emitting use site has a table by construction.
- **`BackingClass`** is a three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) naming the *user's declared materialization target* for a domain-form service-method param. One `BackingClass` per param, attached on R164's domain-form arm; not a slot on any R222-introduced model record. Reading the param's backing class answers "which Java class materialises the args at this domain-form occurrence?" — what consumers of that signal always wanted. The legacy "graphitron picks a backing class" framing is gone: graphitron does not pick; the user declares (via the method signature), and graphitron accommodates.
- **`InputFieldDecl`** is the SDL field declaration on `Input`, pre-binding. Wraps `GraphQLInputObjectField` so applied directives are readable at consumption time. Distinct from `InputField` (the post-classification SQL-side carrier on `InputUsage.classifiedFields`).
- **`ConsumerBinding`** does not exist as a model type. The table fact lives on `InputUsage.table()`; the consumer coordinate that drove the inference is carried on the enclosing `ArgumentOccurrence` (for the root) and recoverable from the parent walk for nested nodes.
- **`InputTypeArg`** is the SQL-form arg-site wrapper carrying a name, the nullability/list flags, the optional arg condition, and the root `InputUsage`. Convenience accessors (`input()`, `table()`, `classifiedFields()`) delegate to the usage carrier.
- **`ArgumentOccurrence`** is the visit-site carrier (coordinate + `InputTypeArg`). May fold into R164's field-level form (the field already carries its coordinate). Spec-stage call.
- "Table-bound input" prose retires: SQL-form `InputUsage` always carries a table; domain-form occurrences do not have an `InputUsage` to begin with. The predicate "is this arg SQL-form" is the R164 discriminator, not a slot on the input model.

## Out of scope

- **Field-level form (SQL-emission vs domain-handoff)**: R164. R222 names the seam and reserves the input-side building blocks both arms reuse, but the `FieldInputForm` sealed family (working names `SqlForm` / `DomainForm`), the domain-form arg carrier, and the method-signature mapping subsystem land in R164. R222's Phase 3 visitor consumes R164's discriminator; until R164 lands, the existing field-level signals stand in.
- **Domain-form `BackingClass` attachment.** R222 ships the `BackingClass` sealed family as vocabulary, but the per-method-param attachment site (one `BackingClass` per service-method param) lives on R164's domain-form arm. R222 does not put `BackingClass` on any model record.
- **Producer-side unification of method invocation paths.** The principle (uniform reflection-mapping rules across `@service` / `@externalField` / `@tableMethod` / `@condition`) is named here for the model to lean on, but unifying the actual producer (today's invocation paths use ad-hoc binding) is its own task. R164 and adjacent items carry it.
- **`@tableMethod` exact categorisation.** Whether some args drive WHERE generation alongside the method call (hybrid) or all args go to the method (uniform with `@service`) is a question for R164's domain-form spec. R222's building blocks support either resolution.
- **Field-side dimensional pivot**: R164's broader scope (the 46-permit field hierarchy). R222 validates the pattern on the smaller input surface.
- **`ServiceCatalog` predicate consolidation**: R220 / R193. Same disease in a different file.
- **`argMapping` grouping syntax**: R97 Phase 1. Separable; the per-arg classification slot the pivot introduces is the natural home for the grouping outcome, but the syntax and parser work is independent.
- **Visitor-driven emission for non-input types**: broader R166. The input-side slice is what this item absorbs.
- **Reachability pruning across all type kinds**: R166 Phase 1. Orthogonal; this item only prunes inputs via the visit gate.

## Risk

- **Per-use-site classification multiplies work for inputs reused across many consumers.** A filter input used by ten queries today is classified once eagerly; under the pivot, the classifier builds one `InputUsage` per SQL-form use site (and one per nested position transitively). The classified fields are stored on the `InputUsage` so consumer-side access is a slot read, not a recompute; the multiplier is on build time, not on consumption. Mitigation 1: the per-use-site work is a lookup-per-SDL-field against the table's column set, typically low tens of fields; profile before optimising. Mitigation 2: if profiling shows a hotspot, deduplicate `InputUsage` construction by a side cache keyed on the `(Input, TableRef)` pair; identical bindings share an `InputUsage` instance. The cache lives outside the model record. The spec does not bake this in; structural equality is the contract.
- **Phases 1-3 keep both models alive simultaneously; the adapter is a deferred-deletion liability.** Mitigation: phases 4-5 are scheduled with the same urgency as the rest; the adapter is gone within the same release window.
- **R164 dependency.** Phase 3's visitor consults R164's field-level form discriminator. Until R164 lands, the visitor uses the existing field-level signals (`@service` / `@externalField` / `@tableMethod` directives on the output field) as the stand-in. The risk: if R164's eventual discriminator splits the form differently than today's directive set, Phase 3's classification may need a touch-up. Mitigation: the stand-in is a one-method call site; substituting R164's discriminator is a mechanical swap. R164 and R222 share a reviewer pool to keep the contract aligned.
- **Test coverage gap: the cross-consumer divergence case has no fixture today.** Mitigation: add the fixture in Phase 3 as acceptance.

## Tests

- **Pipeline-tier (regression):** every existing `graphitron-fixtures-codegen` fixture and Sakila fixture compiles unchanged through the pivot. Output diffs against trunk must be empty (modulo new `BuildWarning` entries for `@table`-decorated and `@record(class:)`-decorated SDL inputs, which the test asserts on as part of the deprecation-warning fixtures below).
- **Pipeline-tier (new):** consumer-derived path on SQL-form. An SDL input with no `@table` directive used by a single `@table`-returning SQL-form consumer produces an `InputTypeArg` whose root `InputUsage.table()` carries the consumer's table (non-Optional, single fact). The consumer's `SchemaCoordinate` is recoverable from the enclosing `ArgumentOccurrence`. R97 Phase 2's acceptance fixture lands here.
- **Pipeline-tier (new):** `@table` on input emits `BuildWarning`. A fixture with `input X @table(name: "x") { ... }` used by a `@table`-returning consumer produces a `BuildWarning` at the directive's `SourceLocation` with the deprecation message, and the resulting root `InputUsage.table()` carries the consumer-derived table (same as if the directive were absent). The R97 Phase 3 runtime-ignore decision is pinned here.
- **Pipeline-tier (new):** `@record(class:)` on input emits `BuildWarning`. A fixture with `input X @record(class: "com.example.X") { ... }` produces a `BuildWarning` at the directive's `SourceLocation` with the deprecation message and otherwise classifies identically: SQL-form occurrences are unaffected (no model slot reads the directive), and domain-form occurrences derive their materialization target from the user's declared service-method param type.
- **Pipeline-tier (new):** notionally-SQL-form arg with no resolvable table. An SDL input declared at an SQL-form field (e.g. a `@table`-returning consumer) but where the consumer's `@table` return cannot be resolved (e.g. an unbound output type) signals `Rejection.AuthorError` through the standard classifier-output channel and no `InputUsage` is constructed for the arg. Pins the non-Optional-table commitment.
- **Pipeline-tier (new):** cross-consumer divergence. One input used by two SQL-form consumers with different return tables produces two `InputTypeArg` occurrences whose root `InputUsage.table()` slots hold the distinct tables. Today's `InputType` collapse becomes per-use-site success.
- **Pipeline-tier (new):** nested-input recursion (same table). A fixture with an input `Outer { inner: Inner }` used at a `@table`-returning consumer produces a root `InputUsage` whose `classifiedFields` contains an `InputField.NestingField` carrying a child `InputUsage` for `Inner`; the child's `table` equals the parent's (same-table SDL grouping). Pins the structural-recursion contract.
- **Pipeline-tier (new):** unreached input. An SDL input declared but never referenced from any reachable consumer produces an `Input` record with no `InputTypeArg` (and no `InputUsage`); `InputRecordGenerator` still emits, downstream classifiers see no per-arg work.
- **Unit-tier (new):** `InputUsage` construction commits. `Classifier.classify(input, table)` produces an `InputField` per `Input.fields()` entry on the resulting `InputUsage.classifiedFields()`, with miss → `UnboundField` and hit → the column-bound arm; equality against the legacy `BuildContext.classifyInputFieldInternal` output for the same `(input, table)` pair is the regression anchor that survives the producer-site move in Phase 3.
- **Pipeline-tier (new):** federation entity-fetch boundary. An SDL input referenced only by an `_Entity` resolver (not by any operation-root field) produces an `Input` record with no `InputTypeArg` and no `InputUsage`; the existing `EntityFetcherDispatch.resolveByReps` boundary handles wire decoding unchanged. Pins the federation-seed-set decision named in §Visitor-driven classification consequence (4).
- **Pipeline-tier (new):** typed rejection on column-miss carries `Rejection.AuthorError.UnknownName` with the input field's source location (R209 lands here).
- **Pipeline-tier (new):** `@condition` on a domain-form field's arg surfaces `Rejection.AuthorError`. Pins the principle that `@condition` is interior to SQL emission, not a separate consumer.
- **Pipeline-tier (new):** domain-form fields produce no `InputTypeArg`. A fixture with a `@service`-decorated output field whose arg is an SDL input type produces no `InputTypeArg` (and no `InputUsage`) for the arg; the legacy `PlainInputArg` stand-in carries the arg until R164's domain-form arm replaces it. Pins R222's commitment to the field-level seam regardless of R164's eventual carrier shape, and pins the producer-side uniform-reflection-mapping assumption at R222's boundary (Principle 3): R222's model neither inspects nor reshapes the method-signature mapping.
- **Compilation-tier:** every `graphitron-sakila-example` compile target stays green.
- **Execution-tier:** every existing execution test passes unchanged. No new execute-tier fixtures are required by the pivot itself.

## Architectural principle this codifies

R164 frames the disease: a sealed hierarchy that tries to represent multiple independent dimensions through a single permit set. The cross product is the permit set; adding a value to any axis multiplies the permits below it; the leaves carry redundant or divergent encodings of the same axis.

The cure is the same in both organs: separate the dimensions onto orthogonal slots, with a sealed sub-taxonomy *per dimension* rather than a sealed sub-taxonomy of the *cross product*. This item ships the cure on the input surface where the cross product is smallest (5 permits encoding 3 axes), then R164 ships the same cure on the field surface where it's largest (46 permits across 3 sealed hierarchies encoding 5+ axes). R220 / R193 then apply it to the consumer-side classifier predicates.

The principle is not "minimise the model"; it is "make the model honest about what it's saying." The cross-product encoding hides axes; the dimensional encoding surfaces them. Hidden axes drift, surfaced axes get the compiler's help.
