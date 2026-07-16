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
import no.sikt.graphitron.rewrite.TypeRegistry;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.JavaRecordType;
import no.sikt.graphitron.rewrite.model.GraphitronType.JooqRecordType;
import no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NestingType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PojoResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.Rejection;

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
 *   <li>{@code @key(fields: "id")} on a {@link NodeType} → {@link KeyAlternative.NodeId}, columns
 *       are the type's {@code nodeKeyColumns}; the rep's id is decoded by
 *       {@code NodeIdEncoder.decodeValues} at runtime.</li>
 *   <li>Any other {@code @key} → {@link KeyAlternative.Direct}, columns resolved by walking the
 *       referenced fields and reading their {@link ColumnRef}.</li>
 * </ul>
 *
 * <h3>Errors</h3>
 * <p>Validation failures are recorded as {@link UnclassifiedType} demotions on the type map
 * (same channel as {@link no.sikt.graphitron.rewrite.TypeBuilder} uses for unresolvable
 * {@code @table}s). Causes:
 * <ul>
 *   <li>Malformed {@code fields:} string — caught by {@link FederationKeyFieldsParser}</li>
 *   <li>A <em>resolvable</em> {@code @key} on a non-table-bound type, or {@code @key} on
 *       {@link TableInterfaceType}. A type whose {@code @key} directives are all
 *       {@code resolvable: false} is a reference-only entity stub: it is skipped (no entity
 *       entry, no rejection) since this subgraph emits no {@code _entities} handler for it.</li>
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
     * whose schema element carries at least one {@code @key} directive. A type
     * whose {@code @key} directives cannot be resolved keeps its classified verdict; the federation
     * rejection is registered as a build-time {@link ValidationError} on {@code diagnosticSink}
     * (the validator drains it) rather than demoting the registry entry, so a verdict read after the
     * walk equals the verdict classification produced.
     *
     * @param registry the classifier's type registry (read-only here)
     * @param fields the classifier's field map (read-only)
     * @param assembled the assembled graphql-java schema (read-only)
     * @param warningSink receiver for non-fatal advisories (e.g. compound key on @node type
     *                    that includes "id"); typically {@code BuildContext::addWarning}
     * @param diagnosticSink receiver for the federation {@code @key} rejections; typically
     *                    {@code BuildContext::addDiagnostic}
     */
    public static Map<String, EntityResolution> build(
        TypeRegistry registry,
        Map<FieldCoordinates, GraphitronField> fields,
        GraphQLSchema assembled,
        Consumer<BuildWarning> warningSink,
        Consumer<ValidationError> diagnosticSink
    ) {
        var out = new LinkedHashMap<String, EntityResolution>();
        // A @key object type the type pass left unclassified (a directiveless object — a
        // federation entity needs a @table) is absent from the registry, so the entity loop below
        // never sees it. Reject it here with the federation diagnostic rather than letting it slip
        // through as a generic unclassified field. Registers the rejection on the
        // diagnostic channel; the type stays absent from the registry (it was never classified) and
        // the entity loop below still never sees it, so no entity entry is built.
        for (var named : assembled.getAllTypesAsList()) {
            if (named instanceof GraphQLObjectType keyObj
                    && !keyObj.getName().startsWith("__")
                    && !keyObj.getAppliedDirectives(KEY_DIRECTIVE).isEmpty()
                    && !registry.contains(keyObj.getName())) {
                var loc = keyObj.getDefinition() != null ? keyObj.getDefinition().getSourceLocation() : null;
                diagnosticSink.accept(ValidationError.forType(keyObj.getName(), Rejection.structural(
                    "@key on type '" + keyObj.getName() + "' requires a table-bound type, but '" + keyObj.getName()
                    + "' is classified as a plain object type — federation entities need a @table directive."),
                    loc));
            }
        }
        for (var entry : List.copyOf(registry.entries().entrySet())) {
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

            // @key on a TableInterfaceType is rejected.
            if (gType instanceof TableInterfaceType) {
                diagnosticSink.accept(ValidationError.forType(typeName, Rejection.structural(
                    "@key on TableInterfaceType is not supported; declare @key on the implementing types instead"),
                    gType.location()));
                continue;
            }
            // A type already rejected upstream (e.g. unresolvable @table, malformed @node
            // keyColumns) carries the real cause on its existing UnclassifiedType. Pass it
            // through unchanged; relitigating would overwrite the actionable diagnostic with
            // a misleading "no @table directive" message.
            if (gType instanceof UnclassifiedType) {
                continue;
            }
            // Federation entities require a backing table (the dispatcher SELECTs from it) —
            // but only when a key is resolvable. A type whose @key directives are all
            // resolvable: false is a reference-only entity stub: it is declared for the
            // supergraph composer, but this subgraph does not own its resolution, emits no
            // _entities handler for it, and so needs no backing table. Skip it without an
            // entity entry (the dispatcher never sees it; the type stays classified as
            // whatever it is, e.g. a record-backed type). Demote only when at least one key is
            // resolvable and thus needs a SELECT path. (keys is non-empty here: NodeType is
            // excluded by the branch condition, and the keys.isEmpty() && !isNodeType case
            // already continued above.)
            if (!(gType instanceof TableType || gType instanceof NodeType)) {
                boolean anyResolvable = keys.stream().anyMatch(EntityResolutionBuilder::readResolvableArg);
                if (!anyResolvable) {
                    continue;
                }
                diagnosticSink.accept(ValidationError.forType(typeName, Rejection.structural(
                    "@key on type '" + typeName + "' requires a table-bound type, but '" + typeName
                    + "' is classified as " + kindLabel(gType)
                    + " — federation entities need a @table directive."),
                    gType.location()));
                continue;
            }

            var alternatives = new ArrayList<KeyAlternative>();
            String error = null;
            ValidationError fatal = null;
            for (var key : keys) {
                var alt = buildAlternative(typeName, gType, key, fields, warningSink);
                if (alt instanceof AltResult.Ok ok) {
                    alternatives.add(ok.alt());
                } else if (alt instanceof AltResult.Err err) {
                    error = err.message();
                    break;
                } else if (alt instanceof AltResult.Fatal f) {
                    fatal = f.error();
                    break;
                }
            }
            if (fatal != null) {
                // The typed InvalidSchema rejection is already constructed; register
                // it verbatim and skip the type (no second structural diagnostic).
                diagnosticSink.accept(fatal);
                continue;
            }
            if (error != null) {
                diagnosticSink.accept(ValidationError.forType(typeName, Rejection.structural(error), gType.location()));
                continue;
            }
            // NodeTypes always get a NODE_ID alternative: it's the canonical SELECT path used
            // by Query.node, Query.nodes, and federation _entities. Add one synthetically when
            // no explicit @key(fields: "id") is among the user-declared keys (in the federation
            // pipeline KeyNodeSynthesiser fills this in at the registry level; tests that
            // bypass loadAttributedRegistry rely on this synthesis instead). Prepend so the
            // most-specific selection logic still finds the user's wider keys first when those
            // are richer than the ["id"] alternative.
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
     * Author-facing label for a non-table-bound classification surviving the
     * pre-checks in {@link #build}. Used inside the rejection message that fires
     * when {@code @key} is declared on a type the classifier produced as something
     * other than {@link TableType} or {@link NodeType}.
     */
    private static String kindLabel(GraphitronType gType) {
        return switch (gType) {
            case NestingType p -> "a plain object type";
            case JavaRecordType r -> "a record-backed type";
            case PojoResultType r -> "a record-backed type";
            case JooqRecordType r -> "a record-backed type";
            case JooqTableRecordType r -> "a record-backed type";
            default -> "a non-table-bound type";
        };
    }

    private sealed interface AltResult {
        record Ok(KeyAlternative alt) implements AltResult {}
        record Err(String message) implements AltResult {}
        /**
         * A fatal rejection whose typed {@link ValidationError} is already constructed.
         * Distinct from {@link Err}, whose message the caller wraps in a
         * {@link Rejection.AuthorError.Structural}; this carries an {@link Rejection.InvalidSchema}
         * the caller registers verbatim, and the caller skips the type without emitting a second
         * diagnostic.
         */
        record Fatal(ValidationError error) implements AltResult {}
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

        // NodeId shape: a NodeType with a single "id" required field uses the node's
        // nodeKeyColumns and decodes the rep's id through NodeIdEncoder at runtime.
        if (gType instanceof NodeType nodeType
            && required.size() == 1
            && ID_FIELD.equals(required.get(0))) {
            return new AltResult.Ok(new KeyAlternative.NodeId(
                nodeType.typeId(),
                List.copyOf(nodeType.nodeKeyColumns()),
                resolvable));
        }

        // Direct shape: each required field name maps to a ColumnRef on the type's table.
        var bindings = new ArrayList<KeyAlternative.RepBinding>();
        for (String name : required) {
            var field = fields.get(FieldCoordinates.coordinates(typeName, name));
            ColumnRef col = columnOf(field);
            if (col == null) {
                return new AltResult.Err("@key(fields: \"" + fieldsValue + "\") on type '"
                    + typeName + "' references field '" + name
                    + "' which is not a column-backed field on this type's table");
            }
            // A @key whose referenced field is itself an @nodeId-encoded reference
            // carrier (a ColumnReferenceField wrapping its column in NodeIdEncodeKeys) cannot resolve
            // through the DIRECT _entities path: that path binds the rep's field value verbatim, but
            // the rep carries a base64-encoded global id, so the encoded id is bound undecoded into
            // the VALUES table — a never-matches predicate or a SQL bind/type error at runtime. Reject
            // fatally (not the non-fatal compound-id warning below, which covers the distinct
            // own-column 'id' carrier). Decode-into-rep is a possible follow-on; rejection first.
            if (field instanceof ChildField.ColumnReferenceField cref
                && cref.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                return new AltResult.Fatal(ValidationError.forType(typeName, Rejection.invalidSchema(
                    "@key(fields: \"" + fieldsValue + "\") on type '" + typeName + "' references field '"
                    + name + "', which is an @nodeId-encoded reference (its rep value is a base64 global "
                    + "id). The _entities DIRECT path binds the rep value verbatim, so the encoded id "
                    + "would be bound undecoded into the entity lookup. Key on a non-encoded column, or "
                    + "use the canonical @key(fields: \"id\") NodeId path on a @node type."),
                    gType.location()));
            }
            bindings.add(new KeyAlternative.RepBinding(name, col));
        }
        if (gType instanceof NodeType && required.contains(ID_FIELD)) {
            // Compound key that includes "id" on a @node type: we treat it as DIRECT (the rep
            // carries each required field individually, including the id), not NODE_ID.
            // Surface a warning so the consumer is aware their compound key bypasses the
            // global-id decode path.
            warningSink.accept(new BuildWarning.NoRule(
                "@key(fields: \"" + fieldsValue + "\") on @node type '" + typeName
                + "' includes 'id' alongside other fields; the id is treated as a column value "
                + "rather than a global NodeId. Federation reps for this alternative must "
                + "carry the column-level id, not a base64-encoded NodeId.",
                gType.location()));
        }
        return new AltResult.Ok(new KeyAlternative.Direct(
            List.copyOf(bindings),
            resolvable));
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

    private static ColumnRef columnOf(GraphitronField field) {
        if (field == null) return null;
        if (field instanceof ChildField.ColumnField cf) return cf.column();
        if (field instanceof ChildField.ColumnReferenceField cref) return cref.column();
        return null;
    }
}
