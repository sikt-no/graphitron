package no.sikt.graphitron.rewrite;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Threaded through {@link BuildContext#classifyInputField} to carry the structural facts a
 * classifier branch needs but a single recursive descent cannot recover from the local field
 * alone: the set of currently-expanding nested input types (for circularity detection), and
 * the inherited {@code override} flag from the enclosing arg- or field-level {@code @condition}
 * cascade.
 *
 * <p>The classifier itself does not read {@link #enclosingOverride} to decide a variant
 * (column-miss always lifts to {@link InputField.UnboundField} regardless of cascade); the
 * record carries it for the future-growth axes the spec calls out so adding the
 * mutation-context lift or a nested-input cascade walker arm does not touch every call site.
 *
 * <p>Use {@link #root()} for the top-level entry and {@link #expanding(String)} for the
 * recursive descent through {@code NestingField}. The {@code with*}-style helpers return a
 * new context so the record stays effectively immutable.
 */
public record ClassifyContext(Set<String> expandingTypes, boolean enclosingOverride) {

    public ClassifyContext {
        expandingTypes = Set.copyOf(expandingTypes);
    }

    public static ClassifyContext root() {
        return new ClassifyContext(Set.of(), false);
    }

    public static ClassifyContext withEnclosingOverride(boolean enclosingOverride) {
        return new ClassifyContext(Set.of(), enclosingOverride);
    }

    public ClassifyContext expanding(String typeName) {
        var s = new LinkedHashSet<>(expandingTypes);
        s.add(typeName);
        return new ClassifyContext(s, enclosingOverride);
    }

    public ClassifyContext withOverride(boolean enclosingOverride) {
        return new ClassifyContext(expandingTypes, enclosingOverride);
    }

    public boolean isExpanding(String typeName) {
        return expandingTypes.contains(typeName);
    }
}
