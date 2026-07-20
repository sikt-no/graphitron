package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits the per-field {@code DSLContext dsl = ...} declaration at every fetcher site, forked on
 * the field's {@link TenantBinding} arm. Single-tenant builds (no configured tenant scopes, or a
 * schema-free emission context) keep the exact {@code graphitronContext(env).getDslContext(env)}
 * form; multi-tenant builds route each acquisition through the generated
 * {@code TenantConnections} carrier:
 *
 * <ul>
 *   <li>{@link TenantBinding.ArgumentBound} — reads every bound slot's runtime value with the
 *       exact build-time-computed read (top-level argument or nested path; never a name search),
 *       folds them through the generated {@code divinedTenant} guard (collections flatten, all
 *       values must agree, absent is a request-level error), and acquires via {@code dslFor(key)}.
 *       The divined key is additionally handed down the subtree as graphql-java
 *       {@code localContext} (see {@link Resolution#handsDownTenant()}), which is what the
 *       {@link TenantBinding.Inherited} arm reads.</li>
 *   <li>{@link TenantBinding.Inherited} — the binding ancestor divined the tenant and handed it
 *       down as {@code localContext}; the field re-acquires the same tenant's connection through
 *       {@code dslFor}. Within a tenant-homogeneous execution context this is a value hand-down,
 *       not a per-row re-read.</li>
 *   <li>{@link TenantBinding.Untenanted} — global reference data; acquires the default source
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

    /** The local holding the divined tenant key when {@link Resolution#handsDownTenant()}. */
    static final String TENANT_KEY_LOCAL = "_divinedTenant";

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
                    .addStatement("$T dsl = $T.of(env).dslDefault()", DSL_CONTEXT, tenantConnections)
                    .build(),
                false);
            case TenantBinding.ArgumentBound bound -> argumentBound(ctx, field, bound, tenantConnections);
            // Inherited, and the per-row family reaching a non-dispatch site: read the tenant the
            // binding ancestor handed down as localContext; absent hands-down fail loudly in the
            // generated divinedTenant guard rather than routing to a default connection.
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
                CodeBlock.of("$T.of(env).dslDefault()", tenantConnections);
            case TenantBinding.ArgumentBound ignored -> throw new IllegalStateException(
                "Field '" + ctx.parentTypeName() + "." + fieldName + "' classified as tenant "
                    + "ArgumentBound reached an expression-only DSL site that cannot emit the "
                    + "bound-slot reads; route it through TenantDslEmitter.resolve with the field carrier.");
            default -> CodeBlock.of("$T.of(env).dslFor($T.divinedTenant(env.getLocalContext()))",
                tenantConnections, tenantConnections);
        };
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
                || ctx.parentTypeName() == null
                || !(schema.tenantBindingOf(ctx.parentTypeName(), field.name())
                    instanceof TenantBinding.ArgumentBound bound)) {
            return new Resolution(CodeBlock.of(""), false);
        }
        var tenantConnections = tenantConnectionsClass(outputPackage);
        return new Resolution(divinedKeyDeclaration(ctx, field, bound, tenantConnections), true);
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
                .addStatement("$T dsl = $T.of(env).dslFor($T.divinedTenant(env.getLocalContext()))",
                    DSL_CONTEXT, tenantConnections, tenantConnections)
                .build(),
            false);
    }

    private static Resolution argumentBound(TypeFetcherEmissionContext ctx, OutputField field,
                                            TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        return new Resolution(
            CodeBlock.builder()
                .add(divinedKeyDeclaration(ctx, field, bound, tenantConnections))
                .addStatement("$T dsl = $T.of(env).dslFor($L)", DSL_CONTEXT, tenantConnections, TENANT_KEY_LOCAL)
                .build(),
            true);
    }

    /** The {@code var _divinedTenant = TenantConnections.divinedTenant(<slot reads>);} statement. */
    private static CodeBlock divinedKeyDeclaration(TypeFetcherEmissionContext ctx, OutputField field,
                                                   TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        var reads = slotReads(ctx, field, bound, tenantConnections);
        var divined = CodeBlock.builder()
            .add("var $L = $T.divinedTenant(", TENANT_KEY_LOCAL, tenantConnections);
        for (int i = 0; i < reads.size(); i++) {
            if (i > 0) {
                divined.add(", ");
            }
            divined.add(reads.get(i));
        }
        return divined.add(");\n").build();
    }

    /**
     * The runtime read for every bound slot, re-derived from the same operation carriers the
     * classifier folded the arm from: the arm decides <em>which</em> slots route, the carriers'
     * {@link CallSiteExtraction}s decide <em>how</em> each value is read (a top-level argument
     * or a build-time-computed nested path). A slot the carriers cannot produce a read for is a
     * generation-time failure: classification and emission disagreeing is a graphitron bug, and
     * failing the build beats emitting a fetcher that cannot route.
     */
    private static List<CodeBlock> slotReads(TypeFetcherEmissionContext ctx, OutputField field,
                                             TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        Set<String> slotNames = new HashSet<>();
        for (TenantBinding.BoundSlot slot : bound.bindings()) {
            slotNames.add(slot.slotName());
        }
        var reads = new ArrayList<CodeBlock>();
        switch (field.operation()) {
            case Operation.Fetch f -> collectFromFilters(ctx, f.filters(), slotNames, tenantConnections, reads);
            case Operation.Paginate p -> collectFromFilters(ctx, p.filters(), slotNames, tenantConnections, reads);
            case Operation.Lookup l -> collectFromLookup(l.lookupMapping(), slotNames, tenantConnections, reads);
            case Operation.Insert i -> collectFromTableInput(i.input(), slotNames, tenantConnections, reads);
            case Operation.Upsert u -> collectFromTableInput(u.input(), slotNames, tenantConnections, reads);
            default -> { }
        }
        if (reads.isEmpty()) {
            throw new IllegalStateException(
                "Field '" + ctx.parentTypeName() + "." + field.name() + "' classified as tenant "
                    + "ArgumentBound on slots " + slotNames + ", but its operation carriers yield no "
                    + "matching runtime read; classification and emission disagree.");
        }
        return reads;
    }

    private static void collectFromFilters(TypeFetcherEmissionContext ctx, List<WhereFilter> filters,
                                           Set<String> slotNames, ClassName tenantConnections,
                                           List<CodeBlock> reads) {
        for (WhereFilter filter : filters) {
            if (!(filter instanceof GeneratedConditionFilter gcf)) {
                continue;
            }
            for (BodyParam param : gcf.bodyParams()) {
                if (!slotNames.contains(param.name())) {
                    continue;
                }
                reads.add(extractionRead(ctx, param.name(), param.extraction(), tenantConnections));
            }
        }
    }

    private static void collectFromLookup(LookupMapping mapping, Set<String> slotNames,
                                          ClassName tenantConnections, List<CodeBlock> reads) {
        if (!(mapping instanceof LookupMapping.ColumnMapping cm)) {
            return;
        }
        for (var arg : cm.args()) {
            switch (arg) {
                case LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg s -> {
                    if (slotNames.contains(s.argName())) {
                        reads.add(CodeBlock.of("env.getArgument($S)", s.argName()));
                    }
                }
                case LookupMapping.ColumnMapping.LookupArg.MapInput mi -> {
                    for (InputColumnBinding.MapBinding b : mi.bindings()) {
                        if (slotNames.contains(b.fieldName())) {
                            reads.add(CodeBlock.of("$T.tenantSlot(env.getArgument($S), $S)",
                                tenantConnections, mi.argName(), b.fieldName()));
                        }
                    }
                }
                case LookupMapping.ColumnMapping.LookupArg.DecodedRecord ignored -> { }
            }
        }
    }

    private static void collectFromTableInput(no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg input,
                                              Set<String> slotNames, ClassName tenantConnections,
                                              List<CodeBlock> reads) {
        for (InputColumnBindingGroup group : input.fieldBindings()) {
            if (!(group instanceof InputColumnBindingGroup.MapGroup mg)) {
                continue;
            }
            for (InputColumnBinding.MapBinding b : mg.bindings()) {
                if (slotNames.contains(b.fieldName())) {
                    reads.add(CodeBlock.of("$T.tenantSlot(env.getArgument($S), $S)",
                        tenantConnections, input.name(), b.fieldName()));
                }
            }
        }
    }

    /** One slot's runtime read from its carrier's {@link CallSiteExtraction}. */
    private static CodeBlock extractionRead(TypeFetcherEmissionContext ctx, String name,
                                            CallSiteExtraction extraction, ClassName tenantConnections) {
        return switch (extraction) {
            case CallSiteExtraction.NestedInputField nested -> {
                var read = CodeBlock.builder()
                    .add("$T.tenantSlot(env.getArgument($S)", tenantConnections, nested.outerArgName());
                for (String key : nested.path()) {
                    read.add(", $S", key);
                }
                yield read.add(")").build();
            }
            case CallSiteExtraction.ContextArg ignored ->
                CodeBlock.of("$L.getContextArgument(env, $S)", ctx.graphitronContextCall(), name);
            // Direct and the coercing leaves (JooqConvert, EnumValueOf, ...) all read the raw
            // top-level argument: the divined key equality/lookup runs on the wire value, whose
            // Java type the generated divinedTenant guard checks against the tenant column type.
            default -> CodeBlock.of("env.getArgument($S)", name);
        };
    }
}
