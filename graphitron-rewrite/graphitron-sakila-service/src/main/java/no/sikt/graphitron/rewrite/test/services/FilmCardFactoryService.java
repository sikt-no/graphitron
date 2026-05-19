package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Compilation-tier fixture: an {@code @service} method whose return type is the
 * {@code @record}-backed Java class {@link FilmCardData}. Wired through a {@code Mutation}
 * field returning a plain SDL Object carrier ({@code SingleFilmCardCarrier}); the carrier
 * promotes to {@code PojoResultType.NoBacking} via {@code TypeBuilder.promoteSingleRecordCarriers}
 * (now consulting {@code BuildContext.scanStructuralDmlPayload}), and the unified per-field
 * classifier on the producer-bound parent resolves the {@code card: FilmCardWrapper} data field.
 *
 * <p>Exists at the compilation tier only — confirms the schema compiles end-to-end.
 */
public final class FilmCardFactoryService {

    private FilmCardFactoryService() {}

    /**
     * Constructs a {@link FilmCardData} for {@code filmId} with a placeholder title. The
     * {@code @service} Mutation field treats the returned value as the carrier's source value;
     * graphql-java traverses through the data field's identity-passthrough fetcher and reads
     * {@code FilmCardData.film()} for the nested Film fetcher.
     */
    public static FilmCardData create(Integer filmId) {
        FilmRecord rec = new FilmRecord();
        rec.setFilmId(filmId);
        rec.setTitle("placeholder");
        return new FilmCardData(rec);
    }
}
