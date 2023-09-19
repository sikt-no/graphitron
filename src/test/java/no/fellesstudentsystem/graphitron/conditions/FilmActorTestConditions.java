package no.fellesstudentsystem.graphitron.conditions;

import no.sikt.graphitron.jooq.generated.testdata.tables.Film;
import no.sikt.graphitron.jooq.generated.testdata.tables.FilmActor;
import no.sikt.graphitron.jooq.generated.testdata.tables.Language;
import org.jooq.Condition;


public class FilmActorTestConditions {
    public static Condition mainActor(Film film, FilmActor filmActor) {
        return null;
    }
    public static Condition starringActor(Film film, FilmActor filmActor) {
        return null;
    }
}
