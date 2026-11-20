package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.multischema_a.tables.records.EventRecord;

import java.util.List;

/**
 * Compilation-tier fixture for the cross-schema {@code create<Record>} helper-name collision: two
 * jOOQ {@code EventRecord} classes with an identical simple name live in
 * {@code multischema_a.…records} and {@code multischema_b.…records}. Binding both as
 * {@code @service} input params on one {@code *Fetchers} class forces two {@code create<Record>}
 * helpers whose names must differ, or the generated class does not compile. The
 * {@code rewrite-generate-multischema-mutation} plugin execution generates that class and the
 * example module compiles it at {@code -release 17}, so this fixture proves the emitted code
 * actually compiles, the guarantee pipeline TypeSpec assertions cannot fully make.
 *
 * <p>The two methods deliberately take the two distinct {@code EventRecord} classes (schema A here,
 * schema B via its fully-qualified name) so the generated helpers reference both.
 */
public final class MultiSchemaEventRecordService {

    private MultiSchemaEventRecordService() {}

    /** Binds the {@code multischema_a.event} record param. */
    public static String modifyEventA(EventRecord in) {
        return "a:" + in.getEventId();
    }

    /**
     * A payload whose {@code events()} hands back {@code multischema_a}'s event records, whose bare
     * table name {@code multischema_b} shares: a field under it keys its rows by the records' own
     * table, told apart from the other schema's by the record class.
     */
    public record SchemaAEvents(List<EventRecord> events) {}

    /** Hands back a {@link SchemaAEvents}. Never called. */
    public static SchemaAEvents schemaAEvents() {
        return new SchemaAEvents(List.of());
    }

    /** Binds the {@code multischema_b.event} record param. */
    public static String modifyEventB(
            no.sikt.graphitron.rewrite.test.jooq.multischema_b.tables.records.EventRecord in) {
        return "b:" + in.getEventId();
    }
}
