package no.sikt.graphitron.model.capture.code.fixtures;

import java.util.List;
import org.jooq.Condition;
import org.jooq.Record3;
import org.jooq.Row2;
import org.jooq.impl.DSL;

/**
 * Conditions taking a key as a jOOQ tuple, so the capture states the element's type arguments: a
 * list of two-column rows, and a three-column record whose middle argument is a wildcard.
 */
public final class TupleFixture {

    private TupleFixture() {}

    public static Condition onKeyRows(org.jooq.Table<?> table, List<Row2<Integer, String>> keys) {
        return DSL.noCondition();
    }

    public static Condition onAPartlyWildKey(org.jooq.Table<?> table, Record3<String, ?, String> key) {
        return DSL.noCondition();
    }
}
