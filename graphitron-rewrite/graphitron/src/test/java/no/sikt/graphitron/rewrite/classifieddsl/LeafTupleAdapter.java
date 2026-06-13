package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import static no.sikt.graphitron.rewrite.classifieddsl.Intent.Delete;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.Fetch;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.Insert;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.Lookup;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.MutationService;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.Nesting;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.NodeResolve;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.QueryService;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.Update;
import static no.sikt.graphitron.rewrite.classifieddsl.Intent.Upsert;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Column;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Field;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Record;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Table;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.TableConnection;

/**
 * R281's <em>throwaway</em> leaf-to-tuple adapter, now reconstructing R299's three-axis verdict: maps
 * each classified {@link OutputField} leaf (and the slots where a dimension lives below the leaf name)
 * to its {@code (carrier, intent, mapping)} {@link DimensionTuple}. Built to full coverage, this switch
 * <em>is</em> the leaf-to-dimension truth table R290 will adopt when the field-side pivot lands; at that
 * point the field carries its dimensions as slots directly, this adapter is deleted, and the corpus
 * assertions stay byte-identical.
 *
 * <p>Each axis reconstructs from leaf identity exactly as {@code (producer, mapping)} already did, so
 * this slice is corpus-and-docs only, no field-model change:
 *
 * <ul>
 *   <li><strong>carrier</strong> from the leaf's enclosing sealed type: {@code QueryField} →
 *       {@link Carrier#Query}, {@code MutationField} → {@link Carrier#Mutation}, {@code ChildField} →
 *       {@link Carrier#Source}.</li>
 *   <li><strong>intent</strong> from leaf identity plus the {@link DmlKind} discriminator on the
 *       {@code @record} DML carriers.</li>
 *   <li><strong>mapping</strong> as before (table-vs-connection off the wrapper, encoded-vs-projected
 *       off the DML return expression).</li>
 * </ul>
 *
 * <p>The derived layer (re-fetch, new-query, {@code FetchRelated}, polarity) is <em>not</em> a tuple
 * axis: it is computed from these three plus the orthogonal slots, never asserted. {@link Carrier} gates
 * {@link Intent}; this switch cannot emit an off-carrier intent.
 *
 * <p><strong>Totality is compiler-enforced.</strong> The {@code switch} is exhaustive over the sealed
 * {@link OutputField} hierarchy, so adding a leaf without a verdict fails the build, the
 * validator-mirrors-classifier discipline turned on the adapter itself.
 *
 * <p><strong>Verdict grounding.</strong> Each verdict is the <em>correct</em> dimensional fact for the
 * leaf, anchored against the {@code TypeFetcherGenerator} dispatch partition as <em>evidence</em>, not
 * blessed from it: where the generator's emission is a known defect the corpus asserts the correct
 * verdict and the defect is filed. The worked case is {@code TableInterfaceField} / {@code
 * TableMethodField}, classified as a plain {@code Fetch} though the generator currently emits a
 * per-parent query (filed as R288).
 */
public final class LeafTupleAdapter {

    private LeafTupleAdapter() {}

    /** Maps a classified output-field leaf to its asserted {@code (carrier, intent, mapping)} tuple. */
    public static DimensionTuple toTuple(OutputField field) {
        return switch (field) {
            // ---- QueryField (root): carrier = Query --------------------------------------------
            case QueryField.QueryTableField f -> query(Fetch, tableMapping(f.returnType()));
            case QueryField.QueryLookupTableField f -> query(Lookup, tableMapping(f.returnType()));
            case QueryField.QueryTableMethodTableField f -> query(Fetch, tableMapping(f.returnType()));
            case QueryField.QueryTableInterfaceField f -> query(Fetch, tableMapping(f.returnType()));
            case QueryField.QueryServiceTableField f -> query(QueryService, tableMapping(f.returnType()));
            case QueryField.QueryServiceRecordField f -> query(QueryService, Record);
            // Polymorphic roots are catalog-bound (every participant is a @table/NodeType): mapping is
            // Table, with the participant set as a derived slot. node/nodes are NodeResolve.
            case QueryField.QueryInterfaceField f -> query(Fetch, polyMapping(f.returnType()));
            case QueryField.QueryUnionField f -> query(Fetch, polyMapping(f.returnType()));
            case QueryField.QueryNodeField f -> query(NodeResolve, polyMapping(f.returnType()));
            case QueryField.QueryNodesField f -> query(NodeResolve, polyMapping(f.returnType()));

            // ---- MutationField (root): carrier = Mutation --------------------------------------
            // DML write. The verb is the leaf identity; the return-shape slot (DmlReturnExpression)
            // decides Column (encoded ID) vs Table (in-fetcher follow-up SELECT). The follow-up itself
            // is the derived re-fetch, not a tuple axis.
            case MutationField.MutationInsertTableField f -> mutation(Insert, dmlMapping(f.returnExpression()));
            case MutationField.MutationUpdateTableField f -> mutation(Update, dmlMapping(f.returnExpression()));
            case MutationField.MutationDeleteTableField f -> mutation(Delete, dmlMapping(f.returnExpression()));
            case MutationField.MutationUpsertTableField f -> mutation(Upsert, dmlMapping(f.returnExpression()));
            case MutationField.MutationServiceTableField f -> mutation(MutationService, tableMapping(f.returnType()));
            case MutationField.MutationServiceRecordField f -> mutation(MutationService, Record);
            // The @record DML carriers read their verb off the DmlKind discriminator; the follow-up
            // projection is the data field's own concern.
            case MutationField.MutationDmlRecordField f -> mutation(dmlIntent(f.kind()), Record);
            case MutationField.MutationBulkDmlRecordField f -> mutation(dmlIntent(f.kind()), Record);
            // Payload wrappers expose the affected rows as a @record; the verb is the leaf identity.
            case MutationField.MutationUpdatePayloadField f -> mutation(Update, Record);
            case MutationField.MutationBulkUpdatePayloadField f -> mutation(Update, Record);
            case MutationField.MutationDeletePayloadField f -> mutation(Delete, Record);
            case MutationField.MutationBulkDeletePayloadField f -> mutation(Delete, Record);

            // ---- ChildField: carrier = Source --------------------------------------------------
            // Catalog column carriers.
            case ChildField.ColumnField f -> source(Fetch, Column);
            case ChildField.ColumnReferenceField f -> source(Fetch, Column);
            case ChildField.ParticipantColumnReferenceField f -> source(Fetch, Column);
            case ChildField.CompositeColumnField f -> source(Fetch, Column);
            case ChildField.CompositeColumnReferenceField f -> source(Fetch, Column);
            // Table targets. LookupTableField/SplitLookupTableField/RecordLookupTableField are Lookup;
            // the rest are plain Fetch. new-query (@splitQuery / record-handoff) is derived, not an axis.
            case ChildField.TableField f -> source(Fetch, tableMapping(f.returnType()));
            case ChildField.LookupTableField f -> source(Lookup, tableMapping(f.returnType()));
            case ChildField.TableInterfaceField f -> source(Fetch, tableMapping(f.returnType()));
            case ChildField.TableMethodField f -> source(Fetch, tableMapping(f.returnType()));
            case ChildField.SplitTableField f -> source(Fetch, tableMapping(f.returnType()));
            case ChildField.SplitLookupTableField f -> source(Lookup, tableMapping(f.returnType()));
            case ChildField.RecordTableField f -> source(Fetch, tableMapping(f.returnType()));
            case ChildField.RecordLookupTableField f -> source(Lookup, tableMapping(f.returnType()));
            case ChildField.RecordTableMethodField f -> source(Fetch, tableMapping(f.returnType()));
            case ChildField.ServiceTableField f -> source(QueryService, tableMapping(f.returnType()));
            // The follow-up SELECT projecting a @table from a service/DML record parent: a plain Fetch
            // of the table. (R290 collapses this leaf, its verdict folding into the re-fetch derivation.)
            case ChildField.SingleRecordTableField f -> source(Fetch, Table);

            // Service record / passthrough scalars.
            case ChildField.ServiceRecordField f -> source(QueryService, Record);
            case ChildField.RecordField f -> source(Fetch, Field);
            case ChildField.PropertyField f -> source(Fetch, Field);
            // @externalField inlines a jOOQ Field<X> into the parent SELECT; mapping stays Column as
            // today. (R290 reclassifies this to a domain Field/Record under the refined model.)
            case ChildField.ComputedField f -> source(Fetch, Column);

            // Nesting (asserted, not derived from an absent join-path) and constructor passthrough.
            case ChildField.NestingField f -> source(Nesting, tableMapping(f.returnType()));
            case ChildField.ConstructorField f -> source(Fetch, Record);

            // Polymorphic children: catalog-bound multi-table UNION keyed off the parent.
            case ChildField.InterfaceField f -> source(Fetch, polyMapping(f.returnType()));
            case ChildField.UnionField f -> source(Fetch, polyMapping(f.returnType()));

            // Encoded-PK scalar carriers: read the PK off RETURNING / the producer's in-memory records.
            case ChildField.SingleRecordIdFieldFromReturning f -> source(Fetch, Column);
            case ChildField.SingleRecordIdField f -> source(Fetch, Column);

            // The errors field reads an Outcome wrapper arm off env.getSource(); @error element types
            // are object types, so Record.
            case ChildField.ErrorsField f -> source(Fetch, Record);
        };
    }

    private static DimensionTuple query(Intent intent, Mapping mapping) {
        return new DimensionTuple(Carrier.Query, intent, mapping);
    }

    private static DimensionTuple mutation(Intent intent, Mapping mapping) {
        return new DimensionTuple(Carrier.Mutation, intent, mapping);
    }

    private static DimensionTuple source(Intent intent, Mapping mapping) {
        return new DimensionTuple(Carrier.Source, intent, mapping);
    }

    /** {@code mapping = TableConnection} when the return wrapper is a Relay connection, else {@code Table}. */
    private static Mapping tableMapping(ReturnTypeRef.TableBoundReturnType returnType) {
        return returnType.wrapper() instanceof FieldWrapper.Connection ? TableConnection : Table;
    }

    /**
     * Polymorphic (interface/union/node) results are catalog-bound over participant tables, so they map
     * to {@link Mapping#Table} ({@link Mapping#TableConnection} when paginated) with the participant set
     * carried in a derived slot rather than as a distinct mapping value.
     */
    private static Mapping polyMapping(ReturnTypeRef.PolymorphicReturnType returnType) {
        return returnType.wrapper() instanceof FieldWrapper.Connection ? TableConnection : Table;
    }

    /**
     * The DML table carrier's mapping reads off the {@link DmlReturnExpression} slot: an encoded-ID
     * return is {@code Column} (the encoded PK scalar); a projected return is {@code Table} (the
     * in-fetcher follow-up SELECT, whose re-fetch is a derivation, not a tuple axis).
     */
    private static Mapping dmlMapping(DmlReturnExpression expr) {
        return switch (expr) {
            case DmlReturnExpression.EncodedSingle ignored -> Column;
            case DmlReturnExpression.EncodedList ignored -> Column;
            case DmlReturnExpression.ProjectedSingle ignored -> Table;
            case DmlReturnExpression.ProjectedList ignored -> Table;
        };
    }

    /** Maps the {@code @record} DML carrier's {@link DmlKind} discriminator to its write {@link Intent}. */
    private static Intent dmlIntent(DmlKind kind) {
        return switch (kind) {
            case INSERT -> Insert;
            case UPDATE -> Update;
            case UPSERT -> Upsert;
            case DELETE -> Delete;
        };
    }
}
