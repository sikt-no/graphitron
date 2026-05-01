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
 *       column class. Returns the Java enum's fully qualified class name on success,
 *       {@code null} when the column is not an enum (the GraphQL type may or may not be one),
 *       or the empty string when validation fails (mismatches appended to the errors list).
 *       The tri-state preserves three honest outcomes the callers branch on directly.</li>
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
     * type. Returns the fully qualified Java enum class name when the column is an enum and
     * all values validate, {@code null} when the column is not an enum, or the empty string
     * when the column is an enum but validation fails (errors are appended to {@code errors}).
     */
    String validateEnumFilter(String graphqlTypeName, ColumnRef column, List<String> errors) {
        Class<?> colClass;
        try {
            colClass = Class.forName(column.columnClass());
        } catch (ClassNotFoundException e) {
            return null;
        }
        if (!colClass.isEnum()) {
            return null;
        }
        var schemaType = ctx.schema.getType(graphqlTypeName);
        if (!(schemaType instanceof GraphQLEnumType graphqlEnum)) {
            errors.add("column '" + column.sqlName() + "' is a jOOQ enum (" + colClass.getSimpleName()
                + ") but GraphQL type '" + graphqlTypeName + "' is not an enum");
            return "";
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
            errors.add("GraphQL enum '" + graphqlTypeName + "' has values that don't match jOOQ enum "
                + colClass.getSimpleName() + ": " + String.join("; ", mismatches));
            return "";
        }
        return colClass.getName();
    }

    /**
     * Derives the {@link CallSiteExtraction} strategy for a scalar column-bound value given
     * its GraphQL type and target column. {@code enumClassName} is the result of
     * {@link #validateEnumFilter} (non-null only for jOOQ-enum columns). {@code mapFieldName}
     * is the generated static-map field name used when the GraphQL type is a text-mapped enum.
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
            String enumClassName = validateEnumFilter(cf.typeName(), cf.column(), errors);
            if (enumClassName != null && enumClassName.isEmpty()) {
                continue;
            }
            String mapFieldName = fieldDef.getName().toUpperCase() + "_"
                + argName.toUpperCase() + "_" + sdlField.getName().toUpperCase() + "_MAP";
            CallSiteExtraction extraction = deriveExtraction(cf.typeName(), cf.column(), enumClassName, mapFieldName);
            bindings.add(new InputColumnBinding.MapBinding(sdlField.getName(), cf.column(), extraction));
        }
        return List.copyOf(bindings);
    }
}
