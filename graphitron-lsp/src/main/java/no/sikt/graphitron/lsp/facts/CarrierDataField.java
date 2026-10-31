package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERRORS_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_READER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Whether a coordinate is where a mutation payload's data arrives, which is the one question the
 * {@code $source} sigil's surfaces ask, keyed on the coordinate the cursor sits in.
 *
 * <p>The payload is the type a {@code @service} field on the mutation root targets, holding one
 * field beside any errors field; a payload with several is one the generator rejects for having no
 * single data field. The producer has to be a
 * {@code @service} method, the sigil binding the value a producer method returned and the user manual
 * and the build's own rejection message both naming that one site. And the field has to be one the
 * sigil lands on: a {@code LOOKUP} producer re-reading a table-bound row through the producer's
 * record, or an {@code ID} the field reads off the returned object through the encoded key.
 */
public final class CarrierDataField {

    private CarrierDataField() {}

    /**
     * The narrowing every reader applies to reach a sigil site, over a field coordinate of the
     * caller's, stated once so the completion's own read and the diagnostics document arm cannot
     * come to disagree about which coordinate admits {@code $source}.
     */
    public static Condition sigilSite(Field<String> graphName, Field<String> typeName,
                                      Field<String> fieldName) {
        var o = GRAPHITRON_PRODUCER.as("sigil_producer");
        var ot = GRAPHITRON_FIELD_TARGET;
        var r = GRAPHQL_ROOT_OPERATION;
        var f = GRAPHQL_FIELD.as("sigil_field");
        var other = GRAPHQL_FIELD.as("sigil_other_field");
        var e = GRAPHITRON_ERRORS_FIELD;
        var p = GRAPHITRON_PRODUCER;
        var rd = GRAPHITRON_READER;
        return exists(selectOne()
            .from(o)
            .join(r).on(r.GRAPH_NAME.eq(o.GRAPH_NAME), r.TYPE_NAME.eq(o.TYPE_NAME),
                r.OPERATION.eq("MUTATION"))
            .join(ot).on(ot.GRAPH_NAME.eq(o.GRAPH_NAME), ot.TYPE_NAME.eq(o.TYPE_NAME),
                ot.FIELD_NAME.eq(o.FIELD_NAME))
            .join(f).on(f.GRAPH_NAME.eq(ot.GRAPH_NAME), f.TYPE_NAME.eq(ot.TARGET_TYPE_NAME))
            .where(o.GRAPH_NAME.eq(graphName), ot.TARGET_TYPE_NAME.eq(typeName),
                o.KIND.eq("SERVICE"), f.FIELD_NAME.eq(fieldName))
            .and(notExists(selectOne().from(other)
                .where(other.GRAPH_NAME.eq(f.GRAPH_NAME), other.TYPE_NAME.eq(f.TYPE_NAME),
                    other.FIELD_NAME.ne(f.FIELD_NAME))
                .and(notExists(selectOne().from(e)
                    .where(e.GRAPH_NAME.eq(other.GRAPH_NAME), e.TYPE_NAME.eq(other.TYPE_NAME),
                        e.FIELD_NAME.eq(other.FIELD_NAME))))))
            .and(exists(selectOne().from(p)
                    .where(p.GRAPH_NAME.eq(f.GRAPH_NAME), p.TYPE_NAME.eq(f.TYPE_NAME),
                        p.FIELD_NAME.eq(f.FIELD_NAME), p.KIND.eq("LOOKUP")))
                .or(f.NAMED_TYPE.eq("ID").and(exists(selectOne().from(rd)
                    .where(rd.GRAPH_NAME.eq(f.GRAPH_NAME), rd.TYPE_NAME.eq(f.TYPE_NAME),
                        rd.FIELD_NAME.eq(f.FIELD_NAME), rd.READS.eq("ACCESSOR")))))));
    }

    /**
     * Whether {@code $source} belongs at this coordinate. False covers everything the narrowing above
     * declines: no mutation {@code @service} field returns the type, the type holds more than one
     * field beside any errors field, or the field is one the sigil does not bind onto.
     */
    public static boolean admitsSigil(StoreHandle store, String typeName, String fieldName) {
        return store.dsl().fetchExists(selectOne()
            .where(sigilSite(val(store.graphName()), val(typeName), val(fieldName))));
    }
}
