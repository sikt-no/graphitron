package no.sikt.graphitron.model.grammar;

/**
 * The sigil literals an author may still write in place of a member name in {@code @field(name:)}.
 *
 * <p>{@code $errors} is the one left: inert, kept parseable on an errors field so a schema carrying
 * it still builds, and the generator warns that it can be removed. Recognising it is the same kind of
 * question as the splits beside it here: what did the written value say.
 */
public final class FieldSourceSigilGrammar {

    /**
     * On an errors-shaped field, an inert sigil: the field's transport follows from its parent, so
     * the generator warns that it can be removed.
     */
    public static final String ERRORS = "$errors";

    private FieldSourceSigilGrammar() {
    }
}
