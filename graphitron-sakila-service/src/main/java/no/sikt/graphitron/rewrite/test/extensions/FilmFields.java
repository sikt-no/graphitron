package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.math.BigDecimal;

/**
 * Two lifters an {@code @externalField} may name beside three methods it may not, one per clause of
 * the contract: {@link #helper} returns no {@code Field}, {@link #combine} takes two arguments, and
 * {@link #fromName} has a lifter's shape exactly but takes no table.
 */
public final class FilmFields {

    private FilmFields() {}

    public static Field<BigDecimal> rentalRate(Film film) {
        return film.RENTAL_RATE;
    }

    public static Field<String> title(Film film) {
        return film.TITLE;
    }

    public static String helper(Film film) {
        return film.getName();
    }

    public static Field<String> combine(Film a, Film b) {
        return a.TITLE;
    }

    public static Field<String> fromName(String name) {
        return DSL.inline(name);
    }
}
