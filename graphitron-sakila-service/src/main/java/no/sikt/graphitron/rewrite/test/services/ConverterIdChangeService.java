package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.ConverterIdChangeRecord;
import org.jooq.DSLContext;

/**
 * Execution fixture: a {@code @service} handed a {@code ConverterIdChange} node's own record, decoded
 * from its id. The key's second column, {@code changed_at}, is a {@code timestamptz} domain forced to
 * {@code LocalDateTime} by a converter, so the record finds its own row only where the decode kept the
 * time of day the id carries.
 */
public final class ConverterIdChangeService {

    private ConverterIdChangeService() {}

    /** The value of the row the decoded key names, or null where it names none. */
    public static String newValueOf(ConverterIdChangeRecord change, DSLContext dsl) {
        return dsl.select(Tables.CONVERTER_ID_CHANGE.NEW_VALUE)
            .from(Tables.CONVERTER_ID_CHANGE)
            .where(Tables.CONVERTER_ID_CHANGE.PERSON_ID.eq(change.getPersonId()),
                Tables.CONVERTER_ID_CHANGE.CHANGED_AT.eq(change.getChangedAt()))
            .fetchOne(Tables.CONVERTER_ID_CHANGE.NEW_VALUE);
    }
}
