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

import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;

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
                // DML accepts a @record payload return when the payload class exposes a canonical
                // constructor with one row-slot parameter (typed as the DML's table record) plus
                // optional defaulted slots and an optional errors slot. The shape check runs in
                // FieldBuilder.resolveDmlPayloadAssembly during construction; this validator only
                // screens for the wrapper shape (single, not list/connection).
                if (r.wrapper().isList()) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + r.returnTypeName() + "' (list of @record) is not yet supported; "
                        + "use a single @record payload, an ID, or a @table type";
                }
                // R75 Phase 1: PojoResultType.NoBacking candidates that fail a trigger condition
                // land here (not in ScalarReturnType, because the classifier still recognises the
                // type as a ResultType). Surface the per-condition reason, same shape as the
                // ScalarReturnType arm above; an authored carrier with className is the redirect.
                if (r.fqClassName() == null && ctx != null
                        && ctx.tryResolveSingleRecordCarrier(r.returnTypeName())
                            instanceof no.sikt.graphitron.rewrite.model.SingleRecordCarrierResolution.Rejected rej) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + r.returnTypeName() + "': " + rej.reason()
                        + "; or author a carrier with @record(record: {className: ...})";
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
     * input argument that drives the statement. Enforces the structural mutation invariants on the
     * input shape: exactly one {@code TableInputArg}, no other argument shapes, no listed
     * input, no {@code @condition} on the {@code @table} arg, only {@code ColumnField} entries
     * inside the input type, and (for UPDATE / DELETE / UPSERT) at least one {@code @lookupKey}
     * binding plus (for UPDATE / DELETE) full PK coverage and (for UPDATE) at least one
     * non-{@code @lookupKey} field.
     *
     * @param kind one of {@link DmlKind#INSERT} / {@link DmlKind#UPDATE} /
     *             {@link DmlKind#DELETE} / {@link DmlKind#UPSERT}
     */
    Resolved resolveInput(GraphQLFieldDefinition fieldDef, DmlKind kind) {
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

            var bindingErrors = new java.util.ArrayList<String>();
            List<InputColumnBindingGroup> bindings =
                enumMapping.buildLookupBindings(tit, arg, fieldDef, argName, bindingErrors);
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
                argName, argTypeName, nonNull, list, tit.table(), bindings, argCondition, tit.inputFields());

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
        // Index @lookupKey-bearing field names from the resolved bindings so the per-field check
        // can dispatch on (carrier × isLookupKey × verb). One InputColumnBindingGroup per
        // @lookupKey-bearing input field: MapGroup carries one or more MapBindings (each named
        // by an input-field name); DecodedRecordGroup names its source input field directly.
        var lookupKeyFieldNames = new java.util.HashSet<String>();
        for (var g : foundTia.fieldBindings()) {
            switch (g) {
                case InputColumnBindingGroup.MapGroup mg -> {
                    for (var b : mg.bindings()) lookupKeyFieldNames.add(b.fieldName());
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg ->
                    lookupKeyFieldNames.add(drg.sourceFieldName());
            }
        }

        for (var f : foundTia.fields()) {
            // ColumnField admission rule:
            //   Direct extraction  → always admitted (canonical mutation-input shape).
            //   NodeIdDecodeKeys   → admitted (R130): single-PK NodeId-decoded column write.
            //                        Lookup-WHERE / INSERT-side value reads dispatch on the
            //                        extraction at emit time (Phase 3).
            if (f instanceof InputField.ColumnField) {
                continue;
            }
            // CompositeColumnField is admitted only on lookup-bearing verbs as a @lookupKey
            // field. INSERT / non-@lookupKey-position is carved out (R130 spec): structurally
            // valid but easy to misuse with auto-generated PKs; lifting waits for a forcing-
            // function schema. Lookup-bearing verbs (DELETE/UPDATE/UPSERT) accept the carrier
            // when it bears @lookupKey because the emitter consumes the DecodedRecordGroup;
            // a CompositeColumnField in non-@lookupKey position would require the emitter to
            // write N PK columns from a decoded record on the SET / INSERT-arm side, which
            // is out of R130's scope.
            if (f instanceof InputField.CompositeColumnField ccf) {
                if (kind == DmlKind.INSERT) {
                    return new Resolved.Rejected(Rejection.deferred(
                        "@mutation input '" + foundTia.typeName() + "' field '" + f.name()
                        + "': CompositeColumnField on @mutation(typeName: INSERT) is not"
                        + " supported; the composite-PK INSERT shape is structurally valid"
                        + " but architecturally rare. Route through individual @field columns"
                        + " if you really need it.",
                        ""));
                }
                if (!lookupKeyFieldNames.contains(ccf.name())) {
                    return new Resolved.Rejected(Rejection.deferred(
                        "@mutation input '" + foundTia.typeName() + "' field '" + f.name()
                        + "': CompositeColumnField is admitted only as a @lookupKey field on"
                        + " lookup-bearing @mutation verbs (DELETE/UPDATE/UPSERT). Apply"
                        + " @lookupKey to this field, or route through individual @field"
                        + " columns if a composite-PK column write was intended.",
                        ""));
                }
                continue;
            }
            // Reference carriers (ColumnReferenceField / CompositeColumnReferenceField) stay
            // deferred: no forcing-function schema reaches them today, and re-admission
            // tracks R24's NodeIdReferenceField join-projection work.
            String reason = switch (f) {
                case InputField.NestingField nf -> "nested input types in @mutation fields are not yet supported";
                case InputField.ColumnReferenceField crf ->
                    "@reference / FK-target @nodeId in @mutation inputs is not yet supported;"
                    + " tracked in R24's scope when a forcing-function schema appears";
                case InputField.CompositeColumnReferenceField ccrf ->
                    "@reference / FK-target @nodeId in @mutation inputs is not yet supported;"
                    + " tracked in R24's scope when a forcing-function schema appears";
                // ColumnField / CompositeColumnField are admitted above; this default exists
                // because the sealed sub-interfaces LookupKeyField / SetField widen the surface
                // pattern even though every leaf is reachable through the named arms.
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

        if (kind.requiresLookupKey() && foundTia.fieldBindings().isEmpty()) {
            return new Resolved.Rejected(Rejection.structural("@mutation(typeName: " + kind + ") requires at least one @lookupKey field in the input type"));
        }

        if (kind == DmlKind.UPDATE && foundTia.setFields().isEmpty()) {
            return new Resolved.Rejected(Rejection.structural("@mutation(typeName: UPDATE) has no non-@lookupKey fields to set"));
        }

        if (kind.requiresPkCoverage()) {
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
                            + ") @lookupKey fields do not cover all PK column(s); missing: "
                            + String.join(", ", missing)));
                }
            }
        }

        return new Resolved.Ok(foundTia);
    }
}
