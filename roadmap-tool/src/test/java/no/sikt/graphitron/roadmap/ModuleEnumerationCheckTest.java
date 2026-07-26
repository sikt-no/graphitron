package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the module-enumeration contract: a module declared by the reactor pom but not named in an
 * enumerating document is a problem, a backtick-free prose occurrence does not count as naming it,
 * and a root pom that parses to no modules fails the run instead of passing vacuously against
 * every document.
 */
class ModuleEnumerationCheckTest {

    private static final String POM = """
        <project>
          <modules>
            <module>graphitron</module>
            <module>roadmap-tool</module>
            <module>docs</module>
          </modules>
        </project>
        """;

    @Test
    void declaredModules_areParsedInDeclarationOrder() {
        assertThat(ModuleEnumerationCheck.declaredModules(POM))
            .containsExactly("graphitron", "roadmap-tool", "docs");
    }

    @Test
    void moduleNotNamed_isReported() {
        String doc = "Modules: `graphitron`, `roadmap-tool`.\n";

        assertThat(ModuleEnumerationCheck.unnamedModules(doc, ModuleEnumerationCheck.declaredModules(POM)))
            .containsExactly("docs");
    }

    @Test
    void unbacktickedOccurrence_doesNotCountAsNaming() {
        // `docs` is also an ordinary word in prose, so only the backticked identifier counts. This is
        // what keeps the check from passing on a document that merely mentions the directory.
        String doc = "Modules: `graphitron`, `roadmap-tool`. The docs render to a site.\n";

        assertThat(ModuleEnumerationCheck.unnamedModules(doc, ModuleEnumerationCheck.declaredModules(POM)))
            .containsExactly("docs");
    }

    @Test
    void everyModuleNamed_isClean() {
        String doc = "Modules: `graphitron`, `roadmap-tool`, `docs`.\n";

        assertThat(ModuleEnumerationCheck.unnamedModules(doc, ModuleEnumerationCheck.declaredModules(POM))).isEmpty();
    }

    @Test
    void moduleRemovedFromPom_isNotFlagged() {
        // Checked in one direction only: a document naming a module the pom no longer declares is
        // out of scope, because a module identifier and an ordinary backticked word are not
        // distinguishable in prose.
        String doc = "Modules: `graphitron`, `roadmap-tool`, `docs`, `graphitron-retired`.\n";

        assertThat(ModuleEnumerationCheck.unnamedModules(doc, Set.of("graphitron"))).isEmpty();
    }

    @Test
    void run_withMissingModuleName_throwsBuildFailure(@TempDir Path dir) throws IOException {
        writeTree(dir, POM, "Modules: `graphitron`, `roadmap-tool`.\n");

        assertThatThrownBy(() -> ModuleEnumerationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_withPomDeclaringNoModules_throwsBuildFailure(@TempDir Path dir) throws IOException {
        // The floor against a vacuous pass: no modules parsed means the check is reading the wrong
        // file or the wrong shape, and every document would satisfy an empty requirement.
        writeTree(dir, "<project><artifactId>standalone</artifactId></project>\n", "No modules here.\n");

        assertThatThrownBy(() -> ModuleEnumerationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_withMissingDocument_throwsBuildFailure(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), POM);
        Files.writeString(dir.resolve("CLAUDE.md"), "Modules: `graphitron`, `roadmap-tool`, `docs`.\n");

        assertThatThrownBy(() -> ModuleEnumerationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_withNoPom_throwsBuildFailure(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("CLAUDE.md"), "Modules: `graphitron`.\n");

        assertThatThrownBy(() -> ModuleEnumerationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_clean_returnsZero(@TempDir Path dir) throws IOException {
        writeTree(dir, POM, "Modules: `graphitron`, `roadmap-tool`, `docs`.\n");

        assertThat(ModuleEnumerationCheck.run(List.of(dir.toString()))).isZero();
    }

    @Test
    void run_usageError_returnsExitCodeWithoutThrowing() throws IOException {
        assertThat(ModuleEnumerationCheck.run(List.of())).isEqualTo(64);
    }

    @Test
    void run_againstThisRepository_isClean() throws IOException {
        // The reactor's own enumerating documents must satisfy the check they are guarded by; this
        // is the assertion that caught both of them describing an eleven-module reactor.
        assertThat(ModuleEnumerationCheck.run(List.of(repoRoot().toString()))).isZero();
    }

    /** Writes a reactor pom plus the same text into every enumerating document the check declares. */
    private static void writeTree(Path root, String pomXml, String docText) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pomXml);
        for (String doc : ModuleEnumerationCheck.ENUMERATING_DOCS) {
            Path file = root.resolve(doc);
            Files.createDirectories(file.getParent());
            Files.writeString(file, docText);
        }
    }

    /** Walks up from the module basedir to the reactor root that holds the enumerating documents. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("reactor root holding CLAUDE.md").isNotNull();
        return dir;
    }
}
