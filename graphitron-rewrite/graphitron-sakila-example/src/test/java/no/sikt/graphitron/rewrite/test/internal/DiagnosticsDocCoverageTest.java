package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bidirectional drift-protection seam between the validator's closed-set diagnostic
 * codes and the per-code paragraphs in {@code docs/manual/reference/diagnostics-glossary.adoc}.
 *
 * <p>Asserts every value of {@link RejectionKind}, {@link Rejection.AttemptKind}, and
 * {@link Rejection.EmitBlockReason} has an anchored {@code === <kebab-case-name>}
 * heading on the page, and every such heading corresponds to an enum value. A new
 * diagnostic added to one of these enums cannot land green without a doc paragraph;
 * an obsolete code's removal forces the heading's removal.
 *
 * <p>The structural variants ({@link Rejection.AuthorError.Structural},
 * {@link Rejection.InvalidSchema.Structural}, {@link Rejection.InvalidSchema.DirectiveConflict})
 * carry ad-hoc messages produced at the classifier site and have no enumerable code; they are
 * not part of this test's scope. The kind-level entries on the doc page describe how to read
 * those errors.
 *
 * <p>R68 Phase 5. Companion to {@code DirectiveDocCoverageTest} and {@code MojoDocCoverageTest};
 * same shape, scoped to the diagnostics surface rather than the SDL or Mojo surface.
 */
@UnitTier
class DiagnosticsDocCoverageTest {

    private static final String DOCS_PATH = "docs/manual/reference/diagnostics-glossary.adoc";

    /** Matches an AsciiDoc anchored level-3 heading: {@code [#anchor]\n=== text}. */
    private static final Pattern ANCHORED_LEVEL_3 = Pattern.compile(
        "^\\[#([\\w-]+)]\\s*\\n===\\s+([\\w-]+)\\s*$",
        Pattern.MULTILINE);

    @Test
    void everyDiagnosticEnumValueHasADocSectionAndViceVersa() throws IOException {
        Set<String> codes = enumValuesAsKebab();
        Set<String> headings = headingsFromDoc();

        Set<String> missingHeadings = new TreeSet<>(codes);
        missingHeadings.removeAll(headings);

        Set<String> staleHeadings = new TreeSet<>(headings);
        staleHeadings.removeAll(codes);

        assertThat(codes)
            .as("at least one diagnostic enum value must be present across "
                + "RejectionKind, Rejection.AttemptKind, Rejection.EmitBlockReason")
            .isNotEmpty();
        assertThat(missingHeadings)
            .as("diagnostic enum values without a matching heading in "
                + "diagnostics-glossary.adoc; add the missing paragraph(s)")
            .isEmpty();
        assertThat(staleHeadings)
            .as("headings in diagnostics-glossary.adoc with no matching enum value "
                + "in RejectionKind / Rejection.AttemptKind / Rejection.EmitBlockReason; "
                + "remove the stale paragraph(s)")
            .isEmpty();
    }

    private static Set<String> enumValuesAsKebab() {
        Set<String> codes = new TreeSet<>();
        codes.addAll(kebabValues(RejectionKind.values()));
        codes.addAll(kebabValues(Rejection.AttemptKind.values()));
        codes.addAll(kebabValues(Rejection.EmitBlockReason.values()));
        return codes;
    }

    private static Set<String> kebabValues(Enum<?>[] values) {
        return Arrays.stream(values)
            .map(e -> e.name().toLowerCase().replace('_', '-'))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> headingsFromDoc() throws IOException {
        Path docPath = locateDoc();
        String text = Files.readString(docPath, StandardCharsets.UTF_8);
        Set<String> headings = new TreeSet<>();
        Matcher m = ANCHORED_LEVEL_3.matcher(text);
        while (m.find()) {
            String heading = m.group(2);
            // The anchor (group 1) carries a category prefix (kind-, attempt-, emit-block-);
            // the heading text (group 2) is the bare kebab-case code we verify against.
            headings.add(heading);
        }
        return headings;
    }

    /**
     * Walks up from the test working directory until it finds
     * {@code docs/manual/reference/diagnostics-glossary.adoc}. Surefire runs from the
     * module directory, so the file is normally two parents up; the walk keeps the
     * test robust against future restructuring of the module layout.
     */
    private static Path locateDoc() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(DOCS_PATH);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + DOCS_PATH + " by walking up from " + cwd);
    }
}
