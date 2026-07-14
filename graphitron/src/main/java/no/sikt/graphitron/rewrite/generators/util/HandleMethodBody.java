package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.KeyAlternative.KeyShape;

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
 *   <li>Group bindings by alternative-index into a {@link java.util.LinkedHashMap}.</li>
 *   <li>For each group, dispatch to {@code select<TypeName>Alt<N>(bindings, groupEnv, dsl, result)}.</li>
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

    static CodeBlock emit(EntityResolution entity, ClassName nodeIdEncoder) {
        var b = CodeBlock.builder();
        emitBindingRecord(b);
        emitGroupingMaps(b);
        emitPerRepLoop(b, entity, nodeIdEncoder);
        emitGroupDispatch(b, entity);
        return b.build();
    }

    private static void emitBindingRecord(CodeBlock.Builder b) {
        // Inline value type for grouping. JavaPoet doesn't support local records cleanly, so we
        // use a Map.Entry analogue: a pair (idx, columnValues) carried via Object[]. The select
        // method consumes List<Object[]> where each Object[] is { Integer idx, Object[] cols }.
        // No comment in emitted code: the structure is obvious from the loop.
    }

    private static void emitGroupingMaps(CodeBlock.Builder b) {
        var objectArray = no.sikt.graphitron.javapoet.ArrayTypeName.of(ClassName.get(Object.class));
        var listOfBindings = ParameterizedTypeName.get(LIST, objectArray);
        var outer = ParameterizedTypeName.get(MAP, ClassName.get(Integer.class), listOfBindings);
        b.addStatement("$T groups = new $T<>()", outer, LINKED_HASH_MAP);
    }

    private static void emitPerRepLoop(CodeBlock.Builder b, EntityResolution entity, ClassName nodeIdEncoder) {
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
            emitDecodeAndGroup(b, entity, alt, p.declarationIndex(), nodeIdEncoder);
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
        ClassName nodeIdEncoder
    ) {
        if (alt.shape() == KeyShape.NODE_ID) {
            // expectedTypeId: NodeType.typeId() (defaults to type name when @node(typeId:) is
            // omitted; differs only when the consumer set an explicit typeId). Passing the raw
            // typename here would silently fail decode for typeId-overridden types.
            String expectedTypeId = entity.nodeTypeId() != null ? entity.nodeTypeId() : entity.typeName();
            b.addStatement("Object idObj = rep.get($S)", "id");
            b.addStatement("if (!(idObj instanceof String idStr)) continue");
            b.addStatement("String[] decoded = $T.decodeValues($S, idStr)", nodeIdEncoder, expectedTypeId);
            // Reject a well-formed id whose decoded key has the wrong arity, exactly as the
            // single-record decode helpers (NodeIdEncoderClassGenerator) and
            // InputBeanInstantiationEmitter do. Without the != N guard, an under-arity id trips
            // AIOOBE inside select<Type>Alt<N> and an over-arity id silently resolves the wrong
            // row; both become a skipped rep (null slot), matching the null-not-error contract.
            b.addStatement("if (decoded == null || decoded.length != $L) continue", alt.columns().size());
            b.addStatement("Object[] cols = new Object[$L]", alt.columns().size());
            b.addStatement("for (int j = 0; j < decoded.length; j++) cols[j] = decoded[j]");
        } else {
            b.addStatement("Object[] cols = new Object[$L]", alt.requiredFields().size());
            for (int i = 0; i < alt.requiredFields().size(); i++) {
                b.addStatement("cols[$L] = rep.get($S)", i, alt.requiredFields().get(i));
            }
        }
        // Per-rep DFE: carries arguments(rep) for any in-rep argument reads inside the
        // dispatched alternative. Built per-rep (not per-group) so a single representation's
        // rep map is the one the dispatched fetcher's env sees.
        b.addStatement("$T repEnv = $T.newDataFetchingEnvironment(env).arguments(rep).build()",
            ENV, ENV_IMPL);
        b.addStatement("groups.computeIfAbsent($L, k -> new $T<>())"
            + ".add(new Object[]{idx, cols, repEnv})",
            altIndex, ARRAY_LIST);
    }

    private static void emitGroupDispatch(CodeBlock.Builder b, EntityResolution entity) {
        var listOfBindings = ParameterizedTypeName.get(LIST,
            no.sikt.graphitron.javapoet.ArrayTypeName.of(ClassName.get(Object.class)));
        var outerEntry = ParameterizedTypeName.get(
            ClassName.get(java.util.Map.Entry.class),
            ClassName.get(Integer.class), listOfBindings);
        b.beginControlFlow("for ($T altEntry : groups.entrySet())", outerEntry);
        b.addStatement("int altIdx = altEntry.getKey()");
        b.addStatement("$T bindings = altEntry.getValue()", listOfBindings);
        b.addStatement("Object[] first = bindings.get(0)");
        b.addStatement("$T groupEnv = ($T) first[2]", ENV, ENV);
        b.addStatement("$T dsl = graphitronContext(groupEnv).getDslContext(groupEnv)", DSL_CONTEXT);
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
