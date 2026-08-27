package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.enums.MpaaRating;
import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor;
import no.sikt.graphitron.rewrite.test.jooq.tables.RentFilm;
import no.sikt.graphitron.rewrite.test.jooq.tables.Rental;
import org.jooq.Condition;

/**
 * Condition methods whose route the <em>store</em> has to resolve, which is why they are here and
 * not on {@link TestConditionStub} beside every other condition fixture.
 *
 * <p>The store reads a condition hop's target off the method's captured signature, and the
 * classpath census admits public classes only: {@code TestConditionStub} is package-private, so no
 * method on it has a census row and the route relation reports {@code CLASS_NOT_IN_CENSUS} for
 * every one of them. That is the census's own disclosed rule rather than a defect, and the
 * generator is unaffected by it, resolving the same signature through the codegen loader. So a case
 * about what the store resolves needs a public carrier, and a case about what the parser resolves
 * does not care either way.
 *
 * <p>Both parameters are concrete generated jOOQ table classes on every method here. A filter site
 * carries no return-type binding for a wildcard signature to fall back on, and the store's route
 * relation refuses a wildcard target for the same reason the parser does.
 */
public final class TestConditionRoutes {

    private TestConditionRoutes() {}

    /**
     * A bare-condition first hop out of a routine result: the {@code rent_film} function result's
     * own generated class as the source and {@code rental} as the target. Pins the seat verdict at
     * that shape, which is {@code UNANCHORED_FIRST_HOP} once the chain can see the hop at all: the
     * predicate names the routine alias, so the post-commit re-read has no anchor whatever the hop
     * resolves to.
     */
    public static Condition routineResultToRental(RentFilm src, Rental tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * A filter path's terminal condition hop, {@code film} to the {@code film_actor} junction. The
     * argument's own column name then resolves against the junction, which is the population the
     * condition arm exists for.
     */
    public static Condition filmToFilmActor(Film src, FilmActor tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * A field-site condition taking a value parameter typed as a generated enum, beside one typed
     * as a plain scalar. The two extractions the {@code @condition} call surface has, on one real
     * signature, which is what a case reading them out of the store needs: a generated enum is in
     * the package the classpath census drops, so this parameter's type is knowable only through the
     * catalog's own enum relation and a fixture typed on an author's enum would not test that.
     */
    public static Condition filmByRating(Film table, MpaaRating rating, String title) {
        throw new UnsupportedOperationException();
    }
}
