package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
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
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.SchemaTransformer;
import graphql.schema.idl.EchoingWiringFactory;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.*;

/**
 * Builds a {@link GraphitronSchema} from a {@link TypeDefinitionRegistry} by classifying every
 * named type into the sealed {@link no.sikt.graphitron.rewrite.model.GraphitronType} hierarchy
 * and every field into the sealed {@link GraphitronField} hierarchy.
 *
 * <p>This is the directive-reading boundary: the only place in the pipeline that reads schema
 * directives. Downstream code works exclusively with the produced model values.
 *
 * <p>The Maven plugin calls {@link #build(TypeDefinitionRegistry, RewriteContext)} before running
 * {@link GraphitronSchemaValidator#validate(GraphitronSchema)}.
 */
public class GraphitronSchemaBuilder {

    /**
     * Pairs the classified {@link GraphitronSchema} with the raw {@link GraphQLSchema} the
     * classifier built internally. The Commit B emitters read raw type structure (argument
     * default values, nested list/non-null wrapping, directive applications) off the assembled
     * schema and don't need the reclassification to carry it all, so we just surface both
     * together. Production callers use {@link #buildBundle(TypeDefinitionRegistry, RewriteContext)};
     * tests that only need the classified model continue to call
     * {@link #build(TypeDefinitionRegistry, RewriteContext)}.
     */
    public record Bundle(GraphitronSchema model, graphql.schema.GraphQLSchema assembled) {}

    /**
     * Classifies all types and fields in {@code registry} and returns the resulting
     * {@link GraphitronSchema}. The registry must already include the Graphitron directive
     * definitions.
     */
    public static GraphitronSchema build(TypeDefinitionRegistry registry, RewriteContext ctx) {
        return buildBundle(registry, ctx).model();
    }

    /**
     * Classifies {@code registry} and returns both the classified model and the assembled
     * {@link GraphQLSchema}. See {@link Bundle}.
     */
    public static Bundle buildBundle(TypeDefinitionRegistry registry, RewriteContext ctx) {
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring ->
            registry.scalars().forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(EchoingWiringFactory.fakeScalar(name));
                }
            })
        );
        var assembled = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        var bctx = new BuildContext(assembled, new JooqCatalog(ctx.jooqPackage()), ctx);
        var svc = new ServiceCatalog(bctx);
        bctx.svc = svc;
        var typeBuilder = new TypeBuilder(bctx, svc);
        var fieldBuilder = new FieldBuilder(bctx, svc);
        var result = buildSchema(bctx, typeBuilder, fieldBuilder);
        return new Bundle(result.model, result.assembled);
    }

    private record BuildResult(GraphitronSchema model, GraphQLSchema assembled) {}

    private static BuildResult buildSchema(BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder) {
        validateDirectiveSchema(ctx);
        ctx.types = typeBuilder.buildTypes();
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        ctx.schema.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLObjectType && !t.getName().startsWith("__"))
            .map(t -> (GraphQLObjectType) t)
            .forEach(objType -> {
                var parentType = ctx.types.get(objType.getName());
                if (parentType == null) return;
                // Fields on plain SDL object types (no domain directive) are the developer's
                // responsibility to wire — the classifier records the parent in schema.types()
                // but leaves per-field classification out. Matches pre-Phase-4 behaviour where
                // such types had no entries in schema.fields().
                if (parentType instanceof no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType) return;
                objType.getFieldDefinitions().forEach(fieldDef ->
                    fields.put(
                        FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()),
                        fieldBuilder.classifyField(fieldDef, objType.getName(), parentType)));
            });
        var rewrites = promoteConnectionTypes(ctx);
        var rebuiltAssembled = rebuildAssembledForConnections(ctx.schema, ctx.types, rewrites);
        var model = new GraphitronSchema(ctx.types, Collections.unmodifiableMap(fields), ctx.warnings());
        return new BuildResult(model, rebuiltAssembled);
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
    private static GraphQLSchema rebuildAssembledForConnections(
            GraphQLSchema original,
            Map<String, no.sikt.graphitron.rewrite.model.GraphitronType> types,
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
            Map<String, no.sikt.graphitron.rewrite.model.GraphitronType> types) {
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

    /**
     * Per-carrier info collected during connection-type promotion so the schema-rebuild pass can
     * find and rewrite each directive-driven carrier field.
     */
    private record CarrierRewrite(
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
     * carrier that points at it. {@link PageInfoType} is registered once at most, and only when
     * at least one Connection is promoted and the SDL doesn't already declare {@code PageInfo}.
     */
    private static List<CarrierRewrite> promoteConnectionTypes(BuildContext ctx) {
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
                ctx.types.put(promotion.connectionName(), new ConnectionType(
                    promotion.connectionName(), null,
                    promotion.elementTypeName(), promotion.edgeName(),
                    promotion.itemNullable(), promotion.shareable(), connSchema));
                ctx.types.put(promotion.edgeName(), new EdgeType(
                    promotion.edgeName(), null,
                    promotion.elementTypeName(), promotion.itemNullable(),
                    promotion.shareable(), edgeSchema));
            }
        }
        if (anyConnectionSynthesised
                && ctx.schema.getType("PageInfo") == null
                && !(ctx.types.get("PageInfo") instanceof PageInfoType)) {
            ctx.types.put("PageInfo", new PageInfoType(
                "PageInfo", null, pageInfoShareable, buildSynthesisedPageInfo(pageInfoShareable)));
        } else if (ctx.schema.getType("PageInfo") instanceof GraphQLObjectType pageInfoObj
                && !(ctx.types.get("PageInfo") instanceof PageInfoType)) {
            boolean shareable = pageInfoObj.hasAppliedDirective("shareable");
            ctx.types.put("PageInfo", new PageInfoType(
                "PageInfo", null, shareable, pageInfoObj));
        }
        return rewrites;
    }

    private static int resolveDefaultFirstValue(GraphQLFieldDefinition field) {
        var applied = field.getAppliedDirective(DIR_AS_CONNECTION);
        if (applied == null) return no.sikt.graphitron.rewrite.model.FieldWrapper.DEFAULT_PAGE_SIZE;
        var arg = applied.getArgument(ARG_DEFAULT_FIRST_VALUE);
        if (arg == null || arg.getValue() == null) return no.sikt.graphitron.rewrite.model.FieldWrapper.DEFAULT_PAGE_SIZE;
        Object v = arg.getValue();
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof graphql.language.IntValue iv) return iv.getValue().intValueExact();
        return no.sikt.graphitron.rewrite.model.FieldWrapper.DEFAULT_PAGE_SIZE;
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
        var builder = GraphQLObjectType.newObject()
            .name(connName)
            .field(edgesField)
            .field(nodesField)
            .field(pageInfoField);
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

    private static void validateDirectiveSchema(BuildContext ctx) {
        assertDirective(ctx, DIR_TABLE, ARG_NAME);
        assertDirective(ctx, DIR_RECORD);
        assertDirective(ctx, DIR_DISCRIMINATE, ARG_ON);
        assertDirective(ctx, DIR_DISCRIMINATOR, ARG_VALUE);
        assertDirective(ctx, DIR_NODE, ARG_TYPE_ID, ARG_KEY_COLUMNS);
        assertDirective(ctx, DIR_NOT_GENERATED);
        assertDirective(ctx, DIR_MULTITABLE_REFERENCE);
        assertDirective(ctx, DIR_NODE_ID, ARG_TYPE_NAME);
        assertDirective(ctx, DIR_FIELD, ARG_NAME, ARG_JAVA_NAME);
        assertDirective(ctx, DIR_REFERENCE, ARG_PATH);
        assertDirective(ctx, DIR_ERROR, ARG_HANDLERS);
        assertDirective(ctx, DIR_TABLE_METHOD);
        assertDirective(ctx, DIR_DEFAULT_ORDER);
        assertDirective(ctx, DIR_SPLIT_QUERY);
        assertDirective(ctx, DIR_SERVICE);
        assertDirective(ctx, DIR_EXTERNAL_FIELD);
        assertDirective(ctx, DIR_LOOKUP_KEY);
        assertDirective(ctx, DIR_ORDER_BY);
        assertDirective(ctx, DIR_CONDITION);
        assertDirective(ctx, DIR_MUTATION, ARG_TYPE_NAME);
        assertDirective(ctx, DIR_AS_CONNECTION, ARG_DEFAULT_FIRST_VALUE, ARG_CONNECTION_NAME);
    }

    private static void assertDirective(BuildContext ctx, String name, String... args) {
        var def = ctx.schema.getDirective(name);
        if (def == null) {
            throw new IllegalStateException("Expected directive @" + name + " in schema but it was not found.");
        }
        var argNames = def.getArguments().stream()
            .map(GraphQLArgument::getName)
            .collect(Collectors.toSet());
        for (var arg : args) {
            if (!argNames.contains(arg)) {
                throw new IllegalStateException(
                    "Expected argument '" + arg + "' on directive @" + name + " but it was not found.");
            }
        }
    }
}
