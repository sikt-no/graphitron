package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.ErrorHandlerType;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HelperRef;
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
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.Rejection;
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
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
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
                    Rejection.structural("typeId '" + typeId + "' is declared on multiple types (" + others
                    + ") — Query.node dispatch would be nondeterministic; pick one via @node(typeId:)")));
            }
        }
    }

    private GraphitronType enrichTableInterfaceType(TableInterfaceType type) {
        var participants = buildParticipantList(implementorNames(type.name()), false, type.table());
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), Rejection.structural(participants.error()));
        }
        return new TableInterfaceType(type.name(), type.location(), type.discriminatorColumn(), type.table(), participants.list());
    }

    private GraphitronType enrichInterfaceType(InterfaceType type) {
        var participants = buildParticipantList(implementorNames(type.name()), true, null);
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), Rejection.structural(participants.error()));
        }
        return new InterfaceType(type.name(), type.location(), participants.list());
    }

    private GraphitronType enrichUnionType(UnionType type) {
        var unionType = (GraphQLUnionType) ctx.schema.getType(type.name());
        var names = unionType.getTypes().stream().map(t -> t.getName()).toList();
        var participants = buildParticipantList(names, false, null);
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), Rejection.structural(participants.error()));
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
     * @param interfaceTable the {@code TableInterfaceType}'s own table when building participants
     *     for a single-table interface. Used to detect each participant's cross-table fields
     *     (those whose {@code @reference} terminates on a different table than the interface
     *     table); the resulting {@link ParticipantRef.TableBound.CrossTableField} list drives
     *     the conditional LEFT JOIN emission in {@code TypeFetcherGenerator}. {@code null} for
     *     plain {@link InterfaceType} and {@link UnionType} contexts, which do not project
     *     cross-table fields through this path.
     */
    private ParticipantListResult buildParticipantList(List<String> typeNames, boolean allowNestingAsUnbound,
                                                       TableRef interfaceTable) {
        var result = new ArrayList<ParticipantRef>();
        var errors = new ArrayList<String>();
        for (var typeName : typeNames) {
            var gt = ctx.types.get(typeName);
            if (gt instanceof TableBackedType tbt && !(gt instanceof TableInterfaceType)) {
                String discriminatorValue = argString(ctx.schema.getObjectType(typeName), DIR_DISCRIMINATOR, ARG_VALUE).orElse(null);
                List<ParticipantRef.TableBound.CrossTableField> crossTableFields = interfaceTable != null
                    ? extractCrossTableFields(typeName, interfaceTable)
                    : List.of();
                result.add(new ParticipantRef.TableBound(typeName, tbt.table(), discriminatorValue, crossTableFields));
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

    /**
     * Walks the participant type's GraphQL field definitions and collects each scalar field
     * whose {@code @reference} traverses a single-hop FK to a table other than {@code interfaceTable}.
     * The interface fetcher emits a conditional LEFT JOIN (gated by the participant's discriminator
     * value) per field returned here; the per-field DataFetcher reads the projected value back
     * from the result {@code Record} by the {@code aliasName} we choose now.
     *
     * <p>Fields that don't fit the pattern (no {@code @reference}, multi-hop path, condition-only
     * step, or path resolving to the interface's own table) are silently ignored — they reach the
     * field-level classifier through the normal {@code FieldBuilder} path.
     */
    private List<ParticipantRef.TableBound.CrossTableField> extractCrossTableFields(
            String participantTypeName, TableRef interfaceTable) {
        var participantObj = ctx.schema.getObjectType(participantTypeName);
        if (participantObj == null) return List.of();
        var out = new ArrayList<ParticipantRef.TableBound.CrossTableField>();
        for (var fieldDef : participantObj.getFieldDefinitions()) {
            if (!fieldDef.hasAppliedDirective(DIR_REFERENCE)) continue;
            String fieldName = fieldDef.getName();
            var parsed = ctx.parsePath(fieldDef, fieldName, interfaceTable.tableName(), null);
            if (parsed.hasError()) continue;
            if (parsed.elements().size() != 1) continue;
            if (!(parsed.elements().get(0) instanceof JoinStep.FkJoin fk)) continue;
            if (fk.targetTable().tableName().equalsIgnoreCase(interfaceTable.tableName())) continue;

            // Resolve the column on the target table that the field maps to. @field(name:) is the
            // primary signal; fall back to the GraphQL field name when the directive is absent.
            String columnSqlName = argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(fieldName);
            var columnEntry = ctx.catalog.findColumn(fk.targetTable().tableName(), columnSqlName).orElse(null);
            if (columnEntry == null) continue;
            var column = new ColumnRef(columnEntry.sqlName(), columnEntry.javaName(), columnEntry.columnClass());

            String aliasName = participantTypeName + "_" + fieldName;
            out.add(new ParticipantRef.TableBound.CrossTableField(fieldName, column, fk, aliasName));
        }
        return List.copyOf(out);
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
            String inertness = checkEnumArgMappingInert(enumType);
            if (inertness != null) {
                return new UnclassifiedType(enumType.getName(), locationOf(enumType), Rejection.structural(inertness));
            }
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
            var typeConflict = detectTypeDirectiveConflict(objType);
            if (typeConflict != null) {
                return new UnclassifiedType(name, location, typeConflict);
            }
            if (objType.hasAppliedDirective(DIR_TABLE)) {
                return buildTableType(objType);
            }
            if (objType.hasAppliedDirective(DIR_ERROR)) {
                return buildErrorType(objType);
            }
            if (objType.hasAppliedDirective(DIR_RECORD)) {
                return buildResultType(objType, name, location);
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
            return new UnclassifiedType(name, location, Rejection.unknownTable(
                "table '" + tableName + "' could not be resolved in the jOOQ catalog",
                tableName, ctx.catalog.allTableSqlNames()));
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
            return new UnclassifiedType(name, location, Rejection.structural(
                "KjerneJooqGenerator metadata on table '" + tableRef.tableName() + "' is malformed: "
                + metadataDiagnostic.get()));
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
            return new UnclassifiedType(name, location, Rejection.structural(
                "@node requires the type to implement the Relay Node interface — add 'implements Node' to the type declaration"));
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
            return new UnclassifiedType(name, location, Rejection.structural(String.join("; ", keyColumnErrors)));
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
                    return new UnclassifiedType(name, location, Rejection.structural(
                        "@node on " + name + " omits keyColumns but table '" + tableRef.tableName()
                        + "' has no primary key — declare `keyColumns:` on @node or add a primary key"));
                }
                resolvedKeyColumns = pk;
            }
            return buildNodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns);
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
                return new UnclassifiedType(name, location, Rejection.structural(
                    "@node(keyColumns: " + keyColumnsLiteral(sdlKeyColumns)
                    + ") on " + name + " disagrees with KjerneJooqGenerator metadata (keyColumns: "
                    + keyColumnsLiteral(meta.keyColumns())
                    + ") — the column sets are different; one side is wrong about the schema"));
            }
            if (!columnListsMatch(sdlKeyColumns, meta.keyColumns())) {
                LOGGER.warn("@node(keyColumns: {}) on {} pins an order different from KjerneJooqGenerator metadata ({}); SDL order wins",
                    keyColumnsLiteral(sdlKeyColumns), name, keyColumnsLiteral(meta.keyColumns()));
            }
            resolvedKeyColumns = List.copyOf(sdlKeyColumns);
        }
        return buildNodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns);
    }

    /**
     * Constructs a {@link NodeType} with pre-resolved {@link HelperRef} references for the
     * per-type {@code encode<TypeName>} / {@code decode<TypeName>} helpers. The encoder class is
     * the same {@link NodeIdEncoderClassGenerator#CLASS_NAME} emitted under
     * {@code outputPackage + ".util"}; the helper method name is derived from the GraphQL type
     * name (not the {@code typeId}, which is the wire string and may differ).
     */
    private NodeType buildNodeType(String name, SourceLocation location, TableRef tableRef,
                                   String typeId, List<ColumnRef> keyColumns) {
        ClassName encoderClass = ClassName.get(
            ctx.ctx.outputPackage() + ".util",
            NodeIdEncoderClassGenerator.CLASS_NAME);
        var encodeMethod = new HelperRef.Encode(encoderClass, "encode" + name, keyColumns);
        var decodeMethod = new HelperRef.Decode(encoderClass, "decode" + name, keyColumns);
        return new NodeType(name, location, tableRef, typeId, keyColumns, encodeMethod, decodeMethod);
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
        String inertness = checkArgMappingInert(ref, "record");
        if (inertness != null) {
            return new UnclassifiedType(name, location, Rejection.structural(inertness));
        }
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
            return new UnclassifiedType(name, location, Rejection.structural(
                "record backing class '" + className + "' could not be loaded"));
        }
    }

    private GraphitronType buildTableInterfaceType(GraphQLInterfaceType iface) {
        String name = iface.getName();
        SourceLocation location = locationOf(iface);
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = svc.resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, Rejection.unknownTable(
                "table '" + tableName + "' could not be resolved in the jOOQ catalog",
                tableName, ctx.catalog.allTableSqlNames()));
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

    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "error-type.path-message-fields",
        description = "Every @error type declares exactly path: [String!]! and message: String!; "
            + "extras and structural mismatches are rejected as UnclassifiedType. The matched "
            + "throwable itself is placed into the payload's errors list and graphql-java's "
            + "PropertyDataFetcher reads each declared SDL field directly from the source at "
            + "serialisation time. The per-(channel, @error type, handler) source-class accessor "
            + "reflection check is the runtime-side guarantee; this schema-level rule is the "
            + "SDL-side anchor.")
    private GraphitronType buildErrorType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);

        // Structural field check (rule 6 + path/message contract):
        // every @error type declares exactly path: [String!]! and message: String!.
        // Producer side of the load-bearing classifier check
        // "error-type.path-message-fields"; the consumer is the per-(channel, @error type,
        // handler) source-class accessor check that lands in step 5.
        List<String> rejectReasons = new ArrayList<>();
        var pathField = objType.getFieldDefinition("path");
        if (pathField == null) {
            rejectReasons.add("missing required field 'path: [String!]!'");
        } else if (!isStringNonNullListNonNull(pathField.getType())) {
            rejectReasons.add("'path' must be declared as [String!]! (got '"
                + GraphQLTypeUtil.simplePrint(pathField.getType()) + "')");
        }
        var messageField = objType.getFieldDefinition("message");
        if (messageField == null) {
            rejectReasons.add("missing required field 'message: String!'");
        } else if (!isStringNonNull(messageField.getType())) {
            rejectReasons.add("'message' must be declared as String! (got '"
                + GraphQLTypeUtil.simplePrint(messageField.getType()) + "')");
        }
        var extraFields = objType.getFieldDefinitions().stream()
            .map(GraphQLFieldDefinition::getName)
            .filter(n -> !"path".equals(n) && !"message".equals(n))
            .toList();
        if (!extraFields.isEmpty()) {
            rejectReasons.add("@error types may only declare 'path' and 'message'; additional fields not allowed: "
                + String.join(", ", extraFields));
        }

        var dir = objType.getAppliedDirective(DIR_ERROR);
        var handlersArg = dir.getArgument(ARG_HANDLERS);
        Object value = handlersArg.getValue();
        List<?> items = value instanceof List<?> l ? l : value == null ? List.of() : List.of(value);
        List<ErrorType.Handler> handlers = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            ErrorType.Handler h = parseErrorHandler(asMap(item), rejectReasons);
            if (h != null) handlers.add(h);
        }

        if (!rejectReasons.isEmpty()) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "@error type rejected: " + String.join("; ", rejectReasons)));
        }
        return new ErrorType(name, location, List.copyOf(handlers));
    }

    private static boolean isStringNonNull(GraphQLType type) {
        if (!(type instanceof GraphQLNonNull nn)) return false;
        return nn.getWrappedType() instanceof GraphQLScalarType st && "String".equals(st.getName());
    }

    private static boolean isStringNonNullListNonNull(GraphQLType type) {
        if (!(type instanceof GraphQLNonNull outer)) return false;
        if (!(outer.getWrappedType() instanceof GraphQLList list)) return false;
        return isStringNonNull(list.getWrappedType());
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
                return new UnclassifiedType(name, location, Rejection.unknownTable(
                    "table '" + tableName + "' could not be resolved in the jOOQ catalog",
                    tableName, ctx.catalog.allTableSqlNames()));
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
            return new UnclassifiedType(name, location, Rejection.structural(
                "mapped to table '" + tableRef.tableName() + "' — unresolvable fields: " + reasons + hint));
        }
        if (!conditionErrors.isEmpty()) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "mapped to table '" + tableRef.tableName() + "' — bad @condition on fields: "
                + String.join("; ", conditionErrors)));
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
        String inertness = checkArgMappingInert(ref, "record");
        if (inertness != null) {
            return new UnclassifiedType(name, location, Rejection.structural(inertness));
        }
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
            return new UnclassifiedType(name, location, Rejection.structural(
                "record backing class '" + className + "' could not be loaded"));
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

    /**
     * Lifts one entry from the {@code handlers} array on an {@code @error} directive into the
     * sealed {@link ErrorType.Handler} variant matching the entry's discriminator. Returns
     * {@code null} and appends a reason to {@code rejectReasons} if the entry is unparseable
     * or violates one of the parse-time intra-handler reject rules (rules 1–5 and the
     * no-handler case in the {@code error-handling-parity} spec). Channel-level reject rules
     * (7–9) live with the carrier classifier; rule 6 (no fields beyond path/message) is
     * applied by the caller.
     */
    private ErrorType.Handler parseErrorHandler(Map<String, Object> item, List<String> rejectReasons) {
        Object handlerRaw = item.get(ARG_HANDLER);
        if (handlerRaw == null) {
            rejectReasons.add("@error handler entry missing required 'handler' field");
            return null;
        }
        ErrorHandlerType handlerType;
        try {
            handlerType = ErrorHandlerType.valueOf(handlerRaw.toString());
        } catch (IllegalArgumentException e) {
            rejectReasons.add("@error handler entry has unknown 'handler' value '" + handlerRaw
                + "' (expected GENERIC, DATABASE, or VALIDATION)");
            return null;
        }
        String className = strip(item.get(ARG_CLASS_NAME));
        String code = strip(item.get(ARG_CODE));
        String sqlState = strip(item.get(ARG_SQL_STATE));
        String matches = strip(item.get(ARG_MATCHES));
        String description = strip(item.get(ARG_DESCRIPTION));
        Optional<String> matchesOpt = Optional.ofNullable(matches);
        Optional<String> descriptionOpt = Optional.ofNullable(description);

        switch (handlerType) {
            case GENERIC -> {
                // Rule 1: GENERIC requires className.
                if (className == null) {
                    rejectReasons.add("@error handler {handler: GENERIC} missing required 'className'");
                    return null;
                }
                // Rule 2: GENERIC ignores SQL discriminators.
                if (sqlState != null || code != null) {
                    rejectReasons.add("@error handler {handler: GENERIC} cannot carry 'sqlState' or 'code'"
                        + " (those apply to DATABASE only)");
                    return null;
                }
                String resolveError = validateExceptionClass(className, "GENERIC");
                if (resolveError != null) {
                    rejectReasons.add(resolveError);
                    return null;
                }
                return new ErrorType.ExceptionHandler(className, matchesOpt, descriptionOpt);
            }
            case DATABASE -> {
                // Rule 3: DATABASE cannot AND both vendor discriminators.
                if (sqlState != null && code != null) {
                    rejectReasons.add("@error handler {handler: DATABASE} cannot carry both 'sqlState' and 'code'"
                        + " (vendor-conflicting); split into two entries — one per discriminator");
                    return null;
                }
                // Rule 4: DATABASE no longer matches on class identity; explicit className is misleading.
                if (className != null) {
                    rejectReasons.add("@error handler {handler: DATABASE} cannot carry 'className'"
                        + " (DATABASE matches any SQLException; use {handler: GENERIC, className: \"...\"} for class-narrowed matching)");
                    return null;
                }
                if (sqlState != null) {
                    return new ErrorType.SqlStateHandler(sqlState, matchesOpt, descriptionOpt);
                }
                if (code != null) {
                    return new ErrorType.VendorCodeHandler(code, matchesOpt, descriptionOpt);
                }
                // No-discriminator DATABASE lifts to ExceptionHandler(SQLException). The legacy
                // "DataAccessException-only" nominal match becomes a documented behaviour shift.
                // SQLException always resolves on the classifier classpath, so no Class.forName
                // check is needed here.
                return new ErrorType.ExceptionHandler("java.sql.SQLException", matchesOpt, descriptionOpt);
            }
            case VALIDATION -> {
                // Rule 5: VALIDATION takes neither discriminators nor matches.
                List<String> disallowed = new ArrayList<>();
                if (className != null) disallowed.add("className");
                if (sqlState != null) disallowed.add("sqlState");
                if (code != null) disallowed.add("code");
                if (matches != null) disallowed.add("matches");
                if (!disallowed.isEmpty()) {
                    rejectReasons.add("@error handler {handler: VALIDATION} cannot carry "
                        + String.join(", ", disallowed)
                        + " (validation runs as a wrapper pre-execution step against jakarta.validation.Validator; SQL discriminators do not apply)");
                    return null;
                }
                return new ErrorType.ValidationHandler(descriptionOpt);
            }
        }
        throw new IllegalStateException("unreachable: " + handlerType);
    }

    private static String strip(Object value) {
        if (value == null) return null;
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * Resolves an {@code ExceptionHandler.exceptionClassName} on the classifier classpath and
     * verifies it's a {@link Throwable} subtype. Returns a non-null reject reason when the
     * class cannot be loaded or doesn't extend {@code Throwable}; returns {@code null} on a
     * clean resolution. Mirrors the {@code Class.forName} check already used by
     * {@code @record(record: {className: ...})} in {@link #buildResultType}.
     *
     * <p>The runtime matcher walks the cause chain testing each link with
     * {@code Class.isInstance}; a non-{@code Throwable} class would never match anything and
     * is almost certainly a typo.
     */
    private static String validateExceptionClass(String className, String handlerKind) {
        try {
            Class<?> cls = Class.forName(className);
            if (!Throwable.class.isAssignableFrom(cls)) {
                return "@error handler {handler: " + handlerKind + ", className: \"" + className
                    + "\"} resolves to a class that does not extend java.lang.Throwable; "
                    + "the matcher walks the cause chain testing each link with isInstance, "
                    + "so a non-Throwable class would never match";
            }
            return null;
        } catch (ClassNotFoundException e) {
            return "@error handler {handler: " + handlerKind + ", className: \"" + className
                + "\"} could not be loaded on the classifier classpath";
        }
    }

    // ===== Conflict detection =====

    /**
     * Returns a reason string when mutually exclusive type-classification directives appear
     * together on one OBJECT, or {@code null} when the combination is allowed.
     *
     * <p>{@code @table}, {@code @record}, and {@code @error} are pairwise mutually exclusive.
     * {@code @table} resolves columns from jOOQ metadata; {@code @record} binds an SDL type
     * to a developer-supplied Java class; {@code @error} declares an SDL-side error shape (the
     * runtime source is the matched throwable itself; there is no developer-supplied data class
     * for an {@code @error} type).
     */
    private static Rejection.InvalidSchema.DirectiveConflict detectTypeDirectiveConflict(GraphQLObjectType objType) {
        boolean hasTable = objType.hasAppliedDirective(DIR_TABLE);
        boolean hasRecord = objType.hasAppliedDirective(DIR_RECORD);
        boolean hasError = objType.hasAppliedDirective(DIR_ERROR);
        int present = (hasTable ? 1 : 0) + (hasRecord ? 1 : 0) + (hasError ? 1 : 0);
        if (present > 1) {
            var bareNames = new java.util.ArrayList<String>();
            var atNames = new java.util.ArrayList<String>();
            if (hasTable)  { bareNames.add(DIR_TABLE);  atNames.add("@" + DIR_TABLE); }
            if (hasRecord) { bareNames.add(DIR_RECORD); atNames.add("@" + DIR_RECORD); }
            if (hasError)  { bareNames.add(DIR_ERROR);  atNames.add("@" + DIR_ERROR); }
            return new Rejection.InvalidSchema.DirectiveConflict(
                bareNames, String.join(", ", atNames) + " are mutually exclusive");
        }
        return null;
    }

    // ===== Structural helpers =====

    /**
     * Returns a rejection message when the {@code ExternalCodeReference} value at {@code ref}
     * carries a non-blank {@code argMapping} on a structurally-inert directive site, or
     * {@code null} otherwise. {@code directiveName} is included in the message bare ("record",
     * "enum") and gets prefixed with {@code @} by the caller.
     */
    private static String checkArgMappingInert(Map<String, Object> ref, String directiveName) {
        String rawArgMapping = Optional.ofNullable(ref.get(no.sikt.graphitron.rewrite.BuildContext.ARG_ARG_MAPPING))
            .map(Object::toString).orElse(null);
        if (rawArgMapping == null || rawArgMapping.isBlank()) return null;
        return "argMapping is not supported on @" + directiveName
            + " — this directive does not consume GraphQL-argument-bound parameters";
    }

    /**
     * Walks {@code @enum} on the given enum type's directives and returns a rejection message
     * when its {@code enumReference} carries a non-blank {@code argMapping}, or {@code null}
     * otherwise.
     */
    private static String checkEnumArgMappingInert(graphql.schema.GraphQLEnumType enumType) {
        var dir = enumType.getAppliedDirective("enum");
        if (dir == null) return null;
        var refArg = dir.getArgument("enumReference");
        if (refArg == null || refArg.getValue() == null) return null;
        Map<String, Object> ref = asMap(refArg.getValue());
        return checkArgMappingInert(ref, "enum");
    }

    // ===== Result container =====

    private record ParticipantListResult(List<ParticipantRef> list, String error) {}
}
