package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.DirectoryScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Expands {@link SchemaInputBinding} glob patterns into resolved {@link SchemaInput} records.
 * Rewrite-core's {@link SchemaInput} holds one concrete source per entry; the pattern-to-file
 * expansion lives here so rewrite-core stays filesystem-agnostic.
 */
class SchemaInputExpander {

    private SchemaInputExpander() {}

    static List<SchemaInput> expand(List<SchemaInputBinding> bindings, Path basedir)
            throws MojoExecutionException {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        var expanded = new ArrayList<SchemaInput>();
        for (int i = 0; i < bindings.size(); i++) {
            var b = bindings.get(i);
            var scanner = new DirectoryScanner();
            scanner.setBasedir(basedir.toFile());
            scanner.setIncludes(new String[]{b.pattern});
            try {
                scanner.scan();
            } catch (RuntimeException e) {
                throw new MojoExecutionException(
                    "<schemaInput pattern='" + b.pattern + "'> scanner error (entry #" + i + "): " + e.getMessage(), e);
            }
            var matches = scanner.getIncludedFiles();
            if (matches.length == 0) {
                throw new MojoExecutionException(
                    "<schemaInput pattern='" + b.pattern + "'> matched no files (entry #" + i + ")");
            }
            var tag = Optional.ofNullable(b.tag).filter(s -> !s.isEmpty());
            var note = Optional.ofNullable(b.descriptionNote).filter(s -> !s.isEmpty());
            for (var rel : matches) {
                var abs = basedir.resolve(rel).toAbsolutePath().normalize().toString();
                expanded.add(new SchemaInput(abs, tag, note));
            }
        }
        return expanded;
    }
}
