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
 *   <li>{@link TenantBinding.ParentRowBound}: the parent row names the tenant at the first
 *       hop's slot. The fetcher declares the divined key from the key extraction's local for
 *       that column ({@link #parentRowHandDown}), partitions its loader on it and hands it down;
 *       the rows method re-reads the same column off its batch's environment
 *       ({@link #resolve}), which the partitioned loader makes agree across the batch.</li>
 *   <li>{@link TenantBinding.Untenanted.GlobalRead}: graphitron's own read of global reference
 *       data; acquires via {@code dslGlobal()}, the request's default tenant when it names one and
 *       the default source otherwise, and deliberately never consults {@code localContext} (a
 *       global table under a bound ancestor reads the default tenant, not the ancestor's).</li>
 *   <li>{@link TenantBinding.Untenanted.DefaultSource}: a global statement that writes or calls a
 *       service; acquires the fixed default source via {@code dslDefault()} in every request.</li>
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
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");

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
     * emission); the fallback reads {@code getDslContext(env)}, which under owned multi-tenant
     * acquisition is the fixed default source, never a routed tenant nor the request's default
     * tenant.
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
            case TenantBinding.Untenanted.GlobalRead ignored -> new Resolution(
                CodeBlock.builder()
                    .addStatement("$T dsl = $T.dslGlobal(env)", DSL_CONTEXT, tenantConnections)
                    .build(),
                false);
            case TenantBinding.Untenanted.DefaultSource ignored -> new Resolution(
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
            case TenantBinding.ParentRowBound parentRow -> parentRowRead(parentRow, tenantConnections);
        };
    }

    /**
     * The single-tenant declaration on its own, for emission paths whose field carrier is not
     * statically an {@link OutputField} (they cannot classify, so they keep the
     * {@code getDslContext(env)} read, which under owned multi-tenant acquisition is the fixed
     * default source).
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
     * into their own statement and carry only the field's name (the child polymorphic sites).
     * Yields the byte-identical {@code graphitronContext(env).getDslContext(env)} in
     * single-tenant builds. {@link TenantBinding.ArgumentBound} throws: an expression cannot
     * carry the bound-slot reads, so a divining coordinate reaching one of these sites is a
     * generation-time failure rather than an unrouted connection. The root service sites, which
     * can divine, go through {@link #serviceDslExpression} instead.
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
            case TenantBinding.Untenanted.GlobalRead ignored ->
                CodeBlock.of("$T.dslGlobal(env)", tenantConnections);
            case TenantBinding.Untenanted.DefaultSource ignored ->
                CodeBlock.of("$T.dslDefault(env)", tenantConnections);
            case TenantBinding.ArgumentBound ignored -> throw new IllegalStateException(
                "Field '" + ctx.parentTypeName() + "." + fieldName + "' classified as tenant "
                    + "ArgumentBound reached an expression-only DSL site that cannot emit the "
                    + "bound-slot reads; route it through TenantDslEmitter.resolve, or through "
                    + "handDownOnly plus serviceDslExpression, with the field carrier.");
            // Unreachable by design: the classifier rejects @tenantFanOut on @service
            // fields, so this arm firing is a graphitron bug, not an unrouted connection.
            case TenantBinding.FanOut ignored -> throw new IllegalStateException(
                "Field '" + ctx.parentTypeName() + "." + fieldName + "' classified as tenant "
                    + "FanOut reached an expression-only DSL site (a service-call path); the "
                    + "service fan-out combination is deferred and rejected at validation.");
            case TenantBinding.Inherited ignored -> inheritedReadExpression(tenantConnections);
            case TenantBinding.NodeIdBound ignored -> inheritedReadExpression(tenantConnections);
            case TenantBinding.EntityRepBound ignored -> inheritedReadExpression(tenantConnections);
            case TenantBinding.ParentRowBound ignored -> throw parentRowUnreachable(ctx, fieldName);
        };
    }

    /**
     * The {@code DSLContext} source a root {@code @service} site hands
     * {@code ServiceMethodCallEmitter.emit}, which declares {@code dsl} itself: the site pastes
     * {@code handDown}'s declaration (the {@code _divinedTenant} local for a divining field, empty
     * otherwise) ahead of the call, and this expression reads that local when it was declared,
     * else it is {@link #dslExpression}'s arm-forked source ({@code dslDefault} for
     * {@link TenantBinding.Untenanted.DefaultSource}, the handed-down read for
     * {@link TenantBinding.Inherited}).
     *
     * @param handDown the {@link #handDownOnly} resolution the site already pasted
     */
    static CodeBlock serviceDslExpression(TypeFetcherEmissionContext ctx, OutputField field,
                                          Resolution handDown, String outputPackage) {
        return handDown.handsDownTenant()
            ? CodeBlock.of("$T.dslFor(env, $L)", tenantConnectionsClass(outputPackage), TENANT_KEY_LOCAL)
            : dslExpression(ctx, field.name(), outputPackage);
    }

    /** The localContext-divined acquisition expression the inherited family splices in. */
    private static CodeBlock inheritedReadExpression(ClassName tenantConnections) {
        return CodeBlock.of("$T.dslFor(env, $T.divinedTenant(env.<Object>getLocalContext()))",
            tenantConnections, tenantConnections);
    }

    /**
     * Hand-down-only resolution for fetchers whose routed {@code dsl} is declared elsewhere: thin
     * delegating fetchers whose SQL lives in a companion rows method, and the root
     * {@code @service} sites, where {@code ServiceMethodCallEmitter.emit} declares {@code dsl}
     * from {@link #serviceDslExpression}. When the field is {@link TenantBinding.ArgumentBound} in
     * a multi-tenant build, yields just the divined-key local so the fetcher's success return can
     * hand the key down the subtree; every other case yields an empty declaration. A companion
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
            case TenantBinding.ParentRowBound ignored -> throw parentRowUnreachable(ctx, field.name());
        };
    }

    /**
     * The field's {@link TenantBinding.ParentRowBound} arm in a multi-tenant build, or
     * {@code null}. The batched fetcher forks its framing on this: the arm's loader name reads
     * the tenant the key extraction declares, so the extraction moves ahead of the name.
     */
    static TenantBinding.ParentRowBound parentRowBinding(TypeFetcherEmissionContext ctx, String fieldName) {
        var schema = ctx.graphitronSchema();
        if (schema == null
                || !(schema.tenantScopes() instanceof TenantScopes.Configured)
                || ctx.parentTypeName() == null) {
            return null;
        }
        return schema.tenantBindingOf(ctx.parentTypeName(), fieldName)
            instanceof TenantBinding.ParentRowBound parentRow ? parentRow : null;
    }

    /**
     * The fetch-site resolution of a {@link TenantBinding.ParentRowBound} field: declares
     * {@value #TENANT_KEY_LOCAL} from {@code keyLocal}, the local the key extraction read the
     * slot's source column into, so the parent row is read once. Hands the key down, which is
     * what the field's {@link TenantBinding.Inherited} children read.
     */
    static Resolution parentRowHandDown(TypeFetcherEmissionContext ctx, String keyLocal,
                                        String outputPackage) {
        var scopes = (TenantScopes.Configured) ctx.graphitronSchema().tenantScopes();
        var keyType = scopes.tenantType().isPrimitive() ? scopes.tenantType().box() : scopes.tenantType();
        return new Resolution(
            CodeBlock.builder()
                .addStatement("$T $L = $T.divinedTenant($L)", keyType, TENANT_KEY_LOCAL,
                    tenantConnectionsClass(outputPackage), keyLocal)
                .build(),
            true);
    }

    /**
     * Only a batched {@code @table} child carries {@link TenantBinding.ParentRowBound}, and its
     * fetcher and rows method go through {@link #parentRowHandDown} and {@link #resolve}; any
     * other site reaching the arm is a generator bug.
     */
    private static IllegalStateException parentRowUnreachable(TypeFetcherEmissionContext ctx, String fieldName) {
        return new IllegalStateException(
            "Field '" + ctx.parentTypeName() + "." + fieldName + "' classified as tenant "
                + "ParentRowBound reached a DSL site other than the batched table fetcher and its "
                + "rows method; only a batched @table child carries the arm.");
    }

    /**
     * The DataLoader-name declaration for a batched field's registration site. Single-tenant
     * builds keep the exact inline path join. Multi-tenant builds route the recipe through the
     * generated carrier's single naming seam: {@link TenantBinding.Inherited} fields read the
     * tenant-partitioned name (the handed-down tenant joins the path as an opaque segment, so
     * every loader batch is tenant-homogeneous and its captured environment routes the right
     * source), {@link TenantBinding.ParentRowBound} fields the same name over the tenant their
     * parent row names, every other arm the bare path name.
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
            // The tenant is the parent row's value, declared by the key extraction ahead of the
            // name (the fetcher's key-first framing), so each loader batch is one tenant's.
            case TenantBinding.ParentRowBound ignored -> CodeBlock.builder()
                .addStatement("$T $L = $T.tenantLoaderName(env, $L)",
                    String.class, localName, tenantConnections, TENANT_KEY_LOCAL)
                .build();
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

    /**
     * The rows-method read for {@link TenantBinding.ParentRowBound}: the slot's source column off
     * the batch's environment, which is the first key's. The fetcher partitions its loader on
     * the same value, so every key in the batch names this tenant.
     */
    private static Resolution parentRowRead(TenantBinding.ParentRowBound parentRow,
                                            ClassName tenantConnections) {
        var parent = parentRow.parentTable();
        return new Resolution(
            CodeBlock.builder()
                .addStatement("$T dsl = $T.dslFor(env, $T.divinedTenant((($T) env.getSource()).get($T.$L.$L)))",
                    DSL_CONTEXT, tenantConnections, tenantConnections, RECORD,
                    no.sikt.graphitron.render.CatalogRefs.constantsClass(parent),
                    parent.javaFieldName(), parentRow.slot().sourceSide().javaName())
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
            CodeBlock read = switch (slot.read()) {
                case TenantBinding.SlotRead.TopLevelArg ignored ->
                    CodeBlock.of("env.<Object>getArgument($S)", slot.slotName());
                case TenantBinding.SlotRead.NestedInput nested -> {
                    var walk = CodeBlock.builder()
                        .add("$T.tenantSlot(env.getArgument($S)", tenantConnections, nested.outerArgName());
                    for (String key : nested.path()) {
                        walk.add(", $S", key);
                    }
                    yield walk.add(")").build();
                }
                case TenantBinding.SlotRead.ContextArg ignored ->
                    CodeBlock.of("$L.getContextArgument(env, $S)",
                        ctx.graphitronContextCall(), slot.slotName());
            };
            reads.add(projected(ctx, slot, read));
        }
        return reads;
    }

    /**
     * The slot's read put through its transform: the read value itself where it already is the
     * tenant value, and the class's own node-id decode helper where the tenant is a slot of an
     * encoded key. The helper is the one {@code CompositeDecodeHelperRegistry} mints for every
     * other grain that decodes this node type, so a malformed id routed here fails with the same
     * message it would have failed with at the carrier's own decode, one statement later.
     */
    private static CodeBlock projected(TypeFetcherEmissionContext ctx,
                                       TenantBinding.BoundSlot slot, CodeBlock read) {
        return switch (slot.projection()) {
            case TenantBinding.SlotProjection.Raw ignored -> read;
            case TenantBinding.SlotProjection.DecodedKeySlot decoded -> {
                var registry = ctx.nodeIdDecodeHelpers();
                if (registry == null) {
                    throw new IllegalStateException(
                        "Graphitron generator bug (tenant routing): the slot '" + slot.slotName()
                        + "' routes on a decoded node id, but this emission context opened no"
                        + " decode-helper collector, so the helper would be dropped and the call"
                        + " site would name a method the class does not declare");
                }
                yield CodeBlock.of("$L($L)",
                    registry.registerTenantSlot(decoded.decode(), decoded.slot()), read);
            }
        };
    }
}
