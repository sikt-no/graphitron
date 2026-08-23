package no.sikt.graphitron.command;

import java.util.List;
import java.util.Objects;

/**
 * How one hop joins to the node before it: the {@code ON} half of a join, as data.
 *
 * <p>Two arms and no third. A join is keyed by columns or decided by an authored predicate, and
 * which of the two is a fact about how the path element resolved, not about what the emitter
 * feels like rendering. The walk's {@code On} carries a third arm for a lateral node, a
 * table-valued function correlated against what precedes it; this vocabulary has none, and the
 * absence is the point rather than an omission. A command tier that cannot spell a lateral hop
 * cannot carry one to a renderer whose family refuses it, so the refusal moves to where the row is
 * minted and stops being a throw every reader of a wider type has to keep.
 */
public sealed interface JoinBasis {

    /**
     * The keyed join: an ordered column pairing plus how the pairing was reached. Both are
     * carried, because they emit differently and neither is derivable from the other. A
     * foreign-key pairing emits through jOOQ's own key constant ({@code .onKey(Keys.FK)}), which
     * needs the constant's name and not the columns; a name-matched one emits the equalities
     * itself, which needs the columns and has no constant to name. The pairs travel with both
     * arms because the correlation a write captures reads them either way.
     */
    record ColumnPairs(Keying keying, List<KeyPair> pairs) implements JoinBasis {

        public ColumnPairs {
            Objects.requireNonNull(keying, "keying");
            pairs = List.copyOf(pairs);
            if (pairs.isEmpty()) {
                throw new IllegalArgumentException(
                    "a keyed join pairs at least one column; an empty pairing emits a join with no"
                    + " ON clause, which is a cross product rather than a join");
            }
        }
    }

    /** The authored join: the two-argument method whose result is the {@code ON} clause. */
    record Predicate(JoinCondition condition) implements JoinBasis {

        public Predicate {
            Objects.requireNonNull(condition, "condition");
        }
    }

    /** Which resolution paired the columns, and therefore how the join spells its keying. */
    sealed interface Keying {

        /**
         * A catalog foreign key: the generated {@code Keys} class and the constant naming this
         * key on it. jOOQ's own key handles the pairing, so the emission names the constant.
         */
        record ForeignKey(String keysClassName, String constantName) implements Keying {

            public ForeignKey {
                Objects.requireNonNull(keysClassName, "keysClassName");
                Objects.requireNonNull(constantName, "constantName");
                if (keysClassName.isBlank() || constantName.isBlank()) {
                    throw new IllegalArgumentException(
                        "a foreign-key join names the generated keys class and its constant; a"
                        + " blank one would emit as an unparseable reference");
                }
            }
        }

        /**
         * A pairing matched by column name, which is what carries a chain out of a table-valued
         * function's result: a function result declares no foreign key, so nothing else can pair
         * it against the table its rows were written into.
         */
        record NameMatched() implements Keying {}
    }
}
