package no.sikt.graphitron.mcp.fixtures.code;

import org.jooq.Condition;

import java.util.List;

/**
 * A service host the code tool reads.
 *
 * <p>Real code rather than a hand-built census row, which is what makes the descriptors, the
 * {@code org.jooq.Condition} match and the declared type forms the ones a consumer's own classfiles
 * carry. This file sits under the one source root the code fixtures walk, so the declaration family
 * reads it in the same shape a dev session's watcher does and the doc comments and positions here are
 * the ones the tool hands an agent.
 */
public class FilmService {

    /** The titles of at most the given number of films. */
    public List<String> titles(int limit) {
        return List.of();
    }

    /** Films still on the shelf. */
    public Condition activeFilms() {
        return null;
    }

    /**
     * One arity, two declarations: the outcome the declaration family reports as a count rather than
     * resolving, a parse reading parameter types as written and so having nothing narrower to match on.
     */
    public String describe(int filmId) {
        return "film " + filmId;
    }

    /** The other half of the same-arity pair. */
    public String describe(String title) {
        return title;
    }
}
