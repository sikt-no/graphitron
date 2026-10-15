package no.sikt.graphitron.model.diagnostics;

import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.BUILD_WARNING_STALE_MODULE;

/**
 * The workspace modules a graph's run read stale, as {@code build_warning_stale_module} states them,
 * and what a session owes saying about them: a module once when it goes stale and once more when it
 * is compiled again, rather than on every round.
 */
public final class StaleModules {

    /** One change to say: a module now read stale, in its sentence, or one no longer read stale. */
    public record Change(boolean stale, String coordinate, String message) {}

    private final Map<String, String> said = new LinkedHashMap<>();

    /** The modules {@code graphName}'s run read stale, by coordinate, each with its sentence. */
    public static Map<String, String> read(DSLContext dsl, String graphName) {
        var w = BUILD_WARNING_STALE_MODULE;
        var stale = new LinkedHashMap<String, String>();
        dsl.select(w.COORDINATE, w.MESSAGE).from(w)
            .where(w.GRAPH_NAME.eq(graphName)).orderBy(w.COORDINATE)
            .forEach(row -> stale.put(row.value1(), row.value2()));
        return stale;
    }

    /** The changes owed for {@code stale} against what this session said before, now said. */
    public synchronized List<Change> update(Map<String, String> stale) {
        var changes = new ArrayList<Change>();
        for (var module : stale.entrySet()) {
            if (!module.getValue().equals(said.get(module.getKey()))) {
                changes.add(new Change(true, module.getKey(), module.getValue()));
            }
        }
        for (String coordinate : said.keySet()) {
            if (!stale.containsKey(coordinate)) {
                changes.add(new Change(false, coordinate, coordinate + " is no longer read stale"));
            }
        }
        said.clear();
        said.putAll(stale);
        return changes;
    }
}
