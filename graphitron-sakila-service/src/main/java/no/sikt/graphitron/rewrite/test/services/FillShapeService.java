package no.sikt.graphitron.rewrite.test.services;

import org.jooq.DSLContext;

import java.util.List;
import java.util.Set;

/**
 * Signatures whose parameters take each kind of fill a call can be given, for the fact documents the
 * fill emitter's tests read. Only the fills are emitted and run there; these bodies never are.
 */
public final class FillShapeService {

    private FillShapeService() {}

    /**
     * Every fill that needs no building: the jOOQ context, an argument, an input field one level and
     * two levels down, a context key, the session handle and one of its members, and a primitive.
     */
    public static String everyFill(DSLContext dsl, Integer limit, List<Integer> ids, String deep, String userId,
                                   FilmKeySummary summary, String label, int count) {
        return null;
    }

    /** An enum argument as its name, as a Java constant read off an input field, and a list of them. */
    public static String moods(String moodName, FilmMood mood, List<FilmMood> moods) {
        return null;
    }

    /** A scalar read through one list of input objects, and through two. */
    public static String listed(List<Integer> ids, List<List<Integer>> groups) {
        return null;
    }

    /** A list argument, a list input field and a list of enum constants, each taken as a set. */
    public static String asSets(Set<Integer> ids, Set<Integer> inner, Set<FilmMood> moods) {
        return null;
    }

    /** An enum read through a list of input objects, which a fill alone cannot turn into its names. */
    public static String moodsThroughList(List<String> moods) {
        return null;
    }

    /**
     * An enum read through a list of input objects into a list of sets: each element arrives as the
     * enum's name, never as a set of them.
     */
    public static String moodSetsThroughList(List<Set<String>> moods) {
        return null;
    }
}
