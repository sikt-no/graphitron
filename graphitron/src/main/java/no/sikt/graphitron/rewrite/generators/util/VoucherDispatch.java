package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.emit.ConnectionKeyShape;
import no.sikt.graphitron.emit.EntityKeyQuery;
import no.sikt.graphitron.emit.VoucherRuntime;
import no.sikt.graphitron.render.CatalogRefs;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * The entity dispatch's voucher arm: what {@link EntityFetcherDispatchClassGenerator} emits for an entity
 * with an {@link EntityKeyQuery.Alternative.Voucher} alternative.
 *
 * <p>Every voucher a call presents is verified in {@code fetchEntities}, over all its representations,
 * before {@code resolveByReps} runs: that resolves one type after another and each type acquires as soon as
 * its groups decode, so checking inside a type's handler would let an earlier type pin and mount before a
 * later type's bad voucher was seen. A voucher that fails any check rejects the request as a whole: the
 * carrier remembers the rejection, refuses every acquisition after it, and the instrumentation throws it in
 * place of the result. A verified voucher's representation carries the token and the vouched-for values in
 * place of the voucher string, as a {@code Map.Entry}, so the handler decodes what was checked and nothing
 * else.
 *
 * <p>A call's vouchers carry one vouched-for value at most, a limit refused with a client error naming it,
 * so the voucher alternative's representations form one group, read on the vouched connection for that
 * value. Graphitron logs the vouch id, scope and outcome of every redemption and rejection, never the token
 * or its values.
 */
final class VoucherDispatch {

    /** The client error for a call whose vouchers carry more than one vouched-for value. */
    static final String LIMIT_MESSAGE = "This request presents vouchers for more than one vouched-for value;"
        + " one request may present vouchers for one.";

    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName HASH_SET = ClassName.get("java.util", "HashSet");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    private VoucherDispatch() {}

    /** Whether any of {@code entities} accepts vouchers. */
    static boolean accepts(List<EntityKeyQuery.Entity> entities) {
        return entities.stream().anyMatch(e -> voucher(e) != null);
    }

    /** The entity's voucher alternative, or null where it accepts none. */
    static EntityKeyQuery.Alternative.Voucher voucher(EntityKeyQuery.Entity entity) {
        return entity.alternatives().stream()
            .filter(EntityKeyQuery.Alternative.Voucher.class::isInstance)
            .map(EntityKeyQuery.Alternative.Voucher.class::cast)
            .filter(EntityKeyQuery.Alternative::resolvable)
            .findFirst().orElse(null);
    }

    /** The dispatcher's logger, for the audit lines of redemptions and rejections. */
    static FieldSpec logger(ClassName dispatcher) {
        return FieldSpec.builder(LOGGER, "LOGGER", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.getLogger($T.class)", LOGGER_FACTORY, dispatcher)
            .build();
    }

    /**
     * The verification of every voucher {@code reps} presents, emitted into {@code fetchEntities} ahead of
     * {@code resolveByReps}. A representation of an accepting type carrying its voucher field is verified
     * against that type's scope, vouched-for fields and key, its values coerced to the columns they reach;
     * a failure rejects the request and rethrows.
     */
    static CodeBlock verifyAll(List<EntityKeyQuery.Entity> entities, String outputPackage, ClassName clientException) {
        var schema = outputPackage + ".schema";
        var codec = ClassName.get(schema, VoucherRuntime.CODEC);
        var token = codec.nestedClass("Token");
        var verifier = codec.nestedClass("Verifier");
        var rejected = ClassName.get(schema, VoucherRuntime.REJECTED);
        var reason = rejected.nestedClass("Reason");
        var ownedConnections = ClassName.get(schema, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        var vouchedForType = ConnectionKeyShape.vouchedForClassName(outputPackage);
        var columnValues = ClassName.get(outputPackage + ".util", ColumnValuesClassGenerator.CLASS_NAME);

        var b = CodeBlock.builder();
        b.add("// Every voucher is verified before any type resolves, so a bad one takes no connection.\n");
        b.addStatement("$T verifier = env.getGraphQlContext().get($T.class)", verifier, verifier);
        b.addStatement("$T<$T> vouched = new $T<>()", SET, vouchedForType, HASH_SET);
        b.beginControlFlow("for (int i = 0; i < reps.size(); i++)");
        b.addStatement("$T<String, Object> rep = reps.get(i)", MAP);
        b.addStatement("if (rep == null) continue");
        b.addStatement("Object typeName = rep.get($S)", EntityFetcherDispatchClassGenerator.TYPENAME_COLUMN);
        boolean first = true;
        for (var entity : entities) {
            var voucher = voucher(entity);
            if (voucher == null) continue;
            String cond = "$S.equals(typeName) && rep.containsKey($S)";
            if (first) {
                b.beginControlFlow("if (" + cond + ")", entity.typeName(), voucher.voucherField());
                first = false;
            } else {
                b.nextControlFlow("else if (" + cond + ")", entity.typeName(), voucher.voucherField());
            }
            b.addStatement("$T token", token);
            b.addStatement("$T presented", vouchedForType);
            b.beginControlFlow("try");
            b.beginControlFlow("if (verifier == null)");
            b.addStatement("throw new $T($S)", IllegalStateException.class,
                "No voucher verifier in the GraphQL context: run the operation through the generated runtime");
            b.endControlFlow();
            b.beginControlFlow("if (!(rep.get($S) instanceof String presentedVoucher))", voucher.voucherField());
            b.addStatement("throw new $T($T.MALFORMED)", rejected, reason);
            b.endControlFlow();
            b.addStatement("token = verifier.verify($S, presentedVoucher, $S, $T.of($L), $T.of($L))",
                entity.typeName(), voucher.scope(),
                SET, voucher.vouchedFor().stream().map(f -> CodeBlock.of("$S", f.fieldName())).collect(CodeBlock.joining(", ")),
                SET, voucher.lookup().stream().map(f -> CodeBlock.of("$S", f.fieldName())).collect(CodeBlock.joining(", ")));
            // Each value must be a value of the column it reaches, as a key field's value must.
            for (var f : voucher.lookup()) {
                b.beginControlFlow("if ($T.coerce($L.getDataType(), token.lookup().get($S)) == null)",
                    columnValues, columnRef(entity, f), f.fieldName());
                b.addStatement("throw new $T($T.VALUE)", rejected, reason);
                b.endControlFlow();
            }
            var parts = CodeBlock.builder();
            for (int i = 0; i < voucher.vouchedFor().size(); i++) {
                var f = voucher.vouchedFor().get(i);
                b.addStatement("Object vouched$L = $T.coerce($L.getDataType(), token.vouchedFor().get($S))",
                    i, columnValues, columnRef(entity, f), f.fieldName());
                b.beginControlFlow("if (vouched$L == null)", i);
                b.addStatement("throw new $T($T.VALUE)", rejected, reason);
                b.endControlFlow();
                if (i > 0) parts.add(", ");
                parts.add("($T) vouched$L", javaType(f), i);
            }
            b.addStatement("presented = new $T($L)", vouchedForType, parts.build());
            b.nextControlFlow("catch ($T e)", rejected);
            b.addStatement("LOGGER.warn($S, $S, $S, e.reason())",
                "Voucher rejected for {} under scope {}: {}", entity.typeName(), voucher.scope());
            b.addStatement("$T.of(env).reject(e)", ownedConnections);
            b.addStatement("throw e");
            b.endControlFlow();
            b.addStatement("LOGGER.info($S, $S, $S, token.vouchId())",
                "Voucher redeemed for {} under scope {}, vouch id {}", entity.typeName(), voucher.scope());
            b.addStatement("vouched.add(presented)");
            b.addStatement("$T<String, Object> verified = new $T<>(rep)", MAP, LINKED_HASH_MAP);
            b.addStatement("verified.put($S, $T.entry(token, presented))", voucher.voucherField(), MAP);
            b.addStatement("reps.set(i, verified)");
        }
        if (!first) {
            b.endControlFlow();
        }
        b.endControlFlow();
        b.beginControlFlow("if (vouched.size() > 1)");
        b.addStatement("throw new $T($S)", clientException, LIMIT_MESSAGE);
        b.endControlFlow();
        return b.build();
    }

    /**
     * Decodes a verified voucher's representation in {@code handle<Type>}: the lookup part into the columns
     * of the key it decodes onto, as a direct key's representation decodes, and the vouched-for values
     * carried beside the binding for the group to acquire by.
     */
    static void decode(CodeBlock.Builder b, EntityKeyQuery.Entity entity, EntityKeyQuery.Alternative.Voucher voucher,
                       ClassName columnValues, String outputPackage) {
        var schema = outputPackage + ".schema";
        var token = ClassName.get(schema, VoucherRuntime.CODEC).nestedClass("Token");
        var vouchedForType = ConnectionKeyShape.vouchedForClassName(outputPackage);
        b.add("// Verified in fetchEntities: the voucher field carries the token and its vouched-for values.\n");
        b.addStatement("if (!(rep.get($S) instanceof $T.Entry<?, ?> verified)) continue",
            voucher.voucherField(), MAP);
        b.addStatement("$T token = ($T) verified.getKey()", token, token);
        b.addStatement("$T vouchedFor = ($T) verified.getValue()", vouchedForType, vouchedForType);
        b.addStatement("Object[] cols = new Object[$L]", voucher.lookup().size());
        for (int i = 0; i < voucher.lookup().size(); i++) {
            var f = voucher.lookup().get(i);
            b.addStatement("cols[$L] = $T.coerce($L.getDataType(), token.lookup().get($S))", i, columnValues,
                columnRef(entity, f), f.fieldName());
            b.addStatement("if (cols[$L] == null) continue", i);
        }
    }

    /**
     * The acquisition of a voucher group: the vouched connection for the group's vouched-for value, which
     * the binding carries as its fourth cell. A call's vouchers carry one value, so the group is
     * homogeneous.
     */
    static CodeBlock acquire(String outputPackage) {
        var schema = outputPackage + ".schema";
        var ownedConnections = ClassName.get(schema, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        var connectionKey = ConnectionKeyShape.className(outputPackage);
        var vouchedForType = ConnectionKeyShape.vouchedForClassName(outputPackage);
        return CodeBlock.of("$T.dslFor(groupEnv, $T.forVouched(($T) first[3]))", ownedConnections, connectionKey,
            vouchedForType);
    }

    private static TypeName javaType(EntityKeyQuery.KeyField field) {
        TypeName type = ClassName.bestGuess(field.column().bindingType());
        return type.isPrimitive() ? type.box() : type;
    }

    private static CodeBlock columnRef(EntityKeyQuery.Entity entity, EntityKeyQuery.KeyField field) {
        return CodeBlock.of("$T.$L.$L", CatalogRefs.className(EntityKeys.tablesClass(entity)),
            entity.tableField(), field.column().javaName());
    }
}
