package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Optional;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_REFERENCE;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.selectCount;

/**
 * The Java method a field's producer directive binds it to: the {@code @service} or
 * {@code @externalField} reference the author wrote, with the arity the classpath census resolved it
 * at. One statement over {@code intent_field_producer_reference} left-joined to
 * {@code intent_field_producer_method}, which is the pair's own grain rather than two questions: a
 * reference and its matches.
 *
 * <p>Left-joined and not inner, because the two relations answer for different populations on
 * purpose. The reference exists as soon as a directive names a class, and a surface naming the
 * declaration a field binds to still wants it when the census never reached that class, which is
 * ordinary rather than exotic: the scan skips the generated jOOQ package and a classpath entry
 * nothing read. So an unresolved reference is a target with no arity rather than no target, and the
 * consumers' name-level fallback is what lands the jump.
 *
 * <p>This is the question the language server used to put to the classification walk's projection,
 * which held the resolved pair on five method-backed classification variants. Four of them are
 * {@code @service} and {@code @externalField} and are answered here; the fifth is {@code @routine},
 * whose generated call surface no relation carries.
 */
public final class FieldProducerMethods {

    private FieldProducerMethods() {}

    /**
     * The method the coordinate's producer reference names, or empty where it names none and where
     * two directives name different ones.
     *
     * <p>Empty on disagreement rather than a pick, on {@link TypeBackingClass}'s terms and for its
     * reason: a coordinate carrying both directives is a rejection, so the generator binds neither
     * method, and a jump to one of them would report a binding that does not exist. The store already
     * says so through {@code intent_authored_claim_conflict}; here the two references simply leave
     * nothing to name, and the caller falls through to what the parent type's scope offers, which is
     * what an unclaimed field gets.
     *
     * <p>An arity-overloaded reference resolves at the first overload in descriptor order, the census's
     * own order, so the answer does not move between reads. Which overload the generator bound is
     * not a fact any relation carries, and {@code candidates} on the resolution is how a surface that
     * must not guess finds out that it would be guessing.
     */
    public static Optional<Producer> of(StoreHandle store, String typeName, String fieldName) {
        var arity = field(selectCount()
            .from(JVM_METHOD_PARAMETER)
            .where(JVM_METHOD_PARAMETER.SOURCE_NAME.eq(INTENT_FIELD_PRODUCER_METHOD.SOURCE_NAME))
            .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(INTENT_FIELD_PRODUCER_METHOD.CLASS_NAME))
            .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(INTENT_FIELD_PRODUCER_METHOD.METHOD_NAME))
            .and(JVM_METHOD_PARAMETER.DESCRIPTOR.eq(INTENT_FIELD_PRODUCER_METHOD.DESCRIPTOR)));
        var rows = store.dsl()
            .select(INTENT_FIELD_PRODUCER_REFERENCE.CLASS_NAME,
                INTENT_FIELD_PRODUCER_REFERENCE.METHOD_NAME, arity)
            .from(INTENT_FIELD_PRODUCER_REFERENCE)
            .leftJoin(INTENT_FIELD_PRODUCER_METHOD)
            .on(INTENT_FIELD_PRODUCER_METHOD.GRAPH_NAME
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME))
            .and(INTENT_FIELD_PRODUCER_METHOD.TYPE_NAME
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME))
            .and(INTENT_FIELD_PRODUCER_METHOD.FIELD_NAME
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME))
            .and(INTENT_FIELD_PRODUCER_METHOD.DECLARED_VIA
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.DECLARED_VIA))
            .where(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME.eq(fieldName))
            .orderBy(INTENT_FIELD_PRODUCER_REFERENCE.DECLARED_VIA,
                INTENT_FIELD_PRODUCER_METHOD.DESCRIPTOR)
            .fetch();
        if (rows.isEmpty()) return Optional.empty();
        var first = rows.getFirst();
        // One reference resolving to several overloads is one answer, the first row; two references
        // naming different methods are the disagreement above, and every row is checked against the
        // first rather than counted, so which rows differ does not matter.
        boolean disagrees = rows.stream().anyMatch(row ->
            !row.value1().equals(first.value1()) || !row.value2().equals(first.value2()));
        if (disagrees) return Optional.empty();
        return Optional.of(new Producer(first.value1(), first.value2(),
            first.value3() == null ? 0 : first.value3()));
    }

    /**
     * A resolved producer method: the class and method the reference spells, and the parameter count
     * of the overload it resolved to. Zero where the census holds no such method, which the consumers
     * read as "jump by name".
     */
    public record Producer(String fqClassName, String methodName, int arity) {}
}
