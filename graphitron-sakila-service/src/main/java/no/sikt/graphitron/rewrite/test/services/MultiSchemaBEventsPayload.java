package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.multischema_b.tables.records.EventRecord;

import java.util.List;

/**
 * A payload whose {@code events} component hands over {@code multischema_b.event}'s records, a table
 * sharing its bare name {@code event} with {@code multischema_a}'s. Under a field typed as
 * {@code multischema_a}'s event, the member of the field's own name returns another table's
 * records, so it keys nothing; see {@link MultiSchemaEventsPayloadService}.
 */
public record MultiSchemaBEventsPayload(List<EventRecord> events) {}
