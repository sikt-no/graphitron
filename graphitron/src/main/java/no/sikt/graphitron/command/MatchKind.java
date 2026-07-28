package no.sikt.graphitron.command;

/**
 * The comparison a {@link ColumnTerm} renders: single-value equality or set membership. Together
 * with the term's column arity ({@code columns().size() > 1} is the row-value form) this covers
 * the four shapes the model's {@code BodyParam.ColumnPredicate} arms spell out
 * ({@code Eq} / {@code In} / {@code RowEq} / {@code RowIn}); the command collapses the 2x2
 * because term arms are SQL shapes, never reasons, and arity is already carried by the columns.
 */
public enum MatchKind { EQUALITY, MEMBERSHIP }
