package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_DATA_FIELD;
import static org.jooq.impl.DSL.selectOne;

/**
 * Whether a coordinate is where a mutation payload's data arrives, which is the one question the
 * {@code $source} sigil's surfaces ask: one read of {@code intent_carrier_data_field}, keyed on the
 * coordinate the cursor sits in.
 *
 * <p>Three of the relation's columns are read as the reading rather than as the answer, which is what
 * that relation's own design asks of a reader. {@code data_fields} has to be 1, a payload declaring
 * several data channels being one the generator rejects for having no single data field. The family
 * has to be {@code SERVICE}, the sigil binding the value a producer method returned and the user
 * manual and the build's own rejection message both naming that one site. And the element has to be
 * one the sigil lands on, a table-bound type or the {@code ID} scalar, those being the two the carrier
 * classification encodes the upstream value onto: a table element through the producer's record and
 * an {@code ID} element through the encoded key.
 *
 * <p>The family narrowing is where this surface's answer changed rather than moved. The projection it
 * replaced was keyed on what the classification walk produced, and a DML carrier's data field lands on
 * the same walk arms a {@code @service} carrier's does, so the sigil was offered on payload-returning
 * writes the documented rule does not admit and the diagnostic stayed silent about them. Reading the
 * family says what the message says.
 */
public final class CarrierDataField {

    private CarrierDataField() {}

    /** The element kinds the {@code $source} sigil binds a value onto. */
    private static final List<String> SIGIL_ELEMENTS = List.of("TABLE", "ID");

    /**
     * The narrowing every reader of this relation applies to reach a sigil site, stated once so the
     * completion's own read and the diagnostics document arm cannot come to disagree about which
     * coordinate admits {@code $source}.
     */
    public static Condition sigilSite() {
        return INTENT_CARRIER_DATA_FIELD.DATA_FIELDS.eq(1)
            .and(INTENT_CARRIER_DATA_FIELD.FAMILY.eq("SERVICE"))
            .and(INTENT_CARRIER_DATA_FIELD.ELEMENT_KIND.in(SIGIL_ELEMENTS));
    }

    /**
     * Whether {@code $source} belongs at this coordinate. False covers everything the relation is
     * silent about and the three narrowings above: no mutation field returns the type, the type is not
     * a carrier shape, the payload declares more than one data channel, the producer is a write rather
     * than a {@code @service} method, or the element is one the sigil does not bind onto.
     */
    public static boolean admitsSigil(StoreHandle store, String typeName, String fieldName) {
        return store.dsl().fetchExists(selectOne()
            .from(INTENT_CARRIER_DATA_FIELD)
            .where(INTENT_CARRIER_DATA_FIELD.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_CARRIER_DATA_FIELD.TYPE_NAME.eq(typeName))
            .and(INTENT_CARRIER_DATA_FIELD.FIELD_NAME.eq(fieldName))
            .and(sigilSite()));
    }
}
