package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.ActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.Record;

import java.util.List;

/**
 * Execution-tier fixture for a {@code @service} that returns a polymorphic entity
 * (the {@code Searchable} interface / {@code Document} union, both implemented by {@code Film} and
 * {@code Actor} on distinct tables).
 *
 * <p>The service hands back PK-populated jOOQ {@link Record}s; the Java type of each returned record
 * <em>is</em> the discriminator. The generated fetcher dispatches on the runtime record class
 * ({@code FilmRecord} → {@code Film}, {@code ActorRecord} → {@code Actor}), tags the matched
 * participant's {@code __typename}, and auto-fetches the selected columns by primary key. The
 * methods therefore set only the PK and leave the rest for Graphitron to fetch, matching the
 * legacy 9.3 contract.
 */
public final class PolymorphicSearchService {

    private PolymorphicSearchService() {}

    /** Single-cardinality polymorphic return: one PK-populated {@code FilmRecord} (the {@code Film} branch). */
    public static Record searchOne() {
        FilmRecord film = new FilmRecord();
        film.setFilmId(1);
        return film;
    }

    /**
     * List-cardinality polymorphic return exercising both distinct-table branches so a misdispatch
     * is observable: a {@code FilmRecord} and an {@code ActorRecord}, each PK-populated.
     */
    public static List<Record> searchDocuments() {
        FilmRecord film = new FilmRecord();
        film.setFilmId(1);
        ActorRecord actor = new ActorRecord();
        actor.setActorId(1);
        return List.of(film, actor);
    }
}
