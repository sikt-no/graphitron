package no.sikt.graphitron.rewrite.schema.input;

import org.codehaus.plexus.util.DirectoryScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A graph's SDL recipe: how to <em>find</em> its schema files, as resolved configuration rather
 * than as the file list one expansion produced. Capture persists it (the
 * {@code store_graph_schema_input} / {@code store_graph_schema_extension} relations), so a
 * currency check can re-expand the globs over the graph's base directory without building the
 * owning module, and discover added or deleted schema files a check over recorded sources alone
 * is blind to.
 *
 * <p>Every run has a recipe, not only a run with {@code <schemaInputs>} configured: an entry is a
 * glob pattern or a literal source, so a programmatic caller's own input list transcribes as
 * literal entries and its graph is as replayable as a build's.
 *
 * <p>This class also owns the one glob dialect the recipe's contract is worth: a recorded pattern
 * is only worth as much as the engine that re-expands it, and two implementations that must agree
 * and cannot be made to would return confidently wrong currency verdicts. {@link #expand(Path,
 * String, Collection)} is that single implementation (plexus {@link DirectoryScanner} includes plus
 * the schema-file-extension filter) and stays the dialect's one primitive; {@link #expand(Path)} is
 * layered over it, and both the build's own scan (through the mojo-side decode) and the freshness
 * replay run over that one layering, so both sides of a currency comparison walk with one dialect.
 *
 * @param buildFile the build file the recipe was resolved from (the module's pom), absolute and
 *                  normalized; {@code null} on a programmatic run with no build file. Its content
 *                  hash is the recipe's trust anchor: a remembered recipe is replayed only while
 *                  the build file still hashes to what capture recorded. The recipe's trust anchor
 *                  rather than an input to the walk, which is why its nullability says "no build
 *                  file" and is not a default door of the kind {@link SchemaSource} refuses
 * @param bindings  the resolved recipe entries, in configuration order
 * @param extensions the effective schema-file-extension filter (leading dot included), one
 *                  per-run set beside the bindings rather than under them. Applies to pattern
 *                  entries only: a literal entry bypasses it, exactly as a programmatic input list
 *                  does today
 */
public record SchemaRecipe(Path buildFile, List<Binding> bindings, List<String> extensions) {

    /**
     * One resolved recipe entry. The tag and description note are not optional
     * fidelity: their appliers run above the capture cut, so replaying a graph's SDL capture
     * without them would mint different rows than the graph's own build.
     */
    public record Binding(Entry entry, Optional<String> tag, Optional<String> descriptionNote) {
        public Binding {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(descriptionNote, "descriptionNote");
        }

        /** An unattributed glob-pattern entry. */
        public static Binding pattern(String glob) {
            return new Binding(new Entry.Pattern(glob), Optional.empty(), Optional.empty());
        }

        /** An unattributed literal entry over the source a programmatic caller handed over. */
        public static Binding literal(SchemaSource source) {
            return new Binding(new Entry.Literal(source), Optional.empty(), Optional.empty());
        }
    }

    /**
     * What a {@link Binding} names: a glob a build resolved, or a literal source a programmatic
     * caller handed over. The distinction is the recipe row's {@code kind} axis, and it stays
     * distinct from {@link SchemaSource}'s arms even though the literal arm carries one: a pattern
     * entry is one row that expands to many file arms, so neither axis is the other's
     * transcription.
     */
    public sealed interface Entry {

        /** A glob in this class's one dialect, expanded and extension-filtered at every replay. */
        record Pattern(String glob) implements Entry {
            public Pattern {
                Objects.requireNonNull(glob, "glob");
            }
        }

        /**
         * A source stated outright. A file literal re-expands by identity plus an existence check,
         * so a file that stopped resolving is a lost match exactly as a pattern whose file set
         * shrank is; a named literal re-expands to itself and is excluded from the currency
         * verdict, its row recording that it was an input all the same.
         */
        record Literal(SchemaSource source) implements Entry {
            public Literal {
                Objects.requireNonNull(source, "source");
            }
        }
    }

    /**
     * What re-expanding a whole recipe produced. Sealed rather than a bag plus an exception,
     * because the expansion has two consumers that cannot share a rendering: the build mojo, which
     * turns a failure into author-facing prose, and the freshness replay, which has to
     * <em>decide</em> on it. A message composed at the detection site serves the first and forces
     * the second to parse prose written for a human.
     */
    public sealed interface Expansion {

        /**
         * One expanded source, tagged with the entry that produced it so a reader can say which
         * binding matched what.
         */
        record Match(int entryIndex, SchemaInput input) {
            public Match {
                Objects.requireNonNull(input, "input");
            }
        }

        /**
         * A pattern entry that matched nothing. Tolerated rather than fatal while some other entry
         * produced content: the build renders these as warnings, and a currency reader counts them
         * as lost matches.
         */
        record EmptyPattern(int entryIndex, String pattern) {
            public EmptyPattern {
                Objects.requireNonNull(pattern, "pattern");
            }
        }

        /**
         * The expansion produced at least one source. {@code matches} is the full ordered list,
         * every entry's contribution in configuration order, and it is what the round-trip anchor
         * compares against a run's own input list.
         */
        record Resolved(List<Match> matches, List<EmptyPattern> emptyPatterns) implements Expansion {
            public Resolved {
                matches = List.copyOf(matches);
                emptyPatterns = List.copyOf(emptyPatterns);
            }

            /**
             * The subset a currency verdict may range over: pattern matches and file literals,
             * never named ones. A label re-expands to itself, so counting it would make every
             * programmatic graph trivially current in the part of its input set nothing on disk
             * corresponds to.
             *
             * <p>This is the one place the arm switch happens, and a driver that decides currency
             * consumes it rather than re-deriving the rule. It is a second view over
             * {@link #matches()} rather than a filter inside the expansion on purpose: an
             * expansion short by its named entries could not reproduce a programmatic run's own
             * input list, which is exactly what the round-trip anchor asserts.
             */
            public List<Match> currencyRelevantMatches() {
                var relevant = new ArrayList<Match>(matches.size());
                for (Match match : matches) {
                    switch (match.input().source()) {
                        case SchemaSource.File ignored -> relevant.add(match);
                        case SchemaSource.Named ignored -> { }
                    }
                }
                return List.copyOf(relevant);
            }
        }

        /**
         * Every entry matched nothing, so the expansion produced no sources at all. A build failure
         * on the build path and a verdict on the replay path, from one typed fact.
         */
        record NoMatches(List<EmptyPattern> emptyPatterns) implements Expansion {
            public NoMatches {
                emptyPatterns = List.copyOf(emptyPatterns);
            }
        }

        /**
         * The walk itself blew up on one entry. Typed here rather than propagated, because this is
         * the only layer that knows which entry it was: the per-entry primitive propagates the
         * scanner's own exception, and a caller that iterated the entries itself would be the only
         * other party able to attribute it.
         */
        record ScannerTrouble(int entryIndex, String pattern, RuntimeException cause)
            implements Expansion {
            public ScannerTrouble {
                Objects.requireNonNull(pattern, "pattern");
                Objects.requireNonNull(cause, "cause");
            }
        }
    }

    public SchemaRecipe {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(extensions, "extensions");
        if (buildFile != null) {
            buildFile = buildFile.toAbsolutePath().normalize();
        }
        bindings = List.copyOf(bindings);
        extensions = List.copyOf(extensions);
    }

    /**
     * A literal recipe over {@code inputs}: one entry per input, in order, carrying its attribution.
     * What a caller with an input list rather than a pattern configuration transcribes, so its graph
     * is as replayable as a build's. A recipe derived from a list cannot disagree with the list it
     * was derived from, which is why this is a derivation and not a second thing to keep in step.
     */
    public static SchemaRecipe literalOver(List<SchemaInput> inputs, Collection<String> extensions) {
        Objects.requireNonNull(inputs, "inputs");
        var bindings = new ArrayList<Binding>(inputs.size());
        for (SchemaInput input : inputs) {
            bindings.add(new Binding(new Entry.Literal(input.source()),
                input.tag(), input.descriptionNote()));
        }
        return new SchemaRecipe(null, bindings, List.copyOf(extensions));
    }

    /**
     * Expands one include pattern under {@code baseDir} and filters to the schema-file
     * extensions, returning base-relative paths in the scanner's order. The dialect, in one
     * place: plexus {@link DirectoryScanner} includes, matched against the file-name component
     * with {@link String#endsWith}, case-sensitively. Scanner trouble propagates as the
     * scanner's own {@link RuntimeException}; typing it as
     * {@link Expansion.ScannerTrouble} is {@link #expand(Path)}'s job, being the layer that knows
     * which entry was being walked.
     */
    public static List<String> expand(Path baseDir, String pattern, Collection<String> extensions) {
        var scanner = new DirectoryScanner();
        scanner.setBasedir(baseDir.toFile());
        scanner.setIncludes(new String[]{pattern});
        scanner.scan();
        var matches = new ArrayList<String>();
        for (String rel : scanner.getIncludedFiles()) {
            if (matchesExtension(fileNameOf(rel), extensions)) {
                matches.add(rel);
            }
        }
        return matches;
    }

    /**
     * Re-expands the whole recipe over {@code baseDir}, in configuration order, carrying each
     * entry's attribution onto every source it produced. This is the one expansion the build's own
     * scan and a currency check both run; a currency check reads
     * {@link Expansion.Resolved#currencyRelevantMatches()} rather than the full list.
     *
     * <p>A file literal is re-expanded by identity plus an existence check, so a file that no
     * longer resolves drops out and is observable as a lost match; a named literal re-expands to
     * itself unconditionally. Neither literal kind is extension-filtered.
     */
    public Expansion expand(Path baseDir) {
        var matches = new ArrayList<Expansion.Match>();
        var emptyPatterns = new ArrayList<Expansion.EmptyPattern>();
        for (int i = 0; i < bindings.size(); i++) {
            Binding binding = bindings.get(i);
            switch (binding.entry()) {
                case Entry.Pattern pattern -> {
                    List<String> relative;
                    try {
                        relative = expand(baseDir, pattern.glob(), extensions);
                    } catch (RuntimeException e) {
                        return new Expansion.ScannerTrouble(i, pattern.glob(), e);
                    }
                    if (relative.isEmpty()) {
                        emptyPatterns.add(new Expansion.EmptyPattern(i, pattern.glob()));
                        continue;
                    }
                    for (String rel : relative) {
                        matches.add(new Expansion.Match(i, new SchemaInput(
                            SchemaSource.file(baseDir.resolve(rel)),
                            binding.tag(), binding.descriptionNote())));
                    }
                }
                case Entry.Literal literal -> resolveLiteral(i, binding, literal, matches);
            }
        }
        if (matches.isEmpty()) {
            return new Expansion.NoMatches(emptyPatterns);
        }
        return new Expansion.Resolved(matches, emptyPatterns);
    }

    /**
     * A literal entry's contribution. The existence check on a file literal belongs to the
     * expansion rather than to a reader: a file that stopped resolving is a lost match, and a lost
     * match is the observation a reader asks the expansion for rather than one it can make for
     * itself.
     */
    private static void resolveLiteral(int index, Binding binding, Entry.Literal literal,
                                       List<Expansion.Match> matches) {
        switch (literal.source()) {
            case SchemaSource.File file -> {
                if (java.nio.file.Files.isRegularFile(file.path())) {
                    matches.add(new Expansion.Match(index,
                        new SchemaInput(file, binding.tag(), binding.descriptionNote())));
                }
            }
            case SchemaSource.Named named -> matches.add(new Expansion.Match(index,
                new SchemaInput(named, binding.tag(), binding.descriptionNote())));
        }
    }

    /**
     * Whether {@code filename} bears one of the accepted schema-file extensions. The predicate,
     * once: the expansion above strips the directory prefix at its own call site and the plugin's
     * orphan scan passes a bare file name, and the two agreeing by having been written to agree is
     * what this method retires. Takes the file name, the narrower contract of the two.
     */
    public static boolean matchesExtension(String filename, Collection<String> extensions) {
        for (String ext : extensions) {
            if (filename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** The scanner hands back base-relative paths, so the file name is taken here. */
    private static String fileNameOf(String relativePath) {
        int sep = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        return sep < 0 ? relativePath : relativePath.substring(sep + 1);
    }
}
