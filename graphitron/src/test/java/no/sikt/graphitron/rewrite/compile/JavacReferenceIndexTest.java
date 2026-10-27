package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins what {@link JavacReferenceIndex} counts as a reference, over small hand-written sources in
 * two "generated" packages ({@code gen.a}, {@code gen.b}) beside a non-generated one ({@code lib}).
 */
@UnitTier
class JavacReferenceIndexTest {

    @TempDir
    Path dir;

    @Test
    void sameAndCrossPackageNamesAreFound() throws IOException {
        var index = index(Map.of(
            "gen.a.A", "package gen.a; class A { B b; gen.b.C c; }",
            "gen.a.B", "package gen.a; public class B {}",
            "gen.b.C", "package gen.b; public class C {}"));
        assertThat(index.get("gen.a.A")).containsExactlyInAnyOrder("gen.a.B", "gen.b.C");
    }

    @Test
    void referencesInBodiesLambdasAnonymousClassesAndAnnotationValuesAreFound() throws IOException {
        var index = index(Map.of(
            "gen.a.A", """
                package gen.a;
                class A {
                    void body() { Object o = new B(); }
                    void lambda() { Runnable r = () -> C.run(); }
                    Object anonymous = new Object() { D d; };
                    @lib.Ann(E.class) void annotated() {}
                }
                """,
            "gen.a.B", "package gen.a; public class B {}",
            "gen.a.C", "package gen.a; public class C { public static void run() {} }",
            "gen.a.D", "package gen.a; public class D {}",
            "gen.a.E", "package gen.a; public class E {}",
            "lib.Ann", "package lib; public @interface Ann { Class<?> value(); }"));
        assertThat(index.get("gen.a.A")).containsExactlyInAnyOrder("gen.a.B", "gen.a.C", "gen.a.D", "gen.a.E");
    }

    @Test
    void aNestedTypeCountsAsItsTopLevelUnit() throws IOException {
        var index = index(Map.of(
            "gen.a.A", "package gen.a; class A { B.Inner inner; }",
            "gen.a.B", "package gen.a; public class B { public static class Inner {} }"));
        assertThat(index.get("gen.a.A")).containsExactly("gen.a.B");
    }

    @Test
    void anInlinedConstantIsFound() throws IOException {
        // javac copies the constant's value into A's class file, so no class-file scan sees B.
        var index = index(Map.of(
            "gen.a.A", "package gen.a; class A { String s = B.NAME; }",
            "gen.a.B", "package gen.a; public class B { public static final String NAME = \"b\"; }"));
        assertThat(index.get("gen.a.A")).containsExactly("gen.a.B");
    }

    @Test
    void aTypeReachedOnlyThroughInferenceIsFound() throws IOException {
        // A never names Made, but which take overload applies depends on what make() returns.
        var index = index(Map.of(
            "gen.a.A", """
                package gen.a;
                class A {
                    void call() { take(Gen.make()); }
                    void take(Object o) {}
                    void take(CharSequence c) {}
                }
                """,
            "gen.a.Gen", "package gen.a; public class Gen { public static Made make() { return null; } }",
            "gen.a.Made", "package gen.a; public class Made {}"));
        assertThat(index.get("gen.a.A")).containsExactlyInAnyOrder("gen.a.Gen", "gen.a.Made");
    }

    @Test
    void selfReferencesAndNonGeneratedClassesAreExcluded() throws IOException {
        var index = index(Map.of(
            "gen.a.A", "package gen.a; class A { A self; lib.Lib lib; String text; }",
            "lib.Lib", "package lib; public class Lib {}"));
        assertThat(index.get("gen.a.A")).isEmpty();
    }

    @Test
    void aSourceThatDoesNotCompileFailsWithItsDiagnostics() {
        assertThatThrownBy(() -> index(Map.of("gen.a.A", "package gen.a; class A { Missing m; }")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing");
    }

    /** Writes the sources and indexes them; units under {@code gen.} are the generated ones. */
    private Map<String, Set<String>> index(Map<String, String> sourcesByFqcn) throws IOException {
        List<Path> files = new ArrayList<>();
        var generated = new LinkedHashSet<String>();
        for (var entry : sourcesByFqcn.entrySet()) {
            Path file = dir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            files.add(file);
            if (entry.getKey().startsWith("gen.")) {
                generated.add(entry.getKey());
            }
        }
        return JavacReferenceIndex.of(files, generated, List.of());
    }
}
