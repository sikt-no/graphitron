package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Film;
import org.jooq.Condition;

import static org.jooq.impl.DSL.and;

public class ReferenceFilmCondition {
    public static Condition sequel(Film f0, Film f1) {
        return null;
    }

    public static Condition releaseYear(Film f0, Film f1) {
        return f0.RELEASE_YEAR.eq(f1.RELEASE_YEAR);
//        System.out.println("Are we even here?");

//        return f0.FILM_ID.eq(f0.FILM_ID);
    }
}
