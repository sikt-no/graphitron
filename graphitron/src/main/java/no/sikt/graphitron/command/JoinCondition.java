package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * A developer-authored join-condition method, typed to the fixed two-argument calling convention
 * the join emitters use: {@code method(sourceAlias, targetAlias)}, the first argument the alias of
 * the table the hop is entered from and the second the alias of the table it lands on.
 *
 * <p>Its own type rather than a bare class-and-method pair, for the reason the walk's
 * {@code JoinConditionRef} is its own type: the calling convention is the type's contract, and
 * other authored-method populations have different ones. Handing a filter-shaped method to a join
 * site is then a compile error rather than a generated-code bug.
 *
 * <p>Carried at two positions with two emit shapes and one convention.
 * {@link JoinBasis.Predicate#condition()} is the join's own {@code ON} clause; a hop's
 * {@code filter} is a predicate appended to the enclosing statement's {@code WHERE} beside a
 * keyed join. The carrying component names the clause, so this record does not.
 *
 * @param className  the authored class's fully qualified name, as written
 * @param methodName the static method on it
 */
public record JoinCondition(String className, String methodName) {

    public JoinCondition {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        if (className.isBlank() || methodName.isBlank()) {
            throw new IllegalArgumentException(
                "a join condition names an authored class and method; a blank one would emit as an"
                + " unparseable call rather than failing here");
        }
    }
}
