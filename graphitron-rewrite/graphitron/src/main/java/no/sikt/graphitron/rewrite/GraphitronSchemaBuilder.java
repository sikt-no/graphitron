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
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.SingleRecordCarrierResolution;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.schema.federation.EntityResolutionBuilder;
import no.sikt.graphitron.rewrite.schema.federation.FederationSpec;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;

import java.util.ArrayList;
import java.util.Collections;
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
        var bctx = new BuildContext(assembled, new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), ctx);
        recordSdlScalarDirectives(registry, bctx);
        var svc = new ServiceCatalog(bctx);
        bctx.svc = svc;
        var typeBuilder = new TypeBuilder(bctx, svc);
        var fieldBuilder = new FieldBuilder(bctx, svc);
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
        ctx.schema.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLObjectType && !t.getName().startsWith("__"))
            .map(t -> (GraphQLObjectType) t)
            .forEach(objType -> {
                var parentType = ctx.types.get(objType.getName());
                if (parentType == null) return;
                // R75 Phase 1: single-record DML carrier registration. PojoResultType.NoBacking
                // types passing the trigger admit a single data field as SingleRecordTableField
                // with an inline-constructed SourceKey (Reader.ResultRowWalk, Wrap.Record, PK
                // columns from the data field's element table; cardinality from the data field's
                // wrapper). Phase 1 admits exactly one field, so we register it here and skip
                // the normal per-type classification pass for this carrier. NotCandidate /
                // Rejected fall through.
                if (ctx.tryResolveSingleRecordCarrier(objType.getName())
                        instanceof SingleRecordCarrierResolution.Ok ok) {
                    var shape = ok.shape();
                    var dataFieldDef = objType.getFieldDefinition(shape.dataFieldName());
                    var pkColumns = shape.dataTable().primaryKeyColumns();
                    var cardinality = shape.dataWrapper().isList()
                        ? SourceKey.Cardinality.MANY
                        : SourceKey.Cardinality.ONE;
                    var sourceKey = new SourceKey(
                        shape.dataTable(),
                        pkColumns,
                        java.util.List.of(),
                        new SourceKey.Wrap.Record(),
                        cardinality,
                        new SourceKey.Reader.ResultRowWalk());
                    ctx.fieldRegistry.classify(
                        FieldCoordinates.coordinates(objType.getName(), shape.dataFieldName()),
                        new ChildField.SingleRecordTableField(
                            objType.getName(), shape.dataFieldName(), locationOf(dataFieldDef),
                            new ReturnTypeRef.TableBoundReturnType(
                                shape.dataElementName(), shape.dataTable(), shape.dataWrapper()),
                            sourceKey));
                    return;
                }
                // Fields on plain SDL object types (no domain directive, no single-record-carrier
                // promotion) are the developer's responsibility to wire — the classifier records
                // the parent in schema.types() but leaves per-field classification out.
                if (parentType instanceof no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType) return;
                Class<?> parentBackingClass = typeBuilder.recordBackingClasses().get(objType.getName());
                objType.getFieldDefinitions().forEach(fieldDef ->
                    ctx.fieldRegistry.classify(
                        FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()),
                        fieldBuilder.classifyField(fieldDef, objType.getName(), parentType, parentBackingClass)));
            });
        var rewrites = ConnectionPromoter.promote(ctx);
        var rebuiltAssembled = ConnectionPromoter.rebuildAssembledForConnections(ctx.schema, ctx.types, rewrites);
        // Hash-suffix dedup: walk every WithErrorChannel field and apply the collision-suffix
        // rule to ErrorChannel.mappingsConstantName so the resolved name lands on the carrier
        // before the emitter runs. Pass-through for the common case (every payload class has at
        // most one channel shape).
        var dedupedFields = MappingsConstantNameDedup.apply(ctx.fieldRegistry.entries());
        Map<String, EntityResolution> entitiesByType =
            EntityResolutionBuilder.build(ctx.typeRegistry, dedupedFields, rebuiltAssembled, ctx::addWarning);
        var model = new GraphitronSchema(
            ctx.types, Collections.unmodifiableMap(dedupedFields), entitiesByType, ctx.warnings());
        return new BuildResult(model, rebuiltAssembled);
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
