package no.sikt.graphitron.rewrite.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.WALK_CLAIM_DOMAIN_FIELD;
import static no.sikt.graphitron.model.Tables.WALK_CLAIM_DOMAIN_TYPE;

/**
 * Reifies a {@link ClaimDomain} as the {@code walk_claim_domain_type} /
 * {@code walk_claim_domain_field} membership rows the {@code intent_authored_claim_conflict}
 * view joins. The production write site is the capture-and-detect pass
 * ({@code FactCapture.detect}), at capture cadence, inside the capture's graph-scoped
 * ownership; the relations' family header carries the cadence and removal criterion.
 */
public final class ClaimDomainRows {

    private ClaimDomainRows() {}

    /** Replaces {@code graphName}'s reach rows with {@code domain}'s membership, atomically. */
    public static void write(DSLContext dsl, String graphName, ClaimDomain domain) {
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            txDsl.deleteFrom(WALK_CLAIM_DOMAIN_FIELD)
                .where(WALK_CLAIM_DOMAIN_FIELD.GRAPH_NAME.eq(graphName))
                .execute();
            txDsl.deleteFrom(WALK_CLAIM_DOMAIN_TYPE)
                .where(WALK_CLAIM_DOMAIN_TYPE.GRAPH_NAME.eq(graphName))
                .execute();
            var typeRows = domain.typeNames().stream()
                .map(typeName -> {
                    var row = txDsl.newRecord(WALK_CLAIM_DOMAIN_TYPE);
                    row.setGraphName(graphName);
                    row.setTypeName(typeName);
                    return row;
                })
                .toList();
            var fieldRows = domain.fieldCoordinates().stream()
                .map(coordinate -> {
                    var row = txDsl.newRecord(WALK_CLAIM_DOMAIN_FIELD);
                    row.setGraphName(graphName);
                    row.setTypeName(coordinate.getTypeName());
                    row.setFieldName(coordinate.getFieldName());
                    return row;
                })
                .toList();
            txDsl.batchInsert(typeRows).execute();
            txDsl.batchInsert(fieldRows).execute();
        });
    }
}
