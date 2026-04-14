package no.sikt.graphitron.rewrite;

import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
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
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLLATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONTEXT_ARGUMENTS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DIRECTION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_FIELDS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_INDEX;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_JAVA_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PATH;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PRIMARY_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_SERVICE_REF;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TABLE_METHOD_REF;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_ID;
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

    private final BuildContext ctx;
    private final ServiceCatalog svc;

    FieldBuilder(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    // ===== Shared resolution helpers =====

    private record ServiceResolution(MethodRef method, ReturnTypeRef returnType, String error) {}

    /**
     * Resolves the {@code @service} directive on a field: unwraps connection types, parses the
     * external reference, reflects the service method, and returns the resolved method + return type.
     * Returns a non-null {@code error} when resolution fails.
     *
     * <p>{@code parentPkColumns} is forwarded to {@link ServiceCatalog#reflectServiceMethod} for
     * batch-key classification. Pass {@link List#of()} for root fields and result-type children
     * (no parent table); pass the parent table's primary-key columns for table-type children.
     */
    private ServiceResolution resolveServiceField(GraphQLFieldDefinition fieldDef, List<ColumnRef> parentPkColumns) {
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
        ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
        List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
        Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
        var result = svc.reflectServiceMethod(serviceRef.className(), serviceRef.methodName(), argNames, new java.util.HashSet<>(contextArgs), parentPkColumns);
        if (result.failed()) {
            return new ServiceResolution(null, null, "service method could not be resolved — " + result.failureReason());
        }
        return new ServiceResolution(result.ref(), returnType, null);
    }

    private record TableFieldComponents(List<WhereFilter> filters, OrderBySpec orderBy, PaginationSpec pagination, String error) {}

    /**
     * Resolves the filter, order-by, and pagination components for a table-bound list field.
     * Returns a non-null {@code error} when any component fails to resolve.
     *
     * @param returnTypeName the GraphQL return type name (e.g. {@code "Film"}), used to derive
     *                       the {@code *Conditions} class name for any generated filter method
     */
    private TableFieldComponents resolveTableFieldComponents(GraphQLFieldDefinition fieldDef, TableRef table, String returnTypeName) {
        var filterErrors = new ArrayList<String>();
        var filters = buildFilters(fieldDef, table, returnTypeName, filterErrors);
        if (filters == null) return new TableFieldComponents(null, null, null, String.join("; ", filterErrors));
        var orderErrors = new ArrayList<String>();
        var orderBy = buildOrderBySpec(fieldDef, table.tableName(), orderErrors);
        if (orderBy == null) {
            String msg = !orderErrors.isEmpty() ? String.join("; ", orderErrors)
                : "could not resolve @defaultOrder columns in table '" + table.tableName() + "'";
            return new TableFieldComponents(null, null, null, msg);
        }
        return new TableFieldComponents(filters, orderBy, buildPaginationSpec(fieldDef), null);
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
            var referencePath = ctx.parsePath(fieldDef, parentTableType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, referencePath.errorMessage());
            }
            var tfc = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName);
            if (tfc.error() != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
            boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
            boolean hasLookupKey  = hasLookupKeyAnywhere(fieldDef);
            var parentBatchKey = new BatchKey.RowKeyed(parentTableType.table().primaryKeyColumns());
            if (hasSplitQuery && hasLookupKey) {
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey);
            }
            if (!hasSplitQuery && hasLookupKey) {
                return new no.sikt.graphitron.rewrite.model.ChildField.LookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
            }
            if (hasSplitQuery) {
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey);
            }
            return new TableField(parentTypeName, name, location,
                returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var referencePath = ctx.parsePath(fieldDef, parentTableType.table().tableName());
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

        // ConstructorField is intentionally not classified here — its directive and generation
        // semantics are not yet defined (planned future deliverable). Fields that would logically
        // map to ConstructorField fall through to UnclassifiedField, which the validator rejects
        // with a clear error, making the gap visible and enforced rather than silently ignored.
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
            "ConstructorField (child field on @table type returning a @record type) is not yet supported");
    }

    // ===== Wrapper helpers =====

    /**
     * Builds a {@link FieldWrapper} from the return type shape of the field (cardinality and
     * nullability only). Ordering is separated into {@link #buildOrderBySpec}.
     *
     * <p>Connection is detected structurally — the return type must be a {@link GraphQLObjectType}
     * that has an {@code edges} field whose element type in turn has a {@code node} field.
     */
    private FieldWrapper buildWrapper(GraphQLFieldDefinition fieldDef) {
        GraphQLType fieldType = fieldDef.getType();
        boolean outerNullable = !(fieldType instanceof GraphQLNonNull);
        GraphQLType unwrappedOnce = GraphQLTypeUtil.unwrapNonNull(fieldType);

        if (unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            return new FieldWrapper.List(outerNullable, itemNullable);
        }

        String typeName = baseTypeName(fieldDef);
        if (ctx.isConnectionType(typeName)) {
            boolean itemNullable = ctx.connectionItemNullable(typeName);
            return new FieldWrapper.Connection(outerNullable, itemNullable);
        }

        return new FieldWrapper.Single(outerNullable);
    }

    /**
     * Builds an {@link OrderBySpec} for a field.
     *
     * <p>Returns {@link OrderBySpec.None} when ordering is not applicable: for single-value
     * returns, or when {@code tableSqlName} is {@code null} (non-table-bound field).
     * Returns {@link OrderBySpec.None} (not an error) when the table has no primary key and no
     * {@code @defaultOrder} is present.
     * Returns {@code null} — signalling a build failure — only when a {@code @defaultOrder}
     * directive is present but its column/index resolution fails.
     */
    private OrderBySpec buildOrderBySpec(GraphQLFieldDefinition fieldDef, String tableSqlName, List<String> errors) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType());
        boolean isList = (unwrapped instanceof GraphQLList) || ctx.isConnectionType(baseTypeName(fieldDef));
        if (!isList || tableSqlName == null) return new OrderBySpec.None();

        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_ORDER_BY)) {
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
     * <p>All three source variants are resolved at build time:
     * <ul>
     *   <li>{@code index:} — columns come from the named index via the jOOQ catalog.</li>
     *   <li>{@code primaryKey:} — columns come from the table's primary key.</li>
     *   <li>{@code fields:} — each column name is looked up in the table via the jOOQ catalog.</li>
     * </ul>
     * Returns {@code null} when any lookup fails (index not found, PK absent, or a column name is
     * unresolvable). The caller must treat {@code null} as a classification failure.
     *
     * <p>Only called when the directive is confirmed present.
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

        var indexArg = dir.getArgument(ARG_INDEX);
        if (indexArg != null) {
            Object indexVal = indexArg.getValue();
            String indexName = indexVal instanceof StringValue sv ? sv.getValue().strip()
                : indexVal instanceof String s ? s.strip() : null;
            if (indexName != null) {
                var colsOpt = ctx.catalog.findIndexColumns(tableSqlName, indexName);
                if (colsOpt.isEmpty() || colsOpt.get().isEmpty()) return null;
                var entries = colsOpt.get().stream()
                    .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                    .toList();
                return new OrderBySpec.Fixed(entries, direction);
            }
        }

        var pkArg = dir.getArgument(ARG_PRIMARY_KEY);
        boolean primaryKey = pkArg != null && (
            pkArg.getValue() instanceof BooleanValue bv ? bv.isValue()
            : Boolean.TRUE.equals(pkArg.getValue()));
        if (primaryKey) {
            var pkCols = ctx.catalog.findPkColumns(tableSqlName);
            if (pkCols.isEmpty()) return null;
            var entries = pkCols.stream()
                .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                .toList();
            return new OrderBySpec.Fixed(entries, direction);
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
            return new OrderBySpec.Fixed(entries, direction);
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
                .anyMatch(v -> v.hasAppliedDirective("order"));
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
        OrderBySpec baseSpec = resolveDefaultOrderSpec(fieldDef, tableSqlName);
        OrderBySpec.Fixed base = baseSpec instanceof OrderBySpec.Fixed f ? f : null;
        return new OrderBySpec.Argument(name, typeName, nonNull, list, sortFieldName, directionFieldName,
            List.of(), // namedOrders: deferred
            base);
    }

    /**
     * Builds the {@link WhereFilter} list for a table-bound field from its GraphQL arguments.
     *
     * <p>Skips {@code @orderBy} args (handled by {@link #buildOrderBySpec}) and the four standard
     * Relay pagination args ({@code first}, {@code last}, {@code after}, {@code before}, handled by
     * {@link #buildPaginationSpec}). Returns {@code null} when any filter classification fails
     * (errors appended to {@code errors}).
     *
     * <p>All filterable scalar/enum arguments are grouped into a single
     * {@link GeneratedConditionFilter} entry. The condition class is named
     * {@code <returnTypeName>Conditions} and the method {@code <fieldName>Condition}.
     * Table-bound input arguments are currently not yet handled (future deliverable).
     */
    private List<WhereFilter> buildFilters(GraphQLFieldDefinition fieldDef, TableRef rt, String returnTypeName, List<String> errors) {
        var bodyParams = new ArrayList<BodyParam>();
        boolean hadError = false;
        for (var arg : fieldDef.getArguments()) {
            String name = arg.getName();
            if (arg.hasAppliedDirective(DIR_ORDER_BY)) continue;
            if (isPaginationArg(name)) continue;
            if (arg.hasAppliedDirective(DIR_CONDITION)) {
                errors.add("argument '" + name + "': @condition is only supported on field definitions, not on arguments");
                hadError = true;
                continue;
            }
            GraphQLType type = arg.getType();
            boolean nonNull = type instanceof GraphQLNonNull;
            boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
            if (ctx.types.containsKey(typeName)) {
                // TODO: table-bound input types (InputFilter) — deferred deliverable
                continue;
            }
            // Scalar arg: bind to column
            String columnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(name);
            if (rt == null) {
                errors.add("argument '" + name + "': column '" + columnName + "' could not be resolved (no table context)");
                hadError = true;
                continue;
            }
            var col = ctx.catalog.findColumn(rt.tableName(), columnName);
            if (col.isEmpty()) {
                errors.add("argument '" + name + "': column '" + columnName + "' could not be resolved in table '"
                    + rt.tableName() + "'" + candidateHint(columnName, ctx.catalog.columnSqlNamesOf(rt.tableName())));
                hadError = true;
                continue;
            }
            var columnRef = new ColumnRef(col.get().sqlName(), col.get().javaName(), col.get().columnClass());
            String enumClassName = validateEnumFilter(typeName, columnRef, errors);
            if (enumClassName != null && enumClassName.isEmpty()) {
                hadError = true;
                continue;
            }
            CallSiteExtraction extraction;
            String javaType;
            if (enumClassName != null) {
                extraction = new CallSiteExtraction.EnumValueOf(enumClassName);
                javaType = enumClassName;
            } else {
                var textEnumMapping = buildTextEnumMapping(typeName);
                if (textEnumMapping != null) {
                    String mapFieldName = fieldDef.getName().toUpperCase() + "_" + name.toUpperCase() + "_MAP";
                    extraction = new CallSiteExtraction.TextMapLookup(mapFieldName, textEnumMapping);
                    javaType = String.class.getName();
                } else {
                    extraction = new CallSiteExtraction.Direct();
                    javaType = columnRef.columnClass();
                }
            }
            bodyParams.add(new BodyParam(name, columnRef, javaType, nonNull, list, extraction));
        }
        if (hadError) return null;
        if (bodyParams.isEmpty()) return List.of();
        String conditionsClassName = GeneratorConfig.outputPackage() + ".rewrite.types." + returnTypeName + "Conditions";
        String methodName = fieldDef.getName() + "Condition";
        var callParams = bodyParams.stream()
            .map(bp -> new CallParam(bp.name(), bp.extraction()))
            .toList();
        return List.of(new GeneratedConditionFilter(conditionsClassName, methodName, rt, callParams, List.copyOf(bodyParams)));
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
     * Builds a {@link PaginationSpec} when the field has any of the standard Relay pagination
     * arguments ({@code first}, {@code last}, {@code after}, {@code before}).
     * Returns {@code null} when none are present.
     */
    private PaginationSpec buildPaginationSpec(GraphQLFieldDefinition fieldDef) {
        PaginationSpec.PaginationArg first = null, last = null, after = null, before = null;
        for (var arg : fieldDef.getArguments()) {
            String argName = arg.getName();
            if (!isPaginationArg(argName)) continue;
            GraphQLType type = arg.getType();
            boolean nonNull = type instanceof GraphQLNonNull;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
            var paginationArg = new PaginationSpec.PaginationArg(argName, typeName, nonNull);
            switch (argName) {
                case "first"  -> first  = paginationArg;
                case "last"   -> last   = paginationArg;
                case "after"  -> after  = paginationArg;
                case "before" -> before = paginationArg;
            }
        }
        if (first == null && last == null && after == null && before == null) return null;
        return new PaginationSpec(first, last, after, before);
    }

    private static boolean isPaginationArg(String argName) {
        return "first".equals(argName) || "last".equals(argName)
            || "after".equals(argName) || "before".equals(argName);
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
            return classifyChildFieldOnResultType(fieldDef, parentTypeName);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
            "parent type '" + parentTypeName + "' has no supported Graphitron classification");
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
            var svcResult = resolveServiceField(fieldDef, List.of());
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
            return new QueryField.QueryLookupTableField(parentTypeName, name, location, tb, tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@tableMethod requires a @table-annotated return type");
            }
            var qtmRef = parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
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
            return new QueryField.QueryTableMethodTableField(parentTypeName, name, location, tb, qtmResult.ref());
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
            var svcResult = resolveServiceField(fieldDef, List.of());
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

    private GraphitronField classifyChildFieldOnResultType(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(fieldDef, List.of());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, svcResult.error());
            }
            var servicePath = ctx.parsePath(fieldDef);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), List.of(), new OrderBySpec.None(), null, svcResult.method());
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
        var objectPath = ctx.parsePath(fieldDef);
        if (objectPath.hasError()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, objectPath.errorMessage());
        }
        return switch (ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef))) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                var tfc = resolveTableFieldComponents(fieldDef, tb.table(), elementTypeName);
                if (tfc.error() != null) yield new UnclassifiedField(parentTypeName, name, location, fieldDef, tfc.error());
                if (hasLookupKeyAnywhere(fieldDef)) {
                    yield new RecordLookupTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
                }
                yield new RecordTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
            }
            case ReturnTypeRef.ResultReturnType r ->
                new RecordField(parentTypeName, name, location, r, columnName);
            case ReturnTypeRef.ScalarReturnType s ->
                new RecordField(parentTypeName, name, location, s, columnName);
            case ReturnTypeRef.PolymorphicReturnType p ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, "@record type returning a polymorphic type is not yet supported");
        };
    }

    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var svcResult = resolveServiceField(fieldDef, tableType.table().primaryKeyColumns());
            if (svcResult.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, svcResult.error());
            }
            // Service reconnect path: starts from the service return type's table (not the parent).
            var servicePath = ctx.parsePath(fieldDef);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
            }
            return switch (svcResult.returnType()) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), List.of(), new OrderBySpec.None(), null, svcResult.method());
                case ReturnTypeRef.ResultReturnType r ->
                    new ServiceRecordField(parentTypeName, name, location, r, servicePath.elements(), svcResult.method());
                case ReturnTypeRef.ScalarReturnType s ->
                    new ServiceRecordField(parentTypeName, name, location, s, servicePath.elements(), svcResult.method());
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            var externalPath = ctx.parsePath(fieldDef, tableType.table().tableName());
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
            var tableMethodPath = ctx.parsePath(fieldDef, tableType.table().tableName());
            if (tableMethodPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, tableMethodPath.errorMessage());
            }
            var tmRef = parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
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
            return new TableMethodField(parentTypeName, name, location, returnType, tableMethodPath.elements(), tmResult.ref());
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
                var nodeRefPath = ctx.parsePath(fieldDef, tableType.table().tableName());
                if (nodeRefPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, nodeRefPath.errorMessage());
                }
                return new NodeIdReferenceField(parentTypeName, name, location, typeName.get(), targetType, parentTable,
                    targetNodeType.typeId(), targetNodeType.nodeKeyColumns(), nodeRefPath.elements());
            } else {
                if (!(tableType instanceof NodeType nodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId requires the containing type to have @node");
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
            var refPath = ctx.parsePath(fieldDef, tableType.table().tableName());
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
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "column '" + columnName + "' could not be resolved in the jOOQ table"
                + candidateHint(columnName, ctx.catalog.columnSqlNamesOf(tableType.table().tableName())));
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
     */
    private ExternalRef parseExternalRef(GraphQLFieldDefinition fieldDef, String directiveName, String argName) {
        var dir = fieldDef.getAppliedDirective(directiveName);
        if (dir == null) return null;
        var arg = dir.getArgument(argName);
        if (arg == null) return null;
        Map<String, Object> ref = asMap(arg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        return new ExternalRef(className, methodName);
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

    record ExternalRef(String className, String methodName) {}
}
