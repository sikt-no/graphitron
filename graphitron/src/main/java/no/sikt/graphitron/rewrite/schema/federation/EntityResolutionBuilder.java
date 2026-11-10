package no.sikt.graphitron.rewrite.schema.federation;

import graphql.language.BooleanValue;
import graphql.language.StringValue;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.TypeRegistry;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.KeyAlternative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.sikt.graphitron.model.schema.federation.FederationKeyFieldsParser;
import no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser;

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
 *   <li>{@code @key(fields: "id")} on a {@link NodeType} → {@link KeyAlternative.NodeId}, columns
 *       are the type's {@code nodeKeyColumns}; the rep's id is decoded by
 *       {@code NodeIdEncoder.decodeColumns} at runtime.</li>
 *   <li>Any other {@code @key} → {@link KeyAlternative.Direct}, columns resolved by walking the
 *       referenced fields and reading their {@link ColumnRef}.</li>
 * </ul>
 *
 * <h3>Refusals</h3>
 * <p>A {@code @key} this builder cannot resolve is refused by the store, as an entry defect the
 * entity key stage writes ({@code no.sikt.graphitron.model.derive.EntityKeyAlternatives}), and a
 * schema carrying one does not generate. So a type with such a key is skipped here without a
 * diagnostic of its own, as is a type whose {@code @key} directives are all
 * {@code resolvable: false}, a reference-only stub this subgraph emits no {@code _entities}
 * handler for.
 */
public final class EntityResolutionBuilder {

    private static final String KEY_DIRECTIVE = "key";
    private static final String FIELDS_ARG = "fields";
    private static final String RESOLVABLE_ARG = "resolvable";
    private static final String ID_FIELD = "id";

    private EntityResolutionBuilder() {}

    /**
     * Walks the classified type map and returns an entity-resolution entry for every type
     * whose schema element carries at least one {@code @key} directive it can resolve, and every
     * node type. A key it cannot resolve is the store's refusal, so the type is skipped.
     *
     * @param registry the classifier's type registry (read-only here)
     * @param fields the classifier's field map (read-only)
     * @param assembled the assembled graphql-java schema (read-only)
     */
    public static Map<String, EntityResolution> build(
        TypeRegistry registry,
        Map<FieldCoordinates, GraphitronField> fields,
        GraphQLSchema assembled
    ) {
        var out = new LinkedHashMap<String, EntityResolution>();
        for (var entry : List.copyOf(registry.entries().entrySet())) {
            String typeName = entry.getKey();
            GraphitronType gType = entry.getValue();
            GraphQLNamedType assembledType = assembled.getType(typeName) instanceof GraphQLNamedType nt ? nt : null;
            if (!(assembledType instanceof GraphQLObjectType objType)) continue;
            var keys = objType.getAppliedDirectives(KEY_DIRECTIVE);
            // NodeTypes always get an entity entry: their NODE_ID alternative is the canonical
            // SELECT path used by Query.node, Query.nodes, and federation _entities, regardless
            // of whether federation @link is present.
            boolean isNodeType = gType instanceof NodeType;
            if (keys.isEmpty() && !isNodeType) continue;
            // An entity is resolved by a SELECT on its table. A type that is not a table or node
            // type here is either a reference-only stub or refused by the store, and a type already
            // rejected upstream carries its own cause.
            if (!(gType instanceof TableType || gType instanceof NodeType)) continue;

            var alternatives = new ArrayList<KeyAlternative>();
            boolean refused = false;
            for (var key : keys) {
                var alt = buildAlternative(typeName, gType, key, fields);
                if (alt == null) {
                    refused = true;
                    break;
                }
                alternatives.add(alt);
            }
            if (refused) continue;
            // Synthesise a NODE_ID alternative when no explicit @key(fields: "id") is among the
            // user-declared keys (KeyNodeSynthesiser fills this in at the registry level; tests
            // that bypass the attributed read rely on this synthesis instead). Prepend so the
            // most-specific selection logic still finds the user's wider keys first.
            if (isNodeType && !hasNodeIdAlternative(alternatives)) {
                NodeType nt = (NodeType) gType;
                alternatives.add(0, new KeyAlternative.NodeId(
                    nt.typeId(), List.copyOf(nt.nodeKeyColumns()), true));
            }

            var tableBacked = (GraphitronType.TableBackedType) registry.get(typeName);
            out.put(typeName, new EntityResolution(typeName, tableBacked.table(), List.copyOf(alternatives)));
        }
        return Map.copyOf(out);
    }

    private static boolean hasNodeIdAlternative(List<KeyAlternative> alternatives) {
        return alternatives.stream().anyMatch(a -> a instanceof KeyAlternative.NodeId);
    }

    /**
     * The alternative one {@code @key} resolves to, or {@code null} where it resolves to none,
     * which the store refuses. The node's own id alone, which carries an encoded node id, is the
     * node-id alternative; an {@code id} carrying {@code @field(name:)} reads a column like any
     * other field, so a key over it is direct (the id-contract ruling in {@code docs/architecture/explanation/fact-model.adoc}).
     */
    private static KeyAlternative buildAlternative(
        String typeName,
        GraphitronType gType,
        GraphQLAppliedDirective keyDirective,
        Map<FieldCoordinates, GraphitronField> fields
    ) {
        String fieldsValue = readFieldsArg(keyDirective);
        if (fieldsValue == null) return null;
        List<String> required;
        try {
            required = FederationKeyFieldsParser.parse(fieldsValue);
        } catch (FederationKeyFieldsParser.ParseException e) {
            return null;
        }
        boolean resolvable = readResolvableArg(keyDirective);

        if (gType instanceof NodeType nodeType
            && required.size() == 1
            && ID_FIELD.equals(required.get(0))
            && columnOf(fields.get(FieldCoordinates.coordinates(typeName, ID_FIELD))) == null) {
            return new KeyAlternative.NodeId(
                nodeType.typeId(),
                List.copyOf(nodeType.nodeKeyColumns()),
                resolvable);
        }

        var bindings = new ArrayList<KeyAlternative.RepBinding>();
        for (String name : required) {
            var field = fields.get(FieldCoordinates.coordinates(typeName, name));
            ColumnRef col = columnOf(field);
            if (col == null) return null;
            bindings.add(new KeyAlternative.RepBinding(name, col));
        }
        return new KeyAlternative.Direct(List.copyOf(bindings), resolvable);
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

    /**
     * The one column of the entity's own table a field reads off the parent row, or {@code null}:
     * a composite carrier binds several, a field encoding a node id carries the encoded id rather
     * than the column's value, and a reference field reads another table's column. The store
     * decides the same from the field's binding.
     */
    private static ColumnRef columnOf(GraphitronField field) {
        if (field instanceof ChildField.ColumnBackedField cf && !cf.isComposite()
                && !(cf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys)) {
            return cf.columns().get(0);
        }
        return null;
    }
}
