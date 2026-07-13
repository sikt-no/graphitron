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
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.SchemaTransformer;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

import graphql.schema.GraphQLInputObjectType;
import no.sikt.graphitron.rewrite.model.FacetNaming;
import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetsType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetValueType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONNECTION_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DEFAULT_FIRST_VALUE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_FACET;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;

/**
 * Promotes Connection-shaped carrier fields and synthesises the supporting
 * {@link ConnectionType} / {@link EdgeType} / {@link PageInfoType} entries.
 *
 * <p>Two entry points, both field-first (R279 slice 5). {@link #synthesiseForField} is called once
 * per visited field during the classification walk: when the field is an {@code @asConnection} or
 * structural connection carrier it registers the supporting types through
 * {@code ctx.typeRegistry.register} (the accumulator owns dedup and the {@code @tag} union across
 * carriers), records any carrier rewrite, and notes which synthesised names are absent from the
 * assembled schema. {@link #rebuildAssembledForConnections} then consumes the resolved
 * synthesised-type set and the rewrite list to produce a {@link GraphQLSchema} whose carriers point
 * at the synthesised types and whose {@code first} / {@code after} arguments are present. There is no
 * standalone all-types promotion pass and no second {@code ctx.types} scan, so the rebuilt assembled
 * schema cannot drift from the registry (rejection of malformed {@code @asConnection} usage lives
 * upstream in {@link FieldBuilder#classifyField}).
 *
 * <p>Stateless utility class. Per-build state lives in {@link BuildContext}.
 */
final class ConnectionPromoter {

    private ConnectionPromoter() {
    }

    // Canonical graphql-relay-js descriptions for the synthesised Connection/Edge/PageInfo
    // boilerplate. Generic (not parameterised by element type): the wording is true for every
    // synthesised type, and weaving in the element name would make the generator responsible for
    // phrasing that stays sensible across every name and pluralisation. These apply only on the
    // synthesis path below; an SDL author who wants different wording declares the
    // Connection/Edge/PageInfo types structurally, which routes through promotionFor's structural
    // branch instead and never reaches these builders.
    private static final String DESC_CONNECTION = "A connection to a list of items.";
    private static final String DESC_EDGES = "A list of edges.";
    private static final String DESC_NODES = "A list of nodes.";
    private static final String DESC_PAGE_INFO_FIELD = "Information to aid in pagination.";
    private static final String DESC_TOTAL_COUNT = "Identifies the total count of items in the connection.";
    private static final String DESC_EDGE = "An edge in a connection.";
    private static final String DESC_CURSOR = "A cursor for use in pagination.";
    private static final String DESC_NODE = "The item at the end of the edge.";
    private static final String DESC_PAGE_INFO = "Information about pagination in a connection.";
    private static final String DESC_HAS_NEXT_PAGE = "When paginating forwards, are there more items?";
    private static final String DESC_HAS_PREVIOUS_PAGE = "When paginating backwards, are there more items?";
    private static final String DESC_START_CURSOR = "When paginating backwards, the cursor to continue.";
    private static final String DESC_END_CURSOR = "When paginating forwards, the cursor to continue.";
    private static final String DESC_FACETS = "Per-facet value counts for the items in the connection.";
    private static final String DESC_FACETS_TYPE = "Facet value counts for a connection.";
    private static final String DESC_FACET_FIELD = "Value counts for this facet, under the connection's filter minus this facet's own predicate.";
    private static final String DESC_FACET_VALUE_TYPE = "One facet bucket: a filterable value and its count.";
    private static final String DESC_FACET_VALUE = "The facet value; feed it back into the filter to select this bucket.";
    private static final String DESC_FACET_COUNT = "The number of items in this bucket.";

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
     * Promotes one visited field, when it is a Relay connection carrier (directive-driven
     * {@code @asConnection} on a bare list, or a structural Connection-shaped return type), to a
     * first-class {@link ConnectionType} / {@link EdgeType} / {@link PageInfoType} entry in
     * {@code ctx.types}. A no-op for every other field, so the walk calls it unconditionally per
     * field. This is the field-first replacement for the former all-types promotion pass (R279
     * slice 5): synthesis happens as a byproduct of visiting the carrier, never by scanning siblings.
     *
     * <p>For directive-driven carriers the {@link GraphQLObjectType} schema form is built
     * programmatically (the synthesised types are not in the assembled schema). For structural
     * carriers the schema form is referenced from the assembled schema, where it was parsed from
     * the SDL.
     *
     * <p>Each type is registered through {@code ctx.typeRegistry.register}, which owns reconciliation:
     * a second carrier reaching the same connection name, and every carrier feeding the one shared
     * {@code PageInfo}, accumulates the union of their {@code @tag} applications there rather than
     * here. So this method neither dedups nor unions; it just registers what the current carrier
     * implies. The synthesised type's {@code location} pins to the first carrier that registers it
     * (register keeps the existing structural fields on a merge). The synthesised {@code PageInfo}'s
     * {@code location} is deliberately {@code null}: a single PageInfo serves every connection, so no
     * carrier site is the actionable one.
     *
     * @param rewrites           accumulates the per-carrier {@link CarrierRewrite}s (carriers whose
     *                           graphql-java return type must change to name the Connection)
     * @param synthesisedNames   accumulates the registered Connection / Edge / PageInfo names that are
     *                           <em>absent from the assembled schema</em>, i.e. the set
     *                           {@link #rebuildAssembledForConnections} must add via
     *                           {@code additionalType}; the walk's owner resolves these to their final
     *                           (post-tag-union) schema forms after the walk
     */
    static void synthesiseForField(
            BuildContext ctx, GraphQLObjectType parent, GraphQLFieldDefinition fieldDef,
            List<CarrierRewrite> rewrites, Set<String> synthesisedNames) {
        ConnectionPromotion promotion = promotionFor(ctx, parent, fieldDef);
        if (promotion == null) return;
        // Record a carrier rewrite only when the graphql-java return type actually needs to change —
        // i.e. the current base-type name differs from the connection name. Directive-driven bare-list
        // carriers need rewriting; structural carriers (where the SDL return type already names the
        // Connection) do not, even when they carry @asConnection alongside.
        String currentBaseName = baseTypeName(fieldDef.getType());
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)
                && !promotion.connectionName().equals(currentBaseName)) {
            rewrites.add(new CarrierRewrite(
                parent.getName(), fieldDef.getName(), promotion.connectionName(),
                resolveDefaultFirstValue(fieldDef), fieldDef.getType() instanceof GraphQLNonNull));
        }
        var carrierLocation = BuildContext.locationOf(fieldDef);
        registerSynthesised(ctx, promotion.connectionName(), new ConnectionType(
            promotion.connectionName(), carrierLocation, promotion.elementTypeName(),
            promotion.edgeName(), promotion.itemNullable(), promotion.shareable(),
            promotion.facets(), promotion.connectionSchemaType()), synthesisedNames);
        registerSynthesised(ctx, promotion.edgeName(), new EdgeType(
            promotion.edgeName(), carrierLocation, promotion.elementTypeName(),
            promotion.itemNullable(), promotion.shareable(),
            promotion.edgeSchemaType()), synthesisedNames);
        registerFacetTypes(ctx, promotion, carrierLocation, synthesisedNames);
        registerPageInfo(ctx, promotion, synthesisedNames);
    }

    /**
     * Registers the facet container ({@code <ConnName>Facets}) and each distinct
     * {@code <Scalar>FacetValue} entry for a faceted directive-driven carrier (R13). A no-op when
     * the promotion carries no facets. {@code FacetValue} types are reusable across the whole
     * schema (one per (scalar, nullability) pair, named by {@link FacetNaming}); repeat
     * registration from another carrier reconciles in {@code TypeRegistry.register} like every
     * other synthesised arm.
     */
    private static void registerFacetTypes(
            BuildContext ctx, ConnectionPromotion promotion,
            graphql.language.SourceLocation carrierLocation, Set<String> synthesisedNames) {
        if (promotion.facets().isEmpty()) return;
        String facetsName = FacetNaming.facetsTypeName(promotion.connectionName());
        registerSynthesised(ctx, facetsName, new FacetsType(
            facetsName, carrierLocation, promotion.connectionName(),
            buildSynthesisedFacets(facetsName, promotion.facets())), synthesisedNames);
        for (var spec : promotion.facets()) {
            registerSynthesised(ctx, spec.facetValueTypeName(), new FacetValueType(
                spec.facetValueTypeName(), carrierLocation, spec.valueTypeName(),
                spec.valueNullable(),
                buildSynthesisedFacetValue(spec)), synthesisedNames);
        }
    }

    /**
     * The single {@code PageInfo} every connection shares. When the SDL declares {@code PageInfo} it
     * is registered verbatim (author-owned, never tagged by promotion); otherwise a synthesised form
     * carrying this carrier's {@code shareable} flag and {@code @tag} applications is registered, and
     * {@code register} unions across carriers. Idempotent across repeated carriers either way.
     */
    private static void registerPageInfo(
            BuildContext ctx, ConnectionPromotion promotion, Set<String> synthesisedNames) {
        if (ctx.schema.getType("PageInfo") instanceof GraphQLObjectType sdlPageInfo) {
            boolean shareable = sdlPageInfo.hasAppliedDirective("shareable");
            ctx.typeRegistry.register("PageInfo", new PageInfoType("PageInfo", null, shareable, sdlPageInfo));
        } else {
            registerSynthesised(ctx, "PageInfo", new PageInfoType("PageInfo", null,
                promotion.shareable(),
                buildSynthesisedPageInfo(promotion.shareable(), promotion.tags())), synthesisedNames);
        }
    }

    /**
     * Registers a synthesised arm and notes its name in {@code synthesisedNames} when it is absent
     * from the assembled schema (directive-driven Connection / Edge, and the synthesised PageInfo),
     * so the post-walk rebuild adds exactly those via {@code additionalType}. Structural / SDL-declared
     * names are already in the assembled schema, so they are registered but not noted.
     */
    private static void registerSynthesised(
            BuildContext ctx, String name, GraphitronType type, Set<String> synthesisedNames) {
        if (ctx.schema.getType(name) == null) synthesisedNames.add(name);
        ctx.typeRegistry.register(name, type);
    }

    /**
     * Rewrites directive-driven {@code @asConnection} carrier fields so their return type
     * references the synthesised Connection and their arguments include {@code first} /
     * {@code after}, and registers the {@code synthesisedTypes} (the schema forms of every
     * Connection / Edge / PageInfo entry absent from the original assembled schema) via
     * {@code additionalType(...)}.
     *
     * <p>R279 slice 5: {@code synthesisedTypes} is the set the walk synthesised via
     * {@link #synthesiseForField} (resolved to final forms by the builder after the walk), so this
     * method no longer re-derives it from a second {@code ctx.types} scan and the rebuilt assembled
     * schema cannot drift from the registry.
     *
     * <p>Returns the rebuilt schema; untouched types pass through by reference via
     * {@link SchemaTransformer}, preserving applied directives and field order on everything
     * outside the carrier set.
     */
    static GraphQLSchema rebuildAssembledForConnections(
            GraphQLSchema original,
            List<GraphQLObjectType> synthesisedTypes,
            List<CarrierRewrite> rewrites) {
        if (rewrites.isEmpty() && synthesisedTypes.isEmpty()) {
            return original;
        }

        // Step 1: register synthesised types on the schema so later carrier rewrites can reference
        // them by typeRef without graphql-java's build-time validation failing.
        //
        // The carrier element types are pinned here as additional types too. After step 2 rewrites a
        // bare-list carrier to name its synthesised Connection, the element type is referenced only
        // through the Connection's `nodes` / Edge's `node` typeRefs. SchemaTransformer rebuilds its
        // type map from the concretely-traversed graph (typeRefs are leaves, not followed), so an
        // element type reachable in the original schema only through that one carrier, and its whole
        // transitive subgraph, would otherwise be pruned: either dropped silently (its `<Name>Type`
        // schema class is never emitted yet GraphitronSchema names it, so generated code fails to
        // compile) or, when a surviving typeRef still points at it, an NPE inside the transform's
        // type-reference resolver. Pinning the element type as an additional root keeps it (and its
        // children) concretely reachable so neither happens. Concretely reachable subgraphs hang off
        // these roots, so pinning the direct carrier elements suffices.
        var pinned = new LinkedHashMap<String, GraphQLType>();
        for (var schemaType : synthesisedTypes) {
            pinned.putIfAbsent(((GraphQLNamedType) schemaType).getName(), schemaType);
        }
        for (var rewrite : rewrites) {
            var elementType = carrierElementType(original, rewrite);
            if (elementType != null) pinned.putIfAbsent(elementType.getName(), elementType);
        }

        var withSynthesised = original;
        if (!pinned.isEmpty()) {
            var extrasBuilder = GraphQLSchema.newSchema(original);
            for (var schemaType : pinned.values()) {
                extrasBuilder.additionalType(schemaType);
            }
            withSynthesised = extrasBuilder.build();
        }

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

    /**
     * Resolves the concrete element type a directive-driven carrier returns in the {@code original}
     * (pre-rewrite) schema: the named base type of {@code <parentTypeName>.<fieldName>}'s bare list.
     * Returns {@code null} when the carrier's parent type or field cannot be found, or the base type
     * is not a named type still present in the original schema — the rebuild then simply pins nothing
     * extra for that carrier rather than failing.
     */
    private static GraphQLNamedType carrierElementType(GraphQLSchema original, CarrierRewrite rewrite) {
        if (!(original.getType(rewrite.parentTypeName()) instanceof GraphQLObjectType parent)) return null;
        var field = parent.getFieldDefinition(rewrite.fieldName());
        if (field == null) return null;
        var base = GraphQLTypeUtil.unwrapAll(field.getType());
        if (!(base instanceof GraphQLNamedType named)) return null;
        return original.getType(named.getName()) instanceof GraphQLNamedType resolved ? resolved : null;
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
        List<GraphQLAppliedDirective> tags,
        List<FacetSpec> facets,
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
            // Directive arm: the carrier field is the tag source (R295). TagApplier tags fields
            // (never type declarations), so a <schemaInput tag> source surfaces here too.
            var tags = fieldDef.getAppliedDirectives(TAG_DIRECTIVE);
            var facets = facetSpecsFor(fieldDef);
            var connSchema = buildSynthesisedConnection(connName, edgeName, elementTypeName, itemNullable, shareable, tags, facets);
            var edgeSchema = buildSynthesisedEdge(edgeName, elementTypeName, itemNullable, shareable, tags);
            return new ConnectionPromotion(connName, edgeName, elementTypeName,
                itemNullable, shareable, tags, facets, connSchema, edgeSchema);
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
            // Structural arm: the SDL-declared Connection type is the tag source (R295). Its own
            // @tag applications already ride on connSchema (the referenced SDL type), so they are
            // not re-applied here; they feed the synthesised PageInfo union below.
            var tags = connSchema.getAppliedDirectives(TAG_DIRECTIVE);
            // Facet synthesis applies only to directive-driven carriers: a structural Connection's
            // shape is author-owned, so the promoter never appends a facets field to it.
            return new ConnectionPromotion(typeName, edgeName, elementTypeName,
                itemNullable, shareable, tags, List.of(), connSchema, edgeSchema);
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
        return no.sikt.graphitron.rewrite.model.ConnectionNaming
            .defaultConnectionName(parentTypeName, field.getName());
    }

    /**
     * Derives one {@link FacetSpec} per well-formed {@code @asFacet}-marked field on the carrier's
     * input-object arguments (R13). The value scalar and its nullability mirror the input field's
     * list-element type exactly, so a client can feed {@code facetValue.value} straight back into
     * the filter; the column comes from {@code @field(name:)}.
     *
     * <p>Malformed applications (no {@code @field}, co-occurrence with
     * {@code @reference} / {@code @condition} / {@code @nodeId}, an {@code ID} or non-leaf value
     * type) are <em>skipped</em> here, not rejected: {@code GraphitronSchemaBuilder}'s facet-misuse
     * reduction walks the same directive surface and registers a build diagnostic for each, so the
     * build fails with a named error while this walk stays a pure projection of the valid facets.
     */
    private static List<FacetSpec> facetSpecsFor(GraphQLFieldDefinition fieldDef) {
        List<FacetSpec> specs = new ArrayList<>();
        for (var arg : fieldDef.getArguments()) {
            if (!(GraphQLTypeUtil.unwrapAll(arg.getType()) instanceof GraphQLInputObjectType inputType)) continue;
            for (var inputField : inputType.getFieldDefinitions()) {
                if (!inputField.hasAppliedDirective(DIR_AS_FACET)) continue;
                if (!inputField.hasAppliedDirective(DIR_FIELD)
                        || inputField.hasAppliedDirective(DIR_REFERENCE)
                        || inputField.hasAppliedDirective(DIR_CONDITION)
                        || inputField.hasAppliedDirective(DIR_NODE_ID)
                        || inputField.getType() instanceof GraphQLNonNull) {
                    continue;
                }
                String columnName = BuildContext.argString(inputField, DIR_FIELD, ARG_NAME).orElse(null);
                if (columnName == null) continue;
                // Element type: for a list field the list element, otherwise the field itself.
                // Nullability is read before unwrapping so it mirrors the filter's element exactly.
                GraphQLType elementLayer = inputField.getType() instanceof GraphQLNonNull nn
                    ? nn.getWrappedType() : inputField.getType();
                if (elementLayer instanceof GraphQLList list) {
                    elementLayer = list.getWrappedType();
                } else {
                    elementLayer = inputField.getType();
                }
                boolean valueNullable = !(elementLayer instanceof GraphQLNonNull);
                GraphQLType leaf = GraphQLTypeUtil.unwrapAll(elementLayer);
                if (!(leaf instanceof GraphQLNamedType named)) continue;
                if (leaf instanceof GraphQLInputObjectType || "ID".equals(named.getName())) continue;
                specs.add(new FacetSpec(inputField.getName(), columnName, named.getName(),
                    valueNullable, FacetNaming.facetValueTypeName(named.getName(), valueNullable)));
            }
        }
        return List.copyOf(specs);
    }

    /**
     * The synthesised {@code <ConnName>Facets} container: one nullable list field per facet
     * ({@code [<Scalar>FacetValue!]}), field name matching the filter-input field name. Field
     * nullability is the failure firewall (R13 "Facet failure semantics"): a facet that fails or
     * times out degrades to null on its own field, never propagating through GraphQL non-null
     * bubbling to the connection.
     */
    private static GraphQLObjectType buildSynthesisedFacets(String facetsName, List<FacetSpec> facets) {
        var builder = GraphQLObjectType.newObject()
            .name(facetsName)
            .description(DESC_FACETS_TYPE);
        for (var spec : facets) {
            builder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(spec.inputFieldName())
                .description(DESC_FACET_FIELD)
                .type(GraphQLList.list(GraphQLNonNull.nonNull(
                    GraphQLTypeReference.typeRef(spec.facetValueTypeName()))))
                .build());
        }
        return builder.build();
    }

    /**
     * The synthesised {@code <Scalar>FacetValue} form: {@code value} mirrors the filter element's
     * scalar and nullability exactly, {@code count} is a non-null {@code Int}.
     */
    private static GraphQLObjectType buildSynthesisedFacetValue(FacetSpec spec) {
        GraphQLOutputType valueType = spec.valueNullable()
            ? GraphQLTypeReference.typeRef(spec.valueTypeName())
            : GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(spec.valueTypeName()));
        return GraphQLObjectType.newObject()
            .name(spec.facetValueTypeName())
            .description(DESC_FACET_VALUE_TYPE)
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("value")
                .description(DESC_FACET_VALUE)
                .type(valueType)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("count")
                .description(DESC_FACET_COUNT)
                .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("Int")))
                .build())
            .build();
    }

    private static GraphQLObjectType buildSynthesisedConnection(String connName, String edgeName,
            String elementTypeName, boolean itemNullable, boolean shareable,
            List<GraphQLAppliedDirective> tags, List<FacetSpec> facets) {
        var edgesField = GraphQLFieldDefinition.newFieldDefinition()
            .name("edges")
            .description(DESC_EDGES)
            .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(
                GraphQLTypeReference.typeRef(edgeName)))))
            .build();
        GraphQLOutputType nodeInnerRef = itemNullable
            ? GraphQLTypeReference.typeRef(elementTypeName)
            : GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(elementTypeName));
        var nodesField = GraphQLFieldDefinition.newFieldDefinition()
            .name("nodes")
            .description(DESC_NODES)
            .type(GraphQLNonNull.nonNull(GraphQLList.list(nodeInnerRef)))
            .build();
        var pageInfoField = GraphQLFieldDefinition.newFieldDefinition()
            .name("pageInfo")
            .description(DESC_PAGE_INFO_FIELD)
            .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("PageInfo")))
            .build();
        // Nullable matches legacy and survives the count-skipped path on Split-Connection naturally.
        var totalCountField = GraphQLFieldDefinition.newFieldDefinition()
            .name("totalCount")
            .description(DESC_TOTAL_COUNT)
            .type(GraphQLTypeReference.typeRef("Int"))
            .build();
        var builder = GraphQLObjectType.newObject()
            .name(connName)
            .description(DESC_CONNECTION)
            .field(edgesField)
            .field(nodesField)
            .field(pageInfoField)
            .field(totalCountField);
        // Nullable, like totalCount: facets are a best-effort aggregate that must degrade to null
        // rather than propagate a failure into the connection (R13 "Facet failure semantics").
        if (!facets.isEmpty()) {
            builder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name("facets")
                .description(DESC_FACETS)
                .type(GraphQLTypeReference.typeRef(FacetNaming.facetsTypeName(connName)))
                .build());
        }
        if (shareable) builder.withAppliedDirective(GraphQLAppliedDirective.newDirective().name("shareable").build());
        for (var tag : tags) builder.withAppliedDirective(tag);
        return builder.build();
    }

    private static GraphQLObjectType buildSynthesisedEdge(String edgeName, String elementTypeName,
            boolean itemNullable, boolean shareable, List<GraphQLAppliedDirective> tags) {
        var cursorField = GraphQLFieldDefinition.newFieldDefinition()
            .name("cursor")
            .description(DESC_CURSOR)
            .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("String")))
            .build();
        GraphQLOutputType nodeType = itemNullable
            ? GraphQLTypeReference.typeRef(elementTypeName)
            : GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(elementTypeName));
        var nodeField = GraphQLFieldDefinition.newFieldDefinition()
            .name("node")
            .description(DESC_NODE)
            .type(nodeType)
            .build();
        var builder = GraphQLObjectType.newObject()
            .name(edgeName)
            .description(DESC_EDGE)
            .field(cursorField)
            .field(nodeField);
        if (shareable) builder.withAppliedDirective(GraphQLAppliedDirective.newDirective().name("shareable").build());
        for (var tag : tags) builder.withAppliedDirective(tag);
        return builder.build();
    }

    private static GraphQLObjectType buildSynthesisedPageInfo(boolean shareable,
            List<GraphQLAppliedDirective> tags) {
        var builder = GraphQLObjectType.newObject()
            .name("PageInfo")
            .description(DESC_PAGE_INFO)
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("hasNextPage")
                .description(DESC_HAS_NEXT_PAGE)
                .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("Boolean")))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("hasPreviousPage")
                .description(DESC_HAS_PREVIOUS_PAGE)
                .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("Boolean")))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("startCursor")
                .description(DESC_START_CURSOR)
                .type(GraphQLTypeReference.typeRef("String"))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("endCursor")
                .description(DESC_END_CURSOR)
                .type(GraphQLTypeReference.typeRef("String"))
                .build());
        if (shareable) builder.withAppliedDirective(GraphQLAppliedDirective.newDirective().name("shareable").build());
        for (var tag : tags) builder.withAppliedDirective(tag);
        return builder.build();
    }

    /**
     * The federation {@code @tag} directive name; matches {@code TagApplier.TAG_DIRECTIVE_NAME}.
     * Read by {@link #promotionFor} to seed the synthesised forms; the cross-carrier {@code @tag}
     * union itself lives in {@code TypeRegistry.register} (R279 slice 5).
     */
    private static final String TAG_DIRECTIVE = "tag";
}
