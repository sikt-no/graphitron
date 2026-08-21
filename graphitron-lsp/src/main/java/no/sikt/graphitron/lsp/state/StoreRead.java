package no.sikt.graphitron.lsp.state;

/**
 * The closed vocabulary of names for the language server's store reads, one constant per
 * store-reading surface. {@link StoreAccess}'s doors take one alongside their other arguments, and
 * the out-of-budget warning speaks it, so a developer and a maintainer can say the same words about
 * the same read: the developer reads the phrase off the console, and the maintainer greps for it or
 * for the constant.
 *
 * <p>An enum rather than a free-text string because the vocabulary is the point. Declared in one
 * place, a reader sees the whole set, a new surface must add a constant rather than invent a
 * spelling, and a test asserts against a constant instead of a sentence. It also cannot drift from
 * the surface it names: a surface that is renamed or removed leaves an unused constant behind, not a
 * lying log line.
 *
 * <p>Deliberately independent of which {@link StoreAccess} door a read goes through. Each constant
 * happens to belong to exactly one door today, but the door is about latency contracts (which reader
 * answers) while the constant is about what the developer is told, and folding the two would make a
 * new interactive caller of the bulk door a change to this vocabulary.
 */
public enum StoreRead {

    HOVER("the hover read"),
    DEFINITION("the go-to-definition read"),
    COMPLETION("the completion read"),
    INLAY_HINTS("the inlay-hint read"),
    CODE_ACTIONS("the code-action quick-fix read"),
    DIAGNOSTICS("the workspace diagnostics drain"),
    DIRECTIVE_VOCABULARY("the directive-vocabulary read");

    private final String phrase;

    StoreRead(String phrase) {
        this.phrase = phrase;
    }

    /** How this read is named in a log line, as the subject of a sentence. */
    public String phrase() {
        return phrase;
    }
}
