package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Optional;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_COLUMN_TABLE;

/**
 * Which table a column name written at a field's site resolves against, when that table is not the
 * one the field's own parent is bound to: one read of {@code intent_field_column_table}, keyed on
 * the coordinate the cursor sits in.
 *
 * <p>The three arms of the answer are the three a surface needs, and the third is the absence of a
 * row rather than a value. {@link Scope.Resolved} names a table, so the surface reads that table's
 * columns instead of the parent's. {@link Scope.Silent} says no column name resolves here at all,
 * and the parent's own scope must not stand in, because offering or validating against it would
 * point the author at the wrong end of a join. An empty result says the relation has nothing to add,
 * which is the reading a surface already falls back to: the parent's own binding answers.
 *
 * <p>This replaced a projection that asked the classifier for a per-variant verdict and mapped the
 * variants onto these same three arms. The mapping was the part worth keeping and the variants were
 * not: what a surface needs to know is where a name resolves, and the resolution has two rules and
 * two silences rather than a case per classification.
 *
 * <p>Named for the relation it reads, because a second relation now answers the same question
 * totally: {@code intent_field_column_scope} carries the parent's own binding as a third rule, which
 * the store derives once for the classifier that resolves names against it. A surface here wants the
 * override rather than the total answer, the conflict silence being the half only this relation
 * carries; a reader that eventually wants both reads the scope and this one together.
 */
public final class FieldColumnTable {

    private FieldColumnTable() {}

    /** What a column name at a field's site resolves against, where the parent's scope is not it. */
    public sealed interface Scope permits Scope.Resolved, Scope.Silent {

        /** Resolve the name against {@code table} rather than against the parent's binding. */
        record Resolved(CatalogTable table) implements Scope {}

        /** No name resolves at this site, and the parent's binding must not answer for it. */
        record Silent() implements Scope {}
    }

    /**
     * The scope for one coordinate, empty where the relation carries no row and the parent's own
     * binding is the answer. Empty is also what an unavailable store gives, which lands on the same
     * fall-back a surface takes when it cannot resolve a shape: the parent's scope, or nothing.
     */
    public static Optional<Scope> of(StoreHandle store, String typeName, String fieldName) {
        var row = store.dsl()
            .select(INTENT_FIELD_COLUMN_TABLE.DISPOSITION,
                INTENT_FIELD_COLUMN_TABLE.TABLE_SOURCE_NAME,
                INTENT_FIELD_COLUMN_TABLE.TABLE_SCHEMA,
                INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)
            .from(INTENT_FIELD_COLUMN_TABLE)
            .where(INTENT_FIELD_COLUMN_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_FIELD_COLUMN_TABLE.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_COLUMN_TABLE.FIELD_NAME.eq(fieldName))
            .fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of("RESOLVE".equals(row.value1())
            ? new Scope.Resolved(new CatalogTable(row.value2(), row.value3(), row.value4()))
            : new Scope.Silent());
    }
}
