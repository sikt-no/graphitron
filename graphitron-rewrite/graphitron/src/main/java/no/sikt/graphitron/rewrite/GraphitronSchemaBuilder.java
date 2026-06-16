package no.sikt.graphitron.rewrite;

import com.apollographql.federation.graphqljava.FederationDirectives;
import graphql.language.DirectiveDefinition;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
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
        typeBuilder.buildTypes();
        return bctx;
    }

    private record BuildResult(GraphitronSchema model, GraphQLSchema assembled) {}

    private static BuildResult buildSchema(BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder) {
        validateDirectiveSchema(ctx);
        typeBuilder.buildTypes();
        // R279 slice 3b — field classification is driven by the same field-first reachability walk
        // that drove type classification: visit the reached object types (in walk order), then a
        // compensating sweep over the unreached object types so the field registry stays
        // behaviour-identical to the eager all-objects pass. Interfaces / unions in the reachable set
        // have no fields classified here (only object types do), so getObjectType filters them out.
        // Slice 6 deletes the compensating sweep, at which point fields of unreachable types are no
        // longer classified and the reachability prune becomes observable.
        var reachable = typeBuilder.reachableOutputTypes();
        for (var name : reachable) {
            if (!(ctx.schema.getType(name) instanceof GraphQLObjectType objType)) continue;
            classifyFieldsOfObject(ctx, typeBuilder, fieldBuilder, objType);
        }
        for (var t : ctx.schema.getAllTypesAsList()) {
            if (!(t instanceof GraphQLObjectType objType) || t.getName().startsWith("__")) continue;
            if (reachable.contains(t.getName())) continue;
            classifyFieldsOfObject(ctx, typeBuilder, fieldBuilder, objType);
        }
        registerNestingTypes(ctx);
        // R279 slice 4 — the multi-producer DomainReturnType agreement check is detected here (on the
        // pre-promotion, pre-dangling field registry, against the assembled-schema SDL-Object axis,
        // byte-identically to the retired reclassifying post-pass) but no longer demotes the
        // producers; the conflicts ride on the model and the validator's validateUniformDomainReturnType
        // surfaces them, closing the enforcement in the same commit so no gap opens.
        var domainReturnTypeConflicts = collectDomainReturnTypeConflicts(ctx);
        var rewrites = ConnectionPromoter.promote(ctx);
        rejectDanglingTypeReferences(ctx);
        var rebuiltAssembled = ConnectionPromoter.rebuildAssembledForConnections(ctx.schema, ctx.types, rewrites);
        // R194: reject case-insensitive type-name collisions. Graphitron emits one Java file per
        // type-name stem; on case-insensitive filesystems two case-equivalent names would clobber
        // each other. Runs post-promotion so synth-vs-synth Connection-name clashes (the consumer
        // repro) are visible; runs post-rebuild so the assembled schema stays consistent with the
        // pre-demotion registry shape (rebuild only inspects carrier rewrites + ConnectionType /
        // EdgeType / PageInfoType arms in the type map; demoting after rebuild keeps the
        // assembled-schema typeRefs resolvable while still routing the collision through the
        // validator's UnclassifiedType path).
        rejectCaseInsensitiveTypeCollisions(ctx);
        // Hash-suffix dedup: walk every WithErrorChannel field and apply the collision-suffix
        // rule to ErrorChannel.mappingsConstantName so the resolved name lands on the carrier
        // before the emitter runs. Pass-through for the common case (every payload class has at
        // most one channel shape).
        var dedupedFields = MappingsConstantNameDedup.apply(ctx.fieldRegistry.entries());
        Map<String, EntityResolution> entitiesByType =
            EntityResolutionBuilder.build(ctx.typeRegistry, dedupedFields, rebuiltAssembled, ctx::addWarning);
        var model = new GraphitronSchema(
            ctx.types, Collections.unmodifiableMap(dedupedFields), entitiesByType, ctx.warnings(),
            domainReturnTypeConflicts);
        return new BuildResult(model, rebuiltAssembled);
    }

    /**
     * R279 slice 3b — classifies every field of one SDL object type. Extracted verbatim from the
     * former all-objects field pass so the reachability-driven driver and the compensating
     * orphan sweep can both invoke it; the body is unchanged. A type the type pass left
     * unclassified ({@code ctx.types.get == null}) is skipped: its fields are resolved through the
     * {@code NestingField} that embeds it ({@link #registerNestingTypes}), not standalone here.
     */
    private static void classifyFieldsOfObject(
            BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder, GraphQLObjectType objType) {
        var parentType = ctx.types.get(objType.getName());
        if (parentType == null) return;
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
        // A directiveless object the type pass left unclassified (ctx.types.get == null,
        // handled by the early return above) has its fields resolved through the NestingField
        // that embeds it, not standalone here. NestingType is assigned post-field-pass from
        // those NestingFields (registerNestingTypes), so no parent is a NestingType yet.
        Class<?> parentBackingClass = typeBuilder.recordBackingClasses().get(objType.getName());
        objType.getFieldDefinitions().forEach(fieldDef ->
            ctx.fieldRegistry.classify(
                FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()),
                fieldBuilder.classifyField(fieldDef, objType.getName(), parentType, parentBackingClass)));
    }

    /**
     * R276: assign {@link no.sikt.graphitron.rewrite.model.GraphitronType.NestingType} to every SDL
     * object type that a {@code NestingField} embeds. The type pass leaves a directiveless object
     * unclassified (it cannot know what it is); a {@code NestingField} built during the field pass is
     * the only thing that establishes it as a nesting projection of a table-backed parent, so this
     * walk registers exactly those, recursing into nested {@code NestingField}s. The invariant
     * {@code NestingType} ⟺ {@code ∃ NestingField} therefore holds by construction.
     *
     * <p>A directiveless object that no {@code NestingField} embeds is left unclassified (absent from
     * {@code schema.types()}). It is an orphan: the field that returns it already classifies as
     * {@code UnclassifiedField}, so the rejection surfaces at the field edge.
     */
    private static void registerNestingTypes(BuildContext ctx) {
        ctx.fieldRegistry.entries().values().forEach(f -> registerNestingTypesIn(ctx, f));
    }

    private static void registerNestingTypesIn(BuildContext ctx, no.sikt.graphitron.rewrite.model.GraphitronField field) {
        if (!(field instanceof no.sikt.graphitron.rewrite.model.ChildField.NestingField nf)) return;
        String name = nf.returnType().returnTypeName();
        if (!ctx.typeRegistry.contains(name)) {
            var objType = ctx.schema.getObjectType(name);
            ctx.typeRegistry.classify(name, new no.sikt.graphitron.rewrite.model.GraphitronType.NestingType(
                name, BuildContext.locationOf(objType), objType));
        }
        nf.nestedFields().forEach(child -> registerNestingTypesIn(ctx, child));
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
     * <p>Demotes the referencing field to {@link GraphitronField.UnclassifiedField} (the same
     * shape as {@link #validateUniformDomainReturnType}), which both fails the build through
     * the validator's UnclassifiedField pass <em>and</em> removes the field from emission, so
     * the dangling reference is structurally impossible rather than merely checked. This is the
     * shape-agnostic closure of the per-shape classifier guards (the {@code @service}
     * orphan-carrier guard rejects recognized carrier shapes with richer, shape-specific
     * guidance before this pass runs; anything that slips past every classifier-level guard —
     * errors-only payloads, scan-{@code Reject} shapes, future holes — lands here). Runs after
     * {@link #registerNestingTypes} (nesting projections registered) and after
     * {@code ConnectionPromoter.promote} (SDL-declared Connection / Edge / PageInfo types are
     * registered by the promoter, not the type pass; running earlier would demote every
     * Connection-returning field).
     */
    private static void rejectDanglingTypeReferences(BuildContext ctx) {
        for (var entry : List.copyOf(ctx.fieldRegistry.entries().entrySet())) {
            if (!(entry.getValue() instanceof OutputField existing)) continue;
            String sdlReturn = sdlReturnTypeName(ctx.schema, entry.getKey());
            if (sdlReturn == null) continue;
            if (ctx.typeRegistry.contains(sdlReturn)) continue;
            var parentObj = ctx.schema.getObjectType(existing.parentTypeName());
            ctx.fieldRegistry.reclassify(
                entry.getKey(),
                new GraphitronField.UnclassifiedField(
                    existing.parentTypeName(), existing.name(), existing.location(),
                    parentObj == null ? null : parentObj.getFieldDefinition(existing.name()),
                    Rejection.structural(
                        "field '" + existing.parentTypeName() + "." + existing.name()
                        + "' returns SDL Object type '" + sdlReturn + "', which did not classify "
                        + "into the model (no @table or record-backed binding, no producer-backed carrier "
                        + "promotion, not embedded as a nesting projection of a table-backed "
                        + "parent). Emitting the field would reference a type absent from the "
                        + "generated schema and assembly would fail with \"type " + sdlReturn
                        + " not found in schema\". Give '" + sdlReturn + "' a binding (e.g. a "
                        + "@table data field or an [ID] @nodeId(typeName:) data field matching a "
                        + "producer's returned record), or remove the field.")),
                existing.getClass());
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
     * <p>Returns one {@link Rejection.AuthorError.MultiProducerDomainTypeDisagreement} per conflict
     * group, each naming every participant. The builder stashes the list on the
     * {@link GraphitronSchema}; {@code GraphitronSchemaValidator.validateUniformDomainReturnType}
     * drains it into compiler-style errors that fail the build (R279 slice 4 retired the
     * reclassify-to-{@link GraphitronField.UnclassifiedField} post-pass that previously enforced
     * this here, moving enforcement to the validator in the same commit so no gap opens). Runs on the
     * pre-promotion, pre-dangling field registry against the assembled-schema SDL-Object axis, so the
     * conflict set is byte-identical to the retired post-pass; the data field on the conflict payload
     * (if any) stays classified as-is per R204's design fork (a), since the validator halts the build
     * before any generated code runs.
     */
    private static List<Rejection> collectDomainReturnTypeConflicts(BuildContext ctx) {
        Map<String, List<FieldCoordinates>> bySdlReturnType = new LinkedHashMap<>();
        for (var entry : ctx.fieldRegistry.entries().entrySet()) {
            if (!(entry.getValue() instanceof OutputField of)) continue;
            String sdlReturn = sdlReturnTypeName(ctx.schema, entry.getKey());
            if (sdlReturn == null) continue;
            bySdlReturnType.computeIfAbsent(sdlReturn, k -> new ArrayList<>()).add(entry.getKey());
        }
        List<Rejection> conflicts = new ArrayList<>();
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
            conflicts.add(new Rejection.AuthorError.MultiProducerDomainTypeDisagreement(
                sdlReturn, participants));
        }
        return List.copyOf(conflicts);
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
     * R194: Demotes every type whose name collides case-insensitively with another emit-producing
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
     * today). For each collision group of two or more members, every member is demoted to
     * {@link UnclassifiedType} carrying a typed
     * {@link Rejection.InvalidSchema.CaseFoldCollision} rejection that names the full group plus
     * the demoted member's classifier-arm {@link Origin}; the variant's {@code message()} renders
     * the actionable fix hint per origin.
     * {@link GraphitronSchemaValidator#validateUnclassifiedType} then projects one
     * {@link ValidationError} per member, so the diagnostic is actionable from either entry point.
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
                ctx.typeRegistry.demote(name, new UnclassifiedType(
                    name, existing.location(), Rejection.caseFoldCollision(group, origin)));
            }
        }
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
