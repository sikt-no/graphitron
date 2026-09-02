package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.capture.java.JavaSourceFacts;
import no.sikt.graphitron.model.sources.SourceWalker;

/**
 * Failure-mode and contract coverage for {@link SourceWalker}: the declarations it reads off a
 * source file, the doc-comment-retention hazard, and tolerance of unparseable / missing files.
 * The end-to-end goto-definition behaviour is covered at the LSP tier; this class pins only what
 * pipeline coverage would make repetitive.
 *
 * <p>The assertions are over {@link SourceWalker#walkFiles}, the walk's product, which is also
 * what {@link JavaSourceFacts} writes into the store. Nothing here asks the walk to pick one
 * declaration per name: that question belongs to whoever queries the rows.
 */
@UnitTier
class SourceWalkerTest {

    @Test
    void recordsClassMethodAndFieldPositions(@TempDir Path root) throws IOException {
        write(root, "com/example/Widgets.java", """
            package com.example;
            /** A widget service. */
            public class Widgets {
                /** The widget id column. */
                public final Object WIDGET_ID = null;
                /** Builds a widget. */
                public Object build(Object table) { return null; }
            }
            """);

        var walk = new SourceWalker().walkFiles(List.of(root));

        assertThat(walk).singleElement().satisfies(file -> {
            assertThat(file.file().toString()).endsWith("Widgets.java");
            assertThat(file.sourceRoot()).isEqualTo(root.toAbsolutePath().normalize());
        });
        assertThat(classes(walk)).singleElement().satisfies(c -> {
            assertThat(c.className()).isEqualTo("com.example.Widgets");
            assertThat(c.line()).isGreaterThan(0);
        });
        assertThat(fields(walk)).singleElement().satisfies(f -> {
            assertThat(f.fieldName()).isEqualTo("WIDGET_ID");
            assertThat(f.line()).isGreaterThan(0);
        });
        assertThat(methods(walk)).singleElement().satisfies(m -> {
            assertThat(m.methodName()).isEqualTo("build");
            assertThat(m.parameterCount()).isEqualTo(1);
            assertThat(m.line()).isGreaterThan(0);
        });
    }

    /**
     * The doc-comment-retention hazard pinned in the spec: if the parse does
     * not keep doc comments, {@code getDocComment} returns null and every
     * Javadoc slot comes back empty. This asserts the {@link SourceWalker}'s
     * task keeps them.
     */
    @Test
    void retainsJavadocOnClassMethodAndField(@TempDir Path root) throws IOException {
        write(root, "com/example/Documented.java", """
            package com.example;
            /** Class doc. */
            public class Documented {
                /** Field doc. */
                public final Object COL = null;
                /** Method doc. */
                public Object run() { return null; }
            }
            """);

        var walk = new SourceWalker().walkFiles(List.of(root));

        assertThat(classes(walk)).extracting(SourceWalker.Declaration::javadoc)
            .containsExactly("Class doc.");
        assertThat(fields(walk)).extracting(SourceWalker.Declaration::javadoc)
            .containsExactly("Field doc.");
        assertThat(methods(walk)).extracting(SourceWalker.Declaration::javadoc)
            .containsExactly("Method doc.");
    }

    @Test
    void sameArityOverloadsAreTwoDeclarations(@TempDir Path root) throws IOException {
        // Two overloads with the same name and arity differ only by parameter type, which the
        // parse has no descriptor to tell apart. Both are declarations and both are read: the
        // walk records what the file says and leaves "which one did you mean" to a reader that
        // has to answer it.
        write(root, "com/example/Overloaded.java", """
            package com.example;
            public class Overloaded {
                public Object filter(String a) { return null; }
                public Object filter(Integer a) { return null; }
                public Object only(String a) { return null; }
            }
            """);

        var walk = new SourceWalker().walkFiles(List.of(root));

        assertThat(methods(walk))
            .extracting(SourceWalker.Declaration.MethodDecl::methodName)
            .containsExactly("filter", "filter", "only");
        assertThat(methods(walk))
            .as("declaration order is the file's, so the rows are distinguishable by position")
            .extracting(SourceWalker.Declaration.MethodDecl::line)
            .doesNotHaveDuplicates();
    }

    @Test
    void walksEverySourceRootAndKeepsEachFilesOwnRoot(@TempDir Path root) throws IOException {
        // Both generated-source roots are walked in one pass, and every file keeps the root it
        // was reached under, which is the scope a consumer prunes by. A method name shared across
        // the two roots is no obstacle: each declaration carries its own declaring class.
        Path jooq = root.resolve("generated-sources/jooq");
        Path graphitron = root.resolve("generated-sources/graphitron");
        write(jooq, "com/example/jooq/tables/Actor.java", """
            package com.example.jooq.tables;
            /** The ACTOR table. */
            public class Actor {
                public Object as(String alias) { return null; }
            }
            """);
        write(graphitron, "com/example/generated/ActorResolver.java", """
            package com.example.generated;
            public class ActorResolver {
                public Object as(String alias) { return null; }
            }
            """);

        var walk = new SourceWalker().walkFiles(List.of(jooq, graphitron));

        assertThat(walk).extracting(SourceWalker.ParsedFile::sourceRoot)
            .containsExactly(jooq.toAbsolutePath().normalize(), graphitron.toAbsolutePath().normalize());
        assertThat(classes(walk)).extracting(SourceWalker.Declaration.ClassDecl::className)
            .containsExactly("com.example.jooq.tables.Actor", "com.example.generated.ActorResolver");
        assertThat(methods(walk))
            .as("the shared simple name is two declarations under two declaring classes")
            .extracting(SourceWalker.Declaration.MethodDecl::className)
            .containsExactly("com.example.jooq.tables.Actor", "com.example.generated.ActorResolver");
    }

    @Test
    void doesNotRecordParametersOrLocalsAsFields(@TempDir Path root) throws IOException {
        write(root, "com/example/Scopes.java", """
            package com.example;
            public class Scopes {
                public final Object FIELD = null;
                public Object run(Object param) {
                    Object local = null;
                    return local;
                }
            }
            """);

        var walk = new SourceWalker().walkFiles(List.of(root));

        assertThat(fields(walk)).extracting(SourceWalker.Declaration.FieldDecl::fieldName)
            .containsExactly("FIELD");
    }

    @Test
    void toleratesUnparseableFileAndStillReadsGoodOnes(@TempDir Path root) throws IOException {
        // A syntax error in one file must not cost the walk the other file's declarations. The
        // offender keeps whatever the parse recovered before the malformed body, which is a class
        // header here: partial is the honest answer, and every consumer reads absence as absence.
        write(root, "com/example/Broken.java", """
            package com.example;
            public class Broken {
                this is not valid java @@@ ###
            """);
        write(root, "com/example/Good.java", """
            package com.example;
            public class Good {
                public Object ok() { return null; }
            }
            """);

        var walk = new SourceWalker().walkFiles(List.of(root));

        assertThat(walk).extracting(f -> f.file().getFileName().toString())
            .as("both files are walked; the offender does not remove itself from the walk")
            .containsExactlyInAnyOrder("Broken.java", "Good.java");
        assertThat(classes(walk)).extracting(SourceWalker.Declaration.ClassDecl::className)
            .contains("com.example.Good");
        assertThat(methods(walk)).extracting(SourceWalker.Declaration.MethodDecl::methodName)
            .as("the good file is read whole, and the offender contributes only what the parse's"
                + " own error recovery reached before the malformed body")
            .containsExactly("ok");
    }

    @Test
    void emptyRootsYieldNoParsedFiles(@TempDir Path empty) {
        assertThat(new SourceWalker().walkFiles(List.of())).isEmpty();
        assertThat(new SourceWalker().walkFiles(List.of(empty))).isEmpty();
    }

    @Test
    void reparsesAfterContentChange(@TempDir Path root) throws IOException {
        // One walker across both walks so the per-file mtime cache is exercised:
        // the second walk must re-parse because the file's modification time moved.
        var walker = new SourceWalker();
        Path file = write(root, "com/example/Mutable.java", """
            package com.example;
            public class Mutable {
                public Object before() { return null; }
            }
            """);

        assertThat(methods(walker.walkFiles(List.of(root))))
            .extracting(SourceWalker.Declaration.MethodDecl::methodName)
            .containsExactly("before");

        // Rewrite with a bumped modification time so the per-file cache invalidates.
        Files.writeString(file, """
            package com.example;
            public class Mutable {
                public Object after() { return null; }
            }
            """);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(file).toMillis() + 5000));

        assertThat(methods(walker.walkFiles(List.of(root))))
            .extracting(SourceWalker.Declaration.MethodDecl::methodName)
            .containsExactly("after");
    }

    @Test
    void instancesDoNotShareCache(@TempDir Path root) throws IOException {
        // The cache is per-instance, not process-wide static. A stale entry
        // from one walker must never leak into another. Walker A warms its cache,
        // the file content changes while keeping the same mtime, then a fresh
        // walker B parses the file anew and sees the new content, proving B does
        // not read A's cache (a static cache keyed by path+mtime would wrongly
        // serve A's stale entry here).
        Path file = write(root, "com/example/Isolated.java", """
            package com.example;
            public class Isolated {
                public Object alpha() { return null; }
            }
            """);
        long mtime = Files.getLastModifiedTime(file).toMillis();

        assertThat(methods(new SourceWalker().walkFiles(List.of(root))))
            .extracting(SourceWalker.Declaration.MethodDecl::methodName)
            .containsExactly("alpha");

        Files.writeString(file, """
            package com.example;
            public class Isolated {
                public Object beta() { return null; }
            }
            """);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtime));

        assertThat(methods(new SourceWalker().walkFiles(List.of(root))))
            .as("a fresh walker must parse the file itself, not read another instance's cache")
            .extracting(SourceWalker.Declaration.MethodDecl::methodName)
            .containsExactly("beta");
    }

    private static List<SourceWalker.Declaration.ClassDecl> classes(List<SourceWalker.ParsedFile> walk) {
        return declarations(walk, SourceWalker.Declaration.ClassDecl.class);
    }

    private static List<SourceWalker.Declaration.MethodDecl> methods(List<SourceWalker.ParsedFile> walk) {
        return declarations(walk, SourceWalker.Declaration.MethodDecl.class);
    }

    private static List<SourceWalker.Declaration.FieldDecl> fields(List<SourceWalker.ParsedFile> walk) {
        return declarations(walk, SourceWalker.Declaration.FieldDecl.class);
    }

    /** Every declaration of one arm across the walk, in walk order then source order. */
    private static <D extends SourceWalker.Declaration> List<D> declarations(
        List<SourceWalker.ParsedFile> walk, Class<D> arm
    ) {
        return walk.stream()
            .flatMap(file -> file.declarations().stream())
            .filter(arm::isInstance)
            .map(arm::cast)
            .toList();
    }

    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
