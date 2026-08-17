package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.ActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Reflection backing for the shared-value-type shapes: one class-backed SDL type produced by
 * more than one producer, where at least one of them reads it as a component of a
 * record-backed parent. This is the shape federation pushes a consumer subgraph toward (another
 * subgraph declares the field {@code @shareable}, so the value type's shape is fixed), and the
 * producers really do put the same Java object at {@code env.getSource()}.
 *
 * <p>Consumed by {@code SharedDomainTypeProducerPipelineTest} through the
 * {@code TestServiceStub.shared*} producers.
 */
public final class SharedValueTypeFixtures {

    private SharedValueTypeFixtures() {}

    /** The shared value type: what every producer in these fixtures hands down. */
    public record Translations(String nb, String en) {}

    /**
     * A class-backed parent holding the shared value type as a single-valued record component;
     * the component read is the producer that answered {@code Plain(java.lang.Object)} before
     * the source-type claims were sharpened.
     */
    public record FilmSummary(Translations translations, String note) {}

    /** The list-shaped sibling of {@link FilmSummary}: the component is {@code List<Translations>}. */
    public record TranslationsList(List<Translations> translations, String note) {}

    /**
     * A <em>nested</em> composite: one {@code FilmRecord} plus a {@code List<ActorRecord>}, whose
     * binary name carries a {@code $}. Two producers of this class spelling it two ways
     * ({@code Outer$Nested} against {@code Outer.Nested}) is the false-conflict the single
     * backing-class mint prevents.
     */
    public record NestedComposite(FilmRecord filmRecord, List<ActorRecord> actorRecords) {}

    /** A class-backed parent reading {@link NestedComposite} as a record component. */
    public record NestedCompositeHolder(NestedComposite composite, String note) {}

    /** A class-backed payload with no jOOQ surface, produced from both operation roots. */
    public record SharedPayload(String status, Translations translations) {}
}
