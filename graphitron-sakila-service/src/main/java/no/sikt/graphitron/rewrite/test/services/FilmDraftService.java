package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fixture behind {@code Mutation.countFilmDrafts}: a {@code @service} mutation taking a list of
 * input objects bound to {@link FilmDraft}, whose payload's error channel carries a
 * {@code {handler: VALIDATION}} member. That handler makes the wrapper validate each
 * {@code FilmDraft} this method is about to receive, against the constraints declared on it, and
 * answer with one error per violation instead of calling this method. With every draft valid the
 * method runs and counts them; {@code count} is only ever set here, so a null {@code count} beside
 * the errors shows the method was not called.
 */
public final class FilmDraftService {

    private FilmDraftService() {}

    public static FilmDraftsCountedPayload count(List<FilmDraft> drafts) {
        return new FilmDraftsCountedPayload(drafts.size(), List.of());
    }

    /** The distinct drafts: a list of input objects built into a set, equal drafts counting once. */
    public static FilmDraftsCountedPayload countDistinct(Set<FilmDraft> drafts) {
        return new FilmDraftsCountedPayload(drafts.size(), List.of());
    }

    /**
     * A child service counting each film's drafts, its outcome validating what it builds: validation
     * under an object, which the store-fed fetcher does not generate yet; never called.
     */
    public static Map<FilmRecord, FilmDraftsCountedPayload> countFor(Set<FilmRecord> films, List<FilmDraft> drafts) {
        return films.stream().collect(Collectors.toMap(Function.identity(),
            film -> new FilmDraftsCountedPayload(drafts.size(), List.of())));
    }

    /** Takes a class nothing can construct, for the build defect that says so; never called. */
    public static FilmDraftsCountedPayload countAbstract(AbstractFilmDraft draft) {
        return new FilmDraftsCountedPayload(1, List.of());
    }
}
