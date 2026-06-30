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
import no.sikt.graphitron.rewrite.model.DomainReturnType;
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
        // R317 slice 4 — the types-only half of the single walk: classify the reachable composites on
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
        // R317 slice 4 — the single classify-and-emit walk. One SchemaTraverser.depthFirst over the
        // reachable output surface classifies each composite type on enter AND classifies the fields of
        // each reached object in the same visit (ClassifyingVisitor), replacing the three former
        // traversals of that surface: the SchemaReachability name-set walk, TypeBuilder.buildTypes'
        // eager type loop, and this method's separate field loop. The fold is possible because field
        // classification became registry-read-free for every target verdict (R317 slices 3a–3e): a
        // field's output target is a not-yet-visited child of its parent under the enter-only traversal,
        // and the field resolves it through the registry-free look-ahead / fixed-point indices, never a
        // registry lookup. buildTypes, the reachableOutputTypes hand-off, and the field loop are deleted.
        //
        // R279 slice 5 — connection synthesis stays a byproduct of visiting each field
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
        // R317 slice 3a — NestingType registration is folded onto the embedding edge (per classified
        // field in classifyFieldsOfObject), so the former post-walk registerNestingTypes sweep over the
        // whole field registry is gone.
        // R279 slice 4 — the multi-producer DomainReturnType agreement check is detected here (on the
        // pre-dangling field registry, against the assembled-schema SDL-Object axis) but no longer
        // demotes the producers; the conflicts ride on the schema's diagnostic channel and the
        // validator surfaces them, closing the enforcement in the same commit so no gap opens.
        // Connection synthesis above does not touch the field registry, so the conflict set is
        // unaffected by its relocation into the walk. R317 slice 5 — this and the three reductions
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
        // R194: reject case-insensitive type-name collisions. Graphitron emits one Java file per
        // type-name stem; on case-insensitive filesystems two case-equivalent names would clobber
        // each other. Runs post-promotion so synth-vs-synth Connection-name clashes (the consumer
        // repro) are visible. R317 slice 5 — registers a build-time diagnostic per colliding member
        // rather than demoting the registry entry, so the colliding types keep their classified
        // verdict and the assembled schema stays consistent; the validator surfaces the collision by
        // draining the channel.
        rejectCaseInsensitiveTypeCollisions(ctx);
        // R262: reject every @nodeId application whose coordinate is not of unwrapped type ID. Reads
        // ctx.schema applied directives (not the registry) because a dropped @nodeId leaves no trace
        // on the classified field — see rejectNonIdNodeId. Sibling soundness reduction; registers a
        // build-time diagnostic the validator drains, demoting nothing.
        rejectNonIdNodeId(ctx);
        // Hash-suffix dedup: walk every WithErrorChannel field and apply the collision-suffix
        // rule to ErrorChannel.mappingsConstantName so the resolved name lands on the carrier
        // before the emitter runs. Pass-through for the common case (every payload class has at
        // most one channel shape).
        var dedupedFields = MappingsConstantNameDedup.apply(ctx.fieldRegistry.entries());
        Map<String, EntityResolution> entitiesByType =
            EntityResolutionBuilder.build(ctx.typeRegistry, dedupedFields, rebuiltAssembled,
                ctx::addWarning, ctx::addDiagnostic);
        var model = new GraphitronSchema(
            ctx.types, Collections.unmodifiableMap(dedupedFields), entitiesByType, ctx.warnings(),
            ctx.diagnostics());
        return new BuildResult(model, rebuiltAssembled);
    }

    /**
     * R317 slice 4 — the single classify-and-emit walk's visitor. Fired by
     * {@link SchemaReachability#walk} once per reached composite (the schema traverser dispatches
     * {@code enter} exactly once per node identity), it classifies each composite type on enter
     * ({@link TypeBuilder#classifyAndRegister}) and, for object types, classifies that object's fields in
     * the same visit ({@link #classifyFieldsOfObject}). Because field classification is registry-read-free
     * (R317 slices 3a–3e), classifying a type and its fields together is order-independent: a field's
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
     * R317 slice 4 — classifies every field of one SDL object type, invoked from
     * {@link ClassifyingVisitor} as the single walk enters each reached object (slice 6 removed the
     * compensating sweep over unreached objects, so an unreached object's fields are not classified). A
     * directiveless nesting target (structurally decided, see
     * {@link TypeBuilder#isDirectivelessNestingTarget}) is skipped: its fields are resolved through the
     * {@code NestingField} that embeds it, whose {@code NestingType} this method registers at the edge
     * ({@link #registerNestingTypesIn}), not standalone here.
     */
    private static void classifyFieldsOfObject(
            BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder, GraphQLObjectType objType,
            List<ConnectionPromoter.CarrierRewrite> connectionRewrites, Set<String> synthesisedConnectionNames) {
        // R279 slice 5 — connection synthesis is a byproduct of visiting each field, run before any of
        // the classification early-returns below so it fires for every field exactly as the retired
        // all-types promotion pass did (including fields on directiveless parents whose standalone
        // classification is skipped). register owns dedup and the cross-carrier @tag union.
        for (var fieldDef : objType.getFieldDefinitions()) {
            ConnectionPromoter.synthesiseForField(
                ctx, objType, fieldDef, connectionRewrites, synthesisedConnectionNames);
        }
        var parentType = ctx.types.get(objType.getName());
        // A directiveless nesting target has its fields resolved through the embedding NestingField, not
        // standalone here. R317 slice 3a — this is decided structurally
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
                || parentType instanceof PageInfoType) {
            return;
        }
        // R178 Phase 4: structural carrier-shape detection (scanStructuralDmlPayload)
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
        // reclassify). R276 binds the carrier to a JooqTableRecordType, so the standard
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
            // R317 slice 3b — land the producer-backed single-record carrier verdict at the producing
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
            // R317 slice 3a — fold NestingType registration onto the embedding edge: a NestingField built
            // here establishes its target (and any deeper nested targets) as a NestingType right now,
            // recursing into nested fields, rather than in a post-walk sweep.
            registerNestingTypesIn(ctx, classified);
        });
    }

    /**
     * R276: assign {@link no.sikt.graphitron.rewrite.model.GraphitronType.NestingType} to the SDL object
     * type a {@code NestingField} embeds, recursing into nested {@code NestingField}s. The type pass
     * leaves a directiveless object unclassified (it cannot know what it is); a {@code NestingField} built
     * at the embedding edge is the only thing that establishes it as a nesting projection of a table-backed
     * parent, so the invariant {@code NestingType} ⟺ {@code ∃ NestingField} holds by construction.
     *
     * <p>R317 slice 3a — called per classified field at the embedding edge (from
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
     * R317 slice 3b — registers the {@link GraphitronType.ResultType} verdict for a producer-backed
     * carrier at the producing edge (the field returning it), replacing the deleted post-type-pass
     * {@code TypeBuilder.promoteSingleRecordPayloads} SDL scan. {@code name} is the field's unwrapped
     * return-type name; the verdict fires only when {@link TypeBuilder#carrierVerdict} resolves a
     * producer-backed carrier (a DML {@code RETURNING} / single-level {@code @service} {@code @table}
     * carrier → {@link GraphitronType.JooqTableRecordType}, or — R329 — a two-level {@code @service}
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
     * R279 slice 5 — resolves the connection names the walk flagged as synthesised (absent from the
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
                case null, default -> null;
            };
            if (form != null) forms.add(form);
        }
        return List.copyOf(forms);
    }

    /**
     * R275 — model-level soundness backstop: no classified field may reference a type the model
     * dropped. A classified {@link OutputField} whose SDL return element is an Object type with
     * no registry entry would emit {@code typeRef("X")} while the type itself is never emitted;
     * graphql-java assembly then fails with {@code AssertException: type X not found in schema}
     * — an invalid schema discovered by the consumer at runtime instead of an author error at
     * build time. SDL Object types are the only kind the type pass can leave entirely absent
     * (directiveless objects with no producer binding, no carrier promotion, and no
     * {@code NestingField} embedding); every other SDL kind either registers or demotes to a
     * registered {@link UnclassifiedType} that carries its own diagnostic.
     *
     * <p>R317 slice 5 — registers a build-time {@link ValidationError} on the schema's diagnostic
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
            // R317 slice 5 — register a build-time diagnostic instead of reclassifying the field to
            // UnclassifiedField. The field keeps its real OutputField verdict; the demotion's second
            // job (removing the field from emission) is redundant because the validator throws before
            // the emitter runs (the global gate), and nothing between here and the validator reads the
            // demoted verdict: rebuildAssembledForConnections works off the SDL assembled schema plus
            // carrier rewrites, not the field registry. The shared ValidationError.forField factory
            // applies the same "Field '<qname>': " prefix the validator's validateUnclassifiedField
            // pass did, so the error stream is byte-identical to the former demotion by construction.
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
                    + "producer's returned record), or remove the field."),
                existing.location()));
        }
    }

    /**
     * R204 / R279 slice 4: detects, but no longer enforces, that every {@link OutputField} producer
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
     * (R279 slice 4 retired the reclassify-to-{@link GraphitronField.UnclassifiedField} post-pass that
     * previously enforced this here, moving enforcement to the validator in the same commit so no gap
     * opens; R317 slice 5 folded the dedicated {@code domainReturnTypeConflicts} carrier into the
     * single diagnostic channel). Runs on the pre-promotion, pre-dangling field registry against the
     * assembled-schema SDL-Object axis, so the conflict set is byte-identical to the retired post-pass;
     * the data field on the conflict payload (if any) stays classified as-is per R204's design fork
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
     * carries its own producer-agreement guarantees. R204's intent is the SDL-Object axis:
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
     * R194: Rejects every type whose name collides case-insensitively with another emit-producing
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
     * today). R317 slice 5 — for each collision group of two or more members, every member's typed
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
                // R317 slice 5 — register a diagnostic instead of demoting the colliding type. The
                // shared ValidationError.forType factory applies the same "Type '<name>': " prefix the
                // validator's validateUnclassifiedType pass did, so the error stream is byte-identical
                // to the former UnclassifiedType demotion by construction.
                ctx.addDiagnostic(ValidationError.forType(name,
                    Rejection.caseFoldCollision(group, origin), existing.location()));
            }
        }
    }

    /**
     * R262: rejects every {@code @nodeId} application whose coordinate does not have an unwrapped
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
     * {@code ctx.schema} here is sound. R317 slice 5 — registers a build-time diagnostic on the
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
