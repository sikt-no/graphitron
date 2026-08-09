package no.sikt.graphitron.rewrite.schema.input;

import org.codehaus.plexus.util.DirectoryScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A graph's SDL recipe: how to <em>find</em> its schema files, as resolved configuration rather
 * than as the file list one expansion produced. Capture persists it (the
 * {@code store_graph_schema_input} / {@code store_graph_schema_extension} relations), so a
 * currency check can re-expand the globs over the graph's base directory without building the
 * owning module, and discover added or deleted schema files a check over recorded sources alone
 * is blind to.
 *
 * <p>This class also owns the one glob dialect the recipe's contract is worth: a recorded pattern
 * is only worth as much as the engine that re-expands it, and two implementations that must agree
 * and cannot be made to would return confidently wrong currency verdicts. {@link #expand} is that
 * single implementation (plexus {@link DirectoryScanner} includes plus the schema-file-extension
 * filter); the Maven plugin's {@code SchemaInputExpander} delegates to it, and the freshness
 * replay runs over it, so both sides of a currency comparison walk with one dialect.
 *
 * @param buildFile the build file the recipe was resolved from (the module's pom), absolute and
 *                  normalized; {@code null} on a programmatic run with no build file. Its content
 *                  hash is the recipe's trust anchor: a remembered recipe is replayed only while
 *                  the build file still hashes to what capture recorded
 * @param bindings  the resolved {@code <schemaInputs>} bindings, in configuration order
 * @param extensions the effective schema-file-extension filter (leading dot included), one
 *                  per-run set beside the bindings rather than under them
 */
public record SchemaRecipe(Path buildFile, List<Binding> bindings, List<String> extensions) {

    /**
     * One resolved {@code <schemaInput>} binding. The tag and description note are not optional
     * fidelity: their appliers run above the capture cut, so replaying a graph's SDL capture
     * without them would mint different rows than the graph's own build.
     */
    public record Binding(String pattern, Optional<String> tag, Optional<String> descriptionNote) {
        public Binding {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(descriptionNote, "descriptionNote");
        }
    }

    public SchemaRecipe {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(extensions, "extensions");
        bindings = List.copyOf(bindings);
        extensions = List.copyOf(extensions);
    }

    /**
     * Expands one include pattern under {@code baseDir} and filters to the schema-file
     * extensions, returning base-relative paths in the scanner's order. The dialect, in one
     * place: plexus {@link DirectoryScanner} includes, matched against the file-name component
     * with {@link String#endsWith}, case-sensitively. Scanner trouble propagates as the
     * scanner's own {@link RuntimeException}; wrapping it in a build-tool exception is the
     * caller's dialect, not this one's.
     */
    public static List<String> expand(Path baseDir, String pattern, Collection<String> extensions) {
        var scanner = new DirectoryScanner();
        scanner.setBasedir(baseDir.toFile());
        scanner.setIncludes(new String[]{pattern});
        scanner.scan();
        var matches = new ArrayList<String>();
        for (String rel : scanner.getIncludedFiles()) {
            if (matchesExtension(rel, extensions)) {
                matches.add(rel);
            }
        }
        return matches;
    }

    /**
     * Re-expands the whole recipe over {@code baseDir}: every binding's matches, as absolute
     * normalized paths, deduplicated across bindings. This is the set a currency check compares
     * against the graph's recorded read-set.
     */
    public Set<Path> expand(Path baseDir) {
        var files = new LinkedHashSet<Path>();
        for (Binding binding : bindings) {
            for (String rel : expand(baseDir, binding.pattern(), extensions)) {
                files.add(baseDir.resolve(rel).toAbsolutePath().normalize());
            }
        }
        return files;
    }

    private static boolean matchesExtension(String relativePath, Collection<String> extensions) {
        int sep = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        var filename = sep < 0 ? relativePath : relativePath.substring(sep + 1);
        for (String ext : extensions) {
            if (filename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
