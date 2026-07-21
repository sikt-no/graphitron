package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.TenantBinding;

import java.util.Comparator;
import java.util.List;

/**
 * Emits the per-type {@code handle<TypeName>} method body for
 * {@link EntityFetcherDispatchClassGenerator}.
 *
 * <p>Per-rep flow within the body:
 * <ol>
 *   <li>For each rep, walk alternatives in most-specific-first order and pick the first
 *       resolvable alternative whose {@code requiredFields} are all present in the rep
 *       (excluding {@code __typename}). No match → result slot stays {@code null}.</li>
 *   <li>Build a per-rep DFE (rebinding {@code arguments} to the rep's contents) so any
 *       in-rep argument reads inside the dispatched alternative resolve against the
 *       individual rep.</li>
 *   <li>Decode the rep into a column-value row: DIRECT copies rep field values index-by-index;
 *       NODE_ID passes {@code rep.id} through {@code NodeIdEncoder.decodeValues}.</li>
 *   <li>Group bindings by alternative-index into a {@link java.util.LinkedHashMap}. In a
 *       multi-tenant build over a tenant-scoped entity ({@link TenantRouting#bound()}), the
 *       grouping widens to {@code Map<Integer, Map<Object, List<Object[]>>>}: each rep's
 *       tenant is read at its alternative's classified decoded position, so one batch spanning
 *       tenants splits into tenant-homogeneous groups instead of erroring or leaking.</li>
 *   <li>For each group, dispatch to {@code select<TypeName>Alt<N>(bindings, groupEnv, dsl, result)}
 *       with the group's own {@code DSLContext}: per-tenant acquisition through
 *       {@code TenantConnections.dslFor} for a bound entity, the default source for a global
 *       entity in a multi-tenant build, and the escape-hatch context read in single-tenant
 *       builds (byte-identical to the pre-tenant emission).</li>
 * </ol>
 *
 * <p>Order preservation: result positions come from the rep's original index in the outer
 * representations list (passed in via {@code indices}); SQL ordering by the {@code idx} column
 * happens in {@link SelectMethodBody}.
 */
final class HandleMethodBody {

    private static final ClassName MAP             = ClassName.get("java.util", "Map");
    private static final ClassName LIST            = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST      = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName ENV             = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName ENV_IMPL        = ClassName.get("graphql.schema", "DataFetchingEnvironmentImpl");
    private static final ClassName DSL_CONTEXT     = ClassName.get("org.jooq", "DSLContext");

    private HandleMethodBody() {}

    /**
     * The dispatch surface's tenant routing for one entity type. {@code null} for a
     * single-tenant build (the emission is byte-identical to the pre-tenant form).
     * {@code bound} carries the per-alternative decoded tenant positions for a tenant-scoped
     * entity, or {@code null} for a global entity (which acquires the default source).
     */
    record TenantRouting(ClassName tenantConnections, TenantBinding.EntityRepBound bound) {}

    /** Single-tenant emission, byte-identical to the pre-tenant form. */
    static CodeBlock emit(EntityResolution entity, ClassName nodeIdEncoder) {
        return emit(entity, nodeIdEncoder, null);
    }

    static CodeBlock emit(EntityResolution entity, ClassName nodeIdEncoder, TenantRouting routing) {
        var b = CodeBlock.builder();
        boolean perTenant = routing != null && routing.bound() != null;
        emitGroupingMaps(b, perTenant);
        emitPerRepLoop(b, entity, nodeIdEncoder, routing);
        emitGroupDispatch(b, entity, routing);
        return b.build();
    }

    private static void emitGroupingMaps(CodeBlock.Builder b, boolean perTenant) {
        var objectArray = no.sikt.graphitron.javapoet.ArrayTypeName.of(ClassName.get(Object.class));
        var listOfBindings = ParameterizedTypeName.get(LIST, objectArray);
        if (perTenant) {
            var byTenant = ParameterizedTypeName.get(MAP, ClassName.get(Object.class), listOfBindings);
            var outer = ParameterizedTypeName.get(MAP, ClassName.get(Integer.class), byTenant);
            b.addStatement("$T groups = new $T<>()", outer, LINKED_HASH_MAP);
        } else {
            var outer = ParameterizedTypeName.get(MAP, ClassName.get(Integer.class), listOfBindings);
            b.addStatement("$T groups = new $T<>()", outer, LINKED_HASH_MAP);
        }
    }

    private static void emitPerRepLoop(CodeBlock.Builder b, EntityResolution entity,
                                       ClassName nodeIdEncoder, TenantRouting routing) {
        b.beginControlFlow("for (int idx : indices)");
        b.addStatement("$T<String, Object> rep = reps.get(idx)", MAP);
        // Most-specific resolvable alternative: emitted as an if-else cascade in priority order.
        var prioritized = priorityOrder(entity.alternatives());
        boolean any = false;
        for (var p : prioritized) {
            var alt = p.alt();
            if (!alt.resolvable()) continue;
            String cond = matchCondition(alt);
            if (any) {
                b.nextControlFlow("else if (" + cond + ")", matchArgs(alt));
            } else {
                b.beginControlFlow("if (" + cond + ")", matchArgs(alt));
                any = true;
            }
            emitDecodeAndGroup(b, entity, alt, p.declarationIndex(), nodeIdEncoder, routing);
        }
        if (any) {
            b.endControlFlow();
        }
        b.endControlFlow();
    }

    private static String matchCondition(KeyAlternative alt) {
        var sb = new StringBuilder();
        for (int i = 0; i < alt.requiredFields().size(); i++) {
            if (i > 0) sb.append(" && ");
            sb.append("rep.containsKey($S)");
        }
        return sb.toString();
    }

    private static Object[] matchArgs(KeyAlternative alt) {
        return alt.requiredFields().toArray();
    }

    private static void emitDecodeAndGroup(
        CodeBlock.Builder b, EntityResolution entity, KeyAlternative alt, int altIndex,
        ClassName nodeIdEncoder, TenantRouting routing
    ) {
        // Exhaustive sealed switch with no default arm: a future third key shape is a compile
        // error at this fork rather than a silent runtime gap.
        switch (alt) {
            case KeyAlternative.NodeId nodeId -> {
                // expectedTypeId: the resolved wire prefix (NodeType.typeId(), defaulted to the
                // type name at classify time; differs only when the consumer set an explicit
                // typeId). The builder resolved it; passing the raw typename here would silently
                // fail decode for typeId-overridden types.
                b.addStatement("Object idObj = rep.get($S)", "id");
                b.addStatement("if (!(idObj instanceof String idStr)) continue");
                b.addStatement("String[] decoded = $T.decodeValues($S, idStr)",
                    nodeIdEncoder, nodeId.expectedTypeId());
                // Reject a well-formed id whose decoded key has the wrong arity, exactly as the
                // single-record decode helpers (NodeIdEncoderClassGenerator) and
                // InputBeanInstantiationEmitter do. Without the != N guard, an under-arity id trips
                // AIOOBE inside select<Type>Alt<N> and an over-arity id silently resolves the wrong
                // row; both become a skipped rep (null slot), matching the null-not-error contract.
                b.addStatement("if (decoded == null || decoded.length != $L) continue", nodeId.columns().size());
                b.addStatement("Object[] cols = new Object[$L]", nodeId.columns().size());
                b.addStatement("for (int j = 0; j < decoded.length; j++) cols[j] = decoded[j]");
            }
            case KeyAlternative.Direct direct -> {
                var bindings = direct.bindings();
                b.addStatement("Object[] cols = new Object[$L]", bindings.size());
                for (int i = 0; i < bindings.size(); i++) {
                    b.addStatement("cols[$L] = rep.get($S)", i, bindings.get(i).repField());
                }
            }
        }
        // Per-rep DFE: carries arguments(rep) for any in-rep argument reads inside the
        // dispatched alternative. Built per-rep (not per-group) so a single representation's
        // rep map is the one the dispatched fetcher's env sees.
        b.addStatement("$T repEnv = $T.newDataFetchingEnvironment(env).arguments(rep).build()",
            ENV, ENV_IMPL);
        if (routing != null && routing.bound() != null) {
            // Each rep carries its own tenant at the alternative's classified decoded position;
            // grouping per (alternative, tenant) keeps every dispatched SELECT tenant-homogeneous.
            b.addStatement("groups.computeIfAbsent($L, k -> new $T<>())"
                + ".computeIfAbsent(cols[$L], k -> new $T<>())"
                + ".add(new Object[]{idx, cols, repEnv})",
                altIndex, LINKED_HASH_MAP, tenantPosition(entity, routing.bound(), altIndex), ARRAY_LIST);
        } else {
            b.addStatement("groups.computeIfAbsent($L, k -> new $T<>())"
                + ".add(new Object[]{idx, cols, repEnv})",
                altIndex, ARRAY_LIST);
        }
    }

    /** The tenant column's decoded position within {@code altIndex}'s column tuple. */
    private static int tenantPosition(EntityResolution entity, TenantBinding.EntityRepBound bound, int altIndex) {
        for (var slot : bound.alternatives()) {
            if (slot.alternativeIndex() == altIndex) {
                return slot.decodedPosition();
            }
        }
        // Classification admits an EntityRepBound only when every resolvable alternative carries
        // a tenant slot, so a miss here means the classifier and this emitter disagree.
        throw new IllegalStateException(
            "Entity '" + entity.typeName() + "' is tenant-bound but key alternative #" + altIndex
                + " carries no decoded tenant position; classification and emission disagree.");
    }

    private static void emitGroupDispatch(CodeBlock.Builder b, EntityResolution entity, TenantRouting routing) {
        var listOfBindings = ParameterizedTypeName.get(LIST,
            no.sikt.graphitron.javapoet.ArrayTypeName.of(ClassName.get(Object.class)));
        boolean perTenant = routing != null && routing.bound() != null;
        if (perTenant) {
            var byTenant = ParameterizedTypeName.get(MAP, ClassName.get(Object.class), listOfBindings);
            var outerEntry = ParameterizedTypeName.get(
                ClassName.get(java.util.Map.Entry.class),
                ClassName.get(Integer.class), byTenant);
            var innerEntry = ParameterizedTypeName.get(
                ClassName.get(java.util.Map.Entry.class),
                ClassName.get(Object.class), listOfBindings);
            b.beginControlFlow("for ($T altEntry : groups.entrySet())", outerEntry);
            b.addStatement("int altIdx = altEntry.getKey()");
            b.beginControlFlow("for ($T tenantEntry : altEntry.getValue().entrySet())", innerEntry);
            b.addStatement("$T bindings = tenantEntry.getValue()", listOfBindings);
            b.addStatement("Object[] first = bindings.get(0)");
            b.addStatement("$T groupEnv = ($T) first[2]", ENV, ENV);
            // One tenant-homogeneous SELECT per group: a null decoded tenant fails loudly in the
            // divined-key guard rather than routing anywhere.
            b.addStatement("$T dsl = $T.dslFor(groupEnv, $T.divinedTenant(tenantEntry.getKey()))",
                DSL_CONTEXT, routing.tenantConnections(), routing.tenantConnections());
            emitAltSwitch(b, entity);
            b.endControlFlow();
            b.endControlFlow();
            return;
        }
        var outerEntry = ParameterizedTypeName.get(
            ClassName.get(java.util.Map.Entry.class),
            ClassName.get(Integer.class), listOfBindings);
        b.beginControlFlow("for ($T altEntry : groups.entrySet())", outerEntry);
        b.addStatement("int altIdx = altEntry.getKey()");
        b.addStatement("$T bindings = altEntry.getValue()", listOfBindings);
        b.addStatement("Object[] first = bindings.get(0)");
        b.addStatement("$T groupEnv = ($T) first[2]", ENV, ENV);
        if (routing != null) {
            // Multi-tenant build, global entity table: every group reads the default source.
            b.addStatement("$T dsl = $T.dslDefault(groupEnv)",
                DSL_CONTEXT, routing.tenantConnections());
        } else {
            b.addStatement("$T dsl = graphitronContext(groupEnv).getDslContext(groupEnv)", DSL_CONTEXT);
        }
        emitAltSwitch(b, entity);
        b.endControlFlow();
    }

    private static void emitAltSwitch(CodeBlock.Builder b, EntityResolution entity) {
        // Switch over altIdx; one arm per resolvable alternative. The default arm is an empty
        // block (no trailing semicolon) since arrow-case + statement-vs-block is finicky in
        // JavaPoet's addStatement.
        b.beginControlFlow("switch (altIdx)");
        for (int i = 0; i < entity.alternatives().size(); i++) {
            var alt = entity.alternatives().get(i);
            if (!alt.resolvable()) continue;
            b.addStatement("case $L -> select$L$L(bindings, groupEnv, dsl, result)",
                i, entity.typeName(), "Alt" + i);
        }
        b.add("default -> {\n}\n");
        b.endControlFlow();
    }

    private record Prioritized(KeyAlternative alt, int declarationIndex) {}

    private static List<Prioritized> priorityOrder(List<KeyAlternative> alternatives) {
        var indexed = new java.util.ArrayList<Prioritized>(alternatives.size());
        for (int i = 0; i < alternatives.size(); i++) {
            indexed.add(new Prioritized(alternatives.get(i), i));
        }
        // Most required fields first; ties broken by declaration order.
        indexed.sort(Comparator
            .comparingInt((Prioritized p) -> -p.alt().requiredFields().size())
            .thenComparingInt(Prioritized::declarationIndex));
        return indexed;
    }
}
