package no.sikt.graphitron.emit.stub;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Row1;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service methods in every shape a call takes: at a root a static method and one that throws a
 * checked exception; under an object the batch's keys taken as the frame's records, as rows, as the
 * parent table's records and as a list in the batch's order, answered as a map by key or a list.
 */
public final class Calls {

    private Calls() {}

    public static Integer count(DSLContext dsl) {
        return dsl == null ? -1 : 42;
    }

    public static String failing(DSLContext dsl) throws Exception {
        throw new Exception("checked");
    }

    public static Map<Record1<Integer>, String> byRecord(Set<Record1<Integer>> keys, DSLContext dsl) {
        var out = new HashMap<Record1<Integer>, String>();
        keys.forEach(k -> out.put(k, "record " + k.value1()));
        return out;
    }

    public static Map<Row1<Integer>, String> byRow(Set<Row1<Integer>> keys) {
        var out = new HashMap<Row1<Integer>, String>();
        keys.forEach(k -> out.put(k, "row " + k.field1()));
        return out;
    }

    public static Map<FilmRecord, String> byFilm(Set<FilmRecord> films) {
        var out = new HashMap<FilmRecord, String>();
        films.forEach(f -> {
            if (f.getFilmId() != 2) out.put(f, "film " + f.getFilmId());
        });
        return out;
    }

    public static List<String> inOrder(List<Record1<Integer>> keys) {
        return keys.stream().map(k -> "at " + k.value1()).toList();
    }
}
