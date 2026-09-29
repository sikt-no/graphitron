package no.sikt.graphitron.rewrite.test.services;

/**
 * Methods whose only job is the shape of their parameters, for the cases that ask how a decoded node
 * id reaches a Java slot. What they return does not matter to those cases, and nothing in the
 * example schema names them.
 *
 * <p>{@link #byIds(String)} and {@link #byIds(int)} are an overload whose halves name their parameter
 * alike, so an argument named {@code ids} matches a parameter in both and resolves to two candidate
 * slots. {@link FilmService#topRated(int, org.jooq.DSLContext)} is the library's other overload, and
 * its halves name their parameters differently, which is the shape where a name finds one.
 *
 * <p>{@link #untyped(int)} is one method taking a primitive. A primitive names no class, so the slot
 * it offers has no type for a key column to be compared against.
 */
public final class NodeIdSlotService {

    private NodeIdSlotService() {}

    /** One half of the overload, taking the id as text. See the class comment. */
    public static String byIds(String ids) {
        return "text:" + ids;
    }

    /** The other half, taking it as a number. See the class comment. */
    public static String byIds(int ids) {
        return "number:" + ids;
    }

    /** The one method, taking a primitive. See the class comment. */
    public static String untyped(int ids) {
        return "untyped:" + ids;
    }
}
