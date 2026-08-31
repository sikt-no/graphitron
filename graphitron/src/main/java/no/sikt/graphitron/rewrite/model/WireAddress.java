package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Where one input field's value sits in the coerced argument map graphql-java hands a resolver:
 * the top-level argument name, the key path from that argument down to the field, and whether the
 * field is list-shaped. Enough to ask "did the request carry a value here" without decoding it.
 *
 * <p>The same descent {@link CallSiteExtraction.NestedInputField} names for reading a value, kept
 * as its own record because a presence question is not a binding: it stops one step short of the
 * decode, and it belongs to the field that owns a generator-minted wrapper rather than to any
 * parameter the wrapper's callee happens to declare. {@link FkTargetConditionFilter} carries one
 * so the correlated {@code EXISTS} it mints can be gated on its own field's presence.
 *
 * <p>{@link #list()} is the field's own type axis, not its parameter's: an absent field and an
 * empty list both mean "no value supplied", which is the semantics the implicit filter arm already
 * gives both shapes.
 */
public record WireAddress(String outerArgName, List<String> path, boolean list) {

    public WireAddress {
        if (outerArgName == null || outerArgName.isBlank()) {
            throw new IllegalArgumentException("a wire address names the top-level argument it roots at");
        }
        path = List.copyOf(path);
        if (path.isEmpty()) {
            throw new IllegalArgumentException(
                "a wire address for input field under '" + outerArgName + "' carries at least one path segment; "
                + "the argument itself is not an input field");
        }
    }
}
