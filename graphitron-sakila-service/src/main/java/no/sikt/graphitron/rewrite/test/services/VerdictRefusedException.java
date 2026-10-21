package no.sikt.graphitron.rewrite.test.services;

/** A refusal from {@link FilmVerdictService}, which the fixture schema's {@code @error} type claims. */
public class VerdictRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public VerdictRefusedException(String message) {
        super(message);
    }
}
