package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.command.UnitRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Writes a generated compilation unit only when its content differs from the file already on
 * disk, and reports which it did. Leaving an unchanged file untouched keeps its timestamp, so the
 * consumer's incremental compile and the dev loop see no change where there is none; the
 * {@link Written#changed()} flag is what feeds {@link GraphQLRewriteGenerator.GenerationResult#changedUnits()}.
 */
final class IdempotentWriter {

    private IdempotentWriter() {
    }

    /** Where a write landed, and whether it touched the file. */
    record Written(Path path, boolean changed) {}

    /**
     * Writes {@code source} as UTF-8 to the {@code .java} file {@code ref} addresses under
     * {@code outputDirectory}, creating the package directories as needed, unless the file
     * already holds exactly those bytes.
     */
    static Written write(Path outputDirectory, UnitRef ref, String source) throws IOException {
        if (Files.exists(outputDirectory) && !Files.isDirectory(outputDirectory)) {
            throw new IllegalArgumentException("path " + outputDirectory + " exists but is not a directory");
        }
        Path packageDir = outputDirectory;
        if (!ref.packageName().isEmpty()) {
            for (String segment : ref.packageName().split("\\.")) {
                packageDir = packageDir.resolve(segment);
            }
        }
        Path target = packageDir.resolve(ref.simpleName() + ".java");
        byte[] content = source.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content)) {
            return new Written(target, false);
        }
        Files.createDirectories(packageDir);
        Files.write(target, content);
        return new Written(target, true);
    }
}
