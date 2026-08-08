package no.sikt.graphitron.rewrite.dependency;

/**
 * One resolved version of a {@link WatchedDependency}, carrying the coordinate it was resolved
 * under rather than only the number.
 *
 * <p>The coordinate travels because a {@link WatchedDependency} is the identity of a <em>library</em>
 * and not of an artifact: jOOQ ships the same release line under several group ids, so the
 * coordinate the advisory tells a consumer to bump has to be the one their build actually resolved.
 * A commercial consumer told to bump the open-source coordinate is being told to switch editions,
 * which for a commercial-only dialect does not work at all.
 *
 * <p>The coordinate is formed at the Maven boundary, out of the artifact it was read from, so no
 * consumer of this record reconstructs or guesses one.
 *
 * @param coordinate {@code groupId:artifactId}, the form the advisory tells a consumer to bump
 * @param version    the resolved version string, uninterpreted
 */
public record ObservedVersion(String coordinate, String version) {
}
