package no.sikt.graphitron.rewrite.test.backing;

import java.util.List;

/** A field-level producer for a film's reviews, overriding what {@link Film#reviews()} delivers. */
public final class ReviewService {

    private ReviewService() {}

    public static List<ReviewDto> forFilm() {
        return List.of();
    }
}
