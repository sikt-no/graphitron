package no.sikt.graphitron.roadmap;

import java.util.Comparator;
import java.util.List;

/**
 * The single tier vocabulary the coverage reports share, mirroring the {@code @UnitTier} /
 * {@code @PipelineTier} / {@code @CompilationTier} / {@code @ExecutionTier} annotations in
 * {@code graphitron}'s test sources. {@link LeafCoverageReport} aggregates "highest tier
 * observed" over the four-arm ordering; {@link SourceCoverageReport} orders its per-tier
 * coverage columns by the same list. Hoisted here so the two reports cannot disagree about
 * the tier set once their tables land on one page.
 */
final class TierVocabulary {

    /** Four-arm ordering: {@code unit < pipeline < compilation < execution}. */
    static final List<String> TIER_ORDER = List.of("unit", "pipeline", "compilation", "execution");

    /**
     * Deliberately off the ordering: cross-cutting marks tests that exercise many arms at once
     * and is reported as a separate flag, never as a rank.
     */
    static final String CROSS_CUTTING = "cross-cutting";

    private TierVocabulary() {}

    /**
     * SQL expression computing the highest tier name observed over {@code column}, per
     * {@link #TIER_ORDER}. Values off the ordering (including {@link #CROSS_CUTTING}) rank
     * as NULL and never win the MAX.
     */
    static String highestTierSql(String column) {
        StringBuilder rank = new StringBuilder("CASE ").append(column);
        for (int i = 0; i < TIER_ORDER.size(); i++) {
            rank.append(" WHEN '").append(TIER_ORDER.get(i)).append("' THEN ").append(i + 1);
        }
        rank.append(" ELSE NULL END");
        StringBuilder out = new StringBuilder("CASE MAX(").append(rank).append(")");
        for (int i = 0; i < TIER_ORDER.size(); i++) {
            out.append(" WHEN ").append(i + 1).append(" THEN '").append(TIER_ORDER.get(i)).append("'");
        }
        out.append(" ELSE NULL END");
        return out.toString();
    }

    /** Orders tier names by {@link #TIER_ORDER}; names off the ordering sort last, alphabetically. */
    static Comparator<String> tierOrder() {
        return Comparator.<String>comparingInt(t -> {
            int i = TIER_ORDER.indexOf(t);
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(Comparator.naturalOrder());
    }
}
