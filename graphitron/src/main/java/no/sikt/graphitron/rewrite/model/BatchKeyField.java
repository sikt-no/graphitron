package no.sikt.graphitron.rewrite.model;

/**
 * A field that requires DataLoader setup — it carries the source-side metadata
 * ({@link SourceKey}) and DataLoader registration shape ({@link LoaderRegistration}) the
 * rows-method emitter and the DataFetcher emitter both dispatch on.
 *
 * <p>Implemented by all field variants that are DataLoader-backed:
 * {@link ChildField.SplitTableField}, {@link ChildField.SplitLookupTableField},
 * {@link ChildField.ServiceTableField}, {@link ChildField.RecordTableField},
 * {@link ChildField.RecordLookupTableField}, {@link ChildField.ServiceRecordField}.
 *
 * <p>This interface is intentionally standalone (does not extend {@link GraphitronField}) so that
 * it can be applied as an orthogonal capability without being restricted by the sealed hierarchy.
 * Generators receive {@link GraphitronField} and pattern-match with {@code instanceof BatchKeyField}.
 *
 * <p>The DataLoader fetcher references {@link #rowsMethodName()} as its rows-method target —
 * the same name the rows method uses when emitting its declaration. The contract is: the fetcher
 * and the rows method agree on this name. DataLoader-backed variants default to {@code rows<Name>};
 * service-backed variants override to {@code load<Name>} to mark the body as a service delegation.
 */
public interface BatchKeyField {
    /**
     * Singular per-field source-side metadata. Built inline by the field classifier in
     * {@link no.sikt.graphitron.rewrite.FieldBuilder} at field-construction time.
     */
    SourceKey sourceKey();

    /**
     * DataLoader container + dispatch projection for this field. Built inline by the field
     * classifier in {@link no.sikt.graphitron.rewrite.FieldBuilder} at field-construction time.
     */
    LoaderRegistration loaderRegistration();

    /**
     * Method name shared by the rows method's declaration and the DataLoader fetcher's call site.
     *
     * <p>Default: {@code "rows" + capitalize(name())} for DataLoader-backed variants. Service-
     * backed variants ({@link ChildField.ServiceTableField}, {@link ChildField.ServiceRecordField})
     * override to {@code "load" + capitalize(name())} to mark the body as a service delegation.
     */
    default String rowsMethodName() {
        String n = name();
        if (n == null || n.isEmpty()) return n;
        return "rows" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /**
     * The field's GraphQL name. Required by {@link #rowsMethodName()}'s default; the in-tree
     * {@code BatchKeyField} implementers all expose {@code name()} on their underlying
     * {@code ChildField} record, so wiring it through is mechanical.
     */
    String name();

    /**
     * Whether this field's rows-method emits exactly one record per DataLoader key.
     *
     * <p>True iff the field is single-cardinality
     * ({@link ChildField.SplitTableField} with {@code !returnType().wrapper().isList()}) or
     * carries {@link LoaderRegistration.Dispatch#LOAD_MANY} (the {@code loader.loadMany} contract:
     * one record per element-PK key, regardless of the field's GraphQL cardinality). False for
     * list-cardinality {@code SplitTableField} / {@code SplitLookupTableField} /
     * {@code RecordLookupTableField}, single-key {@code RecordTableField} (which return a
     * {@code List<Record>} per key).
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
