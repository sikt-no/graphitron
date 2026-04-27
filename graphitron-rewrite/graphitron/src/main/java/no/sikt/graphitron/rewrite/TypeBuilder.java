package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.ErrorHandlerType;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ORDER_BY;
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
import static no.sikt.graphitron.rewrite.BuildContext.DIR_RECORD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_OVERRIDE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.argBoolean;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(TypeBuilder.class);

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
            case TableInterfaceType tit   -> enrichTableInterfaceType(tit);
            case InterfaceType it         -> enrichInterfaceType(it);
            case UnionType ut             -> enrichUnionType(ut);
            case TableType ignored        -> type;
            case NodeType ignored         -> type;
            case ResultType ignored       -> type;
            case RootType ignored         -> type;
            case ErrorType ignored        -> type;
            case InputType ignored        -> type;
            case TableInputType ignored   -> type;
            case ConnectionType ignored   -> type;
            case EdgeType ignored         -> type;
            case PageInfoType ignored     -> type;
            case PlainObjectType ignored  -> type;
            case no.sikt.graphitron.rewrite.model.GraphitronType.EnumType ignored -> type;
            case UnclassifiedType ignored -> type;
        });

        // NodeType typeId uniqueness: two types cannot share a typeId because Query.node(id:)
        // dispatch extracts the typeId prefix and routes to one GraphQL type. Colliding entries
        // demote symmetrically — we can't pick a winner without silently breaking the loser's
        // issued IDs, which would violate the durability invariant.
        validateNodeTypeIdUniqueness(result);

        return result;
    }

    private static void validateNodeTypeIdUniqueness(Map<String, GraphitronType> types) {
        var byTypeId = new LinkedHashMap<String, List<NodeType>>();
        for (var type : types.values()) {
            if (type instanceof NodeType nt) {
                byTypeId.computeIfAbsent(nt.typeId(), k -> new ArrayList<>()).add(nt);
            }
        }
        for (var entry : byTypeId.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            String typeId = entry.getKey();
            List<String> colliding = entry.getValue().stream().map(NodeType::name).sorted().toList();
            String others = String.join(", ", colliding);
            for (var nt : entry.getValue()) {
                types.put(nt.name(), new UnclassifiedType(nt.name(), nt.location(),
                    "typeId '" + typeId + "' is declared on multiple types (" + others
                    + ") — Query.node dispatch would be nondeterministic; pick one via @node(typeId:)"));
            }
        }
    }

    private GraphitronType enrichTableInterfaceType(TableInterfaceType type) {
        var participants = buildParticipantList(implementorNames(type.name()), false);
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new TableInterfaceType(type.name(), type.location(), type.discriminatorColumn(), type.table(), participants.list());
    }

    private GraphitronType enrichInterfaceType(InterfaceType type) {
        var participants = buildParticipantList(implementorNames(type.name()), true);
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new InterfaceType(type.name(), type.location(), participants.list());
    }

    private GraphitronType enrichUnionType(UnionType type) {
        var unionType = (GraphQLUnionType) ctx.schema.getType(type.name());
        var names = unionType.getTypes().stream().map(t -> t.getName()).toList();
        var participants = buildParticipantList(names, false);
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new UnionType(type.name(), type.location(), participants.list());
    }

    private List<String> implementorNames(String interfaceName) {
        var iface = (GraphQLInterfaceType) ctx.schema.getType(interfaceName);
        return ctx.schema.getImplementations(iface).stream().map(obj -> obj.getName()).toList();
    }

    /**
     * @param allowNestingAsUnbound when {@code true}, plain object types without domain
     *     directives are accepted as {@link ParticipantRef.Unbound} — they are nesting types
     *     whose fields expand against the parent table. When {@code false} (union and
     *     {@code TableInterfaceType} participants), every member must be table-bound or
     *     carry a domain directive; plain object types are an error.
     */
    private ParticipantListResult buildParticipantList(List<String> typeNames, boolean allowNestingAsUnbound) {
        var result = new ArrayList<ParticipantRef>();
        var errors = new ArrayList<String>();
        for (var typeName : typeNames) {
            var gt = ctx.types.get(typeName);
            if (gt instanceof TableBackedType tbt && !(gt instanceof TableInterfaceType)) {
                String discriminatorValue = argString(ctx.schema.getObjectType(typeName), DIR_DISCRIMINATOR, ARG_VALUE).orElse(null);
                result.add(new ParticipantRef.TableBound(typeName, tbt.table(), discriminatorValue));
            } else if (gt instanceof PlainObjectType && allowNestingAsUnbound) {
                // Plain SDL object types join as nesting participants only in contexts that
                // allow nesting (plain InterfaceType). Unions and TableInterfaceType require
                // a domain directive or table binding.
                result.add(new ParticipantRef.Unbound(typeName));
            } else if (gt != null && !(gt instanceof UnclassifiedType) && !(gt instanceof PlainObjectType)) {
                result.add(new ParticipantRef.Unbound(typeName));
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
        if (namedType instanceof graphql.schema.GraphQLScalarType) {
            return null;
        }
        // Federation-injected types (e.g. _Service, _Any) are not Graphitron-managed.
        if (namedType.getName().startsWith("_")) {
            return null;
        }
        if (namedType instanceof graphql.schema.GraphQLEnumType enumType) {
            return new no.sikt.graphitron.rewrite.model.GraphitronType.EnumType(
                enumType.getName(), locationOf(enumType), enumType);
        }
        // Directive-argument input types (ErrorHandler, ReferencesForType, etc.) exist only to
        // shape Graphitron's own build-time directives. They must not reach emission, so the
        // classifier skips them entirely — they never enter schema.types().
        if (namedType instanceof GraphQLInputObjectType
                && no.sikt.graphitron.rewrite.generators.schema.InputDirectiveInputTypes.NAMES
                    .contains(namedType.getName())) {
            return null;
        }
        if (namedType instanceof GraphQLInputObjectType inputType) {
            return buildInputType(inputType);
        }

        String name = namedType.getName();
        SourceLocation location = locationOf(namedType);

        if (namedType instanceof GraphQLObjectType objType) {
            if (ROOT_TYPE_NAMES.contains(name)) {
                return new RootType(name, location);
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
            // Plain SDL object type — no domain directive. Record it in the model so
            // emitters can iterate schema.types() without an assembled-schema fallback.
            return new PlainObjectType(name, location, objType);
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

        // Platform-id synthesis. The malformed-metadata diagnostic runs unconditionally so SDL
        // authors see the issue even when they try to override values with explicit @node.
        // Beyond that, NodeType promotion is opt-in via `implements Node @node`: a `@table` type
        // without `@node` stays a TableType regardless of whether the backing jOOQ class carries
        // node-id metadata. Auto-promoting on metadata alone silently collided typeIds across
        // types whose backing tables shared `__NODE_TYPE_ID`, with no SDL-side opt-out.
        Optional<String> metadataDiagnostic = ctx.catalog.nodeIdMetadataDiagnostic(tableRef.tableName());
        if (metadataDiagnostic.isPresent()) {
            return new UnclassifiedType(name, location,
                "KjerneJooqGenerator metadata on table '" + tableRef.tableName() + "' is malformed: "
                + metadataDiagnostic.get());
        }
        Optional<JooqCatalog.NodeIdMetadata> metadata = ctx.catalog.nodeIdMetadata(tableRef.tableName());

        boolean hasNode = objType.hasAppliedDirective(DIR_NODE);
        if (!hasNode) {
            return new TableType(name, location, tableRef);
        }

        // @node declared — the type must implement the Relay Node interface (id: ID!).
        // `implements Node` is a schema-level contract published to clients; we cannot promote
        // a type to NodeType without it.
        if (!implementsNode(objType)) {
            return new UnclassifiedType(name, location,
                "@node requires the type to implement the Relay Node interface — add 'implements Node' to the type declaration");
        }

        // Resolve SDL-declared values.
        String sdlTypeId = argString(objType, DIR_NODE, ARG_TYPE_ID).orElse(null);
        List<String> sdlKeyColumnNames = argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS);
        var keyColumnErrors = new ArrayList<String>();
        var sdlKeyColumns = new ArrayList<ColumnRef>();
        for (String colName : sdlKeyColumnNames) {
            Optional<ColumnRef> kc = svc.resolveKeyColumn(colName, tableRef.tableName());
            if (kc.isEmpty()) {
                keyColumnErrors.add("key column '" + colName + "' in @node could not be resolved in the jOOQ table"
                    + candidateHint(colName, ctx.catalog.columnJavaNamesOf(tableRef.tableName())));
            } else {
                sdlKeyColumns.add(kc.get());
            }
        }
        if (!keyColumnErrors.isEmpty()) {
            return new UnclassifiedType(name, location, String.join("; ", keyColumnErrors));
        }

        if (metadata.isEmpty()) {
            // @node-only path: SDL values win verbatim on any declared axis; fill the omitted
            // ones from sensible defaults (docs: typeId defaults to type name, keyColumns to PK).
            String resolvedTypeId = sdlTypeId != null ? sdlTypeId : name;
            List<ColumnRef> resolvedKeyColumns;
            if (!sdlKeyColumnNames.isEmpty()) {
                resolvedKeyColumns = List.copyOf(sdlKeyColumns);
            } else {
                var pk = ctx.catalog.findPkColumns(tableRef.tableName()).stream()
                    .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()))
                    .toList();
                if (pk.isEmpty()) {
                    return new UnclassifiedType(name, location,
                        "@node on " + name + " omits keyColumns but table '" + tableRef.tableName()
                        + "' has no primary key — declare `keyColumns:` on @node or add a primary key");
                }
                resolvedKeyColumns = pk;
            }
            return new NodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns);
        }

        // Both @node and metadata present. SDL wins — it is the author's published wire-format
        // contract, decoupled from whatever the jOOQ generator happens to output:
        //  - typeId: SDL overrides silently. The entire point of @node(typeId:) is to let
        //    authors pin the wire format independent of the jOOQ table name.
        //  - keyColumns: SDL overrides. If the column *sets* differ, one side is wrong about
        //    the schema — hard error. If sets are equal but *order* differs, SDL wins with a
        //    WARN (the author pinned a specific order; worth surfacing but not blocking).
        //  - Values omitted on an axis fall through to metadata.
        var meta = metadata.get();
        String resolvedTypeId = sdlTypeId != null ? sdlTypeId : meta.typeId();
        List<ColumnRef> resolvedKeyColumns;
        if (sdlKeyColumnNames.isEmpty()) {
            resolvedKeyColumns = List.copyOf(meta.keyColumns());
        } else {
            if (!columnSetsMatch(sdlKeyColumns, meta.keyColumns())) {
                return new UnclassifiedType(name, location,
                    "@node(keyColumns: " + keyColumnsLiteral(sdlKeyColumns)
                    + ") on " + name + " disagrees with KjerneJooqGenerator metadata (keyColumns: "
                    + keyColumnsLiteral(meta.keyColumns())
                    + ") — the column sets are different; one side is wrong about the schema");
            }
            if (!columnListsMatch(sdlKeyColumns, meta.keyColumns())) {
                LOGGER.warn("@node(keyColumns: {}) on {} pins an order different from KjerneJooqGenerator metadata ({}); SDL order wins",
                    keyColumnsLiteral(sdlKeyColumns), name, keyColumnsLiteral(meta.keyColumns()));
            }
            resolvedKeyColumns = List.copyOf(sdlKeyColumns);
        }
        return new NodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns);
    }

    private static boolean implementsNode(GraphQLObjectType objType) {
        return objType.getInterfaces().stream()
            .anyMatch(i -> "Node".equals(((GraphQLNamedType) i).getName()));
    }

    private static boolean columnListsMatch(List<ColumnRef> a, List<ColumnRef> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sqlName().equalsIgnoreCase(b.get(i).sqlName())) return false;
        }
        return true;
    }

    private static boolean columnSetsMatch(List<ColumnRef> a, List<ColumnRef> b) {
        if (a.size() != b.size()) return false;
        var aNames = a.stream().map(c -> c.sqlName().toLowerCase()).collect(Collectors.toSet());
        var bNames = b.stream().map(c -> c.sqlName().toLowerCase()).collect(Collectors.toSet());
        return aNames.equals(bNames);
    }

    private static String keyColumnsLiteral(List<ColumnRef> cols) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(cols.get(i).sqlName()).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Reflects on the backing Java class named in {@code @record(record: {className:})} and
     * constructs the appropriate {@link ResultType} sub-type.
     */
    private GraphitronType buildResultType(GraphQLObjectType objType, String name, SourceLocation location) {
        var dir = objType.getAppliedDirective(DIR_RECORD);
        if (dir == null) return new GraphitronType.PojoResultType(name, location, null);
        var recordArg = dir.getArgument(ARG_RECORD);
        if (recordArg == null || recordArg.getValue() == null) {
            return new GraphitronType.PojoResultType(name, location, null);
        }
        Map<String, Object> ref = asMap(recordArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        if (className == null) {
            return new GraphitronType.PojoResultType(name, location, null);
        }
        try {
            Class<?> cls = Class.forName(className);
            if (cls.isRecord()) {
                return new GraphitronType.JavaRecordType(name, location, className);
            }
            if (org.jooq.TableRecord.class.isAssignableFrom(cls)) {
                TableRef table = svc.resolveTableByRecordClass(cls).orElse(null);
                return new GraphitronType.JooqTableRecordType(name, location, className, table);
            }
            if (org.jooq.Record.class.isAssignableFrom(cls)) {
                return new GraphitronType.JooqRecordType(name, location, className);
            }
            return new GraphitronType.PojoResultType(name, location, className);
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
        String discriminatorRaw = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse(null);
        // Resolve to the SQL column name so generators can use DSL.name(col) with the correct
        // casing. findColumn accepts both Java names and SQL names. Falls back to the raw value
        // when unresolvable (the validator will report the bad column name).
        JooqCatalog.ColumnEntry discriminatorEntry = discriminatorRaw == null ? null
            : ctx.catalog.findColumn(tableOpt.get().tableName(), discriminatorRaw).orElse(null);
        if (discriminatorEntry != null && !discriminatorRaw.equals(discriminatorEntry.javaName())) {
            LOGGER.warn("@discriminate(on: '{}') on '{}' resolved via SQL name; prefer Java field name '{}'",
                discriminatorRaw, name, discriminatorEntry.javaName());
        }
        String discriminatorColumn = discriminatorEntry != null ? discriminatorEntry.sqlName() : discriminatorRaw;
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
        // @record dominates @table on input types: legacy treats the combination as @record-only
        // (six legacy paths skip when hasJavaRecordReference() is true). The rewrite used to fall
        // into the @table branch first, attempt to resolve the input's fields as columns, and
        // fail. Warn and route through @record instead so schemas using the combination still
        // classify. See `@table + @record input-type fix` in graphitron-rewrite/roadmap/changelog.md.
        if (inputType.hasAppliedDirective(DIR_TABLE) && inputType.hasAppliedDirective(DIR_RECORD)) {
            ctx.addWarning(new BuildWarning(
                "Input type '" + name + "': @table is shadowed by @record and is ignored. "
                + "This combination is not supported — remove @table from this input.",
                location));
            return buildNonTableInputType(inputType, name, location);
        }
        if (inputType.hasAppliedDirective(DIR_TABLE)) {
            String tableName = argString(inputType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
            Optional<TableRef> tableOpt = svc.resolveTable(tableName);
            if (tableOpt.isEmpty()) {
                return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog"
                    + candidateHint(tableName, ctx.catalog.allTableSqlNames()));
            }
            return buildTableInputType(name, location, inputType.getFieldDefinitions(), tableOpt.get(), inputType);
        }
        if (isUsedWithOverrideCondition(name)) {
            return buildNonTableInputType(inputType, name, location);
        }
        var tables = findReturnTablesForInput(name);
        if (tables.isEmpty()) {
            return buildNonTableInputType(inputType, name, location);
        }
        if (tables.size() > 1) {
            return buildNonTableInputType(inputType, name, location);
        }
        return buildTableInputType(name, location, inputType.getFieldDefinitions(), tables.values().iterator().next(), inputType);
    }

    /**
     * Resolves a list of raw input fields against a {@link TableRef} into a {@link TableInputType}.
     */
    GraphitronType buildTableInputType(String name, SourceLocation location,
            List<GraphQLInputObjectField> fields, TableRef tableRef, GraphQLInputObjectType inputType) {
        var failures = new ArrayList<InputFieldResolution.Unresolved>();
        var conditionErrors = new ArrayList<String>();
        var resolvedFields = new ArrayList<InputField>();
        for (var f : fields) {
            var resolution = ctx.classifyInputField(f, name, tableRef, new java.util.LinkedHashSet<>(), conditionErrors);
            switch (resolution) {
                case InputFieldResolution.Resolved r -> resolvedFields.add(r.field());
                case InputFieldResolution.Unresolved u -> failures.add(u);
            }
        }
        if (!failures.isEmpty()) {
            String reasons = failures.stream()
                .map(u -> "'" + u.fieldName() + "': " + u.reason())
                .collect(Collectors.joining("; "));
            String hint = failures.stream()
                .map(InputFieldResolution.Unresolved::lookupColumn)
                .filter(c -> c != null)
                .findFirst()
                .map(c -> candidateHint(c, ctx.catalog.columnJavaNamesOf(tableRef.tableName())))
                .orElse("");
            return new UnclassifiedType(name, location,
                "mapped to table '" + tableRef.tableName() + "' — unresolvable fields: " + reasons + hint);
        }
        if (!conditionErrors.isEmpty()) {
            return new UnclassifiedType(name, location,
                "mapped to table '" + tableRef.tableName() + "' — bad @condition on fields: "
                + String.join("; ", conditionErrors));
        }
        return new TableInputType(name, location, tableRef, List.copyOf(resolvedFields), inputType);
    }

    /**
     * Reflects on the backing Java class and constructs the appropriate {@link InputType} sub-type.
     */
    private GraphitronType buildNonTableInputType(GraphQLInputObjectType inputType, String name, SourceLocation location) {
        var dir = inputType.getAppliedDirective(DIR_RECORD);
        if (dir == null) return new GraphitronType.PojoInputType(name, location, null, inputType);
        var recordArg = dir.getArgument(ARG_RECORD);
        if (recordArg == null || recordArg.getValue() == null) {
            return new GraphitronType.PojoInputType(name, location, null, inputType);
        }
        Map<String, Object> ref = asMap(recordArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        if (className == null) {
            return new GraphitronType.PojoInputType(name, location, null, inputType);
        }
        try {
            Class<?> cls = Class.forName(className);
            if (cls.isRecord()) {
                return new GraphitronType.JavaRecordInputType(name, location, className, inputType);
            }
            if (org.jooq.TableRecord.class.isAssignableFrom(cls)) {
                TableRef table = svc.resolveTableByRecordClass(cls).orElse(null);
                return new GraphitronType.JooqTableRecordInputType(name, location, className, table, inputType);
            }
            if (org.jooq.Record.class.isAssignableFrom(cls)) {
                return new GraphitronType.JooqRecordInputType(name, location, className, inputType);
            }
            return new GraphitronType.PojoInputType(name, location, className, inputType);
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
                    .filter(arg -> !arg.hasAppliedDirective(DIR_ORDER_BY))
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

    /**
     * Returns true if {@code inputTypeName} is used as the type of any field argument where
     * either the field or the argument carries {@code @condition(override: true)}. When override
     * is set, the consumer supplies custom condition code, so the input's fields should not be
     * validated against table columns.
     *
     * <p>Only argument-level and field-level {@code @condition} are inspected here;
     * {@code INPUT_FIELD_DEFINITION} override is also checked: if any field in the input type
     * itself carries {@code @condition(override: true)}, the whole type bypasses column validation.
     */
    private boolean isUsedWithOverrideCondition(String inputTypeName) {
        var typeFromSchema = ctx.schema.getType(inputTypeName);
        if (typeFromSchema instanceof GraphQLInputObjectType iot) {
            for (var fieldDef : iot.getFieldDefinitions()) {
                if (fieldDef.hasAppliedDirective(DIR_CONDITION)
                        && argBoolean(fieldDef, DIR_CONDITION, ARG_OVERRIDE, false)) return true;
            }
        }
        for (var namedType : ctx.schema.getAllTypesAsList()) {
            if (!(namedType instanceof GraphQLObjectType objType)) continue;
            if (namedType.getName().startsWith("__")) continue;
            for (var fieldDef : objType.getFieldDefinitions()) {
                boolean fieldOverride = fieldDef.hasAppliedDirective(DIR_CONDITION)
                    && argBoolean(fieldDef, DIR_CONDITION, ARG_OVERRIDE, false);
                for (var arg : fieldDef.getArguments()) {
                    String argTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName();
                    if (!inputTypeName.equals(argTypeName)) continue;
                    if (fieldOverride) return true;
                    if (arg.hasAppliedDirective(DIR_CONDITION)
                            && argBoolean(arg, DIR_CONDITION, ARG_OVERRIDE, false)) return true;
                }
            }
        }
        return false;
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

    // ===== Result container =====

    private record ParticipantListResult(List<ParticipantRef> list, String error) {}
}
