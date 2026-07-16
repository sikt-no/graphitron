package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Backing fixtures for {@code FetcherPipelineTest}'s generic result-type emission tests.
 * Under reflection-only binding a standalone {@code @record} no longer fabricates a backing class,
 * so these tests bind their result type through a {@code @service} producer (in {@link DummyService})
 * returning one of these records. The component shapes match the test SDL field shapes so the
 * inner-field accessor classification (PropertyField for a scalar, RecordField for a nested record)
 * resolves exactly as the {@code @record} idiom used to set up.
 */
public final class DummyFetcherFixtures {

    private DummyFetcherFixtures() {}

    /** Backs {@code type Container { value: String }}: a single String-accessor scalar field. */
    public record ContainerRecord(String value) {}

    /** Backs {@code type FilmStats { count: Int }}: a single Integer-accessor scalar field. */
    public record FilmStatsRecord(Integer count) {}

    /** Backs {@code type FilmDetails { stats: FilmStats }}: a nested-record component field. */
    public record FilmDetailsRecord(FilmStatsRecord stats) {}

    /**
     * Backs PropertyField column-name cases: exposes both a {@code title} accessor (SDL field name)
     * and a {@code film_title} accessor (the {@code @field(name: "film_title")} override target),
     * so the same backing serves the with- and without-override variants.
     */
    public record DetailsProps(String title, String film_title) {}

    /**
     * Backs {@code type FilmDetails { rating: String }} as a {@code @service} return, making
     * {@code FilmDetails} record-backed. The {@code @table}-parent ConstructorField that
     * used to classify cleanly from this shape was retired; it now backs {@code ConstructorFieldValidationTest}'s
     * table-and-service clash rejection fixture (the child has no producer to build it from the
     * {@code @table} parent's row).
     */
    public record FilmDetailsRating(String rating) {}
}
