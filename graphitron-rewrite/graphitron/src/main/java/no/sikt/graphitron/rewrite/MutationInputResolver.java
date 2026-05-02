package no.sikt.graphitron.rewrite;

import graphql.language.EnumValue;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;

/**
 * Resolves the DML {@code @mutation} concern: walks a mutation field's arguments to find the
 * single {@code @table} input that drives the statement, validates Phase 1 mutation invariants
 * on the input shape, validates the return-type constraints (Invariant #14), and reads the
 * {@code @mutation(typeName:)} discriminator. The sixth and last projection resolver under R6
 * (Phase 6e), sibling to {@link OrderByResolver} (Phase 5), {@link LookupMappingResolver}
 * (Phase 6a), {@link PaginationResolver} (Phase 6b), {@link ConditionResolver} (Phase 6c), and
 * {@link InputFieldResolver} (Phase 6d).
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
 *       type). Returns a non-null rejection reason on violation, {@code null} on success.</li>
 *   <li>{@link #resolveInput} — the main beast. Walks the field's arguments, finds the single
 *       {@code TableInputArg}, runs all Phase 1 mutation invariant checks on it (no listed
 *       input, no {@code @condition} on the {@code @table} arg, only {@code ColumnField}
 *       entries inside the input type, lookup-key + PK coverage rules per DML variant), and
 *       returns a sealed {@link Resolved} the caller switches on.</li>
 * </ul>
 *
 * <p>Not a caller of {@link FieldBuilder#classifyArgument}: a mutation field has no parent
 * {@code @table}, so the per-arg classifier's column-binding fallback for un-directived scalars
 * would mis-bind here. The walk stays self-contained and rejects anything that isn't a
 * {@code @table} input.
 *
 * <p>The resolver carries references to {@link ConditionResolver} (for argument-level
 * {@code @condition} resolution) and {@link EnumMappingResolver} (for the lookup-binding walk
 * over {@code @lookupKey}-bearing input fields). No reference to {@link FieldBuilder} is
 * needed since the enum-mapping axis lift in Phase 7 moved {@code buildLookupBindings} into
 * its own resolver.
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
        record Rejected(String message) implements Resolved {}
    }

    /**
     * The four DML statement kinds {@code @mutation(typeName:)} can take. Lifting the schema's
     * raw String into an enum lets every downstream switch (kind dispatch, invariant rules,
     * return-type validation) be exhaustive at compile time.
     */
    enum DmlKind {
        INSERT, UPDATE, DELETE, UPSERT;

        /** UPDATE / DELETE / UPSERT require at least one {@code @lookupKey} binding. */
        boolean requiresLookupKey() {
            return this != INSERT;
        }
        /** UPDATE / DELETE require all PK columns to be covered by {@code @lookupKey} bindings. */
        boolean requiresPkCoverage() {
            return this == UPDATE || this == DELETE;
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
     * {@code [ID]}, {@code T}, or {@code [T]} (where {@code T} is a {@code @table} type).
     * Returns a non-null rejection reason on violation; {@code null} when the return type is
     * acceptable.
     */
    static String validateReturnType(ReturnTypeRef returnType, DmlKind kind) {
        return switch (returnType) {
            case ReturnTypeRef.ScalarReturnType s -> {
                if (!"ID".equals(s.returnTypeName())) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + s.returnTypeName() + "' is not yet supported; use ID or a @table type";
                }
                yield null;
            }
            case ReturnTypeRef.TableBoundReturnType tb -> {
                if (tb.wrapper() instanceof FieldWrapper.Connection) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + tb.returnTypeName() + "' (Connection) is not yet supported; use ID or a @table type";
                }
                yield null;
            }
            case ReturnTypeRef.ResultReturnType r -> {
                // R12: DML accepts a @record payload return when the payload class exposes a
                // canonical constructor with one row-slot parameter (typed as the DML's table
                // record) plus optional defaulted slots and an optional errors slot. The
                // shape check runs in FieldBuilder.resolveDmlPayloadAssembly during
                // construction; this validator only screens for the wrapper shape (single,
                // not list/connection).
                if (r.wrapper().isList()) {
                    yield "@mutation(typeName: " + kind + ") return type '"
                        + r.returnTypeName() + "' (list of @record) is not yet supported; "
                        + "use a single @record payload, an ID, or a @table type";
                }
                yield null;
            }
            case ReturnTypeRef.PolymorphicReturnType p ->
                "@mutation(typeName: " + kind + ") return type '"
                    + p.returnTypeName() + "' (interface/union) is not yet supported; use ID or a @table type";
        };
    }

    /**
     * Walks a DML {@code @mutation} field's arguments and resolves the single {@code @table}
     * input argument that drives the statement. Enforces Phase 1 mutation invariants on the
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
                return new Resolved.Rejected(
                    "@mutation fields only accept @table input arguments; found '" + argName
                        + "' of type '" + argTypeName + "'");
            }

            var bindingErrors = new java.util.ArrayList<String>();
            List<InputColumnBinding.MapBinding> bindings =
                enumMapping.buildLookupBindings(tit, arg, fieldDef, argName, bindingErrors);
            if (!bindingErrors.isEmpty()) {
                return new Resolved.Rejected(String.join("; ", bindingErrors));
            }
            Optional<ArgConditionRef> argCondition;
            switch (conditionResolver.resolveArg(arg)) {
                case ConditionResolver.ArgConditionResult.None n -> argCondition = Optional.empty();
                case ConditionResolver.ArgConditionResult.Ok ok -> argCondition = Optional.of(ok.ref());
                case ConditionResolver.ArgConditionResult.Rejected r ->
                    { return new Resolved.Rejected(r.message()); }
            }
            var tia = new ArgumentRef.InputTypeArg.TableInputArg(
                argName, argTypeName, nonNull, list, tit.table(), bindings, argCondition, tit.inputFields());

            if (foundTia != null) {
                multipleArgsError = "@mutation field has more than one @table input argument";
                break;
            }
            foundTia = tia;
        }
        if (multipleArgsError != null) {
            return new Resolved.Rejected(multipleArgsError);
        }
        if (foundTia == null) {
            return new Resolved.Rejected("no @table input argument found on @mutation field");
        }

        if (foundTia.list()) {
            return new Resolved.Rejected(
                "listed @table input arguments on @mutation fields are not yet supported"
                    + " — declare the argument as a single non-list @table input wrapper"
                    + " (e.g. 'argName: SomeInput' rather than 'argName: [SomeInput!]!') and"
                    + " issue one mutation per row, or wait for bulk-mutation support to land");
        }
        if (foundTia.argCondition().isPresent()) {
            return new Resolved.Rejected(
                "@condition on a @mutation field argument is not supported");
        }
        for (var f : foundTia.fields()) {
            // ColumnField with Direct extraction is the canonical mutation-input shape.
            // ColumnField with NodeIdDecodeKeys is the post-R50 successor of the retired
            // NodeIdField at the synthesis shim (and, post-e3, the @nodeId-typed paths) -- still
            // not supported in @mutation inputs.
            if (f instanceof InputField.ColumnField cf
                    && !(cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys)) {
                continue;
            }
            String reason = switch (f) {
                case InputField.NestingField nf -> "nested input types in @mutation fields are not yet supported";
                case InputField.ColumnReferenceField crf -> "ColumnReferenceField in @mutation inputs is not yet supported";
                case InputField.CompositeColumnField ccf -> "CompositeColumnField in @mutation inputs is not yet supported";
                case InputField.CompositeColumnReferenceField ccrf -> "CompositeColumnReferenceField in @mutation inputs is not yet supported";
                case InputField.ColumnField cf -> "NodeId-decoded ColumnField (post-R50 successor of the retired NodeIdField) in @mutation inputs is not yet supported";
            };
            return new Resolved.Rejected(
                "@mutation input '" + foundTia.typeName() + "' field '" + f.name() + "': " + reason);
        }

        if (kind.requiresLookupKey() && foundTia.fieldBindings().isEmpty()) {
            return new Resolved.Rejected(
                "@mutation(typeName: " + kind + ") requires at least one @lookupKey field in the input type");
        }

        if (kind == DmlKind.UPDATE) {
            var lookupKeyNames = foundTia.fieldBindings().stream()
                .map(InputColumnBinding.MapBinding::fieldName)
                .collect(Collectors.toSet());
            boolean hasNonLookupColumn = foundTia.fields().stream()
                .filter(f -> f instanceof InputField.ColumnField)
                .anyMatch(f -> !lookupKeyNames.contains(f.name()));
            if (!hasNonLookupColumn) {
                return new Resolved.Rejected(
                    "@mutation(typeName: UPDATE) has no non-@lookupKey fields to set");
            }
        }

        if (kind.requiresPkCoverage()) {
            var pkColumns = ctx.catalog.findPkColumns(foundTia.inputTable().tableName());
            if (!pkColumns.isEmpty()) {
                var boundSqlNames = foundTia.fieldBindings().stream()
                    .map(b -> b.targetColumn().sqlName())
                    .collect(Collectors.toSet());
                var missing = pkColumns.stream()
                    .map(JooqCatalog.ColumnEntry::sqlName)
                    .filter(c -> !boundSqlNames.contains(c))
                    .toList();
                if (!missing.isEmpty()) {
                    return new Resolved.Rejected(
                        "@mutation(typeName: " + kind
                            + ") @lookupKey fields do not cover all PK column(s); missing: "
                            + String.join(", ", missing));
                }
            }
        }

        return new Resolved.Ok(foundTia);
    }
}
