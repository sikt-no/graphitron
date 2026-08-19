package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.LspVocabulary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * The directive vocabulary graphitron ships, read back out of a store that captured it.
 *
 * <p>For the tests whose subject is something else. A completion or diagnostics case needs a
 * vocabulary in order to have a coordinate at all, but what it is asserting is what happens at that
 * coordinate, and standing up a store per test class for the definitions every graph captures
 * identically would be scaffolding rather than fixture.
 *
 * <p>The definitions come from real capture, not from a hand-built surface: capture parses
 * graphitron's bundled {@code directives.graphqls} alongside whatever schema it is given, so an empty
 * placeholder schema is enough to make the whole shipped vocabulary rows.
 *
 * <p><b>Only for a fixture that declares no directives of its own.</b> A vocabulary is one graph's,
 * and a surface handed this one alongside a handle on some other graph is being told the shipped
 * definitions are all there are. That is true of most fixtures and false of any that declares a
 * directive an author wrote, so those read their vocabulary off their own store with
 * {@link StoreFixture#vocabulary()}. Production cannot get this wrong, both halves coming from the
 * one workspace.
 *
 * <p>Captured once per JVM and never closed, as the classpath census beside it is: the answer does
 * not change between tests, and the store is a few kilobytes of in-memory H2.
 */
public final class BundledVocabulary {

    /** A schema with nothing in it, so what capture writes is the bundled definitions alone. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    /** Held so the store it owns stays open for the vocabulary's lifetime, which is the JVM's. */
    private static StoreFixture fixture;

    private static LspVocabulary vocabulary;

    private BundledVocabulary() {}

    /** The shipped vocabulary, capturing it on first use. */
    public static synchronized LspVocabulary get() {
        if (vocabulary == null) {
            fixture = StoreFixture.of(temporaryDirectory(), PLACEHOLDER_SDL);
            vocabulary = fixture.vocabulary();
        }
        return vocabulary;
    }

    private static java.nio.file.Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("bundled-vocabulary");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
