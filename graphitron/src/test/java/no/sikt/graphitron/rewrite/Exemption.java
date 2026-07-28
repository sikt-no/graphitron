package no.sikt.graphitron.rewrite;

/**
 * A categorised coverage exemption: one entry in {@link VariantCoverageTest#NO_CASE_REQUIRED} or
 * {@code ClassifiedDslTest.OPERATION_KNOWN_GAPS}. The category is data rather than prose so the
 * grain worklist is a filter over the live lists instead of a hand census that drifts: a new
 * exemption must pick a category, and the consumers of one category read the lists directly.
 *
 * @param category why the corpus cannot demonstrate the entry
 * @param reason   the specific story, naming the covering test where one exists
 */
public record Exemption(Category category, String reason) {

    public Exemption {
        if (category == null) throw new IllegalArgumentException("category is the point; pick one");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("an exemption carries its reason");
    }

    /**
     * The triage taxonomy. Three categories were predicted by the grain-worklist framing
     * (unimplemented behaviour, synthesised with no SDL origin, riding another row's key); the
     * fourth turned out to exist when the lists were read as a set: entries whose behaviour is
     * real and demonstrated, just not reachable by the corpus's fixture catalog or coverage
     * walker.
     */
    public enum Category {
        /**
         * The model declares the arm but no schema-reachable path produces it yet: the
         * classifier rejects upstream or does not mint it. Leaves this list when the behaviour
         * lands.
         */
        UNIMPLEMENTED_BEHAVIOUR,
        /**
         * Synthesised with no SDL declaration to carry a corpus annotation. This is the
         * connection-promotion residue: the entries are command outputs stored in the fact
         * model, and they leave the list when connection synthesis becomes a relation.
         */
        SYNTHESISED_NO_SDL_ORIGIN,
        /**
         * Exists at a key the coverage walk cannot see: it rides another row's list instead of
         * its own coordinate. This is the direct grain-repair worklist; the exemption retires
         * when the entry gets its own key.
         */
        RIDES_ANOTHER_ROWS_KEY,
        /**
         * Demonstrated, but outside the corpus's reach: the fixture catalog lacks the shape
         * (composite-PK node types, synthesised node-id metadata, plain jOOQ records) or the
         * demonstration lives in a test shape the coverage walker does not read. Leaves the
         * list when a suitable fixture or walker extension lands.
         */
        FIXTURE_GAP
    }
}
