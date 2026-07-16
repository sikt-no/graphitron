package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Orphaned compilation-tier fixture. It produced the {@code SingleFilmCardCarrier} /
 * {@code createFilmCard} carrier path that has since been removed (the NoBacking carrier promotion
 * is retired; carriers now bind to {@code JooqTableRecordType}). No schema field or test references
 * this factory any more; it is a deletion candidate. {@code FilmCardData} itself is still live
 * (used by {@code InventoryExtensions} and the schema), so only this factory is dead.
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
