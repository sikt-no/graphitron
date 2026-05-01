package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_LOOKUP_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;

/**
 * Resolves the enum-mapping axis: GraphQL enum values to Java/DB representations, plus the
 * {@link CallSiteExtraction} derivation and post-hoc method-extraction enrichment that depend
 * on it. The seventh and final projection resolver under R6 (Phase 7), sibling to
 * {@link OrderByResolver} (Phase 5), {@link LookupMappingResolver} (Phase 6a),
 * {@link PaginationResolver} (Phase 6b), {@link ConditionResolver} (Phase 6c),
 * {@link InputFieldResolver} (Phase 6d), and {@link MutationInputResolver} (Phase 6e).
 *
 * <p>Five methods cluster here, all touching the same axis: deriving how a GraphQL value
 * (especially an enum value) is converted before reaching jOOQ or a service method.
 *
 * <ul>
 *   <li>{@link #buildTextEnumMapping} reads a GraphQL enum type and produces a value-name to
 *       DB-string mapping (via {@code @field(name:)} on each enum value, defaulting to the
 *       value name itself). Returns {@code null} when the GraphQL type is not an enum.</li>
 *   <li>{@link #validateEnumFilter} matches a GraphQL enum against a jOOQ-generated Java enum
 *       column class. Returns a sealed {@link EnumValidation} the caller switches on:
 *       {@link EnumValidation.NotEnum} when the column is not a jOOQ enum (the GraphQL side may
 *       still be one — the caller treats that as "no enum coercion needed"),
 *       {@link EnumValidation.Valid} carrying the matched Java enum's fully qualified class name,
 *       or {@link EnumValidation.Mismatch} carrying a single composed rejection message that the
 *       caller appends to its accumulating errors list. Replaces the tri-state
 *       null/fqcn/empty-string sentinel that mirrored {@link ConditionResolver}'s pre-Phase-6c
 *       dual-signal pattern.</li>
 *   <li>{@link #deriveExtraction} picks the {@link CallSiteExtraction} strategy for a scalar
 *       column-bound value, given the GraphQL type, the target column, the validated enum
 *       class name (when applicable), and the generated static-map field name to use for
 *       text-enum lookups. Total projection: never fails.</li>
 *   <li>{@link #enrichArgExtractions} walks a {@link MethodRef}'s parameters and rewrites each
 *       {@link ParamSource.Arg} of type {@code java.lang.String} whose extraction is
 *       {@link CallSiteExtraction.Direct} and whose corresponding GraphQL argument type is an
 *       enum, replacing the extraction with a {@link CallSiteExtraction.TextMapLookup}. Used
 *       after service-method or table-method reflection to add enum-text mapping to call
 *       sites that {@link ServiceCatalog} couldn't infer.</li>
 *   <li>{@link #buildLookupBindings} walks a {@link GraphitronType.TableInputType}'s fields
 *       and emits one {@link InputColumnBinding.MapBinding} per {@code @lookupKey}-bearing
 *       scalar column field, deriving each binding's extraction via {@link #deriveExtraction}.
 *       Rejects {@code @lookupKey} on non-{@link InputField.ColumnField} entries (e.g.
 *       reference-navigating, nesting, NodeId fields) and on list-typed input fields. Cluster
 *       member because every binding's extraction routes through the enum-mapping helpers.</li>
 * </ul>
 *
 * <p>No sealed result wrapper. Each method's signature reflects the natural shape of its
 * domain: total projections return their value directly, three-state outcomes preserve their
 * sentinel-null/empty-string discrimination, list-returning walks use the errors-list mutation
 * pattern shared with {@link InputFieldResolver#resolve}.
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
        record Mismatch(String message) implements EnumValidation {}
    }

    private static final EnumValidation NOT_ENUM = new EnumValidation.NotEnum();

    private final BuildContext ctx;

    EnumMappingResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Builds a mapping from GraphQL enum value names to database string values when
     * {@code graphqlTypeName} resolves to a {@link GraphQLEnumType}; returns {@code null}
     * otherwise. Each value's DB string comes from {@code @field(name:)} on the value, falling
     * back to the value's own name when the directive is absent.
     */
    Map<String, String> buildTextEnumMapping(String graphqlTypeName) {
        var schemaType = ctx.schema.getType(graphqlTypeName);
        if (!(schemaType instanceof GraphQLEnumType graphqlEnum)) {
            return null;
        }
        var mapping = new LinkedHashMap<String, String>();
        for (var value : graphqlEnum.getValues()) {
            String dbValue = argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName());
            mapping.put(value.getName(), dbValue);
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
            colClass = Class.forName(column.columnClass());
        } catch (ClassNotFoundException e) {
            return NOT_ENUM;
        }
        if (!colClass.isEnum()) {
            return NOT_ENUM;
        }
        var schemaType = ctx.schema.getType(graphqlTypeName);
        if (!(schemaType instanceof GraphQLEnumType graphqlEnum)) {
            return new EnumValidation.Mismatch("column '" + column.sqlName() + "' is a jOOQ enum ("
                + colClass.getSimpleName() + ") but GraphQL type '" + graphqlTypeName + "' is not an enum");
        }
        var javaConstants = Arrays.stream(colClass.getEnumConstants())
            .map(c -> ((Enum<?>) c).name())
            .collect(Collectors.toSet());
        var mismatches = new ArrayList<String>();
        for (var value : graphqlEnum.getValues()) {
            String target = argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName());
            if (!javaConstants.contains(target)) {
                mismatches.add("'" + value.getName() + "'" + (target.equals(value.getName()) ? "" : " (mapped to '" + target + "')")
                    + candidateHint(target, new ArrayList<>(javaConstants)));
            }
        }
        if (!mismatches.isEmpty()) {
            return new EnumValidation.Mismatch("GraphQL enum '" + graphqlTypeName
                + "' has values that don't match jOOQ enum " + colClass.getSimpleName() + ": "
                + String.join("; ", mismatches));
        }
        return new EnumValidation.Valid(colClass.getName());
    }

    /**
     * Derives the {@link CallSiteExtraction} strategy for a scalar column-bound value given
     * its GraphQL type and target column. {@code enumClassName} is the FQCN from
     * {@link EnumValidation.Valid}; pass {@code null} when the column is not a jOOQ enum (the
     * {@link EnumValidation.NotEnum} arm) so the {@code TextMapLookup} / {@code JooqConvert} /
     * {@code Direct} fallbacks can take over. {@code mapFieldName} is the generated static-map
     * field name used when the GraphQL type is a text-mapped enum.
     */
    CallSiteExtraction deriveExtraction(String typeName, ColumnRef columnRef,
                                        String enumClassName, String mapFieldName) {
        if (enumClassName != null) {
            return new CallSiteExtraction.EnumValueOf(enumClassName);
        }
        if ("ID".equals(typeName)) {
            return new CallSiteExtraction.JooqConvert(columnRef.javaName());
        }
        var textEnumMapping = buildTextEnumMapping(typeName);
        if (textEnumMapping != null) {
            return new CallSiteExtraction.TextMapLookup(mapFieldName, textEnumMapping);
        }
        return new CallSiteExtraction.Direct();
    }

    /**
     * Enriches a {@link MethodRef}'s {@link ParamSource.Arg} parameters: any String-typed Arg
     * with a {@link CallSiteExtraction.Direct} whose corresponding GraphQL argument is an enum
     * is rewritten to extract via {@link CallSiteExtraction.TextMapLookup}. Used after a
     * service or table method has been resolved by {@link ServiceCatalog} (which always emits
     * {@code Direct}) so the GraphQL enum value is mapped to its DB string before reaching the
     * generated method body. Other parameter sources ({@link ParamSource.Context},
     * {@link ParamSource.Table}) and non-String/non-enum Arg sources pass through untouched.
     *
     * <p>The generated static map field lives in the {@code *Fetchers} class for this type.
     */
    MethodRef enrichArgExtractions(MethodRef method, GraphQLFieldDefinition fieldDef) {
        var argTypes = fieldDef.getArguments().stream()
            .collect(Collectors.toMap(
                GraphQLArgument::getName,
                a -> ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(a.getType())).getName()));
        var newParams = method.params().stream().map(p -> {
            if (!(p.source() instanceof ParamSource.Arg arg)) return p;
            if (!(arg.extraction() instanceof CallSiteExtraction.Direct)) return p;
            if (!String.class.getName().equals(p.typeName())) return p;
            String graphqlTypeName = argTypes.get(arg.graphqlArgName());
            if (graphqlTypeName == null) return p;
            var textMapping = buildTextEnumMapping(graphqlTypeName);
            if (textMapping == null) return p;
            String mapFieldName = fieldDef.getName().toUpperCase() + "_"
                + arg.graphqlArgName().toUpperCase() + "_MAP";
            return (MethodRef.Param) new MethodRef.Param.Typed(p.name(), p.typeName(),
                new ParamSource.Arg(new CallSiteExtraction.TextMapLookup(mapFieldName, textMapping),
                    arg.graphqlArgName()));
        }).toList();
        return new MethodRef.Basic(method.className(), method.methodName(),
            method.returnType(), newParams);
    }

    /**
     * Walks a {@link GraphitronType.TableInputType} argument's fields and builds one
     * {@link InputColumnBinding.MapBinding} per {@code @lookupKey}-bearing input field
     * (argres Phase 3).
     *
     * <p>Only {@link InputField.ColumnField} entries contribute bindings: a {@code @lookupKey}
     * on a {@code @reference}-navigating, nesting, or NodeId input field is rejected here.
     * List-typed input fields are also rejected; list cardinality must live on the outer
     * argument, not on an individual input-type field.
     *
     * <p>Returns {@link List#of()} when no input field carries {@code @lookupKey}. The caller
     * (validity gate in {@code FieldBuilder.projectForFilter}) reports "empty lookup mapping
     * despite {@code @lookupKey}" only when the field trips the lookup gate with no other
     * source of lookup columns.
     */
    List<InputColumnBinding.MapBinding> buildLookupBindings(GraphitronType.TableInputType tit,
            GraphQLArgument arg, GraphQLFieldDefinition fieldDef, String argName, List<String> errors) {
        var sdlType = ctx.schema.getType(tit.name());
        if (!(sdlType instanceof GraphQLInputObjectType iot)) {
            return List.of();
        }
        var byName = tit.inputFields().stream()
            .collect(Collectors.toMap(InputField::name, f -> f));
        var bindings = new ArrayList<InputColumnBinding.MapBinding>();
        for (var sdlField : iot.getFieldDefinitions()) {
            if (!sdlField.hasAppliedDirective(DIR_LOOKUP_KEY)) continue;
            var resolved = byName.get(sdlField.getName());
            if (!(resolved instanceof InputField.ColumnField cf)) {
                errors.add("input type '" + tit.name() + "' field '" + sdlField.getName()
                    + "': @lookupKey is only supported on scalar column fields");
                continue;
            }
            if (cf.list()) {
                errors.add("input type '" + tit.name() + "' field '" + sdlField.getName()
                    + "': @lookupKey on a list-typed input field is not supported; "
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
            String mapFieldName = fieldDef.getName().toUpperCase() + "_"
                + argName.toUpperCase() + "_" + sdlField.getName().toUpperCase() + "_MAP";
            CallSiteExtraction extraction = deriveExtraction(cf.typeName(), cf.column(), enumClassName, mapFieldName);
            bindings.add(new InputColumnBinding.MapBinding(sdlField.getName(), cf.column(), extraction));
        }
        return List.copyOf(bindings);
    }
}
