package no.sikt.graphitron.model.schema.input;

import com.apollographql.federation.graphqljava.exceptions.UnsupportedFederationVersionException;
import com.apollographql.federation.graphqljava.exceptions.UnsupportedLinkImportException;
import com.apollographql.federation.graphqljava.exceptions.UnsupportedRenameException;
import graphql.language.SourceLocation;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The loading rewrites, composed once: the in-place edits that stand between the documents an
 * author wrote and the registry the rest of a run sees.
 *
 * <p>Four of them, in this order. {@link TagLinkSynthesiser} adds an {@code @link} importing
 * {@code @tag} where a tag is configured and the author wrote none, {@link FederationLinkApplier}
 * adds the definitions the federation {@code @link} imports, {@link TagApplier} applies the
 * configured tags and {@link DescriptionNoteApplier} appends the configured notes. The order is a
 * dependency: the federation library reads the {@code @link} the first may have written, and the
 * last two apply to declarations the second may have added.
 *
 * <p>Two callers, which is the reason this is one function: the generator's load and the store's
 * assembly. Composed twice, the two would assemble different schemas from one corpus, and a
 * directive the generator accepts could be reported undeclared by the store with nothing comparing
 * the two readings. Synthesis is not here. It is graphitron's own macro expansion rather than a
 * reading of the author's schema, and it stays with the generator.
 *
 * <p>The registry handed in belongs to the call. The rewrites run in place and the federation
 * injection adds definitions one at a time, so a refusal can leave it half rewritten; a caller that
 * wants the registry as written reads one of its own rather than this one.
 */
public final class LoadingRewrites {

    private LoadingRewrites() {}

    /** What the composition made of a registry: rewritten, or refused partway. */
    public sealed interface Outcome {

        /**
         * Every rewrite ran.
         *
         * @param registry      the registry handed in, rewritten
         * @param injectedNames the names of the definitions the federation {@code @link} injected,
         *                      in the order it injected them, empty where there is no federation
         *                      {@code @link}
         */
        record Applied(TypeDefinitionRegistry registry, Set<String> injectedNames) implements Outcome {
            public Applied {
                Objects.requireNonNull(registry, "registry");
                injectedNames = Collections.unmodifiableSet(new LinkedHashSet<>(injectedNames));
            }
        }

        /** A rewrite refused, and the registry handed in is no longer anything a caller should read. */
        record Refused(Refusal refusal) implements Outcome {
            public Refused {
                Objects.requireNonNull(refusal, "refusal");
            }
        }
    }

    /**
     * One way the composition refuses, typed so a caller that records it and a caller that fails on
     * it read the same value.
     *
     * <p>Each carries the exception the rewrite has always thrown, so a build failing on it fails
     * with exactly what it failed with before the rewrites were composed here, and the message that
     * exception was built with, which is graphitron's own fix-it prose rather than a toolchain's
     * sentence.
     */
    public sealed interface Refusal {

        /** What to tell the author, the sentence a build failing on this has always printed. */
        String message();

        /** Where the refusal points, or {@code null} where it points at no position. */
        SourceLocation location();

        /** The exception a build fails with. */
        RuntimeException exception();

        /** The file the refusal is about, where it is about one. */
        default String sourceName() {
            var at = location();
            return at == null ? null : at.getSourceName();
        }

        /** The variant's own name, which is what a reader groups refusals by. */
        default String name() {
            return getClass().getSimpleName();
        }

        /**
         * One source claimed by two inputs, so which tag and note apply to it has two answers. Two
         * recipe patterns can match one file, and the read keeps one document per file, so this is
         * stated over the inputs rather than over any document.
         */
        record SourceInTwoInputs(String sourceName, SchemaInputException exception) implements Refusal {
            @Override public String message() { return exception.getMessage(); }
            @Override public SourceLocation location() { return null; }
        }

        /** More than one federation {@code @link}; located at the second, the first standing. */
        record MultipleFederationLinks(String message, SourceLocation location,
                                       IllegalStateException exception) implements Refusal {}

        /** A federation {@code @link} naming a spec version the federation library does not know. */
        record UnsupportedFederationVersion(SourceLocation location,
                                            UnsupportedFederationVersionException exception)
            implements Refusal {
            @Override public String message() { return exception.getMessage(); }
        }

        /**
         * A federation {@code @link} import the library refuses: a malformed entry, or a directive
         * the linked spec version does not have yet.
         */
        record UnsupportedLinkImport(SourceLocation location, UnsupportedLinkImportException exception)
            implements Refusal {
            @Override public String message() { return exception.getMessage(); }
        }

        /** A federation {@code @link} import renaming something the library will not rename. */
        record UnsupportedRename(SourceLocation location, UnsupportedRenameException exception)
            implements Refusal {
            @Override public String message() { return exception.getMessage(); }
        }

        /**
         * The author declared a definition the federation {@code @link} imports; located at the
         * author's declaration, which is the one to remove.
         */
        record DeclarationCollision(String message, SourceLocation location,
                                    IllegalStateException exception) implements Refusal {}

        /**
         * The federation library returned one definition twice, which its v2.6 and v2.7 spec
         * documents do; located at the {@code @link}, whose version is the thing to change.
         */
        record LibraryDuplicateDeclaration(String message, SourceLocation location,
                                           IllegalStateException exception) implements Refusal {}

        /** A tag is configured and the author's federation {@code @link} does not import {@code @tag}. */
        record TagNotImported(String message, SourceLocation location,
                              ValidationFailedException exception) implements Refusal {}

        /**
         * The registry refused the {@code @link} extension synthesised for a configured tag. It
         * re-checks the schema's operation types on every schema extension it admits, so a corpus
         * whose own extensions already redefine one refuses the synthesised extension for it.
         */
        record SynthesisedLinkRefused(String message, IllegalStateException exception)
            implements Refusal {
            @Override public SourceLocation location() { return null; }
        }
    }

    /**
     * Runs the four rewrites over {@code registry}, attributing its sources by {@code inputs}.
     *
     * <p>{@code inputs} is the run's whole input list in recipe order, the shape a
     * {@link no.sikt.graphitron.model.config.RunContext} holds, rather than one input per document:
     * the attribution's one refusal is a source two inputs claim, which no per-document input could
     * state. A document no input names, the bundled directive vocabulary being one, carries no tag
     * and no note.
     *
     * <p>Never throws on a refusal; the caller decides whether a refusal fails anything.
     */
    public static Outcome apply(TypeDefinitionRegistry registry, List<SchemaInput> inputs) {
        Map<String, SchemaInput> bySource;
        try {
            bySource = SchemaInputAttribution.build(inputs);
        } catch (SchemaInputException e) {
            return new Outcome.Refused(new Refusal.SourceInTwoInputs(firstClaimedTwice(inputs), e));
        }
        var tagLink = TagLinkSynthesiser.synthesise(registry, bySource);
        if (tagLink.isPresent()) {
            return new Outcome.Refused(tagLink.get());
        }
        var injected = FederationLinkApplier.inject(registry);
        if (injected instanceof Outcome.Refused refused) {
            return refused;
        }
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);
        return injected;
    }

    private static String firstClaimedTwice(List<SchemaInput> inputs) {
        var seen = new HashSet<String>();
        for (var input : inputs) {
            if (!seen.add(input.sourceName())) {
                return input.sourceName();
            }
        }
        return null;
    }
}
