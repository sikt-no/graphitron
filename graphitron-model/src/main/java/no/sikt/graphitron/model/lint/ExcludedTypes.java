package no.sikt.graphitron.model.lint;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code excludedTypes} globs of a {@link LintConfig}, compiled once and asked by name.
 *
 * <p>One home for the matching rule rather than one per producer. The glob syntax is the
 * consumer-facing surface the {@code <lint>} block documents ({@code *} any run of characters,
 * {@code ?} one character), so two producers applying it two ways would be one population rule
 * with two meanings, which is the drift a shared point removes. The engine applies it before
 * dispatching its AST walk; a producer minted outside that walk applies it to the coordinate it
 * is about to attach a finding to.
 *
 * <p>It lives here rather than beside the engine because the engine is in {@code graphitron} and
 * the store-reading producers are in this module, which {@code graphitron} depends on. Beside
 * {@link LintConfig}, whose {@code excludedTypePatterns} it compiles, is the one place both can
 * reach.
 *
 * <p>The exclusion stays scoped to what carries a type name. A classifier advisory arrives
 * pre-formed on the schema's warning list with no structured owning type to match against, so it
 * is suppressible by rule id alone; that asymmetry is stated on {@link LintConfig} and this type
 * does not change it.
 */
public final class ExcludedTypes {

    private static final ExcludedTypes NONE = new ExcludedTypes(List.of());

    private final List<Pattern> matchers;

    private ExcludedTypes(List<Pattern> matchers) {
        this.matchers = matchers;
    }

    /** Compiles the configured globs; the empty list excludes nothing. */
    public static ExcludedTypes of(List<String> globs) {
        return globs.isEmpty() ? NONE : new ExcludedTypes(globs.stream()
            .map(ExcludedTypes::globToPattern).toList());
    }

    /** The globs a config carries. */
    public static ExcludedTypes of(LintConfig config) {
        return of(config.excludedTypePatterns());
    }

    /** Whether {@code typeName} matches one of the configured globs. */
    public boolean matches(String typeName) {
        for (Pattern matcher : matchers) {
            if (matcher.matcher(typeName).matches()) return true;
        }
        return false;
    }

    /**
     * Translates a type-name glob ({@code *} any run, {@code ?} one char) into an anchored regex,
     * escaping every other regex metacharacter so a pattern like {@code Legacy*} matches literally.
     */
    private static Pattern globToPattern(String glob) {
        var sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        return Pattern.compile(sb.toString());
    }
}
