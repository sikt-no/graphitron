package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

/**
 * Execution-tier fixture for an {@code @outbox} on a {@code @service} mutation: the message reads the
 * node id of the {@link FilmRecord} the returned {@link FilmCardData}'s {@code film()} hands back.
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
