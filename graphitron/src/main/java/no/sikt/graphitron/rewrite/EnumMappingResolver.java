package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.Rejection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;

/**
 * Resolves the enum-mapping axis: GraphQL enum values to Java/DB representations, plus the
 * {@link CallSiteExtraction} derivation that depends on it. Sibling to {@link OrderByResolver},
 * {@link LookupMappingResolver}, {@link PaginationResolver}, {@link ConditionResolver},
 * {@link InputFieldResolver}, and {@link MutationInputResolver}.
 *
 * <p>Every method here touches the same axis: how a GraphQL value (especially an enum value) is
 * converted before reaching jOOQ.
 */
final class EnumMappingResolver {

    /**
     * Outcome of {@link #validateEnumFilter}, exhausted by switch.
     *
     * <ul>
     *   <li>{@link NotEnum}: the jOOQ column class is not a Java enum. The GraphQL type may
     *       still be an enum (text-mapped); the caller treats this as "no enum coercion bound".</li>
     *   <li>{@link Valid}: every GraphQL enum value maps to a Java enum constant; carries the
     *       Java enum's fully qualified class name.</li>
     *   <li>{@link Mismatch}: the column is a jOOQ enum but the GraphQL side does not line up;
     *       carries a composed rejection message for the caller's errors list.</li>
     * </ul>
     */
    sealed interface EnumValidation {
        record NotEnum() implements EnumValidation {}
        record Valid(String fqcn) implements EnumValidation {}
        record Mismatch(Rejection rejection) implements EnumValidation {
            public String message() { return rejection.message(); }
        }
    }

    /**
     * Column-agnostic result of {@link #checkEnumConstants}: does every value of a GraphQL enum
     * type map to a constant on a given Java enum class? Single parity home:
     * {@link #validateEnumFilter} (the column path) delegates its value-name comparison here, and
     * the {@code @service} enum producers ({@link InputBeanResolver} / {@link ServiceCatalog})
     * call it directly, so the SDL-value vs Java-constant diff is not re-implemented per producer.
     *
     * <ul>
     *   <li>{@link Valid}: every GraphQL enum value's runtime name is a Java constant.</li>
     *   <li>{@link GraphqlNotEnum}: the GraphQL type name does not resolve to a classified enum
     *       (mid-build, or a genuine "this isn't an enum" mistake). The two callers treat this
     *       differently: the column path (where the Java side is definitely a jOOQ enum) surfaces a
     *       mismatch; the {@code @service} enum path falls back to an unchecked {@code EnumValueOf}
     *       so it does not over-reject when the type registry is empty.</li>
     *   <li>{@link Divergence}: the GraphQL side is a classified enum but one or more values have
     *       no matching Java constant; carries the per-value diff so each caller shapes its own
     *       result (a column-flavoured {@link Mismatch} message, or a typed
     *       {@link no.sikt.graphitron.rewrite.model.WireCoercionError.EnumConstantDivergence}).</li>
     * </ul>
     */
    sealed interface EnumConstantParity {
        record Valid() implements EnumConstantParity {}
        record GraphqlNotEnum() implements EnumConstantParity {}
        record Divergence(List<ValueMismatch> mismatches) implements EnumConstantParity {
            public Divergence { mismatches = List.copyOf(mismatches); }
        }
        /**
         * One divergent GraphQL enum value: {@code sdlValueName} is the SDL value name,
         * {@code runtimeValue} is its {@code @field(name:)}-mapped runtime form (equal to
         * {@code sdlValueName} when no mapping applies), {@code candidates} is the full set of Java
         * constant names (the candidate space for a "did you mean" hint).
         */
        record ValueMismatch(String sdlValueName, String runtimeValue, List<String> candidates) {
            public ValueMismatch { candidates = List.copyOf(candidates); }
        }
    }

    /**
     * Compares every value of the GraphQL enum named {@code graphqlTypeName} (resolved through
     * {@code ctx.types}) against the constant names of {@code javaEnumClass}. The comparison is
     * on the pre-resolved {@link no.sikt.graphitron.rewrite.model.EnumValueSpec#runtimeValue},
     * the same form {@code EnumClass.valueOf(...)} receives at runtime.
     */
    EnumConstantParity checkEnumConstants(String graphqlTypeName, Class<?> javaEnumClass) {
        var modelType = ctx.types == null ? null : ctx.types.get(graphqlTypeName);
        if (!(modelType instanceof GraphitronType.EnumType enumType)) {
            return new EnumConstantParity.GraphqlNotEnum();
        }
        var javaConstants = Arrays.stream(javaEnumClass.getEnumConstants())
            .map(c -> ((Enum<?>) c).name())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        var candidates = new ArrayList<>(javaConstants);
        var mismatches = new ArrayList<EnumConstantParity.ValueMismatch>();
        for (var spec : enumType.values()) {
            String target = spec.runtimeValue();
            if (!javaConstants.contains(target)) {
                mismatches.add(new EnumConstantParity.ValueMismatch(spec.sdlName(), target, candidates));
            }
        }
        return mismatches.isEmpty()
            ? new EnumConstantParity.Valid()
            : new EnumConstantParity.Divergence(mismatches);
    }

    private static final EnumValidation NOT_ENUM = new EnumValidation.NotEnum();

    private final BuildContext ctx;

    EnumMappingResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Builds a mapping from GraphQL enum value names to database string values when
     * {@code graphqlTypeName} resolves to a classified {@link GraphitronType.EnumType}; returns
     * {@code null} otherwise. Each value's DB string is the pre-resolved
     * {@link no.sikt.graphitron.rewrite.model.EnumValueSpec#runtimeValue}, lifted at classify
     * time from {@code @field(name:)} (the value's own name when the directive is absent).
     */
    Map<String, String> buildTextEnumMapping(String graphqlTypeName) {
        var modelType = ctx.types.get(graphqlTypeName);
        if (!(modelType instanceof GraphitronType.EnumType enumType)) {
            return null;
        }
        var mapping = new LinkedHashMap<String, String>();
        for (var spec : enumType.values()) {
            mapping.put(spec.sdlName(), spec.runtimeValue());
        }
        return mapping;
    }

    /**
     * Validates that a GraphQL enum type's values match the Java enum constants of the column
     * type. Returns a sealed {@link EnumValidation} the caller switches on; the {@code Mismatch}
     * arm carries a single composed message ready for the caller's accumulating errors list.
     */
    EnumValidation validateEnumFilter(String graphqlTypeName, ColumnRef column) {
        Class<?> colClass;
        try {
            colClass = Class.forName(column.columnClass(), false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return NOT_ENUM;
        }
        if (!colClass.isEnum()) {
            return NOT_ENUM;
        }
        return switch (checkEnumConstants(graphqlTypeName, colClass)) {
            case EnumConstantParity.GraphqlNotEnum ignored -> new EnumValidation.Mismatch(
                Rejection.structural("column '" + column.sqlName() + "' is a jOOQ enum ("
                    + colClass.getSimpleName() + ") but GraphQL type '" + graphqlTypeName + "' is not an enum"));
            case EnumConstantParity.Valid ignored -> new EnumValidation.Valid(colClass.getName());
            case EnumConstantParity.Divergence d -> {
                var rendered = d.mismatches().stream()
                    .map(m -> "'" + m.sdlValueName() + "'"
                        + (m.runtimeValue().equals(m.sdlValueName()) ? "" : " (mapped to '" + m.runtimeValue() + "')")
                        + candidateHint(m.runtimeValue(), m.candidates()))
                    .collect(Collectors.joining("; "));
                yield new EnumValidation.Mismatch(Rejection.structural("GraphQL enum '" + graphqlTypeName
                    + "' has values that don't match jOOQ enum " + colClass.getSimpleName() + ": " + rendered));
            }
        };
    }

    /**
     * Derives the {@link CallSiteExtraction} strategy for a scalar column-bound value given its
     * GraphQL type and target column. {@code enumClassName} is the FQCN from
     * {@link EnumValidation.Valid}; pass {@code null} when the column is not a jOOQ enum (the
     * {@link EnumValidation.NotEnum} arm) so the {@code JooqConvert} / {@code Direct} fallbacks
     * can take over.
     *
     * <p>A text-mapped enum needs no branch here: graphql-java's
     * {@code GraphQLEnumValueDefinition.value(...)} carries the {@code @field(name:)} runtime
     * form (see {@link no.sikt.graphitron.rewrite.model.EnumValueSpec}), so a text-mapped enum
     * input arrives at the resolver already in its DB-string form and routes through
     * {@link CallSiteExtraction.Direct}.
     */
    CallSiteExtraction deriveExtraction(String typeName, ColumnRef columnRef, String enumClassName) {
        if (enumClassName != null) {
            return new CallSiteExtraction.EnumValueOf(enumClassName);
        }
        if ("ID".equals(typeName)) {
            return new CallSiteExtraction.JooqConvert(columnRef.javaName());
        }
        return new CallSiteExtraction.Direct();
    }

    /**
     * Builds one {@link InputColumnBindingGroup} per admissible field in an already-resolved
     * input-field list, for a query-side {@code @lookupKey} argument; the directive gates this
     * walk from the {@code ARGUMENT_DEFINITION} at the call site. UPDATE and DELETE build their
     * WHERE columns directly on their walker carriers and INSERT builds no binding set, so the
     * sole caller is query-side, reached by two routes that produce the same fact (input fields
     * resolved against a table, plus a lookup binding set): the deprecated {@code @table}-input
     * bridge, and the consumer-derived plain-input path, which re-derives the fields against the
     * consuming field's return table via {@code TypeBuilder.resolveInputFields}.
     *
     * <p>The caller reaches this method only with a fully-resolved field list ({@code
     * resolveInputFields} rejects the whole input on any unresolvable field), so a
     * declared-but-unresolved field cannot occur here. Reference, nesting, and unbound carriers
     * are silently skipped; the caller surfaces them through its own structural rejection path.
     */
    List<InputColumnBindingGroup> buildLookupBindings(List<InputField> inputFields, List<String> errors) {
        var groups = new ArrayList<InputColumnBindingGroup>();
        for (var resolved : inputFields) {
            switch (resolved) {
                case InputField.ColumnBackedField cf -> {
                    if (cf.list()) {
                        errors.add("input type '" + cf.parentTypeName() + "' field '" + cf.name()
                            + "': list-typed input field is not supported in this binding position; "
                            + "move list cardinality to the outer argument");
                        continue;
                    }
                    // The binding-group fork is arity-gated on the carrier's own isComposite():
                    // a composite carrier is a decoded node-key tuple (NodeIdDecodeKeys by the
                    // constructor invariant) whose slots pair positionally with the decoded
                    // record's value<i+1>() accessors; enum filters exist only on the
                    // single-column shape.
                    if (cf.isComposite()) {
                        var recordBindings = new ArrayList<InputColumnBinding.RecordBinding>();
                        for (int i = 0; i < cf.columns().size(); i++) {
                            recordBindings.add(new InputColumnBinding.RecordBinding(i, cf.columns().get(i)));
                        }
                        groups.add(new InputColumnBindingGroup.DecodedRecordGroup(
                            cf.name(), (CallSiteExtraction.NodeIdDecodeKeys) cf.extraction(), recordBindings));
                        continue;
                    }
                    var cfColumn = cf.columns().get(0);
                    String enumClassName;
                    switch (validateEnumFilter(cf.typeName(), cfColumn)) {
                        case EnumValidation.NotEnum n -> enumClassName = null;
                        case EnumValidation.Valid v -> enumClassName = v.fqcn();
                        case EnumValidation.Mismatch m -> {
                            errors.add(m.message());
                            continue;
                        }
                    }
                    CallSiteExtraction extraction;
                    if (cf.extraction() instanceof CallSiteExtraction.Direct) {
                        extraction = deriveExtraction(cf.typeName(), cfColumn, enumClassName);
                    } else {
                        extraction = cf.extraction();
                    }
                    groups.add(new InputColumnBindingGroup.MapGroup(List.of(
                        new InputColumnBinding.MapBinding(cf.name(), cfColumn, extraction))));
                }
                case InputField.ColumnBackedReferenceField crf -> {
                    // FK-target @nodeId reference carrier. The target columns are the lifted
                    // source columns on the input's own table (i.e. the FK columns; permuted
                    // into NodeType key order by the DirectFk classifier), not the joined-table
                    // columns carried by crf.columns(). Arity-gated on isComposite(): the
                    // single-column shape produces the same MapGroup an arity-1 NodeId-decoded
                    // ColumnBackedField does; the composite shape pairs slot-for-slot with the
                    // decoded record's value<i+1>() accessors, same DecodedRecordGroup as the
                    // same-table composite arm.
                    if (crf.list()) {
                        errors.add("input type '" + crf.parentTypeName() + "' field '" + crf.name()
                            + "': list-typed input field is not supported in this binding position; "
                            + "move list cardinality to the outer argument");
                        continue;
                    }
                    if (crf.isComposite()) {
                        var recordBindings = new ArrayList<InputColumnBinding.RecordBinding>();
                        for (int i = 0; i < crf.liftedSourceColumns().size(); i++) {
                            recordBindings.add(new InputColumnBinding.RecordBinding(i,
                                crf.liftedSourceColumns().get(i)));
                        }
                        groups.add(new InputColumnBindingGroup.DecodedRecordGroup(
                            crf.name(), (CallSiteExtraction.NodeIdDecodeKeys) crf.extraction(), recordBindings));
                        continue;
                    }
                    groups.add(new InputColumnBindingGroup.MapGroup(List.of(
                        new InputColumnBinding.MapBinding(crf.name(),
                            crf.liftedSourceColumns().get(0), crf.extraction()))));
                }
                case InputField.NestingField ignored -> {
                    // Nesting carriers are not admissible binding shapes here; the caller's
                    // structural walk surfaces them as rejections.
                }
                case InputField.UnboundField ignored -> {
                    // Unbound carrier has no column binding; not enum-mappable.
                }
            }
        }
        return List.copyOf(groups);
    }
}
