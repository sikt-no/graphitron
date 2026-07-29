package no.sikt.graphitron.command;

/**
 * How a {@link SelectTerm.Column} projection is addressed by its readers, decided once by the
 * producer per term (the alias rule: alias a term only when it has no column identity to read
 * by, and alias by result key only when its expression is occurrence-dependent).
 *
 * <p>The slot exists on {@link SelectTerm.Column} because both cases occur there today: a plain
 * scalar column projects unaliased and its readers address it by column identity, while the
 * standalone {@code @reference} (start table equals target) aliases the parent's own column by
 * result key purely so its reader matches the subquery shape's reader. That second case is
 * inherited rather than load-bearing; dropping it is a one-line producer edit in whichever
 * slice owns the read-side change. The subselect-shaped terms ({@link SelectTerm.ScalarSubselect},
 * {@link SelectTerm.HelperCall}) carry no slot: they have no column identity to read by, so they
 * are result-key-aliased structurally.
 */
public enum TermAlias {
    /** Projected under the column's own name; readers address it by column identity. */
    BY_COLUMN_IDENTITY,
    /** Aliased {@code "__rk_" + entry.getKey()}; readers re-derive via the result key. */
    BY_RESULT_KEY
}
