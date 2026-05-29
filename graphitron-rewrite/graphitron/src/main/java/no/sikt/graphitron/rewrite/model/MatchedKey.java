package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * R246 — the key identity {@code UpdateRowsWalker} matched against the UPDATE input's covered
 * columns. The walker queries jOOQ's {@code Table.getPrimaryKey()} / {@code Table.getKeys()} and
 * picks the first candidate (PK preferred) whose column set is a subset of the input-covered
 * columns; the winner is lifted into one of these arms.
 *
 * <p>The {@link PrimaryKey} / {@link UniqueKey} split is cosmetic for the WHERE-clause emitter
 * (both render the same equality conjunction over {@link #columns()}), but the discriminator is
 * load-bearing for the LSP diagnostic surface and for any future per-key-identity decision
 * (e.g. a RETURNING-column choice). {@link #keyName()} echoes jOOQ's {@code Key.getName()} so a
 * {@link UpdateRowsError.NoUniqueKeyCoverage} diagnostic can name the candidates by their catalog
 * identity.
 */
public sealed interface MatchedKey permits MatchedKey.PrimaryKey, MatchedKey.UniqueKey {

    /** The key's columns, ordered as declared on the key. */
    List<ColumnRef> columns();

    /** jOOQ's {@code Key.getName()}, for diagnostics. */
    String keyName();

    record PrimaryKey(List<ColumnRef> columns, String keyName) implements MatchedKey {
        public PrimaryKey { columns = List.copyOf(columns); }
    }

    record UniqueKey(List<ColumnRef> columns, String keyName) implements MatchedKey {
        public UniqueKey { columns = List.copyOf(columns); }
    }
}
