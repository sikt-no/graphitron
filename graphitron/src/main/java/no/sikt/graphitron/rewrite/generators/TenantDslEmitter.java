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
                    .addStatement("$T dsl = $T.dslDefault(env)", DSL_CONTEXT, tenantConnections)
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
            default -> CodeBlock.of("$T.dslFor(env, $T.divinedTenant(env.<Object>getLocalContext()))",
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
        return binding instanceof TenantBinding.Inherited
            ? CodeBlock.builder()
                .addStatement("$T $L = $T.tenantLoaderName(env)", String.class, localName, tenantConnections)
                .build()
            : CodeBlock.builder()
                .addStatement("$T $L = $T.loaderName(env)", String.class, localName, tenantConnections)
                .build();
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

    private static Resolution argumentBound(TypeFetcherEmissionContext ctx, OutputField field,
                                            TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        return new Resolution(
            CodeBlock.builder()
                .add(divinedKeyDeclaration(ctx, field, bound, tenantConnections))
                .addStatement("$T dsl = $T.dslFor(env, $L)", DSL_CONTEXT, tenantConnections, TENANT_KEY_LOCAL)
                .build(),
            true);
    }

    /**
     * The {@code <T> _divinedTenant = TenantConnections.divinedTenant(<slot reads>);} statement,
     * declared with the catalog-read tenant key type (generated sources never use {@code var}).
     */
    private static CodeBlock divinedKeyDeclaration(TypeFetcherEmissionContext ctx, OutputField field,
                                                   TenantBinding.ArgumentBound bound, ClassName tenantConnections) {
        var scopes = (TenantScopes.Configured) ctx.graphitronSchema().tenantScopes();
        var keyType = scopes.tenantType().isPrimitive() ? scopes.tenantType().box() : scopes.tenantType();
        var reads = slotReads(ctx, field, bound, tenantConnections);
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
        // Multi-table polymorphic roots carry their filter surface per participant, not on the
        // operation; mirror the classifier's participantFilters walk. Deduped: the same argument
        // typically binds on every participant, and one read per slot name suffices for the
        // agreement fold.
        var polymorphicFilters = switch (field) {
            case no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField f -> f.participantFilters();
            case no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField f -> f.participantFilters();
            default -> List.<no.sikt.graphitron.rewrite.model.ParticipantFilters>of();
        };
        if (reads.isEmpty() && !polymorphicFilters.isEmpty()) {
            var remaining = new HashSet<>(slotNames);
            for (var pf : polymorphicFilters) {
                var partReads = new ArrayList<CodeBlock>();
                collectFromFilters(ctx, pf.filters(), remaining, tenantConnections, partReads);
                for (var read : partReads) {
                    reads.add(read);
                }
                // One read per slot name across participants; drop names already read.
                for (var filter : pf.filters()) {
                    if (filter instanceof GeneratedConditionFilter gcf) {
                        for (BodyParam param : gcf.bodyParams()) {
                            remaining.remove(param.name());
                        }
                    }
                }
            }
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
                        reads.add(CodeBlock.of("env.<Object>getArgument($S)", s.argName()));
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
        // The VALUES envelope (INSERT / UPSERT, whose fieldBindings is structurally empty):
        // mirror the classifier's fields() walk, accumulating the nested key path for the read.
        collectFromInputFields(input.fields(), new java.util.ArrayDeque<>(), input.name(),
            slotNames, tenantConnections, reads);
    }

    private static void collectFromInputFields(List<no.sikt.graphitron.rewrite.model.InputField> fields,
                                               java.util.Deque<String> path, String argName,
                                               Set<String> slotNames, ClassName tenantConnections,
                                               List<CodeBlock> reads) {
        for (var field : fields) {
            switch (field) {
                case no.sikt.graphitron.rewrite.model.InputField.ColumnField cf -> {
                    if (!slotNames.contains(cf.name())) {
                        continue;
                    }
                    var read = CodeBlock.builder()
                        .add("$T.tenantSlot(env.getArgument($S)", tenantConnections, argName);
                    for (String key : path) {
                        read.add(", $S", key);
                    }
                    reads.add(read.add(", $S)", cf.name()).build());
                }
                case no.sikt.graphitron.rewrite.model.InputField.NestingField nf -> {
                    path.addLast(nf.name());
                    collectFromInputFields(nf.fields(), path, argName, slotNames, tenantConnections, reads);
                    path.removeLast();
                }
                default -> { }
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
            default -> CodeBlock.of("env.<Object>getArgument($S)", name);
        };
    }
}
