package no.sikt.graphitron.rewrite.test.services;

/**
 * A service whose parameter is a class no other package can construct: the draft record is
 * package-private, so a build generated elsewhere cannot build it.
 */
public final class PrivateDraftService {

    private PrivateDraftService() {}

    record PrivateDraft(String title) {}

    public static FilmDraftsCountedPayload count(PrivateDraft draft) {
        return new FilmDraftsCountedPayload(draft == null ? 0 : 1, java.util.List.of());
    }
}
