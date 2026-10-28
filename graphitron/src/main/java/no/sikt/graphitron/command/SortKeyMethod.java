package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.OrderBySpec;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The emitted method a {@code path:} sort key renders through: the entry the method's body
 * walks, and the ref the naming vocabulary minted for it on the unit that renders the order.
 * A command whose fixed order carries path entries carries one of these per entry, so the
 * renderer that emits the order calls the method by the same ref the host class emits it under.
 */
public record SortKeyMethod(OrderBySpec.SortKeyEntry entry, UnitMethodRef method) {

    public SortKeyMethod {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(method, "method");
        if (!(entry.key() instanceof OrderBySpec.SortKey.Path)) {
            throw new IllegalArgumentException(
                "only a path: sort key renders through an emitted method; a method: key calls the"
                + " consumer's method directly");
        }
    }

    /**
     * Checks that {@code methods} names exactly the path entries {@code spec} carries, so every
     * rendered call has an emitted method behind it and nothing is emitted that no order calls.
     */
    public static List<SortKeyMethod> requireCovering(List<SortKeyMethod> methods, OrderBySpec.Fixed spec) {
        var carried = new HashSet<Integer>();
        for (var method : methods) carried.add(method.entry().ordinal());
        var needed = new HashSet<Integer>();
        for (var key : spec.sortKeys()) {
            if (key.key() instanceof OrderBySpec.SortKey.Path) needed.add(key.ordinal());
        }
        if (!carried.equals(needed) || carried.size() != methods.size()) {
            throw new IllegalArgumentException(
                "the sort-key methods " + carried + " do not match the order's path entries " + needed);
        }
        return List.copyOf(methods);
    }
}
