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

    /**
     * The batched delivery's scatter key: the parent-input VALUES table's index column projected
     * under a synthetic name, written by the batched launcher body and read back by the per-class
     * scatter helpers that regroup the flat result per DataLoader key.
     */
    public static final String IDX = "__idx__";

    /**
     * The batched connection page's windowed row number ({@code ROW_NUMBER() OVER (PARTITION BY
     * <idx>)}): written by the batched connection launcher's ranked projection, read by its own
     * outer page filter and by the connection scatter helper's per-parent slice.
     */
    public static final String ROW_NUMBER = "__rn__";

    /**
     * The multi-table polymorphic routing alias: each participant branch projects its concrete
     * GraphQL type name as an inline constant under this alias
     * ({@code DSL.inline("Customer").as("__typename")}), and the readers route on it across
     * packages: the polymorphic emitter's own stage-1 dispatcher, the emitted schema class's
     * {@code TypeResolver}, the federation entity dispatcher, and the node-resolution fetcher.
     * The double-underscore spelling cannot collide with a projected catalog column, and GraphQL
     * reserves the bare {@code __typename} introspection key this alias deliberately mirrors.
     */
    public static final String TYPENAME = "__typename";
}
