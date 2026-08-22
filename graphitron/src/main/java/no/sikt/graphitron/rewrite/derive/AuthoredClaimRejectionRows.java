package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.tables.records.IntentAuthoredClaimRejectionRecord;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.diagnostics.RejectionFacts;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_CLAIM_CONFLICT;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_CLAIM_REJECTION;

/**
 * The capture-cadence writer of {@code intent_authored_claim_rejection}: the rejection each row of
 * {@code intent_authored_claim_conflict} mints, stored so the diagnostics read surface can carry
 * the violation's kind, variant and message as plain columns instead of assembling a sentence in
 * SQL. Runs inside capture's own transaction after the flush, clears the run's graph partition
 * first and re-mints, on {@link TypeBackingRows}'s cadence exactly, so on any settled store these
 * rows stand one-to-one with the conflict view's.
 *
 * <p>Why a writer rather than a view is the relation's own argument and not this class's: the
 * message's naming order is {@link AuthoredClaim}'s declaration order, which is not a captured
 * fact of any graph, so no view over this store can state the render. What lives here is the loop;
 * the rule is {@link AuthoredClaimConflicts#rejectionOf}, shared with the build-error consumer so
 * one violation cannot be worded two ways, and the three columns are three total projections of
 * that one value: {@link RejectionKind#of}, {@link RejectionFacts#classSpelling} and
 * {@link ValidationError}'s coordinate prefix.
 *
 * <p>Total over the conflict view rather than narrowed to the classification domain. The narrowing
 * is a consumer's question ({@link AuthoredClaimConflicts} asks it, because only the emitted
 * surface can fail a build), and the editor's diagnostic arm reads these rows ungated, a type no
 * field reaches being precisely where an author most needs the signal.
 */
public final class AuthoredClaimRejectionRows {

    private AuthoredClaimRejectionRows() {}

    /** Clears and re-mints the graph's claim-rejection partition; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(INTENT_AUTHORED_CLAIM_REJECTION)
            .where(INTENT_AUTHORED_CLAIM_REJECTION.GRAPH_NAME.eq(graphName))
            .execute();
        var typeClaims = AuthoredClaimConflicts.typeClaims(dsl, graphName);
        var fieldClaims = AuthoredClaimConflicts.fieldClaims(dsl, graphName);
        var v = INTENT_AUTHORED_CLAIM_CONFLICT;
        var rows = new ArrayList<IntentAuthoredClaimRejectionRecord>();
        // Coordinate order, type grain first, so the ordinal a row lands on is a function of the
        // partition rather than of the order the engine happened to hand the union's arms over.
        dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName))
            .orderBy(v.FIELD_NAME.isNotNull(), v.TYPE_NAME, v.FIELD_NAME)
            .forEach(row -> {
                String typeName = row.getTypeName();
                String fieldName = row.getFieldName();
                var claims = fieldName == null
                    ? typeClaims.getOrDefault(typeName, List.of())
                    : fieldClaims.getOrDefault(
                        new AuthoredClaimConflicts.FieldCoordinate(typeName, fieldName), List.of());
                Rejection rejection = AuthoredClaimConflicts.rejectionOf(row.getVerdict(), claims);
                var error = fieldName == null
                    ? ValidationError.forType(typeName, rejection, null)
                    : ValidationError.forField(typeName + "." + fieldName, rejection, null);
                var record = dsl.newRecord(INTENT_AUTHORED_CLAIM_REJECTION);
                record.setGraphName(graphName);
                record.setOrdinal(rows.size());
                record.setTypeName(typeName);
                record.setFieldName(fieldName);
                record.setKind(RejectionKind.of(rejection).name());
                record.setVariant(RejectionFacts.classSpelling(rejection.getClass()));
                record.setMessage(error.message());
                rows.add(record);
            });
        if (!rows.isEmpty()) {
            dsl.batchInsert(rows).execute();
        }
    }
}
