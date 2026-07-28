package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard over the command/plan/render package triangle: the import-direction rules
 * that make "no emit vocabulary below the renderers" and "the shell decides nothing" checkable
 * from the first file instead of ratcheted.
 *
 * <ul>
 *   <li>{@code no.sikt.graphitron.command} holds pure data. It may import nothing of the emit
 *       library ({@code no.sikt.graphitron.javapoet}), nothing of {@code plan} or {@code render},
 *       and from the legacy tree only the named model-ref allowlist below. The allowlist is the
 *       migration dial: entries leave it as the refs move to a shared pure-data floor, and the
 *       list is enforced instead of a blanket ban so the model's ref vocabulary is borrowed,
 *       never copied. From graphql-java, only {@code FieldCoordinates} (the coordinate key).</li>
 *   <li>{@code no.sikt.graphitron.plan} produces commands from the model: it may not import the
 *       emit library or {@code render}.</li>
 *   <li>{@code no.sikt.graphitron.render} interprets commands into emitted output: it may not
 *       import the model or legacy core ({@code no.sikt.graphitron.rewrite}) or {@code plan}.</li>
 * </ul>
 *
 * <p>The guard also pins {@code UnitRef}'s minting site: a unit name enters the world through the
 * plan's naming vocabulary ({@code GeneratedUnits}) and nowhere else, so a command naming a unit
 * no scheme produces is unrepresentable in practice.
 */
@UnitTier
class PackageImportDirectionTest {

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");

    /**
     * The model refs {@code command} may borrow, exactly as the roadmap's shared-vocabulary
     * decision enumerates them. An import of a nested member (e.g. {@code JoinStep.Hop}) or a
     * static member counts as its enclosing entry.
     */
    private static final Set<String> COMMAND_MODEL_ALLOWLIST = Set.of(
        "no.sikt.graphitron.rewrite.model.TableRef",
        "no.sikt.graphitron.rewrite.model.ColumnRef",
        "no.sikt.graphitron.rewrite.model.MethodRef",
        "no.sikt.graphitron.rewrite.model.JoinStep",
        "no.sikt.graphitron.rewrite.model.On",
        "no.sikt.graphitron.rewrite.model.CallParam",
        "no.sikt.graphitron.rewrite.model.CallSiteExtraction"
    );

    private static final String COMMAND_GRAPHQL_ALLOWED = "graphql.schema.FieldCoordinates";

    private record Finding(Path file, String importName, String rule) {
        @Override public String toString() {
            return file + "  imports " + importName + "  (" + rule + ")";
        }
    }

    @Test
    void commandPlanRenderImportDirections() throws IOException {
        Path sourceRoot = GuardScope.locateRepoRoot().resolve("graphitron/src/main/java/no/sikt/graphitron");
        var findings = new ArrayList<Finding>();

        int commandFiles = scan(sourceRoot.resolve("command"), findings, (file, imp) -> {
            if (imp.startsWith("no.sikt.graphitron.javapoet")) {
                return "command never imports the emit library";
            }
            if (imp.startsWith("no.sikt.graphitron.plan") || imp.startsWith("no.sikt.graphitron.render")) {
                return "command sits below plan and render";
            }
            if (imp.startsWith("no.sikt.graphitron.") && !imp.startsWith("no.sikt.graphitron.command.")) {
                boolean allowlisted = COMMAND_MODEL_ALLOWLIST.stream()
                    .anyMatch(entry -> imp.equals(entry) || imp.startsWith(entry + "."));
                if (!allowlisted) {
                    return "command may borrow only the enumerated model-ref allowlist";
                }
            }
            if (imp.startsWith("graphql.") && !imp.equals(COMMAND_GRAPHQL_ALLOWED)) {
                return "command's only graphql-java borrow is FieldCoordinates";
            }
            return null;
        });

        int planFiles = scan(sourceRoot.resolve("plan"), findings, (file, imp) -> {
            if (imp.startsWith("no.sikt.graphitron.javapoet")) {
                return "plan never imports the emit library";
            }
            if (imp.startsWith("no.sikt.graphitron.render")) {
                return "plan produces commands; it never sees renderers";
            }
            return null;
        });

        int renderFiles = scan(sourceRoot.resolve("render"), findings, (file, imp) -> {
            if (imp.startsWith("no.sikt.graphitron.rewrite")) {
                return "render never imports the model or legacy core";
            }
            if (imp.startsWith("no.sikt.graphitron.plan")) {
                return "render interprets commands; it never sees producers";
            }
            return null;
        });

        assertThat(findings).as("package-triangle import-direction violations").isEmpty();
        assertThat(commandFiles).as("command sources scanned (walk must not be vacuous)").isGreaterThanOrEqualTo(4);
        assertThat(planFiles).as("plan sources scanned (walk must not be vacuous)").isGreaterThanOrEqualTo(3);
        assertThat(renderFiles).as("render sources scanned (walk must not be vacuous)").isGreaterThanOrEqualTo(1);
    }

    @Test
    void unitRefsAreMintedOnlyByThePlansNamingVocabulary() throws IOException {
        Path sourceRoot = GuardScope.locateRepoRoot().resolve("graphitron/src/main/java");
        Path mintingSite = sourceRoot.resolve("no/sikt/graphitron/plan/GeneratedUnits.java");
        var offenders = new ArrayList<Path>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (file.equals(mintingSite)) continue;
                if (Files.readString(file).contains("new UnitRef(")) {
                    offenders.add(file);
                }
            }
        }
        assertThat(offenders)
            .as("UnitRef is minted only by GeneratedUnits' naming schemes; mint through a scheme"
                + " (or add one) instead of constructing a ref ad hoc")
            .isEmpty();
        assertThat(scanned).as("main sources scanned (walk must not be vacuous)").isGreaterThan(300);
    }

    private interface ImportRule {
        /** Returns the violated rule's description, or null when the import is fine. */
        String check(Path file, String importName);
    }

    /** Applies the rule to every import in every source under {@code packageRoot}; returns the file count. */
    private static int scan(Path packageRoot, List<Finding> findings, ImportRule rule) throws IOException {
        if (!Files.isDirectory(packageRoot)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> files = Files.walk(packageRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                count++;
                for (String line : Files.readAllLines(file)) {
                    var matcher = IMPORT.matcher(line);
                    if (!matcher.find()) continue;
                    String imported = matcher.group(1);
                    String violated = rule.check(file, imported);
                    if (violated != null) {
                        findings.add(new Finding(file, imported, violated));
                    }
                }
            }
        }
        return count;
    }
}
