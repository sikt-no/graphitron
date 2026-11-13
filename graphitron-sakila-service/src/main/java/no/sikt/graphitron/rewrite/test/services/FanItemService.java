package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FanBaseRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Execution fixture: a root {@code @service} answering rows of {@code fan_base}, the shared table the
 * {@code FanItem} discriminated interface is read off, so each row is lifted and projected as
 * whichever participant its {@code fan_kind} names.
 */
public final class FanItemService {

    private FanItemService() {}

    /** The {@code fan_base} rows with the given ids, in id order. */
    public static List<FanBaseRecord> byIds(List<Integer> ids, DSLContext dsl) {
        return dsl.selectFrom(Tables.FAN_BASE)
            .where(Tables.FAN_BASE.FAN_BASE_ID.in(ids))
            .orderBy(Tables.FAN_BASE.FAN_BASE_ID)
            .fetch();
    }
}
