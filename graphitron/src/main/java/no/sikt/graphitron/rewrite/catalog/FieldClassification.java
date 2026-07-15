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
 *
 * <p><b>R217: projection-record simple names are also user-visible.</b> {@code
 * LspClassificationLabels.projectionLabel} returns each permit's simple name verbatim,
 * and {@code DeclarationHovers} prints {@code FieldClassification.<name>} in hover
 * headers. Renaming a permit (say, {@code TableTarget} to {@code JoinedColumnTarget})
 * is therefore <em>also</em> a user-visible-string change touching docs, screenshots,
 * and tutorials, not a purely internal refactor. R217 accepts that coupling as the
 * mechanism that lets the LSP teach the model.
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
            FieldClassification.ServiceBacked,
            FieldClassification.RecordOrProperty,
            FieldClassification.Computed,
            FieldClassification.InputUnbound,
            FieldClassification.Errors,
            FieldClassification.SingleRecordId,
            FieldClassification.SingleRecordIdFromReturning,
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

    /**
     * LSP-arm dispatch projection: collapses the 30 {@link FieldClassification} permits
     * onto three audience-specific arms the {@code @field(name:)}-shaped
     * {@code CatalogColumnBinding} consumers all read off ({@code FieldCompletions},
     * {@code Diagnostics.validateFieldMember}, {@code Hovers.columnHover}). Routed through
     * {@link #lspColumnDispatch()}.
     *
     * <p>{@link Resolve} carries the table whose columns to use for completion / hover /
     * validation: the {@code @reference} terminal table for the four column-bearing
     * permits, and (R343) the navigated child/element table for {@code TableTarget} /
     * {@code RecordTableTarget}, where {@code @defaultOrder(fields: [{name: ...}])} names a
     * column on that element table rather than the enclosing type's {@code @table}. {@link
     * Silent} signals "the LSP should not surface a candidate or
     * diagnostic" (a duplicate diagnostic with the wrong table would be noise for
     * {@code InputUnbound}; an unclassified field has nothing useful to render). {@link
     * FallThrough} means the LSP arm falls back to its existing backing-driven dispatch
     * ({@code typesByName().get(...)}), which is how non-column-bearing permits like
     * {@code Nesting}, {@code ServiceBacked}, {@code DmlMutation}, etc. resolve today.
     *
     * <p>The name commits to the LSP audience because the {@link Silent} semantics
     * ({@code InputUnbound} = "no diagnostic", not "no value") are LSP-shaped. The
     * {@link Resolve} / {@link FallThrough} axis itself is just "column-bearing or
     * not" and would be reusable, but a future non-LSP consumer should add its own
     * audience-specific projection on top rather than route through this one and
     * inherit the LSP-shaped silence policy.
     */
    sealed interface LspColumnDispatch
        permits LspColumnDispatch.Resolve, LspColumnDispatch.Silent, LspColumnDispatch.FallThrough {

        /** Resolve {@code @field(name:)} candidates / hover / validation against {@code tableName}. */
        record Resolve(String tableName) implements LspColumnDispatch {}

        /** No LSP signal for this classification (e.g. {@code InputUnbound}, {@code Unclassified}). */
        record Silent() implements LspColumnDispatch {}

        /** Fall through to the consumer's existing backing-driven dispatch. */
        record FallThrough() implements LspColumnDispatch {}
    }

    /**
     * Projects this classification onto an {@link LspColumnDispatch} arm for the LSP's
     * {@code @field(name:)}-shaped {@code CatalogColumnBinding} consumers. The switch
     * is exhaustive over the sealed permit list and carries no {@code default} arm; a
     * new permit added to {@link FieldClassification} fails this method to compile and
     * forces the implementer to place the new variant in one of the three arms
     * deliberately, in one place, ahead of any consumer-side switch.
     */
    default LspColumnDispatch lspColumnDispatch() {
        return switch (this) {
            case Column c                       -> new LspColumnDispatch.Resolve(c.tableName());
            case ColumnReference c              -> new LspColumnDispatch.Resolve(c.tableName());
            case CompositeColumn c              -> new LspColumnDispatch.Resolve(c.tableName());
            case CompositeColumnReference c     -> new LspColumnDispatch.Resolve(c.tableName());
            case ParticipantCrossTable c        -> new LspColumnDispatch.Resolve(c.targetTableName());
            // R343: a list/connection field navigating to a child table carries that child
            // (element) table in its tableName. @defaultOrder(fields: [{name: ...}]) at such a
            // field names a column on the element table, not the enclosing type's @table, so
            // these resolve their own target table the same way the @reference permits above do.
            case TableTarget c                  -> new LspColumnDispatch.Resolve(c.tableName());
            case RecordTableTarget c            -> new LspColumnDispatch.Resolve(c.tableName());
            case InputUnbound _                 -> new LspColumnDispatch.Silent();
            case Unclassified _                 -> new LspColumnDispatch.Silent();
            case TableMethod _,
                 TableInterface _,
                 Polymorphic _,
                 Nesting _,
                 ServiceBacked _,
                 RecordOrProperty _,
                 Computed _,
                 Errors _,
                 SingleRecordId _,
                 SingleRecordIdFromReturning _,
                 QueryTable _,
                 QueryTableMethod _,
                 QueryNode _,
                 QueryTableInterface _,
                 QueryPolymorphic _,
                 QueryService _,
                 DmlMutation _,
                 MutationService _,
                 DmlRecord _                    -> new LspColumnDispatch.FallThrough();
        };
    }

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
     * the Table-sourced {@code BatchedTableField} arm, {@code LookupTableField}, {@code SplitLookupTableField})
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
     * A child field on a class-backed parent that resolves to a table-bound target
     * via a DataLoader. Covers the record-sourced {@code ChildField.BatchedTableField} arm and
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
     * A child field backed by a developer-provided {@code @service} method. Covers
     * {@code ChildField.ServiceTableField} (tableBound = true, tableName non-null) and
     * {@code ChildField.ServiceRecordField} (tableBound = false, tableName null).
     */
    record ServiceBacked(
        String methodClassName, String methodName, boolean tableBound, String tableName,
        String errorChannelMappingName
    ) implements FieldClassification {}

    /**
     * A class-backed-parent field whose value reaches the field through a parent
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
     * An input field that does not bind to a SQL column (R215). Covers {@code InputField.UnboundField}.
     * {@code methodClassName} / {@code methodName} are populated when the carrier has an explicit
     * {@code @condition}; {@code override} reflects the directive flag. All three are {@code null}/
     * {@code false} when the carrier has no condition at all (the cascade-admitted bare-field case).
     */
    record InputUnbound(String methodClassName, String methodName, boolean override) implements FieldClassification {}

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
     * The single data field on a payload-returning DELETE carrier where the data field
     * is an ID-typed scalar encoding the deleted row's primary key. Covers
     * {@code ChildField.SingleRecordIdFieldFromReturning}.
     */
    record SingleRecordIdFromReturning() implements FieldClassification {}

    /**
     * The single data field on an {@code @service} source-record carrier where the data field
     * is an ID-typed scalar encoding the producer record's node-key column(s), with no
     * follow-up SELECT. Covers {@code ChildField.SingleRecordIdField}.
     */
    record SingleRecordId(String tableName) implements FieldClassification {}

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
