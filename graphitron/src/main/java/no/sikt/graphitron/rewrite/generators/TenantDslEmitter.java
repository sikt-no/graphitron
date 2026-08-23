package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits the per-field {@code DSLContext dsl = ...} declaration at every fetcher site, forked on
 * the field's {@link TenantBinding} arm. Single-tenant builds (no configured tenant scopes, or a
 * schema-free emission context) keep the exact {@code graphitronContext(env).getDslContext(env)}
 * form; multi-tenant builds route each acquisition through the generated
 * {@code TenantConnections} carrier:
 *
 * <ul>
 *   <li>{@link TenantBinding.ArgumentBound}: reads every bound slot's runtime value with the
 *       exact build-time-computed read (top-level argument or nested path; never a name search),
 *       folds them through the generated {@code divinedTenant} guard (collections flatten, all
 *       values must agree, absent is a request-level error), and acquires via {@code dslFor(key)}.
 *       The divined key is additionally handed down the subtree as graphql-java
 *       {@code localContext} (see {@link Resolution#handsDownTenant()}), which is what the
 *       {@link TenantBinding.Inherited} arm reads.</li>
 *   <li>{@link TenantBinding.Inherited}: the binding ancestor divined the tenant and handed it
 *       down as {@code localContext}; the field re-acquires the same tenant's connection through
 *       {@code dslFor}. Within a tenant-homogeneous execution context this is a value hand-down,
 *       not a per-row re-read.</li>
 *   <li>{@link TenantBinding.Untenanted}: global reference data; acquires the default source
 *       via {@code dslDefault()}, and deliberately never consults {@code localContext} (a global
 *       table under a bound ancestor still lives on the default source).</li>
 * </ul>
 *
 * <p>The per-row family ({@link TenantBinding.NodeIdBound}, {@link TenantBinding.EntityRepBound})
 * partitions at its dispatch surfaces ({@code QueryNodeFetcherClassGenerator},
 * {@code EntityFetcherDispatchClassGenerator}), not here; a per-row-bound coordinate reaching one
 * of these sites falls back to the inherited-value read, which fails loudly rather than routing
 * to a default connection.
 */
final class TenantDslEmitter {

    /**
     * The local holding the divined tenant key when {@link Resolution#handsDownTenant()}. One
     * home, shared with the command-driven emission that renders the same acquisition off a
     * plan row ({@code TenantAcquisitionFragments}), so the two paths cannot mint two names for
     * one generated local while both exist.
     */
    static final String TENANT_KEY_LOCAL =
        no.sikt.graphitron.render.TenantAcquisitionFragments.TENANT_KEY_LOCAL;

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");

    private TenantDslEmitter() {}

    /**
     * One site's resolved declaration. {@code declaration} is the full statement block the site
     * pastes where its {@code DSLContext dsl = ...} line goes. {@code handsDownTenant} is true
     * exactly when the declaration bound the {@value #TENANT_KEY_LOCAL} local: the site's
     * success return should then carry {@code .localContext(_divinedTenant)} so descendant
     * fields' {@link TenantBinding.Inherited} reads see the divined key.
     */
    record Resolution(CodeBlock declaration, boolean handsDownTenant) {

        /** {@code .localContext(_divinedTenant)} when the site divined a key, empty otherwise. */
        CodeBlock localContextTail() {
            return handsDownTenant
                ? CodeBlock.of(".localContext($L)", TENANT_KEY_LOCAL)
                : CodeBlock.of("");
        }
    }

    /**
     * Resolves the {@code DSLContext} declaration for {@code field}, emitted as the fetcher
     * method whose {@code env} parameter is the field's own {@code DataFetchingEnvironment}.
     * Falls back to the single-tenant form whenever the emission context carries no classified
     * schema, no tenant scopes are configured, or the coordinate has no binding (out-of-band
     * emission); the fallback reads the escape-hatch {@code DSLContext} and therefore fails
     * loudly under owned multi-tenant acquisition instead of routing anywhere.
     */
    static Resolution resolve(TypeFetcherEmissionContext ctx, OutputField field, String outputPackage) {
        var schema = ctx.graphitronSchema();
        if (schema == null
                || !(schema.tenantScopes() instanceof TenantScopes.Configured)
                || ctx.parentTypeName() == null) {
            return singleTenant(ctx);
        }
        TenantBinding binding = schema.tenantBindingOf(ctx.parentTypeName(), field.name());
        if (binding == null) {
            return singleTenant(ctx);
        }
        var tenantConnections = tenantConnectionsClass(outputPackage);
        return switch (binding) {
            case TenantBinding.Untenanted ignored -> new Resolution(
                CodeBlock.builder()
                    .addStatement("$T dsl = $T.dslDefault(env)", DSL_CONTEXT, tenantConnections)
                    .build(),
                false);
            case TenantBinding.ArgumentBound bound -> argumentBound(ctx, bound, tenantConnections);
            case TenantBinding.FanOut ignored -> throw new IllegalStateException(
                "Field '" + ctx.parentTypeName() + "." + field.name() + "' classified as tenant "
                    + "FanOut reached the generic DSL-declaration site; the fanned-fetcher emission "
                    + "owns this coordinate and acquires per tenant through scatter.");
            case TenantBinding.Inherited ignored -> inheritedRead(tenantConnections);
            case TenantBinding.NodeIdBound ignored -> inheritedRead(tenantConnections);
            case TenantBinding.EntityRepBound ignored -> inheritedRead(tenantConnections);
        };
    }

    /**
     * The single-tenant declaration on its own, for emission paths whose field carrier is not
     * statically an {@link OutputField} (they cannot classify, so they keep the escape-hatch
     * read; under owned multi-tenant acquisition that fails loudly rather than routing).
     */
    static CodeBlock singleTenantDeclaration(TypeFetcherEmissionContext ctx) {
        return singleTenant(ctx).declaration();
    }

    /**
     * {@link #resolve} for sites that carry only the field's name: the classified field is
     * looked up on the schema by coordinate ({@code ctx.parentTypeName()} + name), so a
     * multi-table polymorphic root whose participant filters bind the tenant column gets the
     * full {@link TenantBinding.ArgumentBound} emission (slot reads, agreement fold, hand-down)
     * without threading the carrier through every builder signature. Falls back to the
     * single-tenant form when the coordinate resolves to no classified {@link OutputField}.
     */
    static Resolution resolveByName(TypeFetcherEmissionContext ctx, String fieldName, String outputPackage) {
        var schema = ctx.graphitronSchema();
        if (schema == null || ctx.parentTypeName() == null) {
            return singleTenant(ctx);
        }
        return schema.fields().get(graphql.schema.FieldCoordinates.coordinates(
                ctx.parentTypeName(), fieldName)) instanceof OutputField field
            ? resolve(ctx, field, outputPackage)
            : singleTenant(ctx);
    }

    /**
     * Expression form of {@link #resolve} for sites that splice the {@code DSLContext} source
     * into their own statement and carry only the field's name (the service-call paths). Yields
     * the byte-identical {@code graphitronContext(env).getDslContext(env)} in single-tenant
     * builds. {@link TenantBinding.ArgumentBound} is unreachable here by construction (service
     * operations contribute no argument slots to the classifier); reaching it is a generation-time
     * failure rather than an unrouted connection.
     */
    static CodeBlock dslExpression(TypeFetcherEmissionContext ctx, String fieldName, String outputPackage) {
        var schema = ctx.graphitronSchema();
        if (schema == null
                || !(schema.tenantScopes() instanceof TenantScopes.Configured)
                || ctx.parentTypeName() == null) {
            return CodeBlock.of("$L.getDslContext(env)", ctx.graphitronContextCall());
        }
        TenantBinding binding = schema.tenantBindingOf(ctx.parentTypeName(), fieldName);
        if (binding == null) {
            return CodeBlock.of("$L.getDslContext(env)", ctx.graphitronContextCall());
        }
        var tenantConnections = tenantConnectionsClass(outputPackage);
        return switch (binding) {
            case TenantBinding.Untenanted ignored ->
                CodeBlock.of("$T.dslDefault(env)", tenantConnections);
            case TenantBinding.ArgumentBound ignored -> throw new IllegalStateException(
                "Field '" + ctx.parentTypeName() + "." + fieldName + "' classified as tenant "
                    + "ArgumentBound reached an expression-only DSL site that cannot emit the "
                    + "bound-slot reads; route it through TenantDslEmitter.resolve with the field carrier.");
            // Unreachable by design: the classifier rejects @tenantFanOut on @service
            // fields, so this arm firing is a graphitron bug, not an unrouted connection.
            case TenantBinding.FanOut ignored -> throw new IllegalStateException(
                "Field '" + ctx.parentTypeName() + "." + fieldName + "' classified as tenant "
                    + "FanOut reached an expression-only DSL site (a service-call path); the "
                    + "service fan-out combination is deferred and rejected at validation.");
            case TenantBinding.Inherited ignored -> inheritedReadExpression(tenantConnections);
            case TenantBinding.NodeIdBound ignored -> inheritedReadExpression(tenantConnections);
            case TenantBinding.EntityRepBound ignored -> inheritedReadExpression(tenantConnections);
        };
    }

    /** The localContext-divined acquisition expression the inherited family splices in. */
    private static CodeBlock inheritedReadExpression(ClassName tenantConnections) {
        return CodeBlock.of("$T.dslFor(env, $T.divinedTenant(env.<Object>getLocalContext()))",
            tenantConnections, tenantConnections);
    }

    /**
     * Hand-down-only resolution for thin delegating fetchers whose SQL (and routed {@code dsl})
     * lives in a companion rows method: when the field is {@link TenantBinding.ArgumentBound} in
     * a multi-tenant build, yields just the divined-key local so the fetcher's success return can
     * hand the key down the subtree; every other case yields an empty declaration. The companion
     * re-divines from the same {@code env}, so the two reads agree by construction.
     */
    static Resolution handDownOnly(TypeFetcherEmissionContext ctx, OutputField field, String outputPackage) {
        var schema = ctx.graphitronSchema();
        if (schema == null
                || !(schema.tenantScopes() instanceof TenantScopes.Configured)
                || ctx.parentTypeName() == null) {
            return new Resolution(CodeBlock.of(""), false);
        }
        TenantBinding binding = schema.tenantBindingOf(ctx.parentTypeName(), field.name());
        if (binding == null) {
            return new Resolution(CodeBlock.of(""), false);
        }
        var none = new Resolution(CodeBlock.of(""), false);
        return switch (binding) {
            case TenantBinding.ArgumentBound bound -> new Resolution(
                divinedKeyDeclaration(ctx, bound, tenantConnectionsClass(outputPackage)), true);
            // A fanned field hands tenants down per element (each unioned row's DataFetcherResult
            // carries its own localContext), never as one divined-key local; the fanned-fetcher
            // emission owns that stamping.
            case TenantBinding.FanOut ignored -> none;
            case TenantBinding.Inherited ignored -> none;
            case TenantBinding.NodeIdBound ignored -> none;
            case TenantBinding.EntityRepBound ignored -> none;
            case TenantBinding.Untenanted ignored -> none;
        };
    }

    /**
     * The DataLoader-name declaration for a batched field's registration site. Single-tenant
     * builds keep the exact inline path join. Multi-tenant builds route the recipe through the
     * generated carrier's single naming seam: {@link TenantBinding.Inherited} fields read the
     * tenant-partitioned name (the handed-down tenant joins the path as an opaque segment, so
     * every loader batch is tenant-homogeneous and its captured environment routes the right
     * source), every other arm the bare path name.
     */
    static CodeBlock loaderNameDeclaration(TypeFetcherEmissionContext ctx, String fieldName,
                                           String localName, String outputPackage) {
        var schema = ctx.graphitronSchema();
        TenantBinding binding = schema != null
                && schema.tenantScopes() instanceof TenantScopes.Configured
                && ctx.parentTypeName() != null
            ? schema.tenantBindingOf(ctx.parentTypeName(), fieldName)
            : null;
        if (binding == null) {
            return CodeBlock.builder()
                .addStatement("$T $L = $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                    String.class, localName, String.class, "/")
                .build();
        }
        var tenantConnections = tenantConnectionsClass(outputPackage);
        var tenantPartitioned = CodeBlock.builder()
            .addStatement("$T $L = $T.tenantLoaderName(env)", String.class, localName, tenantConnections)
            .build();
        var barePath = CodeBlock.builder()
            .addStatement("$T $L = $T.loaderName(env)", String.class, localName, tenantConnections)
            .build();
        return switch (binding) {
            case TenantBinding.Inherited ignored -> tenantPartitioned;
            // A fanned field's own loader batches its (untenanted) parents; the fan-out happens
            // inside the batch load, and children partition per tenant through the per-element
            // localContext stamping, not through this field's own loader name.
            case TenantBinding.FanOut ignored -> barePath;
            case TenantBinding.ArgumentBound ignored -> barePath;
            case TenantBinding.NodeIdBound ignored -> barePath;
            case TenantBinding.EntityRepBound ignored -> barePath;
            case TenantBinding.Untenanted ignored -> barePath;
        };
    }

    /**
     * Whether this emission context is a multi-tenant build (configured tenant scopes on a
     * classified schema). Sites whose emitted shape forks on tenancy beyond the DSL declaration
     * (the connection carrier's routed-context slot, its scatter helper) read this one predicate.
     */
    static boolean isMultiTenant(TypeFetcherEmissionContext ctx) {
        var schema = ctx.graphitronSchema();
        return schema != null && schema.tenantScopes() instanceof TenantScopes.Configured;
    }

    /** The generated carrier's {@code ClassName}: {@code <outputPackage>.schema.TenantConnections}. */
    static ClassName tenantConnectionsClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema",
            ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
    }

    /** The byte-identical pre-tenant form: {@code DSLContext dsl = graphitronContext(env).getDslContext(env);}. */
    private static Resolution singleTenant(TypeFetcherEmissionContext ctx) {
        return new Resolution(
            CodeBlock.builder()
                .addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall())
                .build(),
            false);
    }

    private static Resolution inheritedRead(ClassName tenantConnections) {
        return new Resolution(
            CodeBlock.builder()
                .addStatement("$T dsl = $T.dslFor(env, $T.divinedTenant(env.<Object>getLocalContext()))",
                    DSL_CONTEXT, tenantConnections, tenantConnections)
                .build(),
            false);
    }

    private static Resolution argumentBound(TypeFetcherEmissionContext ctx,
                                            TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        return new Resolution(
            CodeBlock.builder()
                .add(divinedKeyDeclaration(ctx, bound, tenantConnections))
                .addStatement("$T dsl = $T.dslFor(env, $L)", DSL_CONTEXT, tenantConnections, TENANT_KEY_LOCAL)
                .build(),
            true);
    }

    /**
     * The {@code <T> _divinedTenant = TenantConnections.divinedTenant(<slot reads>);} statement,
     * declared with the catalog-read tenant key type (generated sources never use {@code var}).
     */
    private static CodeBlock divinedKeyDeclaration(TypeFetcherEmissionContext ctx,
                                                   TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        var scopes = (TenantScopes.Configured) ctx.graphitronSchema().tenantScopes();
        var keyType = scopes.tenantType().isPrimitive() ? scopes.tenantType().box() : scopes.tenantType();
        var reads = slotReads(ctx, bound, tenantConnections);
        var divined = CodeBlock.builder()
            .add("$T $L = $T.divinedTenant(", keyType, TENANT_KEY_LOCAL, tenantConnections);
        for (int i = 0; i < reads.size(); i++) {
            if (i > 0) {
                divined.add(", ");
            }
            divined.add(reads.get(i));
        }
        return divined.add(");\n").build();
    }

    /**
     * The runtime read for every bound slot: a render over the {@link TenantBinding.SlotRead}
     * arm the classifier resolved when it minted the slot. The classifier's single traversal
     * decides <em>which</em> slots route and <em>how</em> each value is read; nothing here
     * re-walks the operation carriers, so classification and emission cannot disagree.
     */
    private static List<CodeBlock> slotReads(TypeFetcherEmissionContext ctx,
                                             TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        var reads = new ArrayList<CodeBlock>();
        for (TenantBinding.BoundSlot slot : bound.bindings()) {
            reads.add(switch (slot.read()) {
                case TenantBinding.SlotRead.TopLevelArg ignored ->
                    CodeBlock.of("env.<Object>getArgument($S)", slot.slotName());
                case TenantBinding.SlotRead.NestedInput nested -> {
                    var read = CodeBlock.builder()
                        .add("$T.tenantSlot(env.getArgument($S)", tenantConnections, nested.outerArgName());
                    for (String key : nested.path()) {
                        read.add(", $S", key);
                    }
                    yield read.add(")").build();
                }
                case TenantBinding.SlotRead.ContextArg ignored ->
                    CodeBlock.of("$L.getContextArgument(env, $S)",
                        ctx.graphitronContextCall(), slot.slotName());
            });
        }
        return reads;
    }
}
