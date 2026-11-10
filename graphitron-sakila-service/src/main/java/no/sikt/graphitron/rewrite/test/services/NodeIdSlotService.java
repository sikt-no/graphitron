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
 *
 * <p>The tuple methods take a key as a jOOQ {@code RecordN} or {@code RowN}: a film's one-column key
 * as a {@code Record1}, a film actor's two-column key (actor_id, film_id, both integers) as a
 * {@code Record2} and as a list of {@code Row2}, and then a {@code Row2} whose second type is not
 * the key column's and a {@code Record3} one wider than the key.
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

    /** A one-column key as its tuple. See the class comment. */
    public static String filmByTuple(org.jooq.Record1<Integer> key) {
        return "film:" + key.value1();
    }

    /** A two-column key as its tuple. See the class comment. */
    public static String pairByTuple(org.jooq.Record2<Integer, Integer> key) {
        return "pair:" + key.value1() + ":" + key.value2();
    }

    /** A list of two-column keys as row values. See the class comment. */
    public static String pairsByRows(java.util.List<org.jooq.Row2<Integer, Integer>> keys) {
        return "pairs:" + keys.size();
    }

    /** A two-column tuple whose second type is not the key column's. See the class comment. */
    public static String pairByMistypedRow(org.jooq.Row2<Integer, String> key) {
        return "mistyped";
    }

    /** A tuple one wider than the key. See the class comment. */
    public static String pairByWideTuple(org.jooq.Record3<Integer, Integer, Integer> key) {
        return "wide";
    }
}
