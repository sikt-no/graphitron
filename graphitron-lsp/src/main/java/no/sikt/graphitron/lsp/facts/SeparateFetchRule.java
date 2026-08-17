package no.sikt.graphitron.lsp.facts;

import java.util.Optional;

/**
 * The rendering side of {@code intent_field_separate_fetch}'s rule vocabulary: one constant per
 * rule, carrying what an editor says about it. The relation is the fact; this is the copy, which is
 * why it lives here rather than as prose in the DDL beside the rule's definition.
 *
 * <p>Both surfaces render through it, so the inlay marker and the hover line cannot come to
 * disagree about which fields cost a round trip. {@code SeparateFetchVocabularyTest} pins the
 * constant set against the literals the view's own definition emits, so a rule added in SQL fails
 * the build here rather than reaching a user as a raw {@code SCREAMING_SNAKE} token.
 *
 * <p>{@link #ROOT_OPERATION} is <em>universal</em>: it is true of every field of its parent, so an
 * inline marker carrying it would repeat itself down a whole root type and tell a reader nothing.
 * The inlay marker therefore stays silent at any coordinate a universal rule reaches, even where a
 * second rule also does; a {@code @splitQuery} on a root field is a marker the generator ignores,
 * and marking the coordinate would advertise a split that never happens. The hover still states
 * every rule, a reader who asked about one declaration having earned the complete answer.
 */
public enum SeparateFetchRule {

    SPLIT_QUERY("`@splitQuery` defers the fetch to a batched DataLoader call", false),
    TENANT_FAN_OUT("`@tenantFanOut` runs the fetch once per tenant, off the parent's statement", false),
    SERVICE("the service fetches independently of the parent's SELECT", false),
    ROOT_OPERATION("a root operation field is its own entry point", true),
    RECORD_HANDED_PARENT(
        "the parent hands back a Java object, so this field's table is a fetch of its own", false);

    private final String description;
    private final boolean universal;

    SeparateFetchRule(String description, boolean universal) {
        this.description = description;
        this.universal = universal;
    }

    /** What the hover says about a field this rule reaches. */
    public String description() {
        return description;
    }

    /** Whether the rule holds of every field of its parent, rather than singling this one out. */
    public boolean universal() {
        return universal;
    }

    /**
     * The constant for a stored rule literal, or empty for one this build does not know. Empty
     * cannot happen against the shipped DDL, and the readers still handle it, because a surface
     * that threw on an unrecognised row would take the whole popup down over one unknown word.
     */
    public static Optional<SeparateFetchRule> of(String rule) {
        for (var candidate : values()) {
            if (candidate.name().equals(rule)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Whether an inline marker at this coordinate would tell a reader something: at least one rule
     * reaches it, and none of them is universal. An unrecognised literal counts as a reason to mark,
     * since a rule this build has not heard of still says the fetch is the field's own.
     */
    public static boolean marksInline(Iterable<String> rules) {
        boolean any = false;
        for (String rule : rules) {
            if (of(rule).map(SeparateFetchRule::universal).orElse(false)) return false;
            any = true;
        }
        return any;
    }
}
