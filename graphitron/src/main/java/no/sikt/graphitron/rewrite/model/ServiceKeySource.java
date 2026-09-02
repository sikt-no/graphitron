package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Objects;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * Where the jOOQ record carrying a batched child {@code @service} field's key columns is bound in
 * the emitted fetcher. The arms differ on the source expression the key is read off, and on
 * nothing else: all four emit through the same wrap-driven extraction
 * ({@code GeneratorUtils.buildKeyExtraction}), which is why this is not a {@link KeyLift}. A
 * {@code @service}-backed field's {@link SourceKey#wrap()} is authored (the {@code Sources}
 * signature declares it) rather than derived from a lift, so routing these leaves through
 * {@link KeyLift#checkResidueAgreement} would assert the inferred rule against an authored wrap.
 *
 * <p>{@link #keyOwner()} is the table whose {@code Tables.X.COL} constants the columns are read
 * through, and {@link #keyColumns()} derives from it rather than being stored beside it: a
 * key-owner and a column list that could be set independently would let a future arm make them
 * disagree. On a {@code @table} parent the key owner is the parent's own table; on a class-backed
 * parent it is the table the {@code Sources} element type names, which is what lets a type
 * aggregated in Java host a batched child.
 *
 * <p>The arms are cut on the source-shape seam so {@link ChildField#sourceShape()} is a total
 * derivation off this one component. Neither service leaf is parent-kind-pure (a
 * {@code ServiceTableField} is minted on both a {@code @table} and a class-backed parent), so a
 * leaf-identity switch cannot answer that question and this component is what carries it.
 *
 * <p>The four arms now shadow {@link KeyLift}'s arm for arm, with nothing binding the two seals.
 * The split's justification is unchanged and is wrap provenance rather than the arm set, but the
 * shadowing is worth an audit: a residue check gated on wrap provenance rather than on leaf
 * identity could let one lift axis serve both paths. That collapse is a question for a later
 * inventory, not a pending decision here.
 */
public sealed interface ServiceKeySource {

    /** The table whose primary key is the batch key, and whose column constants the reads use. */
    TableRef keyOwner();

    /**
     * The batch key's columns: the key owner's primary key. Derived, never stored. A key owner with
     * no primary key is rejected at classify time, so this is non-empty on every constructed arm.
     */
    default List<ColumnRef> keyColumns() {
        return keyOwner().primaryKeyColumns();
    }

    /**
     * What arrives at {@code env.getSource()} under this key source: a catalog-projected table row
     * for {@link FromTableRow}, a producer-handed domain record for the other three. Read by
     * {@link ChildField#sourceShape()} on both service leaves.
     */
    default SourceShape sourceShape() {
        return switch (this) {
            case FromTableRow ignored -> SourceShape.Table;
            case FromHeldRecord ignored -> SourceShape.Record;
            case FromAccessor ignored -> SourceShape.Record;
            case FromLifter ignored -> SourceShape.Record;
        };
    }

    /**
     * A {@code @table} parent's SQL-projected row, read off {@code env.getSource()}. The key owner
     * is the parent's own table, which is the whole of the pre-existing contract: the batch key was
     * the parent's primary key by definition.
     */
    record FromTableRow(TableRef keyOwner) implements ServiceKeySource {
        public FromTableRow {
            Objects.requireNonNull(keyOwner, "keyOwner");
        }
    }

    /**
     * A class-backed parent whose backing class <em>is</em> the declared key record, read off
     * {@code env.getSource()}. No author declaration beyond the {@code Sources} element type: the
     * parent already holds a typed record of the key owner's table.
     */
    record FromHeldRecord(TableRef keyOwner) implements ServiceKeySource {
        public FromHeldRecord {
            Objects.requireNonNull(keyOwner, "keyOwner");
        }
    }

    /**
     * A class-backed parent exposing exactly one zero-arg accessor returning the declared key
     * record; the key columns are read off the record that accessor returns. The accessor may
     * legitimately return {@code null} (a to-one relation that resolved to no row), so the emitted
     * fetcher guards the bound source before extracting.
     */
    record FromAccessor(TableRef keyOwner, AccessorRef accessor) implements ServiceKeySource {
        public FromAccessor {
            Objects.requireNonNull(keyOwner, "keyOwner");
            Objects.requireNonNull(accessor, "accessor");
        }
    }

    /**
     * A class-backed parent whose {@code @sourceRow}-declared static method produces the key
     * record from the parent. The accessor arm's static twin: the author names a producer the
     * class does not itself expose, which is what lets a parent carrying only scalar key columns,
     * or one whose class is not the author's to edit, host a batched child.
     *
     * <p>The declared method may return {@code null} for the same reason an accessor may, so the
     * emitted fetcher guards the produced record before extracting.
     */
    record FromLifter(TableRef keyOwner, StaticProducerRef producer) implements ServiceKeySource {
        public FromLifter {
            Objects.requireNonNull(keyOwner, "keyOwner");
            Objects.requireNonNull(producer, "producer");
        }
    }
}
