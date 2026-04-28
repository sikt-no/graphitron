package no.sikt.graphitron.rewrite.schema.federation;

import graphql.language.BooleanValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.KeyAlternative.KeyShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the {@code entitiesByType} sidecar map: one {@link EntityResolution} per type whose
 * SDL declaration carries a federation {@code @key} directive (or {@code @node}, which is
 * synthesised into a {@code @key(fields: "id", resolvable: true)} by
 * {@link KeyNodeSynthesiser} ahead of classify time).
 *
 * <p>Run after {@link no.sikt.graphitron.rewrite.TypeBuilder} has produced the type map and
 * after {@link no.sikt.graphitron.rewrite.FieldBuilder} has classified each field (so column
 * refs are available). The result is threaded into
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema}'s {@code entitiesByType} sidecar.
 *
 * <h3>Shape selection</h3>
 * <ul>
 *   <li>{@code @key(fields: "id")} on a {@link NodeType} → {@link KeyShape#NODE_ID}, columns
 *       are the type's {@code nodeKeyColumns}; the rep's id is decoded by
 *       {@code NodeIdEncoder.decodeValues} at runtime.</li>
 *   <li>Any other {@code @key} → {@link KeyShape#DIRECT}, columns resolved by walking the
 *       referenced fields and reading their {@link ColumnRef}.</li>
 * </ul>
 *
 * <h3>Errors</h3>
 * <p>Validation failures are recorded as {@link UnclassifiedType} demotions on the type map
 * (same channel as {@link no.sikt.graphitron.rewrite.TypeBuilder} uses for unresolvable
 * {@code @table}s). Causes:
 * <ul>
 *   <li>Malformed {@code fields:} string — caught by {@link FederationKeyFieldsParser}</li>
 *   <li>{@code @key} on a non-table-bound type, or on {@link TableInterfaceType}</li>
 *   <li>{@code @key(fields:)} references a field that is not a column</li>
 * </ul>
 */
public final class EntityResolutionBuilder {

    private static final String KEY_DIRECTIVE = "key";
    private static final String FIELDS_ARG = "fields";
    private static final String RESOLVABLE_ARG = "resolvable";
    private static final String ID_FIELD = "id";

    private EntityResolutionBuilder() {}

    /**
     * Walks the classified type map and returns an entity-resolution entry for every type
     * whose schema element carries at least one {@code @key} directive. Demotes types whose
     * {@code @key} directives cannot be resolved to {@link UnclassifiedType} in place on
     * {@code types}.
     *
     * @param types the classifier's type map (mutable: failed types are replaced in place)
     * @param fields the classifier's field map (read-only)
     * @param assembled the assembled graphql-java schema (read-only)
     * @param warningSink receiver for non-fatal advisories (e.g. compound key on @node type
     *                    that includes "id"); typically {@code BuildContext::addWarning}
     */
    public static Map<String, EntityResolution> build(
        Map<String, GraphitronType> types,
        Map<FieldCoordinates, GraphitronField> fields,
        GraphQLSchema assembled,
        Consumer<BuildWarning> warningSink
    ) {
        var out = new LinkedHashMap<String, EntityResolution>();
        for (var entry : List.copyOf(types.entrySet())) {
            String typeName = entry.getKey();
            GraphitronType gType = entry.getValue();
            GraphQLNamedType assembledType = assembled.getType(typeName) instanceof GraphQLNamedType nt ? nt : null;
            if (!(assembledType instanceof GraphQLObjectType objType)) continue;
            var keys = objType.getAppliedDirectives(KEY_DIRECTIVE);
            // NodeTypes always get an entity entry: their NODE_ID alternative is the
            // canonical SELECT path used by Query.node, Query.nodes, and federation
            // _entities — regardless of whether federation @link is present. Skip only
            // when neither @key nor @node applies.
            boolean isNodeType = gType instanceof NodeType;
            if (keys.isEmpty() && !isNodeType) continue;

            // @key on a TableInterfaceType is rejected — see Non-goals on the federation plan.
            if (gType instanceof TableInterfaceType) {
                types.put(typeName, new UnclassifiedType(typeName, gType.location(),
                    "@key on TableInterfaceType is not supported; declare @key on the implementing types instead"));
                continue;
            }
            // Federation entities require a backing table (the dispatcher SELECTs from it).
            if (!(gType instanceof TableType || gType instanceof NodeType)) {
                types.put(typeName, new UnclassifiedType(typeName, gType.location(),
                    "@key requires a @table-bound type; '" + typeName
                    + "' has no @table directive"));
                continue;
            }

            var alternatives = new ArrayList<KeyAlternative>();
            String error = null;
            if (keys.isEmpty() && isNodeType) {
                // No federation @link, so KeyNodeSynthesiser did not run; but the type is a
                // NodeType, so we synthesise the NODE_ID alternative directly. This is the
                // path Query.node / Query.nodes use to dispatch via the entity dispatcher.
                NodeType nt = (NodeType) gType;
                alternatives.add(new KeyAlternative(
                    List.of(ID_FIELD), List.copyOf(nt.nodeKeyColumns()), true, KeyShape.NODE_ID));
            } else {
                for (var key : keys) {
                    var alt = buildAlternative(typeName, gType, key, fields, warningSink);
                    if (alt instanceof AltResult.Ok ok) {
                        alternatives.add(ok.alt());
                    } else if (alt instanceof AltResult.Err err) {
                        error = err.message();
                        break;
                    }
                }
            }
            if (error != null) {
                types.put(typeName, new UnclassifiedType(typeName, gType.location(), error));
                continue;
            }

            var tableBacked = (GraphitronType.TableBackedType) types.get(typeName);
            String nodeTypeId = tableBacked instanceof NodeType nt ? nt.typeId() : null;
            out.put(typeName, new EntityResolution(typeName, tableBacked.table(), List.copyOf(alternatives), nodeTypeId));
        }
        return Map.copyOf(out);
    }

    private sealed interface AltResult {
        record Ok(KeyAlternative alt) implements AltResult {}
        record Err(String message) implements AltResult {}
    }

    private static AltResult buildAlternative(
        String typeName,
        GraphitronType gType,
        GraphQLAppliedDirective keyDirective,
        Map<FieldCoordinates, GraphitronField> fields,
        Consumer<BuildWarning> warningSink
    ) {
        String fieldsValue = readFieldsArg(keyDirective);
        if (fieldsValue == null) {
            return new AltResult.Err("@key directive on type '" + typeName
                + "' is missing required argument 'fields'");
        }
        List<String> required;
        try {
            required = FederationKeyFieldsParser.parse(fieldsValue);
        } catch (FederationKeyFieldsParser.ParseException e) {
            return new AltResult.Err("@key on type '" + typeName + "': " + e.getMessage());
        }
        boolean resolvable = readResolvableArg(keyDirective);

        // NODE_ID shape: a NodeType with a single "id" required field uses the node's
        // nodeKeyColumns and decodes the rep's id through NodeIdEncoder at runtime.
        if (gType instanceof NodeType nodeType
            && required.size() == 1
            && ID_FIELD.equals(required.get(0))) {
            return new AltResult.Ok(new KeyAlternative(
                List.copyOf(required),
                List.copyOf(nodeType.nodeKeyColumns()),
                resolvable,
                KeyShape.NODE_ID));
        }

        // DIRECT shape: each required field name maps to a ColumnRef on the type's table.
        var columns = new ArrayList<ColumnRef>();
        for (String name : required) {
            ColumnRef col = lookupColumn(typeName, name, fields);
            if (col == null) {
                return new AltResult.Err("@key(fields: \"" + fieldsValue + "\") on type '"
                    + typeName + "' references field '" + name
                    + "' which is not a column-backed field on this type's table");
            }
            columns.add(col);
        }
        if (gType instanceof NodeType && required.contains(ID_FIELD)) {
            // Compound key that includes "id" on a @node type: we treat it as DIRECT (the rep
            // carries each required field individually, including the id), not NODE_ID.
            // Surface a warning so the consumer is aware their compound key bypasses the
            // global-id decode path.
            warningSink.accept(new BuildWarning(
                "@key(fields: \"" + fieldsValue + "\") on @node type '" + typeName
                + "' includes 'id' alongside other fields; the id is treated as a column value "
                + "rather than a global NodeId. Federation reps for this alternative must "
                + "carry the column-level id, not a base64-encoded NodeId.",
                gType.location()));
        }
        return new AltResult.Ok(new KeyAlternative(
            List.copyOf(required),
            List.copyOf(columns),
            resolvable,
            KeyShape.DIRECT));
    }

    private static String readFieldsArg(GraphQLAppliedDirective key) {
        var arg = key.getArgument(FIELDS_ARG);
        if (arg == null) return null;
        Object v = arg.getValue();
        if (v instanceof StringValue sv) return sv.getValue();
        if (v instanceof String s) return s;
        return null;
    }

    private static boolean readResolvableArg(GraphQLAppliedDirective key) {
        var arg = key.getArgument(RESOLVABLE_ARG);
        if (arg == null) return true;
        Object v = arg.getValue();
        if (v instanceof BooleanValue bv) return bv.isValue();
        if (v instanceof Boolean b) return b;
        return true;
    }

    private static ColumnRef lookupColumn(
        String typeName, String fieldName, Map<FieldCoordinates, GraphitronField> fields
    ) {
        var coords = FieldCoordinates.coordinates(typeName, fieldName);
        var field = fields.get(coords);
        if (field == null) return null;
        if (field instanceof ChildField.ColumnField cf) return cf.column();
        if (field instanceof ChildField.ColumnReferenceField cref) return cref.column();
        return null;
    }
}
