package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.multischemafixture.multischema_a.tables.records.EventRecord;

import java.util.List;

/**
 * R441 pipeline-tier fixtures for the typed-accessor / schema-qualified {@code @table} match.
 * Each type is a free-form DTO payload parent exposing a single typed accessor returning a jOOQ
 * {@code EventRecord} from one of the two colliding {@code event} tables in the multischema
 * fixture ({@code multischema_a.event} / {@code multischema_b.event}, both with the bare SQL name
 * {@code event}).
 *
 * <p>The accessor-derived source resolution ({@code FieldBuilder.deriveAccessorRecordParentSource})
 * resolves each accessor's element table by record-class identity, so the surviving
 * {@code TableRef} carries jOOQ's <em>unqualified</em> canonical name {@code "event"}. The payload
 * element type's {@code @table} must be spelled schema-qualified ({@code "multischema_a.event"})
 * because the bare {@code "event"} is ambiguous across the two schemas. Before R441 the match
 * compared the qualified echo against the bare canonical name and dropped the accessor; R441 routes
 * the compare through the reified {@code tableClass} identity so it matches (schema A) or is
 * correctly rejected (schema B).
 *
 * <p>Co-located with the other dummy references so a test SDL can bind a payload type to one of
 * these classes through the {@code @service} producer-return reflection path.
 */
public final class R441EventAccessorPayloads {

    private R441EventAccessorPayloads() {}

    /**
     * Qualified-match case (the reported bug): the accessor returns {@code multischema_a.event}'s
     * {@code EventRecord}. Against an element type declared {@code @table(name: "multischema_a.event")},
     * the accessor must match by table-class identity and the field classify green.
     */
    public record SchemaAEventsPayload(List<EventRecord> events) {}

    /**
     * Genuine-mismatch case (the tightening guard): the accessor returns {@code multischema_b.event}'s
     * {@code EventRecord}, spelled by FQN because the simple name collides with schema A's record.
     * Against the same {@code @table(name: "multischema_a.event")} element type, the accessor denotes
     * a different table and must be dropped, so the field still rejects.
     */
    public record SchemaBEventsPayload(
        List<no.sikt.graphitron.rewrite.multischemafixture.multischema_b.tables.records.EventRecord> events) {}
}
