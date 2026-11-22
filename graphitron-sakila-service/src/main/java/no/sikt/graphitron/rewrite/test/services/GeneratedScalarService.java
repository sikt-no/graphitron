package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Record1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fixture for a generated scalar taken at a call by its components: each method hands back what it
 * was given, so a test reads which components reached it and that a wrong value reached nothing.
 * Graphitron decodes the value and checks it before calling, so these methods see only components,
 * never the string a client sent.
 */
public final class GeneratedScalarService {

    private GeneratedScalarService() {}

    /** A path's four components, joined by bars in the order the record holds them. */
    public static String along(String text, String start, String steps, String end) {
        return text + "|" + start + "|" + steps + "|" + end;
    }

    /**
     * A path's four components, a type name's name and the end type of a path one input object
     * further down, all read out of one input object, joined by bars in that order.
     */
    public static String alongInput(String text, String start, String steps, String end, String name,
                                    String innerEnd) {
        return along(text, start, steps, end) + "|" + name + "|" + innerEnd;
    }

    /** Per film key, the start and end types of the path one field was given, joined by an arrow. */
    public static Map<Record1<Integer>, String> alongForFilms(Set<Record1<Integer>> filmIds, String start,
                                                              String end) {
        var out = new LinkedHashMap<Record1<Integer>, String>();
        for (var filmId : filmIds) {
            out.put(filmId, start + "->" + end);
        }
        return out;
    }

    /** The end type of a path, in a payload whose errors field a refusal is routed into. */
    public static GeneratedScalarPayload endType(String end) {
        return new GeneratedScalarPayload(end, null);
    }
}
