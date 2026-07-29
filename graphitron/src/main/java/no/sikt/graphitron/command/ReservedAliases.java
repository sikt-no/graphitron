package no.sikt.graphitron.command;

/**
 * The reserved SELECT-alias namespace the generated queries and their readers share: synthetic
 * aliases a writer mints so a reader can address a projected value the catalog does not name.
 * One holder because the invariant is cross-package ("writer alias equals reader alias", with
 * writers in {@code render} and the legacy generators and readers in the generated runtime),
 * and the namespace-disjointness argument lives here rather than on any one writer.
 *
 * <p>Disjointness: every reserved alias starts with a double underscore, which no result-key
 * bucket can collide with under the prefixing rule ({@link #RESULT_KEY_PREFIX} + key is outside
 * the client's alias space by construction; an adversarial {@code __rk_foo} client alias mints
 * {@code __rk___rk_foo}, still distinct), and {@link #DISCRIMINATOR} is aliased precisely so the
 * routing read cannot collide with a real catalog column of the same name.
 */
public final class ReservedAliases {

    private ReservedAliases() {}

    /**
     * The result-key alias prefix: a projection that must be read back per GraphQL result key
     * (aliased duplicates: {@code a: displayName b: displayName}) is emitted as
     * {@code .as("__rk_" + <resultKey>)} and read back as
     * {@code DSL.field("__rk_" + env.getField().getResultKey())}. See {@link TermAlias#BY_RESULT_KEY}.
     */
    public static final String RESULT_KEY_PREFIX = "__rk_";

    /**
     * The discriminated-interface routing alias: the base table's discriminator column projected
     * under a synthetic name (mirroring the multi-table {@code __typename} convention) so the
     * generated {@code TypeResolver} routes each row unambiguously even when the interface also
     * exposes the discriminator as a queryable field.
     */
    public static final String DISCRIMINATOR = "__discriminator__";
}
