package no.sikt.graphitron.rewrite.maven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * (the generated descriptor for {@code generate}, {@code validate}, {@code capture} and
 * {@code dev}) has a row in the doc, and every parameter row in the doc corresponds to an editable
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
 * <p>Presence is not the whole of the claim. A parameter documented under the wrong heading is
 * documented wrongly: the shared table's own lead-in says its parameters apply to every goal, so a
 * dev-only parameter listed there tells the reader to configure something three goals will ignore.
 * {@link #everyRowSitsUnderTheGoalsThatDeclareIt()} pins the section each row sits in against the
 * goals the descriptor says declare it, which is what a union of every table against every goal
 * cannot see.
 *
 * <p>Companion to {@code DirectiveDocCoverageTest}; same shape, scoped
 * to the Mojo parameter surface rather than the SDL directive surface. A new
 * {@code @Parameter} field cannot land green without a doc row; a removed field
 * forces the row's removal.
 */
class MojoDocCoverageTest {

    private static final String DOCS_PATH = "docs/manual/reference/mojo-configuration.adoc";
    private static final String PLUGIN_XML_PATH = "target/classes/META-INF/maven/plugin.xml";

    /** Goals whose parameters this test verifies against the doc. */
    private static final Set<String> VERIFIED_GOALS =
        Set.of("generate", "validate", "capture", "dev");

    /**
     * Which goals a parameter documented under each heading must be declared by, exactly. Every
     * parameter table in the doc has to sit under a heading named here, so a new section of
     * parameters fails this test rather than being silently unchecked: an unlisted heading is a
     * claim about scope that nothing has checked.
     */
    private static final Map<String, Set<String>> SECTION_GOALS = Map.of(
        "Shared parameters", VERIFIED_GOALS,
        "`dev`-goal parameters", Set.of("dev"));

    /** Splits the doc into level-one sections, so a row can be attributed to the heading above it. */
    private static final Pattern SECTION_HEADING = Pattern.compile("^== (.+)$", Pattern.MULTILINE);

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

    /**
     * The descriptor declares every goal this test verifies. Without this the filter above is
     * silently one-sided: a goal missing from the descriptor (a dropped or misspelled
     * {@code @Mojo} annotation) contributes no parameters, so the coverage assertion passes by
     * having nothing to check rather than by the doc being right.
     */
    @Test
    void everyVerifiedGoalIsDeclaredInTheDescriptor() throws IOException {
        assertThat(goalsFromDescriptor())
            .as("goals this test verifies that the plugin descriptor does not declare")
            .containsAll(VERIFIED_GOALS);
    }

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
                + "parameter in plugin.xml (across the generate / validate / capture / dev "
                + "goals); "
                + "remove the stale row(s)")
            .isEmpty();
    }

    /**
     * The section a row sits under has to match the goals that declare the parameter. A parameter
     * every verified goal declares belongs under the shared heading; one only {@code dev} declares
     * belongs under the dev heading. Moving a parameter between Mojos without moving its row leaves
     * the doc telling a reader to configure something the goal will ignore, and the coverage
     * assertion above cannot report it: that one unions every table against every goal, so a row
     * that changes owner still matches.
     */
    @Test
    void everyRowSitsUnderTheGoalsThatDeclareIt() throws IOException {
        Map<String, Set<String>> declaringGoals = declaringGoalsFromDescriptor();
        Map<String, String> sectionByRow = parametersByDocSection();

        Map<String, String> misplaced = new TreeMap<>();
        sectionByRow.forEach((parameter, section) -> {
            Set<String> declared = declaringGoals.get(parameter);
            if (declared == null) {
                // A row with no parameter at all is the coverage test's finding, not this one's.
                return;
            }
            Set<String> expected = SECTION_GOALS.get(section);
            if (!declared.equals(expected)) {
                misplaced.put(parameter, "documented under '" + section + "' (which is for "
                    + new TreeSet<>(expected) + ") but declared by " + new TreeSet<>(declared));
            }
        });

        assertThat(sectionByRow)
            .as("no parameter rows were found; did the doc's table or heading shape change?")
            .isNotEmpty();
        assertThat(misplaced)
            .as("parameter rows in mojo-configuration.adoc sitting under a heading whose goals do "
                + "not match the goals declaring the parameter; move the row")
            .isEmpty();
    }

    /** Every editable parameter of a verified goal, against the goals that declare it. */
    private static Map<String, Set<String>> declaringGoalsFromDescriptor() throws IOException {
        String text = Files.readString(descriptor(), StandardCharsets.UTF_8);
        Map<String, Set<String>> byParameter = new TreeMap<>();
        Matcher mojoMatcher = MOJO_BLOCK.matcher(text);
        while (mojoMatcher.find()) {
            String mojoBlock = mojoMatcher.group(1);
            Matcher goalMatcher = MOJO_GOAL.matcher(mojoBlock);
            if (!goalMatcher.find() || !VERIFIED_GOALS.contains(goalMatcher.group(1))) {
                continue;
            }
            String goal = goalMatcher.group(1);
            Matcher paramMatcher = EDITABLE_PARAMETER.matcher(mojoBlock);
            while (paramMatcher.find()) {
                byParameter.computeIfAbsent(paramMatcher.group(1), name -> new TreeSet<>()).add(goal);
            }
        }
        return byParameter;
    }

    /**
     * Each documented parameter against the heading its table sits under. Every parameter table has
     * to be under a heading {@link #SECTION_GOALS} names, so a table added under a new heading fails
     * here instead of going unchecked.
     */
    private static Map<String, String> parametersByDocSection() throws IOException {
        String text = Files.readString(locateDoc(), StandardCharsets.UTF_8);
        Map<String, String> bySection = new LinkedHashMap<>();
        Matcher headings = SECTION_HEADING.matcher(text);
        int bodyStart = -1;
        String title = null;
        while (headings.find()) {
            if (title != null) {
                collectRows(text.substring(bodyStart, headings.start()), title, bySection);
            }
            title = headings.group(1).trim();
            bodyStart = headings.end();
        }
        if (title != null) {
            collectRows(text.substring(bodyStart), title, bySection);
        }
        return bySection;
    }

    /** The parameter rows of every parameter table inside one section's body. */
    private static void collectRows(String body, String section, Map<String, String> into) {
        for (String block : TABLE_DELIM.split(body)) {
            if (!PARAMETER_TABLE_HEADER.matcher(block).find()) continue;
            assertThat(SECTION_GOALS)
                .as("parameter table under a heading this test does not know the goal scope of: '"
                    + section + "'; add it to SECTION_GOALS or the rows below it go unchecked")
                .containsKey(section);
            for (String row : block.split("\\R\\s*\\R+")) {
                if (PARAMETER_TABLE_HEADER.matcher(row).find()) continue;
                String firstLine = row.lines().filter(s -> !s.isBlank()).findFirst().orElse("");
                Matcher m = FIRST_COL_BACKTICKED.matcher(firstLine);
                if (m.find()) into.put(m.group(1), section);
            }
        }
    }

    /** Every goal the generated descriptor declares. */
    private static Set<String> goalsFromDescriptor() throws IOException {
        Set<String> goals = new TreeSet<>();
        Matcher mojoMatcher = MOJO_BLOCK.matcher(Files.readString(descriptor(), StandardCharsets.UTF_8));
        while (mojoMatcher.find()) {
            Matcher goalMatcher = MOJO_GOAL.matcher(mojoMatcher.group(1));
            if (goalMatcher.find()) {
                goals.add(goalMatcher.group(1));
            }
        }
        return goals;
    }

    private static Set<String> parametersFromDescriptor() throws IOException {
        String text = Files.readString(descriptor(), StandardCharsets.UTF_8);
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

    /** The generated plugin descriptor, which both readers above parse. */
    private static Path descriptor() {
        Path descriptor = Path.of(PLUGIN_XML_PATH).toAbsolutePath();
        if (!Files.isRegularFile(descriptor)) {
            throw new IllegalStateException(
                "Plugin descriptor not found at " + descriptor + ". The "
                    + "maven-plugin-plugin descriptor goal runs at process-classes "
                    + "phase; ensure the module has been compiled before running tests.");
        }
        return descriptor;
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
