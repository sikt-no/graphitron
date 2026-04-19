package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.RewriteConfig;
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
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ChildField.ConstructorField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.PlatformIdField;
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
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField;
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
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLLATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONNECTION_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DEFAULT_FIRST_VALUE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONDITION;
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
        var result = svc.reflectServiceMethod(serviceRef.className(), serviceRef.methodName(), argNames, new java.util.HashSet<>(contextArgs), parentPkColumns);
        if (result.failed()) {
            return new ServiceResolution(null, null, "service method could not be resolved — " + result.failureReason());
        }
        return new ServiceResolution(enrichArgExtractions(result.ref(), fieldDef), returnType, null);
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
    private GraphitronField classifyObjectReturnChildField(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType parentTableType) {
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
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, referencePath.errorMessage());
            }
            var tfc = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
            boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
            boolean hasLookupKey  = hasLookupKeyAnywhere(fieldDef);
            var parentBatchKey = new BatchKey.RowKeyed(parentTableType.table().primaryKeyColumns());
            if (hasSplitQuery && hasLookupKey) {
                if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@asConnection on @splitQuery fields is not supported; per-parent pagination inside a "
                        + "DataLoader batch requires window-function partitioning and is deferred to a follow-up plan.");
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey,
                    tfc.lookupMapping());
            }
            if (!hasSplitQuery && hasLookupKey) {
                if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@asConnection on inline (non-@splitQuery) LookupTableField is not supported; add @splitQuery for batched connection semantics");
                }
                if (returnType.wrapper() instanceof FieldWrapper.Single) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "Single-cardinality @lookupKey is not supported; pass a list-returning field or drop @lookupKey");
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.LookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                    tfc.lookupMapping());
            }
            if (hasSplitQuery) {
                if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@asConnection on @splitQuery fields is not supported; per-parent pagination inside a "
                        + "DataLoader batch requires window-function partitioning and is deferred to a follow-up plan.");
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey);
            }
            if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@asConnection on inline (non-@splitQuery) TableField is not supported; add @splitQuery for batched connection semantics");
            }
            return new TableField(parentTypeName, name, location,
                returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, referencePath.errorMessage());
            }
            var tfc = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
            return new TableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper),
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

        // NestingField: a plain object type in the schema with no Graphitron classification.
        // Its fields are resolved from the same table context as the parent.
        if (ctx.schema.getType(elementTypeName) instanceof GraphQLObjectType graphQLObjectType && elementType == null) {
            var wrapper = buildWrapper(fieldDef);
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, parentTableType.table(), wrapper));
        }

        // ConstructorField: @table parent with a @record child — pass the parent's Record through as
        // the child's source. The child's own Fetchers class handles property/table-child resolution.
        if (elementType instanceof ResultType rt) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = (ReturnTypeRef.ResultReturnType) ctx.resolveReturnType(elementTypeName, wrapper);
            return new ConstructorField(parentTypeName, name, location, returnType);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
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

        // @asConnection on a list field → Connection wrapper
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION) && unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            int defaultPageSize = resolveDefaultFirstValue(fieldDef);
            String connectionName = argString(fieldDef, DIR_AS_CONNECTION, ARG_CONNECTION_NAME).orElse(null);
            return new FieldWrapper.Connection(outerNullable, itemNullable, defaultPageSize, connectionName);
        }

        if (unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            return new FieldWrapper.List(outerNullable, itemNullable);
        }

        // Structural detection: pre-expanded Connection type with edges.node pattern.
        // Pass typeName as connectionName so wiring uses the SDL type name directly
        // instead of deriving "<Parent><Field>Connection" from the field declaration.
        String typeName = baseTypeName(fieldDef);
        if (ctx.isConnectionType(typeName)) {
            boolean itemNullable = ctx.connectionItemNullable(typeName);
            return new FieldWrapper.Connection(outerNullable, itemNullable, 100, typeName);
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
     * {@code LookupMapping} happens in dedicated projector methods (step 3+), not here.
     *
     * <p>The intent of this method is to localise the "what is this argument for" decision so
     * multiple projections can read the same classification. See
     * {@code docs/argument-resolution.md}.
     *
     * <p>Step 2 scope: structural classification only. {@code @condition} on arguments is still
     * rejected by {@link #buildFilters}; this method emits {@link ArgumentRef.UnclassifiedArg}
     * for such args with a "deferred to step 4" reason. Once step 4 lands, it will populate
     * {@link ArgConditionRef} on the corresponding {@link ArgumentRef.ScalarArg.ColumnArg}
     * (or input-type variant) instead.
     *
     * <p>Errors append to {@code errors} but never cause a {@code null} return — every arg maps
     * to a variant. Variants like {@link ArgumentRef.ScalarArg.UnboundArg} and
     * {@link ArgumentRef.UnclassifiedArg} carry a {@code reason} so step 10 can turn them into
     * validation errors.
     *
     * <p>{@code rt} is the target {@link TableRef} used to resolve scalar column args; every
     * current caller passes the field's resolved table, so this method does not accept
     * {@code null}.
     */
    List<ArgumentRef> classifyArguments(GraphQLFieldDefinition fieldDef, TableRef rt, List<String> errors) {
        var fieldCondition = readConditionDirective(fieldDef);
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

        if (ctx.types.containsKey(typeName)) {
            var resolvedType = ctx.types.get(typeName);
            if (resolvedType instanceof GraphitronType.TableInputType tit) {
                // Step 2: emit structurally. fieldBindings populated in step 9.
                return new ArgumentRef.InputTypeArg.TableInputArg(
                    name, typeName, nonNull, list, tit.table(), List.of(), argCondition);
            }
            return new ArgumentRef.InputTypeArg.PlainInputArg(
                name, typeName, nonNull, list, argCondition);
        }

        // Scalar arg: bind to column
        String columnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(name);
        var col = ctx.catalog.findColumn(rt.tableName(), columnName);
        if (col.isEmpty()) {
            return new ArgumentRef.ScalarArg.UnboundArg(
                name, typeName, nonNull, list, columnName,
                "column '" + columnName + "' could not be resolved in table '" + rt.tableName() + "'"
                    + candidateHint(columnName, ctx.catalog.columnSqlNamesOf(rt.tableName())));
        }
        var columnRef = new ColumnRef(col.get().sqlName(), col.get().javaName(), col.get().columnClass());
        String enumClassName = validateEnumFilter(typeName, columnRef, errors);
        if (enumClassName != null && enumClassName.isEmpty()) {
            // Enum validation failed; error already appended. Emit UnclassifiedArg so step 10 can
            // surface the structural failure (even though the enum-value-mismatch is already an
            // error — keeping this consistent keeps the classify-never-returns-null invariant).
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "enum filter validation failed for column '" + columnRef.sqlName() + "'");
        }
        CallSiteExtraction extraction;
        if (enumClassName != null) {
            extraction = new CallSiteExtraction.EnumValueOf(enumClassName);
        } else if ("ID".equals(typeName)) {
            extraction = new CallSiteExtraction.JooqConvert(columnRef.javaName());
        } else {
            var textEnumMapping = buildTextEnumMapping(typeName);
            if (textEnumMapping != null) {
                String mapFieldName = fieldDef.getName().toUpperCase() + "_" + name.toUpperCase() + "_MAP";
                extraction = new CallSiteExtraction.TextMapLookup(mapFieldName, textEnumMapping);
            } else {
                extraction = new CallSiteExtraction.Direct();
            }
        }
        boolean isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
        return new ArgumentRef.ScalarArg.ColumnArg(
            name, typeName, nonNull, list, columnRef, extraction, argCondition, fieldOverride, isLookupKey);
    }

    /**
     * Builder-internal record for a parsed {@code @condition} directive. See
     * {@code docs/argument-resolution.md#condition-on-field-and-argument-definitions}.
     */
    private record ConditionDirective(String className, String methodName, boolean override,
                                      List<String> contextArguments) {}

    /**
     * Reads a {@code @condition} directive from a field or argument container. Returns
     * {@code null} when the directive is absent or could not be parsed (e.g. missing
     * {@code className}/{@code method}).
     */
    private ConditionDirective readConditionDirective(graphql.schema.GraphQLDirectiveContainer container) {
        var dir = container.getAppliedDirective(DIR_CONDITION);
        if (dir == null) return null;
        var condArg = dir.getArgument(ARG_CONDITION);
        if (condArg == null || condArg.getValue() == null) return null;
        Map<String, Object> ref = asMap(condArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String refName = Optional.ofNullable(ref.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (refName != null) className = RewriteConfig.namedReferences().get(refName);
        }
        if (className == null || methodName == null) return null;
        boolean override = argBoolean(container, DIR_CONDITION, ARG_OVERRIDE, false);
        List<String> ctxArgs = argStringList(container, DIR_CONDITION, ARG_CONTEXT_ARGUMENTS);
        return new ConditionDirective(className, methodName, override, ctxArgs);
    }

    /**
     * Builds an {@link ArgConditionRef} from a {@code @condition} directive on one GraphQL argument.
     * Reflects the condition method via {@link ServiceCatalog#reflectTableMethod} with the arg's
     * name in {@code argNames} and any declared {@code contextArguments} in {@code ctxKeys}.
     * Appends an error and returns {@link Optional#empty()} on reflection failure.
     */
    private Optional<ArgConditionRef> buildArgCondition(GraphQLArgument arg, List<String> errors) {
        var cond = readConditionDirective(arg);
        if (cond == null) return Optional.empty();
        var argName = arg.getName();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            Set.of(argName), Set.copyOf(cond.contextArguments()));
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
        var cond = readConditionDirective(fieldDef);
        if (cond == null) return null;
        var argNames = fieldDef.getArguments().stream()
            .map(GraphQLArgument::getName)
            .collect(Collectors.toSet());
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argNames, Set.copyOf(cond.contextArguments()));
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
        // column. Today only scalar @lookupKey args contribute columns; @lookupKey on input-type
        // fields trips the gate without producing any column (composite-key support is Phase 5).
        // Surface the gap as a classify-time error rather than letting the generator fail later.
        if (hasLookupKeyAnywhere(fieldDef) && lookupMapping.columns().isEmpty()) {
            return TableFieldComponents.error(
                "@lookupKey is declared but no scalar argument resolved to a lookup column — "
                + "composite-key input types with @lookupKey on their fields are not yet supported");
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
        var columns = new ArrayList<LookupMapping.LookupColumn>();
        for (var ref : refs) {
            if (!(ref instanceof ArgumentRef.ScalarArg.ColumnArg ca)) continue;
            if (!ca.isLookupKey()) continue;
            columns.add(new LookupMapping.LookupColumn(ca.name(), ca.column(), ca.extraction(), ca.list()));
        }
        return new LookupMapping(List.copyOf(columns), targetTable);
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
        };
    }

    /**
     * Projects the classified arguments into a {@link WhereFilter} list for a table-bound field.
     *
     * <p>{@link ArgumentRef.OrderByArg} and {@link ArgumentRef.PaginationArgRef} are skipped
     * (handled by {@link #projectOrderBySpec} / {@link #projectPaginationSpec}).
     * {@link ArgumentRef.UnclassifiedArg} and {@link ArgumentRef.ScalarArg.UnboundArg} add to
     * {@code errors}. {@link ArgumentRef.InputTypeArg} variants are currently skipped (full
     * support is deferred to step 9 / lookup projection in step 6). Returns {@code null} when
     * any filter classification fails.
     *
     * <p>All column-bound scalar args are grouped into a single {@link GeneratedConditionFilter}
     * entry. The condition class is named {@code <returnTypeName>Conditions} and the method
     * {@code <fieldName>Condition}.
     */
    private List<WhereFilter> projectFilters(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef,
                                             TableRef rt, String returnTypeName, List<String> errors) {
        var bodyParams = new ArrayList<BodyParam>();
        var argConditions = new ArrayList<ConditionFilter>();
        boolean hadError = false;
        for (var ref : refs) {
            switch (ref) {
                case ArgumentRef.OrderByArg ignored -> {}                     // handled by projectOrderBySpec
                case ArgumentRef.PaginationArgRef ignored -> {}               // handled by projectPaginationSpec
                case ArgumentRef.InputTypeArg.TableInputArg tia -> {
                    // Auto-column binding for @table input types is deferred to step 9.
                    // An arg-level @condition on the whole input still emits a predicate today.
                    tia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.InputTypeArg.PlainInputArg pia -> {
                    // Plain input types are silently skipped unless paired with @condition;
                    // see the out-of-scope note in docs/argument-resolution.md.
                    pia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
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
                        bodyParams.add(new BodyParam(ca.name(), ca.column(), javaType, ca.nonNull(), ca.list(), ca.extraction()));
                    }
                    ca.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
            }
        }
        if (hadError) return null;

        var filters = new ArrayList<WhereFilter>();
        if (!bodyParams.isEmpty()) {
            String conditionsClassName = RewriteConfig.outputPackage() + ".rewrite.types." + returnTypeName + "Conditions";
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
            method.returnTypeName(), newParams);
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
        if (dir == null) return 100;
        var arg = dir.getArgument(ARG_DEFAULT_FIRST_VALUE);
        if (arg == null || arg.getValue() == null) return 100;
        Object val = arg.getValue();
        if (val instanceof graphql.language.IntValue iv) return iv.getValue().intValueExact();
        if (val instanceof Number n) return n.intValue();
        return 100;
    }

    // ===== Field classification =====

    GraphitronField classifyField(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // Detect conflicts among the child-field exclusive directives before the @notGenerated and
        // @multitableReference early-returns — those returns would otherwise silently mask a
        // conflicting directive on the same field.
        if (!(parentType instanceof RootType)) {
            String conflict = detectChildFieldConflict(fieldDef);
            if (conflict != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, conflict);
            }
        }

        if (fieldDef.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new NotGeneratedField(parentTypeName, name, location);
        }
        if (fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE)) {
            return new MultitableReferenceField(parentTypeName, name, location);
        }

        if (parentType instanceof RootType rootType) {
            return classifyRootField(fieldDef, parentTypeName);
        }
        if (parentType instanceof TableBackedType tbt && !(parentType instanceof TableInterfaceType)) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tbt);
        }
        if (parentType instanceof ResultType resultType) {
            return classifyChildFieldOnResultType(fieldDef, parentTypeName, resultType);
        }
        if (parentType instanceof ErrorType) {
            return classifyChildFieldOnErrorType(fieldDef, parentTypeName);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
            "parent type is unclassified");
    }

    private GraphitronField classifyChildFieldOnErrorType(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        if (isScalarOrEnum(fieldDef)) {
            return new PropertyField(parentTypeName, name, location, name);
        }
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
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
        return new UnclassifiedField(parentTypeName, fieldDef.getName(), locationOf(fieldDef), fieldDef,
            "fields on '" + parentTypeName + "' (Subscription is not supported)");
    }

    private GraphitronField classifyQueryField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        String conflict = detectQueryFieldConflict(fieldDef);
        if (conflict != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, conflict);
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(parentTypeName, fieldDef, List.of());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, svcResult.error());
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new QueryField.QueryServiceTableField(parentTypeName, name, location, tb, svcResult.method());
                case ReturnTypeRef.ResultReturnType r ->
                    new QueryField.QueryServiceRecordField(parentTypeName, name, location, r, svcResult.method());
                case ReturnTypeRef.ScalarReturnType s ->
                    new QueryField.QueryServiceRecordField(parentTypeName, name, location, s, svcResult.method());
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (name.equals("_entities")) {
            return new QueryField.QueryEntityField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (name.equals("node")) {
            return new QueryField.QueryNodeField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (hasLookupKeyAnywhere(fieldDef)) {
            String lookupTypeName = baseTypeName(fieldDef);
            var returnType = ctx.resolveReturnType(lookupTypeName, buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@lookupKey requires a @table-annotated return type");
            }
            var tfc = resolveTableFieldComponents(fieldDef, tb.table(), lookupTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
            return new QueryField.QueryLookupTableField(parentTypeName, name, location, tb, tfc.filters(), tfc.orderBy(), tfc.pagination(),
                tfc.lookupMapping());
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@tableMethod requires a @table-annotated return type");
            }
            var qtmRef = parseExternalRef(parentTypeName, fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
            if (qtmRef != null && qtmRef.lookupError() != null) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "table method could not be resolved — " + qtmRef.lookupError());
            }
            Set<String> qtmArgNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            List<String> qtmCtxArgs = parseContextArguments(fieldDef, DIR_TABLE_METHOD);
            var qtmResult = svc.reflectTableMethod(
                qtmRef != null ? qtmRef.className() : null,
                qtmRef != null ? qtmRef.methodName() : null,
                qtmArgNames, new java.util.HashSet<>(qtmCtxArgs));
            if (qtmResult.failed()) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
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
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
            return new QueryField.QueryTableField(parentTypeName, name, location, returnType, tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var tfc = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
            return new QueryField.QueryTableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (elementType instanceof InterfaceType interfaceType) {
            return new QueryField.QueryInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }
        if (elementType instanceof UnionType unionType) {
            return new QueryField.QueryUnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
            "return type '" + elementTypeName + "' is not a @table, interface, or union Graphitron type; " +
            "@service, @lookupKey, and @tableMethod are all absent");
    }

    private GraphitronField classifyMutationField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE) && fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "@" + DIR_SERVICE + ", @" + DIR_MUTATION + " are mutually exclusive");
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(parentTypeName, fieldDef, List.of());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, svcResult.error());
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new MutationField.MutationServiceTableField(parentTypeName, name, location, tb, svcResult.method());
                case ReturnTypeRef.ResultReturnType r ->
                    new MutationField.MutationServiceRecordField(parentTypeName, name, location, r, svcResult.method());
                case ReturnTypeRef.ScalarReturnType s ->
                    new MutationField.MutationServiceRecordField(parentTypeName, name, location, s, svcResult.method());
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
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
                    default       -> new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "unknown @mutation(typeName:) value '" + typeName + "'");
                };
            }
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
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
        boolean hasNotGenerated  = fieldDef.hasAppliedDirective(DIR_NOT_GENERATED);
        boolean hasMultitable    = fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE);
        boolean hasService       = fieldDef.hasAppliedDirective(DIR_SERVICE);
        boolean hasExternalField = fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD);
        boolean hasTableMethod   = fieldDef.hasAppliedDirective(DIR_TABLE_METHOD);
        boolean hasNodeId        = fieldDef.hasAppliedDirective(DIR_NODE_ID);

        int slots = (hasNotGenerated  ? 1 : 0)
                  + (hasMultitable    ? 1 : 0)
                  + (hasService       ? 1 : 0)
                  + (hasExternalField ? 1 : 0)
                  + (hasTableMethod   ? 1 : 0)
                  + (hasNodeId        ? 1 : 0);

        if (slots <= 1) return null;

        var names = new ArrayList<String>();
        if (hasNotGenerated)  names.add("@" + DIR_NOT_GENERATED);
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
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, svcResult.error());
            }
            var servicePath = ctx.parsePath(fieldDef, name, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
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
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (isScalarOrEnum(fieldDef)) {
            String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
                ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
                : name;
            return new PropertyField(parentTypeName, name, location, columnName);
        }

        // Object return type on a result-mapped parent.
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        var objectPath = ctx.parsePath(fieldDef, name, null);
        if (objectPath.hasError()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, objectPath.errorMessage());
        }
        return switch (ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef))) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                var tfc = resolveTableFieldComponents(fieldDef, tb.table(), elementTypeName);
                if (tfc.error() != null) yield new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
                if (hasLookupKeyAnywhere(fieldDef)) {
                    yield new RecordLookupTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                        tfc.lookupMapping());
                }
                var batchKey = deriveBatchKeyForResultType(objectPath.elements(), parentResultType);
                if (batchKey == null) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "RecordTableField requires a FK join path and a typed backing class for batch key extraction");
                }
                yield new RecordTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), batchKey);
            }
            case ReturnTypeRef.ResultReturnType r ->
                new RecordField(parentTypeName, name, location, r, columnName);
            case ReturnTypeRef.ScalarReturnType s ->
                new RecordField(parentTypeName, name, location, s, columnName);
            case ReturnTypeRef.PolymorphicReturnType p ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, "@record type returning a polymorphic type is not yet supported");
        };
    }

    /**
     * Derives the {@link BatchKey} for a {@link no.sikt.graphitron.rewrite.model.ChildField.RecordTableField}
     * by reading the FK source columns from the join path's first {@link JoinStep.FkJoin} step.
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

    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(parentTypeName, fieldDef, tableType.table().primaryKeyColumns());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, svcResult.error());
            }
            // Service reconnect path: starts from the service return type's table (not the parent).
            var servicePath = ctx.parsePath(fieldDef, name, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
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
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            var externalPath = ctx.parsePath(fieldDef, name, tableType.table().tableName());
            if (externalPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, externalPath.errorMessage());
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
            var tableMethodPath = ctx.parsePath(fieldDef, name, tableType.table().tableName());
            if (tableMethodPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, tableMethodPath.errorMessage());
            }
            var tmRef = parseExternalRef(parentTypeName, fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
            if (tmRef != null && tmRef.lookupError() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "table method could not be resolved — " + tmRef.lookupError());
            }
            Set<String> tmArgNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            List<String> tmCtxArgs = parseContextArguments(fieldDef, DIR_TABLE_METHOD);
            var tmResult = svc.reflectTableMethod(
                tmRef != null ? tmRef.className() : null,
                tmRef != null ? tmRef.methodName() : null,
                tmArgNames, new java.util.HashSet<>(tmCtxArgs));
            if (tmResult.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "table method could not be resolved — " + tmResult.failureReason());
            }
            return new TableMethodField(parentTypeName, name, location, returnType, tableMethodPath.elements(), enrichArgExtractions(tmResult.ref(), fieldDef));
        }

        if (!isScalarOrEnum(fieldDef)) {
            return classifyObjectReturnChildField(fieldDef, parentTypeName, tableType);
        }

        if (fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                ReturnTypeRef targetType = ctx.resolveReturnType(typeName.get(), new FieldWrapper.Single(true));
                var targetGType = ctx.types.get(typeName.get());
                if (targetGType == null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not exist in the schema"
                        + candidateHint(typeName.get(), new ArrayList<>(ctx.types.keySet())));
                }
                if (!(targetGType instanceof NodeType targetNodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not have @node");
                }
                TableRef parentTable = tableType.table();
                var nodeRefPath = ctx.parsePath(fieldDef, name, tableType.table().tableName());
                if (nodeRefPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, nodeRefPath.errorMessage());
                }
                return new NodeIdReferenceField(parentTypeName, name, location, typeName.get(), targetType, parentTable,
                    targetNodeType.typeId(), targetNodeType.nodeKeyColumns(), nodeRefPath.elements());
            } else {
                if (!(tableType instanceof NodeType nodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
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
            var refPath = ctx.parsePath(fieldDef, name, tableType.table().tableName());
            if (refPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, refPath.errorMessage());
            }
            Optional<ColumnRef> column = svc.resolveColumnForReference(columnName, refPath.elements(), tableType);
            if (column.isEmpty()) {
                String terminalTable = svc.terminalTableSqlNameForReference(refPath.elements(), tableType);
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "column '" + columnName + "' could not be resolved in the jOOQ table"
                    + (terminalTable != null ? candidateHint(columnName, ctx.catalog.columnSqlNamesOf(terminalTable)) : ""));
            }
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column.get(), refPath.elements(), javaNamePresent);
        }

        Optional<ColumnRef> column = svc.resolveColumn(columnName, tableType);
        if (column.isEmpty()) {
            String tableSqlName = tableType.table().tableName();
            boolean isList = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType()) instanceof GraphQLList;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
            // Path 2 — synthesized NodeIdField. `@nodeId`, `@reference`, and `@field` are already
            // excluded above (`@nodeId` by the directive check, `@reference` by its own block, and
            // `@field` via the exclusion here). See plan: docs/planning/legacy-platform-id.md.
            if (tableType instanceof NodeType nodeType
                    && "ID".equals(typeName)
                    && !isList
                    && !hasFieldDirective) {
                return new NodeIdField(parentTypeName, name, location,
                    nodeType.typeId(), nodeType.nodeKeyColumns());
            }
            var platformIdMethods = ctx.catalog.platformIdOutputMethodNames(tableSqlName);
            // Fallback: check for legacy platform-key output accessor on the jOOQ table class.
            // Conditions: scalar ID type, not a list. @nodeId is already handled above.
            if ("ID".equals(typeName) && !isList) {
                String getterName = "get" + JooqCatalog.sqlToAccessorSuffix(columnName);
                if (platformIdMethods.contains(getterName)) {
                    return new PlatformIdField(parentTypeName, name, location, getterName);
                }
            }
            String platformHint = platformIdMethods.isEmpty() ? ""
                : candidateHint(columnName, platformIdMethods, "; platform-id methods on table class: ");
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "column '" + columnName + "' could not be resolved in the jOOQ table"
                + candidateHint(columnName, ctx.catalog.columnSqlNamesOf(tableSqlName))
                + platformHint);
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
     * the name is looked up in {@link RewriteConfig#namedReferences()}. A deprecation warning is
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
                String resolved = RewriteConfig.namedReferences().get(name);
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

    // ===== Inner records =====

    record ExternalRef(String className, String methodName, String lookupError) {}
}
