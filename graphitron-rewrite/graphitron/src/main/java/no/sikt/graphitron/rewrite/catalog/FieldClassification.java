package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.model.DmlKind;

import java.util.List;

/**
 * LSP-facing projection of a {@link no.sikt.graphitron.rewrite.model.GraphitronField}'s
 * classified variant. The LSP's inlay-hint and hover arms consume this to render
 * classification information at SDL field declarations and at the directive sites where
 * a canonical argument was inferred. Carried alongside {@link TypeBackingShape} on
 * {@link LspSchemaSnapshot.Built}.
 *
 * <p>Sized to <b>distinct hover-payload shapes</b>, not 1:1 with the generator-side
 * {@code GraphitronField} permits. Permits that differ only in a label dimension
 * (DML verb, single/list multiplicity, split-batch toggle, lookup-key toggle) collapse
 * onto one record carrying that dimension as a discriminator field. Permits whose
 * hover-relevant payload genuinely diverges keep their own record.
 *
 * <p>The producer-side exhaustive switch in
 * {@link CatalogBuilder#projectFieldClassification} enforces <em>coverage</em>: a new
 * permit in {@code ChildField} / {@code QueryField} / {@code MutationField} /
 * {@code InputField} fails the switch to compile until mapped. What this projection
 * collapses is the LSP-side cardinality; the label switch in
 * {@code LspClassificationLabels} still dispatches over the full generator-side permit
 * set.
 *
 * <p>Each record carries only LSP-renderable payload (table names, column names, FK
 * names, target type names, error-channel constants, primitive flags). No
 * {@code TableRef} / {@code ColumnRef} / {@code graphql-java} types reach the LSP module.
 * Label strings are not on the projection record; rendering lives in the LSP module as
 * a sibling switch.
 */
public sealed interface FieldClassification
    permits FieldClassification.Column,
            FieldClassification.ColumnReference,
            FieldClassification.CompositeColumn,
            FieldClassification.CompositeColumnReference,
            FieldClassification.ParticipantCrossTable,
            FieldClassification.TableTarget,
            FieldClassification.RecordTableTarget,
            FieldClassification.TableMethod,
            FieldClassification.TableInterface,
            FieldClassification.Polymorphic,
            FieldClassification.Nesting,
            FieldClassification.Constructor,
            FieldClassification.ServiceBacked,
            FieldClassification.RecordOrProperty,
            FieldClassification.Computed,
            FieldClassification.InputCondition,
            FieldClassification.Errors,
            FieldClassification.SingleRecordTable,
            FieldClassification.SingleRecordIdFromReturning,
            FieldClassification.SingleRecordTableFromReturning,
            FieldClassification.QueryTable,
            FieldClassification.QueryTableMethod,
            FieldClassification.QueryNode,
            FieldClassification.QueryTableInterface,
            FieldClassification.QueryPolymorphic,
            FieldClassification.QueryService,
            FieldClassification.DmlMutation,
            FieldClassification.MutationService,
            FieldClassification.DmlRecord,
            FieldClassification.Unclassified {

    /**
     * One step in a join path, identifying the FK and the target table. Rendered as
     * part of {@link ColumnReference} / {@link TableTarget} / {@link RecordTableTarget}
     * hover content.
     */
    record FkStep(String targetTableName, String fkName) {}

    // ===== Column-bearing fields =====

    /**
     * A single-column projection on a {@code @table}-backed parent. Covers
     * {@code ChildField.ColumnField} and {@code InputField.ColumnField}; the input/output
     * label dimension is dispatched by the label switch, not by the record identity.
     */
    record Column(String tableName, String columnName) implements FieldClassification {}

    /**
     * A single-column projection reached through a {@code @reference} join path. Covers
     * {@code ChildField.ColumnReferenceField} and {@code InputField.ColumnReferenceField}.
     */
    record ColumnReference(String tableName, String columnName, List<FkStep> joinPath)
        implements FieldClassification {

        public ColumnReference {
            joinPath = List.copyOf(joinPath);
        }
    }

    /**
     * A multi-column projection on a {@code @table}-backed parent. Covers
     * {@code ChildField.CompositeColumnField} and {@code InputField.CompositeColumnField}.
     */
    record CompositeColumn(String tableName, List<String> columnNames)
        implements FieldClassification {

        public CompositeColumn {
            columnNames = List.copyOf(columnNames);
        }
    }

    /**
     * A multi-column projection reached through a {@code @reference} join path. Covers
     * {@code ChildField.CompositeColumnReferenceField} and
     * {@code InputField.CompositeColumnReferenceField}.
     */
    record CompositeColumnReference(
        String tableName, List<String> columnNames, List<FkStep> joinPath
    ) implements FieldClassification {

        public CompositeColumnReference {
            columnNames = List.copyOf(columnNames);
            joinPath = List.copyOf(joinPath);
        }
    }

    /**
     * A scalar field on a {@code @table}-interface participant reached via a single-hop
     * {@code @reference} to a different table. Covers
     * {@code ChildField.ParticipantColumnReferenceField}; the participant-cross-table
     * shape (target table + column + FK constant + projection alias) is hover-distinct
     * from the broader {@link ColumnReference} payload, so this record stays separate.
     */
    record ParticipantCrossTable(
        String targetTableName, String columnName, String fkName, String alias
    ) implements FieldClassification {}

    // ===== Table-target child fields =====

    /**
     * A child field that navigates to (or stays at) a table scope and generates SQL.
     * Covers the four {@code ChildField.TableField} family permits ({@code TableField},
     * {@code SplitTableField}, {@code LookupTableField}, {@code SplitLookupTableField})
     * plus {@code TableInterfaceField} which adds the polymorphic axes (see
     * {@link TableInterface} for the polymorphic-only payload). The {@code splitBatched}
     * and {@code hasLookupKey} booleans encode the per-permit axes; the label switch
     * still emits a per-permit label.
     */
    record TableTarget(
        String tableName, List<FkStep> joinPath, boolean splitBatched, boolean hasLookupKey
    ) implements FieldClassification {

        public TableTarget {
            joinPath = List.copyOf(joinPath);
        }
    }

    /**
     * A child field on a {@code @record} parent that resolves to a table-bound target
     * via a DataLoader. Covers {@code ChildField.RecordTableField} and
     * {@code ChildField.RecordLookupTableField}.
     */
    record RecordTableTarget(
        String tableName, List<FkStep> joinPath, boolean hasLookupKey
    ) implements FieldClassification {

        public RecordTableTarget {
            joinPath = List.copyOf(joinPath);
        }
    }

    /**
     * A child field using {@code @tableMethod}. Covers {@code ChildField.TableMethodField}
     * and {@code ChildField.RecordTableMethodField}; the {@code recordParent} flag
     * encodes the parent-shape axis.
     */
    record TableMethod(
        String tableName, String methodClassName, String methodName, boolean recordParent
    ) implements FieldClassification {}

    // ===== Polymorphic =====

    /**
     * Single-table polymorphic field. Covers {@code ChildField.TableInterfaceField}
     * (carries discriminator column + participants on a single shared table).
     */
    record TableInterface(
        String tableName, String discriminatorColumn, List<String> participantTypeNames
    ) implements FieldClassification {

        public TableInterface {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * Multi-table polymorphic field. Covers {@code ChildField.InterfaceField} and
     * {@code ChildField.UnionField}; the source axis (interface implementer set vs
     * union member set) is encoded by the per-permit label, not by the record identity.
     */
    record Polymorphic(List<String> participantTypeNames) implements FieldClassification {

        public Polymorphic {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    // ===== Other child-field permits =====

    /**
     * Nesting fragment: the field's value is a sub-projection of the parent's
     * table-bound shape (no SQL navigation). Covers {@code ChildField.NestingField} and
     * {@code InputField.NestingField}; the input/output dimension is dispatched by the
     * label switch.
     */
    record Nesting() implements FieldClassification {}

    /**
     * The field's value is the parent itself, propagated through to a {@code @record}-
     * typed child. Covers {@code ChildField.ConstructorField}.
     */
    record Constructor() implements FieldClassification {}

    /**
     * A child field backed by a developer-provided {@code @service} method. Covers
     * {@code ChildField.ServiceTableField} (tableBound = true, tableName non-null) and
     * {@code ChildField.ServiceRecordField} (tableBound = false, tableName null).
     */
    record ServiceBacked(
        String methodClassName, String methodName, boolean tableBound, String tableName,
        String errorChannelMappingName
    ) implements FieldClassification {}

    /**
     * A {@code @record}-parent field whose value reaches the field through a parent
     * column or accessor. Covers {@code ChildField.RecordField} and
     * {@code ChildField.PropertyField}; either component may be null when the parent
     * shape doesn't carry that resolution kind.
     */
    record RecordOrProperty(String columnName, String accessorName) implements FieldClassification {}

    /**
     * A child field using {@code @externalField} — a developer-supplied static method
     * returning a jOOQ {@code Field<X>} inlined into the parent's projection at emit
     * time. Covers {@code ChildField.ComputedField}.
     */
    record Computed(String methodClassName, String methodName) implements FieldClassification {}

    /**
     * An input field whose only emission is an explicit {@code @condition(override: true)}
     * method call; no column binding is recorded because {@code override: true} suppresses the
     * implicit predicate by construction (R210). Covers {@code InputField.ConditionOnlyField}.
     */
    record InputCondition(String methodClassName, String methodName) implements FieldClassification {}

    /**
     * The {@code errors} field on a payload type, listing the mapped {@code @error}
     * types in source order. Covers {@code ChildField.ErrorsField}.
     */
    record Errors(List<String> errorTypeNames) implements FieldClassification {

        public Errors {
            errorTypeNames = List.copyOf(errorTypeNames);
        }
    }

    // ===== Single-record carrier data fields (R75 / R156) =====

    /**
     * The single data field on a single-record DML payload carrier whose element is a
     * {@code @table}-bound type. Covers {@code ChildField.SingleRecordTableField}.
     */
    record SingleRecordTable(String tableName) implements FieldClassification {}

    /**
     * The single data field on a payload-returning DELETE carrier where the data field
     * is an ID-typed scalar encoding the deleted row's primary key. Covers
     * {@code ChildField.SingleRecordIdFieldFromReturning}.
     */
    record SingleRecordIdFromReturning() implements FieldClassification {}

    /**
     * The single data field on a payload-returning DELETE carrier where the data field
     * is a {@code @table}-element projected from the PK-only RETURNING record. Covers
     * {@code ChildField.SingleRecordTableFieldFromReturning}.
     */
    record SingleRecordTableFromReturning(String tableName) implements FieldClassification {}

    // ===== Query fields =====

    /**
     * A root query field returning a {@code @table}-bound type. Covers
     * {@code QueryField.QueryTableField} and {@code QueryField.QueryLookupTableField};
     * the lookup-helper axis is encoded by {@code isLookup}.
     */
    record QueryTable(String tableName, boolean isLookup) implements FieldClassification {}

    /**
     * A root query field bound to a developer-authored {@code @tableMethod}. Covers
     * {@code QueryField.QueryTableMethodTableField}.
     */
    record QueryTableMethod(
        String tableName, String methodClassName, String methodName
    ) implements FieldClassification {}

    /**
     * A root query field implementing Relay's {@code node(id:)} or {@code nodes(ids:)}.
     * Covers {@code QueryField.QueryNodeField} ({@code isList = false}) and
     * {@code QueryField.QueryNodesField} ({@code isList = true}).
     */
    record QueryNode(boolean isList) implements FieldClassification {}

    /**
     * A root query field returning a single-table interface. Covers
     * {@code QueryField.QueryTableInterfaceField}.
     */
    record QueryTableInterface(
        String tableName, String discriminatorColumn, List<String> participantTypeNames
    ) implements FieldClassification {

        public QueryTableInterface {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * A root query field returning a multi-table interface or union. Covers
     * {@code QueryField.QueryInterfaceField} and {@code QueryField.QueryUnionField}.
     */
    record QueryPolymorphic(List<String> participantTypeNames) implements FieldClassification {

        public QueryPolymorphic {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * A root query field backed by a developer-provided {@code @service} method.
     * Covers {@code QueryField.QueryServiceTableField} ({@code tableBound = true}) and
     * {@code QueryField.QueryServiceRecordField} ({@code tableBound = false}).
     */
    record QueryService(
        String methodClassName, String methodName, boolean tableBound, String tableName,
        String errorChannelMappingName
    ) implements FieldClassification {}

    // ===== Mutation fields =====

    /**
     * A mutation field bound to a {@code @table} input arg that emits direct DML and
     * returns the {@code @table}-bound type. Covers the four
     * {@code MutationField.DmlTableField} permits via the {@link DmlKind} discriminator.
     */
    record DmlMutation(
        String tableName, String inputTypeName, DmlKind kind, String errorChannelMappingName
    ) implements FieldClassification {}

    /**
     * A mutation field backed by a developer-provided {@code @service} method. Covers
     * {@code MutationField.MutationServiceTableField} ({@code tableBound = true},
     * {@code tableName} non-null) and {@code MutationField.MutationServiceRecordField}
     * ({@code tableBound = false}, {@code tableName} null). Mirrors {@link QueryService}'s
     * payload shape so the hover surface renders the target table for table-bound service
     * mutations.
     */
    record MutationService(
        String methodClassName, String methodName, boolean tableBound, String tableName,
        String errorChannelMappingName
    ) implements FieldClassification {}

    /**
     * A record-returning DML mutation. Covers {@code MutationField.MutationDmlRecordField}
     * ({@code bulk = false}) and {@code MutationField.MutationBulkDmlRecordField}
     * ({@code bulk = true}); the bulk-input axis is encoded by the boolean.
     */
    record DmlRecord(
        String tableName, String inputTypeName, DmlKind kind, boolean bulk,
        String errorChannelMappingName
    ) implements FieldClassification {}

    // ===== Unclassified =====

    /**
     * A field the classifier could not assign a variant to. Covers
     * {@code GraphitronField.UnclassifiedField}. The {@code reason} is the human-readable
     * rejection message; rendering decides whether to surface it.
     */
    record Unclassified(String reason) implements FieldClassification {}
}
