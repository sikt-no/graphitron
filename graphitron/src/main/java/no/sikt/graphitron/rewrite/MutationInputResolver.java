package no.sikt.graphitron.rewrite;

import graphql.language.EnumValue;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnOverlap;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_MULTI_ROW;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_OVERRIDE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TABLE_REF;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_LOOKUP_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.argString;

/**
 * Resolves the DML {@code @mutation} concern's shared, phase-portable facts: the
 * {@code @mutation(typeName:)} discriminator ({@link #parseDmlKind}), the return-type constraints
 * ({@link #validateReturnType}, Invariants #14 / #15), the write-target precedence
 * ({@link #resolveDmlWriteTableRef}), and the per-input-field admission rules
 * ({@link #admitMutationInputFields} / {@link #rejectPlainColumnCollision} /
 * {@link #rejectInputFieldDirectives}). An all-static utility; the classify-phase entry points
 * live on {@link FieldBuilder}, the binding grounder on {@code RecordBindingResolver}. Sibling to
 * {@link OrderByResolver}, {@link LookupMappingResolver}, {@link PaginationResolver},
 * {@link ConditionResolver}, and {@link InputFieldResolver}.
 */
final class MutationInputResolver {

    /**
     * Outcome of {@link #parseDmlKind}. Three terminal arms:
     *
     * <ul>
     *   <li>{@link Absent}: {@code @mutation} or its {@code typeName:} argument is unset; the
     *       caller falls through to its surrounding "directive missing" rejection.</li>
     *   <li>{@link Kind}: the argument resolves to a {@link DmlKind}.</li>
     *   <li>{@link Unknown}: the argument is set but matches no {@link DmlKind}; the caller
     *       surfaces an {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}
     *       carrying the raw value.</li>
     * </ul>
     */
    sealed interface DmlKindResult {
        record Absent() implements DmlKindResult {}
        record Kind(DmlKind kind) implements DmlKindResult {}
        record Unknown(String raw) implements DmlKindResult {}
    }

    private static final DmlKindResult ABSENT = new DmlKindResult.Absent();

    private MutationInputResolver() {
    }

    /**
     * Lifts the {@code @mutation(typeName:)} argument on {@code fieldDef} into a
     * {@link DmlKindResult}; see its arm docs.
     */
    static DmlKindResult parseDmlKind(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_MUTATION);
        if (dir == null) return ABSENT;
        var arg = dir.getArgument(ARG_TYPE_NAME);
        if (arg == null) return ABSENT;
        Object value = arg.getValue();
        String raw = value instanceof EnumValue ev ? ev.getName()
            : value instanceof String s ? s
            : null;
        if (raw == null) return ABSENT;
        try {
            return new DmlKindResult.Kind(DmlKind.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return new DmlKindResult.Unknown(raw);
        }
    }

    /**
     * Reads {@code @mutation(multiRow:)} from the field's directive application. Defaults to
     * {@code false} when the argument is absent or set to {@code null}.
     */
    static boolean parseMultiRow(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_MUTATION);
        if (dir == null) return false;
        var arg = dir.getArgument(ARG_MULTI_ROW);
        if (arg == null) return false;
        Object value = arg.getValue();
        return value instanceof Boolean b && b;
    }

    /**
     * Reads {@code @mutation(table:)} off the field's directive application. Returns the SQL
     * table name the field names as its write target, or empty when the argument is absent or
     * blank. Verb gating lives on {@link #TABLE_ARG_SUPPORTED_VERBS}.
     */
    static Optional<String> parseMutationTableArg(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_MUTATION);
        if (dir == null) return Optional.empty();
        var arg = dir.getArgument(ARG_TABLE_REF);
        if (arg == null) return Optional.empty();
        Object value = arg.getValue();
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    /**
     * The {@code @mutation} verbs whose {@code @mutation(table:)} argument names a field-relative
     * write target: DELETE (which cannot carry its table on the return, per Invariant #14), plus
     * the encoded-ID / scalar-return INSERT and UPDATE shapes (whose return names no table).
     * Single-sourced so every consumer agrees on the set: the classifier's unsupported-verb guard
     * (which rejects the arg on any other verb) and {@link #resolveDmlWriteTableRef}'s verb gate.
     */
    static final Set<DmlKind> TABLE_ARG_SUPPORTED_VERBS = Set.of(DmlKind.DELETE, DmlKind.INSERT, DmlKind.UPDATE);

    /**
     * The {@code @mutation} verbs whose write target derives from the return type (a direct
     * {@code @table} return, or a carrier payload's single {@code @table}-element data field):
     * INSERT and UPDATE. DELETE is deliberately absent: a DELETE cannot return the deleted row's
     * {@code @table} type (Invariant #14), so no DELETE return names a table. This rung is
     * preferred over {@code @mutation(table:)} and the input {@code @table} bridge (see
     * {@link #resolveDmlWriteTableRef}).
     */
    static final Set<DmlKind> RETURN_DERIVED_TABLE_VERBS = Set.of(DmlKind.INSERT, DmlKind.UPDATE);

    /**
     * Outcome of {@link #resolveDmlWriteTableRef}: the resolved write-target table, an
     * {@code @mutation(table:)} name the catalog does not know, or no live source at all.
     *
     * <p>The two failure arms exist so the two callers can diverge on diagnostics while sharing the
     * precedence: the binding grounder ({@code RecordBindingResolver.groundDmlMutationField}) treats
     * both {@link UnknownTable} and {@link None} as a silent skip (its contract is observation-grounding,
     * not diagnostics), while the classify-time write-target resolver
     * ({@code FieldBuilder.resolveDeleteWriteTarget}) turns {@link UnknownTable} into the loud
     * {@code unknownTableRejection} and {@link None} into the "no write target" rejection.
     */
    sealed interface WriteTableRef {
        /** A live write target resolved from either rung of the precedence. */
        record Resolved(TableRef table) implements WriteTableRef {}
        /** {@code @mutation(table:)} named a table the catalog could not resolve. */
        record UnknownTable(String namedTable) implements WriteTableRef {}
        /** No live write-target source on the field for this verb. */
        record None() implements WriteTableRef {}
    }

    /**
     * Resolves a DML {@code @mutation} field's write-target table by the shared precedence:
     *
     * <ol>
     *   <li><b>Rung 1 (preferred): the return's own {@code @table}</b>, for verbs in
     *       {@link #RETURN_DERIVED_TABLE_VERBS}; see {@link #resolveReturnDerivedTable}.</li>
     *   <li><b>Rung 2: {@code @mutation(table:)}</b>, for verbs in
     *       {@link #TABLE_ARG_SUPPORTED_VERBS}.</li>
     * </ol>
     *
     * The one producer of the precedence fact, called from both the binding walk
     * ({@code RecordBindingResolver.groundDmlMutationField}) and the classify walk
     * ({@code FieldBuilder.resolveDeleteWriteTarget} / {@code FieldBuilder.resolveInsertWriteTarget}),
     * so the grounded {@code DmlEmitted} and the classified write target cannot diverge. The
     * must-agree cross-check (a present rung 2 disagreeing with a present rung 1) lives in
     * the classify-phase resolvers, not here; this helper returns the highest present rung and
     * never rejects on disagreement.
     *
     * <p>Phase-portable: reads only SDL directives, the catalog through
     * {@link ServiceCatalog#resolveTable}, and the registry-free
     * {@link BuildContext#scanStructuralDmlPayload}, all available before the classification walk.
     * The verb gates live here rather than at the call sites so a verb gaining a rung flows to
     * every caller through the sets.
     */
    static WriteTableRef resolveDmlWriteTableRef(
            GraphQLFieldDefinition fieldDef, DmlKind kind, ServiceCatalog svc, BuildContext ctx) {
        // Rung 1: the return's own @table.
        if (RETURN_DERIVED_TABLE_VERBS.contains(kind)) {
            var returnTable = resolveReturnDerivedTable(fieldDef, svc, ctx);
            if (returnTable.isPresent()) {
                return new WriteTableRef.Resolved(returnTable.get());
            }
        }
        // Rung 2: @mutation(table:).
        if (TABLE_ARG_SUPPORTED_VERBS.contains(kind)) {
            var named = parseMutationTableArg(fieldDef);
            if (named.isPresent()) {
                return svc.resolveTable(named.get())
                    .<WriteTableRef>map(WriteTableRef.Resolved::new)
                    .orElseGet(() -> new WriteTableRef.UnknownTable(named.get()));
            }
        }
        return new WriteTableRef.None();
    }

    /**
     * The return-derived write-target rung (rung 1 of {@link #resolveDmlWriteTableRef}): the table
     * named by a DML {@code @mutation} field's return type, from SDL + catalog only. A direct
     * {@code @table} return resolves the return object's {@code @table} name against the catalog;
     * a carrier payload return resolves through {@link BuildContext#scanStructuralDmlPayload} to
     * its single {@code @table}-element data field's {@link TableRef}. Returns empty for an
     * ID / scalar return, a payload whose data field is ID- or record-element, or a return that
     * resolves to no live table; the caller then falls to rung 2.
     */
    static Optional<TableRef> resolveReturnDerivedTable(
            GraphQLFieldDefinition fieldDef, ServiceCatalog svc, BuildContext ctx) {
        if (!(GraphQLTypeUtil.unwrapAll(fieldDef.getType()) instanceof GraphQLObjectType returnObj)) {
            return Optional.empty();
        }
        if (returnObj.hasAppliedDirective(DIR_TABLE)) {
            String tableSqlName = argString(returnObj, DIR_TABLE, ARG_NAME).orElse(returnObj.getName().toLowerCase());
            return svc.resolveTable(tableSqlName);
        }
        if (ctx.scanStructuralDmlPayload(returnObj.getName()) instanceof BuildContext.DmlPayloadScan.Admit admit
                && admit.element() instanceof BuildContext.DmlElementKind.Table tbl) {
            return Optional.of(tbl.table());
        }
        return Optional.empty();
    }

    /**
     * Validates Invariant #14 (DML {@code @mutation} return type must be {@code ID},
     * {@code [ID]}, {@code T}, {@code [T]}, or a single class-backed payload, where
     * {@code T} is a {@code @table} type) and Invariant #15 (a list-shaped {@code @table}
     * input requires a list-shaped return). The cardinality check is uniform across all three
     * admitted return-type arms ({@link ReturnTypeRef.ScalarReturnType ID},
     * {@link ReturnTypeRef.TableBoundReturnType T}, and
     * {@link ReturnTypeRef.ResultReturnType Payload}): every arm's DML emit path ends in
     * {@code valuesOfRows(...).returningResult(...).fetchOne()}, whose jOOQ contract throws
     * {@code TooManyRowsException} on any input with &gt;1 row; rejecting at classify time keeps
     * that footgun from reaching authors.
     *
     * <p>Returns a non-null rejection reason on violation; {@code null} when the return type
     * is acceptable.
     */
    static String validateReturnType(ReturnTypeRef returnType, DmlKind kind, boolean listInput, BuildContext ctx) {
        String perArm = switch (returnType) {
            case ReturnTypeRef.ScalarReturnType s -> {
                if ("ID".equals(s.returnTypeName())) {
                    yield null;
                }
                // A structurally invalid carrier shape surfaces the scan's Reject diagnostic,
                // which names the offending field.
                if (ctx != null) {
                    var scan = ctx.scanStructuralDmlPayload(s.returnTypeName());
                    if (scan instanceof BuildContext.DmlPayloadScan.Reject scanReject) {
                        yield "@mutation(typeName: " + kind + ") return type '"
                            + s.returnTypeName() + "': " + scanReject.reason()
                            + "; or back the carrier with a producing @service return type or a @table type";
                    }
                    // A payload that would classify as a DML carrier but for a forbidden
                    // directive on its data field gets a message naming that field and directive;
                    // the generic "use ID or a @table type" text would misdirect at the return
                    // type rather than the one-token edit that disqualified it.
                    var forbidden = ctx.diagnoseForbiddenCarrierDirective(s.returnTypeName());
                    if (forbidden.isPresent()) {
                        var fc = forbidden.get();
                        String message = "@mutation(typeName: " + kind + ") return type '"
                            + s.returnTypeName() + "': payload data field '" + fc.dataFieldName()
                            + "' carries " + fc.directiveName() + ", which a DML payload carrier's data "
                            + "field may not have (it signals a different fetcher contract than the "
                            + "carrier's record-backed data-field path); remove it so the payload "
                            + "classifies as a carrier.";
                        if ("@splitQuery".equals(fc.directiveName())) {
                            message += " @splitQuery is redundant on an @service-backed carrier's data "
                                + "field (which already resolves through a PK-keyed follow-up SELECT, "
                                + "where it fires the warnIfSplitQueryOnRecordParent advisory), but it "
                                + "is rejected on a DML carrier.";
                        }
                        yield message;
                    }
                }
                yield "@mutation(typeName: " + kind + ") return type '"
                    + s.returnTypeName() + "' is not yet supported; use ID or a @table type";
            }
            case ReturnTypeRef.TableBoundReturnType tb -> {
                if (kind == DmlKind.DELETE) {
                    yield "@mutation(typeName: DELETE) return type '" + tb.returnTypeName()
                        + "' (@table) is not supported: the row is gone after the statement, and "
                        + "RETURNING carries only the primary key, so a full @table projection is "
                        + "impossible; return ID";
                }
                if (tb.wrapper() instanceof FieldWrapper.Connection) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + tb.returnTypeName() + "' (Connection) is not yet supported; use ID or a @table type";
                }
                yield null;
            }
            case ReturnTypeRef.ResultReturnType r -> {
                // DML accepts a class-backed carrier return; this validator screens only the
                // wrapper shape. Payload-shape rejections surface from per-child classification
                // (FieldBuilder.buildServiceField on @service mutations; the @mutation
                // classifier's element / table-equality checks on DML).
                if (r.wrapper().isList()) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + r.returnTypeName() + "' (list of record-backed payloads) is not yet supported; "
                        + "use a single record-backed payload, an ID, or a @table type";
                }
                yield null;
            }
            case ReturnTypeRef.PolymorphicReturnType p ->
                "@mutation(typeName: " + kind + ") return type '"
                    + p.returnTypeName() + "' (interface/union) is not yet supported; use ID or a @table type";
        };
        if (perArm != null) {
            return perArm;
        }
        // Cardinality coherence: payload-shaped (ResultReturnType) returns dispatch on the
        // structural single-data-field's wrapper; non-payload returns dispatch on the return's
        // own wrapper. A list-shaped data field pairs with bulk input (Invariant #16) and rejects
        // single input; a single-shaped data field rejects bulk input (Invariant #15). UPSERT is
        // refused upstream at resolveInput before this check can see it.
        if (returnType instanceof ReturnTypeRef.ResultReturnType r && ctx != null) {
            var dataField = singleDataField(r.returnTypeName(), ctx);
            if (dataField != null) {
                boolean dataFieldIsList = ctx.buildWrapper(dataField).isList();
                if (listInput && dataFieldIsList) {
                    // Admitted arm: bulk input + list-shaped @table-element data field.
                    return null;
                }
                if (!listInput && dataFieldIsList) {
                    return "@mutation(typeName: " + kind + ") with a single @table input "
                        + "cannot return a list-shaped data field on the carrier ('"
                        + r.returnTypeName() + "." + dataField.getName()
                        + "'); list-shaped output requires bulk input (Invariant #16)";
                }
                if (listInput && !dataFieldIsList) {
                    return "@mutation(typeName: " + kind + ") with a listed @table input "
                        + "must return a list (found '" + r.returnTypeName() + "', "
                        + "single-cardinality); the emit path runs valuesOfRows(...).fetchOne(), "
                        + "which throws TooManyRowsException on every call with >1 input row "
                        + "(Invariant #15)";
                }
            }
            // Carrier with zero or multiple recognized data fields: the classifier's
            // BuildContext.scanStructuralDmlPayload owns the rejection diagnostics.
            return null;
        }
        if (listInput && !returnType.wrapper().isList()) {
            return "@mutation(typeName: " + kind + ") with a listed @table input "
                + "must return a list (found '" + returnType.returnTypeName() + "', "
                + "single-cardinality); the emit path runs valuesOfRows(...).fetchOne(), "
                + "which throws TooManyRowsException on every call with >1 input row "
                + "(Invariant #15)";
        }
        return null;
    }

    /**
     * Structural lookup for a payload's single non-errors data field. Returns
     * the field definition when the payload SDL exposes exactly one non-errors-shaped field;
     * {@code null} for zero or multiple data fields, or non-Object payload types. Used by the
     * cardinality coherence check in {@link #validateReturnType} to read the data field's
     * list-ness directly from the SDL wrapper.
     */
    private static graphql.schema.GraphQLFieldDefinition singleDataField(String payloadSdlName, BuildContext ctx) {
        if (payloadSdlName == null) return null;
        var payloadType = ctx.schema.getType(payloadSdlName);
        if (!(payloadType instanceof graphql.schema.GraphQLObjectType payloadObj)) return null;
        graphql.schema.GraphQLFieldDefinition dataField = null;
        for (var f : payloadObj.getFieldDefinitions()) {
            if (ctx.detectErrorsFieldShape(f) != null) continue;
            if (dataField != null) return null;
            dataField = f;
        }
        return dataField;
    }

    /**
     * Rejects {@code @lookupKey} and non-override {@code @condition} (a filter shape that
     * competes with the verb's WHERE) on any mutation input field, recursing into nested
     * non-{@code @table} grouping inputs so a buried leaf is held to the same rule. The
     * {@code @condition(override: true)} case is left to {@link #admitMutationInputFields}.
     * Returns the first rejection, or {@code null}. Called from
     * {@code FieldBuilder.resolveInsertWriteTarget}.
     */
    static Rejection rejectInputFieldDirectives(
            graphql.schema.GraphQLInputObjectType iot, String argTypeName) {
        for (var sdlField : iot.getFieldDefinitions()) {
            if (sdlField.hasAppliedDirective(DIR_LOOKUP_KEY)) {
                return Rejection.directiveConflict(List.of(DIR_LOOKUP_KEY),
                    "@mutation input '" + argTypeName + "' field '" + sdlField.getName()
                    + "': @lookupKey on a mutation input field is no longer supported; "
                    + "remove it (the field is a filter by default)");
            }
            if (sdlField.hasAppliedDirective(DIR_CONDITION)) {
                var condDir = sdlField.getAppliedDirective(DIR_CONDITION);
                var overrideArg = condDir != null ? condDir.getArgument(ARG_OVERRIDE) : null;
                boolean override = overrideArg != null && Boolean.TRUE.equals(overrideArg.getValue());
                if (!override) {
                    return Rejection.directiveConflict(List.of(DIR_CONDITION),
                        "@mutation input '" + argTypeName + "' field '" + sdlField.getName()
                        + "': @condition on a mutation input field is not supported "
                        + "(mutations write values; only @condition(override: true) is admitted)");
                }
            }
            // Descends into every nested input. @table on one is deprecated and inert, so it does
            // not gate the scan: without this a @lookupKey / @condition buried inside a nested
            // @table grouping input would escape the admission rules its directiveless twin is
            // held to, failing open.
            var base = GraphQLTypeUtil.unwrapAll(sdlField.getType());
            if (base instanceof graphql.schema.GraphQLInputObjectType nested) {
                var nestedRejection = rejectInputFieldDirectives(nested, argTypeName);
                if (nestedRejection != null) {
                    return nestedRejection;
                }
            }
        }
        return null;
    }

    /**
     * Per-field admission for the INSERT path (UPDATE / DELETE are intercepted upstream by their
     * walkers; UPSERT is refused at the {@code @mutation} classifier dispatch). Recurses into
     * {@link InputField.NestingField} grouping inputs: a nested leaf is admitted under the
     * same rules as a root leaf, so a buried composite {@link InputField.ColumnBackedField} still
     * trips the INSERT carve-out. A list-typed nesting or a nested group carrying
     * {@code @condition} rejects. Returns the first inadmissible field's rejection, or {@code null}.
     * Called from {@code FieldBuilder.resolveInsertWriteTarget}.
     */
    static Rejection admitMutationInputFields(List<InputField> fields, String typeName, DmlKind kind) {
        for (var f : fields) {
            // ColumnBackedField admits with Direct extraction (canonical mutation-input shape)
            // and with NodeIdDecodeKeys (NodeId-decoded column write). The INSERT carve-out is
            // arity-gated on the carrier's isComposite() (the composite-PK INSERT shape is
            // architecturally rare) and fires for nested leaves too, since the recursion reaches
            // them. Composite carriers admit on every other non-UPSERT verb.
            if (f instanceof InputField.ColumnBackedField cbf) {
                if (cbf.isComposite() && kind == DmlKind.INSERT) {
                    return Rejection.deferred(
                        "@mutation input '" + typeName + "' field '" + f.name()
                        + "': a composite-key (multi-column) @nodeId column carrier on"
                        + " @mutation(typeName: INSERT) is not supported; the composite-PK"
                        + " INSERT shape is structurally valid but architecturally rare."
                        + " Route through individual @field columns if you really need it.");
                }
                continue;
            }
            // FK-target reference carriers (@nodeId(typeName: T) pointing at another @table's
            // NodeType, classified to DirectFk) admit on INSERT, UPDATE and DELETE at every
            // arity. The carrier's liftedSourceColumns live on the input's own table (no JOIN at
            // the emit site) and the extraction is narrowed to NodeIdDecodeKeys, so the emitters
            // bind decoded keys against liftedSourceColumns positionally, the same shape the
            // same-table NodeId carriers drive.
            if (f instanceof InputField.ColumnBackedReferenceField) {
                continue;
            }
            // UnboundField with @condition(override: true) admits on UPDATE / DELETE; the
            // developer takes over the WHERE half via the explicit condition method. INSERT has
            // no WHERE clause for the override to bind into, so the carrier rejects there.
            // UnboundField with condition.isEmpty() or @condition(override:false) is never a
            // valid mutation input shape: the field has nothing to write and no filter slot.
            if (f instanceof InputField.UnboundField uf) {
                if (uf.condition().isPresent() && uf.condition().get().override()) {
                    if (kind == DmlKind.INSERT) {
                        return Rejection.structural(
                            "@mutation input '" + typeName + "' field '" + uf.name()
                            + "': @condition(override: true) on a @mutation(typeName: INSERT) "
                            + "input field is not supported; INSERT has no WHERE clause for the "
                            + "override condition to bind into");
                    }
                    continue;
                }
                return Rejection.structural(
                    "@mutation input '" + typeName + "' field '" + uf.name()
                    + "': field has no column binding and no @condition(override: true); "
                    + "mutation input fields must bind a column or carry an override condition");
            }
            // A non-@table nested grouping input flattens onto the outer table's columns.
            // Admit it by recursing on its leaves under the same rules; reject a list-typed nesting
            // (no meaning when flattening onto one outer row) and a group-level @condition.
            if (f instanceof InputField.NestingField nf) {
                if (nf.list()) {
                    return Rejection.structural(
                        "@mutation input '" + typeName + "' field '" + nf.name()
                        + "': list-typed nested input types (e.g. '" + nf.name() + ": ["
                        + nf.typeName() + "!]') are not yet supported; a list grouping has no "
                        + "obvious meaning when flattening onto one outer row.");
                }
                if (nf.condition().isPresent()) {
                    return Rejection.structural(
                        "@mutation input '" + typeName + "' field '" + nf.name()
                        + "': @condition on a nested grouping input is not supported.");
                }
                var nestedRejection = admitMutationInputFields(nf.fields(), typeName, kind);
                if (nestedRejection != null) {
                    return nestedRejection;
                }
                continue;
            }
            return Rejection.structural(
                "@mutation input '" + typeName + "' field '" + f.name()
                + "': input field shape " + f.getClass().getSimpleName() + " is not yet supported");
        }
        return null;
    }

    /**
     * Rejects a column written by two or more plain {@code @field} writers (no {@code @nodeId}
     * decode among them), recursing into nested grouping inputs. Such an overlap is a pure schema
     * fact, so it is an author error caught at build time, the mutation-path mirror of the
     * {@code @service} reject. An overlap involving at least one decode is admitted here and left
     * to the runtime value-agreement check (FK topology can legitimately force a column to be
     * written by two references). Returns the first offending column's rejection, or {@code null}.
     * Called from {@code FieldBuilder.resolveInsertWriteTarget}.
     */
    static Rejection rejectPlainColumnCollision(List<InputField> fields, String typeName) {
        var writers = new ArrayList<ColumnOverlap.ColumnWriter>();
        collectSetColumns(fields, List.of(), writers);
        for (var oc : ColumnOverlap.groupByColumn(writers)) {
            if (oc.shared() && oc.allPlain()) {
                return Rejection.structural(
                    "@mutation input '" + typeName + "' fields '" + oc.contributors().get(0).writer().label()
                    + "' and '" + oc.contributors().get(1).writer().label()
                    + "' both resolve to column '" + oc.column().sqlName() + "' — two fields cannot populate one"
                    + " column; remove one, or point its @field(name:) at a different column");
            }
        }
        return null;
    }

    /**
     * One SET-side carrier as a {@link ColumnOverlap.ColumnWriter}; the dotted access path is the
     * reject-message label. The same grouping the emitters trigger value-agreement on yields the
     * all-plain collision the validator rejects on, so reject and agreement read one fold.
     */
    private record InputFieldWriter(List<ColumnRef> targetColumns, boolean decode, String label)
            implements ColumnOverlap.ColumnWriter {}

    /**
     * Accumulates the SET-side carriers writing the input's own columns as
     * {@link ColumnOverlap.ColumnWriter}s, recursing into {@link InputField.NestingField} grouping
     * inputs and threading the access-path prefix. Value carriers source columns from
     * {@code columns()}, FK-reference carriers from {@code liftedSourceColumns()}; a
     * {@link InputField.ColumnBackedField} carries a decode only when its extraction is a
     * {@link CallSiteExtraction.NodeIdDecodeKeys}.
     */
    private static void collectSetColumns(List<InputField> fields, List<String> prefix,
            List<ColumnOverlap.ColumnWriter> writers) {
        for (var f : fields) {
            if (f instanceof InputField.NestingField nf) {
                var child = new ArrayList<>(prefix);
                child.add(nf.name());
                collectSetColumns(nf.fields(), child, writers);
                continue;
            }
            List<ColumnRef> columns;
            boolean decode;
            switch (f) {
                case InputField.ColumnBackedField cf -> {
                    columns = cf.columns();
                    decode = cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys;
                }
                case InputField.ColumnBackedReferenceField crf -> {
                    columns = crf.liftedSourceColumns();
                    decode = crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys;
                }
                default -> { continue; } // UnboundField and other non-column carriers contribute nothing
            }
            var dotted = new ArrayList<>(prefix);
            dotted.add(f.name());
            writers.add(new InputFieldWriter(columns, decode, String.join(".", dotted)));
        }
    }
}
