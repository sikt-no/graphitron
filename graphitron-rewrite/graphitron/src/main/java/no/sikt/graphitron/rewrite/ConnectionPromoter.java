package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.SchemaTransformer;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONNECTION_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DEFAULT_FIRST_VALUE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;

/**
 * Promotes Connection-shaped carrier fields and synthesises the supporting
 * {@link ConnectionType} / {@link EdgeType} / {@link PageInfoType} entries.
 *
 * <p>Two entry points: {@link #promote(BuildContext)} runs after type classification has
 * accepted the carrier (rejection of malformed {@code @asConnection} usage lives upstream
 * in {@link FieldBuilder#classifyField}) and registers synthesised types on
 * {@code ctx.typeRegistry} while returning a per-carrier rewrite list;
 * {@link #rebuildAssembledForConnections} consumes that list and the {@code typeRegistry}
 * to produce a {@link GraphQLSchema} whose carriers point at the synthesised types and
 * whose {@code first} / {@code after} arguments are present.
 *
 * <p>Stateless utility class. Per-build state lives in {@link BuildContext}.
 */
final class ConnectionPromoter {

    private ConnectionPromoter() {
    }

    /**
     * Per-carrier info collected during connection-type promotion so the schema-rebuild pass can
     * find and rewrite each directive-driven carrier field.
     */
    record CarrierRewrite(
        String parentTypeName,
        String fieldName,
        String connectionName,
        int defaultPageSize,
        boolean outerNonNull
    ) {}

    /**
     * Promotes every carrier field that returns a Relay connection (directive-driven
     * {@code @asConnection} on a bare list, or a structural Connection-shaped return type) to a
     * first-class {@link ConnectionType} / {@link EdgeType} / {@link PageInfoType} entry in
     * {@code ctx.types}.
     *
     * <p>For directive-driven carriers the {@link GraphQLObjectType} schema form is built
     * programmatically (the synthesised types are not in the assembled schema). For structural
     * carriers the schema form is referenced from the assembled schema, where it was parsed from
     * the SDL.
     *
     * <p>Dedups by name: a single synthesised {@code QueryStoresConnection} entry covers every
     * carrier that points at it. First-write-wins on dedupe — the synthesised type's
     * {@code location} pins to the first carrier that triggered synthesis, and later carriers
     * sharing the name short-circuit on the {@link ConnectionType} early-{@code continue} below
     * without overwriting it. Once R208 retires {@code @asConnection(connectionName:)}, every
     * connection name is unambiguously produced by exactly one carrier and the dedupe is purely
     * a structural-vs-directive overlap (which still resolves to the first writer).
     *
     * <p>{@link PageInfoType} is registered once at most, and only when at least one Connection is
     * promoted and the SDL doesn't already declare {@code PageInfo}. The synthesised PageInfo's
     * {@code location} is deliberately {@code null}: a single PageInfo serves every connection in
     * the schema, so no carrier site is the actionable one — diagnostics on a synth-vs-SDL
     * PageInfo collision anchor on the SDL member's parse location instead.
     */
    static List<CarrierRewrite> promote(BuildContext ctx) {
        var rewrites = new ArrayList<CarrierRewrite>();
        boolean pageInfoShareable = false;
        boolean anyConnectionSynthesised = false;
        for (var t : ctx.schema.getAllTypesAsList()) {
            if (t.getName().startsWith("_")) continue;
            if (!(t instanceof GraphQLObjectType objType)) continue;
            for (var fieldDef : objType.getFieldDefinitions()) {
                ConnectionPromotion promotion = promotionFor(ctx, objType, fieldDef);
                if (promotion == null) continue;
                // Record a carrier rewrite only when the graphql-java return type actually needs
                // to change — i.e. the current base-type name differs from the connection name.
                // Directive-driven bare-list carriers need rewriting; structural carriers (where
                // the SDL return type already names the Connection) do not, even when they
                // carry @asConnection alongside.
                String currentBaseName = baseTypeName(fieldDef.getType());
                if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)
                        && !promotion.connectionName().equals(currentBaseName)) {
                    rewrites.add(new CarrierRewrite(
                        objType.getName(), fieldDef.getName(),
                        promotion.connectionName(),
                        resolveDefaultFirstValue(fieldDef),
                        fieldDef.getType() instanceof GraphQLNonNull));
                }
                if (ctx.types.get(promotion.connectionName()) instanceof ConnectionType existing) {
                    pageInfoShareable |= existing.shareable();
                    anyConnectionSynthesised = true;
                    continue;
                }
                anyConnectionSynthesised = true;
                pageInfoShareable |= promotion.shareable();
                var connSchema = promotion.connectionSchemaType();
                var edgeSchema = promotion.edgeSchemaType();
                // Directive-driven connections: name does not exist in the SDL → synthesize.
                // Structural connections: SDL declares the Connection / Edge object types with
                // no domain directive, so the first pass classifies them as PlainObjectType;
                // promotion replaces that with the typed ConnectionType / EdgeType (an enrich).
                var carrierLocation = BuildContext.locationOf(fieldDef);
                var connectionType = new ConnectionType(
                    promotion.connectionName(), carrierLocation,
                    promotion.elementTypeName(), promotion.edgeName(),
                    promotion.itemNullable(), promotion.shareable(), connSchema);
                if (ctx.typeRegistry.contains(promotion.connectionName())) {
                    ctx.typeRegistry.enrich(promotion.connectionName(), connectionType);
                } else {
                    ctx.typeRegistry.synthesize(promotion.connectionName(), connectionType);
                }
                var edgeType = new EdgeType(
                    promotion.edgeName(), carrierLocation,
                    promotion.elementTypeName(), promotion.itemNullable(),
                    promotion.shareable(), edgeSchema);
                if (ctx.typeRegistry.contains(promotion.edgeName())) {
                    ctx.typeRegistry.enrich(promotion.edgeName(), edgeType);
                } else {
                    ctx.typeRegistry.synthesize(promotion.edgeName(), edgeType);
                }
            }
        }
        if (anyConnectionSynthesised
                && ctx.schema.getType("PageInfo") == null
                && !(ctx.types.get("PageInfo") instanceof PageInfoType)) {
            ctx.typeRegistry.synthesize("PageInfo", new PageInfoType(
                "PageInfo", null, pageInfoShareable, buildSynthesisedPageInfo(pageInfoShareable)));
        } else if (ctx.schema.getType("PageInfo") instanceof GraphQLObjectType pageInfoObj
                && !(ctx.types.get("PageInfo") instanceof PageInfoType)) {
            boolean shareable = pageInfoObj.hasAppliedDirective("shareable");
            // SDL-declared PageInfo: first pass classified it as PlainObjectType (no domain
            // directive). Connection promotion replaces that with PageInfoType so the
            // connection-emitter can pick up the shareable flag and SDL-derived schema form.
            // Structurally an enrich; the synthesise arm above handles the no-SDL case.
            ctx.typeRegistry.enrich("PageInfo", new PageInfoType(
                "PageInfo", null, shareable, pageInfoObj));
        }
        return rewrites;
    }

    /**
     * Rewrites directive-driven {@code @asConnection} carrier fields so their return type
     * references the synthesised Connection and their arguments include {@code first} /
     * {@code after}, and registers all directive-synthesised types
     * ({@link ConnectionType}, {@link EdgeType}, {@link PageInfoType} entries absent from the
     * original assembled schema) via {@code additionalType(...)}.
     *
     * <p>Returns the rebuilt schema; untouched types pass through by reference via
     * {@link SchemaTransformer}, preserving applied directives and field order on everything
     * outside the carrier set.
     */
    static GraphQLSchema rebuildAssembledForConnections(
            GraphQLSchema original,
            Map<String, GraphitronType> types,
            List<CarrierRewrite> rewrites) {
        if (rewrites.isEmpty() && noSynthesisedTypes(original, types)) {
            return original;
        }

        // Step 1: register synthesised types on the schema so later carrier rewrites can reference
        // them by typeRef without graphql-java's build-time validation failing.
        var withSynthesised = original;
        boolean anySynth = false;
        var extrasBuilder = GraphQLSchema.newSchema(original);
        for (var entry : types.entrySet()) {
            if (original.getType(entry.getKey()) != null) continue;
            GraphQLObjectType schemaType = switch (entry.getValue()) {
                case ConnectionType ct -> ct.schemaType();
                case EdgeType et -> et.schemaType();
                case PageInfoType pi -> pi.schemaType();
                default -> null;
            };
            if (schemaType != null) {
                extrasBuilder.additionalType(schemaType);
                anySynth = true;
            }
        }
        if (anySynth) withSynthesised = extrasBuilder.build();

        if (rewrites.isEmpty()) return withSynthesised;

        // Step 2: transform carrier fields. typeRef("<ConnName>") now resolves against the
        // synthesised types added in step 1.
        var rewriteIndex = new LinkedHashMap<String, CarrierRewrite>();
        for (var r : rewrites) rewriteIndex.put(r.parentTypeName() + "." + r.fieldName(), r);

        var visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLFieldDefinition(
                    GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                var parent = context.getParentNode();
                if (!(parent instanceof GraphQLObjectType parentObj)) return TraversalControl.CONTINUE;
                var rewrite = rewriteIndex.get(parentObj.getName() + "." + node.getName());
                if (rewrite == null) return TraversalControl.CONTINUE;
                var rewritten = rewriteCarrierField(node, rewrite);
                return TreeTransformerUtil.changeNode(context, rewritten);
            }
        };
        return SchemaTransformer.transformSchema(withSynthesised, visitor);
    }

    private static boolean noSynthesisedTypes(GraphQLSchema assembled,
            Map<String, GraphitronType> types) {
        for (var entry : types.entrySet()) {
            var v = entry.getValue();
            boolean isSynth = v instanceof ConnectionType || v instanceof EdgeType || v instanceof PageInfoType;
            if (isSynth && assembled.getType(entry.getKey()) == null) return false;
        }
        return true;
    }

    /**
     * Returns the rewritten carrier: return type swapped for the synthesised Connection's
     * reference; {@code first} / {@code after} arguments appended after any existing ones.
     * Description, deprecation, and applied directives pass through untouched.
     */
    private static GraphQLFieldDefinition rewriteCarrierField(GraphQLFieldDefinition original, CarrierRewrite rewrite) {
        var ref = GraphQLTypeReference.typeRef(rewrite.connectionName());
        GraphQLOutputType newType = rewrite.outerNonNull() ? GraphQLNonNull.nonNull(ref) : ref;
        // defaultPageSize mirrors FieldWrapper.Connection.defaultPageSize at this carrier site.
        var firstArg = GraphQLArgument.newArgument()
            .name("first")
            .type(GraphQLTypeReference.typeRef("Int"))
            .defaultValueProgrammatic(rewrite.defaultPageSize())
            .build();
        var afterArg = GraphQLArgument.newArgument()
            .name("after")
            .type(GraphQLTypeReference.typeRef("String"))
            .build();
        return original.transform(b -> b.type(newType).argument(firstArg).argument(afterArg));
    }

    private static int resolveDefaultFirstValue(GraphQLFieldDefinition field) {
        var applied = field.getAppliedDirective(DIR_AS_CONNECTION);
        if (applied == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        var arg = applied.getArgument(ARG_DEFAULT_FIRST_VALUE);
        if (arg == null || arg.getValue() == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        Object v = arg.getValue();
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof graphql.language.IntValue iv) return iv.getValue().intValueExact();
        return FieldWrapper.DEFAULT_PAGE_SIZE;
    }

    private record ConnectionPromotion(
        String connectionName,
        String edgeName,
        String elementTypeName,
        boolean itemNullable,
        boolean shareable,
        GraphQLObjectType connectionSchemaType,
        GraphQLObjectType edgeSchemaType
    ) {}

    /**
     * Returns a {@link ConnectionPromotion} describing the synthesis for a connection-returning
     * carrier field, or {@code null} when this field is not a connection carrier.
     */
    private static ConnectionPromotion promotionFor(BuildContext ctx, GraphQLObjectType parent,
                                                     GraphQLFieldDefinition fieldDef) {
        GraphQLOutputType fieldType = fieldDef.getType();
        GraphQLOutputType unwrapped = fieldType instanceof GraphQLNonNull nn
            ? (GraphQLOutputType) nn.getWrappedType()
            : fieldType;

        // Directive-driven: @asConnection on a bare list — build the schema type programmatically.
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION) && unwrapped instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            var elementLayer = itemNullable
                ? listType.getWrappedType()
                : ((GraphQLNonNull) listType.getWrappedType()).getWrappedType();
            String elementTypeName = elementLayer instanceof GraphQLNamedType named
                ? named.getName() : elementLayer.toString();
            String connName = resolveConnectionName(parent.getName(), fieldDef);
            String edgeName = connName.replace("Connection", "Edge");
            boolean shareable = fieldDef.hasAppliedDirective("shareable");
            var connSchema = buildSynthesisedConnection(connName, edgeName, elementTypeName, itemNullable, shareable);
            var edgeSchema = buildSynthesisedEdge(edgeName, elementTypeName, itemNullable, shareable);
            return new ConnectionPromotion(connName, edgeName, elementTypeName,
                itemNullable, shareable, connSchema, edgeSchema);
        }

        // Structural: the return type shape is a declared Connection — reference the assembled type.
        String typeName = baseTypeName(unwrapped);
        if (typeName != null && ctx.isConnectionType(typeName)) {
            var connSchema = (GraphQLObjectType) ctx.schema.getType(typeName);
            boolean itemNullable = ctx.connectionItemNullable(typeName);
            String elementTypeName = ctx.connectionElementTypeName(typeName);
            String edgeName = typeName.replace("Connection", "Edge");
            var edgeSchema = (GraphQLObjectType) ctx.schema.getType(edgeName);
            boolean shareable = connSchema.hasAppliedDirective("shareable");
            return new ConnectionPromotion(typeName, edgeName, elementTypeName,
                itemNullable, shareable, connSchema, edgeSchema);
        }
        return null;
    }

    private static String baseTypeName(GraphQLOutputType t) {
        GraphQLOutputType cur = t;
        while (cur instanceof GraphQLNonNull nn) cur = (GraphQLOutputType) nn.getWrappedType();
        while (cur instanceof GraphQLList list) cur = (GraphQLOutputType) list.getWrappedType();
        while (cur instanceof GraphQLNonNull nn) cur = (GraphQLOutputType) nn.getWrappedType();
        return cur instanceof GraphQLNamedType named ? named.getName() : null;
    }

    /**
     * Resolves the connection type name for a carrier field: explicit
     * {@code @asConnection(connectionName:)} wins; otherwise derive
     * {@code <ParentType><FieldName>Connection}.
     */
    private static String resolveConnectionName(String parentTypeName, GraphQLFieldDefinition field) {
        var applied = field.getAppliedDirective(DIR_AS_CONNECTION);
        if (applied != null) {
            var arg = applied.getArgument(ARG_CONNECTION_NAME);
            if (arg != null && arg.getValue() instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return parentTypeName + capitalize(field.getName()) + "Connection";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static GraphQLObjectType buildSynthesisedConnection(String connName, String edgeName,
            String elementTypeName, boolean itemNullable, boolean shareable) {
        var edgesField = GraphQLFieldDefinition.newFieldDefinition()
            .name("edges")
            .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(
                GraphQLTypeReference.typeRef(edgeName)))))
            .build();
        GraphQLOutputType nodeInnerRef = itemNullable
            ? GraphQLTypeReference.typeRef(elementTypeName)
            : GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(elementTypeName));
        var nodesField = GraphQLFieldDefinition.newFieldDefinition()
            .name("nodes")
            .type(GraphQLNonNull.nonNull(GraphQLList.list(nodeInnerRef)))
            .build();
        var pageInfoField = GraphQLFieldDefinition.newFieldDefinition()
            .name("pageInfo")
            .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("PageInfo")))
            .build();
        // Nullable matches legacy and survives the count-skipped path on Split-Connection naturally.
        var totalCountField = GraphQLFieldDefinition.newFieldDefinition()
            .name("totalCount")
            .type(GraphQLTypeReference.typeRef("Int"))
            .build();
        var builder = GraphQLObjectType.newObject()
            .name(connName)
            .field(edgesField)
            .field(nodesField)
            .field(pageInfoField)
            .field(totalCountField);
        if (shareable) builder.withAppliedDirective(GraphQLAppliedDirective.newDirective().name("shareable").build());
        return builder.build();
    }

    private static GraphQLObjectType buildSynthesisedEdge(String edgeName, String elementTypeName,
            boolean itemNullable, boolean shareable) {
        var cursorField = GraphQLFieldDefinition.newFieldDefinition()
            .name("cursor")
            .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("String")))
            .build();
        GraphQLOutputType nodeType = itemNullable
            ? GraphQLTypeReference.typeRef(elementTypeName)
            : GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(elementTypeName));
        var nodeField = GraphQLFieldDefinition.newFieldDefinition()
            .name("node")
            .type(nodeType)
            .build();
        var builder = GraphQLObjectType.newObject()
            .name(edgeName)
            .field(cursorField)
            .field(nodeField);
        if (shareable) builder.withAppliedDirective(GraphQLAppliedDirective.newDirective().name("shareable").build());
        return builder.build();
    }

    private static GraphQLObjectType buildSynthesisedPageInfo(boolean shareable) {
        var builder = GraphQLObjectType.newObject()
            .name("PageInfo")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("hasNextPage")
                .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("Boolean")))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("hasPreviousPage")
                .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("Boolean")))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("startCursor")
                .type(GraphQLTypeReference.typeRef("String"))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("endCursor")
                .type(GraphQLTypeReference.typeRef("String"))
                .build());
        if (shareable) builder.withAppliedDirective(GraphQLAppliedDirective.newDirective().name("shareable").build());
        return builder.build();
    }
}
