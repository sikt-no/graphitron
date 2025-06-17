package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Film;
import org.jooq.Condition;

public class ReferenceFilmCondition {
    public static Condition sequel(Film f0, Film f1) {
        return null;
    }

    public static Condition sequel2(Film f0, Film f1) {
        return null;
    }


    public static Condition followUp(Film f0, Film f1) {
        return null;
//        return f0.LENGTH.cast(Integer.class).greaterThan(100).and(f1.LENGTH.cast(Integer.class).greaterThan(100));
    }
}
