package no.sikt.graphitron.rewrite;

import graphql.language.EnumValue;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnOverlap;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_MULTI_ROW;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_OVERRIDE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_LOOKUP_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;

/**
 * Resolves the DML {@code @mutation} concern: walks a mutation field's arguments to find the
 * single {@code @table} input that drives the statement, validates mutation invariants on the
 * input shape, validates the return-type constraints (Invariant #14), and reads the
 * {@code @mutation(typeName:)} discriminator. Sibling to {@link OrderByResolver},
 * {@link LookupMappingResolver}, {@link PaginationResolver}, {@link ConditionResolver}, and
 * {@link InputFieldResolver}.
 *
 * <p>Three responsibilities cluster here, all centred on {@code @mutation} field classification:
 *
 * <ul>
 *   <li>{@link #parseDmlKind} — reads the {@code typeName} argument off the {@code @mutation}
 *       directive and lifts it into a sealed {@link DmlKindResult}. Used by the
 *       mutation-classification arm to dispatch to the four DML statement variants
 *       ({@link DmlKind#INSERT} / {@link DmlKind#UPDATE} / {@link DmlKind#DELETE} /
 *       {@link DmlKind#UPSERT}).</li>
 *   <li>{@link #validateReturnType} — validates Invariant #14 (mutation return must be
 *       {@code ID}, {@code [ID]}, {@code T}, or {@code [T]} where {@code T} is a {@code @table}
 *       type) and Invariant #15 (bulk-input + single-cardinality return is rejected on the
 *       Scalar/TableBound arms). Returns a non-null rejection reason on violation,
 *       {@code null} on success.</li>
 *   <li>{@link #resolveInput} — the main beast. Walks the field's arguments, finds the single
 *       {@code TableInputArg}, runs the structural mutation invariant checks on it (no
 *       {@code @condition} on the {@code @table} arg, only {@code ColumnField} entries inside
 *       the input type, lookup-key + PK coverage rules per DML variant), and returns a sealed
 *       {@link Resolved} the caller switches on. Listed {@code @table} inputs
 *       ({@code in: [FilmInput!]!}) are admitted; the bulk arm is dispatched via
 *       {@link ArgumentRef.InputTypeArg.TableInputArg#list() TableInputArg.list()} downstream.</li>
 * </ul>
 *
 * <p>Not a caller of {@link FieldBuilder#classifyArgument}: a mutation field has no parent
 * {@code @table}, so the per-arg classifier's column-binding fallback for un-directived scalars
 * would mis-bind here. The walk stays self-contained and rejects anything that isn't a
 * {@code @table} input.
 *
 * <p>The resolver carries references to {@link ConditionResolver} (for argument-level
 * {@code @condition} resolution) and {@link EnumMappingResolver} (for the lookup-binding walk
 * over {@code @lookupKey}-bearing input fields). The lookup-binding walk lives on
 * {@link EnumMappingResolver} so {@link FieldBuilder} is not needed here.
 */
final class MutationInputResolver {

    /**
     * Outcome of {@link #resolveInput}. Two terminal arms. The {@code Ok} arm carries the
     * resolved {@link ArgumentRef.InputTypeArg.TableInputArg} that drives the DML statement; the
     * {@code Rejected} arm carries a fully-formed reason ready for an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} with
     * {@link RejectionKind#AUTHOR_ERROR}.
     */
    sealed interface Resolved {
        record Ok(ArgumentRef.InputTypeArg.TableInputArg tia) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
        }
    }

    /**
     * Outcome of {@link #parseDmlKind}. Three terminal arms.
     *
     * <ul>
     *   <li>{@link Absent} — {@code @mutation} or its {@code typeName:} argument is unset on the
     *       field. Pre-existing behaviour treats this as "not a DML field"; the caller falls
     *       through to its surrounding "directive missing" rejection.</li>
     *   <li>{@link Kind} — the argument resolves to one of the four well-formed values.</li>
     *   <li>{@link Unknown} — the argument is set but doesn't match any {@link DmlKind}; the
     *       caller surfaces an {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}
     *       carrying the raw value.</li>
     * </ul>
     */
    sealed interface DmlKindResult {
        record Absent() implements DmlKindResult {}
        record Kind(DmlKind kind) implements DmlKindResult {}
        record Unknown(String raw) implements DmlKindResult {}
    }

    private static final DmlKindResult ABSENT = new DmlKindResult.Absent();

    private final BuildContext ctx;
    private final ConditionResolver conditionResolver;
    private final EnumMappingResolver enumMapping;

    MutationInputResolver(BuildContext ctx, ConditionResolver conditionResolver, EnumMappingResolver enumMapping) {
        this.ctx = ctx;
        this.conditionResolver = conditionResolver;
        this.enumMapping = enumMapping;
    }

    /**
     * Reads the {@code typeName} argument off the {@code @mutation} directive on
     * {@code fieldDef} and lifts the raw String into a sealed {@link DmlKindResult} the caller
     * switches on. {@link DmlKindResult.Absent} signals the directive is absent or the argument
     * is unset; {@link DmlKindResult.Kind} carries the resolved {@link DmlKind};
     * {@link DmlKindResult.Unknown} carries the raw value when it doesn't match any kind.
     */
    DmlKindResult parseDmlKind(GraphQLFieldDefinition fieldDef) {
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
     * Validates Invariant #14 — DML {@code @mutation} return type must be {@code ID},
     * {@code [ID]}, {@code T}, {@code [T]}, or a single class-backed payload (where
     * {@code T} is a {@code @table} type) — and Invariant #15 — when the {@code @table}
     * input is list-shaped ({@code in: [FilmInput!]!}), the return type must also be
     * list-shaped. The cardinality check is sealed-root-uniform across all three admitted
     * return-type arms ({@link ReturnTypeRef.ScalarReturnType ID},
     * {@link ReturnTypeRef.TableBoundReturnType T}, and
     * {@link ReturnTypeRef.ResultReturnType Payload}): for every arm the DML emit path
     * ends in {@code valuesOfRows(...).returningResult(...).fetchOne()}, whose jOOQ
     * contract throws {@code TooManyRowsException} on any input with &gt;1 row. Rejecting
     * at classify time keeps that footgun from reaching authors.
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
                // R178 Phase 4: candidate types whose carrier-shape is structurally invalid
                // surface a per-condition diagnostic through scanStructuralDmlPayload: if the
                // SDL Object's fields don't classify into recognized DML element kinds, the
                // scan's Reject arm names the offending field.
                if (ctx != null) {
                    var scan = ctx.scanStructuralDmlPayload(s.returnTypeName());
                    if (scan instanceof BuildContext.DmlPayloadScan.Reject scanReject) {
                        yield "@mutation(typeName: " + kind + ") return type '"
                            + s.returnTypeName() + "': " + scanReject.reason()
                            + "; or back the carrier with a producing @service return type or a @table type";
                    }
                    // R310: a payload that would classify as a DML carrier but for a forbidden
                    // directive on its data field surfaces a targeted message naming the offending
                    // field and directive, instead of the generic "use ID or a @table type"
                    // misdirection, which points at the return type (which is fine) rather than the
                    // one-token edit on the data field that actually disqualified it.
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
                // R178 step 3: DML accepts a class-backed carrier return; the validator screens only
                // for the wrapper shape (single, not list/connection). Payload-shape rejections
                // surface from the unified path's per-child classification (the legacy-equality
                // check inside FieldBuilder.buildServiceField on @service mutations; the
                // @mutation classifier's inline record-backed element / table-equality checks on DML).
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
        // R141: cardinality dispatch on the carrier's data-channel wrapper. A single-record carrier
        // (Payload type, not list-wrapped) whose data field is list-shaped pairs with bulk input
        // (admitted as MutationBulkDmlRecordField) and rejects single input (new Invariant #16).
        // The singleton-data-field case (data field is single-shaped) still rejects bulk input via
        // R138's lifted Invariant #15 below. UPSERT under R144's cardinality-safety regime is
        // refused upstream at resolveInput; if we ever reach this check with kind == UPSERT and
        // bulk input, the refusal there will fire before this point.
        // R178 Phase 4: payload-shaped (ResultReturnType) returns dispatch cardinality coherence
        // on the structural single-data-field's wrapper; non-payload returns dispatch on the
        // return's own wrapper. The structural walk via singleDataField produces the
        // admit/reject decision for the carrier-with-single-data-field case.
        if (returnType instanceof ReturnTypeRef.ResultReturnType r && ctx != null) {
            var dataField = singleDataField(r.returnTypeName(), ctx);
            if (dataField != null) {
                boolean dataFieldIsList = ctx.buildWrapper(dataField).isList();
                if (listInput && dataFieldIsList) {
                    // R141 admitted arm: bulk input + list-shaped @table-element data field.
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
            // Carrier with zero or multiple recognized data fields: the @mutation classifier's
            // structural scan (BuildContext.scanStructuralDmlPayload) owns the rejection
            // diagnostics; return null and let the classifier surface them.
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
     * R178 Phase 4 — structural lookup for a payload's single non-errors data field. Returns
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
     * Walks a DML {@code @mutation} field's arguments and resolves the single {@code @table}
     * input argument that drives the statement. After R246 / R258 / R266 intercept UPDATE and
     * DELETE before this call, INSERT is the lone verb that completes resolveInput (UPSERT is
     * refused at the top, deferred to R145). It enforces:
     *
     * <ul>
     *   <li>UPSERT is refused outright (deferred to R145).</li>
     *   <li>{@code multiRow: true} is rejected on INSERT (no WHERE clause to multiply over).</li>
     *   <li>Per-input-field structural checks: value carriers ({@link InputField.ColumnField} /
     *       {@link InputField.CompositeColumnField}) and FK-target reference carriers (R189) are
     *       admitted. A non-{@code @table} {@link InputField.NestingField} grouping is admitted by
     *       recursing on its leaves under the same rules (R186); a list-typed nesting and a nested
     *       {@code @table} input (R122's compound-entity territory) are not. {@code @lookupKey} on
     *       input fields rejects via the classifier's retirement diagnostic (R144).
     *       {@code @condition} without {@code override: true} rejects (R215), at every nesting
     *       depth.</li>
     * </ul>
     *
     * <p>UPDATE and DELETE never reach the body: their walker classifiers in {@link FieldBuilder}
     * intercept them before this call, and a regression that routes one back here fails loudly via
     * the {@code IllegalStateException} guard. R266 retired {@code @value} (the last partition
     * machinery) along with the UPDATE/DELETE PK-coverage check, both of which moved onto the
     * {@code UpdateRowsWalker} / {@code DeleteRowsWalker}.
     *
     * <p>The empty-input case ("no fields on the {@code @table} input") needs no resolver-level
     * check: graphql-java rejects empty input types at parse time
     * ({@code "InputObjectType ... must define one or more fields"}), so {@code foundTia.fields()}
     * is non-empty by parser guarantee. Every non-admissible field shape rejects in the loop above.
     *
     * @param kind one of {@link DmlKind#INSERT} / {@link DmlKind#UPDATE} /
     *             {@link DmlKind#DELETE} / {@link DmlKind#UPSERT}
     */
    Resolved resolveInput(GraphQLFieldDefinition fieldDef, DmlKind kind) {
        if (kind == DmlKind.UPSERT) {
            return new Resolved.Rejected(Rejection.deferred(
                "@mutation(typeName: UPSERT) is not supported under the R144 cardinality-safety "
                + "regime; the conflict-target's uniqueness and the bulk-UPSERT cardinality story "
                + "are designed under R145 (mutation-cardinality-safety-upsert).",
                "mutation-cardinality-safety-upsert"));
        }

        boolean multiRow = parseMultiRow(fieldDef);
        if (multiRow && kind == DmlKind.INSERT) {
            return new Resolved.Rejected(Rejection.structural(
                "@mutation(typeName: INSERT) does not accept multiRow: true; INSERT has no WHERE "
                + "clause to multiply over"));
        }

        var args = fieldDef.getArguments();
        ArgumentRef.InputTypeArg.TableInputArg foundTia = null;
        String multipleArgsError = null;
        for (var arg : args) {
            String argName = arg.getName();
            GraphQLType argType = arg.getType();
            boolean nonNull = argType instanceof GraphQLNonNull;
            boolean list = GraphQLTypeUtil.unwrapNonNull(argType) instanceof GraphQLList;
            String argTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(argType)).getName();

            var resolvedType = ctx.types.get(argTypeName);
            if (!(resolvedType instanceof GraphitronType.TableInputType tit)) {
                return new Resolved.Rejected(Rejection.structural("@mutation fields only accept @table input arguments; found '" + argName
                        + "' of type '" + argTypeName + "'"));
            }

            // Resolve the SDL input object so per-field @value / @condition co-occurrence can be
            // checked. Same lookup the binding walk uses.
            var sdlInputType = ctx.schema.getType(argTypeName);
            if (!(sdlInputType instanceof graphql.schema.GraphQLInputObjectType iot)) {
                return new Resolved.Rejected(Rejection.structural(
                    "@mutation input '" + argTypeName + "' did not resolve as an InputObject in the schema"));
            }

            // Validate directive presence per input field. @lookupKey on any input field rejects
            // with the retirement diagnostic (R144); @condition without override(true) is filter-
            // shape that competes with the verb's WHERE shape and rejects (R215). R266 retired
            // @value (UPDATE and DELETE both classify through their walkers now; INSERT is the lone
            // verb reaching here and never partitioned its input fields), so there is no @value
            // marker to accumulate or reject.
            var directiveRejection = rejectInputFieldDirectives(iot, argTypeName);
            if (directiveRejection != null) {
                return directiveRejection;
            }

            var bindingErrors = new java.util.ArrayList<String>();
            List<InputColumnBindingGroup> bindings =
                enumMapping.buildLookupBindings(tit, arg, fieldDef, argName, bindingErrors);
            // INSERT walks tia.fields() directly for VALUES emission and never reads
            // tia.fieldBindings(); the binding set is structurally empty.
            if (kind == DmlKind.INSERT) {
                bindings = List.of();
            }
            if (!bindingErrors.isEmpty()) {
                return new Resolved.Rejected(Rejection.structural(String.join("; ", bindingErrors)));
            }
            Optional<ArgConditionRef> argCondition;
            switch (conditionResolver.resolveArg(arg)) {
                case ConditionResolver.ArgConditionResult.None n -> argCondition = Optional.empty();
                case ConditionResolver.ArgConditionResult.Ok ok -> argCondition = Optional.of(ok.ref());
                case ConditionResolver.ArgConditionResult.Rejected r ->
                    { return new Resolved.Rejected(Rejection.structural(r.message())); }
            }
            var tia = ArgumentRef.InputTypeArg.TableInputArg.of(
                argName, argTypeName, nonNull, list, tit.table(), bindings, argCondition,
                tit.inputFields());

            if (foundTia != null) {
                multipleArgsError = "@mutation field has more than one @table input argument";
                break;
            }
            foundTia = tia;
        }
        if (multipleArgsError != null) {
            return new Resolved.Rejected(Rejection.structural(multipleArgsError));
        }
        if (foundTia == null) {
            return new Resolved.Rejected(Rejection.structural("no @table input argument found on @mutation field"));
        }

        if (foundTia.argCondition().isPresent()) {
            return new Resolved.Rejected(Rejection.structural("@condition on a @mutation field argument is not supported"));
        }

        var admissionRejection = admitMutationInputFields(foundTia.fields(), foundTia.typeName(), kind);
        if (admissionRejection != null) {
            return admissionRejection;
        }

        // R322 (D2): two or more plain @field writers on one column is a pure schema fact (no runtime
        // input could make them agree) and is avoidable, so it is a validate-time reject — the mutation-
        // path mirror of the @service R336 reject, moving the failure from a Postgres "column specified
        // more than once" crash to an UnclassifiedField. An overlap involving a @nodeId decode is admitted
        // and reconciled at runtime by the value-agreement check (D3), so it is not caught here.
        var collisionRejection = rejectPlainColumnCollision(foundTia.fields(), foundTia.typeName());
        if (collisionRejection != null) {
            return collisionRejection;
        }

        if (kind == DmlKind.UPDATE || kind == DmlKind.DELETE) {
            // R246 / R258 / R266: every UPDATE and DELETE is intercepted in FieldBuilder before this
            // call — UPDATE by classifyUpdateTableField / classifyUpdatePayloadField, DELETE by
            // classifyDeleteTableField / classifyDeletePayloadField — and identified by the
            // UpdateRowsWalker / DeleteRowsWalker's PK-or-UK matched-key membership, never by @value
            // or a resolveInput PK-coverage check. Reaching here means a future regression routed one
            // back onto resolveInput; fail the build loudly. With both intercepted, INSERT is the
            // lone verb that completes resolveInput, so the legacy PK-coverage block (which only ever
            // fired for UPDATE / DELETE) is gone and @value is fully retired (R188).
            throw new IllegalStateException(
                "MutationInputResolver.resolveInput reached with DmlKind." + kind + " — UPDATE and "
                + "DELETE are intercepted in FieldBuilder by their walker classifiers before "
                + "resolveInput and never reach the @value / PK-coverage machinery (R246 / R258 / R266).");
        }

        return new Resolved.Ok(foundTia);
    }

    /**
     * R215 + R186: reject {@code @lookupKey} (retired by R144) and non-override {@code @condition}
     * (filter-shape that competes with the verb's WHERE) on any mutation input field, recursing into
     * nested non-{@code @table} grouping inputs so a buried leaf is held to the same rule as a
     * top-level field. The {@code @condition(override: true)} case is left to the per-field admission
     * walk ({@link #admitMutationInputFields}), which routes it through the classifier's
     * {@code UnboundField} collapse. Returns the first rejection, or {@code null} when every field
     * (at every nesting depth) carries an admissible directive set.
     */
    private Resolved.Rejected rejectInputFieldDirectives(
            graphql.schema.GraphQLInputObjectType iot, String argTypeName) {
        for (var sdlField : iot.getFieldDefinitions()) {
            if (sdlField.hasAppliedDirective(DIR_LOOKUP_KEY)) {
                return new Resolved.Rejected(Rejection.structural(
                    "@mutation input '" + argTypeName + "' field '" + sdlField.getName()
                    + "': @lookupKey on a mutation input field is no longer supported (R144); "
                    + "remove it (the field is a filter by default)"));
            }
            if (sdlField.hasAppliedDirective(DIR_CONDITION)) {
                var condDir = sdlField.getAppliedDirective(DIR_CONDITION);
                var overrideArg = condDir != null ? condDir.getArgument(ARG_OVERRIDE) : null;
                boolean override = overrideArg != null && Boolean.TRUE.equals(overrideArg.getValue());
                if (!override) {
                    return new Resolved.Rejected(Rejection.structural(
                        "@mutation input '" + argTypeName + "' field '" + sdlField.getName()
                        + "': @condition on a mutation input field is not supported "
                        + "(mutations write values; only @condition(override: true) is admitted)"));
                }
            }
            // R186: recurse into nested non-@table grouping inputs so a nested-leaf @lookupKey /
            // @condition is rejected with the same diagnostic as a top-level field. A nested @table
            // input is R122's territory and is not descended here.
            var base = GraphQLTypeUtil.unwrapAll(sdlField.getType());
            if (base instanceof graphql.schema.GraphQLInputObjectType nested
                    && !nested.hasAppliedDirective(DIR_TABLE)) {
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
     * walkers; UPSERT is refused at the top of {@link #resolveInput}). Recurses into
     * {@link InputField.NestingField} grouping inputs (R186): a nested leaf is admitted under the
     * same rules as a root leaf, so a buried {@link InputField.CompositeColumnField} still trips the
     * R130 INSERT carve-out. A list-typed nesting or a nested group carrying {@code @condition}
     * rejects naming R186. Returns the first inadmissible field's rejection, or {@code null}.
     */
    private Resolved.Rejected admitMutationInputFields(List<InputField> fields, String typeName, DmlKind kind) {
        for (var f : fields) {
            // ColumnField admission rule:
            //   Direct extraction  → always admitted (canonical mutation-input shape).
            //   NodeIdDecodeKeys   → admitted (R130): single-PK NodeId-decoded column write.
            if (f instanceof InputField.ColumnField) {
                continue;
            }
            // R144 lifts the R130 carve-out: CompositeColumnField is now admissible on every
            // non-UPSERT verb. R130's INSERT carve-out stays in place (composite-PK INSERT shape is
            // architecturally rare; lifting waits for a forcing-function schema). The carve-out fires
            // for a nested leaf too, since the recursion reaches it.
            if (f instanceof InputField.CompositeColumnField) {
                if (kind == DmlKind.INSERT) {
                    return new Resolved.Rejected(Rejection.deferred(
                        "@mutation input '" + typeName + "' field '" + f.name()
                        + "': CompositeColumnField on @mutation(typeName: INSERT) is not"
                        + " supported; the composite-PK INSERT shape is structurally valid"
                        + " but architecturally rare. Route through individual @field columns"
                        + " if you really need it.",
                        ""));
                }
                continue;
            }
            // R189: FK-target reference carriers ({@code @nodeId(typeName: T)} pointing at
            // another @table's NodeType, classified to DirectFk) admit on INSERT, UPDATE and
            // DELETE. The carrier's liftedSourceColumns live on the input's own table — no JOIN
            // at the emit site — and the extraction is narrowed to NodeIdDecodeKeys, so the
            // emitters bind decoded keys against liftedSourceColumns positionally, the same
            // shape the same-table NodeId carriers (ColumnField / CompositeColumnField with
            // NodeIdDecodeKeys) already drive.
            if (f instanceof InputField.ColumnReferenceField
                || f instanceof InputField.CompositeColumnReferenceField) {
                continue;
            }
            // R215: UnboundField with @condition(override: true) admits on UPDATE / DELETE; the
            // developer takes over the WHERE half via the explicit condition method. INSERT has
            // no WHERE clause for the override to bind into, so the carrier rejects there.
            // UnboundField with condition.isEmpty() or @condition(override:false) is never a
            // valid mutation input shape — the field has nothing to write and no filter slot.
            if (f instanceof InputField.UnboundField uf) {
                if (uf.condition().isPresent() && uf.condition().get().override()) {
                    if (kind == DmlKind.INSERT) {
                        return new Resolved.Rejected(Rejection.structural(
                            "@mutation input '" + typeName + "' field '" + uf.name()
                            + "': @condition(override: true) on a @mutation(typeName: INSERT) "
                            + "input field is not supported; INSERT has no WHERE clause for the "
                            + "override condition to bind into"));
                    }
                    continue;
                }
                return new Resolved.Rejected(Rejection.structural(
                    "@mutation input '" + typeName + "' field '" + uf.name()
                    + "': field has no column binding and no @condition(override: true); "
                    + "mutation input fields must bind a column or carry an override condition"));
            }
            // R186: a non-@table nested grouping input flattens onto the outer table's columns.
            // Admit it by recursing on its leaves under the same rules; reject a list-typed nesting
            // (no meaning when flattening onto one outer row) and a group-level @condition (R245).
            if (f instanceof InputField.NestingField nf) {
                if (nf.list()) {
                    return new Resolved.Rejected(Rejection.structural(
                        "@mutation input '" + typeName + "' field '" + nf.name()
                        + "': list-typed nested input types (e.g. '" + nf.name() + ": ["
                        + nf.typeName() + "!]') are not yet supported (R186); a list grouping has no "
                        + "obvious meaning when flattening onto one outer row."));
                }
                if (nf.condition().isPresent()) {
                    return new Resolved.Rejected(Rejection.structural(
                        "@mutation input '" + typeName + "' field '" + nf.name()
                        + "': @condition on a nested grouping input is not supported (R245)."));
                }
                var nestedRejection = admitMutationInputFields(nf.fields(), typeName, kind);
                if (nestedRejection != null) {
                    return nestedRejection;
                }
                continue;
            }
            return new Resolved.Rejected(Rejection.structural(
                "@mutation input '" + typeName + "' field '" + f.name()
                + "': input field shape " + f.getClass().getSimpleName() + " is not yet supported"));
        }
        return null;
    }

    /**
     * R322 (D2): rejects a column written by two or more plain {@code @field} writers (no {@code @nodeId}
     * decode among them), recursing into nested grouping inputs so a buried leaf is held to the same rule.
     * Such an overlap is a pure schema fact (both names resolve to one column with no runtime input) and is
     * avoidable, so it is an author error caught at build time, the mutation-path mirror of the {@code @service}
     * R336 reject. An overlap involving at least one decode is left to the runtime value-agreement check
     * (FK topology can legitimately force a column to be written by two references), so it is admitted here.
     * Returns the first offending column's rejection, or {@code null}.
     */
    private Resolved.Rejected rejectPlainColumnCollision(List<InputField> fields, String typeName) {
        var writers = new ArrayList<ColumnOverlap.ColumnWriter>();
        collectSetColumns(fields, List.of(), writers);
        for (var oc : ColumnOverlap.groupByColumn(writers)) {
            if (oc.shared() && oc.allPlain()) {
                return new Resolved.Rejected(Rejection.structural(
                    "@mutation input '" + typeName + "' fields '" + oc.contributors().get(0).writer().label()
                    + "' and '" + oc.contributors().get(1).writer().label()
                    + "' both resolve to column '" + oc.column().sqlName() + "' — two fields cannot populate one"
                    + " column; remove one, or point its @field(name:) at a different column"));
            }
        }
        return null;
    }

    /** R356: one SET-side carrier as a {@link ColumnOverlap.ColumnWriter}; the dotted access path is the
     *  reject-message label. The same shared grouping the emitters trigger value-agreement on (R322/R342)
     *  yields the all-plain collision the validator rejects on, so reject and agreement read one fold. */
    private record InputFieldWriter(List<ColumnRef> targetColumns, boolean decode, String label)
            implements ColumnOverlap.ColumnWriter {}

    /**
     * Accumulates the SET-side carriers writing the input's own columns as {@link ColumnOverlap.ColumnWriter}s,
     * recursing into {@link InputField.NestingField} grouping inputs (R186) and threading the access-path
     * prefix. Value carriers source columns from {@code column() / columns()}, FK-reference carriers from
     * {@code liftedSourceColumns()}; composite and reference carriers carry a decode by construction, a
     * {@link InputField.ColumnField} only when its extraction is a {@link CallSiteExtraction.NodeIdDecodeKeys}.
     */
    private void collectSetColumns(List<InputField> fields, List<String> prefix,
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
                case InputField.ColumnField cf -> {
                    columns = List.of(cf.column());
                    decode = cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys;
                }
                case InputField.CompositeColumnField ccf -> { columns = ccf.columns(); decode = true; }
                case InputField.ColumnReferenceField crf -> {
                    columns = crf.liftedSourceColumns();
                    decode = crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys;
                }
                case InputField.CompositeColumnReferenceField ccrf -> {
                    columns = ccrf.liftedSourceColumns();
                    decode = true;
                }
                default -> { continue; } // UnboundField and other non-column carriers contribute nothing
            }
            var dotted = new ArrayList<>(prefix);
            dotted.add(f.name());
            writers.add(new InputFieldWriter(columns, decode, String.join(".", dotted)));
        }
    }
}
