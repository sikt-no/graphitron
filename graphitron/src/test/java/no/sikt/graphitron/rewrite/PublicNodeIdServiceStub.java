package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.InventoryRecord;
import org.jooq.Result;

/**
 * Service methods whose parameter is named for a {@code @nodeId} argument, for the fixtures whose
 * subject is the parameter a decoded node id lands in. Four spellings of one signature, differing
 * only in the parameter's type, so a fixture picks the one whose type either agrees with the node
 * key or does not, and one whose return type binds no table at all.
 *
 * <p>Public and separate from {@link TestServiceStub}, and both halves of that are load-bearing. The
 * classpath census takes public top-level classes only, so a package-private stub is invisible to it
 * however well reflection resolves the same method; and the relations these fixtures read are the
 * census's, not reflection's. Separate because a fixture that needs these signatures also needs the
 * census, and a census over the whole test tree is what {@code TestServiceStub}'s own callers do not
 * pay for.
 *
 * <p>Every method returns without a body being reachable: the fixtures classify and capture, and
 * nothing calls them.
 */
public class PublicNodeIdServiceStub {

    private PublicNodeIdServiceStub() {}

    /**
     * The parameter typed as the wire format, which is the shape that used to be handed the base64
     * string with nothing in the build saying a word.
     */
    public static Result<FilmRecord> getFilmsByStringKey(String key) {
        throw new UnsupportedOperationException();
    }

    /** The parameter typed as the key column jOOQ binds, which is what a decode can fill. */
    public static Result<FilmRecord> getFilmsByIntegerKey(Integer key) {
        throw new UnsupportedOperationException();
    }

    /**
     * The parameter typed as a node type's own generated record, which takes the whole decoded tuple
     * whatever the key's arity.
     */
    public static Result<FilmRecord> getFilmsByInventoryKey(InventoryRecord key) {
        throw new UnsupportedOperationException();
    }

    /** The parameter typed as a primitive, which the census reads no class at. */
    public static Result<FilmRecord> getFilmsByPrimitiveKey(int key) {
        throw new UnsupportedOperationException();
    }

    /**
     * A producer whose own return type binds no table, so a bare {@code @nodeId} at its argument has
     * no table to inherit a target from and the inference has to say so.
     */
    public static String getTitleByStringKey(String key) {
        throw new UnsupportedOperationException();
    }

    /**
     * A delete surface's producer: the return type binds no table either, and the table its
     * arguments bind against is the one {@code @mutation(table:)} names.
     */
    public static String deleteFilmByIntegerKey(Integer key) {
        throw new UnsupportedOperationException();
    }
}
