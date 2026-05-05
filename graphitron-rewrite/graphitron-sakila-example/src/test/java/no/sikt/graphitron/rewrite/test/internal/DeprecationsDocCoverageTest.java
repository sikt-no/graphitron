package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bidirectional drift-protection seam between the rewrite's SDL {@code @deprecated()}
 * markers in {@code directives.graphqls} and the deprecations index at
 * {@code docs/manual/reference/deprecations.adoc}.
 *
 * <p>The GraphQL spec only permits {@code @deprecated} on argument definitions,
 * input field definitions, field definitions, and enum values; it cannot mark a
 * whole directive definition. The verifier therefore covers the SDL-detectable
 * surface plus a small allow-list of whole-directive deprecations that are
 * documented prose-side on the directive's own reference page.
 *
 * <p>R68 Phase 5 closing slice. A future PR that adds a {@code @deprecated()}
 * marker to a directive argument or input field cannot land green without adding
 * a row to {@code deprecations.adoc}; a PR that removes a marker must remove the
 * row (or the build fails).
 */
@UnitTier
class DeprecationsDocCoverageTest {

    private static final String DIRECTIVES_RESOURCE =
        "/no/sikt/graphitron/rewrite/schema/directives.graphqls";

    private static final String DOCS_PAGE_PATH = "docs/manual/reference/deprecations.adoc";

    /**
     * Matches an SDL {@code @deprecated} marker preceded by an argument or
     * input-field declaration on the same line: {@code <name>: <Type>[!]
     * @deprecated(...)}. The capture group is the field/argument name.
     */
    private static final Pattern SDL_DEPRECATED_MARKER =
        Pattern.compile("(\\w+)\\s*:\\s*[\\w\\[\\]!]+\\s*@deprecated\\b");

    /**
     * Matches an SDL {@code directive @<name>(...)} or {@code input <Name>}
     * declaration at column 0. We walk backwards from each {@code @deprecated}
     * hit to the closest such declaration to get the parent name; the qualified
     * key is then {@code <parent>.<member>}.
     */
    private static final Pattern PARENT_DECLARATION =
        Pattern.compile("^(?:directive\\s+@(\\w+)|input\\s+(\\w+))", Pattern.MULTILINE);

    /**
     * Whole-directive deprecations cannot carry an SDL {@code @deprecated} marker
     * (the spec disallows it on directive definitions). They are documented on
     * the directive's own reference page and aggregated in the deprecations
     * index. A new whole-directive deprecation must be added here AND have a row
     * in {@code deprecations.adoc}.
     */
    private static final Set<String> WHOLE_DIRECTIVE_DEPRECATIONS = Set.of("index");

    @Test
    void everySdlDeprecatedMarkerHasARowInTheIndex() throws IOException {
        List<String> qualifiedKeys = sdlDeprecatedQualifiedKeys();
        String docText = readDocPage();

        assertThat(qualifiedKeys)
            .as("at least one @deprecated() marker must exist in directives.graphqls; "
                + "if all deprecations have been removed, also remove the deprecations.adoc table")
            .isNotEmpty();

        Set<String> missing = new TreeSet<>();
        for (String qualified : qualifiedKeys) {
            int dot = qualified.indexOf('.');
            String parent = qualified.substring(0, dot);
            String member = qualified.substring(dot + 1);
            // The doc must mention both parts somewhere; rows naturally include
            // both in the "Site" cell (e.g. `@asConnection(connectionName:)`,
            // `ExternalCodeReference.name`).
            if (!docText.contains(parent) || !docText.contains(member)) {
                missing.add(qualified);
            }
        }
        assertThat(missing)
            .as("SDL @deprecated() markers without a corresponding row in "
                + DOCS_PAGE_PATH + " (looked for both parent and member name); add a row")
            .isEmpty();
    }

    @Test
    void everyWholeDirectiveDeprecationHasARowInTheIndex() throws IOException {
        String docText = readDocPage();
        Set<String> missing = new TreeSet<>();
        for (String directive : WHOLE_DIRECTIVE_DEPRECATIONS) {
            if (!docText.contains("`@" + directive + "`")) {
                missing.add(directive);
            }
        }
        assertThat(missing)
            .as("whole-directive deprecations on the allow-list without a row in "
                + DOCS_PAGE_PATH + "; either add a row or remove the directive from "
                + "WHOLE_DIRECTIVE_DEPRECATIONS")
            .isEmpty();
    }

    private static List<String> sdlDeprecatedQualifiedKeys() throws IOException {
        try (InputStream in = DeprecationsDocCoverageTest.class.getResourceAsStream(DIRECTIVES_RESOURCE)) {
            assertThat(in)
                .as("classpath resource: " + DIRECTIVES_RESOURCE)
                .isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> keys = new LinkedHashSet<>();
            Matcher m = SDL_DEPRECATED_MARKER.matcher(text);
            while (m.find()) {
                String member = m.group(1);
                String parent = nearestParentBefore(text, m.start());
                keys.add(parent + "." + member);
            }
            return List.copyOf(keys);
        }
    }

    private static String nearestParentBefore(String text, int idx) {
        Matcher m = PARENT_DECLARATION.matcher(text.substring(0, idx));
        String parent = null;
        while (m.find()) {
            parent = m.group(1) != null ? m.group(1) : m.group(2);
        }
        if (parent == null) {
            throw new IllegalStateException(
                "No directive or input declaration found before @deprecated marker at offset " + idx);
        }
        return parent;
    }

    private static String readDocPage() throws IOException {
        Path docsPage = locateDocPage();
        return Files.readString(docsPage, StandardCharsets.UTF_8);
    }

    private static Path locateDocPage() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(DOCS_PAGE_PATH);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + DOCS_PAGE_PATH + " by walking up from " + cwd);
    }
}
