package no.fellesstudentsystem.graphitron.conditions;

import no.sikt.graphitron.jooq.generated.testdata.tables.Film;
import org.jooq.Condition;

import java.util.List;

/**
 * Fake service for condition tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class RatingTestConditions {

    public static Condition rating(Film film, String rating) {
        return null;
    }

    public static Condition ratingAll(Film film, String rating, Integer releaseYear) {
        return null;
    }

    public static Condition ratingInputAll(Film film, Integer releaseYear1, String rating, Integer releaseYear2) {
        return null;
    }

    public static Condition ratingList(Film film, List<String> ratings) {
        return null;
    }

    public static Condition ratingListAll(Film film, List<String> ratings, String releaseYear) {
        return null;
    }
}
