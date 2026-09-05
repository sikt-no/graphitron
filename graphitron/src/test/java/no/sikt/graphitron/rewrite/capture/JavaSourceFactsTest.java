package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.sources.Observation;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.FactWriters;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.assertj.core.groups.Tuple;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.test.FactWriters.refreshJavaSources;
import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FIELD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FILE;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import no.sikt.graphitron.model.sources.SourceWalker;

/**
 * The {@code java_} family's anchors: the rows are the parse reduced the same way the walk's own
 * product is, and one file is the unit both halves of the refresh work at.
 *
 * <p>The content anchor is the {@code jvm_} census's shape, one parse reduced two ways, and it is
 * non-vacuous for the same reason: the writer and the assertion reach the declarations by
 * different routes, so a writer that dropped, duplicated or mis-keyed a declaration disagrees.
 * The lifecycle anchor is partitioned by source file where the oracle families' are partitioned by
 * graph, because that is this family's ownership scope: a refresh rewrites the files whose content
 * moved, prunes the files that left the roots it walked, and touches nothing under a root it never
 * walked.
 */
@UnitTier
class JavaSourceFactsTest {

    /** The corpus this family crawls, as {@code meta_gatherer_corpus} names it. */
    private static final String JAVA_SOURCE = "java-source";

    private static final String WIDGETS = """
        package com.example;
        /** A widget service. */
        public class Widgets {
            /** The widget id column. */
            public final Object WIDGET_ID = null;
            /** Builds a widget. */
            public Object build(Object table) { return null; }
            public Object build(Object table, Object extra) { return null; }
            /** By name. */
            public Object of(Object name) { return null; }
            /** By id. */
            public Object of(Integer id) { return null; }
            /** A nested holder. */
            public static class Holder {
                public Object value() { return null; }
            }
        }
        """;

    private static final String GADGETS = """
        package com.example;
        public class Gadgets {
            public Object spin() { return null; }
        }
        """;

    @Test
    @DisplayName("the store's declarations are the walk's, reduced the same way")
    void declarationRowsEqualTheParse(@TempDir Path root) throws IOException {
        write(root, "com/example/Widgets.java", WIDGETS);
        write(root, "com/example/Gadgets.java", GADGETS);

        try (var store = FactStores.inMemory()) {
            var walk = refreshJavaSources(store.dsl(), List.of(root));

            assertThat(files(store.dsl()))
                .as("one java_file row per source the walk read, each under the root it walked")
                .containsExactlyInAnyOrderElementsOf(expectedFiles(walk, root));
            assertThat(methodRows(store.dsl()))
                .as("the fixture's floor: an equality anchor over two empty sides passes on any"
                    + " writer, so the count the fixture declares is stated here")
                .hasSize(6);
            assertThat(classRows(store.dsl()))
                .as("class declarations, position and doc comment as the parse read them")
                .containsExactlyInAnyOrderElementsOf(expected(walk, SourceWalker.Declaration.ClassDecl.class,
                    (file, c) -> tuple(file, c.className(), c.line(), c.column(), javadoc(c.javadoc()))));
            assertThat(methodRows(store.dsl()))
                .as("method declarations, every overload its own row")
                .containsExactlyInAnyOrderElementsOf(expected(walk, SourceWalker.Declaration.MethodDecl.class,
                    (file, m) -> tuple(file, m.className(), m.methodName(), m.parameterCount(),
                        m.line(), m.column(), javadoc(m.javadoc()))));
            assertThat(fieldRows(store.dsl()))
                .as("field declarations, class-level variables only")
                .containsExactlyInAnyOrderElementsOf(expected(walk, SourceWalker.Declaration.FieldDecl.class,
                    (file, f) -> tuple(file, f.className(), f.fieldName(), f.line(), f.column(),
                        javadoc(f.javadoc()))));
        }
    }

    /**
     * The improvement the relation's own key buys: a same-arity overload pair is two rows, where a
     * map keyed by {@code (class, name, arity)} can only hold one and has to invent a policy for
     * the other. The ordinals are dense from zero in declaration order, so a reader asking for a
     * name gets both declarations and the count is the resolution outcome.
     */
    @Test
    @DisplayName("a same-arity overload pair is two rows, ordered by declaration")
    void sameArityOverloadsAreSeparateRows(@TempDir Path root) throws IOException {
        write(root, "com/example/Widgets.java", WIDGETS);

        try (var store = FactStores.inMemory()) {
            refreshJavaSources(store.dsl(), List.of(root));

            assertThat(store.dsl()
                .select(JAVA_METHOD_DECLARATION.ORDINAL, JAVA_METHOD_DECLARATION.JAVADOC)
                .from(JAVA_METHOD_DECLARATION)
                .where(JAVA_METHOD_DECLARATION.METHOD_NAME.eq("of"))
                .orderBy(JAVA_METHOD_DECLARATION.ORDINAL)
                .fetch(row -> tuple(row.value1(), row.value2())))
                .as("both same-arity declarations, in the order the file declares them")
                .containsExactly(tuple(0, "By name."), tuple(1, "By id."));
        }
    }

    /**
     * A file the store already agrees with is not rewritten. The witness has to be something only
     * a rewrite would put back, so the row is marked with a value the parse never produces: it
     * survives a refresh that left the file alone and does not survive one that read it again.
     */
    @Test
    @DisplayName("a file that still hashes to its stamp is left alone")
    void anUnchangedFileIsNotRewritten(@TempDir Path root) throws IOException {
        write(root, "com/example/Gadgets.java", GADGETS);

        try (var store = FactStores.inMemory()) {
            refreshJavaSources(store.dsl(), List.of(root));
            store.dsl().update(JAVA_CLASS_DECLARATION)
                .set(JAVA_CLASS_DECLARATION.JAVADOC, "tampered")
                .execute();

            refreshJavaSources(store.dsl(), List.of(root));

            assertThat(store.dsl().select(JAVA_CLASS_DECLARATION.JAVADOC)
                .from(JAVA_CLASS_DECLARATION).fetchOne(0, String.class))
                .as("the retained row is the one the first refresh wrote, untouched")
                .isEqualTo("tampered");
        }
    }

    @Test
    @DisplayName("an edited file is rewritten whole, and what it stopped declaring is gone")
    void aChangedFileIsRewrittenWhole(@TempDir Path root) throws IOException {
        Path file = write(root, "com/example/Gadgets.java", GADGETS);

        try (var store = FactStores.inMemory()) {
            refreshJavaSources(store.dsl(), List.of(root));
            Files.writeString(file, """
                package com.example;
                public class Gadgets {
                    /** Now with a comment. */
                    public Object twirl() { return null; }
                }
                """);

            refreshJavaSources(store.dsl(), List.of(root));

            assertThat(store.dsl()
                .select(JAVA_METHOD_DECLARATION.METHOD_NAME, JAVA_METHOD_DECLARATION.JAVADOC)
                .from(JAVA_METHOD_DECLARATION)
                .fetch(row -> tuple(row.value1(), row.value2())))
                .as("the file's declarations after the edit, the ones it dropped included")
                .containsExactly(tuple("twirl", "Now with a comment."));
        }
    }

    /**
     * The ownership scope: a walk prunes the files that left its own roots and never reaches a root
     * it did not walk. In a store shared by a workspace's modules the second half is what keeps one
     * module's refresh out of another's rows.
     */
    @Test
    @DisplayName("a deleted file loses its rows, and an unwalked root keeps its own")
    void pruningIsScopedToTheWalkedRoots(@TempDir Path tmp) throws IOException {
        Path own = Files.createDirectories(tmp.resolve("own"));
        Path sibling = Files.createDirectories(tmp.resolve("sibling"));
        Path doomed = write(own, "com/example/Widgets.java", WIDGETS);
        write(sibling, "com/example/Gadgets.java", GADGETS);

        try (var store = FactStores.inMemory()) {
            refreshJavaSources(store.dsl(), List.of(own, sibling));
            assertThat(files(store.dsl())).as("both roots' files, after a walk over both").hasSize(2);

            Files.delete(doomed);
            // Only the own root is walked this time, so the sibling's file is not in the walk's
            // set; it must survive on the strength of its root, not of having been seen.
            refreshJavaSources(store.dsl(), List.of(own));

            assertThat(files(store.dsl()))
                .as("the deleted file is pruned and the unwalked root's file is not")
                .containsExactly(tuple(
                    sibling.resolve("com/example/Gadgets.java").toString(), sibling.toString()));
            assertThat(store.dsl().selectCount().from(JAVA_METHOD_DECLARATION).fetchOne(0, int.class))
                .as("the pruned file's declarations went with its row")
                .isEqualTo(1);
        }
    }

    /**
     * A parse reads what a compiler refuses, so the writer has to answer for input no key can hold:
     * one file declaring one class name twice. First declaration wins, which is the merge policy
     * the projection beside it uses for the same case.
     */
    @Test
    @DisplayName("a file declaring one class name twice contributes the first")
    void aDuplicateClassNameIsFirstWins(@TempDir Path root) throws IOException {
        write(root, "com/example/Twice.java", """
            package com.example;
            /** The first. */
            class Twice { }
            /** The second. */
            class Twice { }
            """);

        try (var store = FactStores.inMemory()) {
            refreshJavaSources(store.dsl(), List.of(root));

            assertThat(store.dsl().select(JAVA_CLASS_DECLARATION.JAVADOC)
                .from(JAVA_CLASS_DECLARATION)
                .where(JAVA_CLASS_DECLARATION.CLASS_NAME.eq("com.example.Twice"))
                .fetch(0, String.class))
                .as("one row for the name, the first declaration's")
                .containsExactly("The first.");
        }
    }

    /**
     * The arm the observation is judged on, because it is the arm a developer is actually in: a
     * session whose store is already warm, where the first refresh rewrites nothing at all. Written
     * against a cold store the same assertions would pass for the wrong reason, every file having
     * been rewritten and dated on the way through, which is why the seeding is the test rather than
     * a detail of it.
     */
    @Test
    @DisplayName("over a warm store, a verified file is trusted and the next pass hashes nothing")
    void aWarmStoreIsCheapFromTheFirstPass(@TempDir Path root) throws IOException {
        write(root, "com/example/Widgets.java", WIDGETS);
        write(root, "com/example/Gadgets.java", GADGETS);
        var roots = List.of(root);

        try (var store = FactStores.inMemory()) {
            // A previous session's store: the rows are current and their dates are that session's,
            // which this session's floor outranks.
            refreshJavaSources(store.dsl(), roots);

            var observation = new Observation(store.dsl());
            observation.observing(JAVA_SOURCE);
            var walker = new SourceWalker();

            var seed = refreshJavaSources(store.dsl(), roots, walker, observation);
            assertThat(tuple(seed.hashed(), seed.skipped(), seed.rewritten()))
                .as("the seed cannot trust what a previous session read, so it hashes everything;"
                    + " it rewrites nothing, the store already agreeing")
                .isEqualTo(tuple(2, 0, 0));

            var steady = refreshJavaSources(store.dsl(), roots, walker, observation);
            assertThat(tuple(steady.hashed(), steady.skipped(), steady.rewritten()))
                .as("and the pass after it hashes nothing: verification is the read rather than the"
                    + " rewrite, so the seed's own reads are what established this")
                .isEqualTo(tuple(0, 2, 0));
        }
    }

    @Test
    @DisplayName("a marked file is hashed and its neighbours are not")
    void onlyTheMarkedFileIsHashed(@TempDir Path root) throws IOException {
        Path widgets = write(root, "com/example/Widgets.java", WIDGETS);
        write(root, "com/example/Gadgets.java", GADGETS);
        var roots = List.of(root);

        try (var store = FactStores.inMemory()) {
            var observation = new Observation(store.dsl());
            observation.observing(JAVA_SOURCE);
            var walker = new SourceWalker();
            refreshJavaSources(store.dsl(), roots, walker, observation);
            refreshJavaSources(store.dsl(), roots, walker, observation);

            Files.writeString(widgets, WIDGETS.replace("A widget service.", "A widget shop."));
            observation.mark(widgets);
            var round = refreshJavaSources(store.dsl(), roots, walker, observation);

            assertThat(tuple(round.hashed(), round.skipped(), round.rewritten()))
                .as("the edit costs one hash and one rewrite, where before it cost the tree")
                .isEqualTo(tuple(1, 1, 1));
            assertThat(classRows(store.dsl()))
                .as("and the rewritten rows are the edited file's")
                .contains(tuple(widgets.toString(), "com.example.Widgets", 3, 1, "A widget shop."));
        }
    }

    @Test
    @DisplayName("a false mark costs one hash, not the rest of the session")
    void aMarkOnAnUnchangedFileIsCheap(@TempDir Path root) throws IOException {
        Path widgets = write(root, "com/example/Widgets.java", WIDGETS);
        var roots = List.of(root);

        try (var store = FactStores.inMemory()) {
            var observation = new Observation(store.dsl());
            observation.observing(JAVA_SOURCE);
            var walker = new SourceWalker();
            refreshJavaSources(store.dsl(), roots, walker, observation);

            // A save that changed nothing: an editor writing the buffer back unmodified, or a
            // build touching a generated source it regenerated identically.
            observation.mark(widgets);
            var marked = refreshJavaSources(store.dsl(), roots, walker, observation);
            assertThat(tuple(marked.hashed(), marked.rewritten()))
                .as("distrust discards no stamp, so the file is hashed, compared and skipped")
                .isEqualTo(tuple(1, 0));

            var after = refreshJavaSources(store.dsl(), roots, walker, observation);
            assertThat(after.hashed())
                .as("and it is trusted again straight afterwards, the hash having verified it")
                .isZero();
        }
    }

    @Test
    @DisplayName("a file the pass could not read keeps the date it had and is read again")
    void anUnreadableFileIsNotDated(@TempDir Path root) throws IOException {
        Path widgets = write(root, "com/example/Widgets.java", WIDGETS);
        write(root, "com/example/Gadgets.java", GADGETS);
        var roots = List.of(root);

        try (var store = FactStores.inMemory()) {
            var observation = new Observation(store.dsl());
            observation.observing(JAVA_SOURCE);
            var facts = FactWriters.javaSourceFacts(store.dsl(), observation);
            facts.register(roots);
            var walk = new SourceWalker().walkFiles(roots);
            facts.refresh(roots, walk, facts.beginPass());

            // The walk read it and the hash cannot: the file left between the two. The same walk
            // is replayed both times, so the subject is the writer's own answer rather than the
            // walker's, and the mark is what puts the file on the hashing arm at all.
            Files.delete(widgets);
            observation.mark(widgets);
            var attempt = facts.refresh(roots, walk, facts.beginPass());
            assertThat(tuple(attempt.hashed(), attempt.rewritten()))
                .as("the file is read and cannot be, so nothing about it is established")
                .isEqualTo(tuple(1, 0));

            var next = facts.refresh(roots, walk, facts.beginPass());
            assertThat(next.hashed())
                .as("so it is read again next pass rather than skipped for the rest of the"
                    + " session; its neighbour, which was verified, is not")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("with no observation every walked file is hashed, which is the one-shot path")
    void withoutAnObservationEverythingIsHashed(@TempDir Path root) throws IOException {
        write(root, "com/example/Widgets.java", WIDGETS);
        write(root, "com/example/Gadgets.java", GADGETS);
        var roots = List.of(root);

        try (var store = FactStores.inMemory()) {
            var walker = new SourceWalker();
            refreshJavaSources(store.dsl(), roots, walker, null);
            var round = refreshJavaSources(store.dsl(), roots, walker, null);

            assertThat(tuple(round.hashed(), round.skipped()))
                .as("a caller watching nothing can vouch for nothing, so it reads the bytes")
                .isEqualTo(tuple(2, 0));
        }
    }

    private static List<Tuple> files(DSLContext dsl) {
        return dsl.select(JAVA_FILE.FILE, JAVA_FILE.SOURCE_ROOT).from(JAVA_FILE)
            .fetch(row -> tuple(row.value1(), row.value2()));
    }

    private static List<Tuple> classRows(DSLContext dsl) {
        return dsl.select(JAVA_CLASS_DECLARATION.FILE, JAVA_CLASS_DECLARATION.CLASS_NAME,
                JAVA_CLASS_DECLARATION.SOURCE_LINE, JAVA_CLASS_DECLARATION.SOURCE_COLUMN,
                JAVA_CLASS_DECLARATION.JAVADOC)
            .from(JAVA_CLASS_DECLARATION)
            .fetch(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4(), row.value5()));
    }

    private static List<Tuple> methodRows(DSLContext dsl) {
        return dsl.select(JAVA_METHOD_DECLARATION.FILE, JAVA_METHOD_DECLARATION.CLASS_NAME,
                JAVA_METHOD_DECLARATION.METHOD_NAME, JAVA_METHOD_DECLARATION.PARAMETER_COUNT,
                JAVA_METHOD_DECLARATION.SOURCE_LINE, JAVA_METHOD_DECLARATION.SOURCE_COLUMN,
                JAVA_METHOD_DECLARATION.JAVADOC)
            .from(JAVA_METHOD_DECLARATION)
            .fetch(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5(), row.value6(), row.value7()));
    }

    private static List<Tuple> fieldRows(DSLContext dsl) {
        return dsl.select(JAVA_FIELD_DECLARATION.FILE, JAVA_FIELD_DECLARATION.CLASS_NAME,
                JAVA_FIELD_DECLARATION.FIELD_NAME, JAVA_FIELD_DECLARATION.SOURCE_LINE,
                JAVA_FIELD_DECLARATION.SOURCE_COLUMN, JAVA_FIELD_DECLARATION.JAVADOC)
            .from(JAVA_FIELD_DECLARATION)
            .fetch(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5(), row.value6()));
    }

    private static List<Tuple> expectedFiles(List<SourceWalker.ParsedFile> walk, Path root) {
        return walk.stream().map(f -> tuple(f.file().toString(), root.toString())).toList();
    }

    /** The walk's declarations of one arm, projected the way the relation holding them keys them. */
    private static <D extends SourceWalker.Declaration> List<Tuple> expected(
        List<SourceWalker.ParsedFile> walk, java.lang.Class<D> arm,
        java.util.function.BiFunction<String, D, Tuple> row
    ) {
        var out = new ArrayList<Tuple>();
        for (SourceWalker.ParsedFile file : walk) {
            for (SourceWalker.Declaration declaration : file.declarations()) {
                if (arm.isInstance(declaration)) {
                    out.add(row.apply(file.file().toString(), arm.cast(declaration)));
                }
            }
        }
        return out;
    }

    /** The store's spelling of an absent doc comment, which the walker spells as the empty string. */
    private static String javadoc(String javadoc) {
        return javadoc.isEmpty() ? null : javadoc;
    }

    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
