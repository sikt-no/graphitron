package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;

/**
 * R160 — friendly LSP-facing label strings for classified fields and types. The label
 * vocabulary lives here, in the LSP module, rather than on the projection records; the
 * projection carries the load-bearing identity-plus-payload, this switch picks the
 * user-visible name.
 *
 * <p>Labels are 1:1 with the generator-side permits. The label switches dispatch over
 * the {@code GraphitronField} / {@code GraphitronType} sealed permit sets independently
 * of the {@link FieldClassification} / {@link TypeClassification} projection records,
 * which may collapse multiple permits onto one record. The two switches stay
 * exhaustive in opposite directions: the projector's switch enforces coverage of the
 * model permits with shapes the LSP can render; this label switch enforces that every
 * model permit has a user-facing name.
 *
 * <p>Generator-side renames are free; user-visible names are deliberate. Refinement of
 * the label phrasing is an LSP-module concern that does not affect the projection.
 */
public final class LspClassificationLabels {

    private LspClassificationLabels() {}

    /**
     * Label for a classified field, dispatched on the generator-side permit. Exhaustive
     * over {@code GraphitronField}'s sealed permit set; adding a new permit fails this
     * switch to compile until the label is added.
     */
    public static String fieldLabel(GraphitronField field) {
        return switch (field) {
            // --- ChildField permits ---
            case ChildField.ColumnField ignored -> "column";
            case ChildField.ColumnReferenceField ignored -> "reference column";
            case ChildField.ParticipantColumnReferenceField ignored -> "discriminated reference column";
            case ChildField.CompositeColumnField ignored -> "composite column";
            case ChildField.CompositeColumnReferenceField ignored -> "composite reference column";
            case ChildField.SingleRecordTableField ignored -> "single record table field";
            case ChildField.SingleRecordIdFieldFromReturning ignored -> "single record id field (RETURNING)";
            case ChildField.SingleRecordTableFieldFromReturning ignored -> "single record table field (RETURNING)";
            case ChildField.TableField ignored -> "table field";
            case ChildField.SplitTableField ignored -> "split table field";
            case ChildField.LookupTableField ignored -> "lookup table field";
            case ChildField.SplitLookupTableField ignored -> "split lookup table field";
            case ChildField.TableMethodField ignored -> "table method field";
            case ChildField.RecordTableMethodField ignored -> "record table method field";
            case ChildField.TableInterfaceField ignored -> "table interface field";
            case ChildField.InterfaceField ignored -> "interface field";
            case ChildField.UnionField ignored -> "union field";
            case ChildField.NestingField ignored -> "nesting field";
            case ChildField.ConstructorField ignored -> "constructor field";
            case ChildField.ServiceTableField ignored -> "service table field";
            case ChildField.ServiceRecordField ignored -> "service record field";
            case ChildField.RecordTableField ignored -> "record table field";
            case ChildField.RecordLookupTableField ignored -> "record lookup table field";
            case ChildField.RecordField ignored -> "record field";
            case ChildField.ComputedField ignored -> "computed field";
            case ChildField.PropertyField ignored -> "property field";
            case ChildField.ErrorsField ignored -> "errors field";

            // --- QueryField permits ---
            case QueryField.QueryTableField ignored -> "query table field";
            case QueryField.QueryLookupTableField ignored -> "query lookup table field";
            case QueryField.QueryTableMethodTableField ignored -> "query table-method field";
            case QueryField.QueryNodeField ignored -> "query node field";
            case QueryField.QueryNodesField ignored -> "query nodes field";
            case QueryField.QueryTableInterfaceField ignored -> "query table-interface field";
            case QueryField.QueryInterfaceField ignored -> "query interface field";
            case QueryField.QueryUnionField ignored -> "query union field";
            case QueryField.QueryServiceTableField ignored -> "query service-table field";
            case QueryField.QueryServiceRecordField ignored -> "query service-record field";

            // --- MutationField permits ---
            case MutationField.MutationInsertTableField ignored -> "insert mutation";
            case MutationField.MutationUpdateTableField ignored -> "update mutation";
            case MutationField.MutationDeleteTableField ignored -> "delete mutation";
            case MutationField.MutationUpsertTableField ignored -> "upsert mutation";
            case MutationField.MutationServiceTableField ignored -> "service table mutation";
            case MutationField.MutationServiceRecordField ignored -> "service record mutation";
            case MutationField.MutationDmlRecordField ignored -> "DML record mutation";
            case MutationField.MutationBulkDmlRecordField ignored -> "bulk DML record mutation";

            // --- InputField permits ---
            case InputField.ColumnField ignored -> "input column";
            case InputField.ColumnReferenceField ignored -> "input reference column";
            case InputField.CompositeColumnField ignored -> "input composite column";
            case InputField.CompositeColumnReferenceField ignored -> "input composite reference column";
            case InputField.NestingField ignored -> "input nesting field";

            // --- Unclassified ---
            case GraphitronField.UnclassifiedField f -> "unclassified (" + f.kind().displayName() + ")";
        };
    }

    /**
     * Label for a {@link FieldClassification} projection — used when the LSP only has the
     * projection in hand (no access to the model permit). The projection carries enough
     * information to distinguish broad categories; falls back to {@code "field"} for the
     * generic case. Prefer {@link #fieldLabel(GraphitronField)} when the model permit is
     * available.
     */
    public static String projectionLabel(FieldClassification classification) {
        return switch (classification) {
            case FieldClassification.Column ignored -> "column";
            case FieldClassification.ColumnReference ignored -> "reference column";
            case FieldClassification.ParticipantCrossTable ignored -> "discriminated reference column";
            case FieldClassification.CompositeColumn ignored -> "composite column";
            case FieldClassification.CompositeColumnReference ignored -> "composite reference column";
            case FieldClassification.TableTarget t -> labelFor(t);
            case FieldClassification.RecordTableTarget t -> t.hasLookupKey() ? "record lookup table field" : "record table field";
            case FieldClassification.TableMethod t -> t.recordParent() ? "record table method field" : "table method field";
            case FieldClassification.TableInterface ignored -> "table interface field";
            case FieldClassification.Polymorphic ignored -> "interface/union field";
            case FieldClassification.Nesting ignored -> "nesting field";
            case FieldClassification.Constructor ignored -> "constructor field";
            case FieldClassification.ServiceBacked s -> s.tableBound() ? "service table field" : "service record field";
            case FieldClassification.RecordOrProperty ignored -> "record/property field";
            case FieldClassification.Computed ignored -> "computed field";
            case FieldClassification.Errors ignored -> "errors field";
            case FieldClassification.SingleRecordTable ignored -> "single record table field";
            case FieldClassification.SingleRecordIdFromReturning ignored -> "single record id field (RETURNING)";
            case FieldClassification.SingleRecordTableFromReturning ignored -> "single record table field (RETURNING)";
            case FieldClassification.QueryTable q -> q.isLookup() ? "query lookup table field" : "query table field";
            case FieldClassification.QueryTableMethod ignored -> "query table-method field";
            case FieldClassification.QueryNode q -> q.isList() ? "query nodes field" : "query node field";
            case FieldClassification.QueryTableInterface ignored -> "query table-interface field";
            case FieldClassification.QueryPolymorphic ignored -> "query interface/union field";
            case FieldClassification.QueryService s -> s.tableBound() ? "query service-table field" : "query service-record field";
            case FieldClassification.DmlMutation dml -> dmlVerbLabel(dml.kind()) + " mutation";
            case FieldClassification.MutationService s -> s.tableBound() ? "service table mutation" : "service record mutation";
            case FieldClassification.DmlRecord r -> (r.bulk() ? "bulk " : "") + "DML record mutation";
            case FieldClassification.Unclassified u -> "unclassified (" + u.reason() + ")";
        };
    }

    private static String labelFor(FieldClassification.TableTarget t) {
        if (t.splitBatched() && t.hasLookupKey()) return "split lookup table field";
        if (t.splitBatched()) return "split table field";
        if (t.hasLookupKey()) return "lookup table field";
        return "table field";
    }

    private static String dmlVerbLabel(DmlKind kind) {
        return switch (kind) {
            case INSERT -> "insert";
            case UPDATE -> "update";
            case DELETE -> "delete";
            case UPSERT -> "upsert";
        };
    }

    /**
     * Label for a classified type, dispatched on the generator-side permit. Exhaustive
     * over {@code GraphitronType}'s sealed permit set.
     */
    public static String typeLabel(GraphitronType type) {
        return switch (type) {
            case GraphitronType.TableType ignored -> "table type";
            case GraphitronType.NodeType ignored -> "node type";
            case GraphitronType.TableInterfaceType ignored -> "table interface";
            case GraphitronType.InterfaceType ignored -> "interface";
            case GraphitronType.UnionType ignored -> "union";
            case GraphitronType.JavaRecordType ignored -> "java record type";
            case GraphitronType.JavaRecordInputType ignored -> "java record input";
            case GraphitronType.JooqRecordType ignored -> "jooq record type";
            case GraphitronType.JooqRecordInputType ignored -> "jooq record input";
            case GraphitronType.JooqTableRecordType ignored -> "jooq table-record type";
            case GraphitronType.JooqTableRecordInputType ignored -> "jooq table-record input";
            case GraphitronType.PojoResultType.Backed ignored -> "pojo result type";
            case GraphitronType.PojoResultType.NoBacking ignored -> "unbacked pojo result type";
            case GraphitronType.PojoInputType ignored -> "pojo input";
            case GraphitronType.TableInputType ignored -> "table input";
            case GraphitronType.RootType t -> "root (" + t.name().toLowerCase() + ")";
            case GraphitronType.ConnectionType ignored -> "connection type";
            case GraphitronType.EdgeType ignored -> "edge type";
            case GraphitronType.PageInfoType ignored -> "page info";
            case GraphitronType.ErrorType ignored -> "error type";
            case GraphitronType.EnumType ignored -> "enum";
            case GraphitronType.ScalarType ignored -> "scalar";
            case GraphitronType.PlainObjectType ignored -> "plain object";
            case GraphitronType.UnclassifiedType t -> "unclassified (" + t.reason() + ")";
        };
    }

    /**
     * Label for a {@link TypeClassification} projection. Falls back to a generic label
     * when the projection alone doesn't distinguish the operation name; prefer
     * {@link #typeLabel(GraphitronType)} when the model permit is available.
     */
    public static String projectionTypeLabel(TypeClassification classification) {
        return switch (classification) {
            case TypeClassification.Table ignored -> "table type";
            case TypeClassification.Node ignored -> "node type";
            case TypeClassification.TableInterface ignored -> "table interface";
            case TypeClassification.Interface ignored -> "interface";
            case TypeClassification.Union ignored -> "union";
            case TypeClassification.JavaRecord ignored -> "java record type";
            case TypeClassification.JavaRecordInput ignored -> "java record input";
            case TypeClassification.JooqRecord ignored -> "jooq record type";
            case TypeClassification.JooqRecordInput ignored -> "jooq record input";
            case TypeClassification.JooqTableRecord ignored -> "jooq table-record type";
            case TypeClassification.JooqTableRecordInput ignored -> "jooq table-record input";
            case TypeClassification.PojoResult ignored -> "pojo result type";
            case TypeClassification.UnbackedPojoResult ignored -> "unbacked pojo result type";
            case TypeClassification.PojoInput ignored -> "pojo input";
            case TypeClassification.TableInput ignored -> "table input";
            case TypeClassification.Root r -> "root (" + r.operation().toLowerCase() + ")";
            case TypeClassification.Connection ignored -> "connection type";
            case TypeClassification.Edge ignored -> "edge type";
            case TypeClassification.PageInfo ignored -> "page info";
            case TypeClassification.Error ignored -> "error type";
            case TypeClassification.Enum ignored -> "enum";
            case TypeClassification.Scalar ignored -> "scalar";
            case TypeClassification.PlainObject ignored -> "plain object";
            case TypeClassification.Unclassified u -> "unclassified (" + u.reason() + ")";
        };
    }
}
