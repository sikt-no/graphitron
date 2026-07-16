package no.sikt.graphitron.rewrite;

import com.apollographql.federation.graphqljava.FederationDirectives;
import graphql.language.DirectiveDefinition;
import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.schema.idl.EchoingWiringFactory;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.DomainReturnType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.EmitsPerTypeFile;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.Rejection.InvalidSchema.CaseFoldCollision.Origin;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.schema.federation.EntityResolutionBuilder;
import no.sikt.graphitron.rewrite.schema.federation.FederationSpec;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        var bctx = new BuildContext(assembled, new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), ctx);
        recordSdlScalarDirectives(registry, bctx);
        var svc = new ServiceCatalog(bctx);
        bctx.svc = svc;
        var typeBuilder = new TypeBuilder(bctx, svc);
        bctx.typeBuilder = typeBuilder;
        var fieldBuilder = new FieldBuilder(bctx, svc);
        fieldBuilder.setTypeBuilder(typeBuilder);
        var result = buildSchema(bctx, typeBuilder, fieldBuilder);
        return new Bundle(result.model, result.assembled, federationLink);
    }

    /**
     * Reads the {@link TypeDefinitionRegistry}'s scalar definitions / extensions and copies the
     * applied-directive names onto the {@link BuildContext}. Graphql-java's {@code SchemaGenerator}
     * picks its own {@code GraphQLScalarType} instances for spec built-ins ({@code GraphQLString},
     * {@code GraphQLInt}, ...) and discards SDL applied directives that target those names, so a
     * {@code scalar String @scalarType(...)} declaration silently loses the directive at
     * assembly time. The pre-pass keeps the directive-name signal alive so the type classifier
     * can raise a {@code Rejection.InvalidSchema.DirectiveConflict} at directive-read time.
     *
     * <p>Non-built-in scalars carry their applied directives through to the assembled schema, so
     * for those the classifier can keep reading from the {@code GraphQLScalarType} directly. The
     * pre-pass is general enough to record both regardless; the consumer decides which source to
     * trust.
     */
    private static void recordSdlScalarDirectives(TypeDefinitionRegistry registry, BuildContext bctx) {
        java.util.function.BiConsumer<String, java.util.List<graphql.language.Directive>> record = (name, directives) -> {
            if (directives == null || directives.isEmpty()) return;
            var names = new java.util.LinkedHashSet<String>();
            for (var d : directives) names.add(d.getName());
            bctx.recordSdlScalarDirectives(name, names);
        };
        registry.scalars().forEach((name, def) -> record.accept(name, def.getDirectives()));
        registry.scalarTypeExtensions().forEach((name, exts) -> {
            var collected = new java.util.LinkedHashSet<String>();
            for (var ext : exts) {
                if (ext.getDirectives() == null) continue;
                for (var d : ext.getDirectives()) collected.add(d.getName());
            }
            if (!collected.isEmpty()) {
                var existing = new java.util.LinkedHashSet<>(bctx.sdlScalarDirectiveNames(name));
                existing.addAll(collected);
                bctx.recordSdlScalarDirectives(name, existing);
            }
        });
    }

    /**
     * Test-only seam: builds and returns the {@link BuildContext} after type classification,
     * without running field classification. Used by resolver-tier unit tests
     * ({@link no.sikt.graphitron.rewrite.NodeIdLeafResolverTest}) that need the same fully-wired
     * {@code BuildContext} the orchestrator hands to {@link FieldBuilder}, but without the
     * field-classification side effects (which would consume the resolver).
     */
    static BuildContext buildContextForTests(AttributedRegistry attributed, RewriteContext ctx) {
        var registry = attributed.registry();
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring ->
            registry.scalars().forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(EchoingWiringFactory.fakeScalar(name));
                }
            })
        );
        GraphQLSchema assembled = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        var bctx = new BuildContext(assembled, new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), ctx);
        recordSdlScalarDirectives(registry, bctx);
        var svc = new ServiceCatalog(bctx);
        bctx.svc = svc;
        var typeBuilder = new TypeBuilder(bctx, svc);
        bctx.typeBuilder = typeBuilder;
        // The types-only half of the single walk: classify the reachable composites on
        // enter (a ClassifyingVisitor with no FieldBuilder, so no field-classification side effects),
        // then the post-walk type-level work. This reproduces the type registry the deleted
        // buildTypes() built, without consuming the resolver via field classification (which
        // NodeIdLeafResolverTest depends on; see this method's javadoc).
        typeBuilder.prepareForWalk();
        SchemaReachability.walk(bctx.schema,
            new ClassifyingVisitor(bctx, typeBuilder, null, null, null));
        typeBuilder.finishTypeClassification();
        return bctx;
    }

    private record BuildResult(GraphitronSchema model, GraphQLSchema assembled) {}

    private static BuildResult buildSchema(BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder) {
        validateDirectiveSchema(ctx);
        // The single classify-and-emit walk. One SchemaTraverser.depthFirst over the
        // reachable output surface classifies each composite type on enter AND classifies the fields of
        // each reached object in the same visit (ClassifyingVisitor), replacing the three former
        // traversals of that surface: the SchemaReachability name-set walk, TypeBuilder.buildTypes'
        // eager type loop, and this method's separate field loop. The fold is possible because field
        // classification became registry-read-free for every target verdict: a
        // field's output target is a not-yet-visited child of its parent under the enter-only traversal,
        // and the field resolves it through the registry-free look-ahead / fixed-point indices, never a
        // registry lookup. buildTypes, the reachableOutputTypes hand-off, and the field loop are deleted.
        //
        // Connection synthesis stays a byproduct of visiting each field
        // (ConnectionPromoter.synthesiseForField inside classifyFieldsOfObject); the walk accumulates
        // the carrier rewrites and the synthesised names absent from the assembled schema.
        typeBuilder.prepareForWalk();
        var connectionRewrites = new ArrayList<ConnectionPromoter.CarrierRewrite>();
        var synthesisedConnectionNames = new LinkedHashSet<String>();
        SchemaReachability.walk(ctx.schema, new ClassifyingVisitor(
            ctx, typeBuilder, fieldBuilder, connectionRewrites, synthesisedConnectionNames));
        // The post-walk type-level work: classify the input / scalar / enum kinds the output walk never
        // reaches, then the global validation reductions over the finished registry. Runs after the walk
        // because field classification is registry-read-free, so the reductions change no verdict.
        typeBuilder.finishTypeClassification();
        // NestingType registration is folded onto the embedding edge (per classified
        // field in classifyFieldsOfObject), so the former post-walk registerNestingTypes sweep over the
        // whole field registry is gone.
        // The multi-producer DomainReturnType agreement check is detected here (on the
        // pre-dangling field registry, against the assembled-schema SDL-Object axis) but no longer
        // demotes the producers; the conflicts ride on the schema's diagnostic channel and the
        // validator surfaces them, closing the enforcement in the same commit so no gap opens.
        // Connection synthesis above does not touch the field registry, so the conflict set is
        // unaffected by its relocation into the walk. This and the three reductions
        // below register build-time diagnostics on ctx rather than demoting a verdict; see
        // GraphitronSchema.diagnostics.
        collectDomainReturnTypeConflicts(ctx);
        rejectDanglingTypeReferences(ctx);
        // The synthesised-type set for the rebuild is resolved once, here, from the names the walk
        // flagged (their final post-tag-union forms live on the registry). rebuild consumes this typed
        // set rather than re-deriving it, so the rebuilt assembled schema cannot drift from the registry.
        var synthesisedConnectionTypes = resolveSynthesisedConnectionTypes(ctx, synthesisedConnectionNames);
        var rebuiltAssembled = ConnectionPromoter.rebuildAssembledForConnections(
            ctx.schema, synthesisedConnectionTypes, connectionRewrites);
        // Reject case-insensitive type-name collisions. Graphitron emits one Java file per
        // type-name stem; on case-insensitive filesystems two case-equivalent names would clobber
        // each other. Runs post-promotion so synth-vs-synth Connection-name clashes (the consumer
        // repro) are visible. Registers a build-time diagnostic per colliding member
        // rather than demoting the registry entry, so the colliding types keep their classified
        // verdict and the assembled schema stays consistent; the validator surfaces the collision by
        // draining the channel.
        rejectCaseInsensitiveTypeCollisions(ctx);
        // Deprecation signal over @table-on-input usages, carving out the encoded-ID /
        // scalar-return INSERT/UPSERT case whose only write-target signal is still the input's
        // @table (a later consumer-derived-table change retires that carve-out). Placed beside the other post-classification
        // cross-cutting passes on the live ctx; reads the classified model (the carve-out set is
        // computed off the field registry's MutationField leaves).
        emitTableOnInputDeprecationWarnings(ctx);
        // Reject every @nodeId application whose coordinate is not of unwrapped type ID. Reads
        // ctx.schema applied directives (not the registry) because a dropped @nodeId leaves no trace
        // on the classified field — see rejectNonIdNodeId. Sibling soundness reduction; registers a
        // build-time diagnostic the validator drains, demoting nothing.
        rejectNonIdNodeId(ctx);
        // Reject @asFacet misuse. Sibling soundness reduction to rejectNonIdNodeId, and for
        // the same reason a raw-schema pass: the promoter's facet walk skips malformed applications
        // (they produce no FacetSpec, no trace on the classified model), so the misuse is only
        // visible on the SDL directive surface. Registers build-time diagnostics the validator
        // drains, demoting nothing.
        rejectFacetMisuse(ctx);
        // Hash-suffix dedup: walk every WithErrorChannel field and apply the collision-suffix
        // rule to ErrorChannel.mappingsConstantName so the resolved name lands on the carrier
        // before the emitter runs. Pass-through for the common case (every payload class has at
        // most one channel shape).
        var dedupedFields = MappingsConstantNameDedup.apply(ctx.fieldRegistry.entries());
        Map<String, EntityResolution> entitiesByType =
            EntityResolutionBuilder.build(ctx.typeRegistry, dedupedFields, rebuiltAssembled,
                ctx::addWarning, ctx::addDiagnostic);
        // The ancestor-product arrival fold, computed once over the assembled (pre-connection-
        // promotion) SDL. Pure SDL fact (list-ness needs no classification), independent of walk order,
        // so it is computed here and threaded onto the schema for OutputField.source(Arrival) consumers
        // to read through GraphitronSchema.sourceOf. No generator reads it (emit stays leaf-identity
        // dispatch), so populating OnlyChild changes no generated code.
        var arrivals = ArrivalIndex.compute(ctx.schema).byName();
        var model = new GraphitronSchema(
            ctx.types, Collections.unmodifiableMap(dedupedFields), entitiesByType, ctx.warnings(),
            ctx.diagnostics(), arrivals);
        return new BuildResult(model, rebuiltAssembled);
    }

    /**
     * The single classify-and-emit walk's visitor. Fired by
     * {@link SchemaReachability#walk} once per reached composite (the schema traverser dispatches
     * {@code enter} exactly once per node identity), it classifies each composite type on enter
     * ({@link TypeBuilder#classifyAndRegister}) and, for object types, classifies that object's fields in
     * the same visit ({@link #classifyFieldsOfObject}). Because field classification is registry-read-free,
     * classifying a type and its fields together is order-independent: a field's
     * output target is a not-yet-visited child, resolved through the look-ahead / fixed-point indices, not
     * a registry lookup.
     *
     * <p>The object's own verdict is registered before its fields classify, so the field work reads the
     * object's own registry entry (its parent verdict, set here or, for a producer-backed carrier /
     * connection arm, by the discovering parent field before this visit) — never a sibling's or a
     * not-yet-determined verdict.
     *
     * <p>{@code fieldBuilder} is {@code null} for the types-only test seam
     * ({@link #buildContextForTests}); then only type classification runs and no fields are classified.
     */
    private static final class ClassifyingVisitor extends GraphQLTypeVisitorStub {
        private final BuildContext ctx;
        private final TypeBuilder typeBuilder;
        private final FieldBuilder fieldBuilder;
        private final List<ConnectionPromoter.CarrierRewrite> connectionRewrites;
        private final Set<String> synthesisedConnectionNames;

        ClassifyingVisitor(
                BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder,
                List<ConnectionPromoter.CarrierRewrite> connectionRewrites,
                Set<String> synthesisedConnectionNames) {
            this.ctx = ctx;
            this.typeBuilder = typeBuilder;
            this.fieldBuilder = fieldBuilder;
            this.connectionRewrites = connectionRewrites;
            this.synthesisedConnectionNames = synthesisedConnectionNames;
        }

        @Override
        public TraversalControl visitGraphQLObjectType(
                GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
            typeBuilder.classifyAndRegister(node);
            if (fieldBuilder != null) {
                classifyFieldsOfObject(ctx, typeBuilder, fieldBuilder, node,
                    connectionRewrites, synthesisedConnectionNames);
            }
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitGraphQLInterfaceType(
                GraphQLInterfaceType node, TraverserContext<GraphQLSchemaElement> context) {
            typeBuilder.classifyAndRegister(node);
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitGraphQLUnionType(
                GraphQLUnionType node, TraverserContext<GraphQLSchemaElement> context) {
            typeBuilder.classifyAndRegister(node);
            return TraversalControl.CONTINUE;
        }
    }

    /**
     * Classifies every field of one SDL object type, invoked from
     * {@link ClassifyingVisitor} as the single walk enters each reached object (the compensating sweep
     * over unreached objects was removed, so an unreached object's fields are not classified). A
     * directiveless nesting target (structurally decided, see
     * {@link TypeBuilder#isDirectivelessNestingTarget}) is skipped: its fields are resolved through the
     * {@code NestingField} that embeds it, whose {@code NestingType} this method registers at the edge
     * ({@link #registerNestingTypesIn}), not standalone here.
     */
    private static void classifyFieldsOfObject(
            BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder, GraphQLObjectType objType,
            List<ConnectionPromoter.CarrierRewrite> connectionRewrites, Set<String> synthesisedConnectionNames) {
        // Connection synthesis is a byproduct of visiting each field, run before any of
        // the classification early-returns below so it fires for every field exactly as the retired
        // all-types promotion pass did (including fields on directiveless parents whose standalone
        // classification is skipped). register owns dedup and the cross-carrier @tag union.
        for (var fieldDef : objType.getFieldDefinitions()) {
            ConnectionPromoter.synthesiseForField(
                ctx, objType, fieldDef, connectionRewrites, synthesisedConnectionNames);
        }
        var parentType = ctx.types.get(objType.getName());
        // A directiveless nesting target has its fields resolved through the embedding NestingField, not
        // standalone here. This is decided structurally
        // (TypeBuilder.isDirectivelessNestingTarget), not by reading parentType == null: once NestingType
        // registration folds onto the embedding edge, this object's own registry slot may already hold the
        // NestingType a sibling edge produced, so a null check would observe sibling state. The structural
        // verdict is sibling-independent and reproduces the old null signal. A structural Connection / Edge
        // / PageInfo type is also directiveless in the SDL: the connection synthesis above just registered
        // it as a connection arm, but its fields are emitted by the connection emitter, never classified
        // standalone, so skip it too so folding synthesis into the walk does not start classifying
        // connection-internal fields.
        if (typeBuilder.isDirectivelessNestingTarget(objType.getName())
                || parentType instanceof ConnectionType
                || parentType instanceof EdgeType
                || parentType instanceof PageInfoType
                || parentType instanceof GraphitronType.FacetsType
                || parentType instanceof GraphitronType.FacetValueType) {
            return;
        }
        // Structural carrier-shape detection (scanStructuralDmlPayload)
        // routes payload-returning DML through the unified path. For payloads with a
        // producer binding (DmlEmitted for non-DELETE, or ServiceEmitted), the per-type
        // pass runs against the binding to construct the data-field permit. For orphan
        // carriers (carrier-shaped payload that no producer mutation returns)
        // and for DELETE DML carriers (data-field permit constructed at the @mutation
        // classifier from the DmlElementKind dispatch), the data field stays
        // unregistered at this site; graphql-java's never-traverse-unproduced-fields
        // guarantee makes the missing entry structurally safe. Errors-shaped fields
        // still classify through the normal per-field classifier so ErrorsField is
        // materialised independent of the producer binding.
        var nDml = typeBuilder.dmlEmittedBinding(objType.getName());
        var nService = typeBuilder.serviceEmittedBinding(objType.getName());
        boolean skipForUnifiedPath =
            (nDml.isPresent() && nDml.get().kind() != no.sikt.graphitron.rewrite.model.DmlKind.DELETE)
            || nService.isPresent();
        var scan = ctx.scanStructuralDmlPayload(objType.getName());
        // A DELETE carrier's data field is owned by the @mutation DELETE classifier
        // (SingleRecordIdFieldFromReturning, set via
        // reclassify). The carrier is bound to a JooqTableRecordType, so the standard
        // per-type pass below would classify that same data field a second time and collide;
        // skip it here, classifying only the errors field. (Orphan carriers are no longer
        // promoted, so they fall through to the NestingType guard below.)
        if (scan instanceof BuildContext.DmlPayloadScan.Admit
                && nDml.isPresent() && nDml.get().kind() == no.sikt.graphitron.rewrite.model.DmlKind.DELETE) {
            Class<?> parentBackingClass0 = typeBuilder.recordBackingClasses().get(objType.getName());
            for (var f : objType.getFieldDefinitions()) {
                if (ctx.detectErrorsFieldShape(f) != null) {
                    ctx.fieldRegistry.classify(
                        FieldCoordinates.coordinates(objType.getName(), f.getName()),
                        fieldBuilder.classifyField(f, objType.getName(), parentType, parentBackingClass0));
                }
            }
            return;
        }
        // A directiveless object the type pass left unclassified (the structural skip above) has its
        // fields resolved through the NestingField that embeds it, not standalone here.
        Class<?> parentBackingClass = typeBuilder.recordBackingClasses().get(objType.getName());
        objType.getFieldDefinitions().forEach(fieldDef -> {
            // Land the producer-backed single-record carrier verdict at the producing
            // edge: the field whose return type is the carrier registers its JooqTableRecordType here,
            // before its own classification, replacing the deleted post-type-pass
            // promoteSingleRecordPayloads SDL scan. The binding (carrierTableBinding) is computed from
            // the producer (DmlEmitted / ServiceEmitted) and the structural carrier scan, never from the
            // in-progress type registry, so it is order-independent; the contains guard keeps the
            // register idempotent across the several edges (producing field, the carrier's own later
            // visit) that observe the same carrier. Registering before classifyField is what retires the
            // classify-reads-classify dependency: the field's resolveReturnType then sees the carrier as
            // a ResultType and routes through the record-backed mutation path, and the carrier's own
            // later visit reads the verdict as its parentType.
            registerProducerBackedCarrier(ctx, typeBuilder, BuildContext.baseTypeName(fieldDef));
            var classified = fieldBuilder.classifyField(fieldDef, objType.getName(), parentType, parentBackingClass);
            ctx.fieldRegistry.classify(
                FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()), classified);
            // Fold NestingType registration onto the embedding edge: a NestingField built
            // here establishes its target (and any deeper nested targets) as a NestingType right now,
            // recursing into nested fields, rather than in a post-walk sweep.
            registerNestingTypesIn(ctx, classified);
        });
    }

    /**
 * Assign {@link no.sikt.graphitron.rewrite.model.GraphitronType.NestingType} to the SDL object
     * type a {@code NestingField} embeds, recursing into nested {@code NestingField}s. The type pass
     * leaves a directiveless object unclassified (it cannot know what it is); a {@code NestingField} built
     * at the embedding edge is the only thing that establishes it as a nesting projection of a table-backed
     * parent, so the invariant {@code NestingType} ⟺ {@code ∃ NestingField} holds by construction.
     *
     * <p>Called per classified field at the embedding edge (from
     * {@link #classifyFieldsOfObject}), not in a post-walk sweep over the whole field registry. The
     * {@code contains} guard keeps the registration idempotent across the several edges that may embed the
     * same target; it dedups an already-produced {@code NestingType}, it does not gate the nesting verdict
     * (that is decided structurally and registry-free at each edge).
     *
     * <p>A directiveless object that no {@code NestingField} embeds is left unclassified (absent from
     * {@code schema.types()}). It is an orphan: the field that returns it already classifies as
     * {@code UnclassifiedField}, so the rejection surfaces at the field edge.
     */
    private static void registerNestingTypesIn(BuildContext ctx, no.sikt.graphitron.rewrite.model.GraphitronField field) {
        if (!(field instanceof no.sikt.graphitron.rewrite.model.ChildField.NestingField nf)) return;
        String name = nf.returnType().returnTypeName();
        if (!ctx.typeRegistry.contains(name)) {
            var objType = ctx.schema.getObjectType(name);
            ctx.typeRegistry.register(name, new no.sikt.graphitron.rewrite.model.GraphitronType.NestingType(
                name, BuildContext.locationOf(objType), objType));
        }
        nf.nestedFields().forEach(child -> registerNestingTypesIn(ctx, child));
    }

    /**
     * Registers the {@link GraphitronType.ResultType} verdict for a producer-backed
     * carrier at the producing edge (the field returning it), replacing the deleted post-type-pass
     * {@code TypeBuilder.promoteSingleRecordPayloads} SDL scan. {@code name} is the field's unwrapped
     * return-type name; the verdict fires only when {@link TypeBuilder#carrierVerdict} resolves a
     * producer-backed carrier (a DML {@code RETURNING} / single-level {@code @service} {@code @table}
     * carrier → {@link GraphitronType.JooqTableRecordType}, or a two-level {@code @service}
     * record-composite carrier → a class-backed {@link GraphitronType.ResultType}), which is
     * registry-free, so registering it at whichever edge first returns the carrier is
     * order-independent. The {@code contains} guard keeps it idempotent: the carrier's own later visit,
     * and any sibling field returning the same carrier, see the verdict already present. A
     * directiveless object that no producer returns gets {@code null} here and stays a nesting /
     * orphan target, decided at its embedding edge ({@link #registerNestingTypesIn}).
     */
    private static void registerProducerBackedCarrier(BuildContext ctx, TypeBuilder typeBuilder, String name) {
        if (ctx.typeRegistry.contains(name)) return;
        var verdict = typeBuilder.carrierVerdict(name);
        if (verdict == null) return;
        ctx.typeRegistry.register(name, verdict);
    }

    /**
     * Resolves the connection names the walk flagged as synthesised (absent from the
     * assembled schema) to their final graphql-java forms on the registry, in walk-visit order. The
     * forms are read after the walk so any cross-carrier {@code @tag} union {@code register} applied
     * has settled; {@link ConnectionPromoter#rebuildAssembledForConnections} adds exactly these via
     * {@code additionalType}. This is a keyed lookup of the walk's own output, not a sweep of
     * {@code ctx.types}, so there remains one producer of the synthesised-type set.
     */
    private static List<GraphQLObjectType> resolveSynthesisedConnectionTypes(
            BuildContext ctx, Set<String> synthesisedConnectionNames) {
        var forms = new ArrayList<GraphQLObjectType>(synthesisedConnectionNames.size());
        for (var name : synthesisedConnectionNames) {
            GraphQLObjectType form = switch (ctx.typeRegistry.get(name)) {
                case ConnectionType ct -> ct.schemaType();
                case EdgeType et -> et.schemaType();
                case PageInfoType pi -> pi.schemaType();
                case GraphitronType.FacetsType ft -> ft.schemaType();
                case GraphitronType.FacetValueType fvt -> fvt.schemaType();
                case null, default -> null;
            };
            if (form != null) forms.add(form);
        }
        return List.copyOf(forms);
    }

    /**
 * Model-level soundness backstop: no classified field may reference a type the model
     * dropped. A classified {@link OutputField} whose SDL return element is an Object type with
     * no registry entry would emit {@code typeRef("X")} while the type itself is never emitted;
     * graphql-java assembly then fails with {@code AssertException: type X not found in schema}
     * — an invalid schema discovered by the consumer at runtime instead of an author error at
     * build time. SDL Object types are the only kind the type pass can leave entirely absent
     * (directiveless objects with no producer binding, no carrier promotion, and no
     * {@code NestingField} embedding); every other SDL kind either registers or demotes to a
     * registered {@link UnclassifiedType} that carries its own diagnostic.
     *
     * <p>Registers a build-time {@link ValidationError} on the schema's diagnostic
     * channel instead of reclassifying the field to {@link GraphitronField.UnclassifiedField}. The
     * field keeps its real {@link OutputField} verdict (so a verdict read after the walk equals the
     * one classification produced); the build still fails through the validator's drain of the
     * channel. The former demotion's second job — removing the field from emission — is redundant
     * because the validator throws before the emitter runs and nothing between this pass and the
     * validator reads the field verdict ({@code rebuildAssembledForConnections} works off the SDL
     * assembled schema plus carrier rewrites). This is the
     * shape-agnostic closure of the per-shape classifier guards (the {@code @service}
     * orphan-carrier guard rejects recognized carrier shapes with richer, shape-specific
     * guidance before this pass runs; anything that slips past every classifier-level guard —
     * errors-only payloads, scan-{@code Reject} shapes, future holes — lands here). Runs after the
     * field-first walk has registered every nesting projection at its embedding edge
     * ({@link #registerNestingTypesIn}) and after that walk's
     * connection synthesis ({@code ConnectionPromoter.synthesiseForField} registers the Connection /
     * Edge / PageInfo types as a byproduct of visiting each carrier; running earlier would demote
     * every Connection-returning field).
     */
    private static void rejectDanglingTypeReferences(BuildContext ctx) {
        for (var entry : ctx.fieldRegistry.entries().entrySet()) {
            if (!(entry.getValue() instanceof OutputField existing)) continue;
            String sdlReturn = sdlReturnTypeName(ctx.schema, entry.getKey());
            if (sdlReturn == null) continue;
            if (ctx.typeRegistry.contains(sdlReturn)) continue;
            // Register a build-time diagnostic instead of reclassifying the field to
            // UnclassifiedField. The field keeps its real OutputField verdict; the demotion's second
            // job (removing the field from emission) is redundant because the validator throws before
            // the emitter runs (the global gate), and nothing between here and the validator reads the
            // demoted verdict: rebuildAssembledForConnections works off the SDL assembled schema plus
            // carrier rewrites, not the field registry. The shared ValidationError.forField factory
            // applies the same "Field '<qname>': " prefix the validator's validateUnclassifiedField
            // pass did, so the error stream is byte-identical to the former demotion by construction.
            // When the binding walk recorded a gated accessor near-miss for this type (a parent
            // accessor name-matched but failed the arity / boolean-is / field-fallback-with-arguments
            // gate), name the gate rather than the generic "did not classify" cascade — the walk is the
            // one place that knows why the accessor did not match.
            String gateNote = ctx.typeBuilder == null ? "" : ctx.typeBuilder.accessorGateReason(sdlReturn)
                .map(g -> " The accessor that could have grounded '" + sdlReturn + "' was rejected: "
                    + g.reason() + " (via " + g.parentSdlType() + "." + g.fieldName() + ").")
                .orElse("");
            ctx.addDiagnostic(ValidationError.forField(
                existing.qualifiedName(),
                Rejection.structural(
                    "field '" + existing.parentTypeName() + "." + existing.name()
                    + "' returns SDL Object type '" + sdlReturn + "', which did not classify "
                    + "into the model (no @table or record-backed binding, no producer-backed carrier "
                    + "promotion, not embedded as a nesting projection of a table-backed "
                    + "parent). Emitting the field would reference a type absent from the "
                    + "generated schema and assembly would fail with \"type " + sdlReturn
                    + " not found in schema\". Give '" + sdlReturn + "' a binding (e.g. a "
                    + "@table data field or an [ID] @nodeId(typeName:) data field matching a "
                    + "producer's returned record), or remove the field." + gateNote),
                existing.location()));
        }
    }

    /**
     * Detects, but no longer enforces, that every {@link OutputField} producer
     * reaching the same SDL return-type name agrees on its {@link DomainReturnType} sealed arm.
     * Disagreement means the producers put structurally different Java values at
     * {@code env.getSource()} for the SDL return type's child datafetchers; the generator commits to
     * one source-Java-type per child-field coord at emit time and does not branch on runtime source
     * type, so a multi-producer disagreement would feed a datafetcher generated against the other
     * producer's record shape.
     *
     * <p>Registers one {@link Rejection.AuthorError.MultiProducerDomainTypeDisagreement} per conflict
     * group on the schema's diagnostic channel ({@code ctx.addDiagnostic}), each naming every
     * participant; the validator drains the channel into compiler-style errors that fail the build
     * (the reclassify-to-{@link GraphitronField.UnclassifiedField} post-pass that previously enforced
     * this here was retired, moving enforcement to the validator in the same commit so no gap opens;
     * the dedicated {@code domainReturnTypeConflicts} carrier was later folded into the single
     * diagnostic channel). Runs on the pre-promotion, pre-dangling field registry against the
     * assembled-schema SDL-Object axis, so the conflict set is byte-identical to the retired post-pass;
     * the data field on the conflict payload (if any) stays classified as-is per the design fork
     * (a), since the validator halts the build before any generated code runs. The diagnostic
     * coordinate ({@code "<schema>"}) and empty {@link SourceLocation} reproduce what the former
     * {@code validateUniformDomainReturnType} drain emitted, so the error stream is byte-identical.
     */
    private static void collectDomainReturnTypeConflicts(BuildContext ctx) {
        Map<String, List<FieldCoordinates>> bySdlReturnType = new LinkedHashMap<>();
        for (var entry : ctx.fieldRegistry.entries().entrySet()) {
            if (!(entry.getValue() instanceof OutputField of)) continue;
            String sdlReturn = sdlReturnTypeName(ctx.schema, entry.getKey());
            if (sdlReturn == null) continue;
            bySdlReturnType.computeIfAbsent(sdlReturn, k -> new ArrayList<>()).add(entry.getKey());
        }
        for (var group : bySdlReturnType.entrySet()) {
            String sdlReturn = group.getKey();
            List<FieldCoordinates> coords = group.getValue();
            if (coords.size() < 2) continue;

            java.util.Map<DomainReturnType, List<FieldCoordinates>> byDomain = new LinkedHashMap<>();
            for (var coord : coords) {
                var of = (OutputField) ctx.fieldRegistry.get(coord);
                byDomain.computeIfAbsent(of.domainReturnType(), k -> new ArrayList<>()).add(coord);
            }
            if (byDomain.size() < 2) continue;

            List<Rejection.AuthorError.MultiProducerDomainTypeDisagreement.Participant> participants =
                new ArrayList<>(coords.size());
            for (var coord : coords) {
                var of = (OutputField) ctx.fieldRegistry.get(coord);
                participants.add(new Rejection.AuthorError.MultiProducerDomainTypeDisagreement.Participant(
                    of.parentTypeName(), of.name(), of.domainReturnType()));
            }
            ctx.addDiagnostic(new ValidationError("<schema>",
                new Rejection.AuthorError.MultiProducerDomainTypeDisagreement(sdlReturn, participants),
                SourceLocation.EMPTY));
        }
    }

    /**
     * Resolves the SDL return-type name (with list / non-null wrappers stripped) for a field coord
     * <em>only</em> when the unwrapped return is an SDL Object type. Scalars and enums are leaves
     * (no child datafetchers downstream of {@code env.getSource()}); interfaces and unions resolve
     * to a concrete implementation type at runtime, and the per-implementation classification
     * carries its own producer-agreement guarantees. The intent is the SDL-Object axis:
     * "all OutputField producers reaching SDL Object {@code P} agree on the Java type at
     * {@code env.getSource()} for {@code P}'s child datafetchers."
     */
    private static String sdlReturnTypeName(GraphQLSchema schema, FieldCoordinates coord) {
        var parent = schema.getObjectType(coord.getTypeName());
        if (parent == null) return null;
        var fieldDef = parent.getFieldDefinition(coord.getFieldName());
        if (fieldDef == null) return null;
        var unwrapped = graphql.schema.GraphQLTypeUtil.unwrapAll(fieldDef.getType());
        if (!(unwrapped instanceof graphql.schema.GraphQLObjectType obj)) return null;
        return obj.getName();
    }

    /**
 * Rejects every type whose name collides case-insensitively with another emit-producing
     * type. Graphitron emits one Java file per type-name stem, and GraphQL identifiers are
     * case-sensitive ({@code type Foo} and {@code type foo} parse as distinct types); on
     * case-insensitive filesystems (APFS, NTFS) the two map to the same filename and clobber each
     * other. Rather than introduce a content-suffix collision strategy, we reject at build time:
     * case-only collisions are an API-design smell, and graphitron pushes back rather than papering
     * over.
     *
     * <p>One case-folded pass over {@code ctx.typeRegistry.entries()}, skipping variants that do
     * not implement {@link EmitsPerTypeFile} ({@link
     * no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType} and {@link UnclassifiedType}
     * today). For each collision group of two or more members, every member's typed
     * {@link Rejection.InvalidSchema.CaseFoldCollision} rejection (naming the full group plus the
     * member's classifier-arm {@link Origin}) is registered as a build-time {@link ValidationError}
     * on the diagnostic channel rather than demoting the registry entry; the colliding types keep
     * their classified verdict and the validator surfaces one error per member by draining the
     * channel. The coordinate, prefixed message and location are byte-identical to what the former
     * demotion's {@code validateUnclassifiedType} projection produced.
     *
     * <p>Demote-all (rather than first-wins) because there's no logical winner between two
     * SDL-declared types: silently picking one would tilt the schema's public surface against the
     * author. Synthesised Connection / Edge / PageInfo arms specialise the message to hint at the
     * {@code @asConnection(connectionName:)} fix.
     *
     * <p>{@link Locale#ROOT} for the fold; GraphQL identifiers are ASCII-only per the spec rule
     * {@code [_A-Za-z][_0-9A-Za-z]*} (no non-ASCII letters, no locale-dependent folding hazards).
     */
    private static void rejectCaseInsensitiveTypeCollisions(BuildContext ctx) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (var entry : ctx.typeRegistry.entries().entrySet()) {
            if (!(entry.getValue() instanceof EmitsPerTypeFile)) continue;
            String folded = entry.getKey().toLowerCase(Locale.ROOT);
            groups.computeIfAbsent(folded, k -> new ArrayList<>()).add(entry.getKey());
        }
        for (var group : groups.values()) {
            if (group.size() < 2) continue;
            for (String name : group) {
                GraphitronType existing = ctx.typeRegistry.get(name);
                Origin origin = switch (existing) {
                    case ConnectionType ignored -> Origin.SYNTH_CONNECTION;
                    case EdgeType ignored -> Origin.SYNTH_EDGE;
                    case PageInfoType ignored -> Origin.SYNTH_PAGE_INFO;
                    default -> Origin.SDL;
                };
                // Register a diagnostic instead of demoting the colliding type. The
                // shared ValidationError.forType factory applies the same "Type '<name>': " prefix the
                // validator's validateUnclassifiedType pass did, so the error stream is byte-identical
                // to the former UnclassifiedType demotion by construction.
                ctx.addDiagnostic(ValidationError.forType(name,
                    Rejection.caseFoldCollision(group, origin), existing.location()));
            }
        }
    }

    /**
 * The actionable tier of the {@code @table}-on-input deprecation signal. Walks every
     * {@code INPUT_OBJECT} that <em>explicitly declares</em> {@code @table} (read off
     * {@code ctx.schema} applied directives, like {@link #rejectNonIdNodeId} — the consumer-derived
     * table branch of {@link TypeBuilder#buildInputType} carries no author-written directive and is
     * deliberately not flagged) and emits a non-fatal {@link BuildWarning.NoRule} advisory per usage,
     * except those in the encoded-ID / scalar-return INSERT/UPSERT carve-out
     * ({@link #encodedWriteTargetInputTypes}).
     *
     * <p>The message names no roadmap ID (D2): it gives the replacement instruction (the write target
     * is derived from the consuming mutation field's resolved table) rather than pointing at the
     * internal removal owner. Unconditional and not suppressible (D4): a switchable nudge defeats the
     * "stop adding new usages" purpose, and there is no build-breakage to suppress. The plain
     * {@link BuildWarning.NoRule} arm (not {@link BuildWarning.LintFinding}) is deliberate (D1): this
     * is a deprecation announcement, not a lint-engine finding, and stays unconditional. The live
     * precedent for {@code NoRule} is the federation compound-key advisory in
     * {@link EntityResolutionBuilder}.
     */
    private static void emitTableOnInputDeprecationWarnings(BuildContext ctx) {
        Set<String> carveOut = encodedWriteTargetInputTypes(ctx);
        // DELETE now has a field-relative write-target path (@mutation(table:)), so its
        // inputs are no longer carved out of the warning; instead the warning names that replacement
        // explicitly. The commit-1 suppression set is gone (additive-then-cutover): the warning fires
        // on DELETE inputs too, driving migration.
        Set<String> deleteConsumed = deleteConsumedInputTypes(ctx);
        for (var type : ctx.schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLInputObjectType input)) continue;
            if (!input.hasAppliedDirective(DIR_TABLE)) continue;
            if (carveOut.contains(input.getName())) continue;
            String replacement = deleteConsumed.contains(input.getName())
                ? " For the consuming `@mutation(typeName: DELETE)` field, set the write target with "
                  + "`@mutation(table: \"…\")` on the field, not with `@table` on the input type."
                : "";
            ctx.addWarning(new BuildWarning.NoRule(
                "`@table` on input type '" + input.getName() + "' is deprecated and will be removed "
                + "in a future release; the write target is derived from the consuming mutation "
                + "field's resolved table. Remove `@table` from this input." + replacement,
                locationOf(input)));
        }
    }

    /**
 * The SDL input-type names in the encoded-ID / scalar-return INSERT/UPSERT carve-out (D3).
     * These are the {@link MutationField.MutationInsertTableField} /
     * {@link MutationField.MutationUpsertTableField} whose {@link MutationField.DmlTableField#returnExpression()}
     * is an {@code Encoded*} arm: their return type carries no {@code @table}, so there is nothing for
     * the field-relative derivation to collapse to, and the input's {@code @table} is currently the
     * only signal naming the write target. Every part is pre-resolved on the classified model (the
     * encoded-vs-projected axis is a settled {@link DmlReturnExpression} arm; the leaf names its input
     * type), so no {@code lookAheadVerdict} or reflection is needed.
     *
     * <p>Conservative type-level rule (D3): an input reused by both an encoded INSERT/UPSERT and a
     * projected consumer is suppressed (added to the set once <em>any</em> consumer is an encoded
     * INSERT/UPSERT), because a false fire tells an author to delete the only signal naming their
     * write target and breaks their build, whereas a false suppress costs one extra release carrying a
     * directive. Per-{@code (input, consumer)} precision is a future axis.
     *
     * <p>This set is retired once the write target is field-relative: encoded INSERT/UPSERT inputs no
     * longer need {@code @table} and this set empties, letting the warning fire on them too.
     */
    private static Set<String> encodedWriteTargetInputTypes(BuildContext ctx) {
        Set<String> carveOut = new LinkedHashSet<>();
        for (var field : ctx.fieldRegistry.entries().values()) {
            switch (field) {
                case MutationField.MutationInsertTableField f -> {
                    if (isEncoded(f.returnExpression())) carveOut.add(f.tableInputArg().typeName());
                }
                case MutationField.MutationUpsertTableField f -> {
                    if (isEncoded(f.returnExpression())) carveOut.add(f.tableInputArg().typeName());
                }
                default -> { /* only the two @table-carrying direct-return DML leaves are in scope */ }
            }
        }
        return carveOut;
    }

    private static boolean isEncoded(DmlReturnExpression expr) {
        return expr instanceof DmlReturnExpression.EncodedSingle
            || expr instanceof DmlReturnExpression.EncodedList;
    }

    /**
 * The SDL input-type names consumed by a {@code @mutation(typeName: DELETE)} field. Drives
     * the DELETE-specific replacement clause on the {@code @table}-on-input deprecation warning (name
     * the write target with {@code @mutation(table:)} on the field). Earlier, this same computation
     * suppressed the warning on DELETE inputs, before the field-relative path existed; it was
     * repurposed from suppression to wording, leaving no dead set behind.
     *
     * <p>The three DELETE leaves carry an {@link no.sikt.graphitron.rewrite.model.InputArgRef} whose
     * accessor is {@code inputTypeName()} (not the {@code tableInputArg().typeName()} the encoded
     * INSERT/UPSERT carve-out reads); the record-carrier DML leaves are INSERT/UPSERT-only by compact
     * constructor, so these three arms are exhaustive over DELETE.
     */
    private static Set<String> deleteConsumedInputTypes(BuildContext ctx) {
        Set<String> carveOut = new LinkedHashSet<>();
        for (var field : ctx.fieldRegistry.entries().values()) {
            switch (field) {
                case MutationField.MutationDeleteTableField f -> carveOut.add(f.inputArg().inputTypeName());
                case MutationField.MutationDeletePayloadField f -> carveOut.add(f.inputArg().inputTypeName());
                case MutationField.MutationBulkDeletePayloadField f -> carveOut.add(f.inputArg().inputTypeName());
                default -> { /* only the three DELETE leaves carry an InputArgRef write target */ }
            }
        }
        return carveOut;
    }

    /**
 * Rejects every {@code @nodeId} application whose coordinate does not have an unwrapped
     * SDL type of {@code ID}. The SDL directive permits {@code @nodeId} on {@code FIELD_DEFINITION |
     * INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION} with no {@code ID} restriction, but every
     * decode/encode arm in the generator is gated on {@code "ID".equals(...)}; on a non-{@code ID}
     * coordinate the directive is silently dropped and the raw base64 wire String is bound undecoded,
     * a green build with a production failure.
     *
     * <p>Unlike its sibling reductions ({@link #rejectCaseInsensitiveTypeCollisions},
     * {@link #collectDomainReturnTypeConflicts}), this pass reads {@code ctx.schema} applied
     * directives rather than the classified registries: a dropped {@code @nodeId} leaves <em>no
     * trace</em> on the classified field (that absence is the bug), so there is nothing in the
     * registry to walk. {@link BuildContext} is a permitted raw-schema holder, so reading
     * {@code ctx.schema} here is sound. Registers a build-time diagnostic on the
     * shared channel that {@link GraphitronSchemaValidator} drains; it demotes no verdict.
     *
     * <p>The three {@code on} locations map onto the schema as: object/interface field definitions
     * ({@code FIELD_DEFINITION}, the encode side), their arguments ({@code ARGUMENT_DEFINITION}, a
     * decode side), and input-object fields ({@code INPUT_FIELD_DEFINITION}, the other decode side).
     */
    private static void rejectNonIdNodeId(BuildContext ctx) {
        for (var type : ctx.schema.getAllTypesAsList()) {
            switch (type) {
                case GraphQLObjectType obj -> {
                    for (var field : obj.getFieldDefinitions()) {
                        checkNodeIdFieldAndArguments(ctx, obj.getName(), field);
                    }
                }
                case GraphQLInterfaceType iface -> {
                    for (var field : iface.getFieldDefinitions()) {
                        checkNodeIdFieldAndArguments(ctx, iface.getName(), field);
                    }
                }
                case GraphQLInputObjectType input -> {
                    for (var field : input.getFieldDefinitions()) {
                        if (!field.hasAppliedDirective(DIR_NODE_ID)) continue;
                        String unwrapped = unwrappedTypeName(field.getType());
                        if ("ID".equals(unwrapped)) continue;
                        ctx.addDiagnostic(ValidationError.forField(
                            input.getName() + "." + field.getName(),
                            Rejection.invalidSchema(nonIdNodeIdMessage(unwrapped)),
                            locationOf(field)));
                    }
                }
                default -> { /* scalars, enums, unions carry no @nodeId-bearing coordinate */ }
            }
        }
    }

    /**
     * Checks a single object/interface field for a non-{@code ID} {@code @nodeId} (the encode side,
     * {@code FIELD_DEFINITION}) and each of its arguments (a decode side,
     * {@code ARGUMENT_DEFINITION}). Argument rejections attach to the parent field's qualified name
     * (the coordinate {@code UnclassifiedArg} surfaces on) and name the argument in the message.
     */
    private static void checkNodeIdFieldAndArguments(BuildContext ctx, String parentTypeName, GraphQLFieldDefinition field) {
        if (field.hasAppliedDirective(DIR_NODE_ID)) {
            String unwrapped = unwrappedTypeName(field.getType());
            if (!"ID".equals(unwrapped)) {
                ctx.addDiagnostic(ValidationError.forField(
                    parentTypeName + "." + field.getName(),
                    Rejection.invalidSchema(nonIdNodeIdMessage(unwrapped)),
                    locationOf(field)));
            }
        }
        for (var arg : field.getArguments()) {
            if (!arg.hasAppliedDirective(DIR_NODE_ID)) continue;
            String unwrapped = unwrappedTypeName(arg.getType());
            if ("ID".equals(unwrapped)) continue;
            ctx.addDiagnostic(ValidationError.forField(
                parentTypeName + "." + field.getName(),
                Rejection.invalidSchema("argument '" + arg.getName() + "': " + nonIdNodeIdMessage(unwrapped)),
                argLocation(arg)));
        }
    }

    /**
 * Rejects every misused {@code @asFacet} application. The rejection split follows a
     * definition-keyed / use-keyed axis: the binding checks (a facet must be a plain
     * {@code @field}-bound scalar/enum column; {@code @reference} / {@code @condition} /
     * {@code @nodeId} bindings and {@code ID} fields are v1-unsupported) are authored-directive
     * facts at the input type's member coordinate, while the reachability check (the enclosing
     * input type must be a filter input on at least one {@code @asConnection} field, else the
     * {@code facets} expansion would be dead schema) is a derived join over the consuming
     * coordinates. An input type shared by connection and non-connection consumers is fine:
     * {@code @asFacet} surfaces facets at the connection use sites and is inert at the others.
     *
     * <p>Rejecting {@code ID} fields outright (rather than only {@code @nodeId} co-occurrence)
     * also closes the node-reference synthesis shim: a bare {@code ID} field whose column hits the
     * qualifier map classifies as a reference carrier with no directive trace, which the v1
     * direct-column facet emitter cannot serve.
     *
     * <p>Like {@link #rejectNonIdNodeId}, this reads {@code ctx.schema} applied directives rather
     * than the classified registries: the promoter's facet walk skips malformed applications, so
     * they leave no trace on the classified model. Registers build-time diagnostics on the shared
     * channel that {@link GraphitronSchemaValidator} drains; it demotes no verdict.
     */
    private static void rejectFacetMisuse(BuildContext ctx) {
        // Per input type: consumed by any @asConnection field at all; (Phase 4) consumed by one
        // carrying the deprecated connectionName: override (the facet emitters resolve a carrier's
        // ConnectionType through the derived ConnectionNaming.defaultConnectionName, which an
        // overridden name would silently miss); and consumed by a carrier the v1 facet emitter
        // does not serve (only root Query connections over a @table-backed object element bind a
        // facet plan — a faceted child/@splitQuery or interface/union carrier would expose a
        // facets field whose resolver always returns null, a green build with a dead surface).
        String queryRootName = ctx.schema.getQueryType() != null
            ? ctx.schema.getQueryType().getName() : null;
        var connectionFilterInputs = new LinkedHashSet<String>();
        var overriddenNameConsumers = new LinkedHashMap<String, String>();
        var unsupportedCarrierConsumers = new LinkedHashMap<String, String>();
        for (var type : ctx.schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType obj)) continue;
            for (var field : obj.getFieldDefinitions()) {
                if (!field.hasAppliedDirective(DIR_AS_CONNECTION)) continue;
                // Structural carriers (the SDL return type already names a declared Connection,
                // i.e. the return is not a bare list) never gain facets — the promoter's
                // structural arm synthesises nothing — so @asFacet is inert there per the spec's
                // "inert at the others" rule. They contribute neither reached nor unsupported:
                // an input consumed ONLY by structural carriers still lands in the dead-schema
                // rejection below, while sharing with a served root carrier stays legal.
                var unwrappedOnce = field.getType() instanceof graphql.schema.GraphQLNonNull nn
                    ? nn.getWrappedType() : field.getType();
                if (!(unwrappedOnce instanceof graphql.schema.GraphQLList)) continue;
                boolean overriddenName = argString(field, DIR_AS_CONNECTION, ARG_CONNECTION_NAME)
                    .filter(s -> !s.isEmpty()).isPresent();
                String carrierCoordinate = obj.getName() + "." + field.getName();
                String unsupportedReason = unsupportedFacetCarrierReason(
                    ctx, obj, field, queryRootName, carrierCoordinate);
                var facetNamesOnCarrier = new LinkedHashSet<String>();
                var duplicateFacetNames = new LinkedHashSet<String>();
                var filterParamNameCounts = new LinkedHashMap<String, Integer>();
                for (var arg : field.getArguments()) {
                    if (GraphQLTypeUtil.unwrapAll(arg.getType()) instanceof GraphQLInputObjectType in) {
                        connectionFilterInputs.add(in.getName());
                        if (overriddenName) {
                            overriddenNameConsumers.putIfAbsent(in.getName(), carrierCoordinate);
                        }
                        if (unsupportedReason != null) {
                            unsupportedCarrierConsumers.putIfAbsent(in.getName(), unsupportedReason);
                        }
                        // Facet labels must be unique per carrier: each becomes one field on the
                        // synthesised <ConnName>Facets object (the promoter keeps the first and
                        // drops repeats so synthesis cannot crash; this is the named rejection).
                        for (var inputField : in.getFieldDefinitions()) {
                            filterParamNameCounts.merge(inputField.getName(), 1, Integer::sum);
                            if (!inputField.hasAppliedDirective(DIR_AS_FACET)) continue;
                            if (!facetNamesOnCarrier.add(inputField.getName())) {
                                duplicateFacetNames.add(inputField.getName());
                                ctx.addDiagnostic(ValidationError.forField(carrierCoordinate,
                                    Rejection.invalidSchema("Field '" + carrierCoordinate
                                        + "': duplicate facet field name '" + inputField.getName()
                                        + "' across this connection's filter inputs; each facet "
                                        + "surfaces as one field on the synthesised facets object, "
                                        + "so names must be unique per carrier"),
                                    locationOf(field)));
                            }
                        }
                    } else {
                        filterParamNameCounts.merge(arg.getName(), 1, Integer::sum);
                    }
                }
                // A facet's name must be unique across the carrier's whole filter surface
                // (sibling input args' fields and top-level arguments): the facet fragments and
                // the generated condition method both key parameters by name, so a same-named
                // non-facet filter is irreducibly ambiguous at the emission boundary. (The
                // generated <Type>Conditions method cannot even declare two same-named
                // parameters today; that generic defect is tracked separately.)
                for (var facetName : facetNamesOnCarrier) {
                    if (duplicateFacetNames.contains(facetName)) continue;
                    if (filterParamNameCounts.getOrDefault(facetName, 0) > 1) {
                        ctx.addDiagnostic(ValidationError.forField(carrierCoordinate,
                            Rejection.invalidSchema("Field '" + carrierCoordinate + "': facet '"
                                + facetName + "' shares its name with another filter argument or "
                                + "filter-input field on this connection; v1 requires a facet's "
                                + "name to be unique across the carrier's whole filter surface. "
                                + "Rename one of them"),
                            locationOf(field)));
                    }
                }
            }
        }
        for (var type : ctx.schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLInputObjectType input)) continue;
            for (var field : input.getFieldDefinitions()) {
                if (!field.hasAppliedDirective(DIR_AS_FACET)) continue;
                String reason = facetMisuseReason(field, input.getName(), connectionFilterInputs,
                    overriddenNameConsumers, unsupportedCarrierConsumers);
                if (reason == null) continue;
                String coordinate = input.getName() + "." + field.getName();
                ctx.addDiagnostic(ValidationError.forField(coordinate,
                    Rejection.invalidSchema("Field '" + coordinate + "': " + reason),
                    locationOf(field)));
            }
        }
    }

    /**
 * Why a consuming {@code @asConnection} carrier is outside the v1 facet emitter's scope,
     * or {@code null} when it is served. The v1 facet plan is built only by the root Query
     * single-table connection fetcher; child ({@code @splitQuery}) carriers and interface/union
     * elements paginate through emitters that bind no plan, so their facets would silently
     * resolve to null.
     */
    private static String unsupportedFacetCarrierReason(BuildContext ctx, GraphQLObjectType parent,
            GraphQLFieldDefinition field, String queryRootName, String carrierCoordinate) {
        if (!parent.getName().equals(queryRootName)) {
            return "consumer '" + carrierCoordinate + "' is not a root Query field";
        }
        var element = GraphQLTypeUtil.unwrapAll(field.getType());
        if (!(element instanceof GraphQLObjectType elementObj)
                || !elementObj.hasAppliedDirective(DIR_TABLE)) {
            return "consumer '" + carrierCoordinate
                + "' does not return a @table-backed object element";
        }
        return null;
    }

    /**
     * The rejection reason for one {@code @asFacet} application, or {@code null} when it is well
     * formed. Definition-keyed binding checks first (actionable at the field), then the use-keyed
     * reachability checks.
     */
    private static String facetMisuseReason(
            graphql.schema.GraphQLInputObjectField field, String inputTypeName,
            Set<String> connectionFilterInputs, Map<String, String> overriddenNameConsumers,
            Map<String, String> unsupportedCarrierConsumers) {
        // Definition-keyed half: the shared predicate the promoter's synthesis walk also gates
        // on, so what the reduction rejects and what the promoter skips cannot drift.
        String definitionKeyed = FacetFieldValidation.definitionKeyedRejection(field);
        if (definitionKeyed != null) {
            return definitionKeyed;
        }
        if (!connectionFilterInputs.contains(inputTypeName)) {
            return "@asFacet has no effect: input type '" + inputTypeName + "' is not used as a "
                + "filter input on any @asConnection field, so the facets expansion would be dead "
                + "schema; move the directive to a connection filter input or remove it";
        }
        String overrideConsumer = overriddenNameConsumers.get(inputTypeName);
        if (overrideConsumer != null) {
            return "@asFacet cannot be combined with the deprecated @asConnection(connectionName:) "
                + "override (used by '" + overrideConsumer + "'); facets require the connection "
                + "field to own its derived-name Connection type. Drop the connectionName: override";
        }
        String unsupportedCarrier = unsupportedCarrierConsumers.get(inputTypeName);
        if (unsupportedCarrier != null) {
            return "v1 emits the facet aggregate only for root Query connections over a "
                + "@table-backed object element; " + unsupportedCarrier + ", so its facets field "
                + "would always resolve to null. Facets on child (@splitQuery) and interface/union "
                + "connections are a follow-up; remove @asFacet or move the connection to the root";
        }
        return null;
    }

    private static String unwrappedTypeName(GraphQLType type) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
    }

    private static SourceLocation argLocation(GraphQLArgument arg) {
        var def = arg.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    private static String nonIdNodeIdMessage(String actualType) {
        return "@nodeId is only valid on a field/argument of type ID (got '" + actualType + "'). "
            + "Every @nodeId decode/encode arm is gated on the ID type, so on a non-ID coordinate the "
            + "directive is silently dropped and the raw base64 wire value is bound undecoded. Change "
            + "the type to ID, or remove @nodeId.";
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
                result.add(new ValidationError(null,
                        no.sikt.graphitron.rewrite.model.Rejection.invalidSchema(buildRecipeMessage(m.group(1))), loc));
            } else {
                result.add(new ValidationError(null,
                        no.sikt.graphitron.rewrite.model.Rejection.invalidSchema(err.getMessage()), loc));
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
                + "See docs/manual/how-to/apollo-federation.adoc.";
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
        assertDirective(ctx, DIR_MUTATION, ARG_TYPE_NAME, ARG_MULTI_ROW);
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
