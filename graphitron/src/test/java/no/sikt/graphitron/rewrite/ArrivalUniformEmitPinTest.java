package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Census pin for arrival-uniform emit: populating {@link no.sikt.graphitron.rewrite.model.Source.OnlyChild}
 * changes no generated code. The arm is a classification fact (arrival {@code One}, direct SQL
 * licensed), not an emit-strategy dispatch: no generator main-source site reads it distinctly
 * from {@link no.sikt.graphitron.rewrite.model.Source.Child}, which this test pins at zero
 * {@code OnlyChild} tokens across the generators package's code regions.
 *
 * <p>The constraint any future strategy must discharge before consuming the arm lives on the
 * arm's own javadoc: {@code One} is a static per-dispatch guarantee about unaliased projections,
 * and query aliases can materialize {@code k} parents even on a {@code One} chain, so an emit
 * strategy the arm licenses must stay row-correct at every arrival count. A direct-SQL
 * {@code OnlyChild} strategy knowingly inverts or retires this pin when it lands, together with
 * the aliasing row-correctness enforcer it owes.
 */
@UnitTier
class ArrivalUniformEmitPinTest {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+");
    private static final int MIN_SCANNED_GENERATOR_FILES = 40;

    @Test
    void populatingOnlyChildChangesNoGeneratedCode() throws IOException {
        Path generators = GuardScope.locateRepoRoot()
            .resolve("graphitron/src/main/java/no/sikt/graphitron/rewrite/generators");
        List<String> findings = new ArrayList<>();
        int scanned = walk(generators, (file, text) -> {
            String[] codeLines = JavaSourceRegions.code(text);
            for (int i = 0; i < codeLines.length; i++) {
                Matcher m = IDENTIFIER.matcher(codeLines[i]);
                while (m.find()) {
                    if (m.group().equals("OnlyChild")) {
                        findings.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        });

        assertThat(scanned)
            .as("the generators walk found suspiciously few files; a vacuous walk pins nothing")
            .isGreaterThan(MIN_SCANNED_GENERATOR_FILES);
        assertThat(findings)
            .as("a generator main-source code region references Source.OnlyChild, so emit is no "
                + "longer arrival-uniform. A strategy that deliberately consumes the arm must land "
                + "its aliasing row-correctness enforcer and retire or invert this pin in the same "
                + "change. Sites:\n" + String.join("\n", findings))
            .isEmpty();
    }

    @Test
    void classifierStillMintsBothArrivalArms() throws IOException {
        // Anti-over-deletion carve-out: the zero-tolerance census above must not be satisfiable
        // by deleting the arm or its population. ChildField#source is the single construction
        // site of both wrappers.
        Path childField = GuardScope.locateRepoRoot()
            .resolve("graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java");
        String code = String.join("\n", JavaSourceRegions.code(Files.readString(childField)));
        assertThat(code).contains("new Source.OnlyChild(", "new Source.Child(");
    }

    private interface FileSink { void accept(Path file, String text); }

    private static int walk(Path root, FileSink perFile) throws IOException {
        int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (file.getFileName().toString().endsWith(".java")) {
                    count[0]++;
                    try {
                        perFile.accept(file, Files.readString(file));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }
}
