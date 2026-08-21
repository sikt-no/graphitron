package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * An {@code @error} handler source class carrying a node key rather than a node id: the exception a
 * lookup throws when it cannot find a film, holding {@code film.film_id}'s own binding type.
 *
 * <p>The shape the read-family encode exists for. Before it, an SDL {@code ID} field carrying
 * {@code @nodeId(typeName: "Film")} on the {@code @error} type read this accessor and handed the raw
 * key to the consumer, so consumers encoded by hand and their accessor returned a {@code String}.
 * {@code label} is a second accessor typed as something else, so the type disagreement has a
 * fixture on the same class.
 */
public class FilmNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Integer filmId;

    public FilmNotFoundException(Integer filmId) {
        super("no film " + filmId);
        this.filmId = filmId;
    }

    public Integer getFilmId() {
        return filmId;
    }

    public String getLabel() {
        return "film " + filmId;
    }
}
