package no.sikt.graphitron.mcp.fixtures.schema;

/**
 * A consumer service an SDL {@code @service} names, and the seed of a backing closure: the class its
 * method returns is what backs the SDL type the field is typed by, which is the population
 * {@code intent_type_backing_class} derives and the {@code schema} tool renders.
 *
 * <p>Its own package, deliberately apart from the {@code fixtures.code} classes: those are the
 * {@code code} tool's census subjects and one of its cases asserts a class's method list exactly, so a
 * method added there for a different tool's sake would break an assertion about something else.
 */
public class CardService {

    /** The producer whose return type backs {@code FilmSummary}. */
    public FilmSummary summary(int filmId) {
        return null;
    }

    /**
     * The producer that makes a {@code @table}-bound type answered a second way: the closure backs the
     * type this returns to with this class, where the type's own binding backs it with a generated jOOQ
     * record, and the store reports both rather than applying the walk's table-wins precedence.
     */
    public FilmSummary contested() {
        return null;
    }

    /**
     * The producer named by a field whose own name matches a column of its type's bound table, so the
     * structural reading and the authored one both fire and only the resolution says which wins.
     */
    public String describe() {
        return null;
    }
}
