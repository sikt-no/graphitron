package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 4 — unit coverage of the warm incremental compile engine over a synthetic source set:
 * a clean compile lands {@code .class} in the exclusive dir; a failing unit is collected as a
 * diagnostic, keeps its last-good {@code .class}, and makes the round report failure; the orphan
 * sweep drops class files no longer backing a live unit; and, the named risk, a stale symbol never
 * survives a round across the reused file manager.
 */
@UnitTier
class IncrementalCompileEngineTest {

    /** An in-memory Java source compilation unit. */
    private static JavaFileObject source(String fqcn, String code) {
        return new SimpleJavaFileObject(
            URI.create("string:///" + fqcn.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private static Path classFile(Path outputDir, String fqcn) {
        return outputDir.resolve(fqcn.replace('.', '/') + ".class");
    }

    @Test
    void compilesCleanSourceIntoTheExclusiveDir(@TempDir Path out) {
        try (var engine = new IncrementalCompileEngine(out, List.of())) {
            var round = engine.compile(List.of(
                source("gen.Widget", "package gen; public class Widget { public int v() { return 1; } }")));

            assertThat(round.success()).isTrue();
            assertThat(round.diagnostics()).isEmpty();
            assertThat(classFile(out, "gen.Widget")).exists();
        }
    }

    @Test
    void failingUnitIsCollectedKeepsLastGoodClassAndFailsTheRound(@TempDir Path out) {
        try (var engine = new IncrementalCompileEngine(out, List.of())) {
            // A good sibling compiles; the bad unit fails, so the round fails and the bad unit gets no .class.
            var round = engine.compile(List.of(
                source("gen.Good", "package gen; public class Good { public int v() { return 1; } }"),
                source("gen.Bad", "package gen; public class Bad { public int v() { return notAThing; } }")));

            assertThat(round.success()).isFalse();
            assertThat(round.errors()).isNotEmpty();
            assertThat(round.diagnostics())
                .anySatisfy(d -> assertThat(d.file()).contains("Bad"));
            assertThat(classFile(out, "gen.Good")).exists();
            assertThat(classFile(out, "gen.Bad")).doesNotExist(); // no fresh bytecode for the failing unit
        }
    }

    @Test
    void sweepDropsOrphanClassesButKeepsLiveOnes(@TempDir Path out) {
        try (var engine = new IncrementalCompileEngine(out, List.of())) {
            engine.compile(List.of(
                source("gen.A", "package gen; public class A {}"),
                source("gen.B", "package gen; public class B {}")));
            assertThat(classFile(out, "gen.A")).exists();
            assertThat(classFile(out, "gen.B")).exists();

            // B is no longer a live unit (its coordinate was removed): its .class must be swept.
            engine.sweepOrphans(Set.of("gen.A"));

            assertThat(classFile(out, "gen.A")).exists();
            assertThat(classFile(out, "gen.B")).doesNotExist();
        }
    }

    @Test
    void sweepKeepsNestedClassesOfLiveUnits(@TempDir Path out) throws Exception {
        try (var engine = new IncrementalCompileEngine(out, List.of())) {
            engine.compile(List.of(
                source("gen.Outer",
                    "package gen; public class Outer { public static class Inner {} }")));
            assertThat(Files.exists(out.resolve("gen/Outer$Inner.class"))).isTrue();

            engine.sweepOrphans(Set.of("gen.Outer")); // Outer is live -> its nested class stays

            assertThat(classFile(out, "gen.Outer")).exists();
            assertThat(Files.exists(out.resolve("gen/Outer$Inner.class"))).isTrue();
        }
    }

    @Test
    void staleSymbolNeverSurvivesARound(@TempDir Path out) {
        try (var engine = new IncrementalCompileEngine(out, List.of())) {
            // Round 1: A.kind() returns String.
            assertThat(engine.compile(List.of(
                source("gen.A", "package gen; public class A { public static String kind() { return \"s\"; } }")))
                .success()).isTrue();

            // Round 2: A recompiled, kind() now returns int (an ABI move).
            assertThat(engine.compile(List.of(
                source("gen.A", "package gen; public class A { public static int kind() { return 1; } }")))
                .success()).isTrue();

            // Round 3: a dependent that expects int resolves A from the freshly written .class -> compiles.
            assertThat(engine.compile(List.of(
                source("gen.C", "package gen; public class C { public static int k() { return A.kind(); } }")))
                .success())
                .as("dependent recompiled in a later round must see A's round-2 ABI (int)")
                .isTrue();

            // Round 4: a dependent that expects String must FAIL; a stale cached String-returning A would
            // let it pass, so its failure proves the reused manager serves the fresh symbol, not the stale one.
            assertThat(engine.compile(List.of(
                source("gen.C", "package gen; public class C { public static String k() { return A.kind(); } }")))
                .success())
                .as("a stale String-returning A symbol must not survive the round-2 recompile")
                .isFalse();
        }
    }
}
