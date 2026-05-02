package no.sikt.graphitron.rewrite;

import com.apollographql.federation.graphqljava.FederationDirectives;
import graphql.language.DirectiveDefinition;
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
import graphql.schema.idl.errors.SchemaProblem;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.schema.federation.EntityResolutionBuilder;
import no.sikt.graphitron.rewrite.schema.federation.FederationSpec;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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

    private static final Pattern UNDECLARED_DIRECTIVE_PATTERN =
            Pattern.compile("tried to use an undeclared directive '([^']+)'");

    /**
     * Initialisation-on-demand holder for the federation directive name set. The set is computed
     * by calling into {@code federation-graphql-java-support}, which loads the pinned spec URL
     * and parses its directive definitions. If that call ever fails (network, classpath, or
     * federation-jvm version mismatch) we want the failure to land only on schemas that actually
     * use federation, not on every {@code GraphitronSchemaBuilder} class load. Putting the set
     * behind a holder class delays the federation-jvm call until {@link #buildRecipeErrors}
     * needs it; non-federation pipelines never trigger the load.
     *
     * <p>Also: we use {@link FederationDirectives#loadFederationSpecDefinitions(String)} rather
     * than {@code FederationDirectives.allNames}, because in v6.0.0 {@code allNames} is the
     * Federation 1 set only and would miss {@code @shareable}, {@code @inaccessible},
     * {@code @override}, {@code @tag}, {@code @composeDirective}, and {@code @interfaceObject}.
     * Don't "fix" this back to {@code allNames}.
     */
    private static final class FederationDirectiveNamesHolder {
        static final Set<String> NAMES = FederationDirectives.loadFederationSpecDefinitions(
                        FederationSpec.URL).stream()
                .filter(d -> d instanceof DirectiveDefinition)
                .map(d -> ((DirectiveDefinition) d).getName())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Pairs the classified {@link GraphitronSchema} with the raw {@link GraphQLSchema} the
     * classifier built internally. The Commit B emitters read raw type structure (argument
     * default values, nested list/non-null wrapping, directive applications) off the assembled
     * schema and don't need the reclassification to carry it all, so we just surface both
     * together. Production callers pass an {@link AttributedRegistry} carrying the
     * {@code federationLink} flag captured from
     * {@link FederationLinkApplier#apply}'s return value; tests that hand-craft a registry use
     * the convenience overload {@link #buildBundle(TypeDefinitionRegistry, RewriteContext)},
     * which derives the flag via {@link AttributedRegistry#from(TypeDefinitionRegistry)}.
     */
    public record Bundle(GraphitronSchema model, graphql.schema.GraphQLSchema assembled, boolean federationLink) {}

    /**
     * Convenience overload for tests that hand-craft a {@link TypeDefinitionRegistry} without
     * running {@link GraphQLRewriteGenerator#loadAttributedRegistry}. Wraps via
     * {@link AttributedRegistry#from(TypeDefinitionRegistry)}; production code uses
     * {@link #build(AttributedRegistry, RewriteContext)} directly.
     */
    public static GraphitronSchema build(TypeDefinitionRegistry registry, RewriteContext ctx) {
        return build(AttributedRegistry.from(registry), ctx);
    }

    /**
     * Classifies all types and fields in the {@link AttributedRegistry} and returns the
     * resulting {@link GraphitronSchema}. The registry must already include the Graphitron
     * directive definitions.
     */
    public static GraphitronSchema build(AttributedRegistry attributed, RewriteContext ctx) {
        return buildBundle(attributed, ctx).model();
    }

    /**
     * Convenience overload for tests; see {@link #build(TypeDefinitionRegistry, RewriteContext)}.
     */
    public static Bundle buildBundle(TypeDefinitionRegistry registry, RewriteContext ctx) {
        return buildBundle(AttributedRegistry.from(registry), ctx);
    }

    /**
     * Classifies the {@link AttributedRegistry} and returns both the classified model and the
     * assembled {@link GraphQLSchema}. See {@link Bundle}.
     */
    public static Bundle buildBundle(AttributedRegistry attributed, RewriteContext ctx) {
        var registry = attributed.registry();
        boolean federationLink = attributed.federationLink();
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring ->
            registry.scalars().forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(EchoingWiringFactory.fakeScalar(name));
                }
            })
        );
        GraphQLSchema assembled;
        try {
            assembled = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        } catch (SchemaProblem e) {
            var recipeErrors = buildRecipeErrors(e);
            if (recipeErrors != null) {
                throw new ValidationFailedException(recipeErrors);
            }
            throw e;
        }
        var bctx = new BuildContext(assembled, new JooqCatalog(ctx.jooqPackage()), ctx);
        var svc = new ServiceCatalog(bctx);
        bctx.svc = svc;
        var typeBuilder = new TypeBuilder(bctx, svc);
        var fieldBuilder = new FieldBuilder(bctx, svc);
        var result = buildSchema(bctx, typeBuilder, fieldBuilder);
        return new Bundle(result.model, result.assembled, federationLink);
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
        // R12 §3 hash-suffix dedup: walk every WithErrorChannel field and apply the
        // collision-suffix rule to ErrorChannel.mappingsConstantName so the resolved name lands
        // on the carrier before the emitter runs. Pass-through for the common case (every
        // payload class has at most one channel shape).
        var dedupedFields = MappingsConstantNameDedup.apply(fields);
        Map<String, EntityResolution> entitiesByType =
            EntityResolutionBuilder.build(ctx.types, dedupedFields, rebuiltAssembled, ctx::addWarning);
        var model = new GraphitronSchema(
            ctx.types, Collections.unmodifiableMap(dedupedFields), entitiesByType, ctx.warnings());
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

    /**
     * Rewrites a {@link SchemaProblem} that includes at least one undeclared federation
     * directive into a {@link ValidationError} list whose federation entries point at the
     * getting-started recipe. Returns {@code null} when no error names a federation directive,
     * letting the caller rethrow the original {@code SchemaProblem} unchanged.
     *
     * <p>Mixed-error trade-off: when a {@code SchemaProblem} contains both federation and
     * non-federation undeclared-directive entries, every error in the bag is converted to
     * {@link RejectionKind#INVALID_SCHEMA}, dropping the original exception type for the
     * non-federation half. This is intentional: we cannot keep the {@code SchemaProblem}
     * around (it is unrecoverable once unwrapped) and also throw {@code ValidationFailedException}
     * with the recipe-rewrap entries for the federation half. The chosen behaviour preserves
     * every error message; it loses only the original exception subtype distinction. If a real
     * consumer surfaces wanting the original type back for the non-federation half, switch
     * to a two-pass strategy here (raise {@code ValidationFailedException} for the federation
     * half, rethrow the {@code SchemaProblem} for the rest).
     */
    private static List<ValidationError> buildRecipeErrors(SchemaProblem e) {
        var errors = e.getErrors();
        boolean anyFed = false;
        var result = new ArrayList<ValidationError>();
        for (var err : errors) {
            var m = UNDECLARED_DIRECTIVE_PATTERN.matcher(err.getMessage());
            var locs = err.getLocations();
            var loc = (locs != null && !locs.isEmpty()) ? locs.get(0) : null;
            if (m.find() && FederationDirectiveNamesHolder.NAMES.contains(m.group(1))) {
                anyFed = true;
                result.add(new ValidationError(RejectionKind.INVALID_SCHEMA, null,
                        buildRecipeMessage(m.group(1)), loc));
            } else {
                result.add(new ValidationError(RejectionKind.INVALID_SCHEMA, null,
                        err.getMessage(), loc));
            }
        }
        return anyFed ? result : null;
    }

    private static String buildRecipeMessage(String directiveName) {
        return "Federation directive '@" + directiveName + "' is not declared. Pick one:\n"
                + "  (1) Open one of your .graphqls files with\n"
                + "      `extend schema @link(url: \"https://specs.apollo.dev/federation/v2.x\",\n"
                + "                           import: [\"@" + directiveName + "\", ...])`\n"
                + "  (2) Or declare it manually with `directive @" + directiveName + " ... on ...`.\n"
                + "See graphitron-rewrite/docs/getting-started.adoc#build-time-federation-directives.";
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
        assertDirective(ctx, DIR_FIELD, ARG_NAME);
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
