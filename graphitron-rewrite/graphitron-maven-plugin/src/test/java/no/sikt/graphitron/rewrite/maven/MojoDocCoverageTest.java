package no.sikt.graphitron.rewrite.maven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bidirectional drift-protection seam between the Mojo classes' user-configurable
 * {@code @Parameter}-annotated fields and the per-parameter rows in
 * {@code docs/manual/reference/mojo-configuration.adoc}.
 *
 * <p>Asserts every editable parameter listed in {@code META-INF/maven/plugin.xml}
 * (the generated descriptor for {@code generate}, {@code validate}, and {@code dev})
 * has a row in the doc, and every parameter row in the doc corresponds to an editable
 * parameter on one of those goals. Readonly parameters ({@code <editable>false</editable>}
 * in the descriptor; for example the Maven-injected {@code project}) are excluded
 * because they are not user-configurable and do not appear in the doc.
 *
 * <p>The {@code @Parameter} annotation has {@code RetentionPolicy.CLASS} (not
 * {@code RUNTIME}), so reflection on field annotations does not see it. The plugin
 * descriptor is the canonical metadata source the {@code maven-plugin-plugin}
 * generates from those annotations at the {@code process-classes} phase, before the
 * {@code test} phase runs, so it is reliably on disk by the time this test executes.
 *
 * <p>R68 Phase 4. Companion to {@code DirectiveDocCoverageTest}; same shape, scoped
 * to the Mojo parameter surface rather than the SDL directive surface. A new
 * {@code @Parameter} field cannot land green without a doc row; a removed field
 * forces the row's removal.
 */
class MojoDocCoverageTest {

    private static final String DOCS_PATH = "docs/manual/reference/mojo-configuration.adoc";
    private static final String PLUGIN_XML_PATH = "target/classes/META-INF/maven/plugin.xml";

    /** Goals whose parameters this test verifies against the doc. */
    private static final Set<String> VERIFIED_GOALS = Set.of("generate", "validate", "dev");

    /**
     * Splits the doc text on AsciiDoc table delimiters ({@code |===}). The body
     * blocks contain the table's header row and rows; we use that to identify
     * the parameter tables specifically (vs the goals table or the binding tables).
     */
    private static final Pattern TABLE_DELIM = Pattern.compile("^\\|===\\s*$", Pattern.MULTILINE);

    /**
     * Identifies the parameter-tables specifically: their header row begins with
     * {@code | Name | Type | Default}. This shape distinguishes them from the goals
     * table ({@code | Goal | Default phase}) and the binding tables
     * ({@code | Child | Type | Description}).
     */
    private static final Pattern PARAMETER_TABLE_HEADER =
        Pattern.compile("^\\|\\s+Name\\s+\\|\\s+Type\\s+\\|\\s+Default\\b", Pattern.MULTILINE);

    /**
     * Matches a column-1 entry of the shape {@code | `<identifier>`} on its own
     * line (modulo trailing whitespace). Within a parameter-table body these are
     * always parameter rows; within other contexts they could be type-column or
     * default-column entries, so this pattern is only applied after a parameter
     * table is identified.
     */
    private static final Pattern FIRST_COL_BACKTICKED =
        Pattern.compile("^\\|\\s+`(\\w+)`\\s*$", Pattern.MULTILINE);

    /** Splits the descriptor into one block per {@code <mojo>}. */
    private static final Pattern MOJO_BLOCK =
        Pattern.compile("<mojo>(.*?)</mojo>", Pattern.DOTALL);

    /** Reads the {@code <goal>} of a single mojo block. */
    private static final Pattern MOJO_GOAL =
        Pattern.compile("<goal>(\\w+)</goal>");

    /**
     * Matches a {@code <parameter>} block that is editable (the {@code @Parameter}
     * annotation's {@code readonly = true} maps to {@code <editable>false</editable>}).
     */
    private static final Pattern EDITABLE_PARAMETER = Pattern.compile(
        "<parameter>\\s*"
            + "<name>(\\w+)</name>\\s*"
            + "<type>[^<]+</type>\\s*"
            + "<required>(?:true|false)</required>\\s*"
            + "<editable>true</editable>",
        Pattern.DOTALL);

    @Test
    void everyMojoParameterHasADocRowAndViceVersa() throws IOException {
        Set<String> parameters = parametersFromDescriptor();
        Set<String> docRows = parametersFromDoc();

        Set<String> missingRows = new TreeSet<>(parameters);
        missingRows.removeAll(docRows);

        Set<String> staleRows = new TreeSet<>(docRows);
        staleRows.removeAll(parameters);

        assertThat(parameters)
            .as("at least one editable parameter must be present in plugin.xml; "
                + "did the maven-plugin-plugin descriptor generation run?")
            .isNotEmpty();
        assertThat(missingRows)
            .as("editable parameters in plugin.xml without a matching row in "
                + "mojo-configuration.adoc; add the missing row(s)")
            .isEmpty();
        assertThat(staleRows)
            .as("parameter rows in mojo-configuration.adoc with no matching editable "
                + "parameter in plugin.xml (across the generate / validate / dev goals); "
                + "remove the stale row(s)")
            .isEmpty();
    }

    private static Set<String> parametersFromDescriptor() throws IOException {
        Path descriptor = Path.of(PLUGIN_XML_PATH).toAbsolutePath();
        if (!Files.isRegularFile(descriptor)) {
            throw new IllegalStateException(
                "Plugin descriptor not found at " + descriptor + ". The "
                    + "maven-plugin-plugin descriptor goal runs at process-classes "
                    + "phase; ensure the module has been compiled before running tests.");
        }
        String text = Files.readString(descriptor, StandardCharsets.UTF_8);
        Set<String> names = new TreeSet<>();
        Matcher mojoMatcher = MOJO_BLOCK.matcher(text);
        while (mojoMatcher.find()) {
            String mojoBlock = mojoMatcher.group(1);
            Matcher goalMatcher = MOJO_GOAL.matcher(mojoBlock);
            if (!goalMatcher.find() || !VERIFIED_GOALS.contains(goalMatcher.group(1))) {
                continue;
            }
            Matcher paramMatcher = EDITABLE_PARAMETER.matcher(mojoBlock);
            while (paramMatcher.find()) {
                names.add(paramMatcher.group(1));
            }
        }
        return names;
    }

    private static Set<String> parametersFromDoc() throws IOException {
        Path docPath = locateDoc();
        String text = Files.readString(docPath, StandardCharsets.UTF_8);
        Set<String> names = new TreeSet<>();
        // For each parameter-table body, split rows on blank lines (the convention
        // throughout this doc puts each cell on its own line and separates rows
        // with one blank line). The column-1 cell is the row's first non-blank
        // line; subsequent cells (type, default, CLI property, description) are on
        // following lines and are skipped because we only read the first.
        for (String block : TABLE_DELIM.split(text)) {
            if (!PARAMETER_TABLE_HEADER.matcher(block).find()) continue;
            for (String row : block.split("\\R\\s*\\R+")) {
                if (PARAMETER_TABLE_HEADER.matcher(row).find()) continue;
                String firstLine = row.lines()
                    .filter(s -> !s.isBlank())
                    .findFirst()
                    .orElse("");
                Matcher m = FIRST_COL_BACKTICKED.matcher(firstLine);
                if (m.find()) names.add(m.group(1));
            }
        }
        return names;
    }

    /**
     * Walks up from the test working directory until it finds
     * {@code docs/manual/reference/mojo-configuration.adoc}. Surefire runs from the
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
