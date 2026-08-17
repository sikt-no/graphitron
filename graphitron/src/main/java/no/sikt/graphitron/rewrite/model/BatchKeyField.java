package no.sikt.graphitron.rewrite.model;

/**
 * A field that requires DataLoader setup — it carries the source-side metadata
 * ({@link SourceKey}) and DataLoader registration shape ({@link LoaderRegistration}) the
 * rows-method emitter and the DataFetcher emitter both dispatch on.
 *
 * <p>Implemented by all field variants that are DataLoader-backed:
 * {@link ChildField.BatchedTableField}, lookup-keyed or not (both
 * source shapes; the {@code @sourceRow} DTO-parent shape dissolves onto the former's
 * Record arm), {@link ChildField.BatchedPivotField},
 * {@link ChildField.ServiceTableField}, {@link ChildField.ServiceRecordField}, the
 * batched polymorphic pair {@link ChildField.BatchedInterfaceField} /
 * {@link ChildField.BatchedUnionField}, and the discriminated interface child's batched half
 * {@link ChildField.BatchedTableInterfaceField}. The "all" is enforced as a biconditional by the
 * projection membership census: a {@link ChildField} leaf declares a
 * {@link LoaderRegistration} record component iff it implements this interface.
 *
 * <p>This interface is intentionally standalone (does not extend {@link GraphitronField}) so that
 * it can be applied as an orthogonal capability without being restricted by the sealed hierarchy.
 * Generators receive {@link GraphitronField} and pattern-match with {@code instanceof BatchKeyField}.
 *
 * <p>The rows/load-method a DataLoader fetcher targets is named by the launcher row's
 * {@code UnitMethodRef} (minted in {@code no.sikt.graphitron.plan.GeneratedUnits}, the one
 * minting locus); this capability carries no generated-unit naming fact.
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
     * The field's GraphQL name. Read by the polymorphic batched emission (which keys its
     * per-typename helpers and parent-input aliases on the field); the in-tree
     * {@code BatchKeyField} implementers all expose {@code name()} on their underlying
     * {@code ChildField} record, so wiring it through is mechanical.
     */
    String name();

    /**
     * Whether this field's rows-method emits exactly one record per DataLoader key.
     *
     * <p>True iff the field is single-cardinality ({@code !returnType().wrapper().isList()}) or
     * carries {@link LoaderRegistration.Dispatch#LOAD_MANY} (the {@code loader.loadMany} contract:
     * one record per element-PK key, regardless of the field's GraphQL cardinality; unreachable
     * on {@link ChildField.BatchedTableField}'s Table-sourced arm, whose constructor pins
     * {@code LOAD_ONE}). False for list-cardinality batched / lookup fields (which return a
     * {@code List<Record>} per key).
     *
     * <p>The two consumer sites are
     * {@code TypeFetcherGenerator}'s {@code scatterSingleByIdx} helper-emission gate and the
     * launcher producer's batched result-shape fold (single-record vs record-list); both ask
     * the same uniform question of multiple variants and so collapse onto this capability
     * rather than each repeating the disjunction. The batched polymorphic pair sits outside
     * both sites (it inlines its own scatter and renders through no launcher row) and
     * overrides to a stated {@code false}; the iff above scopes to the leaves those two
     * sites dispatch on.
     */
    default boolean emitsSingleRecordPerKey() {
        return false;
    }
}
