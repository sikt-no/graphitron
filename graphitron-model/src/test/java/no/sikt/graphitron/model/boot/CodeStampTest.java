package no.sikt.graphitron.model.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/** The code half of the store's stamp is the code's content, not where or how it sits on disk. */
class CodeStampTest {

    @Test
    @DisplayName("a classes directory and the jar built from it stamp alike, and a changed class does not")
    void theStampIsTheContent(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        write(classes.resolve("no/sikt/A.class"), "a");
        write(classes.resolve("no/sikt/graphitron-model.sql"), "CREATE TABLE t (x INT);");
        Path jar = jar(tmp.resolve("model.jar"), classes, "10-SNAPSHOT");

        String stamp = CodeStamp.of(classes);
        assertThat(stamp).as("sixteen hex digits").matches("[0-9a-f]{16}");
        assertThat(CodeStamp.of(jar)).as("the jar, its manifest and Maven metadata aside").isEqualTo(stamp);
        assertThat(CodeStamp.of(jar(tmp.resolve("rebuilt.jar"), classes, "10-SNAPSHOT")))
            .as("the same code packed again").isEqualTo(stamp);

        write(classes.resolve("no/sikt/A.class"), "b");
        assertThat(CodeStamp.of(classes)).as("a class whose bytes changed").isNotEqualTo(stamp);

        write(classes.resolve("no/sikt/A.class"), "a");
        write(classes.resolve("no/sikt/graphitron-model.sql"), "CREATE TABLE t (y INT);");
        assertThat(CodeStamp.of(classes)).as("a resource whose bytes changed").isNotEqualTo(stamp);
    }

    @Test
    @DisplayName("this JVM's stamp is computed once and is the module's own")
    void thisJvmsStampIsStable() {
        assertThat(CodeStamp.current()).isSameAs(CodeStamp.current()).isNotEqualTo(CodeStamp.UNKNOWN);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** {@code classes} packed as a build packs it, with a manifest and Maven's metadata beside them. */
    private static Path jar(Path jar, Path classes, String version) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var files = Files.walk(classes)) {
            out.putNextEntry(new JarEntry("META-INF/maven/no.sikt/graphitron-model/pom.properties"));
            out.write(("version=" + version + "\nbuilt=" + jar.getFileName()).getBytes());
            out.closeEntry();
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }
}
