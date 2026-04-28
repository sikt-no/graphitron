package no.sikt.graphitron.rewrite;

import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ChildField.ConstructorField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.PropertyField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.TableMethodField;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLLATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONNECTION_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DEFAULT_FIRST_VALUE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONTEXT_ARGUMENTS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DIRECTION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_FIELDS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_INDEX;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_JAVA_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_OVERRIDE;
import static no.sikt.graphitron.rewrite.BuildContext.argBoolean;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PATH;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PRIMARY_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_SERVICE_REF;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TABLE_METHOD_REF;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_DEFAULT_ORDER;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_EXTERNAL_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_LOOKUP_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MULTITABLE_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NOT_GENERATED;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ORDER_BY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SPLIT_QUERY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.argStringList;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;
import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;
import static no.sikt.graphitron.rewrite.BuildContext.locationOf;

/**
 * Classifies all fields in the schema into the {@link GraphitronField} hierarchy.
 *
 * <p>Reads directives ({@code @service}, {@code @reference}, {@code @field}, {@code @nodeId},
 * {@code @externalField}, {@code @tableMethod}, {@code @mutation}, {@code @splitQuery},
 * {@code @lookupKey}, {@code @defaultOrder}, {@code @orderBy}, {@code @condition}) to determine
 * the correct field variant. Downstream code works exclusively with the produced
 * {@link GraphitronField} values.
 */
class FieldBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(FieldBuilder.class);

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    FieldBuilder(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    // ===== Shared resolution helpers =====

    private record ServiceResolution(MethodRef method, ReturnTypeRef returnType, String error) {}

    /**
     * Extracts the {@link BatchKey} from the first {@link MethodRef.Param.Sourced} parameter of the
     * given method, or {@code null} when the method has no such parameter.
     *
     * <p>A {@code null} result means the service method lacks the required {@code Sources}
     * parameter — the validator will surface this as an error before code generation runs.
     */
    private static BatchKey extractBatchKey(MethodRef method) {
        return method.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> ((MethodRef.Param.Sourced) p).batchKey())
            .findFirst()
            .orElse(null);
    }

    /**
     * Resolves the {@code @service} directive on a field: unwraps connection types, parses the
     * external reference, reflects the service method, and returns the resolved method + return type.
     * Returns a non-null {@code error} when resolution fails.
     *
     * <p>{@code parentPkColumns} is forwarded to {@link ServiceCatalog#reflectServiceMethod} for
     * batch-key classification. Pass {@link List#of()} for root fields and result-type children
     * (no parent table); pass the parent table's primary-key columns for table-type children.
     */
    private ServiceResolution resolveServiceField(String parentTypeName, GraphQLFieldDefinition fieldDef, List<ColumnRef> parentPkColumns) {
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
        ExternalRef serviceRef = parseExternalRef(parentTypeName, fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
        if (serviceRef != null && serviceRef.lookupError() != null) {
            return new ServiceResolution(null, null, "service method could not be resolved — " + serviceRef.lookupError());
        }
        List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
        Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
        // Strict return-type validation applies to root @service fields only (parentPkColumns empty).
        // Child @service uses DataLoader-batched semantics where the method takes Sources keys and
        // returns a flat or keyed shape that doesn't directly match the field's return type — that
        // shape is the child-service plan's concern. Root fields hand the value straight to graphql-
        // java, so the framework needs to know its specific shape.
        TypeName expectedReturnType = parentPkColumns.isEmpty()
            ? computeExpectedServiceReturnType(returnType)
            : null;
        var result = svc.reflectServiceMethod(serviceRef.className(), serviceRef.methodName(), argNames, new java.util.HashSet<>(contextArgs), parentPkColumns, expectedReturnType);
        if (result.failed()) {
            return new ServiceResolution(null, null, "service method could not be resolved — " + result.failureReason());
        }
        return new ServiceResolution(enrichArgExtractions(result.ref(), fieldDef), returnType, null);
    }

    /**
     * Computes the expected return type that a {@code @service} method must declare, as a
     * structured javapoet {@link TypeName}. Returns {@code null} when no strict validation
     * is applicable (the caller treats the actual reflection-captured return type as truth).
     *
     * <ul>
     *   <li>{@code TableBoundReturnType} + Single → {@code <jooqPackage>.tables.records.<TableName>Record}</li>
     *   <li>{@code TableBoundReturnType} + List → {@code org.jooq.Result<<RecordFqcn>>}</li>
     *   <li>{@code ResultReturnType} (with non-null fqClassName) + Single → {@code <fqClassName>}</li>
     *   <li>{@code ResultReturnType} (with non-null fqClassName) + List → {@code java.util.List<<fqClassName>>}</li>
     *   <li>{@code ResultReturnType} (null fqClassName) → null</li>
     *   <li>{@code ScalarReturnType} → null (graphql-java's scalar coercion handles type matching)</li>
     *   <li>{@code PolymorphicReturnType} → null (rejected separately)</li>
     * </ul>
     *
     * <p>Connection-cardinality cases are unreachable here because {@code @service} +
     * {@code Connection} is rejected at Invariants §1 before this helper runs.
     */
    private TypeName computeExpectedServiceReturnType(ReturnTypeRef returnType) {
        // Connection-cardinality is rejected by Invariants §1 downstream of this helper; skip the
        // return-type check here so the §1 message fires (rather than masking it with a
        // less-specific return-type mismatch).
        if (returnType.wrapper() instanceof FieldWrapper.Connection) return null;
        boolean isList = returnType.wrapper().isList();
        return switch (returnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                ClassName recordCls = ClassName.get(
                    ctx.ctx().jooqPackage() + ".tables.records",
                    tb.table().javaClassName() + "Record");
                yield isList
                    ? ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), recordCls)
                    : recordCls;
            }
            case ReturnTypeRef.ResultReturnType r -> {
                if (r.fqClassName() == null) yield null;
                ClassName resultCls = ClassName.bestGuess(r.fqClassName());
                yield isList
                    ? ParameterizedTypeName.get(ClassName.get("java.util", "List"), resultCls)
                    : resultCls;
            }
            case ReturnTypeRef.ScalarReturnType ignored -> null;
            case ReturnTypeRef.PolymorphicReturnType ignored -> null;
        };
    }

    /**
     * Shared invariant check for root {@code @service} fields (both Query and Mutation arms).
     * Returns a non-null reason string when the resolved {@link ServiceResolution} violates an
     * invariant, {@code null} otherwise.
     *
     * <ul>
     *   <li>§1: {@link FieldWrapper.Connection} return type — root has no pagination context.</li>
     *   <li>§2: {@link ParamSource.Sources} parameter — root has no parent context to batch
     *       against.</li>
     * </ul>
     */
    private static String validateRootServiceInvariants(ServiceResolution svcResult) {
        if (svcResult.returnType().wrapper() instanceof FieldWrapper.Connection) {
            return "@service at the root does not support Connection return types — use [T] or T instead";
        }
        if (svcResult.method().params().stream().anyMatch(p -> p.source() instanceof ParamSource.Sources)) {
            return "@service at the root does not support List<Row>/List<Record>/List<Object> batch parameters — the root has no parent context to batch against";
        }
        return null;
    }

    private record TableFieldComponents(List<WhereFilter> filters, OrderBySpec orderBy, PaginationSpec pagination,
                                        String error, LookupMapping lookupMapping) {
        /** Construct an error result with no component values. */
        static TableFieldComponents error(String message) {
            return new TableFieldComponents(null, null, null, message, null);
        }
    }

    /**
     * Resolves the filter, order-by, and pagination components for a table-bound list field.
     * Returns a non-null {@code error} when any component fails to resolve.
     *
     * @param returnTypeName the GraphQL return type name (e.g. {@code "Film"}), used to derive
     *                       the {@code *Conditions} class name for any generated filter method
     */
    private TableFieldComponents resolveTableFieldComponents(GraphQLFieldDefinition fieldDef, TableRef table, String returnTypeName) {
        var errors = new ArrayList<String>();
        var refs = classifyArguments(fieldDef, table, errors);
        return projectForFilter(refs, fieldDef, table, returnTypeName, errors);
    }

    // ===== Object-return child field classification =====

    /**
     * Classifies a child field on a {@link TableType} parent whose return type is an object, interface,
     * or union — not a scalar or enum. Called after the {@code @tableMethod} check in
     * {@link #classifyChildFieldOnTableType}.
     *
     * <p>P2 handles {@link TableField} and {@link NestingField}. Remaining variants
     * ({@code TableInterfaceField}, {@code InterfaceField}, {@code UnionField}, {@code ServiceField},
     * {@code ComputedField}) are added in P3.
     */
    private GraphitronField classifyObjectReturnChildField(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType parentTableType, Set<String> expandingTypes) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        String rawTypeName = baseTypeName(fieldDef);

        // For connection types the element type is edges.node, not the connection wrapper type.
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        GraphitronType elementType = ctx.types.get(elementTypeName);

        if (elementType instanceof TableBackedType tbt && !(elementType instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = (ReturnTypeRef.TableBoundReturnType) ctx.resolveReturnType(elementTypeName, wrapper);
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName(), returnType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, referencePath.errorMessage());
            }
            var tfc = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tfc.error());
            boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
            boolean hasLookupKey  = hasLookupKeyAnywhere(fieldDef);
            boolean isList = returnType.wrapper().isList();
            var parentBatchKey = deriveSplitQueryBatchKey(parentTableType.table(), referencePath.elements(), isList);
            if (hasSplitQuery && hasLookupKey) {
                if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                        "@asConnection on @lookupKey fields is invalid: @lookupKey establishes a positional "
                        + "correspondence between the input key list and the output list (one entry per key), "
                        + "which pagination would break. Drop @asConnection or drop @lookupKey.");
                }
                if (returnType.wrapper() instanceof FieldWrapper.Single) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                        "Single-cardinality @splitQuery @lookupKey is not supported; pass a list-returning field or drop @lookupKey");
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey,
                    tfc.lookupMapping());
            }
            if (!hasSplitQuery && hasLookupKey) {
                if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                        "@asConnection on @lookupKey fields is invalid: @lookupKey establishes a positional "
                        + "correspondence between the input key list and the output list (one entry per key), "
                        + "which pagination would break. Drop @asConnection or drop @lookupKey.");
                }
                if (returnType.wrapper() instanceof FieldWrapper.Single) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                        "Single-cardinality @lookupKey is not supported; pass a list-returning field or drop @lookupKey");
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.LookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                    tfc.lookupMapping());
            }
            if (hasSplitQuery) {
                if (returnType.wrapper() instanceof FieldWrapper.Single
                        && referencePath.elements().size() != 1) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                        "Single-cardinality @splitQuery requires a single-hop parent-holds-FK reference path; "
                        + "multi-hop paths are not yet supported on single cardinality");
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey);
            }
            if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                    "@asConnection on inline (non-@splitQuery) TableField is not supported; add @splitQuery for batched connection semantics");
            }
            return new TableField(parentTypeName, name, location,
                returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName(), tableInterfaceType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, referencePath.errorMessage());
            }
            var tfc = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tfc.error());
            var joinPathError = validateSingleHopFkJoin(referencePath.elements(), name);
            if (joinPathError != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, joinPathError);
            var knownValues = knownDiscriminatorValues(tableInterfaceType);
            return new TableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper),
                tableInterfaceType.discriminatorColumn(), knownValues, tableInterfaceType.participants(),
                referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (elementType instanceof InterfaceType interfaceType) {
            return new InterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        if (elementType instanceof UnionType unionType) {
            return new UnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        // NestingField: a plain object type in the schema with no Graphitron domain classification.
        // Its fields are resolved from the same table context as the parent — classified
        // recursively so nested scalars reach the model as ColumnField (and future arms as
        // their respective leaves). @record parents cannot reach here; this path is gated
        // on TableBackedType by classifyChildFieldOnTableType's caller at line 1217.
        //
        // "No domain classification" includes both the pre-classifier-records-plain-types state
        // (elementType == null) and the post-Phase-4 state (classified as PlainObjectType).
        boolean isPlainObjectElement = elementType == null
            || elementType instanceof GraphitronType.PlainObjectType;
        if (ctx.schema.getType(elementTypeName) instanceof GraphQLObjectType graphQLObjectType
                && isPlainObjectElement) {
            var wrapper = buildWrapper(fieldDef);
            if (expandingTypes.contains(elementTypeName)) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                    "circular type reference detected while expanding '" + elementTypeName + "'");
            }
            var newExpanding = new LinkedHashSet<>(expandingTypes);
            newExpanding.add(elementTypeName);
            var nestedFields = new ArrayList<ChildField>();
            for (var nestedDef : graphQLObjectType.getFieldDefinitions()) {
                var nested = classifyChildFieldOnTableType(nestedDef, elementTypeName, parentTableType, newExpanding);
                if (nested instanceof UnclassifiedField unc) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, unc.kind(),
                        "nested type '" + elementTypeName + "' field '" + nestedDef.getName() + "': " + unc.reason());
                }
                if (nested instanceof ChildField cf) {
                    nestedFields.add(cf);
                } else {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INTERNAL_INVARIANT,
                        "nested type '" + elementTypeName + "' field '" + nestedDef.getName()
                        + "' classified as unexpected variant " + nested.getClass().getSimpleName());
                }
            }
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, parentTableType.table(), wrapper),
                List.copyOf(nestedFields));
        }

        // ConstructorField: @table parent with a @record child — pass the parent's Record through as
        // the child's source. The child's own Fetchers class handles property/table-child resolution.
        if (elementType instanceof ResultType rt) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = (ReturnTypeRef.ResultReturnType) ctx.resolveReturnType(elementTypeName, wrapper);
            return new ConstructorField(parentTypeName, name, location, returnType);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "return type '" + elementTypeName + "' is not a @table, @record, interface, or union Graphitron type");
    }

    // ===== Wrapper helpers =====

    /**
     * Builds a {@link FieldWrapper} from the return type shape of the field (cardinality and
     * nullability only). Ordering is separated into {@link #buildOrderBySpec}.
     *
     * <p>Connection is detected two ways:
     * <ol>
     *   <li><b>Directive-driven:</b> the field has {@code @asConnection} — the schema type is a
     *       bare list {@code [Film]} but the wrapper is {@link FieldWrapper.Connection}.</li>
     *   <li><b>Structural:</b> the return type has an {@code edges.node} pattern (pre-expanded
     *       connection from the schema transform or hand-written).</li>
     * </ol>
     */
    private FieldWrapper buildWrapper(GraphQLFieldDefinition fieldDef) {
        GraphQLType fieldType = fieldDef.getType();
        boolean outerNullable = !(fieldType instanceof GraphQLNonNull);
        GraphQLType unwrappedOnce = GraphQLTypeUtil.unwrapNonNull(fieldType);

        // @asConnection on a list field → Connection wrapper.
        // Per-type metadata (name, element, item nullability) lives on ConnectionType in
        // schema.types(); this wrapper only carries per-carrier-site pagination metadata.
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION) && unwrappedOnce instanceof GraphQLList) {
            int defaultPageSize = resolveDefaultFirstValue(fieldDef);
            return new FieldWrapper.Connection(outerNullable, defaultPageSize);
        }

        if (unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            return new FieldWrapper.List(outerNullable, itemNullable);
        }

        // Structural detection: pre-expanded Connection type with edges.node pattern.
        String typeName = baseTypeName(fieldDef);
        if (ctx.isConnectionType(typeName)) {
            return new FieldWrapper.Connection(outerNullable, FieldWrapper.DEFAULT_PAGE_SIZE);
        }

        return new FieldWrapper.Single(outerNullable);
    }

    /**
     * Projects the classified arguments into an {@link OrderBySpec}.
     *
     * <p>Returns {@link OrderBySpec.None} when ordering is not applicable: for single-value
     * returns, or when {@code tableSqlName} is {@code null} (non-table-bound field).
     * Returns {@link OrderBySpec.None} (not an error) when the table has no primary key and no
     * {@code @defaultOrder} is present.
     * Returns {@code null} — signalling a build failure — when a {@code @defaultOrder}
     * directive is present but its column/index resolution fails, or when an {@code @orderBy}
     * argument failed to classify.
     */
    private OrderBySpec projectOrderBySpec(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef,
                                           String tableSqlName, List<String> errors) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType());
        boolean isList = (unwrapped instanceof GraphQLList)
            || ctx.isConnectionType(baseTypeName(fieldDef))
            || fieldDef.hasAppliedDirective(DIR_AS_CONNECTION);
        if (!isList || tableSqlName == null) return new OrderBySpec.None();

        for (var ref : refs) {
            if (ref instanceof ArgumentRef.OrderByArg ob) {
                var arg = fieldDef.getArgument(ob.name());
                return resolveOrderByArgSpec(arg, fieldDef, tableSqlName, errors);
            }
        }
        return resolveDefaultOrderSpec(fieldDef, tableSqlName);
    }

    /**
     * Resolves the effective default order for a table-backed list/connection field.
     *
     * <p>Returns {@link OrderBySpec.Fixed} when {@code @defaultOrder} resolves successfully or the
     * table has a primary key. Returns {@link OrderBySpec.None} when the table has no primary key
     * and no {@code @defaultOrder} is present. Returns {@code null} when {@code @defaultOrder} is
     * present but column/index resolution fails.
     */
    private OrderBySpec resolveDefaultOrderSpec(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        if (fieldDef.hasAppliedDirective(DIR_DEFAULT_ORDER)) {
            return resolveColumnOrderSpec(fieldDef, tableSqlName);
        }
        var pkCols = ctx.catalog.findPkColumns(tableSqlName);
        if (pkCols.isEmpty()) return new OrderBySpec.None();
        return new OrderBySpec.Fixed(
            pkCols.stream()
                .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                .toList(),
            "ASC");
    }

    /**
     * Resolves the {@code @defaultOrder} directive on a field into a fully-normalised
     * {@link OrderBySpec.Fixed} against {@code tableSqlName}.
     *
     * <p>Only called when the directive is confirmed present. Returns {@code null} when any
     * catalog lookup fails; the caller generates a diagnostic message in that case.
     */
    private OrderBySpec.Fixed resolveColumnOrderSpec(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        var dir = fieldDef.getAppliedDirective(DIR_DEFAULT_ORDER);

        // direction has a default of ASC in the directive; absent arg means ASC.
        String direction = "ASC";
        var dirArg = dir.getArgument(ARG_DIRECTION);
        if (dirArg != null) {
            Object dirVal = dirArg.getValue();
            if (dirVal instanceof EnumValue ev) direction = ev.getName();
            else if (dirVal instanceof String s) direction = s;
        }

        var entries = resolveOrderEntries(dir, tableSqlName);
        if (entries == null) return null;
        return new OrderBySpec.Fixed(entries, direction);
    }

    /**
     * Resolves an {@code @order} directive on an enum value into a {@link OrderBySpec.Fixed}.
     *
     * <p>The direction is not stored here — it comes from the runtime input object's direction
     * field and is applied at code-generation time in the {@code *OrderBy} helper method.
     * Returns {@code null} and appends an error when catalog lookup fails.
     */
    private OrderBySpec.Fixed resolveEnumValueOrderSpec(
            GraphQLEnumValueDefinition ev,
            String tableSqlName,
            List<String> errors) {
        var dir = ev.getAppliedDirective("order");
        List<OrderBySpec.ColumnOrderEntry> entries;
        if (dir != null) {
            entries = resolveOrderEntries(dir, tableSqlName);
        } else {
            // @index is a deprecated alias: @index(name: "idx") ≡ @order(index: "idx")
            var indexDir = ev.getAppliedDirective("index");
            var nameArg = indexDir != null ? indexDir.getArgument(ARG_NAME) : null;
            Object nameVal = nameArg != null ? nameArg.getValue() : null;
            String indexName = nameVal instanceof StringValue sv ? sv.getValue().strip()
                : nameVal instanceof String s ? s.strip() : null;
            entries = resolveIndexColumns(tableSqlName, indexName);
        }
        if (entries == null) {
            errors.add("enum value '" + ev.getName() + "': could not resolve @order columns in table '" + tableSqlName + "'");
            return null;
        }
        return new OrderBySpec.Fixed(entries, "ASC");
    }

    /** Looks up named index columns from the catalog; returns {@code null} when not found. */
    private List<OrderBySpec.ColumnOrderEntry> resolveIndexColumns(String tableSqlName, String indexName) {
        if (indexName == null) return null;
        var colsOpt = ctx.catalog.findIndexColumns(tableSqlName, indexName);
        if (colsOpt.isEmpty() || colsOpt.get().isEmpty()) return null;
        return colsOpt.get().stream()
            .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
            .toList();
    }

    /**
     * Resolves the column entries from an {@code @order} or {@code @defaultOrder} directive.
     *
     * <p>All three source variants are resolved at build time:
     * <ul>
     *   <li>{@code index:} — columns come from the named index via the jOOQ catalog.</li>
     *   <li>{@code primaryKey:} — columns come from the table's primary key.</li>
     *   <li>{@code fields:} — each column name is looked up in the table via the jOOQ catalog.</li>
     * </ul>
     * Returns {@code null} when any lookup fails (index not found, PK absent, or a column name is
     * unresolvable). The caller is responsible for generating a diagnostic message.
     */
    private List<OrderBySpec.ColumnOrderEntry> resolveOrderEntries(GraphQLAppliedDirective dir, String tableSqlName) {
        var indexArg = dir.getArgument(ARG_INDEX);
        if (indexArg != null) {
            Object indexVal = indexArg.getValue();
            String indexName = indexVal instanceof StringValue sv ? sv.getValue().strip()
                : indexVal instanceof String s ? s.strip() : null;
            if (indexName != null) return resolveIndexColumns(tableSqlName, indexName);
        }

        var pkArg = dir.getArgument(ARG_PRIMARY_KEY);
        boolean primaryKey = pkArg != null && (
            pkArg.getValue() instanceof BooleanValue bv ? bv.isValue()
            : Boolean.TRUE.equals(pkArg.getValue()));
        if (primaryKey) {
            var pkCols = ctx.catalog.findPkColumns(tableSqlName);
            if (pkCols.isEmpty()) return null;
            return pkCols.stream()
                .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                .toList();
        }

        var fieldsArg = dir.getArgument(ARG_FIELDS);
        if (fieldsArg != null) {
            Object value = fieldsArg.getValue();
            List<?> items = value instanceof List<?> l ? l : List.of(value);
            var entries = new ArrayList<OrderBySpec.ColumnOrderEntry>();
            for (var item : items) {
                if (!(item instanceof Map)) continue;
                var map = asMap(item);
                Object nameRaw = map.get(ARG_NAME);
                if (nameRaw == null) return null;
                String colName = nameRaw.toString().strip();
                String collation = Optional.ofNullable(map.get(ARG_COLLATE)).map(Object::toString).map(String::strip).orElse(null);
                var ceOpt = ctx.catalog.findColumn(tableSqlName, colName);
                if (ceOpt.isEmpty()) return null;
                var ce = ceOpt.get();
                entries.add(new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), collation));
            }
            return entries;
        }

        return null;
    }

    /**
     * Resolves an {@code @orderBy} argument into an {@link OrderBySpec.Argument}.
     * Appends to {@code errors} and returns {@code null} when the input type structure is invalid.
     */
    private OrderBySpec resolveOrderByArgSpec(GraphQLArgument arg, GraphQLFieldDefinition fieldDef, String tableSqlName, List<String> errors) {
        String name = arg.getName();
        GraphQLType type = arg.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();

        var rawType = ctx.schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType inputType)) {
            errors.add("argument '" + name + "': @orderBy argument type '" + typeName + "' is not an input type");
            return null;
        }
        String sortFieldName = null;
        String directionFieldName = null;
        for (var field : inputType.getFieldDefinitions()) {
            var fieldType = GraphQLTypeUtil.unwrapNonNull(field.getType());
            if (!(fieldType instanceof GraphQLEnumType enumType)) continue;
            boolean isSortEnum = enumType.getValues().stream()
                .anyMatch(v -> v.hasAppliedDirective("order") || v.hasAppliedDirective("index"));
            if (isSortEnum) {
                if (sortFieldName != null) {
                    errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' must have exactly one sort enum field, but found multiple");
                    return null;
                }
                sortFieldName = field.getName();
            } else {
                if (directionFieldName != null) {
                    errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' must have exactly one direction field, but found multiple");
                    return null;
                }
                directionFieldName = field.getName();
            }
        }
        if (sortFieldName == null) {
            errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' has no sort enum field (no enum values with @order)");
            return null;
        }
        if (directionFieldName == null) {
            errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' has no direction field");
            return null;
        }
        GraphQLEnumType sortEnum = (GraphQLEnumType) GraphQLTypeUtil.unwrapNonNull(
            inputType.getFieldDefinition(sortFieldName).getType());
        var namedOrders = new ArrayList<OrderBySpec.NamedOrder>();
        for (var value : sortEnum.getValues()) {
            if (!value.hasAppliedDirective("order") && !value.hasAppliedDirective("index")) continue;
            OrderBySpec.Fixed order = resolveEnumValueOrderSpec(value, tableSqlName, errors);
            if (order == null) return null; // error already appended
            namedOrders.add(new OrderBySpec.NamedOrder(value.getName(), order));
        }
        OrderBySpec baseSpec = resolveDefaultOrderSpec(fieldDef, tableSqlName);
        if (baseSpec == null) return null; // resolveColumnOrderSpec failed; error already appended
        return new OrderBySpec.Argument(name, typeName, nonNull, list, sortFieldName, directionFieldName,
            List.copyOf(namedOrders),
            baseSpec);
    }

    /**
     * Classifies every GraphQL argument on the field into an {@link ArgumentRef} variant in one
     * pass. Projection into {@link WhereFilter} / {@link OrderBySpec} / {@link PaginationSpec} /
     * {@code LookupMapping} happens in dedicated projector methods, not here.
     *
     * <p>The intent of this method is to localise the "what is this argument for" decision so
     * multiple projections can read the same classification. See
     * {@code docs/argument-resolution.md}.
     *
     * <p>Errors append to {@code errors} but never cause a {@code null} return: every arg maps
     * to a variant. Variants like {@link ArgumentRef.ScalarArg.UnboundArg} and
     * {@link ArgumentRef.UnclassifiedArg} carry a {@code reason} that {@link #projectFilters}
     * surfaces into the field-level {@code errors} list.
     *
     * <p>{@code rt} is the target {@link TableRef} used to resolve scalar column args; every
     * current caller passes the field's resolved table, so this method does not accept
     * {@code null}.
     */
    List<ArgumentRef> classifyArguments(GraphQLFieldDefinition fieldDef, TableRef rt, List<String> errors) {
        var fieldCondition = ctx.readConditionDirective(fieldDef);
        boolean fieldOverride = fieldCondition != null && fieldCondition.override();
        var refs = new ArrayList<ArgumentRef>();
        for (var arg : fieldDef.getArguments()) {
            refs.add(classifyArgument(fieldDef, arg, rt, fieldOverride, errors));
        }
        return List.copyOf(refs);
    }

    private ArgumentRef classifyArgument(GraphQLFieldDefinition fieldDef, GraphQLArgument arg,
                                         TableRef rt, boolean fieldOverride, List<String> errors) {
        String name = arg.getName();
        GraphQLType type = arg.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();

        if (arg.hasAppliedDirective(DIR_ORDER_BY)) {
            return classifyOrderByArg(arg, name, typeName, nonNull, list, errors);
        }
        if (isPaginationArg(name)) {
            ArgumentRef.PaginationArgRef.Role role = switch (name) {
                case "first"  -> ArgumentRef.PaginationArgRef.Role.FIRST;
                case "last"   -> ArgumentRef.PaginationArgRef.Role.LAST;
                case "after"  -> ArgumentRef.PaginationArgRef.Role.AFTER;
                case "before" -> ArgumentRef.PaginationArgRef.Role.BEFORE;
                default       -> throw new IllegalStateException("unreachable: isPaginationArg(" + name + ")");
            };
            return new ArgumentRef.PaginationArgRef(name, typeName, nonNull, list, role);
        }

        Optional<ArgConditionRef> argCondition = buildArgCondition(arg, errors);

        // Route the arg to an input-shaped classification when the classifier recognises its type
        // as something input-like. TableInputType keeps its dedicated binding resolution.
        // InputType (Pojo / Java record / jOOQ record) and UnclassifiedType (input resolution
        // failed — e.g. FilmKey unresolvable against the surrounding table) both go through the
        // plain-input path so lookup-key search still runs and produces a focused error.
        var resolvedType = ctx.types.get(typeName);
        if (resolvedType instanceof GraphitronType.TableInputType tit) {
            List<InputColumnBinding> bindings = buildLookupBindings(tit, arg, fieldDef, name, errors);
            return new ArgumentRef.InputTypeArg.TableInputArg(
                name, typeName, nonNull, list, tit.table(), bindings, argCondition, tit.inputFields());
        }
        boolean isInputLike = resolvedType instanceof GraphitronType.InputType
            || (resolvedType instanceof GraphitronType.UnclassifiedType
                && ctx.schema.getType(typeName) instanceof GraphQLInputObjectType);
        if (isInputLike) {
            // The plain-input path's per-field errors are silently dropped by projectFilters
            // unless paired with @condition / @lookupKey gates. @notGenerated is a hard policy
            // violation, so reject the whole arg up front via UnclassifiedArg, which sets
            // hadError in projectFilters and propagates as an UnclassifiedField on the
            // surrounding query field.
            if (ctx.schema.getType(typeName) instanceof GraphQLInputObjectType iot) {
                var rejected = iot.getFieldDefinitions().stream()
                    .filter(f -> f.hasAppliedDirective(DIR_NOT_GENERATED))
                    .findFirst();
                if (rejected.isPresent()) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        "input field '" + rejected.get().getName()
                        + "': @notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");
                }
            }
            List<InputField> plainFields = classifyPlainInputFields(typeName, rt, errors);
            return new ArgumentRef.InputTypeArg.PlainInputArg(
                name, typeName, nonNull, list, argCondition, plainFields);
        }

        // NodeId scalar: scalar ID arg on a node-type table — skip column binding and produce
        // NodeIdArg so projections emit hasIds/hasId instead of a column predicate.
        if ("ID".equals(typeName) && !list) {
            Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta = ctx.catalog.nodeIdMetadata(rt.tableName());
            if (nodeIdMeta.isPresent()) {
                boolean isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
                return new ArgumentRef.ScalarArg.NodeIdArg(
                    name, typeName, nonNull, list,
                    nodeIdMeta.get().typeId(), nodeIdMeta.get().keyColumns(),
                    new CallSiteExtraction.Direct(), argCondition, isLookupKey);
            }
        }

        // Scalar arg: bind to column
        String columnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(name);
        var col = ctx.catalog.findColumn(rt.tableName(), columnName);
        if (col.isEmpty()) {
            return new ArgumentRef.ScalarArg.UnboundArg(
                name, typeName, nonNull, list, columnName,
                "column '" + columnName + "' could not be resolved in table '" + rt.tableName() + "'"
                    + candidateHint(columnName, ctx.catalog.columnJavaNamesOf(rt.tableName())));
        }
        if (!columnName.equals(col.get().javaName())) {
            LOG.warn("@field(name: '{}') on arg '{}' resolved via SQL name; prefer Java field name '{}'",
                columnName, name, col.get().javaName());
        }
        var columnRef = new ColumnRef(col.get().sqlName(), col.get().javaName(), col.get().columnClass());
        String enumClassName = validateEnumFilter(typeName, columnRef, errors);
        if (enumClassName != null && enumClassName.isEmpty()) {
            // Enum validation failed; error already appended. Emit UnclassifiedArg so
            // projectFilters surfaces the structural failure (even though the enum-value-mismatch
            // is already an error; keeping this consistent keeps the classify-never-returns-null
            // invariant).
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "enum filter validation failed for column '" + columnRef.sqlName() + "'");
        }
        CallSiteExtraction extraction = deriveExtraction(typeName, columnRef, enumClassName,
            fieldDef.getName().toUpperCase() + "_" + name.toUpperCase() + "_MAP");
        boolean isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
        return new ArgumentRef.ScalarArg.ColumnArg(
            name, typeName, nonNull, list, columnRef, extraction, argCondition, fieldOverride, isLookupKey);
    }

    /**
     * Derives the {@link CallSiteExtraction} strategy for a scalar column-bound value given its
     * GraphQL type and target column. {@code enumClassName} is the result of
     * {@link #validateEnumFilter} (non-null only for jOOQ-enum columns). {@code mapFieldName} is
     * the generated static-map field name used when the GraphQL type is a text-mapped enum.
     */
    private CallSiteExtraction deriveExtraction(String typeName, ColumnRef columnRef,
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
     * Classifies the fields of a plain (non-{@code @table}) input type at call site against {@code rt}.
     * Used to populate {@link ArgumentRef.InputTypeArg.PlainInputArg#fields()}.
     * Returns {@link List#of()} when {@code rt} is {@code null} or the type is not an input object.
     * Fields that fail column resolution are silently skipped (their conditions cannot be built).
     * {@code @notGenerated} fields are rejected up front by {@link #classifyArgument} as an
     * {@link ArgumentRef.UnclassifiedArg}, so they never reach this classifier.
     */
    private List<InputField> classifyPlainInputFields(String typeName, TableRef rt, List<String> errors) {
        if (rt == null) return List.of();
        var rawType = ctx.schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType iot)) return List.of();
        var condErrors = new ArrayList<String>();
        var classified = new ArrayList<InputField>();
        for (var f : iot.getFieldDefinitions()) {
            var res = ctx.classifyInputField(f, typeName, rt, new LinkedHashSet<>(), condErrors);
            if (res instanceof InputFieldResolution.Resolved r) {
                classified.add(r.field());
            }
        }
        condErrors.forEach(e -> errors.add("plain input type '" + typeName + "': " + e));
        return List.copyOf(classified);
    }

    /**
     * Walks a {@link GraphitronType.TableInputType} argument's fields and builds one
     * {@link InputColumnBinding} per {@code @lookupKey}-bearing input field (argres Phase 3).
     *
     * <p>Only {@link InputField.ColumnField} entries contribute bindings — a {@code @lookupKey}
     * on a {@code @reference}-navigating, nesting, or NodeId input field is rejected here.
     * List-typed input fields are also rejected: list cardinality must live on the outer
     * argument, not on an individual input-type field.
     *
     * <p>Returns {@link List#of()} when no input field carries {@code @lookupKey} — the caller
     * (validity gate in {@link #projectForFilter}) reports "empty lookup mapping despite
     * {@code @lookupKey}" only when the field trips the lookup gate with no other source of
     * lookup columns.
     */
    private List<InputColumnBinding> buildLookupBindings(GraphitronType.TableInputType tit,
            GraphQLArgument arg, GraphQLFieldDefinition fieldDef, String argName, List<String> errors) {
        var sdlType = ctx.schema.getType(tit.name());
        if (!(sdlType instanceof GraphQLInputObjectType iot)) {
            return List.of();
        }
        var byName = tit.inputFields().stream()
            .collect(Collectors.toMap(InputField::name, f -> f));
        var bindings = new ArrayList<InputColumnBinding>();
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
                continue; // enum validation failed; error already appended
            }
            String mapFieldName = fieldDef.getName().toUpperCase() + "_"
                + argName.toUpperCase() + "_" + sdlField.getName().toUpperCase() + "_MAP";
            CallSiteExtraction extraction = deriveExtraction(cf.typeName(), cf.column(), enumClassName, mapFieldName);
            bindings.add(new InputColumnBinding(sdlField.getName(), cf.column(), extraction));
        }
        return List.copyOf(bindings);
    }

    /**
     * Builds an {@link ArgConditionRef} from a {@code @condition} directive on one GraphQL argument.
     * Reflects the condition method via {@link ServiceCatalog#reflectTableMethod} with the arg's
     * name in {@code argNames} and any declared {@code contextArguments} in {@code ctxKeys}.
     * Appends an error and returns {@link Optional#empty()} on reflection failure.
     */
    private Optional<ArgConditionRef> buildArgCondition(GraphQLArgument arg, List<String> errors) {
        var cond = ctx.readConditionDirective(arg);
        if (cond == null) return Optional.empty();
        var argName = arg.getName();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            Set.of(argName), Set.copyOf(cond.contextArguments()), null);
        if (result.failed()) {
            errors.add("argument '" + argName + "' @condition: " + result.failureReason());
            return Optional.empty();
        }
        var methodRef = result.ref();
        return Optional.of(new ArgConditionRef(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()),
            cond.override()));
    }

    /**
     * Builds a field-level {@link ConditionFilter} from a {@code @condition} directive on the
     * field definition. Reflects via {@link ServiceCatalog#reflectTableMethod} with every field
     * argument name in {@code argNames} and any declared {@code contextArguments}. Returns
     * {@code null} when the directive is absent or reflection fails (error appended).
     */
    private ConditionFilter buildFieldCondition(GraphQLFieldDefinition fieldDef, List<String> errors) {
        var cond = ctx.readConditionDirective(fieldDef);
        if (cond == null) return null;
        var argNames = fieldDef.getArguments().stream()
            .map(GraphQLArgument::getName)
            .collect(Collectors.toSet());
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argNames, Set.copyOf(cond.contextArguments()), null);
        if (result.failed()) {
            errors.add("field '" + fieldDef.getName() + "' @condition: " + result.failureReason());
            return null;
        }
        var methodRef = result.ref();
        return new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params());
    }

    private ArgumentRef classifyOrderByArg(GraphQLArgument arg, String name, String typeName,
                                           boolean nonNull, boolean list, List<String> errors) {
        var rawType = ctx.schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType inputType)) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy argument type '" + typeName + "' is not an input type");
        }
        String sortFieldName = null;
        String directionFieldName = null;
        for (var field : inputType.getFieldDefinitions()) {
            var fieldType = GraphQLTypeUtil.unwrapNonNull(field.getType());
            if (!(fieldType instanceof GraphQLEnumType enumType)) continue;
            boolean isSortEnum = enumType.getValues().stream()
                .anyMatch(v -> v.hasAppliedDirective("order") || v.hasAppliedDirective("index"));
            if (isSortEnum) {
                if (sortFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        "@orderBy input type '" + typeName + "' must have exactly one sort enum field, but found multiple");
                }
                sortFieldName = field.getName();
            } else {
                if (directionFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        "@orderBy input type '" + typeName + "' must have exactly one direction field, but found multiple");
                }
                directionFieldName = field.getName();
            }
        }
        if (sortFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy input type '" + typeName + "' has no sort enum field (no enum values with @order)");
        }
        if (directionFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy input type '" + typeName + "' has no direction field");
        }
        return new ArgumentRef.OrderByArg(name, typeName, nonNull, list, sortFieldName, directionFieldName);
    }

    /**
     * Runs the full filter / orderBy / pagination projection for a table-bound field, using
     * {@link #classifyArguments} output as the single source of truth about each argument.
     * Replaces the legacy three-pass model ({@code buildFilters} / {@code buildOrderBySpec} /
     * {@code buildPaginationSpec}) with one classification + one projection step. See
     * {@code docs/argument-resolution.md}.
     */
    private TableFieldComponents projectForFilter(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef,
                                                  TableRef rt, String returnTypeName, List<String> errors) {
        var filters = projectFilters(refs, fieldDef, rt, returnTypeName, errors);
        if (filters == null) return TableFieldComponents.error(String.join("; ", errors));
        var fieldCondition = buildFieldCondition(fieldDef, errors);
        if (!errors.isEmpty() && fieldCondition == null && fieldDef.hasAppliedDirective(DIR_CONDITION)) {
            return TableFieldComponents.error(String.join("; ", errors));
        }
        if (fieldCondition != null) {
            var withField = new ArrayList<>(filters);
            withField.add(fieldCondition);
            filters = List.copyOf(withField);
        }
        var orderBy = projectOrderBySpec(refs, fieldDef, rt.tableName(), errors);
        if (orderBy == null) {
            String msg = !errors.isEmpty() ? String.join("; ", errors)
                : "could not resolve @defaultOrder columns in table '" + rt.tableName() + "'";
            return TableFieldComponents.error(msg);
        }
        var lookupMapping = projectForLookup(refs, rt);
        // LookupField invariant: if the field will classify as a lookup variant (signalled by
        // @lookupKey appearing anywhere on its arguments), the mapping must have at least one
        // column. Both scalar @lookupKey args (ColumnArg) and @lookupKey on composite-key input
        // fields (TableInputArg.fieldBindings, argres Phase 3) contribute; the gate fires only
        // when every lookup-key source failed to produce a column (e.g. all fieldBindings were
        // rejected for being @reference-navigating or list-typed).
        // LookupField invariant: if any @lookupKey is present, the mapping must be non-empty.
        // NodeIdMapping is always non-empty (carries typeId + keyColumns from the NodeType).
        // ColumnMapping must have at least one column.
        boolean emptyMapping = switch (lookupMapping) {
            case ColumnMapping cm -> cm.columns().isEmpty();
            case LookupMapping.NodeIdMapping ignored -> false;
        };
        if (hasLookupKeyAnywhere(fieldDef) && emptyMapping) {
            // Prefer the specific binding-failure reason (e.g. @lookupKey on a @reference field)
            // when buildLookupBindings recorded one; fall back to the generic empty-mapping error.
            String msg = errors.isEmpty()
                ? "@lookupKey is declared but no argument resolved to a lookup column"
                : String.join("; ", errors);
            return TableFieldComponents.error(msg);
        }
        return new TableFieldComponents(filters, orderBy, projectPaginationSpec(refs, fieldDef), null, lookupMapping);
    }

    /**
     * Projects {@code @lookupKey}-bearing scalar arguments into a {@link LookupMapping} for the
     * target table. Reads only from {@link ArgumentRef.ScalarArg.ColumnArg#isLookupKey()} — the
     * classifier is the single source of truth; this projection does not re-read the SDL.
     *
     * <p>Non-lookup fields receive an empty-columns mapping (the field will still validate and
     * generate correctly via {@link GeneratedConditionFilter} or the standard filter path).
     * {@link no.sikt.graphitron.rewrite.model.LookupField} variants must have at least one
     * column; that invariant is enforced by {@link #projectForFilter} before the field is
     * constructed as a {@code LookupField} variant.
     */
    private LookupMapping projectForLookup(List<ArgumentRef> refs, TableRef targetTable) {
        // NodeIdArg with @lookupKey produces NodeIdMapping instead of the VALUES+JOIN path.
        for (var ref : refs) {
            if (ref instanceof ArgumentRef.ScalarArg.NodeIdArg na && na.isLookupKey()) {
                return new LookupMapping.NodeIdMapping(
                    na.name(), na.nodeTypeId(), na.nodeKeyColumns(), na.list(), targetTable);
            }
        }
        var columns = new ArrayList<ColumnMapping.LookupColumn>();
        for (var ref : refs) {
            switch (ref) {
                case ArgumentRef.ScalarArg.ColumnArg ca when ca.isLookupKey() ->
                    columns.add(new ColumnMapping.LookupColumn(
                        new ColumnMapping.SourcePath(List.of(ca.name())),
                        ca.column(), ca.extraction(), ca.list()));
                case ArgumentRef.InputTypeArg.TableInputArg tia -> {
                    // Each binding on a @table input type's @lookupKey field becomes one column.
                    // List cardinality is inherited from the outer argument — individual input
                    // fields are guaranteed scalar by buildLookupBindings.
                    for (var binding : tia.fieldBindings()) {
                        columns.add(new ColumnMapping.LookupColumn(
                            new ColumnMapping.SourcePath(List.of(tia.name(), binding.inputFieldName())),
                            binding.targetColumn(), binding.extraction(), tia.list()));
                    }
                }
                default -> {}
            }
        }
        return new ColumnMapping(List.copyOf(columns), targetTable);
    }

    /**
     * Resolves the Java type that a {@link BodyParam} must carry given its extraction strategy
     * and target column. Extracted from the old {@code buildFilters} switch so projection can
     * derive {@link BodyParam#javaType} without re-classifying the argument.
     */
    private static String javaTypeFor(CallSiteExtraction extraction, ColumnRef column) {
        return switch (extraction) {
            case CallSiteExtraction.EnumValueOf ev -> ev.enumClassName();
            case CallSiteExtraction.TextMapLookup ignored -> String.class.getName();
            case CallSiteExtraction.JooqConvert ignored -> column.columnClass();
            case CallSiteExtraction.Direct ignored -> column.columnClass();
            case CallSiteExtraction.ContextArg ignored -> column.columnClass();
            case CallSiteExtraction.NestedInputField ignored -> column.columnClass();
        };
    }

    /**
     * Projects the classified arguments into a {@link WhereFilter} list for a table-bound field.
     *
     * <p>{@link ArgumentRef.OrderByArg} and {@link ArgumentRef.PaginationArgRef} are skipped
     * (handled by {@link #projectOrderBySpec} / {@link #projectPaginationSpec}).
     * {@link ArgumentRef.UnclassifiedArg} and {@link ArgumentRef.ScalarArg.UnboundArg} add to
     * {@code errors}. For {@link ArgumentRef.InputTypeArg} variants the arg-level, input-field-level
     * {@code @condition} predicates and (for {@code @table} inputs) implicit column-equality
     * predicates on un-annotated fields are emitted via {@link #walkInputFieldConditions}.
     * Returns {@code null} when any filter classification fails.
     *
     * <p>All column-bound scalar args and implicit column-equality predicates from {@code @table}
     * input fields are grouped into a single {@link GeneratedConditionFilter} entry. The condition
     * class is named {@code <returnTypeName>Conditions} and the method {@code <fieldName>Condition}.
     */
    private List<WhereFilter> projectFilters(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef,
                                             TableRef rt, String returnTypeName, List<String> errors) {
        var bodyParams = new ArrayList<BodyParam>();
        var argConditions = new ArrayList<ConditionFilter>();
        boolean hadError = false;
        var fieldCond = ctx.readConditionDirective(fieldDef);
        boolean fieldOverride = fieldCond != null && fieldCond.override();
        for (var ref : refs) {
            switch (ref) {
                case ArgumentRef.OrderByArg ignored -> {}                     // handled by projectOrderBySpec
                case ArgumentRef.PaginationArgRef ignored -> {}               // handled by projectPaginationSpec
                case ArgumentRef.InputTypeArg.TableInputArg tia -> {
                    // Arg-level @condition and field-level @condition predicates.
                    tia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                    // Implicit predicates are suppressed when any ancestor carries override:true.
                    boolean enclosingOverride = fieldOverride
                        || tia.argCondition().map(ArgConditionRef::override).orElse(false);
                    var lookupBoundNames = tia.fieldBindings().stream()
                        .map(InputColumnBinding::inputFieldName)
                        .collect(Collectors.toUnmodifiableSet());
                    var implicitParams = new ArrayList<BodyParam>();
                    walkInputFieldConditions(tia.fields(), tia.name(), List.of(),
                        enclosingOverride, lookupBoundNames, implicitParams, argConditions);
                    bodyParams.addAll(implicitParams);
                }
                case ArgumentRef.InputTypeArg.PlainInputArg pia -> {
                    // Plain input types are silently skipped unless paired with @condition;
                    // see the out-of-scope note in docs/argument-resolution.md.
                    pia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                    walkInputFieldConditions(pia.fields(), pia.name(), List.of(),
                        false, Set.of(), null, argConditions);
                }
                case ArgumentRef.UnclassifiedArg u -> {
                    errors.add("argument '" + u.name() + "': " + u.reason());
                    hadError = true;
                }
                case ArgumentRef.ScalarArg.UnboundArg u -> {
                    errors.add("argument '" + u.name() + "': " + u.reason());
                    hadError = true;
                }
                case ArgumentRef.ScalarArg.ColumnArg ca -> {
                    boolean autoSuppressed = ca.suppressedByFieldOverride()
                        || (ca.argCondition().isPresent() && ca.argCondition().get().override());
                    // Lookup-key args are consumed by projectForLookup → LookupMapping and
                    // emitted via VALUES+JOIN by LookupValuesJoinEmitter. They must not appear
                    // as GeneratedConditionFilter bodyParams (per docs/argument-resolution.md Phase 1).
                    if (!autoSuppressed && !ca.isLookupKey()) {
                        String javaType = javaTypeFor(ca.extraction(), ca.column());
                        bodyParams.add(new BodyParam.ColumnEq(ca.name(), ca.column(), javaType, ca.nonNull(), ca.list(), ca.extraction()));
                    }
                    ca.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.ScalarArg.NodeIdArg na -> {
                    // Lookup-key NodeIdArg is consumed by projectForLookup → NodeIdMapping;
                    // non-lookup NodeIdArg filter emission is not yet implemented (Step 4 follow-up).
                    na.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
            }
        }
        if (hadError) return null;

        var filters = new ArrayList<WhereFilter>();
        if (!bodyParams.isEmpty()) {
            String conditionsClassName = ctx.ctx().outputPackage() + ".conditions." + returnTypeName + "Conditions";
            String methodName = fieldDef.getName() + "Condition";
            var callParams = bodyParams.stream()
                .map(bp -> new CallParam(bp.name(), bp.extraction(), bp.list(), bp.javaType()))
                .toList();
            filters.add(new GeneratedConditionFilter(conditionsClassName, methodName, rt, callParams, List.copyOf(bodyParams)));
        }
        filters.addAll(argConditions);
        return List.copyOf(filters);
    }

    /**
     * Recursively collects explicit {@code @condition} filters and implicit column-equality
     * predicates from a list of {@link InputField}s.
     *
     * <p><b>Explicit conditions</b> — {@link InputField.ColumnField},
     * {@link InputField.ColumnReferenceField}, and {@link InputField.NestingField} all carry an
     * optional {@code condition}. When present, the filter is rewrapped with a
     * {@link CallSiteExtraction.NestedInputField} extraction and added to {@code out}.
     *
     * <p><b>Implicit conditions</b> — when {@code implicitBodyParams} is non-null (i.e. the
     * input is a {@code @table}-annotated {@link ArgumentRef.InputTypeArg.TableInputArg}), every
     * un-annotated {@link InputField.ColumnField} and {@link InputField.ColumnReferenceField}
     * that carries no {@code @condition} annotation, is not already consumed by a {@code @lookupKey}
     * binding, and is not suppressed by an enclosing {@code override: true}, gets an implicit
     * column-equality predicate — a {@link BodyParam} with a
     * {@link CallSiteExtraction.NestedInputField} extraction — added to {@code implicitBodyParams}.
     * Fields that carry an explicit {@code @condition} (any override value) never also emit an
     * implicit predicate.
     *
     * <p>{@code enclosingOverride} propagates the override flag down through
     * {@link InputField.NestingField} children: once set to {@code true} it stays {@code true}
     * for all descendants, suppressing their implicit predicates.
     *
     * <p>{@code lookupBoundNames} is the set of field names already consumed as
     * {@code @lookupKey} bindings on the enclosing {@code TableInputArg}. These must not also
     * emit an implicit condition (the VALUES+JOIN path owns them).
     *
     * <p>{@code outerArgName} is the top-level field-argument name (e.g. {@code "filter"}).
     * {@code pathPrefix} is the list of Map keys from {@code outerArgName} down to the parent of
     * {@code fields}; empty at the top level.
     */
    private void walkInputFieldConditions(
            List<InputField> fields, String outerArgName, List<String> pathPrefix,
            boolean enclosingOverride, Set<String> lookupBoundNames,
            List<BodyParam> implicitBodyParams,
            List<ConditionFilter> out) {
        for (var f : fields) {
            var leafPath = new ArrayList<>(pathPrefix);
            leafPath.add(f.name());
            switch (f) {
                case InputField.ColumnField cf -> {
                    cf.condition().ifPresent(c -> out.add(rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (implicitBodyParams != null && !enclosingOverride
                            && cf.condition().isEmpty()
                            && !lookupBoundNames.contains(cf.name())) {
                        implicitBodyParams.add(implicitBodyParam(
                            cf.column(), cf.name(), cf.typeName(), cf.nonNull(), outerArgName, leafPath));
                    }
                }
                case InputField.ColumnReferenceField rf -> {
                    rf.condition().ifPresent(c -> out.add(rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (implicitBodyParams != null && !enclosingOverride
                            && rf.condition().isEmpty()
                            && !lookupBoundNames.contains(rf.name())) {
                        implicitBodyParams.add(implicitBodyParam(
                            rf.column(), rf.name(), rf.typeName(), rf.nonNull(), outerArgName, leafPath));
                    }
                }
                case InputField.NestingField nf -> {
                    nf.condition().ifPresent(c -> out.add(rewrapForNested(c.filter(), outerArgName, leafPath)));
                    boolean nestOverride = enclosingOverride
                        || nf.condition().map(ArgConditionRef::override).orElse(false);
                    walkInputFieldConditions(nf.fields(), outerArgName, leafPath,
                        nestOverride, lookupBoundNames, implicitBodyParams, out);
                }
                case InputField.NodeIdField ignored -> {}
                case InputField.NodeIdReferenceField ignored -> {}
                case InputField.IdReferenceField ignored -> {}
                case InputField.NodeIdInFilterField nf -> {
                    // The body always guards `arg == null || arg.isEmpty()`, so the outer-list
                    // nullability ([ID!] vs [ID!]!) does not matter for the emitted predicate.
                    if (implicitBodyParams != null && !enclosingOverride
                            && !lookupBoundNames.contains(nf.name())) {
                        implicitBodyParams.add(new BodyParam.NodeIdIn(
                            nf.name(),
                            nf.nodeTypeId(),
                            nf.nodeKeyColumns(),
                            /* nonNull */ false,
                            new CallSiteExtraction.NestedInputField(outerArgName, leafPath)));
                    }
                }
            }
        }
    }

    /**
     * Builds a {@link BodyParam} for an implicit column-equality predicate on a {@code @table}
     * input field. The extraction is {@link CallSiteExtraction.NestedInputField} so the fetcher
     * call site traverses the argument Map to reach the leaf value. GraphQL {@code ID} scalars
     * are delivered as {@code String} by graphql-java; all other types use the column's own
     * Java class.
     */
    private static BodyParam implicitBodyParam(ColumnRef column, String fieldName,
                                               String graphqlTypeName, boolean nonNull,
                                               String outerArgName, List<String> leafPath) {
        String javaType = "ID".equals(graphqlTypeName) ? String.class.getName() : column.columnClass();
        return new BodyParam.ColumnEq(fieldName, column, javaType, nonNull, false,
            new CallSiteExtraction.NestedInputField(outerArgName, leafPath));
    }

    /**
     * Rebuilds a {@link ConditionFilter} whose {@link ParamSource.Arg} params need to be extracted
     * from a nested position inside the enclosing input argument Map rather than from a top-level
     * argument. Each {@code Arg} param's {@link CallSiteExtraction} is replaced with a
     * {@link CallSiteExtraction.NestedInputField} carrying the path down from {@code outerArgName}
     * to the leaf value. {@link ParamSource.Context} params and implicit
     * {@link ParamSource.Table} params are left untouched.
     */
    private ConditionFilter rewrapForNested(ConditionFilter src, String outerArgName, List<String> leafPath) {
        var rewritten = new ArrayList<MethodRef.Param>();
        for (var p : src.params()) {
            if (p.source() instanceof ParamSource.Arg) {
                rewritten.add(new MethodRef.Param.Typed(p.name(), p.typeName(),
                    new ParamSource.Arg(new CallSiteExtraction.NestedInputField(outerArgName, leafPath))));
            } else {
                rewritten.add(p);
            }
        }
        return new ConditionFilter(src.className(), src.methodName(), List.copyOf(rewritten));
    }

    /**
     * Post-processes {@link ParamSource.Arg} parameters on a method reference to detect
     * text-mapped enum arguments. {@link no.sikt.graphitron.rewrite.ServiceCatalog} handles jOOQ
     * enum detection (requires reflection); this method handles text-mapped enums (requires the
     * GraphQL schema, which only {@link FieldBuilder} holds).
     *
     * <p>A parameter is text-mapped when its Java type is {@code String} (already defaulted to
     * {@link CallSiteExtraction.Direct} by {@code ServiceCatalog}) and the corresponding GraphQL
     * argument type is an enum with value mappings. The enriched extraction emits a static-map
     * lookup that delivers the DB string to the service method — service code does not know about
     * GraphQL enum value names.
     *
     * <p>The generated static map field lives in the {@code *Fetchers} class for this type.
     */
    MethodRef enrichArgExtractions(MethodRef method, GraphQLFieldDefinition fieldDef) {
        var argTypes = fieldDef.getArguments().stream()
            .collect(java.util.stream.Collectors.toMap(
                GraphQLArgument::getName,
                a -> ((graphql.schema.GraphQLNamedType) graphql.schema.GraphQLTypeUtil.unwrapAll(a.getType())).getName()));
        var newParams = method.params().stream().map(p -> {
            if (!(p.source() instanceof ParamSource.Arg arg)) return p;
            if (!(arg.extraction() instanceof CallSiteExtraction.Direct)) return p;
            if (!String.class.getName().equals(p.typeName())) return p;
            String graphqlTypeName = argTypes.get(p.name());
            if (graphqlTypeName == null) return p;
            var textMapping = buildTextEnumMapping(graphqlTypeName);
            if (textMapping == null) return p;
            String mapFieldName = fieldDef.getName().toUpperCase() + "_"
                + p.name().toUpperCase() + "_MAP";
            return (MethodRef.Param) new MethodRef.Param.Typed(p.name(), p.typeName(),
                new ParamSource.Arg(new CallSiteExtraction.TextMapLookup(mapFieldName, textMapping)));
        }).toList();
        return new MethodRef.Basic(method.className(), method.methodName(),
            method.returnType(), newParams);
    }

    /**
     * If the GraphQL type is an enum, builds a mapping from GraphQL enum value names to database
     * string values (from {@code @field(name:)} or the value name itself). Returns {@code null}
     * when the GraphQL type is not an enum.
     */
    private java.util.Map<String, String> buildTextEnumMapping(String graphqlTypeName) {
        var schemaType = ctx.schema.getType(graphqlTypeName);
        if (!(schemaType instanceof graphql.schema.GraphQLEnumType graphqlEnum)) {
            return null;
        }
        var mapping = new java.util.LinkedHashMap<String, String>();
        for (var value : graphqlEnum.getValues()) {
            String dbValue = argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName());
            mapping.put(value.getName(), dbValue);
        }
        return mapping;
    }

    /**
     * Validates that a GraphQL enum type's values match the Java enum constants of the column type.
     *
     * <p>Returns the fully qualified Java enum class name when the column is an enum and all values
     * validate. Returns {@code null} when the column is not an enum. Returns an empty string when
     * the column is an enum but validation fails (errors are appended to {@code errors}).
     */
    private String validateEnumFilter(String graphqlTypeName, ColumnRef column, java.util.List<String> errors) {
        Class<?> colClass;
        try {
            colClass = Class.forName(column.columnClass());
        } catch (ClassNotFoundException e) {
            return null; // Can't load — not an enum we can validate
        }
        if (!colClass.isEnum()) {
            return null;
        }
        // Column is a Java enum — validate GraphQL enum values
        var schemaType = ctx.schema.getType(graphqlTypeName);
        if (!(schemaType instanceof graphql.schema.GraphQLEnumType graphqlEnum)) {
            errors.add("column '" + column.sqlName() + "' is a jOOQ enum (" + colClass.getSimpleName()
                + ") but GraphQL type '" + graphqlTypeName + "' is not an enum");
            return "";
        }
        var javaConstants = java.util.Arrays.stream(colClass.getEnumConstants())
            .map(c -> ((Enum<?>) c).name())
            .collect(java.util.stream.Collectors.toSet());
        var mismatches = new java.util.ArrayList<String>();
        for (var value : graphqlEnum.getValues()) {
            String target = argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName());
            if (!javaConstants.contains(target)) {
                mismatches.add("'" + value.getName() + "'" + (target.equals(value.getName()) ? "" : " (mapped to '" + target + "')")
                    + candidateHint(target, new java.util.ArrayList<>(javaConstants)));
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
     * Projects the classified arguments into a {@link PaginationSpec} for a list/connection field.
     * Returns {@code null} when no pagination arguments are present and {@code @asConnection} is
     * not declared on the field.
     */
    private PaginationSpec projectPaginationSpec(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef) {
        PaginationSpec.PaginationArg first = null, last = null, after = null, before = null;
        for (var ref : refs) {
            if (!(ref instanceof ArgumentRef.PaginationArgRef p)) continue;
            var paginationArg = new PaginationSpec.PaginationArg(p.name(), p.typeName(), p.nonNull());
            switch (p.role()) {
                case FIRST  -> first  = paginationArg;
                case LAST   -> last   = paginationArg;
                case AFTER  -> after  = paginationArg;
                case BEFORE -> before = paginationArg;
            }
        }

        // @asConnection without explicit pagination args: synthesize forward-pagination defaults
        if (first == null && last == null && after == null && before == null
                && fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)) {
            first = new PaginationSpec.PaginationArg("first", "Int", false);
            after = new PaginationSpec.PaginationArg("after", "String", false);
        }

        if (first == null && last == null && after == null && before == null) return null;
        return new PaginationSpec(first, last, after, before);
    }

    private static boolean isPaginationArg(String argName) {
        return "first".equals(argName) || "last".equals(argName)
            || "after".equals(argName) || "before".equals(argName);
    }

    private static int resolveDefaultFirstValue(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_AS_CONNECTION);
        if (dir == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        var arg = dir.getArgument(ARG_DEFAULT_FIRST_VALUE);
        if (arg == null || arg.getValue() == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        Object val = arg.getValue();
        if (val instanceof graphql.language.IntValue iv) return iv.getValue().intValueExact();
        if (val instanceof Number n) return n.intValue();
        return FieldWrapper.DEFAULT_PAGE_SIZE;
    }

    // ===== Field classification =====

    GraphitronField classifyField(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // @notGenerated is no longer supported. Reject any application before conflict detection
        // so the user sees the no-longer-supported reason rather than a misleading "conflict with
        // @service" message when both directives are present.
        if (fieldDef.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");
        }

        // Detect conflicts among the child-field exclusive directives before the
        // @multitableReference early-return; that return would otherwise silently mask a
        // conflicting directive on the same field.
        if (!(parentType instanceof RootType)) {
            String conflict = detectChildFieldConflict(fieldDef);
            if (conflict != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA, conflict);
            }
        }

        if (fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE)) {
            return new MultitableReferenceField(parentTypeName, name, location);
        }

        if (parentType instanceof RootType rootType) {
            return classifyRootField(fieldDef, parentTypeName);
        }
        if (parentType instanceof TableBackedType tbt && !(parentType instanceof TableInterfaceType)) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tbt, Set.of());
        }
        if (parentType instanceof ResultType resultType) {
            return classifyChildFieldOnResultType(fieldDef, parentTypeName, resultType);
        }
        if (parentType instanceof ErrorType) {
            return classifyChildFieldOnErrorType(fieldDef, parentTypeName);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "parent type is unclassified");
    }

    private GraphitronField classifyChildFieldOnErrorType(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        if (isScalarOrEnum(fieldDef)) {
            return new PropertyField(parentTypeName, name, location, name, null);
        }
        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
            "fields on @error types must be scalar or enum");
    }

    // ===== Root field classification (P5) =====

    private GraphitronField classifyRootField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        if (parentTypeName.equals("Mutation")) {
            return classifyMutationField(fieldDef, parentTypeName);
        }
        if (parentTypeName.equals("Query")) {
            return classifyQueryField(fieldDef, parentTypeName);
        }
        return new UnclassifiedField(parentTypeName, fieldDef.getName(), locationOf(fieldDef), fieldDef, RejectionKind.DEFERRED,
            "fields on '" + parentTypeName + "' (Subscription is not supported)");
    }

    private GraphitronField classifyQueryField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        String conflict = detectQueryFieldConflict(fieldDef);
        if (conflict != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA, conflict);
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(parentTypeName, fieldDef, List.of());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, svcResult.error());
            }
            String invariant = validateRootServiceInvariants(svcResult);
            if (invariant != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA, invariant);
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new QueryField.QueryServiceTableField(parentTypeName, name, location, tb, svcResult.method());
                case ReturnTypeRef.ResultReturnType r ->
                    new QueryField.QueryServiceRecordField(parentTypeName, name, location, r, svcResult.method());
                case ReturnTypeRef.ScalarReturnType s ->
                    new QueryField.QueryServiceRecordField(parentTypeName, name, location, s, svcResult.method());
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (name.equals("node")) {
            return new QueryField.QueryNodeField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (name.equals("nodes")) {
            return new QueryField.QueryNodesField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (hasLookupKeyAnywhere(fieldDef)) {
            String lookupTypeName = baseTypeName(fieldDef);
            var returnType = ctx.resolveReturnType(lookupTypeName, buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "@lookupKey requires a @table-annotated return type");
            }
            var tfc = resolveTableFieldComponents(fieldDef, tb.table(), lookupTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tfc.error());
            return new QueryField.QueryLookupTableField(parentTypeName, name, location, tb, tfc.filters(), tfc.orderBy(), tfc.pagination(),
                tfc.lookupMapping());
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "@tableMethod requires a @table-annotated return type");
            }
            // Invariants §1: Connection wrapper not supported on @tableMethod at root.
            if (tb.wrapper() instanceof FieldWrapper.Connection) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                    "@tableMethod at the root does not support Connection return types — use [T] or T instead");
            }
            var qtmRef = parseExternalRef(parentTypeName, fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
            if (qtmRef != null && qtmRef.lookupError() != null) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "table method could not be resolved — " + qtmRef.lookupError());
            }
            Set<String> qtmArgNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            List<String> qtmCtxArgs = parseContextArguments(fieldDef, DIR_TABLE_METHOD);
            // Invariants §3 (return-type strictness): the developer's @tableMethod must return
            // the generated jOOQ table class exactly, not a wider Table<R>. Computed from the
            // resolved @table-bound return type's TableRef + the build-context jOOQ package.
            ClassName expectedReturnClass = ClassName.get(
                ctx.ctx().jooqPackage() + ".tables", tb.table().javaClassName());
            var qtmResult = svc.reflectTableMethod(
                qtmRef != null ? qtmRef.className() : null,
                qtmRef != null ? qtmRef.methodName() : null,
                qtmArgNames, new java.util.HashSet<>(qtmCtxArgs),
                expectedReturnClass);
            if (qtmResult.failed()) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "table method could not be resolved — " + qtmResult.failureReason());
            }
            return new QueryField.QueryTableMethodTableField(parentTypeName, name, location, tb, enrichArgExtractions(qtmResult.ref(), fieldDef));
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        GraphitronType elementType = ctx.types.get(elementTypeName);

        if (elementType instanceof TableBackedType tbt && !(elementType instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = (ReturnTypeRef.TableBoundReturnType) ctx.resolveReturnType(elementTypeName, wrapper);
            var tfc = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tfc.error());
            return new QueryField.QueryTableField(parentTypeName, name, location, returnType, tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var tfc = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tfc.error());
            var knownValues = knownDiscriminatorValues(tableInterfaceType);
            return new QueryField.QueryTableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper),
                tableInterfaceType.discriminatorColumn(), knownValues, tableInterfaceType.participants(),
                tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (elementType instanceof InterfaceType interfaceType) {
            return new QueryField.QueryInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }
        if (elementType instanceof UnionType unionType) {
            return new QueryField.QueryUnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "return type '" + elementTypeName + "' is not a @table, interface, or union Graphitron type; " +
            "@service, @lookupKey, and @tableMethod are all absent");
    }

    private GraphitronField classifyMutationField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE) && fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                "@" + DIR_SERVICE + ", @" + DIR_MUTATION + " are mutually exclusive");
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(parentTypeName, fieldDef, List.of());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, svcResult.error());
            }
            String invariant = validateRootServiceInvariants(svcResult);
            if (invariant != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA, invariant);
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new MutationField.MutationServiceTableField(parentTypeName, name, location, tb, svcResult.method());
                case ReturnTypeRef.ResultReturnType r ->
                    new MutationField.MutationServiceRecordField(parentTypeName, name, location, r, svcResult.method());
                case ReturnTypeRef.ScalarReturnType s ->
                    new MutationField.MutationServiceRecordField(parentTypeName, name, location, s, svcResult.method());
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            String typeName = getMutationTypeName(fieldDef);
            if (typeName != null) {
                String rawReturn = baseTypeName(fieldDef);
                ReturnTypeRef returnType = ctx.resolveReturnType(rawReturn, buildWrapper(fieldDef));
                return switch (typeName) {
                    case "INSERT" -> new MutationField.MutationInsertTableField(parentTypeName, name, location, returnType);
                    case "UPDATE" -> new MutationField.MutationUpdateTableField(parentTypeName, name, location, returnType);
                    case "DELETE" -> new MutationField.MutationDeleteTableField(parentTypeName, name, location, returnType);
                    case "UPSERT" -> new MutationField.MutationUpsertTableField(parentTypeName, name, location, returnType);
                    default       -> new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "unknown @mutation(typeName:) value '" + typeName + "'");
                };
            }
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "@" + DIR_SERVICE + " and @" + DIR_MUTATION + " are both absent on this mutation field");
    }

    /**
     * Returns {@code true} when {@code @lookupKey} appears on any direct argument of the field,
     * or on any field within an input-type argument (recursively). This is the field-level
     * classification signal — which specific argument carries it has no semantic significance.
     */
    private boolean hasLookupKeyAnywhere(GraphQLFieldDefinition fieldDef) {
        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_LOOKUP_KEY)) return true;
            String argTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName();
            if (ctx.schema.getType(argTypeName) instanceof GraphQLInputObjectType inputType) {
                if (inputTypeHasLookupKey(inputType, 0)) return true;
            }
        }
        return false;
    }

    private boolean inputTypeHasLookupKey(GraphQLInputObjectType inputType, int depth) {
        if (depth > 10) return false; // guard against pathological nesting
        for (var field : inputType.getFieldDefinitions()) {
            if (field.hasAppliedDirective(DIR_LOOKUP_KEY)) return true;
            String fieldTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(field.getType())).getName();
            if (ctx.schema.getType(fieldTypeName) instanceof GraphQLInputObjectType nested) {
                if (inputTypeHasLookupKey(nested, depth + 1)) return true;
            }
        }
        return false;
    }

    private String getMutationTypeName(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_MUTATION);
        if (dir == null) return null;
        var arg = dir.getArgument(ARG_TYPE_NAME);
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof EnumValue ev) return ev.getName();
        if (value instanceof String s) return s;
        return null;
    }

    // ===== Conflict detection helpers =====
    // Each method returns a human-readable reason string when mutually exclusive directives are
    // found together, or {@code null} when no conflict exists. Callers produce an
    // {@link UnclassifiedField} or {@link GraphitronType.UnclassifiedType} carrying the reason,
    // which the validator then reports as a standard error.


    /**
     * Returns a reason string when mutually exclusive child-field classification directives appear
     * together, or {@code null} when at most one exclusive slot is occupied.
     *
     * <p>Note: {@code @reference} is a path-annotation directive, not a classification directive —
     * it may be combined with {@code @service}, {@code @externalField}, {@code @tableMethod}, and
     * {@code @tableField} (as a FK reference path) or with {@code @nodeId} (producing
     * {@link NodeIdReferenceField}). It is therefore not included in this check.
     */
    private String detectChildFieldConflict(GraphQLFieldDefinition fieldDef) {
        boolean hasMultitable    = fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE);
        boolean hasService       = fieldDef.hasAppliedDirective(DIR_SERVICE);
        boolean hasExternalField = fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD);
        boolean hasTableMethod   = fieldDef.hasAppliedDirective(DIR_TABLE_METHOD);
        boolean hasNodeId        = fieldDef.hasAppliedDirective(DIR_NODE_ID);

        int slots = (hasMultitable    ? 1 : 0)
                  + (hasService       ? 1 : 0)
                  + (hasExternalField ? 1 : 0)
                  + (hasTableMethod   ? 1 : 0)
                  + (hasNodeId        ? 1 : 0);

        if (slots <= 1) return null;

        var names = new ArrayList<String>();
        if (hasMultitable)    names.add("@" + DIR_MULTITABLE_REFERENCE);
        if (hasService)       names.add("@" + DIR_SERVICE);
        if (hasExternalField) names.add("@" + DIR_EXTERNAL_FIELD);
        if (hasTableMethod)   names.add("@" + DIR_TABLE_METHOD);
        if (hasNodeId)        names.add("@" + DIR_NODE_ID);
        return String.join(", ", names) + " are mutually exclusive";
    }

    /**
     * Returns a reason string when mutually exclusive query-field directives appear together
     * ({@code @service}, {@code @lookupKey} on arguments, {@code @tableMethod}), or {@code null}.
     */
    private String detectQueryFieldConflict(GraphQLFieldDefinition fieldDef) {
        boolean hasService     = fieldDef.hasAppliedDirective(DIR_SERVICE);
        boolean hasLookupKey   = hasLookupKeyAnywhere(fieldDef);
        boolean hasTableMethod = fieldDef.hasAppliedDirective(DIR_TABLE_METHOD);

        int slots = (hasService     ? 1 : 0)
                  + (hasLookupKey   ? 1 : 0)
                  + (hasTableMethod ? 1 : 0);

        if (slots <= 1) return null;

        var names = new ArrayList<String>();
        if (hasService)     names.add("@" + DIR_SERVICE);
        if (hasLookupKey)   names.add("@" + DIR_LOOKUP_KEY);
        if (hasTableMethod) names.add("@" + DIR_TABLE_METHOD);
        return String.join(", ", names) + " are mutually exclusive";
    }

    private GraphitronField classifyChildFieldOnResultType(GraphQLFieldDefinition fieldDef, String parentTypeName,
            ResultType parentResultType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(parentTypeName, fieldDef, List.of());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, svcResult.error());
            }
            var servicePath = ctx.parsePath(fieldDef, name, null, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, servicePath.errorMessage());
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), List.of(), new OrderBySpec.None(), null,
                        svcResult.method(), extractBatchKey(svcResult.method()));
                case ReturnTypeRef.ResultReturnType r ->
                    new ServiceRecordField(parentTypeName, name, location, r, servicePath.elements(), svcResult.method());
                case ReturnTypeRef.ScalarReturnType s ->
                    new ServiceRecordField(parentTypeName, name, location, s, servicePath.elements(), svcResult.method());
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (isScalarOrEnum(fieldDef)) {
            String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
                ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
                : name;
            return new PropertyField(parentTypeName, name, location, columnName,
                resolveColumnOnJooqTableRecord(columnName, parentResultType));
        }

        // Object return type on a result-mapped parent.
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        // Typed @record parents backed by a jOOQ TableRecord anchor the path's starting table,
        // which is how parsePath validates FK direction on each hop. Without it, multi-hop paths
        // through junction tables (e.g. film → film_actor → actor) flip the first hop's traversal
        // direction and spuriously fail to resolve.
        String parentSqlTableName = parentResultType instanceof GraphitronType.JooqTableRecordType jtr && jtr.table() != null
            ? jtr.table().tableName() : null;
        var resolvedReturnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
        String targetSqlTableName = resolvedReturnType instanceof ReturnTypeRef.TableBoundReturnType tbt
            ? tbt.table().tableName() : null;
        var objectPath = ctx.parsePath(fieldDef, name, parentSqlTableName, targetSqlTableName);
        if (objectPath.hasError()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, objectPath.errorMessage());
        }
        return switch (resolvedReturnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                var tfc = resolveTableFieldComponents(fieldDef, tb.table(), elementTypeName);
                if (tfc.error() != null) yield new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tfc.error());
                var batchKey = deriveBatchKeyForResultType(objectPath.elements(), parentResultType);
                if (hasLookupKeyAnywhere(fieldDef)) {
                    if (batchKey == null) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                            "RecordLookupTableField requires a FK join path and a typed backing class for batch key extraction");
                    }
                    yield new RecordLookupTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                        batchKey, tfc.lookupMapping());
                }
                if (batchKey == null) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                        "RecordTableField requires a FK join path and a typed backing class for batch key extraction");
                }
                yield new RecordTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), batchKey);
            }
            case ReturnTypeRef.ResultReturnType r ->
                new RecordField(parentTypeName, name, location, r, columnName,
                    resolveColumnOnJooqTableRecord(columnName, parentResultType));
            case ReturnTypeRef.ScalarReturnType s ->
                new RecordField(parentTypeName, name, location, s, columnName,
                    resolveColumnOnJooqTableRecord(columnName, parentResultType));
            case ReturnTypeRef.PolymorphicReturnType p ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED, "@record type returning a polymorphic type is not yet supported");
        };
    }

    /**
     * Resolves the parent-table {@link ColumnRef} for a {@code PropertyField} or {@code RecordField}
     * when the parent is a {@link GraphitronType.JooqTableRecordType} whose backing jOOQ table
     * resolves in the catalog and contains a column with the given SQL name. Returns {@code null}
     * for all other {@link GraphitronType.ResultType} variants, when the {@code JooqTableRecordType}
     * has a {@code null} {@code TableRef} (backing class not loaded at build time), or when the
     * SQL column name doesn't match any column on the resolved table.
     *
     * <p>A non-null result unlocks typed {@code Tables.X.COL} emission in
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator};
     * a null result falls back to untyped {@code DSL.field("col_name")} access.
     */
    private ColumnRef resolveColumnOnJooqTableRecord(String columnName, GraphitronType.ResultType parentResultType) {
        if (!(parentResultType instanceof GraphitronType.JooqTableRecordType jtrt)) return null;
        if (jtrt.table() == null) return null;
        return svc.resolveColumnInTable(columnName, jtrt.table().tableName()).orElse(null);
    }

    /**
     * Derives the {@link BatchKey} for a {@code @table}-parent {@code @splitQuery} field. Single
     * cardinality keys by the parent's FK columns (parent-holds-FK); list cardinality keys by the
     * parent's PK. The direction signal is cardinality alone — the {@code @splitQuery} schema
     * contract ties Single ⇒ parent-holds-FK and List ⇒ child-holds-FK, so no table-identity
     * comparison is needed. The caller enforces the single-hop precondition; this helper only
     * picks the keying strategy and is safe to call with any path shape (multi-hop single
     * cardinality falls through to parent-PK, but the classifier rejects it upstream).
     *
     * <p>Sibling of {@link #deriveBatchKeyForResultType} — that helper is for record parents and
     * unconditionally uses {@code fk.sourceColumns()} because record parents never batch by
     * parent PK.
     */
    private static BatchKey deriveSplitQueryBatchKey(TableRef parentTable, List<JoinStep> path, boolean isList) {
        if (!isList && !path.isEmpty() && path.get(0) instanceof JoinStep.FkJoin fk) {
            return new BatchKey.RowKeyed(fk.sourceColumns());
        }
        return new BatchKey.RowKeyed(parentTable.primaryKeyColumns());
    }

    /**
     * Derives the {@link BatchKey} for a record-parent batched field
     * ({@link no.sikt.graphitron.rewrite.model.ChildField.RecordTableField},
     * {@link no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField}) by reading the FK
     * source columns from the join path's first {@link JoinStep.FkJoin} step.
     *
     * <p>Returns {@code null} (→ {@link GraphitronField.UnclassifiedField}) when:
     * <ul>
     *   <li>the join path is empty or its first step is not an {@link JoinStep.FkJoin}</li>
     *   <li>the parent is an untyped {@link GraphitronType.PojoResultType} with a {@code null} class
     *       (cannot generate a typed cast for key extraction)</li>
     * </ul>
     */
    private static BatchKey deriveBatchKeyForResultType(
            List<JoinStep> joinPath, GraphitronType.ResultType parentResultType) {
        if (joinPath.isEmpty() || !(joinPath.get(0) instanceof JoinStep.FkJoin fkJoin)) {
            return null;
        }
        if (parentResultType instanceof GraphitronType.PojoResultType prt && prt.fqClassName() == null) {
            return null;
        }
        return new BatchKey.RowKeyed(fkJoin.sourceColumns());
    }

    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "column-field-requires-table-backed-parent",
        description = "ChildField.ColumnField is constructed only inside this method (sole "
            + "construction site at the tail return), and this method is only entered for "
            + "parents that are table-backed types. Lets the TypeFetcherGenerator switch arm "
            + "treat parentTable == null as a classifier-invariant violation rather than an "
            + "expected runtime branch.")
    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType tableType, Set<String> expandingTypes) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(parentTypeName, fieldDef, tableType.table().primaryKeyColumns());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, svcResult.error());
            }
            // Service reconnect path: starts from the service return type's table (not the parent).
            var servicePath = ctx.parsePath(fieldDef, name, null, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, servicePath.errorMessage());
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), List.of(), new OrderBySpec.None(), null,
                        svcResult.method(), extractBatchKey(svcResult.method()));
                case ReturnTypeRef.ResultReturnType r ->
                    new ServiceRecordField(parentTypeName, name, location, r, servicePath.elements(), svcResult.method());
                case ReturnTypeRef.ScalarReturnType s ->
                    new ServiceRecordField(parentTypeName, name, location, s, servicePath.elements(), svcResult.method());
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            var externalPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (externalPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, externalPath.errorMessage());
            }
            String extTypeName = baseTypeName(fieldDef);
            return new ComputedField(parentTypeName, name, location,
                ctx.resolveReturnType(extTypeName, buildWrapper(fieldDef)),
                externalPath.elements());
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            var tableMethodPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (tableMethodPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tableMethodPath.errorMessage());
            }
            var tmRef = parseExternalRef(parentTypeName, fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
            if (tmRef != null && tmRef.lookupError() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "table method could not be resolved — " + tmRef.lookupError());
            }
            Set<String> tmArgNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            List<String> tmCtxArgs = parseContextArguments(fieldDef, DIR_TABLE_METHOD);
            // Invariants §3 (return-type strictness) — applies to child @tableMethod too.
            ClassName tmExpectedReturnClass = returnType instanceof ReturnTypeRef.TableBoundReturnType tbr
                ? ClassName.get(ctx.ctx().jooqPackage() + ".tables", tbr.table().javaClassName())
                : null;
            var tmResult = svc.reflectTableMethod(
                tmRef != null ? tmRef.className() : null,
                tmRef != null ? tmRef.methodName() : null,
                tmArgNames, new java.util.HashSet<>(tmCtxArgs),
                tmExpectedReturnClass);
            if (tmResult.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "table method could not be resolved — " + tmResult.failureReason());
            }
            return new TableMethodField(parentTypeName, name, location, returnType, tableMethodPath.elements(), enrichArgExtractions(tmResult.ref(), fieldDef));
        }

        if (!isScalarOrEnum(fieldDef)) {
            return classifyObjectReturnChildField(fieldDef, parentTypeName, tableType, expandingTypes);
        }

        if (fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                ReturnTypeRef targetType = ctx.resolveReturnType(typeName.get(), new FieldWrapper.Single(true));
                var targetGType = ctx.types.get(typeName.get());
                if (targetGType == null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not exist in the schema"
                        + candidateHint(typeName.get(), new ArrayList<>(ctx.types.keySet())));
                }
                if (!(targetGType instanceof NodeType targetNodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not have @node");
                }
                TableRef parentTable = tableType.table();
                var nodeRefPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), targetNodeType.table().tableName());
                if (nodeRefPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, nodeRefPath.errorMessage());
                }
                return new NodeIdReferenceField(parentTypeName, name, location, typeName.get(), targetType, parentTable,
                    targetNodeType.typeId(), targetNodeType.nodeKeyColumns(), nodeRefPath.elements());
            } else {
                if (!(tableType instanceof NodeType nodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "@nodeId requires the containing type to be a node type (via @node or KjerneJooqGenerator metadata)");
                }
                return new NodeIdField(parentTypeName, name, location, nodeType.typeId(), nodeType.nodeKeyColumns());
            }
        }

        boolean hasFieldDirective = fieldDef.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDirective
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        boolean javaNamePresent = hasFieldDirective
            && argString(fieldDef, DIR_FIELD, ARG_JAVA_NAME).isPresent();

        if (fieldDef.hasAppliedDirective(DIR_REFERENCE)) {
            var refPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (refPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, refPath.errorMessage());
            }
            Optional<ColumnRef> column = svc.resolveColumnForReference(columnName, refPath.elements(), tableType);
            if (column.isEmpty()) {
                String terminalTable = svc.terminalTableSqlNameForReference(refPath.elements(), tableType);
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "column '" + columnName + "' could not be resolved in the jOOQ table"
                    + (terminalTable != null ? candidateHint(columnName, ctx.catalog.columnJavaNamesOf(terminalTable)) : ""));
            }
            if (!columnName.equals(column.get().javaName())) {
                LOG.warn("@field(name: '{}') on field '{}.{}' resolved via SQL name; prefer Java field name '{}'",
                    columnName, parentTypeName, name, column.get().javaName());
            }
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column.get(), refPath.elements(), javaNamePresent);
        }

        Optional<ColumnRef> column = svc.resolveColumn(columnName, tableType);
        if (column.isEmpty()) {
            String tableSqlName = tableType.table().tableName();
            boolean isList = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType()) instanceof GraphQLList;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
            // Path 2 — migration-shim NodeIdField. `@nodeId`, `@reference`, and `@field` are
            // already excluded above (`@nodeId` by the directive check, `@reference` by its own
            // block, and `@field` via the exclusion here). Fires a per-site deprecation diagnostic
            // — the canonical form is to declare `@nodeId` explicitly. Retired at R7. See plan:
            // graphitron-rewrite/roadmap/retire-nodeid-synthesis-shim.md.
            if (tableType instanceof NodeType nodeType
                    && "ID".equals(typeName)
                    && !isList
                    && !hasFieldDirective) {
                LOG.warn("field '{}.{}' synthesizes NodeIdField without '@nodeId' — declare the"
                    + " directive explicitly; synthesis shim will be removed in a future release."
                    + " See graphitron-rewrite/roadmap/retire-nodeid-synthesis-shim.md",
                    parentTypeName, name);
                return new NodeIdField(parentTypeName, name, location,
                    nodeType.typeId(), nodeType.nodeKeyColumns());
            }
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                "column '" + columnName + "' could not be resolved in the jOOQ table"
                + candidateHint(columnName, ctx.catalog.columnJavaNamesOf(tableSqlName)));
        }
        if (!columnName.equals(column.get().javaName())) {
            LOG.warn("@field(name: '{}') on field '{}.{}' resolved via SQL name; prefer Java field name '{}'",
                columnName, parentTypeName, name, column.get().javaName());
        }
        return new ColumnField(parentTypeName, name, location, columnName, column.get(), javaNamePresent);
    }

    private boolean isScalarOrEnum(GraphQLFieldDefinition fieldDef) {
        var baseType = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
        return baseType instanceof GraphQLScalarType || baseType instanceof GraphQLEnumType;
    }

    /**
     * Parses the {@code ExternalCodeReference} input object at argument {@code argName} of the
     * given directive on {@code fieldDef} and returns a builder-private {@code ExternalRef} holding
     * the {@code className} and {@code method} strings. Returns {@code null} when the directive or
     * argument is absent.
     *
     * <p>When the reference uses the deprecated {@code name} form instead of {@code className},
     * the name is looked up in {@link RewriteContext#namedReferences()}. A deprecation warning is
     * logged per field. If the name is not in the map, the returned {@code ExternalRef} carries a
     * non-null {@link ExternalRef#lookupError()} and the {@code className} is {@code null}.
     */
    private ExternalRef parseExternalRef(String parentTypeName, GraphQLFieldDefinition fieldDef, String directiveName, String argName) {
        var dir = fieldDef.getAppliedDirective(directiveName);
        if (dir == null) return null;
        var arg = dir.getArgument(argName);
        if (arg == null) return null;
        Map<String, Object> ref = asMap(arg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String name = Optional.ofNullable(ref.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (name != null) {
                LOG.warn("ExternalCodeReference 'name' is deprecated on field '{}.{}'; use 'className' instead", parentTypeName, fieldDef.getName());
                String resolved = ctx.ctx().namedReferences().get(name);
                if (resolved != null) {
                    className = resolved;
                } else {
                    return new ExternalRef(null, methodName, "named reference '" + name + "' not found in namedReferences config");
                }
            }
        }
        return new ExternalRef(className, methodName, null);
    }

    /**
     * Returns the {@code contextArguments} list from the {@code @service} or {@code @tableMethod}
     * directive on {@code fieldDef}, or an empty list when the directive is absent or the argument
     * is not set.
     */
    private List<String> parseContextArguments(GraphQLFieldDefinition fieldDef, String directiveName) {
        return argStringList(fieldDef, directiveName, ARG_CONTEXT_ARGUMENTS);
    }

    /** Collects the non-null discriminator values from all {@link ParticipantRef.TableBound} participants. */
    private static List<String> knownDiscriminatorValues(GraphitronType.TableInterfaceType tit) {
        return tit.participants().stream()
            .filter(p -> p instanceof ParticipantRef.TableBound tb && tb.discriminatorValue() != null)
            .map(p -> ((ParticipantRef.TableBound) p).discriminatorValue())
            .toList();
    }

    /**
     * Validates that a join path for a {@link ChildField.TableInterfaceField} is a single
     * {@link JoinStep.FkJoin} step. Returns an error message if the path is multi-hop or
     * contains a {@link JoinStep.ConditionJoin}, or {@code null} if the path is valid.
     */
    private static String validateSingleHopFkJoin(List<JoinStep> path, String fieldName) {
        if (path.size() != 1) {
            return "Field '" + fieldName + "': TableInterfaceField @reference paths must be a single FK hop "
                + "(multi-hop paths are not yet supported — see stub-interface-union-fetchers.md)";
        }
        if (!(path.get(0) instanceof JoinStep.FkJoin)) {
            return "Field '" + fieldName + "': TableInterfaceField @reference paths must use a foreign key "
                + "(ConditionJoin paths are not yet supported — see stub-interface-union-fetchers.md)";
        }
        return null;
    }

    // ===== Inner records =====

    record ExternalRef(String className, String methodName, String lookupError) {}
}
