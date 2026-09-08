package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.tables.records.IntentFieldUnlowerableOrderingRejectionRecord;
import org.jooq.DSLContext;

import java.util.ArrayList;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_UNLOWERABLE_ORDERING_REJECTION;

/**
 * The capture-cadence writer of {@code intent_field_unlowerable_ordering_rejection}: the rejection
 * each minting row of {@code intent_field_unlowerable_ordering} carries, stored so the diagnostics
 * read surface can project the violation's kind, variant and message as plain columns instead of
 * assembling a sentence in SQL. Clears the run's graph partition first and re-mints, on
 * {@link AuthoredClaimRejectionRows}'s cadence, so on any settled store these rows stand one to one
 * with the view's minting rows.
 *
 * <p>Why a writer rather than a view is the relation's own argument: the message names the
 * multitable container's participants in a sentence and picks its remedy off the availability route,
 * which is a composition of facts this store holds separately rather than a fact of any graph. What
 * lives here is the loop; the rule is {@link UnlowerableOrderings#rejectionOf}, shared with the
 * build-error consumer so one violation cannot be worded two ways.
 *
 * <p>One thing separates this writer from its siblings and it is a dependency rather than a choice:
 * the view it renders reads {@code intent_field_scope_table}, which is materialized, so a call
 * beside the capture flush would render the previous capture's rows. It therefore runs after the
 * refresh instead of before it, which is the earliest point at which it can see what the capture
 * landed.
 *
 * <p>Total over the view's <em>minting</em> rows rather than over its rows, and the gap is the
 * population's own: the held verdict mints no rejection, so its coordinates are counted by the view
 * and worded nowhere. Total over the whole partition rather than narrowed to the classification
 * domain, on {@link AuthoredClaimRejectionRows}'s reasoning: the narrowing is a consumer's question
 * and the editor's arm reads these rows ungated.
 */
public final class UnlowerableOrderingRejectionRows {

    private UnlowerableOrderingRejectionRows() {}

    /** Clears and re-mints the graph's unlowerable-ordering rejection partition; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(INTENT_FIELD_UNLOWERABLE_ORDERING_REJECTION)
            .where(INTENT_FIELD_UNLOWERABLE_ORDERING_REJECTION.GRAPH_NAME.eq(graphName))
            .execute();
        var rows = new ArrayList<IntentFieldUnlowerableOrderingRejectionRecord>();
        // Coordinate-and-route order, which the read already returns in, so the ordinal a row lands
        // on is a function of the partition rather than of the order the engine handed the union's
        // arms over.
        for (var unlowered : UnlowerableOrderings.rows(dsl, graphName)) {
            var minted = UnlowerableOrderings.rejectionOf(unlowered);
            if (minted.isEmpty()) {
                continue;
            }
            Rejection rejection = minted.get();
            var error = ValidationError.forField(unlowered.coordinate(), rejection, null);
            var record = dsl.newRecord(INTENT_FIELD_UNLOWERABLE_ORDERING_REJECTION);
            record.setGraphName(graphName);
            record.setOrdinal(rows.size());
            record.setTypeName(unlowered.typeName());
            record.setFieldName(unlowered.fieldName());
            record.setAvailableVia(unlowered.availableVia().name());
            record.setArgumentName(unlowered.argumentName());
            record.setKind(RejectionKind.of(rejection).name());
            record.setVariant(Rejection.classSpelling(rejection.getClass()));
            record.setMessage(error.message());
            rows.add(record);
        }
        if (!rows.isEmpty()) {
            dsl.batchInsert(rows).execute();
        }
    }
}
