package no.sikt.graphitron.rewrite.test.backing;

/** A producer a schema names without a method, which grounds nothing and still stops the hop. */
public final class ScoreService {

    private ScoreService() {}

    public static ScoreDto forFilm() {
        return null;
    }
}
