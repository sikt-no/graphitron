package no.sikt.graphitron.rewrite.diagnostics;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.tables.records.RejectionValidationErrorDirectiveRecord;
import no.sikt.graphitron.model.tables.records.RejectionValidationErrorRecord;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.model.DeleteRowsError;
import no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError;
import no.sikt.graphitron.rewrite.model.JooqRecordInputError;
import no.sikt.graphitron.rewrite.model.MutationTableArgError;
import no.sikt.graphitron.rewrite.model.PivotError;
import no.sikt.graphitron.rewrite.model.ReflectionError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError;
import no.sikt.graphitron.rewrite.model.ServiceMethodCallError;
import no.sikt.graphitron.rewrite.model.UpdateRowsError;
import no.sikt.graphitron.rewrite.model.WireCoercionError;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR;
import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR_DIRECTIVE;

/**
 * The {@code rejection_} family's writer: transcribes the classification walk's error stream
 * into {@code rejection_validation_error} and its ordered {@code directives} child, in the
 * sealed {@link Rejection} hierarchy's own spellings. The input is the walk's list, never the
 * assembled report: a detection-minted family (the claim-conflict pilot's violations) is
 * structurally absent from this loader's input, so per-family drainage needs no skip-list that
 * could drift.
 *
 * <p>This class is the single exhaustive-switch site the residue's typed columns come from:
 * {@link #typedColumns} destructures every {@link Rejection} leaf with no {@code default} and
 * no {@code instanceof} chain, so a new arm forces a decision here the way
 * {@link RejectionKind#of} already does, and the nine {@code lspCode()}-bearing sub-seals are
 * matched explicitly. It is also the one site decoding {@link ValidationError}'s coordinate
 * convention ({@code forType} / {@code forField}'s string-plus-null) back into the stored
 * {@code (type_name, field_name)} pair; when the sealed coordinate component lands, this
 * decode becomes a switch and no column changes.
 *
 * <p>Cadence and failure posture are {@link no.sikt.graphitron.rewrite.compile.CompileFacts}'s:
 * the dev session's live store handle, one graph-scoped delete-and-insert transaction per
 * snapshot, and store trouble costs warmth, never the dev loop.
 */
public final class RejectionFacts {

    private static final Logger LOG = LoggerFactory.getLogger(RejectionFacts.class);

    private final DSLContext dsl;
    private final FactCapture.GraphIdentity graph;
    private final boolean[] ownershipWarned = new boolean[1];

    /**
     * @param dsl   the dev session's store handle; live, shared with the session's in-process
     *              readers, never a per-snapshot open of the writer's own
     * @param graph the session's graph: the partition every statement is scoped by, and the base
     *              directory the graph's ownership is checked against
     */
    public RejectionFacts(DSLContext dsl, FactCapture.GraphIdentity graph) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** Replaces the graph's residue partition with {@code walkErrors}, in emit order. */
    public void write(List<ValidationError> walkErrors) {
        try {
            dsl.transaction(tx -> writeErrors(tx.dsl(), walkErrors));
        } catch (DataAccessException e) {
            LOG.warn("validation rejections for graph '{}' could not be written to the fact "
                + "store; store-side readers answer without this snapshot", graph.name(), e);
        }
    }

    private void writeErrors(DSLContext tx, List<ValidationError> walkErrors) {
        if (!OwnedGraphPartition.prepare(tx, graph, LOG, ownershipWarned)) {
            return;
        }
        tx.deleteFrom(REJECTION_VALIDATION_ERROR_DIRECTIVE)
            .where(REJECTION_VALIDATION_ERROR_DIRECTIVE.GRAPH_NAME.eq(graph.name()))
            .execute();
        tx.deleteFrom(REJECTION_VALIDATION_ERROR)
            .where(REJECTION_VALIDATION_ERROR.GRAPH_NAME.eq(graph.name()))
            .execute();
        var rows = new ArrayList<RejectionValidationErrorRecord>(walkErrors.size());
        var directiveRows = new ArrayList<RejectionValidationErrorDirectiveRecord>();
        int ordinal = 0;
        for (ValidationError error : walkErrors) {
            var typed = typedColumns(error.rejection());
            var coordinate = coordinateOf(error.coordinate());
            var row = tx.newRecord(REJECTION_VALIDATION_ERROR);
            row.setGraphName(graph.name());
            row.setOrdinal(ordinal);
            row.setKind(error.kind().name());
            row.setVariant(Rejection.classSpelling(error.rejection().getClass()));
            row.setLspCode(typed.lspCode());
            row.setAttemptKind(typed.attemptKind());
            row.setAttempt(typed.attempt());
            row.setStubKey(typed.stubKey());
            row.setTypeName(coordinate.typeName());
            row.setFieldName(coordinate.fieldName());
            row.setMessage(error.message());
            SourceLocation location = error.location();
            if (location != null && location.getSourceName() != null
                    && !location.getSourceName().isEmpty()) {
                row.setFile(location.getSourceName());
            }
            if (location != null && location.getLine() > 0) {
                row.setSourceLine(location.getLine());
                row.setSourceColumn(location.getColumn());
            }
            rows.add(row);
            int position = 0;
            for (String directive : typed.directives()) {
                var child = tx.newRecord(REJECTION_VALIDATION_ERROR_DIRECTIVE);
                child.setGraphName(graph.name());
                child.setErrorOrdinal(ordinal);
                child.setPosition(position++);
                child.setDirective(directive);
                directiveRows.add(child);
            }
            ordinal++;
        }
        if (!rows.isEmpty()) {
            tx.batchInsert(rows).execute();
        }
        if (!directiveRows.isEmpty()) {
            tx.batchInsert(directiveRows).execute();
        }
    }

    /** The typed columns one rejection contributes; everything not applicable stays SQL NULL. */
    private record TypedColumns(String lspCode, String attemptKind, String attempt,
                                String stubKey, List<String> directives) {}

    private static final TypedColumns NONE = new TypedColumns(null, null, null, null, List.of());

    /**
     * The exhaustive switch the residue's typed columns come from. No {@code default}: a new
     * {@link Rejection} arm fails compilation here and forces a column decision, which is the
     * property the deleted per-consumer extractors used to carry.
     */
    private static TypedColumns typedColumns(Rejection rejection) {
        return switch (rejection) {
            case Rejection.AuthorError.UnknownName u ->
                new TypedColumns(null, u.attemptKind().name(), u.attempt(), null, List.of());
            case Rejection.AuthorError.Structural ignored -> NONE;
            case Rejection.AuthorError.AccessorMismatch ignored -> NONE;
            case Rejection.AuthorError.RecordBindingMultiProducer ignored -> NONE;
            case Rejection.AuthorError.TypeConflict ignored -> NONE;
            case Rejection.AuthorError.MultiProducerDomainTypeDisagreement ignored -> NONE;
            case Rejection.AuthorError.SortEnumMissingOrder ignored -> NONE;
            case Rejection.AuthorError.TenantColumnTypeDisagreement ignored -> NONE;
            case Rejection.AuthorError.NoTenantBinding ignored -> NONE;
            case ServiceMethodCallError e -> coded(e.lspCode());
            case ReflectionError e -> coded(e.lspCode());
            case UpdateRowsError e -> coded(e.lspCode());
            case DeleteRowsError e -> coded(e.lspCode());
            case MutationTableArgError e -> coded(e.lspCode());
            case ErrorChannelWalkerError e -> coded(e.lspCode());
            case WireCoercionError e -> coded(e.lspCode());
            case ServiceCarrierShapeError e -> coded(e.lspCode());
            case PivotError e -> coded(e.lspCode());
            case JooqRecordInputError e -> coded(e.lspCode());
            case Rejection.InvalidSchema.DirectiveConflict d ->
                new TypedColumns(null, null, null, null, d.directives());
            case Rejection.InvalidSchema.CaseFoldCollision ignored -> NONE;
            case Rejection.InvalidSchema.Structural ignored -> NONE;
            case Rejection.Deferred d ->
                new TypedColumns(null, null, null, stubKeyOf(d.stubKey()), List.of());
        };
    }

    private static TypedColumns coded(String lspCode) {
        return new TypedColumns(lspCode, null, null, null, List.of());
    }

    /** The stub anchor's stored spelling, on the same rule as {@code variant}. */
    private static String stubKeyOf(Rejection.StubKey key) {
        return switch (key) {
            case Rejection.StubKey.VariantClass v -> v.variant();
        };
    }

    private record CoordinatePair(String typeName, String fieldName) {}

    /**
     * Decodes {@link ValidationError}'s coordinate convention (null for schema-wide, a type
     * name, or {@code Type.field}) into the stored pair, at this one site.
     */
    private static CoordinatePair coordinateOf(String coordinate) {
        if (coordinate == null) {
            return new CoordinatePair(null, null);
        }
        int dot = coordinate.indexOf('.');
        return dot < 0
            ? new CoordinatePair(coordinate, null)
            : new CoordinatePair(coordinate.substring(0, dot), coordinate.substring(dot + 1));
    }
}
