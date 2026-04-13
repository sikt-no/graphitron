package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.configuration.ErrorHandlerType;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DESCRIPTION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CODE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLLATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_HANDLER;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_HANDLERS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_KEY_COLUMNS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_MATCHES;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_ON;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_RECORD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_SQL_STATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_VALUE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_DISCRIMINATE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_DISCRIMINATOR;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ERROR;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NOT_GENERATED;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_RECORD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.argStringList;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;
import static no.sikt.graphitron.rewrite.BuildContext.locationOf;

/**
 * Classifies all named types in the schema into the {@link GraphitronType} hierarchy.
 *
 * <p>Runs in two passes (see {@link #buildTypes()}): the first pass classifies each type in
 * isolation; the second pass enriches interface and union types with their participant lists,
 * which require the full first-pass map to be available.
 */
class TypeBuilder {

    private static final Set<String> ROOT_TYPE_NAMES = Set.of("Query", "Mutation", "Subscription");

    private final BuildContext ctx;
    private final ServiceCatalog svc;

    TypeBuilder(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    // ===== Two-pass type map construction =====

    Map<String, GraphitronType> buildTypes() {
        var result = new LinkedHashMap<String, GraphitronType>();
        ctx.schema.getAllTypesAsList().stream()
            .filter(t -> !t.getName().startsWith("__"))
            .forEach(namedType -> {
                var gType = classifyType(namedType);
                if (gType != null) {
                    result.put(namedType.getName(), gType);
                }
            });

        // Expose the first-pass result so that buildParticipantList can look up TableType entries
        // during the second pass below.
        ctx.types = result;

        result.replaceAll((name, type) -> switch (type) {
            case TableInterfaceType tit   -> enrichTableInterfaceType(tit, result);
            case InterfaceType it         -> enrichInterfaceType(it, result);
            case UnionType ut             -> enrichUnionType(ut, result);
            case TableType ignored        -> type;
            case NodeType ignored         -> type;
            case ResultType ignored       -> type;
            case RootType ignored         -> type;
            case ErrorType ignored        -> type;
            case InputType ignored        -> type;
            case TableInputType ignored   -> type;
            case UnclassifiedType ignored -> type;
        });

        return result;
    }

    private GraphitronType enrichTableInterfaceType(TableInterfaceType type, Map<String, GraphitronType> types) {
        var participants = buildParticipantList(implementorNames(type.name(), types));
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new TableInterfaceType(type.name(), type.location(), type.discriminatorColumn(), type.table(), participants.list());
    }

    private GraphitronType enrichInterfaceType(InterfaceType type, Map<String, GraphitronType> types) {
        var participants = buildParticipantList(implementorNames(type.name(), types));
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new InterfaceType(type.name(), type.location(), participants.list());
    }

    private GraphitronType enrichUnionType(UnionType type, Map<String, GraphitronType> types) {
        var unionType = (GraphQLUnionType) ctx.schema.getType(type.name());
        var names = unionType.getTypes().stream().map(t -> t.getName()).toList();
        var participants = buildParticipantList(names);
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new UnionType(type.name(), type.location(), participants.list());
    }

    private List<String> implementorNames(String interfaceName, Map<String, GraphitronType> types) {
        var iface = (GraphQLInterfaceType) ctx.schema.getType(interfaceName);
        return ctx.schema.getImplementations(iface).stream().map(obj -> obj.getName()).toList();
    }

    private ParticipantListResult buildParticipantList(List<String> typeNames) {
        var result = new ArrayList<ParticipantRef>();
        var errors = new ArrayList<String>();
        for (var typeName : typeNames) {
            var gt = ctx.types.get(typeName);
            if (gt instanceof TableBackedType tbt && !(gt instanceof TableInterfaceType)) {
                String discriminatorValue = argString(ctx.schema.getObjectType(typeName), DIR_DISCRIMINATOR, ARG_VALUE).orElse(null);
                result.add(new ParticipantRef(typeName, tbt.table(), discriminatorValue));
            } else {
                errors.add("implementing type '" + typeName + "' is not table-bound (missing @table directive)");
            }
        }
        if (!errors.isEmpty()) {
            return new ParticipantListResult(null, String.join("; ", errors));
        }
        return new ParticipantListResult(List.copyOf(result), null);
    }

    // ===== Type classification =====

    GraphitronType classifyType(GraphQLNamedType namedType) {
        if (namedType instanceof graphql.schema.GraphQLScalarType
                || namedType instanceof graphql.schema.GraphQLEnumType) {
            return null;
        }
        if (namedType instanceof GraphQLInputObjectType inputType) {
            return buildInputType(inputType);
        }

        String name = namedType.getName();
        SourceLocation location = locationOf(namedType);

        if (namedType instanceof GraphQLObjectType objType) {
            if (ROOT_TYPE_NAMES.contains(name)) {
                return new RootType(name, location, fieldCoordinatesOf(objType));
            }
            String typeConflict = detectTypeDirectiveConflict(objType);
            if (typeConflict != null) {
                return new UnclassifiedType(name, location, typeConflict);
            }
            if (objType.hasAppliedDirective(DIR_TABLE)) {
                return buildTableType(objType);
            }
            if (objType.hasAppliedDirective(DIR_RECORD)) {
                return buildResultType(objType, name, location);
            }
            if (objType.hasAppliedDirective(DIR_ERROR)) {
                return buildErrorType(objType);
            }
            return null;
        }
        if (namedType instanceof GraphQLInterfaceType iface) {
            if (iface.hasAppliedDirective(DIR_TABLE) && iface.hasAppliedDirective(DIR_DISCRIMINATE)) {
                return buildTableInterfaceType(iface);
            }
            return new InterfaceType(name, location, List.of());
        }
        if (namedType instanceof GraphQLUnionType) {
            return new UnionType(name, location, List.of());
        }
        return null;
    }

    private GraphitronType buildTableType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);
        String tableName = argString(objType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = svc.resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog"
                + candidateHint(tableName, ctx.catalog.allTableSqlNames()));
        }
        TableRef tableRef = tableOpt.get();
        if (!objType.hasAppliedDirective(DIR_NODE)) {
            return new TableType(name, location, tableRef, fieldCoordinatesOf(objType));
        }
        String typeId = argString(objType, DIR_NODE, ARG_TYPE_ID).orElse(null);
        List<String> keyColumnNames = argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS);
        var keyColumnErrors = new ArrayList<String>();
        var keyColumns = new ArrayList<ColumnRef>();
        for (String colName : keyColumnNames) {
            Optional<ColumnRef> kc = svc.resolveKeyColumn(colName, tableRef.tableName());
            if (kc.isEmpty()) {
                keyColumnErrors.add("key column '" + colName + "' in @node could not be resolved in the jOOQ table"
                    + candidateHint(colName, ctx.catalog.columnSqlNamesOf(tableRef.tableName())));
            } else {
                keyColumns.add(kc.get());
            }
        }
        if (!keyColumnErrors.isEmpty()) {
            return new UnclassifiedType(name, location, String.join("; ", keyColumnErrors));
        }
        return new NodeType(name, location, tableRef, typeId, List.copyOf(keyColumns), fieldCoordinatesOf(objType));
    }

    /**
     * Reflects on the backing Java class named in {@code @record(record: {className:})} and
     * constructs the appropriate {@link ResultType} sub-type.
     */
    private GraphitronType buildResultType(GraphQLObjectType objType, String name, SourceLocation location) {
        var fcs = fieldCoordinatesOf(objType);
        var dir = objType.getAppliedDirective(DIR_RECORD);
        if (dir == null) return new GraphitronType.PojoResultType(name, location, fcs, null);
        var recordArg = dir.getArgument(ARG_RECORD);
        if (recordArg == null || recordArg.getValue() == null) {
            return new GraphitronType.PojoResultType(name, location, fcs, null);
        }
        Map<String, Object> ref = asMap(recordArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        if (className == null) {
            return new GraphitronType.PojoResultType(name, location, fcs, null);
        }
        try {
            Class<?> cls = Class.forName(className);
            if (cls.isRecord()) {
                return new GraphitronType.JavaRecordType(name, location, fcs, className);
            }
            if (org.jooq.TableRecord.class.isAssignableFrom(cls)) {
                TableRef table = svc.resolveTableByRecordClass(cls).orElse(null);
                return new GraphitronType.JooqTableRecordType(name, location, fcs, className, table);
            }
            if (org.jooq.Record.class.isAssignableFrom(cls)) {
                return new GraphitronType.JooqRecordType(name, location, fcs, className);
            }
            return new GraphitronType.PojoResultType(name, location, fcs, className);
        } catch (ClassNotFoundException e) {
            return new UnclassifiedType(name, location,
                "record backing class '" + className + "' could not be loaded");
        }
    }

    private GraphitronType buildTableInterfaceType(GraphQLInterfaceType iface) {
        String name = iface.getName();
        SourceLocation location = locationOf(iface);
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = svc.resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog"
                + candidateHint(tableName, ctx.catalog.allTableSqlNames()));
        }
        String discriminatorColumn = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse(null);
        return new TableInterfaceType(name, location, discriminatorColumn, tableOpt.get(), List.of());
    }

    private ErrorType buildErrorType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);
        var dir = objType.getAppliedDirective(DIR_ERROR);
        var handlersArg = dir.getArgument(ARG_HANDLERS);
        Object value = handlersArg.getValue();
        List<?> items = value instanceof List<?> l ? l : List.of(value);
        List<ErrorType.Handler> handlers = items.stream()
            .filter(v -> v instanceof Map)
            .map(v -> parseErrorHandler(asMap(v)))
            .toList();
        return new ErrorType(name, location, handlers);
    }

    private GraphitronType buildInputType(GraphQLInputObjectType inputType) {
        String name = inputType.getName();
        SourceLocation location = locationOf(inputType);
        var filteredFields = inputType.getFieldDefinitions().stream()
            .filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED))
            .toList();
        if (inputType.hasAppliedDirective(DIR_TABLE)) {
            String tableName = argString(inputType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
            Optional<TableRef> tableOpt = svc.resolveTable(tableName);
            if (tableOpt.isEmpty()) {
                return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog"
                    + candidateHint(tableName, ctx.catalog.allTableSqlNames()));
            }
            return buildTableInputType(name, location, filteredFields, tableOpt.get());
        }
        var tables = findReturnTablesForInput(name);
        if (tables.isEmpty()) {
            return buildNonTableInputType(inputType, name, location);
        }
        if (tables.size() > 1) {
            var tableNames = String.join("', '", tables.keySet());
            return new UnclassifiedType(name, location,
                "used as argument on fields with conflicting return tables: '" + tableNames + "'");
        }
        return buildTableInputType(name, location, filteredFields, tables.values().iterator().next());
    }

    /**
     * Resolves a list of raw input fields against a {@link TableRef} into a {@link TableInputType}.
     */
    GraphitronType buildTableInputType(String name, SourceLocation location,
            List<GraphQLInputObjectField> fields, TableRef tableRef) {
        var errors = new ArrayList<String>();
        var resolvedFields = new ArrayList<InputField>();
        for (var f : fields) {
            var field = buildInputField(f, name, tableRef);
            if (field.isEmpty()) {
                String colName = f.hasAppliedDirective(DIR_FIELD)
                    ? argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName()) : f.getName();
                errors.add("field '" + f.getName() + "' column '" + colName + "' could not be resolved in the jOOQ table"
                    + candidateHint(colName, ctx.catalog.columnSqlNamesOf(tableRef.tableName())));
            } else {
                resolvedFields.add(field.get());
            }
        }
        if (!errors.isEmpty()) {
            return new UnclassifiedType(name, location, String.join("; ", errors));
        }
        return new TableInputType(name, location, tableRef, List.copyOf(resolvedFields));
    }

    /**
     * Reflects on the backing Java class and constructs the appropriate {@link InputType} sub-type.
     */
    private GraphitronType buildNonTableInputType(GraphQLInputObjectType inputType, String name, SourceLocation location) {
        var dir = inputType.getAppliedDirective(DIR_RECORD);
        if (dir == null) return new GraphitronType.PojoInputType(name, location, null);
        var recordArg = dir.getArgument(ARG_RECORD);
        if (recordArg == null || recordArg.getValue() == null) {
            return new GraphitronType.PojoInputType(name, location, null);
        }
        Map<String, Object> ref = asMap(recordArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        if (className == null) {
            return new GraphitronType.PojoInputType(name, location, null);
        }
        try {
            Class<?> cls = Class.forName(className);
            if (cls.isRecord()) {
                return new GraphitronType.JavaRecordInputType(name, location, className);
            }
            if (org.jooq.TableRecord.class.isAssignableFrom(cls)) {
                TableRef table = svc.resolveTableByRecordClass(cls).orElse(null);
                return new GraphitronType.JooqTableRecordInputType(name, location, className, table);
            }
            if (org.jooq.Record.class.isAssignableFrom(cls)) {
                return new GraphitronType.JooqRecordInputType(name, location, className);
            }
            return new GraphitronType.PojoInputType(name, location, className);
        } catch (ClassNotFoundException e) {
            return new UnclassifiedType(name, location,
                "record backing class '" + className + "' could not be loaded");
        }
    }

    /**
     * Walks all field definitions in the schema to find which tables are referenced by fields
     * that accept a given input type (excluding @service, @tableMethod, @mutation fields).
     */
    private Map<String, TableRef> findReturnTablesForInput(String inputTypeName) {
        var tables = new LinkedHashMap<String, TableRef>();
        for (var namedType : ctx.schema.getAllTypesAsList()) {
            if (!(namedType instanceof GraphQLObjectType objType)) continue;
            if (namedType.getName().startsWith("__")) continue;
            for (var fieldDef : objType.getFieldDefinitions()) {
                if (fieldDef.hasAppliedDirective(DIR_SERVICE)
                        || fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)
                        || fieldDef.hasAppliedDirective(DIR_MUTATION)) continue;
                boolean usesInput = fieldDef.getArguments().stream()
                    .anyMatch(arg -> inputTypeName.equals(
                        ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName()));
                if (!usesInput) continue;
                var returnBase = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
                if (!(returnBase instanceof GraphQLObjectType returnObj)) continue;
                if (!returnObj.hasAppliedDirective(DIR_TABLE)) continue;
                String tableName = argString(returnObj, DIR_TABLE, ARG_NAME)
                    .orElse(returnObj.getName().toLowerCase());
                if (!tables.containsKey(tableName.toLowerCase())) {
                    svc.resolveTable(tableName).ifPresent(tr -> tables.put(tableName.toLowerCase(), tr));
                }
            }
        }
        return tables;
    }

    private Optional<InputField> buildInputField(GraphQLInputObjectField field,
            String parentTypeName, TableRef resolvedTable) {
        String name = field.getName();
        GraphQLType type = field.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean hasFieldDir = field.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDir
            ? argString(field, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        if (field.hasAppliedDirective(DIR_REFERENCE)) {
            var path = ctx.parsePath(field, resolvedTable.tableName());
            if (path.hasError()) return Optional.empty();
            return svc.resolveColumnForReference(columnName, path.elements(), resolvedTable.tableName())
                .map(col -> new InputField.ColumnReferenceField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list, col, path.elements()));
        }
        return ctx.catalog.findColumn(resolvedTable.tableName(), columnName)
            .map(e -> new InputField.ColumnField(parentTypeName, name, locationOf(field), typeName, nonNull, list,
                new ColumnRef(e.sqlName(), e.javaName(), e.columnClass())));
    }

    private ErrorType.Handler parseErrorHandler(Map<String, Object> item) {
        Object handlerRaw = item.get(ARG_HANDLER);
        ErrorHandlerType handlerType = handlerRaw != null
            ? ErrorHandlerType.valueOf(handlerRaw.toString())
            : null;
        if (handlerType == null) {
            throw new IllegalStateException("Missing required 'handler' field in @error handler");
        }
        String className = Optional.ofNullable(item.get(ARG_CLASS_NAME)).map(Object::toString).map(String::strip).orElse(null);
        String code = Optional.ofNullable(item.get(ARG_CODE)).map(Object::toString).map(String::strip).orElse(null);
        String sqlState = Optional.ofNullable(item.get(ARG_SQL_STATE)).map(Object::toString).map(String::strip).orElse(null);
        String matches = Optional.ofNullable(item.get(ARG_MATCHES)).map(Object::toString).map(String::strip).orElse(null);
        String description = Optional.ofNullable(item.get(ARG_DESCRIPTION)).map(Object::toString).map(String::strip).orElse(null);
        return new ErrorType.Handler(handlerType, className, code, sqlState, matches, description);
    }

    // ===== Conflict detection =====

    /**
     * Returns a reason string when {@code @table}, {@code @record}, and/or {@code @error} appear
     * together on one type, or {@code null} when at most one is present.
     */
    private static String detectTypeDirectiveConflict(GraphQLObjectType objType) {
        var present = List.of(DIR_TABLE, DIR_RECORD, DIR_ERROR).stream()
            .filter(objType::hasAppliedDirective)
            .toList();
        if (present.size() <= 1) return null;
        return present.stream().map(d -> "@" + d).collect(java.util.stream.Collectors.joining(", "))
            + " are mutually exclusive";
    }

    // ===== Structural helpers =====

    static List<FieldCoordinates> fieldCoordinatesOf(GraphQLObjectType objType) {
        return objType.getFieldDefinitions().stream()
            .map(f -> FieldCoordinates.coordinates(objType.getName(), f.getName()))
            .toList();
    }

    // ===== Result container =====

    private record ParticipantListResult(List<ParticipantRef> list, String error) {}
}
