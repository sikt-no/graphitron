package no.sikt.graphitron.rewrite.model;

/**
 * Where the row a {@link ResultKeyAliasedField}'s result-key alias is written into starts: the
 * verdict that decides whether the alias is path-qualified. The second axis of the alias, beside
 * {@link AliasOwner} (who owns the namespace); the two vary independently, so they are separate
 * stamps rather than one multiplied set of arms.
 *
 * <p>The problem it solves. A nesting field's type has no table of its own: its children are
 * read from the parent's SQL row, so its projection unit is spliced into an ancestor's SELECT
 * list. Two selection sets spliced into one row (two sibling nesting fields, or one nesting field
 * selected twice under two aliases) would otherwise write the same {@code __rk_<resultKey>} alias
 * with different SQL, and both readers would read whichever term won. Qualifying a spliced
 * field's alias by the result keys from the row's owner down to its nesting occurrence gives
 * every spliced selection set its own alias space.
 *
 * <ul>
 *   <li>{@link RowOwner}: the declaring type owns the row the field is written into, so the alias
 *       is unqualified by path.</li>
 *   <li>{@link Spliced}: the declaring type is a nesting type, so the field is written into an
 *       ancestor's row and the alias carries the path of result keys to it. The write side
 *       threads the path into the nested unit's {@code $project}; the read side composes the same
 *       path at runtime from the execution step chain.</li>
 * </ul>
 *
 * <p>Minted once, at capture, off the declaring type, the same place {@link AliasOwner} is
 * minted, and copied by every downstream carrier; neither the write side nor the read side
 * re-derives it.
 */
public sealed interface AliasScope {

    /** The declaring type owns the row: no path qualification. */
    record RowOwner() implements AliasScope {}

    /** The declaring type is a nesting type: the alias is qualified by the path to its row. */
    record Spliced() implements AliasScope {}

    /** The unqualified verdict, the answer for every field declared on a row-owning type. */
    static AliasScope rowOwner() {
        return new RowOwner();
    }

    /** The path-qualified verdict, the answer for every field declared on a nesting type. */
    static AliasScope spliced() {
        return new Spliced();
    }
}
