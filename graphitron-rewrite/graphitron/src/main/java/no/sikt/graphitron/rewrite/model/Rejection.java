package no.sikt.graphitron.rewrite.model;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Why a classifier failed to produce a model variant. The arms carry the structural
 * data each rejection class actually has; the validator and any downstream consumer
 * (LSP fix-its, watch-mode formatter) switch on the variant rather than parsing
 * prose. See plan {@code R58 lift-unclassified-field-onto-sealed-result}.
 *
 * <p>{@link #message()} renders the variant's data as a single human-readable
 * sentence, for the build-time log surface that
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} writes today. Each
 * leaf record overrides it; the interface itself only declares the contract.
 */
public sealed interface Rejection permits Rejection.AuthorError, Rejection.InvalidSchema, Rejection.Deferred {

    String message();

    /**
     * Author-correctable. Two sub-arms because the data shapes diverge: most
     * AUTHOR_ERROR sites today are structural rule violations carrying just prose;
     * a minority resolve a name against a closed set and carry the lookup attempt
     * + candidates. Mirrors R1's split of {@code BatchKey} into {@code ParentKeyed}
     * / {@code RecordParentBatchKey}: each sub-arm's accessors apply uniformly to
     * that arm.
     */
    sealed interface AuthorError extends Rejection permits AuthorError.UnknownName, AuthorError.Structural {

        /**
         * The classifier resolved a name (column, table, FK, service method,
         * NodeId key, lifter method, ...) that the catalog or SDL registry did not
         * recognise. {@code summary} is the prose preceding the candidate hint;
         * {@code attempt} is what the author wrote; {@code candidates} is the
         * closed set the catalog had at this site; {@code attemptKind} names the
         * lookup space for downstream tooling (LSP fix-its).
         */
        record UnknownName(
            String summary,
            AttemptKind attemptKind,
            String attempt,
            List<String> candidates
        ) implements AuthorError {
            public UnknownName {
                candidates = List.copyOf(candidates);
            }

            @Override public String message() {
                return summary + candidateHint(attempt, candidates);
            }
        }

        /**
         * Author-correctable structural rule violations that don't resolve a name
         * against a closed set. The majority arm.
         */
        record Structural(String reason) implements AuthorError {
            @Override public String message() { return reason; }
        }
    }

    /**
     * Structural rule violations the author can't fix without dropping or
     * replacing a directive: "this combination cannot work, period". Two sub-arms
     * by the same logic as {@link AuthorError}: a minority of sites are
     * directive-conflict ("@X conflicts with @Y", "@asConnection on inline
     * (non-@splitQuery) TableField"), the majority are structural rules without a
     * clean directive enumeration ("lookup fields must not return a connection",
     * "result type does not match input cardinality").
     */
    sealed interface InvalidSchema extends Rejection permits InvalidSchema.DirectiveConflict, InvalidSchema.Structural {

        /**
         * Two or more directives co-occur on the same declaration in a combination
         * the classifier rejects, or one directive is rejected because of where it
         * appears. {@code directives} carries the bare directive names (no leading
         * {@code @}) for downstream tooling; {@code reason} is the prose that
         * surfaces on the validator log line.
         */
        record DirectiveConflict(
            List<String> directives,
            String reason
        ) implements InvalidSchema {
            public DirectiveConflict {
                directives = List.copyOf(directives);
            }

            @Override public String message() { return reason; }
        }

        /** Structural-rule majority: just prose. */
        record Structural(String reason) implements InvalidSchema {
            @Override public String message() { return reason; }
        }
    }

    /**
     * Recognised but not yet generator-supported. {@code summary} is the prose
     * preceding the optional roadmap-path suffix; {@code planSlug} names the
     * roadmap file under {@code graphitron-rewrite/roadmap/} (no extension), or is
     * empty when the deferred message has no associated plan; {@code stubKey}
     * names the variant class or other anchor inside that plan. The render form
     * embeds the plan path verbatim, separator-prefixed by an em-dash; LSP
     * fix-its read the slug as a typed value and offer "open the roadmap item"
     * instead of parsing the path back out.
     */
    record Deferred(
        String summary,
        String planSlug,
        StubKey stubKey
    ) implements Rejection {
        @Override public String message() {
            return planSlug.isEmpty()
                ? summary
                : summary + " — see graphitron-rewrite/roadmap/" + planSlug + ".md";
        }
    }

    /**
     * Lookup-space identifier for {@link AuthorError.UnknownName}. Every space
     * carries the same {@code (attempt, candidates)} shape; the kind is a typed
     * tag for downstream tooling rather than an arm split.
     */
    enum AttemptKind {
        COLUMN, TABLE, FOREIGN_KEY, SERVICE_METHOD, TABLE_METHOD, LIFTER_METHOD,
        ENUM_CONSTANT, TYPE_NAME, NODEID_KEY_COLUMN, ARGUMENT_NAME, FIELD_NAME,
        DML_KIND
    }

    /**
     * Stable identifier for a deferred-stub site. Two arms: a variant-class key
     * (the {@code TypeFetcherGenerator.STUBBED_VARIANTS} key form, used
     * when the generator stubs an entire variant class) and an emit-block key (a
     * typed enum, used when a variant classifies cleanly but a particular shape
     * inside the emitter doesn't yet emit). R58 Phase C wires both forms into the
     * validator's deferred gate via the shared {@link Deferred#message()} renderer.
     */
    sealed interface StubKey permits StubKey.VariantClass, StubKey.EmitBlock, StubKey.None {
        record VariantClass(Class<? extends GraphitronField> fieldClass) implements StubKey {}
        record EmitBlock(EmitBlockReason reason) implements StubKey {}
        /**
         * Inline-deferred site with no variant-class or emit-block anchor: the rejection
         * names a feature shape ("@service returning a polymorphic type") rather than a
         * stubbed leaf class. Validator paths that switch on {@link StubKey} treat
         * {@link None} the same as {@link VariantClass} for log output; the typed
         * distinction matters only to LSP-fix-its / watch-mode tooling that wants to
         * jump to the roadmap entry.
         */
        record None() implements StubKey {
            public static final None INSTANCE = new None();
        }
    }

    /**
     * Closed set of intra-emitter "this shape can't emit yet" reasons. One value
     * per {@code SplitRowsMethodEmitter.unsupportedReason} arm today; a new value
     * lands when a new emit-block predicate is introduced. R58 Phase C wires these
     * through the validator's deferred-gate path: each {@code unsupportedReason}
     * arm builds a {@link Deferred} keyed by an {@link EmitBlock} of the matching
     * value, and the validator projects it through the shared {@code Deferred.message()}
     * renderer.
     */
    enum EmitBlockReason {
        SPLIT_TABLE_FIELD_CONDITION_JOIN_STEP,
        SPLIT_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP,
        RECORD_TABLE_FIELD_CONDITION_JOIN_STEP,
        RECORD_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP
    }

    // ===== Factories =====

    /** {@link AuthorError.Structural} factory; the majority shape. */
    static Rejection structural(String reason) {
        return new AuthorError.Structural(reason);
    }

    /** {@link InvalidSchema.Structural} factory; the majority shape. */
    static Rejection invalidSchema(String reason) {
        return new InvalidSchema.Structural(reason);
    }

    /**
     * {@link InvalidSchema.DirectiveConflict} factory carrying the conflicting
     * directive names; {@code directives} are bare names (no leading {@code @}).
     */
    static Rejection directiveConflict(List<String> directives, String reason) {
        return new InvalidSchema.DirectiveConflict(directives, reason);
    }

    /**
     * {@link Deferred} factory keyed on a stubbed variant class, with no roadmap
     * slug attached. Use when the deferred message is self-contained prose.
     */
    static Rejection deferred(String summary, Class<? extends GraphitronField> fieldClass) {
        return new Deferred(summary, "", new StubKey.VariantClass(fieldClass));
    }

    /**
     * {@link Deferred} factory for inline-deferred sites with no specific variant-class
     * anchor: the prose names a feature shape rather than a stubbed leaf class. Use when
     * neither a variant class nor an emit-block reason fits.
     */
    static Rejection deferred(String summary) {
        return new Deferred(summary, "", StubKey.None.INSTANCE);
    }

    /**
     * {@link Deferred} factory for inline-deferred sites with a roadmap slug but no
     * specific variant-class anchor.
     */
    static Rejection deferredAt(String summary, String planSlug) {
        return new Deferred(summary, planSlug, StubKey.None.INSTANCE);
    }

    /**
     * {@link Deferred} factory keyed on a stubbed variant class with a roadmap
     * slug. {@code planSlug} is the file basename under
     * {@code graphitron-rewrite/roadmap/}, no extension.
     */
    static Rejection deferred(String summary, String planSlug, Class<? extends GraphitronField> fieldClass) {
        return new Deferred(summary, planSlug, new StubKey.VariantClass(fieldClass));
    }

    /** {@link AuthorError.UnknownName} factory for column-name lookups. */
    static Rejection unknownColumn(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.COLUMN, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for table-name lookups. */
    static Rejection unknownTable(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.TABLE, attempt, candidates);
    }

    /**
     * Produces the {@code "; did you mean: X, Y, Z"} suffix used by
     * {@link AuthorError.UnknownName#message()}. Mirrors {@code BuildContext.candidateHint}; kept
     * here so the rejection model is self-contained and consumers outside the rewrite package can
     * render rejection messages without pulling in the package-private {@code BuildContext}.
     */
    private static String candidateHint(String attempt, List<String> candidates) {
        if (candidates.isEmpty()) return "";
        String lc = attempt.toLowerCase();
        return "; did you mean: " + candidates.stream()
            .sorted(Comparator.comparingInt(c -> levenshteinDistance(lc, c.toLowerCase())))
            .limit(5)
            .collect(Collectors.joining(", "));
    }

    private static int levenshteinDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1))
                    curr[j] = prev[j - 1];
                else
                    curr[j] = 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    /** {@link AuthorError.UnknownName} factory for foreign-key-name lookups. */
    static Rejection unknownForeignKey(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.FOREIGN_KEY, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for service-method lookups. */
    static Rejection unknownServiceMethod(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.SERVICE_METHOD, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for lifter-method lookups. */
    static Rejection unknownLifterMethod(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.LIFTER_METHOD, attempt, candidates);
    }
}
