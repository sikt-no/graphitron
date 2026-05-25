package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
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
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;

/**
 * Resolves the enum-mapping axis: GraphQL enum values to Java/DB representations, plus the
 * {@link CallSiteExtraction} derivation that depends on it. Sibling to {@link OrderByResolver},
 * {@link LookupMappingResolver}, {@link PaginationResolver}, {@link ConditionResolver},
 * {@link InputFieldResolver}, and {@link MutationInputResolver}.
 *
 * <p>Four methods cluster here, all touching the same axis: how a GraphQL value (especially an
 * enum value) is converted before reaching jOOQ.
 *
 * <ul>
 *   <li>{@link #buildTextEnumMapping} reads a classified {@link GraphitronType.EnumType} and
 *       produces a value-name to runtime-string mapping; the runtime string is the pre-resolved
 *       {@link no.sikt.graphitron.rewrite.model.EnumValueSpec#runtimeValue} lifted at classify
 *       time from {@code @field(name:)} (or the SDL name when the directive is absent).
 *       Returns {@code null} when the GraphQL type is not a classified enum.</li>
 *   <li>{@link #validateEnumFilter} matches a GraphQL enum against a jOOQ-generated Java enum
 *       column class. Returns a sealed {@link EnumValidation} the caller switches on:
 *       {@link EnumValidation.NotEnum} when the column is not a jOOQ enum,
 *       {@link EnumValidation.Valid} carrying the matched Java enum's fully qualified class name,
 *       or {@link EnumValidation.Mismatch} carrying a single composed rejection message.</li>
 *   <li>{@link #deriveExtraction} picks the {@link CallSiteExtraction} strategy for a scalar
 *       column-bound value, given the GraphQL type, the target column, and the validated enum
 *       class name (when applicable). Total projection: never fails.</li>
 *   <li>{@link #buildLookupBindings} walks a {@link GraphitronType.TableInputType}'s fields
 *       and emits one {@link InputColumnBinding.MapBinding} per {@code @lookupKey}-bearing
 *       scalar column field, deriving each binding's extraction via {@link #deriveExtraction}.</li>
 * </ul>
 *
 * <p>R229 retired {@code enrichArgExtractions} along with the
 * {@code CallSiteExtraction.TextMapLookup} permit it produced: graphql-java's
 * {@code GraphQLEnumValueDefinition.value(...)} now carries the {@code @field(name:)} runtime
 * form, so the wire-form → runtime-form translation happens at graphql-java's boundary and the
 * Java-side conversion step is no longer needed.
 */
final class EnumMappingResolver {

    /**
     * Outcome of {@link #validateEnumFilter}. Three terminal arms; the caller exhausts them with
     * a switch.
     *
     * <ul>
     *   <li>{@link NotEnum} — the jOOQ column class is not a Java enum. The GraphQL type may
     *       still be an enum (text-mapped); the caller treats this as "no enum coercion bound".</li>
     *   <li>{@link Valid} — the column is a jOOQ enum and every GraphQL enum value maps to a
     *       Java enum constant. Carries the Java enum's fully qualified class name.</li>
     *   <li>{@link Mismatch} — the column is a jOOQ enum but the GraphQL side either isn't an
     *       enum or has values that don't line up with the Java constants. Carries the composed
     *       rejection message; the caller appends to its errors list.</li>
     * </ul>
     */
    sealed interface EnumValidation {
        record NotEnum() implements EnumValidation {}
        record Valid(String fqcn) implements EnumValidation {}
        record Mismatch(Rejection rejection) implements EnumValidation {
            public String message() { return rejection.message(); }
        }
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
     * {@link no.sikt.graphitron.rewrite.model.EnumValueSpec#runtimeValue} on the model — lifted
     * once at classify time from {@code @field(name:)}, falling back to the value's own name
     * when the directive is absent.
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
        var modelType = ctx.types.get(graphqlTypeName);
        if (!(modelType instanceof GraphitronType.EnumType enumType)) {
            return new EnumValidation.Mismatch(Rejection.structural("column '" + column.sqlName() + "' is a jOOQ enum ("
                + colClass.getSimpleName() + ") but GraphQL type '" + graphqlTypeName + "' is not an enum"));
        }
        var javaConstants = Arrays.stream(colClass.getEnumConstants())
            .map(c -> ((Enum<?>) c).name())
            .collect(Collectors.toSet());
        var mismatches = new ArrayList<String>();
        for (var spec : enumType.values()) {
            String target = spec.runtimeValue();
            if (!javaConstants.contains(target)) {
                mismatches.add("'" + spec.sdlName() + "'" + (target.equals(spec.sdlName()) ? "" : " (mapped to '" + target + "')")
                    + candidateHint(target, new ArrayList<>(javaConstants)));
            }
        }
        if (!mismatches.isEmpty()) {
            return new EnumValidation.Mismatch(Rejection.structural("GraphQL enum '" + graphqlTypeName
                + "' has values that don't match jOOQ enum " + colClass.getSimpleName() + ": "
                + String.join("; ", mismatches)));
        }
        return new EnumValidation.Valid(colClass.getName());
    }

    /**
     * Derives the {@link CallSiteExtraction} strategy for a scalar column-bound value given its
     * GraphQL type and target column. {@code enumClassName} is the FQCN from
     * {@link EnumValidation.Valid}; pass {@code null} when the column is not a jOOQ enum (the
     * {@link EnumValidation.NotEnum} arm) so the {@code JooqConvert} / {@code Direct} fallbacks
     * can take over.
     *
     * <p>R229 retired the text-mapped-enum branch. Graphql-java's
     * {@code GraphQLEnumValueDefinition.value(...)} now carries the {@code @field(name:)}
     * runtime form (see {@link no.sikt.graphitron.rewrite.model.EnumValueSpec}), so a text-mapped
     * enum input arrives at the resolver already in its DB-string form and routes through
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
     * Walks a {@link GraphitronType.TableInputType} argument's fields and builds one
     * {@link InputColumnBindingGroup} per admissible non-excluded input field. R144 retired
     * {@code @lookupKey} on {@code INPUT_FIELD_DEFINITION}; the directive no longer gates this
     * walk. The caller decides which fields to admit:
     *
     * <ul>
     *   <li>Mutation-side (DELETE / UPDATE): every admissible input field is a WHERE-binding,
     *       modulo {@code excludeFieldNames} which carries the {@code @value}-marked names on
     *       UPDATE. INSERT passes an empty caller-side and skips the call (no WHERE clause).</li>
     *   <li>Query-side ({@code @lookupKey} on {@code ARGUMENT_DEFINITION}, with a {@code @table}
     *       input arg): every admissible input field of the input type is a lookup-key binding;
     *       {@code excludeFieldNames} is empty. The Query-side derivation reads the binding set
     *       as the VALUES-join column list.</li>
     * </ul>
     *
     * Each admitted carrier produces one group:
     *
     * <ul>
     *   <li>{@link InputField.ColumnField} (whether {@link CallSiteExtraction.Direct} or
     *       arity-1 {@link CallSiteExtraction.NodeIdDecodeKeys}) — one
     *       {@link InputColumnBindingGroup.MapGroup} carrying one
     *       {@link InputColumnBinding.MapBinding}. The binding's extraction honors the carrier's
     *       {@code cf.extraction()} when non-{@code Direct}; otherwise it is re-derived via
     *       {@link #deriveExtraction} from the column's raw metadata (enum / map / JooqConvert
     *       fallback).</li>
     *   <li>{@link InputField.CompositeColumnField} (arity &ge; 2 NodeId-decoded, own table) —
     *       one {@link InputColumnBindingGroup.DecodedRecordGroup} carrying the per-NodeType
     *       decode helper once on {@code extraction} and N
     *       {@link InputColumnBinding.RecordBinding} slots indexed {@code 0..N-1} into the
     *       decoded {@code Record<N>}.</li>
     * </ul>
     *
     * <p>List-typed admissible carriers are rejected: list cardinality must live on the outer
     * argument, not on an individual input-type field. Reference, nesting, and unresolved
     * carriers are silently skipped here; the caller surfaces them through its own structural
     * rejection path (mutation: {@code MutationInputResolver.resolveInput}'s per-field walk;
     * query: not currently a binding shape).
     */
    List<InputColumnBindingGroup> buildLookupBindings(GraphitronType.TableInputType tit,
            GraphQLArgument arg, GraphQLFieldDefinition fieldDef, String argName,
            List<String> errors, Set<String> excludeFieldNames) {
        var sdlType = ctx.schema.getType(tit.name());
        if (!(sdlType instanceof GraphQLInputObjectType iot)) {
            return List.of();
        }
        var byName = tit.inputFields().stream()
            .collect(Collectors.toMap(InputField::name, f -> f));
        var groups = new ArrayList<InputColumnBindingGroup>();
        for (var sdlField : iot.getFieldDefinitions()) {
            if (excludeFieldNames.contains(sdlField.getName())) continue;
            var resolved = byName.get(sdlField.getName());
            switch (resolved) {
                case InputField.ColumnField cf -> {
                    if (cf.list()) {
                        errors.add("input type '" + tit.name() + "' field '" + sdlField.getName()
                            + "': list-typed input field is not supported in this binding position; "
                            + "move list cardinality to the outer argument");
                        continue;
                    }
                    String enumClassName;
                    switch (validateEnumFilter(cf.typeName(), cf.column())) {
                        case EnumValidation.NotEnum n -> enumClassName = null;
                        case EnumValidation.Valid v -> enumClassName = v.fqcn();
                        case EnumValidation.Mismatch m -> {
                            errors.add(m.message());
                            continue;
                        }
                    }
                    CallSiteExtraction extraction;
                    if (cf.extraction() instanceof CallSiteExtraction.Direct) {
                        extraction = deriveExtraction(cf.typeName(), cf.column(), enumClassName);
                    } else {
                        extraction = cf.extraction();
                    }
                    groups.add(new InputColumnBindingGroup.MapGroup(List.of(
                        new InputColumnBinding.MapBinding(sdlField.getName(), cf.column(), extraction))));
                }
                case InputField.CompositeColumnField ccf -> {
                    if (ccf.list()) {
                        errors.add("input type '" + tit.name() + "' field '" + sdlField.getName()
                            + "': list-typed input field is not supported in this binding position; "
                            + "move list cardinality to the outer argument");
                        continue;
                    }
                    var recordBindings = new ArrayList<InputColumnBinding.RecordBinding>();
                    for (int i = 0; i < ccf.columns().size(); i++) {
                        recordBindings.add(new InputColumnBinding.RecordBinding(i, ccf.columns().get(i)));
                    }
                    groups.add(new InputColumnBindingGroup.DecodedRecordGroup(
                        sdlField.getName(), ccf.extraction(), recordBindings));
                }
                case InputField.ColumnReferenceField crf -> {
                    // R189: FK-target @nodeId reference carrier, arity-1. The target column is
                    // the lifted source column on the input's own table (i.e. the FK column),
                    // not the joined-table column carried by crf.column(). The extraction is
                    // narrowed to NodeIdDecodeKeys at the DirectFk arm; the resulting binding
                    // has the same MapGroup shape an arity-1 NodeId-decoded ColumnField produces.
                    if (crf.list()) {
                        errors.add("input type '" + tit.name() + "' field '" + sdlField.getName()
                            + "': list-typed input field is not supported in this binding position; "
                            + "move list cardinality to the outer argument");
                        continue;
                    }
                    groups.add(new InputColumnBindingGroup.MapGroup(List.of(
                        new InputColumnBinding.MapBinding(sdlField.getName(),
                            crf.liftedSourceColumns().get(0), crf.extraction()))));
                }
                case InputField.CompositeColumnReferenceField ccrf -> {
                    // R189: FK-target @nodeId reference carrier, arity >= 2. Target columns are
                    // the lifted source columns on the input's own table (permuted into
                    // NodeType key order by the DirectFk classifier); pairs slot-for-slot with
                    // the decoded record's value<i+1>() accessors. Same DecodedRecordGroup
                    // shape as the same-table CompositeColumnField arm.
                    if (ccrf.list()) {
                        errors.add("input type '" + tit.name() + "' field '" + sdlField.getName()
                            + "': list-typed input field is not supported in this binding position; "
                            + "move list cardinality to the outer argument");
                        continue;
                    }
                    var recordBindings = new ArrayList<InputColumnBinding.RecordBinding>();
                    for (int i = 0; i < ccrf.liftedSourceColumns().size(); i++) {
                        recordBindings.add(new InputColumnBinding.RecordBinding(i,
                            ccrf.liftedSourceColumns().get(i)));
                    }
                    groups.add(new InputColumnBindingGroup.DecodedRecordGroup(
                        sdlField.getName(), ccrf.extraction(), recordBindings));
                }
                case InputField.NestingField ignored -> {
                    // Nesting carriers are not admissible binding shapes here; the caller's
                    // structural walk surfaces them as rejections.
                }
                case InputField.UnboundField ignored -> {
                    // R215: unbound carrier has no column binding; not enum-mappable.
                }
                case null -> {
                    // SDL field declared but the input type didn't classify it (Unresolved
                    // upstream); structural walk surfaces it as a rejection.
                }
            }
        }
        return List.copyOf(groups);
    }
}
