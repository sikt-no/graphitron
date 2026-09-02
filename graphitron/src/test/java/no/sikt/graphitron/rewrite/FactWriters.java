package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.capture.GraphIdentity;
import no.sikt.graphitron.rewrite.capture.JavaSourceFacts;
import no.sikt.graphitron.rewrite.capture.SourceWalker;
import no.sikt.graphitron.rewrite.compile.CompileFacts;
import no.sikt.graphitron.rewrite.diagnostics.BuildWarningFacts;
import no.sikt.graphitron.rewrite.diagnostics.RejectionFacts;
import org.jooq.DSLContext;

import java.nio.file.Path;
import java.util.List;

/**
 * The shipped facts writers, over a store somebody else opened: the writer-level population, for
 * the tests whose subject is what a writer puts in a table at its own cadence.
 *
 * <p><b>Which harness is this.</b> A writer runs on its own cadence and answers for its own
 * partition, which is a different fact about the store from what a capture walk writes. So this
 * level is a sibling of {@link CapturedStore} rather than something under it: a writer test has no
 * capture to run, and a fixture that captured first would have put rows in front of the assertion
 * that the writer did not write. It is the sibling of
 * {@link no.sikt.graphitron.model.test.SeededStore} one module up, in the same sense: that one puts
 * rows in a store by stating them, this one by running the code that ships.
 *
 * <p><b>Over a {@link DSLContext}, owning nothing.</b> The store comes from
 * {@link no.sikt.graphitron.model.test.FactStores}, whichever of its two shapes the case wants, and
 * the caller closes it. That is what lets a module's own fixture delegate its writer calls here
 * while keeping the store it already holds, and what keeps a case that drives two writers over one
 * store from having to say which of them owns it.
 *
 * <p><b>The graph identity is two arguments rather than a type.</b> Every graph-scoped writer takes
 * the graph's name and the directory it was captured from, because those are the two values a case
 * varies: a second graph to say the partition holds, a second directory to say a checkout that does
 * not own the graph writes nothing. Assembling {@link GraphIdentity} is this level's job,
 * so a call site states what it is varying and nothing else.
 */
public final class FactWriters {

    private FactWriters() {}

    /**
     * The {@code javac_} family's writer for one graph. Its rounds replace each other wholesale
     * within the graph, so a case about replacement calls {@link CompileFacts#write} twice.
     */
    public static CompileFacts compileFacts(DSLContext dsl, String graphName, Path baseDir) {
        return new CompileFacts(dsl, identity(graphName, baseDir));
    }

    /** The rejection residue's writer for one graph, which the build's own report goes through. */
    public static RejectionFacts rejectionFacts(DSLContext dsl, String graphName, Path baseDir) {
        return new RejectionFacts(dsl, identity(graphName, baseDir));
    }

    /** The warning family's writer for one graph, taking the list a build already suppressed. */
    public static BuildWarningFacts buildWarningFacts(DSLContext dsl, String graphName, Path baseDir) {
        return new BuildWarningFacts(dsl, identity(graphName, baseDir));
    }

    /**
     * The {@code java_} declaration family's writer, which takes no graph. This family is written by
     * neither capture nor a graph, so it is partitioned by source file where the graph-scoped
     * writers above are partitioned by graph; {@link #refreshJavaSources} is the whole of what
     * driving it looks like unless a case needs the walk and the write to disagree.
     */
    public static JavaSourceFacts javaSourceFacts(DSLContext dsl) {
        return new JavaSourceFacts(dsl);
    }

    /**
     * Walks {@code roots} and refreshes the store from what it read, which is the one shape the
     * production callers use: one walk, one sink. Returns the walk, so a case can assert that the
     * rows are the parse reduced the same way rather than restating the parse by hand.
     *
     * <p>The roots are both the walk's inputs and the pruning scope, which is why they are one
     * argument here. A case whose subject is the two disagreeing takes {@link #javaSourceFacts}
     * and passes its own walk.
     */
    public static List<SourceWalker.ParsedFile> refreshJavaSources(DSLContext dsl, List<Path> roots) {
        var walk = new SourceWalker().walkFiles(roots);
        javaSourceFacts(dsl).refresh(roots, walk);
        return walk;
    }

    private static GraphIdentity identity(String graphName, Path baseDir) {
        return new GraphIdentity(graphName, baseDir);
    }
}
