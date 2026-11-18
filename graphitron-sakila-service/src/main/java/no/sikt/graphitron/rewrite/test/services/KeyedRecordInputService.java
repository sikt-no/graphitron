package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.idreffixture.tables.records.StudierettRecord;
import no.sikt.graphitron.rewrite.test.jooq.nodeidfixture.tables.records.ReorderedFkChildRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.XlatChildRecord;

/**
 * Services taking jOOQ records whose foreign keys are not the plain case: a key to an alternate
 * unique column rather than the parent's primary key, a composite key listing its columns in
 * another order than the parent's, and two keys from one table to the same parent. A node id built
 * into each record lands, or is refused, by the key it names.
 */
public final class KeyedRecordInputService {

    private KeyedRecordInputService() {}

    public static String describeXlatChild(XlatChildRecord in) {
        return "xlat:" + in.getParentAltKey();
    }

    public static String describeReorderedChild(ReorderedFkChildRecord in) {
        return "reordered:" + in.getFkA() + "/" + in.getFkB() + "/" + in.getFkC();
    }

    public static String describeStudierett(StudierettRecord in) {
        return "studierett:" + in.getRegistrarStudieprogram();
    }
}
