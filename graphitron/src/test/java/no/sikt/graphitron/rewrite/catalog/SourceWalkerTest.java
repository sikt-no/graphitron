package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure-mode and contract coverage for {@link SourceWalker}: the
 * positional + Javadoc index, the doc-comment-retention hazard, the
 * overload-ambiguity rule, and tolerance of unparseable / missing files.
 * The end-to-end goto-definition behaviour is covered at the LSP tier; this
 * class pins only what pipeline coverage would make repetitive.
 */
@UnitTier
class SourceWalkerTest {

    @Test
    void indexesClassMethodAndFieldPositions(@TempDir Path root) throws IOException {
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

        var index = new SourceWalker().walk(List.of(root));

        var clazz = index.classes().get("com.example.Widgets");
        assertThat(clazz).isNotNull();
        assertThat(clazz.location().line()).isGreaterThan(0);
        assertThat(clazz.location().uri()).endsWith("Widgets.java");

        var field = index.fields().get(new SourceWalker.FieldKey("com.example.Widgets", "WIDGET_ID"));
        assertThat(field).isNotNull();
        assertThat(field.location().line()).isGreaterThan(0);

        var method = index.methods().get(new SourceWalker.MethodKey("com.example.Widgets", "build", 1));
        assertThat(method).isNotNull();
        assertThat(method.location().line()).isGreaterThan(0);
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

        var index = new SourceWalker().walk(List.of(root));

        assertThat(index.classes().get("com.example.Documented").javadoc()).isEqualTo("Class doc.");
        assertThat(index.fields().get(new SourceWalker.FieldKey("com.example.Documented", "COL")).javadoc())
            .isEqualTo("Field doc.");
        assertThat(index.methods().get(new SourceWalker.MethodKey("com.example.Documented", "run", 0)).javadoc())
            .isEqualTo("Method doc.");
    }

    @Test
    void overloadCollisionDropsMethodKeyToForceUnknown(@TempDir Path root) throws IOException {
        // Two overloads with the same name and arity differ only by parameter
        // type; the join key (className, methodName, paramCount) cannot tell
        // them apart, so the walker drops the key and the caller keeps UNKNOWN.
        write(root, "com/example/Overloaded.java", """
            package com.example;
            public class Overloaded {
                public Object filter(String a) { return null; }
                public Object filter(Integer a) { return null; }
                public Object only(String a) { return null; }
            }
            """);

        var index = new SourceWalker().walk(List.of(root));

        var filterKey = new SourceWalker.MethodKey("com.example.Overloaded", "filter", 1);
        assertThat(index.methods()).doesNotContainKey(filterKey);
        // The certainty the merge used to discard is now exposed: the dropped
        // key is reported as ambiguous, so the LSP can distinguish it from a
        // never-seen method (Ambiguous vs SourceAbsent) rather than collapsing
        // both to a silent no-jump.
        assertThat(index.ambiguousMethods()).contains(filterKey);
        // A distinct-arity method is unaffected and is not ambiguous.
        var onlyKey = new SourceWalker.MethodKey("com.example.Overloaded", "only", 1);
        assertThat(index.methods()).containsKey(onlyKey);
        assertThat(index.ambiguousMethods()).doesNotContain(onlyKey);
    }

    @Test
    void disjointGeneratorPackagesKeepTableJumpLocatedNotAmbiguous(@TempDir Path root) throws IOException {
        // R369 / D1: once graphitron's own output root (target/generated-sources/graphitron)
        // is walked alongside the jOOQ root, a method that shares a simple name + arity with a
        // jOOQ table declaration must NOT route the table jump to Ambiguous. It cannot, because
        // graphitron emits into the consumer's outputPackage, disjoint by construction from the
        // jOOQ table package, so the (FQN, name, arity) keys never collide. This pins that
        // disjointness rather than leaving it as an unpinned prose claim.
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

        var index = new SourceWalker().walk(List.of(jooq, graphitron));

        // The table class resolves to a single position: a Located jump.
        var table = index.classes().get("com.example.jooq.tables.Actor");
        assertThat(table).isNotNull();
        assertThat(table.location().uri()).endsWith("Actor.java");
        // The shared simple name `as(String)` exists under both FQNs, neither dropped to
        // ambiguous, because the package prefixes differ.
        var tableMethod = new SourceWalker.MethodKey("com.example.jooq.tables.Actor", "as", 1);
        var resolverMethod = new SourceWalker.MethodKey("com.example.generated.ActorResolver", "as", 1);
        assertThat(index.methods()).containsKey(tableMethod).containsKey(resolverMethod);
        assertThat(index.ambiguousMethods())
            .as("disjoint output / jOOQ packages keep method keys distinct")
            .doesNotContain(tableMethod, resolverMethod);
    }

    @Test
    void doesNotIndexParametersOrLocalsAsFields(@TempDir Path root) throws IOException {
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

        var index = new SourceWalker().walk(List.of(root));

        assertThat(index.fields()).containsKey(new SourceWalker.FieldKey("com.example.Scopes", "FIELD"));
        assertThat(index.fields()).doesNotContainKey(new SourceWalker.FieldKey("com.example.Scopes", "param"));
        assertThat(index.fields()).doesNotContainKey(new SourceWalker.FieldKey("com.example.Scopes", "local"));
    }

    @Test
    void toleratesUnparseableFileAndStillIndexesGoodOnes(@TempDir Path root) throws IOException {
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

        var index = new SourceWalker().walk(List.of(root));

        assertThat(index.classes()).containsKey("com.example.Good");
        assertThat(index.methods())
            .containsKey(new SourceWalker.MethodKey("com.example.Good", "ok", 0));
    }

    @Test
    void emptyRootsYieldEmptyIndex(@TempDir Path empty) {
        assertThat(new SourceWalker().walk(List.of()).isEmpty()).isTrue();
        assertThat(new SourceWalker().walk(List.of(empty)).isEmpty()).isTrue();
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

        var first = walker.walk(List.of(root));
        assertThat(first.methods())
            .containsKey(new SourceWalker.MethodKey("com.example.Mutable", "before", 0));

        // Rewrite with a bumped modification time so the per-file cache invalidates.
        Files.writeString(file, """
            package com.example;
            public class Mutable {
                public Object after() { return null; }
            }
            """);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(file).toMillis() + 5000));

        var second = walker.walk(List.of(root));
        assertThat(second.methods())
            .containsKey(new SourceWalker.MethodKey("com.example.Mutable", "after", 0));
        assertThat(second.methods())
            .doesNotContainKey(new SourceWalker.MethodKey("com.example.Mutable", "before", 0));
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

        var a = new SourceWalker().walk(List.of(root));
        assertThat(a.methods())
            .containsKey(new SourceWalker.MethodKey("com.example.Isolated", "alpha", 0));

        Files.writeString(file, """
            package com.example;
            public class Isolated {
                public Object beta() { return null; }
            }
            """);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtime));

        var b = new SourceWalker().walk(List.of(root));
        assertThat(b.methods())
            .as("a fresh walker must parse the file itself, not read another instance's cache")
            .containsKey(new SourceWalker.MethodKey("com.example.Isolated", "beta", 0));
    }

    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
