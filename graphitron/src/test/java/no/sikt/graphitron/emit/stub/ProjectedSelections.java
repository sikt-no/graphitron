package no.sikt.graphitron.emit.stub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What each type's projection stub was handed, by type, so a test can see the selection it got. */
public final class ProjectedSelections {

    private static final Map<String, List<Object>> HANDED = new LinkedHashMap<>();

    private ProjectedSelections() {}

    public static synchronized void record(String typeName, Object selection) {
        HANDED.computeIfAbsent(typeName, k -> new ArrayList<>()).add(selection);
    }

    public static synchronized List<Object> handed(String typeName) {
        return List.copyOf(HANDED.getOrDefault(typeName, List.of()));
    }

    public static synchronized void clear() {
        HANDED.clear();
    }
}
