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

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLInputObjectType;
import no.sikt.graphitron.rewrite.model.CarriesObjectForm;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis.MintedName;
import no.sikt.graphitron.rewrite.model.FacetNaming;
import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetsType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetValueType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.model.Rejection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONNECTION_NAME;
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
 * <p>Two entry points, both field-first. {@link #synthesiseForField} is called once
 * per visited field during the classification walk: when the field is an {@code @asConnection} or
 * structural connection carrier it registers the supporting types through
 * {@code ctx.typeRegistry.register} (the accumulator owns dedup and the {@code @tag} union across
 * carriers) and adds one {@link ConnectionSynthesis} row to the relation, carrying the minted
 * names with their absent-from-assembled discriminators and, on the directive-driven arm, the
 * carrier-rewrite facts. {@link #rebuildAssembledForConnections} then folds the finished
 * {@link ConnectionSynthesisRelation} to produce a {@link GraphQLSchema} whose carriers point
 * at the synthesised types and whose {@code first} / {@code after} arguments are present. The
 * rebuild consumes only the walk's outputs, so it cannot drift from the registry; rejection of
 * malformed {@code @asConnection} usage lives upstream in {@link FieldBuilder#classifyField}.
 *
 * <p>Stateless utility class. Per-build state lives in {@link BuildContext} and the relation's
 * {@link ConnectionSynthesisRelation.Builder}.
 */
final class ConnectionPromoter {

    private ConnectionPromoter() {
    }

    // Canonical graphql-relay-js descriptions for the synthesised Connection/Edge/PageInfo
    // boilerplate. Deliberately generic (not parameterised by element type): weaving in the
    // element name would make the generator responsible for phrasing that stays sensible across
    // every name and pluralisation. Synthesis-path only: an SDL author who wants different
    // wording declares the types structurally, which routes through promotionFor's structural
    // branch and never reaches these builders.
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
     * Promotes one visited field, when it is a Relay connection carrier (directive-driven
     * {@code @asConnection} on a bare list, or a structural Connection-shaped return type), to a
     * first-class {@link ConnectionType} / {@link EdgeType} / {@link PageInfoType} entry in
     * {@code ctx.types}, and adds the carrier's {@link ConnectionSynthesis} row to
     * {@code relation}. A no-op for every other field, so the walk calls it unconditionally per
     * field. Synthesis happens as a byproduct of visiting the carrier, never by scanning
     * siblings.
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
     * <p>The relation's construction enforces the minted-name axis: two carriers minting the same
     * connection name must agree on the shape they mint (the registry keeps only one reconciled
     * entry per name, so a disagreement would silently first-win there); a disagreement registers
     * a build diagnostic naming both carriers. Ordering constraint, review-only prose: this
     * method must run before the classification early-returns in the walk (so it fires for every
     * field, including fields on parents whose standalone classification is skipped); nothing in
     * the relation entails that, the walk's code order carries it.
     */
    static void synthesiseForField(
            BuildContext ctx, GraphQLObjectType parent, GraphQLFieldDefinition fieldDef,
            ConnectionSynthesisRelation.Builder relation) {
        ConnectionPromotion promotion = promotionFor(ctx, parent, fieldDef);
        if (promotion == null) return;
        var carrierLocation = BuildContext.locationOf(fieldDef);
        var rowMinted = new ArrayList<MintedName>(3);
        rowMinted.add(registerSynthesised(ctx, promotion.connectionName(), new ConnectionType(
            promotion.connectionName(), carrierLocation, promotion.elementTypeName(),
            promotion.edgeName(), promotion.itemNullable(), promotion.shareable(),
            promotion.facets(), promotion.connectionSchemaType())));
        rowMinted.add(registerSynthesised(ctx, promotion.edgeName(), new EdgeType(
            promotion.edgeName(), carrierLocation, promotion.elementTypeName(),
            promotion.itemNullable(), promotion.shareable(),
            promotion.edgeSchemaType())));
        var facetsMinted = registerFacetTypes(ctx, promotion, carrierLocation, relation);
        if (facetsMinted != null) rowMinted.add(facetsMinted);
        registerPageInfo(ctx, promotion, relation);

        ConnectionSynthesis row;
        if (promotion.directiveDriven()) {
            // The return type is rewritten unless the declared base type already carries the
            // minted connection name, in which case there is no swap to make.
            String currentBaseName = baseTypeName(fieldDef.getType());
            row = new ConnectionSynthesis.DirectiveDriven(
                parent.getName(), fieldDef.getName(), promotion.connectionName(),
                PaginationResolver.defaultPageSize(ctx.facts.pagination(), fieldDef),
                fieldDef.getType() instanceof GraphQLNonNull,
                !promotion.connectionName().equals(currentBaseName),
                rowMinted);
        } else {
            row = new ConnectionSynthesis.Structural(
                parent.getName(), fieldDef.getName(), promotion.connectionName(), rowMinted);
        }
        var conflict = relation.add(row, new ConnectionSynthesisRelation.MintedShape(
            promotion.elementTypeName(), promotion.itemNullable(), promotion.edgeName(),
            promotion.facets()));
        if (conflict != null) {
            var other = conflict.existingRow();
            ctx.addDiagnostic(ValidationError.forField(
                parent.getName() + "." + fieldDef.getName(),
                Rejection.invalidSchema("connection carriers '"
                    + other.parentTypeName() + "." + other.fieldName() + "' and '"
                    + parent.getName() + "." + fieldDef.getName() + "' both mint connection type '"
                    + promotion.connectionName() + "' but disagree on its shape (element type, "
                    + "item nullability, edge type or facets). Carriers sharing one connection "
                    + "name must project the same shape; give one of them its own connection name."),
                carrierLocation));
        }
    }

    /**
     * Registers the facet container ({@code <ConnName>Facets}) and each distinct
     * {@code <Scalar>FacetValue} entry for a faceted directive-driven carrier, returning the
     * container's {@link MintedName} (coordinate-grain, it rides the row) or {@code null} for a
     * facet-free carrier. {@code FacetValue} types are reusable across the whole schema (one per
     * (scalar, nullability) pair, named by {@link FacetNaming}), so they land on the relation's
     * schema-grain pool rather than the row; repeat registration from another carrier reconciles
     * in {@code TypeRegistry.register} like every other synthesised arm.
     */
    private static MintedName registerFacetTypes(
            BuildContext ctx, ConnectionPromotion promotion,
            graphql.language.SourceLocation carrierLocation,
            ConnectionSynthesisRelation.Builder relation) {
        if (promotion.facets().isEmpty()) return null;
        String facetsName = FacetNaming.facetsTypeName(promotion.connectionName());
        var facetsMinted = registerSynthesised(ctx, facetsName, new FacetsType(
            facetsName, carrierLocation, promotion.connectionName(),
            buildSynthesisedFacets(facetsName, promotion.facets())));
        for (var spec : promotion.facets()) {
            relation.addShared(registerSynthesised(ctx, spec.facetValueTypeName(), new FacetValueType(
                spec.facetValueTypeName(), carrierLocation, spec.valueTypeName(),
                spec.valueNullable(),
                buildSynthesisedFacetValue(spec))));
        }
        return facetsMinted;
    }

    /**
     * The single {@code PageInfo} every connection shares, a schema-grain slot on the relation.
     * When the SDL declares {@code PageInfo} it is registered verbatim (author-owned, never
     * tagged by promotion); otherwise a synthesised form carrying this carrier's
     * {@code shareable} flag and {@code @tag} applications is registered, and {@code register}
     * unions across carriers. Idempotent across repeated carriers either way.
     *
     * <p>Both arms route through {@link #registerSynthesised}, which derives the same
     * {@link MintedName} either arm would state by hand (an SDL-declared {@code PageInfo} is by
     * definition present in the assembled schema) and sweeps the registered form for the scalars it
     * references. The declared arm needs that sweep as much as the synthesised one: an SDL
     * {@code PageInfo} nothing authored references is registered here without ever being
     * walk-reached, so its {@code Boolean} and {@code String} field scalars are not registered
     * either.
     */
    private static void registerPageInfo(
            BuildContext ctx, ConnectionPromotion promotion,
            ConnectionSynthesisRelation.Builder relation) {
        if (ctx.schema.getType("PageInfo") instanceof GraphQLObjectType sdlPageInfo) {
            boolean shareable = sdlPageInfo.hasAppliedDirective("shareable");
            relation.addShared(registerSynthesised(ctx, "PageInfo",
                new PageInfoType("PageInfo", null, shareable, sdlPageInfo)));
        } else {
            relation.addShared(registerSynthesised(ctx, "PageInfo", new PageInfoType("PageInfo", null,
                promotion.shareable(),
                buildSynthesisedPageInfo(promotion.shareable(), promotion.tags()))));
        }
    }

    /**
     * Registers a synthesised arm and returns its {@link MintedName}, carrying the
     * absent-from-assembled discriminator (directive-driven Connection / Edge / facet types, and
     * the synthesised PageInfo, are absent; structural / SDL-declared names are present). The
     * post-walk rebuild reads the discriminator off the relation instead of re-probing the
     * schema, so exactly the absent set is added via {@code additionalType}.
     *
     * <p>Also demands registration of every scalar the registered form references, via
     * {@link #demandReferencedScalars}. The parameter's intersection bound is what makes that
     * single-sourced: an arm can only be registered here if it carries its graphql-java form
     * ({@link CarriesObjectForm}), so a future synthesised surface referencing a new scalar demands
     * it by construction instead of relying on someone remembering to extend a list.
     */
    private static <T extends GraphitronType & CarriesObjectForm> MintedName registerSynthesised(
            BuildContext ctx, String name, T type) {
        boolean absentFromAssembled = ctx.schema.getType(name) == null;
        demandReferencedScalars(ctx, type.schemaType());
        ctx.typeRegistry.register(name, type);
        return new MintedName(name, type.getClass(), absentFromAssembled);
    }

    /**
     * Demands a classification row for every scalar {@code form} references, so the emitted schema
     * registers the scalars its own synthesised surfaces name. {@code Int} rides in on
     * {@code Connection.totalCount} and {@code FacetValue.count}, {@code String} on
     * {@code Edge.cursor} and {@code PageInfo.startCursor}, {@code Boolean} on
     * {@code PageInfo.hasNextPage}; none of them need appear anywhere in the author's SDL.
     *
     * <p>Sweeps names, not instances. A minted form references its scalars as
     * {@link GraphQLTypeReference}, an SDL-wrapped form (the declared-{@code PageInfo} arm) as real
     * {@link graphql.schema.GraphQLScalarType} instances, and both unwrap to a
     * {@link GraphQLNamedType}. Every named reference is offered without filtering:
     * {@code TypeBuilder.ensureScalarRegistered} owns the which-names-are-scalars decision and
     * no-ops on the rest, so this sweep carries no type-axis knowledge that could disagree with the
     * classifier's.
     *
     * <p>The {@code first: Int} / {@code after: String} arguments {@link #rewriteCarrierField}
     * mints at rebuild time are not swept here (they are not on any registered form), and need not
     * be: both names are already demanded by the Connection and Edge forms above. Nothing in the
     * code entails that overlap, which is why the emitted population is swept again as a build-time
     * guard rather than left to this argument.
     */
    private static void demandReferencedScalars(BuildContext ctx, GraphQLObjectType form) {
        if (form == null || ctx.typeBuilder == null) return;
        for (var field : form.getFieldDefinitions()) {
            demandNamedReference(ctx, field.getType());
            for (var arg : field.getArguments()) {
                demandNamedReference(ctx, arg.getType());
            }
        }
    }

    private static void demandNamedReference(BuildContext ctx, GraphQLType type) {
        String name = BuildContext.referencedTypeName(type);
        if (name != null) ctx.typeBuilder.ensureScalarRegistered(name);
    }

    /**
     * Rewrites directive-driven {@code @asConnection} carrier fields so their return type
     * references the synthesised Connection and their arguments include {@code first} /
     * {@code after}, and registers the {@code synthesisedTypes} (the schema forms of every
     * synthesised entry absent from the original assembled schema) via
     * {@code additionalType(...)}.
     *
     * <p>Both inputs are the walk's own output: {@code synthesisedTypes} is resolved by the
     * builder from the relation's absent-from-assembled discriminators (never re-probed against
     * the schema), and the carrier-rewrite fold below is a total switch over the relation's two
     * row arms, so the rebuilt assembled schema cannot drift from the registry.
     *
     * <p>Ordering constraint, now partly a data dependency: step 1 (additional types) must run
     * before step 2 (carrier rewrites) because step 2's {@code typeRef("<ConnName>")} resolves
     * against the types step 1 adds, and graphql-java fails the transform loudly otherwise; the
     * sequencing itself stays code order inside this method, but step 1's input is relation row
     * data rather than a schema probe, so the two steps consume one producer's output.
     *
     * <p>Returns the rebuilt schema; untouched types pass through by reference via
     * {@link SchemaTransformer}, preserving applied directives and field order on everything
     * outside the carrier set.
     */
    static GraphQLSchema rebuildAssembledForConnections(
            GraphQLSchema original,
            List<GraphQLObjectType> synthesisedTypes,
            ConnectionSynthesisRelation relation) {
        // The carrier-rewrite fold: a TOTAL switch over the two row arms, so a third arm is a
        // compile error here rather than a silently-skipped carrier.
        var rewriteIndex = new LinkedHashMap<FieldCoordinates, ConnectionSynthesis.DirectiveDriven>();
        for (var row : relation.rows().values()) {
            switch (row) {
                case ConnectionSynthesis.DirectiveDriven dd -> {
                    if (dd.rewritesCarrierReturnType()) {
                        rewriteIndex.put(
                            FieldCoordinates.coordinates(dd.parentTypeName(), dd.fieldName()), dd);
                    }
                }
                case ConnectionSynthesis.Structural ignored -> { }
            }
        }
        if (rewriteIndex.isEmpty() && synthesisedTypes.isEmpty()) {
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
        for (var rewrite : rewriteIndex.values()) {
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

        if (rewriteIndex.isEmpty()) return withSynthesised;

        // Step 2: transform carrier fields, looked up by coordinate. typeRef("<ConnName>") now
        // resolves against the synthesised types added in step 1.
        var visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLFieldDefinition(
                    GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                var parent = context.getParentNode();
                if (!(parent instanceof GraphQLObjectType parentObj)) return TraversalControl.CONTINUE;
                var rewrite = rewriteIndex.get(FieldCoordinates.coordinates(parentObj.getName(), node.getName()));
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
     * is not a named type still present in the original schema; the rebuild then pins nothing
     * extra for that carrier rather than failing.
     */
    private static GraphQLNamedType carrierElementType(
            GraphQLSchema original, ConnectionSynthesis.DirectiveDriven rewrite) {
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
    private static GraphQLFieldDefinition rewriteCarrierField(
            GraphQLFieldDefinition original, ConnectionSynthesis.DirectiveDriven rewrite) {
        var ref = GraphQLTypeReference.typeRef(rewrite.connectionName());
        GraphQLOutputType newType = rewrite.outerNonNull() ? GraphQLNonNull.nonNull(ref) : ref;
        // defaultPageSize rode in on the directive-driven row from the pagination fact's one
        // resolved view (PaginationResolver.defaultPageSize), the same view the wrapper
        // classification reads, so the two emitted materialisations of the default cannot drift.
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

    private record ConnectionPromotion(
        boolean directiveDriven,
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
            // Directive arm: the carrier field is the tag source. TagApplier tags fields
            // (never type declarations), so a <schemaInput tag> source surfaces here too.
            var tags = fieldDef.getAppliedDirectives(TAG_DIRECTIVE);
            var facets = facetSpecsFor(fieldDef);
            var connSchema = buildSynthesisedConnection(connName, edgeName, elementTypeName, itemNullable, shareable, tags, facets);
            var edgeSchema = buildSynthesisedEdge(edgeName, elementTypeName, itemNullable, shareable, tags);
            return new ConnectionPromotion(true, connName, edgeName, elementTypeName,
                itemNullable, shareable, tags, facets, connSchema, edgeSchema);
        }

        // Structural: the return type shape is a declared Connection — reference the assembled type.
        String typeName = baseTypeName(unwrapped);
        if (typeName != null && ctx.isConnectionType(typeName)) {
            var connSchema = (GraphQLObjectType) ctx.schema.getType(typeName);
            boolean itemNullable = ctx.connectionItemNullable(typeName);
            String elementTypeName = ctx.connectionElementTypeName(typeName);
            // The edge name is the edges field's actual element type: the author owns the
            // structural shape, so no naming convention is assumed. (The directive arm above
            // keeps its minting formula; there the promoter owns both names.)
            var edgeSchema = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(
                connSchema.getFieldDefinition("edges").getType());
            String edgeName = edgeSchema.getName();
            boolean shareable = connSchema.hasAppliedDirective("shareable");
            // Structural arm: the SDL-declared Connection type is the tag source. Its own
            // @tag applications already ride on connSchema (the referenced SDL type), so they are
            // not re-applied here; they feed the synthesised PageInfo union below.
            var tags = connSchema.getAppliedDirectives(TAG_DIRECTIVE);
            // Facet synthesis applies only to directive-driven carriers: a structural Connection's
            // shape is author-owned, so the promoter never appends a facets field to it.
            return new ConnectionPromotion(false, typeName, edgeName, elementTypeName,
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
     * input-object arguments. The value scalar and its nullability mirror the input field's
     * list-element type exactly, so a client can feed {@code facetValue.value} straight back into
     * the filter; the column comes from {@code @field(name:)}.
     *
     * <p>Malformed applications are <em>skipped</em> here, not rejected: inclusion is gated on
     * the shared definition-keyed predicate {@link FacetFieldValidation#definitionKeyedRejection},
     * the single home both this walk and {@code GraphitronSchemaBuilder}'s facet-misuse reduction
     * read, so a skipped field is by construction one the reduction rejects with a named
     * diagnostic, and this walk stays a pure projection of the valid facets.
     */
    private static List<FacetSpec> facetSpecsFor(GraphQLFieldDefinition fieldDef) {
        List<FacetSpec> specs = new ArrayList<>();
        // First occurrence wins on a duplicate facet name across this carrier's filter inputs:
        // the synthesised <ConnName>Facets object cannot carry two same-named fields (graphql-java
        // would refuse to build it). The duplicate itself is rejected with a named diagnostic by
        // GraphitronSchemaBuilder's facet-misuse reduction; the dedup here only keeps synthesis
        // from crashing before that diagnostic surfaces.
        var seenNames = new java.util.HashSet<String>();
        for (var arg : fieldDef.getArguments()) {
            if (!(GraphQLTypeUtil.unwrapAll(arg.getType()) instanceof GraphQLInputObjectType inputType)) continue;
            for (var inputField : inputType.getFieldDefinitions()) {
                if (!inputField.hasAppliedDirective(DIR_AS_FACET)) continue;
                if (FacetFieldValidation.definitionKeyedRejection(inputField) != null) continue;
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
                if (!seenNames.add(inputField.getName())) continue;
                specs.add(new FacetSpec(arg.getName(), inputField.getName(), columnName,
                    named.getName(), valueNullable,
                    FacetNaming.facetValueTypeName(named.getName(), valueNullable)));
            }
        }
        return List.copyOf(specs);
    }

    /**
     * The synthesised {@code <ConnName>Facets} container: one nullable list field per facet
     * ({@code [<Scalar>FacetValue!]}), field name matching the filter-input field name. Field
     * nullability is the facet failure firewall: a facet that fails or
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
        // Nullable so the count-skipped path on a split connection degrades to null totalCount.
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
        // rather than propagate a failure into the connection.
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
     * union itself lives in {@code TypeRegistry.register}.
     */
    private static final String TAG_DIRECTIVE = "tag";
}
