package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import org.apache.maven.plugin.MojoExecutionException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Expands {@link SchemaInputBinding} glob patterns into resolved {@link SchemaInput} records.
 * The walk itself is {@link SchemaRecipe#expand}, rewrite-core's one glob dialect, because the
 * pattern a build expands is also a pattern capture records and a currency check re-expands, and
 * two implementations of the dialect could not be kept in agreement. What stays here is the
 * Maven-shaped work: reading {@link SchemaInputBinding}, the empty-pattern diagnostics, and the
 * {@link MojoExecutionException} shapes.
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
            List<String> matches;
            try {
                matches = SchemaRecipe.expand(basedir, b.pattern, schemaFileExtensions);
            } catch (RuntimeException e) {
                throw new MojoExecutionException(
                    "<schemaInput pattern='" + b.pattern + "'> scanner error (entry #" + i + "): " + e.getMessage(), e);
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
}
