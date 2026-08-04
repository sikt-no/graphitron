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
 * {@code GraphitronField} permits. Classifications that differ only in a discriminator
 * dimension (the write verb, single/list multiplicity, the split-batch or lookup-key
 * axis) collapse onto one record carrying that dimension as a payload field. Permits
 * whose hover-relevant payload genuinely diverges keep their own record.
 *
 * <p>The producer-side exhaustive switch in
 * {@link CatalogBuilder#projectFieldClassification} enforces <em>coverage</em>: a new
 * permit in {@code ChildField} / {@code QueryField} / {@code MutationField} /
 * {@code InputField} fails the switch to compile until mapped. That switch and the
 * {@code @ProjectionFor} coverage pins are the two compile-checked homes of the
 * leaf-to-record mapping, so record javadoc below does not restate which permits reach
 * which record. The label switch in {@code LspClassificationLabels} dispatches over
 * this projection's own sealed permit set.
 *
 * <p>Each record carries only LSP-renderable payload (table names, column names, FK
 * names, target type names, error-channel constants, primitive flags). No
 * {@code TableRef} / {@code ColumnRef} / {@code graphql-java} types reach the LSP module.
 * Label strings are not on the projection record; rendering lives in the LSP module as
 * a sibling switch.
 *
 * <p><b>Projection-record simple names are also user-visible.</b> {@code
 * LspClassificationLabels.projectionLabel} returns each permit's simple name verbatim,
 * and {@code DeclarationHovers} prints {@code FieldClassification.<name>} in hover
 * headers. Renaming a permit is therefore <em>also</em> a user-visible-string change
 * touching docs and tutorials, not a purely internal refactor; the coupling is accepted
 * as the mechanism that lets the LSP teach the model.
 */
public sealed interface FieldClassification
    permits FieldClassification.Column,
            FieldClassification.ColumnReference,
            FieldClassification.CompositeColumn,
            FieldClassification.CompositeColumnReference,
            FieldClassification.ParticipantCrossTable,
            FieldClassification.TableTarget,
            FieldClassification.RecordTableTarget,
            FieldClassification.TableInterface,
            FieldClassification.Polymorphic,
            FieldClassification.Nesting,
            FieldClassification.Pivot,
            FieldClassification.ServiceBacked,
            FieldClassification.RecordOrProperty,
            FieldClassification.Computed,
            FieldClassification.InputUnbound,
            FieldClassification.Errors,
            FieldClassification.SingleRecordId,
            FieldClassification.SingleRecordIdFromReturning,
            FieldClassification.QueryTable,
            FieldClassification.RoutineBacked,
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
     * LSP-arm dispatch projection: collapses the {@link FieldClassification} permits
     * onto three audience-specific arms the {@code @field(name:)}-shaped
     * {@code CatalogColumnBinding} consumers all read off ({@code FieldCompletions},
     * {@code Diagnostics.validateFieldMember}, {@code Hovers.columnHover}). Routed through
     * {@link #lspColumnDispatch()}.
     *
     * <p>{@link Resolve} carries the table whose columns to use for completion / hover /
     * validation: the {@code @reference} terminal table for the column-bearing permits,
     * and the navigated child/element table for {@code TableTarget} /
     * {@code RecordTableTarget}, where {@code @defaultOrder(fields: [{name: ...}])} names a
     * column on that element table rather than the enclosing type's {@code @table}.
     * {@link Silent} signals "the LSP should not surface a candidate or diagnostic"
     * (a duplicate diagnostic with the wrong table would be noise for
     * {@code InputUnbound}; an unclassified field has nothing useful to render).
     * {@link FallThrough} means the consumer falls back to its existing backing-driven
     * dispatch ({@code typesByName().get(...)}).
     *
     * <p>The name commits to the LSP audience because the {@link Silent} semantics
     * ({@code InputUnbound} = "no diagnostic", not "no value") are LSP-shaped; a
     * non-LSP consumer adds its own audience-specific projection rather than inherit
     * the LSP-shaped silence policy through this one.
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
            // tableName is the navigated child/element table; see LspColumnDispatch.
            case TableTarget c                  -> new LspColumnDispatch.Resolve(c.tableName());
            case RecordTableTarget c            -> new LspColumnDispatch.Resolve(c.tableName());
            case InputUnbound _                 -> new LspColumnDispatch.Silent();
            case Unclassified _                 -> new LspColumnDispatch.Silent();
            case TableInterface _,
                 Polymorphic _,
                 Nesting _,
                 Pivot _,
                 ServiceBacked _,
                 RecordOrProperty _,
                 Computed _,
                 Errors _,
                 SingleRecordId _,
                 SingleRecordIdFromReturning _,
                 QueryTable _,
                 RoutineBacked _,
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
     * A single-column projection on a {@code @table}-backed parent; input and output
     * fields project alike (the SDL declaration site the hover sits on shows which one
     * the reader is looking at).
     */
    record Column(String tableName, String columnName) implements FieldClassification {}

    /**
     * A single-column projection reached through a {@code @reference} join path.
     */
    record ColumnReference(String tableName, String columnName, List<FkStep> joinPath)
        implements FieldClassification {

        public ColumnReference {
            joinPath = List.copyOf(joinPath);
        }
    }

    /**
     * A multi-column projection on a {@code @table}-backed parent. Denormalized view:
     * the merged leaf carries arity as a column count, and the projection derives this
     * variant from its {@code isComposite()} accessor, keeping the wire surface stable.
     */
    record CompositeColumn(String tableName, List<String> columnNames)
        implements FieldClassification {

        public CompositeColumn {
            columnNames = List.copyOf(columnNames);
        }
    }

    /**
     * A multi-column projection reached through a {@code @reference} join path.
     * Denormalized view derived from the merged leaf's {@code isComposite()} accessor,
     * like {@link CompositeColumn}.
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
     * {@code @reference} to a different table. The participant-cross-table shape
     * (target table + column + FK constant + projection alias) is hover-distinct
     * from the broader {@link ColumnReference} payload, so this record stays separate.
     */
    record ParticipantCrossTable(
        String targetTableName, String columnName, String fkName, String alias
    ) implements FieldClassification {}

    // ===== Table-target child fields =====

    /**
     * A child field that navigates to (or stays at) a table scope and generates SQL.
     * The {@code splitBatched} and {@code hasLookupKey} booleans carry the delivery and
     * lookup axes as payload facts.
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
     * via a DataLoader; {@code hasLookupKey} carries the lookup axis.
     */
    record RecordTableTarget(
        String tableName, List<FkStep> joinPath, boolean hasLookupKey
    ) implements FieldClassification {

        public RecordTableTarget {
            joinPath = List.copyOf(joinPath);
        }
    }

    // ===== Polymorphic =====

    /**
     * Single-table polymorphic field: discriminator column + participants on a single
     * shared table.
     */
    record TableInterface(
        String tableName, String discriminatorColumn, List<String> participantTypeNames
    ) implements FieldClassification {

        public TableInterface {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * Multi-table polymorphic field. The interface-vs-union distinction is not on the
     * record; the SDL type declaration the hover sits on already carries it.
     */
    record Polymorphic(List<String> participantTypeNames) implements FieldClassification {

        public Polymorphic {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    // ===== Other child-field permits =====

    /**
     * Nesting fragment: the field's value is a sub-projection of the parent's
     * table-bound shape (no SQL navigation); input and output nesting fields project
     * alike.
     */
    record Nesting() implements FieldClassification {}

    /**
     * Discriminator-keyed aggregate projection ({@code @pivot}): the field pivots the attribute
     * table's {@code (owner-key…, discriminator, value)} rows into one filtered aggregate per
     * slot of its plain return type. Carries the attribute table and the two pivot columns for
     * hover rendering; {@code batched} mirrors the {@code @splitQuery} delivery fork.
     */
    record Pivot(String tableName, String onColumn, String valueColumn, boolean batched)
        implements FieldClassification {}

    /**
     * A child field backed by a developer-provided {@code @service} method;
     * {@code tableBound} distinguishes the table-bound return ({@code tableName}
     * non-null) from the record-backed one ({@code tableName} null).
     */
    record ServiceBacked(
        String methodClassName, String methodName, boolean tableBound, String tableName,
        String errorChannelMappingName
    ) implements FieldClassification {}

    /**
     * A record-backed-parent field whose value reaches the field through a parent
     * column or accessor, including the record-backed passthrough shapes (no column,
     * no accessor); either component may be null when the read doesn't carry that
     * resolution kind.
     */
    record RecordOrProperty(String columnName, String accessorName) implements FieldClassification {}

    /**
     * A child field using {@code @externalField}: a developer-supplied static method
     * returning a jOOQ {@code Field<X>} inlined into the parent's projection at emit
     * time.
     */
    record Computed(String methodClassName, String methodName) implements FieldClassification {}

    /**
     * An input field that does not bind to a SQL column.
     * {@code methodClassName} / {@code methodName} are populated when the carrier has an explicit
     * {@code @condition}; {@code override} reflects the directive flag. All three are {@code null}/
     * {@code false} when the carrier has no condition at all (the cascade-admitted bare-field case).
     */
    record InputUnbound(String methodClassName, String methodName, boolean override) implements FieldClassification {}

    /**
     * The {@code errors} field on a payload type, listing the mapped {@code @error}
     * types in source order.
     */
    record Errors(List<String> errorTypeNames) implements FieldClassification {

        public Errors {
            errorTypeNames = List.copyOf(errorTypeNames);
        }
    }

    // ===== Single-record carrier data fields =====

    /**
     * The single data field on a payload-returning DELETE carrier where the data field
     * is an ID-typed scalar encoding the deleted row's primary key.
     */
    record SingleRecordIdFromReturning() implements FieldClassification {}

    /**
     * The single data field on an {@code @service} source-record carrier where the data field
     * is an ID-typed scalar encoding the producer record's node-key column(s), with no
     * follow-up SELECT.
     */
    record SingleRecordId(String tableName) implements FieldClassification {}

    // ===== Query fields =====

    /**
     * A root query field returning a {@code @table}-bound type; the lookup-helper axis
     * is carried by {@code isLookup}.
     */
    record QueryTable(String tableName, boolean isLookup) implements FieldClassification {}

    /**
     * A root field whose rows come from a generated jOOQ {@code Routines}-class method
     * call, on the read or the write side; {@code methodClassName} is the generated
     * {@code Routines} class, so hover and jump-to-source route to the routine's call
     * surface.
     */
    record RoutineBacked(
        String tableName, String methodClassName, String methodName
    ) implements FieldClassification {}

    /**
     * A root query field implementing Relay's {@code node(id:)} or {@code nodes(ids:)};
     * {@code isList} distinguishes them.
     */
    record QueryNode(boolean isList) implements FieldClassification {}

    /**
     * A root query field returning a single-table interface.
     */
    record QueryTableInterface(
        String tableName, String discriminatorColumn, List<String> participantTypeNames
    ) implements FieldClassification {

        public QueryTableInterface {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * A root query field returning a multi-table interface or union.
     */
    record QueryPolymorphic(List<String> participantTypeNames) implements FieldClassification {

        public QueryPolymorphic {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * A root query field backed by a developer-provided {@code @service} method;
     * {@code tableBound} distinguishes the table-bound return from the record-backed
     * (and polymorphic) ones.
     */
    record QueryService(
        String methodClassName, String methodName, boolean tableBound, String tableName,
        String errorChannelMappingName
    ) implements FieldClassification {}

    // ===== Mutation fields =====

    /**
     * A mutation field bound to a DML input arg that emits direct DML and returns the
     * {@code @table}-bound type; the write verb projects off the carried write arm onto
     * the {@link DmlKind} discriminator.
     */
    record DmlMutation(
        String tableName, String inputTypeName, DmlKind kind, String errorChannelMappingName
    ) implements FieldClassification {}

    /**
     * A mutation field backed by a developer-provided {@code @service} method. Mirrors
     * {@link QueryService}'s payload shape so the hover surface renders the target
     * table for table-bound service mutations.
     */
    record MutationService(
        String methodClassName, String methodName, boolean tableBound, String tableName,
        String errorChannelMappingName
    ) implements FieldClassification {}

    /**
     * A record-returning DML mutation; the write verb projects onto the {@link DmlKind}
     * discriminator and the bulk-input axis onto {@code bulk}.
     */
    record DmlRecord(
        String tableName, String inputTypeName, DmlKind kind, boolean bulk,
        String errorChannelMappingName
    ) implements FieldClassification {}

    // ===== Unclassified =====

    /**
     * A field the classifier could not assign a variant to. The {@code reason} is the
     * human-readable rejection message; rendering decides whether to surface it.
     */
    record Unclassified(String reason) implements FieldClassification {}
}
