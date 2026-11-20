package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Hands back a payload whose member of a field's own name returns records of another schema's
 * {@code event} table than the field's {@code @table} type: {@code multischema_b.event}'s under a
 * field typed as {@code multischema_a.event}'s. The member keys nothing for that field, so it is
 * refused rather than read as the field's value.
 */
public final class MultiSchemaEventsPayloadService {

    private MultiSchemaEventsPayloadService() {}

    /** A payload holding no events; what matters is the class of the records its member returns. */
    public static MultiSchemaBEventsPayload schemaBEvents() {
        return new MultiSchemaBEventsPayload(List.of());
    }
}
