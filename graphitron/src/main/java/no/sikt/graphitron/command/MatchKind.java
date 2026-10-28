package no.sikt.graphitron.command;

/**
 * The comparison a {@link ColumnTerm} renders: single-value equality, set membership, or partial
 * membership. Together with the term's column arity ({@code columns().size() > 1} is the
 * row-value form) the first two cover the four shapes the model's
 * {@code BodyParam.ColumnPredicate} arms {@code Eq} / {@code In} / {@code RowEq} / {@code RowIn}
 * spell out; the command collapses that 2x2 because term arms are SQL shapes, never reasons, and
 * arity is already carried by the columns. {@link #PARTIAL_MEMBERSHIP} is the fifth arm,
 * {@code PartialRowIn}: a distinct SQL shape (the OR over elements of the AND over each element's
 * carried cells) at any arity, whose binding local is the finished condition.
 */
public enum MatchKind { EQUALITY, MEMBERSHIP, PARTIAL_MEMBERSHIP }
