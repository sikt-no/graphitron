package no.sikt.graphitron.rewrite.model;

/**
 * A field that requires DataLoader setup — it has a batch key and a corresponding rows method.
 *
 * <p>Implemented by all field variants that are DataLoader-backed:
 * {@link ChildField.SplitTableField}, {@link ChildField.SplitLookupTableField},
 * {@link ChildField.ServiceTableField}, {@link ChildField.RecordTableField},
 * {@link ChildField.RecordLookupTableField}.
 *
 * <p>This interface is intentionally standalone (does not extend {@link GraphitronField}) so that
 * it can be applied as an orthogonal capability without being restricted by the sealed hierarchy.
 * Generators receive {@link GraphitronField} and pattern-match with {@code instanceof BatchKeyField}.
 *
 * <p>The DataLoader fetcher references {@link #rowsMethodName()} as its rows-method target —
 * the same name the rows method uses when emitting its declaration. The contract is: the fetcher
 * and the rows method agree on this name. The naming convention is determined by each implementing
 * type independently.
 */
public interface BatchKeyField {
    BatchKey batchKey();
    String rowsMethodName();

    /**
     * Whether this field's rows-method emits exactly one record per DataLoader key.
     *
     * <p>True iff the field is single-cardinality
     * ({@link ChildField.SplitTableField} with {@code !returnType().wrapper().isList()}) or
     * carries {@link BatchKey.AccessorKeyedMany} (the {@code loader.loadMany} contract: one
     * record per element-PK key, regardless of the field's GraphQL cardinality). False for
     * list-cardinality {@code SplitTableField} / {@code SplitLookupTableField} /
     * {@code RecordLookupTableField}, single-key {@code RecordTableField}
     * ({@code RowKeyed} / {@code LifterRowKeyed} / {@code AccessorKeyedSingle}, which return
     * a {@code List<Record>} per key).
     *
     * <p>The two consumer sites are
     * {@code TypeFetcherGenerator}'s {@code scatterSingleByIdx} helper-emission gate and
     * {@code SplitRowsMethodEmitter.buildForRecordTable}'s {@code buildSingleMethod} routing
     * decision; both ask the same uniform question of multiple variants and so collapse onto
     * this capability rather than each repeating the disjunction.
     */
    default boolean emitsSingleRecordPerKey() {
        return false;
    }
}
