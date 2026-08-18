package no.sikt.graphitron.lsp.facts;

import java.util.List;
import java.util.Optional;

/**
 * The Java method a field's producer directive binds it to: the {@code @service} or
 * {@code @externalField} reference the author wrote, with the arity the classpath census resolved it
 * at. The pair of relations behind it is {@code intent_field_producer_reference} left-joined to
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
 *
 * <p>What lives here is the rule, not the read. {@link DeclarationFacts} asks the two relations as an
 * arm of the one statement a declaration surface issues, and hands the rows back to {@link #resolve}.
 */
public final class FieldProducerMethods {

    private FieldProducerMethods() {}

    /**
     * One producer reference the coordinate carries, with the arity of the census method it matched.
     *
     * @param arity {@code null} where the census holds no matching method, which is the unresolved
     *              reference the outer join keeps
     */
    public record Reference(String className, String methodName, Integer arity) {}

    /**
     * The method the coordinate's references name, or empty where they name none and where two of them
     * name different ones.
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
    public static Optional<Producer> resolve(List<Reference> references) {
        if (references.isEmpty()) return Optional.empty();
        var first = references.getFirst();
        // One reference resolving to several overloads is one answer, the first row; two references
        // naming different methods are the disagreement above, and every row is checked against the
        // first rather than counted, so which rows differ does not matter.
        boolean disagrees = references.stream().anyMatch(row ->
            !row.className().equals(first.className()) || !row.methodName().equals(first.methodName()));
        if (disagrees) return Optional.empty();
        return Optional.of(new Producer(first.className(), first.methodName(),
            first.arity() == null ? 0 : first.arity()));
    }

    /**
     * A resolved producer method: the class and method the reference spells, and the parameter count
     * of the overload it resolved to. Zero where the census holds no such method, which the consumers
     * read as "jump by name".
     */
    public record Producer(String fqClassName, String methodName, int arity) {}
}
