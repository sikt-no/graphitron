package no.sikt.graphql.helpers.resolvers;

import java.util.*;

/**
 * Tracks which fields were provided in a GraphQL arguments map, without retaining values.
 * Built from {@code env.getArguments()} by stripping all values and keeping only the key structure.
 * Object fields become named children, list items become indexed children ("0", "1", ...),
 * and leaf values (including explicit nulls) become {@link #EMPTY} presence markers.
 */
public class ArgumentPresence {
    public static final ArgumentPresence EMPTY = new ArgumentPresence(Map.of());

    private final Map<String, ArgumentPresence> children;

    public ArgumentPresence(Map<String, ArgumentPresence> children) {
        this.children = children;
    }

    /** Check if a direct child field was provided. */
    public boolean hasField(String name) {
        return children.containsKey(name);
    }

    /** Navigate to a child field's subtree. Returns EMPTY if not present. */
    public ArgumentPresence child(String name) {
        return children.getOrDefault(name, EMPTY);
    }

    /** Navigate to a list item's subtree by index. Returns EMPTY if not present. */
    public ArgumentPresence itemAt(int index) {
        return children.getOrDefault(String.valueOf(index), EMPTY);
    }

    /**
     * Wrap this node as a single-item list presence.
     * Used by RecordTransformer single-item overloads that wrap input in List.of().
     */
    public ArgumentPresence asSingleItem() {
        return new ArgumentPresence(Map.of("0", this));
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }

    /**
     * Build from a GraphQL arguments map, stripping all values and retaining only key structure.
     * Explicit nulls are preserved as present (key exists → EMPTY node), while absent fields
     * have no entry at all. This mirrors GraphQL Java's behavior where explicitly provided null
     * fields appear as keys with null values in {@code env.getArguments()}, while omitted fields
     * are absent from the map entirely.
     */
    @SuppressWarnings("unchecked")
    public static ArgumentPresence build(Map<String, Object> arguments) {
        var result = new LinkedHashMap<String, ArgumentPresence>();
        for (var entry : arguments.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                result.put(key, build((Map<String, Object>) value));
            } else if (value instanceof List<?> list) {
                var listChildren = new LinkedHashMap<String, ArgumentPresence>();
                for (int i = 0; i < list.size(); i++) {
                    var item = list.get(i);
                    if (item instanceof Map<?, ?>) {
                        listChildren.put(String.valueOf(i), build((Map<String, Object>) item));
                    }
                }
                result.put(key, new ArgumentPresence(listChildren));
            } else {
                // Leaf value (scalar, enum, or explicit null) — presence recorded, value discarded.
                result.put(key, EMPTY);
            }
        }
        return new ArgumentPresence(result);
    }
}
