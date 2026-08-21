package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.Rejection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
     *   <li>{@link GraphqlNotEnum}: the GraphQL type name does not resolve to a classified enum,
     *       i.e. it genuinely is not an enum (the verdict is recomputed registry-free through
     *       {@link BuildContext#lookAheadVerdict}, so a mid-walk read cannot miss). The two
     *       callers treat this differently: the column path (where the Java side is definitely a
     *       jOOQ enum) surfaces a mismatch; the {@code @service} enum path falls back to an
     *       unchecked {@code EnumValueOf}.</li>
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

        /**
         * One value an authored SDL construct asks a Java enum to carry: {@code sdlValueName} as
         * authored, {@code runtimeValue} in the form actually compared against the enum's
         * constants. The two differ when a mapping intervenes (a GraphQL enum value's
         * {@code @field(name:)}) and coincide when the authored spelling <em>is</em> the compared
         * form (a {@code @discriminator(value:)} literal).
         */
        record Target(String sdlValueName, String runtimeValue) {}
    }

    /**
     * Which spelling of a Java enum's constants a comparison targets, the one axis the two callers
     * of {@link #constantMismatches} differ on.
     */
    enum ConstantSpelling {
        /**
         * {@code Enum.name()}, the form {@code EnumClass.valueOf(...)} accepts. What a GraphQL enum
         * value's runtime form is compared against, because the generated coercion calls
         * {@code valueOf}.
         */
        JAVA_NAME,
        /**
         * The database literal, {@code org.jooq.EnumType.getLiteral()} on a jOOQ-generated enum
         * (read reflectively, so the codegen loader's jOOQ need not be this generator's). The two
         * spellings diverge whenever the literal is not a Java identifier: {@code mpaa_rating}'s
         * {@code 'PG-13'} literal is the constant {@code PG_13}. What a value authored as the
         * database's own content is compared against. Falls back to {@link #JAVA_NAME} for a plain
         * Java enum, which has no literal to speak of.
         */
        DATABASE_LITERAL
    }

    /**
     * The constant-comparison core: which of {@code targets} name no constant of
     * {@code javaEnumClass} under {@code spelling}, each carrying the full constant set as its
     * candidate space. Single home for the "does this authored value exist on that Java enum"
     * question, so the GraphQL-enum caller ({@link #checkEnumConstants}) and the
     * {@code @discriminator(value:)} caller ({@link TypeBuilder#discriminatorLiteralRejection})
     * walk {@code getEnumConstants()} through one implementation rather than one each.
     */
    static List<EnumConstantParity.ValueMismatch> constantMismatches(
            Class<?> javaEnumClass, ConstantSpelling spelling,
            List<EnumConstantParity.Target> targets) {
        var names = constantNames(javaEnumClass, spelling);
        var candidates = new ArrayList<>(names);
        var mismatches = new ArrayList<EnumConstantParity.ValueMismatch>();
        for (var target : targets) {
            if (!names.contains(target.runtimeValue())) {
                mismatches.add(new EnumConstantParity.ValueMismatch(
                    target.sdlValueName(), target.runtimeValue(), candidates));
            }
        }
        return List.copyOf(mismatches);
    }

    /**
     * The constant names of a Java enum class in declaration order, under the requested
     * {@link ConstantSpelling}. The literal read is reflective ({@code getLiteral()} by name)
     * rather than a cast to {@code org.jooq.EnumType}: the class comes from the codegen loader,
     * which need not share this generator's jOOQ.
     */
    static java.util.LinkedHashSet<String> constantNames(Class<?> javaEnumClass, ConstantSpelling spelling) {
        var literal = spelling == ConstantSpelling.DATABASE_LITERAL
            ? literalAccessorOrNull(javaEnumClass) : null;
        return Arrays.stream(javaEnumClass.getEnumConstants())
            .map(c -> literal == null ? ((Enum<?>) c).name() : invokeLiteral(literal, c))
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** {@code getLiteral()} when the class has one, {@code null} for a plain Java enum. */
    private static java.lang.reflect.Method literalAccessorOrNull(Class<?> javaEnumClass) {
        try {
            return javaEnumClass.getMethod("getLiteral");
        } catch (NoSuchMethodException plainJavaEnum) {
            return null;
        }
    }

    private static String invokeLiteral(java.lang.reflect.Method literal, Object constant) {
        try {
            return (String) literal.invoke(constant);
        } catch (ReflectiveOperationException unreachable) {
            // getLiteral() on a jOOQ-generated enum is a public no-arg accessor returning a
            // constant; a failure here is a broken generated artifact, not an authoring error.
            throw new IllegalStateException(
                "reading the database literal of " + constant + " failed", unreachable);
        }
    }

    /**
     * Compares every value of the GraphQL enum named {@code graphqlTypeName} (resolved through
     * {@link BuildContext#lookAheadVerdict}: this runs during field classification, when the enum
     * may be a not-yet-visited child of the walk) against the constant names of
     * {@code javaEnumClass}. The comparison is on the pre-resolved
     * {@link no.sikt.graphitron.rewrite.model.EnumValueSpec#runtimeValue},
     * the same form {@code EnumClass.valueOf(...)} receives at runtime, so the spelling is
     * {@link ConstantSpelling#JAVA_NAME}.
     */
    EnumConstantParity checkEnumConstants(String graphqlTypeName, Class<?> javaEnumClass) {
        var modelType = ctx.lookAheadVerdict(graphqlTypeName);
        if (!(modelType instanceof GraphitronType.EnumType enumType)) {
            return new EnumConstantParity.GraphqlNotEnum();
        }
        var mismatches = constantMismatches(javaEnumClass, ConstantSpelling.JAVA_NAME,
            enumType.values().stream()
                .map(spec -> new EnumConstantParity.Target(spec.sdlName(), spec.runtimeValue()))
                .toList());
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
     * Validates that a GraphQL enum type's values match the Java enum constants of the column
     * type. Returns a sealed {@link EnumValidation} the caller switches on; the {@code Mismatch}
     * arm carries a single composed message ready for the caller's accumulating errors list.
     */
    EnumValidation validateEnumFilter(String graphqlTypeName, ColumnRef column) {
        Class<?> colClass;
        try {
            // nameability: exempt (jOOQ catalog column class)
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
                    // FK-target @nodeId reference carrier. The bound columns are the binding's
                    // own-table tuple (the FK columns, permuted into NodeType key order by the
                    // DirectFk classifier), not the target-table columns carried by crf.columns().
                    // Arity-gated on isComposite(): the single-column shape produces the same
                    // MapGroup an arity-1 NodeId-decoded ColumnBackedField does; the composite shape
                    // pairs slot-for-slot with the decoded record's value<i+1>() accessors, same
                    // DecodedRecordGroup as the same-table composite arm.
                    if (!(crf.binding() instanceof FilterBinding.Local(var ownTableColumns))) {
                        // Unreachable: FieldBuilder.classifyPlainLookupKeyArg, the one caller, gates
                        // a Remote-bound carrier off this rail. Throwing keeps that gate the single
                        // decision point; this method accumulates prose into `errors` and has no
                        // typed channel to report the deferral through.
                        throw new IllegalStateException("lookup binding walk reached a remote-bound"
                            + " reference carrier '" + crf.name() + "'; the lookup-rail gate should"
                            + " have rejected it");
                    }
                    if (crf.list()) {
                        errors.add("input type '" + crf.parentTypeName() + "' field '" + crf.name()
                            + "': list-typed input field is not supported in this binding position; "
                            + "move list cardinality to the outer argument");
                        continue;
                    }
                    if (crf.isComposite()) {
                        var recordBindings = new ArrayList<InputColumnBinding.RecordBinding>();
                        for (int i = 0; i < ownTableColumns.size(); i++) {
                            recordBindings.add(new InputColumnBinding.RecordBinding(i,
                                ownTableColumns.get(i)));
                        }
                        groups.add(new InputColumnBindingGroup.DecodedRecordGroup(
                            crf.name(), (CallSiteExtraction.NodeIdDecodeKeys) crf.extraction(), recordBindings));
                        continue;
                    }
                    groups.add(new InputColumnBindingGroup.MapGroup(List.of(
                        new InputColumnBinding.MapBinding(crf.name(),
                            ownTableColumns.get(0), crf.extraction()))));
                }
                case InputField.NestingField ignored -> {
                    // Nesting carriers are not admissible binding shapes here; the caller's
                    // structural walk surfaces them as rejections.
                }
                case InputField.UnboundField ignored -> {
                    // Unbound carrier has no column binding; not enum-mappable.
                }
                case InputField.ConditionOwnedField ignored -> {
                    // The explicit condition method owns the predicate; no column to enum-map.
                }
            }
        }
        return List.copyOf(groups);
    }
}
