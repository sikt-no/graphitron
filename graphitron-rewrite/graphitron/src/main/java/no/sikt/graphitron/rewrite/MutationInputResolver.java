package no.sikt.graphitron.rewrite;

import graphql.language.EnumValue;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_MULTI_ROW;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_LOOKUP_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_VALUE;

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
     * {@code [ID]}, {@code T}, {@code [T]}, or a single {@code @record} payload (where
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
                // R75 Phase 1: candidates whose carrier types failed to promote land here as a
                // ScalarReturnType (no PojoResultType registered). Surface the per-condition reason
                // from the trigger so the validator names the same criterion the classifier checked.
                if (ctx != null && ctx.tryResolveSingleRecordCarrier(s.returnTypeName())
                        instanceof no.sikt.graphitron.rewrite.model.SingleRecordCarrierResolution.Rejected rej) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + s.returnTypeName() + "': " + rej.reason()
                        + "; or author a carrier with @record(record: {className: ...})";
                }
                yield "@mutation(typeName: " + kind + ") return type '"
                    + s.returnTypeName() + "' is not yet supported; use ID or a @table type";
            }
            case ReturnTypeRef.TableBoundReturnType tb -> {
                if (tb.wrapper() instanceof FieldWrapper.Connection) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + tb.returnTypeName() + "' (Connection) is not yet supported; use ID or a @table type";
                }
                yield null;
            }
            case ReturnTypeRef.ResultReturnType r -> {
                // DML accepts a @record carrier return when the SDL type classifies through the
                // carrier walk (one DataChannel field, optional ErrorChannelRole field). The
                // validator only screens for the wrapper shape (single, not list/connection)
                // and surfaces the carrier walk's per-condition rejection reason.
                if (r.wrapper().isList()) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + r.returnTypeName() + "' (list of @record) is not yet supported; "
                        + "use a single @record payload, an ID, or a @table type";
                }
                // R161: the carrier walk's candidate predicate admits every ResultType arm, so
                // the probe runs unconditionally — one probe keyed off the SDL shape, not two
                // probes composing on `fqClassName == null` vs `!= null`.
                if (ctx != null
                        && ctx.tryResolveSingleRecordCarrier(r.returnTypeName())
                            instanceof no.sikt.graphitron.rewrite.model.SingleRecordCarrierResolution.Rejected rej) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + r.returnTypeName() + "': " + rej.reason();
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
        if (returnType instanceof ReturnTypeRef.ResultReturnType r && ctx != null) {
            var resolution = ctx.tryResolveSingleRecordCarrier(r.returnTypeName());
            if (resolution instanceof no.sikt.graphitron.rewrite.model.SingleRecordCarrierResolution.Ok ok) {
                var data = ok.shape().data();
                boolean dataFieldIsList = data.element().wrapper().isList();
                if (listInput && dataFieldIsList) {
                    // R141 admitted arm: bulk input + list-shaped @table-element data field.
                    // Classifier will route this to MutationBulkDmlRecordField; the response-SELECT
                    // joins on the input table's PK and the emitter batches per-row DML in input
                    // order to satisfy the order-preservation invariant.
                    return null;
                }
                if (!listInput && dataFieldIsList) {
                    return "@mutation(typeName: " + kind + ") with a single @table input "
                        + "cannot return a list-shaped data field on the carrier ('"
                        + ok.shape().carrierTypeName() + "." + data.fieldName()
                        + "'); list-shaped output requires bulk input (Invariant #16)";
                }
            }
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
     * Walks a DML {@code @mutation} field's arguments and resolves the single {@code @table}
     * input argument that drives the statement. R144 enforces:
     *
     * <ul>
     *   <li>UPSERT is refused outright (deferred to R145).</li>
     *   <li>{@code multiRow: true} is rejected on INSERT (no WHERE clause to multiply over).</li>
     *   <li>Per-input-field structural checks: only {@link InputField.ColumnField} and
     *       {@link InputField.CompositeColumnField} are admitted; reference carriers and nesting
     *       fields stay deferred. {@code @lookupKey} on input fields rejects via the classifier's
     *       retirement diagnostic (handled upstream).</li>
     *   <li>{@code @value}-on-DELETE / {@code @value}-on-INSERT and
     *       {@code @value}-with-{@code @condition} on the same field are mutually exclusive
     *       structural rejections.</li>
     *   <li>DELETE / UPDATE: WHERE-side filter columns must cover the table's primary key, unless
     *       the mutation carries {@code multiRow: true}.</li>
     *   <li>UPDATE: at least one {@code @value}-marked field; not every field {@code @value}-marked.</li>
     * </ul>
     *
     * <p>The empty-input case ("no fields on the {@code @table} input") needs no resolver-level
     * check: graphql-java rejects empty input types at parse time
     * ({@code "InputObjectType ... must define one or more fields"}), so {@code foundTia.fields()}
     * is non-empty by parser guarantee. Every non-admissible field shape rejects in the loop above.
     *
     * @param kind one of {@link DmlKind#INSERT} / {@link DmlKind#UPDATE} /
     *             {@link DmlKind#DELETE} / {@link DmlKind#UPSERT}
     */
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "mutation-input.where-columns-cover-pk",
        description = "On DELETE / UPDATE without `multiRow: true`, the union of contributed "
            + "filter columns (ColumnField.column() and CompositeColumnField.columns()) covers "
            + "the input @table's primary key. Lets the lookup-WHERE emitter assume the WHERE "
            + "clause matches at most one row per input row.")
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "mutation-input.update-set-fields-equal-value-marked",
        description = "On DmlKind.UPDATE, tia.setFields() is exactly the set of input fields "
            + "carrying @value (in SDL declaration order). On DmlKind.DELETE / DmlKind.INSERT, "
            + "tia.setFields() is empty. Lets each SET-walking emitter trust the partition "
            + "source without checking kind or directive presence.")
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

            // Validate directive presence per verb. @value rejects on DELETE / INSERT;
            // @lookupKey on any input field rejects with the retirement diagnostic (R144);
            // @value + @condition on the same field is mutually exclusive.
            var valueMarkedNames = new java.util.LinkedHashSet<String>();
            for (var sdlField : iot.getFieldDefinitions()) {
                boolean hasValue = sdlField.hasAppliedDirective(DIR_VALUE);
                boolean hasLookupKey = sdlField.hasAppliedDirective(DIR_LOOKUP_KEY);
                boolean hasCondition = sdlField.hasAppliedDirective(DIR_CONDITION);
                if (hasLookupKey) {
                    return new Resolved.Rejected(Rejection.structural(
                        "@mutation input '" + argTypeName + "' field '" + sdlField.getName()
                        + "': @lookupKey on a mutation input field is no longer supported (R144); "
                        + "remove it (the field is a filter by default), or replace it with "
                        + "@value on UPDATE value fields"));
                }
                if (hasValue) {
                    if (!kind.acceptsValueMarker()) {
                        String suffix = kind == DmlKind.DELETE
                            ? "DELETE has no assignment clause"
                            : "INSERT does not partition its input fields";
                        return new Resolved.Rejected(Rejection.structural(
                            "@mutation input '" + argTypeName + "' field '" + sdlField.getName()
                            + "': @value is not valid on @mutation(typeName: " + kind + ") inputs; " + suffix));
                    }
                    if (hasCondition) {
                        return new Resolved.Rejected(Rejection.structural(
                            "@mutation input '" + argTypeName + "' field '" + sdlField.getName()
                            + "': @value and @condition on the same input field are mutually exclusive"));
                    }
                    valueMarkedNames.add(sdlField.getName());
                }
            }

            var bindingErrors = new java.util.ArrayList<String>();
            List<InputColumnBindingGroup> bindings =
                enumMapping.buildLookupBindings(tit, arg, fieldDef, argName, bindingErrors, valueMarkedNames);
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
                tit.inputFields(), kind, valueMarkedNames);

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

        for (var f : foundTia.fields()) {
            // ColumnField admission rule:
            //   Direct extraction  → always admitted (canonical mutation-input shape).
            //   NodeIdDecodeKeys   → admitted (R130): single-PK NodeId-decoded column write.
            if (f instanceof InputField.ColumnField) {
                continue;
            }
            // R144 lifts the R130 carve-out: CompositeColumnField is now admissible on every
            // non-UPSERT verb regardless of @value position. The directive question "filter or
            // value?" is answered by @value presence, not by carrier shape. R130's INSERT carve-
            // out stays in place (composite-PK INSERT shape is architecturally rare; lifting
            // waits for a forcing-function schema).
            if (f instanceof InputField.CompositeColumnField) {
                if (kind == DmlKind.INSERT) {
                    return new Resolved.Rejected(Rejection.deferred(
                        "@mutation input '" + foundTia.typeName() + "' field '" + f.name()
                        + "': CompositeColumnField on @mutation(typeName: INSERT) is not"
                        + " supported; the composite-PK INSERT shape is structurally valid"
                        + " but architecturally rare. Route through individual @field columns"
                        + " if you really need it.",
                        ""));
                }
                continue;
            }
            // Reference carriers and nesting fields stay deferred: no forcing-function schema
            // reaches them today, and re-admission tracks R24's NodeIdReferenceField
            // join-projection work / R128's compound-entity-mutations territory.
            String reason = switch (f) {
                case InputField.NestingField nf -> "nested input types in @mutation fields are not yet supported";
                case InputField.ColumnReferenceField crf ->
                    "@reference / FK-target @nodeId in @mutation inputs is not yet supported;"
                    + " tracked in R24's scope when a forcing-function schema appears";
                case InputField.CompositeColumnReferenceField ccrf ->
                    "@reference / FK-target @nodeId in @mutation inputs is not yet supported;"
                    + " tracked in R24's scope when a forcing-function schema appears";
                default -> "input field shape " + f.getClass().getSimpleName() + " is not yet supported";
            };
            Rejection rej = (f instanceof InputField.ColumnReferenceField
                || f instanceof InputField.CompositeColumnReferenceField)
                ? Rejection.deferred("@mutation input '" + foundTia.typeName() + "' field '"
                    + f.name() + "': " + reason, "nodeidreferencefield-join-projection-form")
                : Rejection.structural("@mutation input '" + foundTia.typeName() + "' field '"
                    + f.name() + "': " + reason);
            return new Resolved.Rejected(rej);
        }

        if (kind == DmlKind.UPDATE) {
            if (foundTia.setFields().isEmpty()) {
                return new Resolved.Rejected(Rejection.structural(
                    "@mutation(typeName: UPDATE) has no @value fields to set; mark at least one "
                    + "input field with @value to define the SET clause"));
            }
            if (foundTia.lookupKeyFields().isEmpty()) {
                return new Resolved.Rejected(Rejection.structural(
                    "@mutation(typeName: UPDATE) has no filter fields (every input field is "
                    + "@value-marked); UPDATE without a WHERE clause would update every row"));
            }
        }

        if (kind.requiresPkCoverage() && !multiRow) {
            var pkColumns = ctx.catalog.findPkColumns(foundTia.inputTable().tableName());
            if (!pkColumns.isEmpty()) {
                var boundSqlNames = foundTia.fieldBindings().stream()
                    .flatMap(g -> g.targetColumns().stream())
                    .map(c -> c.sqlName())
                    .collect(Collectors.toSet());
                var missing = pkColumns.stream()
                    .map(JooqCatalog.ColumnEntry::sqlName)
                    .filter(c -> !boundSqlNames.contains(c))
                    .toList();
                if (!missing.isEmpty()) {
                    return new Resolved.Rejected(Rejection.structural("@mutation(typeName: " + kind
                            + ") filter columns do not cover all PK column(s); missing: "
                            + String.join(", ", missing)
                            + ". Add the missing column(s) to the @table input, or opt into "
                            + "broadcast semantics with multiRow: true on the @mutation directive."));
                }
            }
        }

        return new Resolved.Ok(foundTia);
    }
}
