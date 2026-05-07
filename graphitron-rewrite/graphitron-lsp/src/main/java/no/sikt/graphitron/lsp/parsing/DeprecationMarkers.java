package no.sikt.graphitron.lsp.parsing;

import no.sikt.graphitron.lsp.code_action.SdlAction.DeprecationTarget;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks {@code directives.graphqls} once and emits the canonical set
 * of deprecation targets, covering both marker shapes:
 *
 * <ul>
 *   <li><b>Member-level.</b> SDL {@code @deprecated()} directive on an
 *       argument or input-type field. Today:
 *       {@code ExternalCodeReference.name} and
 *       {@code @asConnection(connectionName:)}. Yields
 *       {@link DeprecationTarget.Member}.</li>
 *   <li><b>Whole-directive.</b> Javadoc-style {@code @deprecated <reason>}
 *       token in the directive's SDL description string. The GraphQL
 *       spec disallows {@code @deprecated} on directive definitions,
 *       so whole-directive deprecation needs a parallel marker that
 *       lives in source close to the deprecation target. Today:
 *       {@code @index} (description carries
 *       {@code "@deprecated use @order(index:) instead"}). Yields
 *       {@link DeprecationTarget.WholeDirective}.</li>
 * </ul>
 *
 * <p>The drift test in {@code SdlActionDriftTest} consumes this set
 * to assert bidirectional coverage against the {@code SdlAction}
 * registry plus its {@code MANUAL_MIGRATION_DEPRECATIONS} allow-list.
 *
 * <p>Parsing is regex-driven over the SDL text. The structured marker
 * is matched as the literal token {@code @deprecated} preceded by a
 * word boundary inside a description string; prose like "Deprecated:"
 * (capitalised, with a colon) does not match the regex and so is not
 * picked up as a structured marker. This is by design: the structured
 * form is what tools key on; prose stays for humans.
 */
public final class DeprecationMarkers {

    private DeprecationMarkers() {}

    private static final String DIRECTIVES_RESOURCE =
        "/no/sikt/graphitron/rewrite/schema/directives.graphqls";

    /**
     * Matches an SDL {@code @deprecated()} or {@code @deprecated} marker
     * on an argument or input-type field. We capture the preceding
     * field/argument name so the qualified key
     * ({@code <parent>.<member>}) can be assembled by walking back to
     * the closest {@code directive @<name>} or {@code input <Name>}
     * declaration.
     */
    private static final Pattern SDL_DEPRECATED_MARKER =
        Pattern.compile("(\\w+)\\s*:\\s*[\\w\\[\\]!]+\\s*@deprecated\\b");

    /** {@code directive @<name>} or {@code input <Name>} declaration at column 0. */
    private static final Pattern PARENT_DECLARATION =
        Pattern.compile("^(?:directive\\s+@(\\w+)|input\\s+(\\w+))", Pattern.MULTILINE);

    /**
     * Description string preceding a directive declaration. The
     * description may be triple-quoted or single-quoted; we capture the
     * inner text plus the directive name that follows.
     *
     * <p>Group 1 (or 2) is the description body; group 3 is the
     * directive name.
     */
    private static final Pattern DIRECTIVE_WITH_DESCRIPTION =
        Pattern.compile(
            "(?:\"\"\"((?:(?!\"\"\")[\\s\\S])*)\"\"\"|\"([^\"\\n]*)\")\\s*\\n?\\s*"
            + "directive\\s+@(\\w+)",
            Pattern.MULTILINE);

    /** Javadoc-style deprecation token in a description string. */
    private static final Pattern DESCRIPTION_DEPRECATED_TOKEN =
        Pattern.compile("(?<![A-Za-z0-9])@deprecated\\b");

    /**
     * Parses the bundled {@code directives.graphqls} resource.
     * Convenience for production callers.
     */
    public static Set<DeprecationTarget> parseFromClasspath() {
        try (InputStream in = DeprecationMarkers.class.getResourceAsStream(DIRECTIVES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "classpath resource not found: " + DIRECTIVES_RESOURCE
                    + " (the graphitron module's directives.graphqls must be on the classpath)");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                "failed to read " + DIRECTIVES_RESOURCE, e);
        }
    }

    /**
     * Parses the supplied SDL text. Used by tests with synthetic
     * fixtures and by {@link #parseFromClasspath()} with the bundled
     * resource.
     */
    public static Set<DeprecationTarget> parse(String sdl) {
        var out = new LinkedHashSet<DeprecationTarget>();
        out.addAll(extractMemberDeprecations(sdl));
        out.addAll(extractWholeDirectiveDeprecations(sdl));
        return Set.copyOf(out);
    }

    private static Set<DeprecationTarget> extractMemberDeprecations(String sdl) {
        var out = new LinkedHashSet<DeprecationTarget>();
        Matcher m = SDL_DEPRECATED_MARKER.matcher(sdl);
        while (m.find()) {
            String member = m.group(1);
            String parent = nearestParentBefore(sdl, m.start());
            if (parent == null) continue;
            // Members under a directive declaration get the @ prefix on
            // parent (e.g. @asConnection.connectionName); members under
            // an input declaration use the input type name as parent
            // (e.g. ExternalCodeReference.name). Distinguish by checking
            // whether the declaration was a directive or an input.
            boolean isDirective = parent.startsWith("@");
            String parentKey = isDirective ? parent : parent;
            out.add(new DeprecationTarget.Member(parentKey, member));
        }
        return out;
    }

    private static Set<DeprecationTarget> extractWholeDirectiveDeprecations(String sdl) {
        var out = new LinkedHashSet<DeprecationTarget>();
        Matcher m = DIRECTIVE_WITH_DESCRIPTION.matcher(sdl);
        while (m.find()) {
            String description = m.group(1) != null ? m.group(1) : m.group(2);
            String directive = m.group(3);
            if (description == null || directive == null) continue;
            if (DESCRIPTION_DEPRECATED_TOKEN.matcher(description).find()) {
                out.add(new DeprecationTarget.WholeDirective(directive));
            }
        }
        return out;
    }

    /**
     * Returns the closest preceding {@code directive @<name>} (with
     * leading {@code @}) or {@code input <Name>} declaration, or
     * {@code null} if none precedes {@code idx}.
     */
    private static String nearestParentBefore(String text, int idx) {
        Matcher m = PARENT_DECLARATION.matcher(text.substring(0, idx));
        String parent = null;
        while (m.find()) {
            String directive = m.group(1);
            String input = m.group(2);
            if (directive != null) parent = "@" + directive;
            else if (input != null) parent = input;
        }
        return parent;
    }
}
