package no.sikt.graphitron.rewrite.dependency;

import no.sikt.graphitron.rewrite.BuildWarning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The generation-time nudge toward current graphql-java and jOOQ, derived purely from
 * {@link DependencyVersions}. Kept a pure decision (no schema, no {@code RewriteContext}) so it is
 * unit-testable directly; {@code GraphQLRewriteGenerator.withLintFindings} calls it with the carrier
 * and folds the result into the build-warning channel, where lint suppression (by rule id) applies
 * uniformly.
 *
 * <p>This is a usability nudge, not a compatibility gate. The compiler is the correctness bar: if
 * the generated code compiles against the consumer's versions, the consumer is fine. What the
 * advisory addresses is silent drift, a subgraph sitting on an old line because nothing in the build
 * ever mentioned it. Nothing here can fail a build, and there is no separately maintained minimum
 * supported version; the reference is simply whatever graphitron itself was built against, so it
 * moves for free when graphitron upgrades.
 *
 * <p>Every advisory is a {@link BuildWarning.LintFinding} (rule-tagged, hence suppressible by id via
 * {@code <lint><disabledRules>}) with a {@code null} location: a resolved dependency version is a
 * {@code pom.xml} / whole-build fact with no SDL coordinate, the same shape
 * {@link no.sikt.graphitron.rewrite.session.SessionStateWarnings} already emits.
 *
 * <p><b>The predicate is the minor line, not the patch.</b> Because the reference is "current", an
 * advisory keyed on any lag at all would fire for nearly everyone on nearly every build, and a line
 * that fires forever gets filtered out and discredits the other warnings sharing the channel. A
 * consumer one patch behind is materially current; a minor line behind is the drift worth a nudge.
 * Major-line lag falls out of the same {@code (major, minor)} comparison with no special-casing, and
 * comparing the pair as integers is also what gets jOOQ's {@code 3.9} versus {@code 3.20} right,
 * where a lexical compare of the full strings gets it backwards.
 *
 * <p><b>The advisory names the coordinate the consumer resolved</b>, never a canonical one. jOOQ's
 * editions put one library at several group ids, and telling a commercial consumer to bump the
 * open-source coordinate is telling them to switch editions, which for a commercial-only dialect does
 * not work at all. When a library was resolved at more than one coordinate, {@link #lowestLine}
 * settles which one the advisory speaks about.
 */
public final class DependencyVersionWarnings {

    private DependencyVersionWarnings() {}

    /**
     * One advisory per watched dependency whose observed minor line orders below the reference one,
     * in {@link WatchedDependency} declaration order.
     *
     * <p>Silence is the interesting output here, and every case collapses to it: at the reference
     * version, behind only at patch level, <em>ahead</em> of the reference (a consumer who upgraded
     * before graphitron did), the dependency absent from either side, or a version string that does
     * not decompose into a {@code (major, minor)} pair.
     */
    public static List<BuildWarning> forVersions(DependencyVersions versions) {
        var warnings = new ArrayList<BuildWarning>();
        for (WatchedDependency dep : WatchedDependency.values()) {
            advisoryFor(dep, versions.observedFor(dep), versions.reference().get(dep))
                .ifPresent(warnings::add);
        }
        return List.copyOf(warnings);
    }

    /** The advisory for one dependency, or empty for any of the silence cases above. */
    static Optional<BuildWarning> advisoryFor(
        WatchedDependency dep, List<ObservedVersion> observed, String reference
    ) {
        MinorLine referenceLine = minorLine(reference);
        ObservedVersion lagging = lowestLine(observed);
        if (referenceLine == null || lagging == null) {
            return Optional.empty();
        }
        if (!minorLine(lagging.version()).isBehind(referenceLine)) {
            return Optional.empty();
        }
        return Optional.of(BuildWarning.LintFinding.of(
            "This build resolves " + lagging.coordinate() + " " + lagging.version() + ", a minor line "
                + "behind the " + reference + " graphitron is built against. Staying current keeps you "
                + "on upstream fixes and keeps the next upgrade small; bump " + lagging.coordinate()
                + " in your pom, or silence this rule (" + dep.rule().id() + ") to accept the lag. "
                + "Nothing is broken: the generated code compiles against your version, or the build "
                + "would have said so.",
            null, dep.rule()));
    }

    /**
     * The observation to advise on when a library was resolved at more than one coordinate, or
     * {@code null} when none of them carries a readable version.
     *
     * <p>The lowest line wins, because that is the one actually holding the consumer back, and it is
     * always a coordinate the consumer's build resolved rather than one graphitron picked out of the
     * air. A tie needs a defined answer or the message text moves between runs on an unchanged
     * project, so equal lines order on the coordinate string; Maven mediates per coordinate, so no two
     * observations share one and the order is total. Selecting here rather than at the Maven boundary
     * is what keeps release-line comparison on this side of that seam.
     *
     * <p>An observation whose version does not decompose is passed over rather than allowed to
     * suppress the others: it cannot be ordered, and the remaining ones are still true.
     */
    static ObservedVersion lowestLine(List<ObservedVersion> observed) {
        return observed.stream()
            .filter(o -> minorLine(o.version()) != null)
            .min(Comparator.<ObservedVersion, MinorLine>comparing(o -> minorLine(o.version()))
                .thenComparing(ObservedVersion::coordinate))
            .orElse(null);
    }

    /** A version's release line, the only ordering this advisory needs. */
    record MinorLine(int major, int minor) implements Comparable<MinorLine> {
        @Override
        public int compareTo(MinorLine other) {
            return major != other.major
                ? Integer.compare(major, other.major)
                : Integer.compare(minor, other.minor);
        }

        boolean isBehind(MinorLine other) {
            return compareTo(other) < 0;
        }
    }

    /**
     * The {@code (major, minor)} pair of a Maven version string, or {@code null} when it does not
     * decompose into one.
     *
     * <p>Decomposing rather than ordering is why this does not reach for
     * {@code org.apache.maven.artifact.versioning.ComparableVersion}: that class orders full version
     * strings without decomposing them, and it lives behind the Maven boundary the decision
     * deliberately sits inside of. The cost of declining it is its totality, so this states what it
     * does with odd input, and the answer is silence. Failing to read a version string is not worth
     * a build message, let alone a build failure.
     *
     * <p>Two shapes are read leniently. A qualifier is dropped at the first {@code -}, so
     * {@code 3.21.6-SNAPSHOT} is the {@code 3.21} line. A version with no minor segment is read as
     * minor {@code 0}, so a consumer who pins {@code <version>25</version>} is compared against
     * {@code 25.0} rather than dropped: {@code 25} does mean {@code 25.0}, and reading it that way
     * cannot produce a false positive, since an equal line is silent either way.
     */
    static MinorLine minorLine(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        int qualifier = trimmed.indexOf('-');
        String release = qualifier < 0 ? trimmed : trimmed.substring(0, qualifier);
        String[] segments = release.split("\\.", -1);
        Integer major = segment(segments, 0);
        if (major == null) {
            return null;
        }
        Integer minor = segments.length > 1 ? segment(segments, 1) : Integer.valueOf(0);
        return minor == null ? null : new MinorLine(major, minor);
    }

    /** The segment as a non-negative {@code int}, or {@code null} when it is not one. */
    private static Integer segment(String[] segments, int index) {
        String raw = segments[index];
        if (raw.isEmpty() || !raw.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }
}
