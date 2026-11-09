package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/** Hands back {@link FilmPair}s, the parent of the ambiguous key row fixture. */
public final class FilmPairService {

    private FilmPairService() {}

    public static List<FilmPair> pairs() {
        return List.of();
    }
}
