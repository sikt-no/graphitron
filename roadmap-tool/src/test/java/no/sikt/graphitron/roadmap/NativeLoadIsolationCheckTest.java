package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the native-load isolation contract: a module carrying the ONNX/tokenizer stack must run its
 * {@code exec-maven-plugin} executions in a forked JVM, because an in-process {@code exec:java}
 * loads the native library behind a per-execution class loader and the second build in a reused JVM
 * then cannot load it at all. Modules without the stack keep their in-process executions; commented-out
 * XML never trips the scan; and the reactor's own poms satisfy the check.
 */
class NativeLoadIsolationCheckTest {

    @Test
    void forkedExecutionInMarkedModule_passes() {
        assertThat(NativeLoadIsolationCheck.checkPom("graphitron-mcp",
            markedModule("<executions><execution><id>build-docs-index</id>"
                + "<goals><goal>exec</goal></goals></execution></executions>")))
            .isEmpty();
    }

    @Test
    void inProcessExecutionInMarkedModule_failsNamingIdAndConsequence() {
        assertThat(NativeLoadIsolationCheck.checkPom("graphitron-mcp",
            markedModule("<executions><execution><id>build-docs-index</id>"
                + "<goals><goal>java</goal></goals></execution></executions>")))
            .singleElement().asString()
            .contains("graphitron-mcp")
            .contains("'build-docs-index'")
            .contains("langchain4j-embeddings-bge-small-en-v15-q")
            .contains("already loaded in another classloader");
    }

    @Test
    void inProcessExecutionInUnmarkedModule_passes() {
        // roadmap-tool and graphitron-model both bind exec:java executions on purpose; nothing
        // they run touches a native library, so the loader-per-execution churn cannot hurt them.
        String pom = "<project><build><plugins>"
            + "<plugin><artifactId>exec-maven-plugin</artifactId>"
            + "<executions><execution><id>check-adoc-tables</id>"
            + "<goals><goal>java</goal></goals></execution></executions></plugin>"
            + "</plugins></build></project>";

        assertThat(NativeLoadIsolationCheck.checkPom("roadmap-tool", pom)).isEmpty();
    }

    @Test
    void directOnnxRuntimeDependency_isAlsoMarked() {
        String pom = "<project><dependencies><dependency>"
            + "<groupId>com.microsoft.onnxruntime</groupId><artifactId>onnxruntime</artifactId>"
            + "</dependency></dependencies><build><plugins>"
            + "<plugin><artifactId>exec-maven-plugin</artifactId>"
            + "<executions><execution><id>embed</id><goals><goal>java</goal></goals>"
            + "</execution></executions></plugin></plugins></build></project>";

        assertThat(NativeLoadIsolationCheck.checkPom("m", pom))
            .singleElement().asString().contains("onnxruntime");
    }

    @Test
    void unnamedExecution_isReportedWithoutAnId() {
        assertThat(NativeLoadIsolationCheck.checkPom("m",
            markedModule("<executions><execution><goals><goal>java</goal></goals>"
                + "</execution></executions>")))
            .singleElement().asString().contains("(unnamed)");
    }

    @Test
    void configurationOnlyExecPlugin_passes() {
        // Configuration without an <execution> binds nothing, so there is no in-process run to fix.
        assertThat(NativeLoadIsolationCheck.checkPom("graphitron-mcp",
            markedModule("<configuration><mainClass>Whatever</mainClass></configuration>")))
            .isEmpty();
    }

    @Test
    void commentedOutInProcessExecution_doesNotTrip() {
        assertThat(NativeLoadIsolationCheck.checkPom("graphitron-mcp",
            markedModule("<!-- <executions><execution><id>old</id>"
                + "<goals><goal>java</goal></goals></execution></executions> -->")))
            .isEmpty();
    }

    @Test
    void everyInProcessExecutionInAMarkedModule_isReported() {
        assertThat(NativeLoadIsolationCheck.checkPom("graphitron-mcp",
            markedModule("<executions>"
                + "<execution><id>one</id><goals><goal>java</goal></goals></execution>"
                + "<execution><id>two</id><goals><goal>exec</goal></goals></execution>"
                + "<execution><id>three</id><goals><goal>java</goal></goals></execution>"
                + "</executions>")))
            .hasSize(2)
            .anySatisfy(p -> assertThat(p).contains("'one'"))
            .anySatisfy(p -> assertThat(p).contains("'three'"));
    }

    @Test
    void noExecPluginAtAll_passes() {
        assertThat(NativeLoadIsolationCheck.checkPom("m", "<project></project>")).isEmpty();
    }

    @Test
    void run_withViolatingModule_throwsBuildFailure(@TempDir Path dir) throws IOException {
        writeTree(dir, markedModule("<executions><execution><id>embed</id>"
            + "<goals><goal>java</goal></goals></execution></executions>"));

        assertThatThrownBy(() -> NativeLoadIsolationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_withDeclaredModuleMissingItsPom_throwsBuildFailure(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"),
            "<project><modules><module>missing</module></modules></project>");

        assertThatThrownBy(() -> NativeLoadIsolationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_clean_returnsZero(@TempDir Path dir) throws IOException {
        writeTree(dir, markedModule("<executions><execution><id>embed</id>"
            + "<goals><goal>exec</goal></goals></execution></executions>"));

        assertThat(NativeLoadIsolationCheck.run(List.of(dir.toString()))).isZero();
    }

    @Test
    void run_usageError_returnsExitCodeWithoutThrowing() throws IOException {
        assertThat(NativeLoadIsolationCheck.run(List.of())).isEqualTo(64);
    }

    @Test
    void run_againstThisRepository_isClean() throws IOException {
        // The assertion that graphitron-mcp's docs-index execution still forks. Reverting it to
        // exec:java reddens this, which is the point: no build reproduces the failure it causes.
        assertThat(NativeLoadIsolationCheck.run(List.of(repoRoot().toString()))).isZero();
    }

    /** A pom carrying the bge dependency, with {@code execBody} inside its exec-maven-plugin block. */
    private static String markedModule(String execBody) {
        return "<project><dependencies><dependency>"
            + "<groupId>dev.langchain4j</groupId>"
            + "<artifactId>langchain4j-embeddings-bge-small-en-v15-q</artifactId>"
            + "</dependency></dependencies><build><plugins>"
            + "<plugin><artifactId>exec-maven-plugin</artifactId>" + execBody + "</plugin>"
            + "</plugins></build></project>";
    }

    /** A root pom declaring one module, whose pom is {@code modulePom}. */
    private static void writeTree(Path root, String modulePom) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules><module>m</module></modules></project>");
        Files.createDirectories(root.resolve("m"));
        Files.writeString(root.resolve("m/pom.xml"), modulePom);
    }

    /** Walks up from the module basedir to the reactor root. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("reactor root holding CLAUDE.md").isNotNull();
        return dir;
    }
}
