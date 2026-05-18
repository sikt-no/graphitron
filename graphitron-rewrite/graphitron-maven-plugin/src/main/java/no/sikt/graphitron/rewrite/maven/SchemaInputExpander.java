package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.DirectoryScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Expands {@link SchemaInputBinding} glob patterns into resolved {@link SchemaInput} records.
 * Rewrite-core's {@link SchemaInput} holds one concrete source per entry; the pattern-to-file
 * expansion lives here so rewrite-core stays filesystem-agnostic.
 */
class SchemaInputExpander {

    private SchemaInputExpander() {}

    /**
     * Result of expanding a list of {@link SchemaInputBinding} entries: the flat list of resolved
     * {@link SchemaInput} sources and any per-binding empty-pattern observations the caller can
     * surface as warnings. Per-pattern empty matches are tolerated (other bindings can still
     * produce content); the aggregate-empty case (every configured pattern matched zero) is
     * thrown from {@link #expand} rather than handed back, so a non-empty {@code inputs} list
     * is the only successful shape.
     */
    record ExpansionResult(List<SchemaInput> inputs, List<EmptyPattern> emptyPatterns) {
        record EmptyPattern(int entryIndex, String pattern) {}
    }

    static ExpansionResult expand(List<SchemaInputBinding> bindings, Path basedir,
            Set<String> schemaFileExtensions) throws MojoExecutionException {
        if (bindings == null || bindings.isEmpty()) {
            return new ExpansionResult(List.of(), List.of());
        }
        var expanded = new ArrayList<SchemaInput>();
        var emptyPatterns = new ArrayList<ExpansionResult.EmptyPattern>();
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
            var rawMatches = scanner.getIncludedFiles();
            var matches = new ArrayList<String>(rawMatches.length);
            for (var rel : rawMatches) {
                if (matchesExtension(rel, schemaFileExtensions)) {
                    matches.add(rel);
                }
            }
            if (matches.isEmpty()) {
                emptyPatterns.add(new ExpansionResult.EmptyPattern(i, b.pattern));
                continue;
            }
            var tag = Optional.ofNullable(b.tag).filter(s -> !s.isEmpty());
            var note = Optional.ofNullable(b.descriptionNote).filter(s -> !s.isEmpty());
            for (var rel : matches) {
                var abs = basedir.resolve(rel).toAbsolutePath().normalize().toString();
                expanded.add(new SchemaInput(abs, tag, note));
            }
        }
        if (expanded.isEmpty()) {
            var sb = new StringBuilder("<schemaInputs> matched no files. Empty patterns:");
            for (var ep : emptyPatterns) {
                sb.append("\n  entry #").append(ep.entryIndex()).append(": ").append(ep.pattern());
            }
            throw new MojoExecutionException(sb.toString());
        }
        return new ExpansionResult(expanded, List.copyOf(emptyPatterns));
    }

    private static boolean matchesExtension(String relativePath, Set<String> schemaFileExtensions) {
        int sep = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        var filename = sep < 0 ? relativePath : relativePath.substring(sep + 1);
        for (String ext : schemaFileExtensions) {
            if (filename.endsWith(ext)) return true;
        }
        return false;
    }
}
