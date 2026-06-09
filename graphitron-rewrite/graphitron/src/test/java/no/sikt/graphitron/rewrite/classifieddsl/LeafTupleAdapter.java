package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import static no.sikt.graphitron.rewrite.classifieddsl.DimensionTuple.inline;
import static no.sikt.graphitron.rewrite.classifieddsl.DimensionTuple.of;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Column;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Field;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Polymorphic;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Record;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.Table;
import static no.sikt.graphitron.rewrite.classifieddsl.Mapping.TableConnection;
import static no.sikt.graphitron.rewrite.classifieddsl.ProducerStep.Dml;
import static no.sikt.graphitron.rewrite.classifieddsl.ProducerStep.Query;
import static no.sikt.graphitron.rewrite.classifieddsl.ProducerStep.Service;

/**
 * R281's <em>throwaway</em> leaf-to-tuple adapter: maps each classified {@link OutputField} leaf
 * (and the slots where a dimension lives below the leaf name) to its {@code (producer, mapping)}
 * {@link DimensionTuple}. Built to full coverage, this switch <em>is</em> the leaf-to-dimension
 * truth table R164 will adopt when the dimensional pivot lands; at that point the field carries its
 * dimensions as slots directly, this adapter is deleted, and the corpus assertions stay byte-identical.
 *
 * <p><strong>Totality is compiler-enforced.</strong> The {@code switch} is exhaustive over the
 * sealed {@link OutputField} hierarchy, so adding a leaf without a verdict fails the build, the
 * validator-mirrors-classifier discipline turned on the adapter itself (R281 §"Validating the axis
 * set", "Totality").
 *
 * <p><strong>Verdict grounding.</strong> Each verdict is the <em>correct</em> dimensional fact for
 * the leaf, anchored against the {@code TypeFetcherGenerator} dispatch partition as <em>evidence</em>,
 * not blessed from it: where the generator's emission is a known defect the corpus asserts the
 * correct verdict and the defect is filed. The worked case is {@code TableInterfaceField} /
 * {@code TableMethodField}, classified inline ({@code []}) though the generator currently emits a
 * per-parent query (filed as R288). See R281 §"Current implementation vs. what's correct".
 */
public final class LeafTupleAdapter {

    private LeafTupleAdapter() {}

    /** Maps a classified output-field leaf to its asserted {@code (producer, mapping)} tuple. */
    public static DimensionTuple toTuple(OutputField field) {
        return switch (field) {
            // ---- QueryField (root) -------------------------------------------------------------
            // A root output field always starts a new query; its mapping is what the projection lands on.
            case QueryField.QueryTableField f -> of(Query, tableMapping(f.returnType()));
            case QueryField.QueryLookupTableField f -> of(Query, tableMapping(f.returnType()));
            case QueryField.QueryTableMethodTableField f -> of(Query, tableMapping(f.returnType()));
            case QueryField.QueryTableInterfaceField f -> of(Query, tableMapping(f.returnType()));
            // [Service, Query]: a developer method produces rows, then a follow-up query re-enters
            // the catalog to project the @table; [Service] terminal when the result is a record.
            case QueryField.QueryServiceTableField f -> of(Service, Query, tableMapping(f.returnType()));
            case QueryField.QueryServiceRecordField f -> of(Service, Record);
            // FORK (polymorphic mapping): multi-table interface/union/node roots resolve over
            // participant tables, not a single Table. Provisional Mapping.Polymorphic.
            case QueryField.QueryInterfaceField f -> of(Query, Polymorphic);
            case QueryField.QueryUnionField f -> of(Query, Polymorphic);
            case QueryField.QueryNodeField f -> of(Query, Polymorphic);
            case QueryField.QueryNodesField f -> of(Query, Polymorphic);

            // ---- MutationField (root) ----------------------------------------------------------
            // DML write. The return-shape slot (DmlReturnExpression) decides [Dml] (encoded ID) vs
            // [Dml, Query] (in-fetcher follow-up SELECT) and Column vs Table.
            case MutationField.DmlTableField f -> dmlTuple(f.returnExpression());
            case MutationField.MutationServiceTableField f -> of(Service, Query, tableMapping(f.returnType()));
            case MutationField.MutationServiceRecordField f -> of(Service, Record);
            // The @record DML carrier produces the PK-only RETURNING rows; the follow-up projection
            // is the child's [Query]. The carrier itself is [Dml], mapping Record.
            case MutationField.MutationDmlRecordField f -> of(Dml, Record);
            case MutationField.MutationBulkDmlRecordField f -> of(Dml, Record);
            // FORK (payload wrappers): the mutation payload wrapper exposes the affected rows as a
            // @record. Provisional [Dml], Record.
            case MutationField.MutationUpdatePayloadField f -> of(Dml, Record);
            case MutationField.MutationBulkUpdatePayloadField f -> of(Dml, Record);
            case MutationField.MutationDeletePayloadField f -> of(Dml, Record);
            case MutationField.MutationBulkDeletePayloadField f -> of(Dml, Record);

            // ---- ChildField: catalog column carriers (inline) ----------------------------------
            case ChildField.ColumnField f -> inline(Column);
            case ChildField.ColumnReferenceField f -> inline(Column);
            case ChildField.ParticipantColumnReferenceField f -> inline(Column);
            case ChildField.CompositeColumnField f -> inline(Column);
            case ChildField.CompositeColumnReferenceField f -> inline(Column);

            // ---- ChildField: table targets -----------------------------------------------------
            // Inline correlated subquery: no new query.
            case ChildField.TableField f -> inline(tableMapping(f.returnType()));
            case ChildField.LookupTableField f -> inline(tableMapping(f.returnType()));
            // TableInterfaceField / TableMethodField are classified inline (FK-correlatable); the
            // generator's current per-parent query is the R288 defect, not a [Query] verdict.
            case ChildField.TableInterfaceField f -> inline(tableMapping(f.returnType()));
            case ChildField.TableMethodField f -> inline(tableMapping(f.returnType()));
            // @splitQuery and record-parent re-query: a new keyed query batched through a DataLoader.
            case ChildField.SplitTableField f -> of(Query, tableMapping(f.returnType()));
            case ChildField.SplitLookupTableField f -> of(Query, tableMapping(f.returnType()));
            case ChildField.RecordTableField f -> of(Query, tableMapping(f.returnType()));
            case ChildField.RecordLookupTableField f -> of(Query, tableMapping(f.returnType()));
            case ChildField.RecordTableMethodField f -> of(Query, tableMapping(f.returnType()));
            // Service-backed table child: service rows then re-query to project the @table.
            case ChildField.ServiceTableField f -> of(Service, Query, tableMapping(f.returnType()));
            // Follow-up SELECT (the [_, Query] tail of a service/DML record-parent composition),
            // realized as its own child leaf carrying the [Query] segment.
            case ChildField.SingleRecordTableField f -> of(Query, Table);

            // ---- ChildField: service record / passthrough scalars (inline) ---------------------
            case ChildField.ServiceRecordField f -> of(Service, Record);
            case ChildField.RecordField f -> inline(Field);
            case ChildField.PropertyField f -> inline(Field);
            // FORK (computed scalar): @externalField inlines a jOOQ Field<X> into the parent SELECT,
            // a catalog-side computed column. Provisional inline Column.
            case ChildField.ComputedField f -> inline(Column);

            // ---- ChildField: nesting / constructor passthroughs --------------------------------
            case ChildField.NestingField f -> inline(tableMapping(f.returnType()));
            // FORK (constructor passthrough): builds a @record child from the parent record. The
            // tuple follows the constructed shape; provisional inline Record.
            case ChildField.ConstructorField f -> inline(Record);

            // ---- ChildField: polymorphic children ----------------------------------------------
            // FORK (polymorphic mapping + producer): multi-table UNION ALL keyed off the parent.
            // Provisional [Query], Polymorphic.
            case ChildField.InterfaceField f -> of(Query, Polymorphic);
            case ChildField.UnionField f -> of(Query, Polymorphic);

            // ---- ChildField: DELETE/payload returning carriers ---------------------------------
            // FORK (returning-record reads): the source IS the RETURNING record; no new query, the
            // field reads/encodes off the source row. Provisional inline.
            case ChildField.SingleRecordTableFieldFromReturning f -> inline(Table);
            case ChildField.SingleRecordIdFieldFromReturning f -> inline(Column);

            // FORK (errors channel): the errors field reads off localContext / a payload property /
            // an outcome wrapper arm; no catalog or service mapping fits cleanly. Provisional inline Field.
            case ChildField.ErrorsField f -> inline(Field);
        };
    }

    /** {@code mapping = TableConnection} when the return wrapper is a Relay connection, else {@code Table}. */
    private static Mapping tableMapping(ReturnTypeRef.TableBoundReturnType returnType) {
        return returnType.wrapper() instanceof FieldWrapper.Connection ? TableConnection : Table;
    }

    /**
     * The DML carrier's verdict reads off the {@link DmlReturnExpression} slot: an encoded-ID return
     * is {@code [Dml]} with a {@code Column} mapping (the encoded PK scalar); a projected return is
     * {@code [Dml, Query]} with a {@code Table} mapping (write, then in-fetcher follow-up SELECT).
     */
    private static DimensionTuple dmlTuple(DmlReturnExpression expr) {
        return switch (expr) {
            case DmlReturnExpression.EncodedSingle ignored -> of(Dml, Column);
            case DmlReturnExpression.EncodedList ignored -> of(Dml, Column);
            case DmlReturnExpression.ProjectedSingle ignored -> of(Dml, Query, Table);
            case DmlReturnExpression.ProjectedList ignored -> of(Dml, Query, Table);
        };
    }
}
