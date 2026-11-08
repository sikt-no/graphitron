package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static org.jooq.impl.DSL.coalesce;

/**
 * Each argument and input-field occurrence's path of names, from its argument down, read off the
 * parent links: an argument's path is its own name, an input field's its parent's and its own. A step
 * is a list where its argument or input field is declared one, and non-null where it is declared so.
 */
final class ArgumentPaths {

    private ArgumentPaths() {}

    /** The names from the argument down, and the positions on them that are lists and non-null. */
    record Path(List<String> names, Set<Integer> lists, Set<Integer> nonNull) {
        Path {
            names = List.copyOf(names);
            lists = Set.copyOf(lists);
            nonNull = Set.copyOf(nonNull);
        }

        /** Whether every step is declared non-null, so the value at the end is never absent. */
        boolean present() {
            return nonNull.size() == names.size();
        }
    }

    /** The graph's argument and input-field occurrences' paths, by coordinate. */
    static Map<String, Path> read(StoreHandle store) {
        var o = GRAPHITRON_OCCURRENCE;
        var a = GRAPHITRON_ARGUMENT;
        var f = GRAPHITRON_FIELD;
        record Step(String parent, String name, boolean argument, boolean list, boolean nonNull) {}
        var steps = new HashMap<String, Step>();
        store.dsl().select(o.COORDINATE, o.PARENT_COORDINATE, o.NAME, o.KIND, coalesce(a.IS_LIST, f.IS_LIST),
                coalesce(a.NON_NULL, f.NON_NULL))
            .from(o)
            .leftJoin(a).on(a.GRAPH_NAME.eq(o.GRAPH_NAME), a.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(o.GRAPH_NAME.eq(store.graphName()), o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"))
            .forEach(r -> steps.put(r.value1(), new Step(r.value2(), r.value3(),
                "FIELD_ARGUMENT".equals(r.value4()), Boolean.TRUE.equals(r.value5()),
                Boolean.TRUE.equals(r.value6()))));
        var out = new HashMap<String, Path>();
        steps.forEach((coordinate, step) -> {
            var chain = new ArrayList<Step>();
            for (var at = step; at != null; at = at.argument() ? null : steps.get(at.parent())) {
                chain.addFirst(at);
            }
            var lists = new HashSet<Integer>();
            var nonNull = new HashSet<Integer>();
            for (int i = 0; i < chain.size(); i++) {
                if (chain.get(i).list()) lists.add(i);
                if (chain.get(i).nonNull()) nonNull.add(i);
            }
            out.put(coordinate, new Path(chain.stream().map(Step::name).toList(), lists, nonNull));
        });
        return out;
    }
}
