package no.sikt.graphitron.rewrite.selection;

import java.util.List;

/**
 * Represents a single field in a parsed GraphQL selection set.
 *
 * <p>Field names may contain dots (e.g. {@code some.dotted.field}), which is an
 * extension over the standard GraphQL grammar supported by {@link GraphQLSelectionParser}.
 */
public record ParsedField(
        String alias,
        String name,
        List<ParsedArgument> arguments,
        List<ParsedField> selectionSet
) {
    /** Returns {@code true} if this field has an alias. */
    public boolean hasAlias() {
        return alias != null;
    }

    /** Returns {@code true} if this field has at least one argument. */
    public boolean hasArguments() {
        return !arguments.isEmpty();
    }

    /** Returns {@code true} if this field has a nested selection set. */
    public boolean hasSelectionSet() {
        return !selectionSet.isEmpty();
    }
}
