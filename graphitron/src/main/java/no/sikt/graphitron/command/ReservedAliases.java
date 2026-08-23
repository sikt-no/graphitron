package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.AliasOwner;

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
 *
 * <p>The result-key namespace has a second axis, the alias <em>owner</em>
 * ({@link AliasOwner}), and it is disjoint from the first on the
 * delimiter. {@link #resultKeyPrefix} composes {@code __rk_<owner>$}; GraphQL names admit no
 * {@code $} (the spec's name grammar is {@code /[_A-Za-z][_0-9A-Za-z]*&#47;}), so a client-minted
 * result key can never spell an owner qualifier, and every qualified alias is distinct from every
 * bare one and from every other owner's. That is why the delimiter is {@code $} and not the
 * {@code _} the sibling {@code <TypeName>_<fieldName>} participant-scalar alias uses: that scheme
 * composes two build-time-visible SDL names, so a collision there is censusable, while this one
 * composes a client-minted result key no build-time check can enumerate. PostgreSQL's 63-byte
 * identifier limit bounds both, and the qualifier consumes more of that budget without changing
 * the exposure in kind.
 */
public final class ReservedAliases {

    /**
     * The owner qualifier's delimiter. JavaPoet reads {@code $} as its own format placeholder, so
     * this string only ever reaches a {@code CodeBlock} as an {@code $S} argument, never inside a
     * format string.
     */
    private static final String OWNER_DELIMITER = "$";

    private ReservedAliases() {}

    /**
     * The emitted alias prefix for one {@link AliasOwner}: the
     * constant a writer concatenates the runtime result key onto, and the same constant the
     * matching read spells. Both halves call this rather than composing the string themselves, so
     * the delimiter and the concatenation order are one decision.
     */
    public static String resultKeyPrefix(AliasOwner owner) {
        return switch (owner) {
            case AliasOwner.Shared ignored -> RESULT_KEY_PREFIX;
            case AliasOwner.QualifiedBy q -> RESULT_KEY_PREFIX + q.owner() + OWNER_DELIMITER;
        };
    }

    /**
     * The result-key alias prefix: a projection that must be read back per GraphQL result key
     * (aliased duplicates: {@code a: displayName b: displayName}) is emitted as
     * {@code .as("__rk_" + <resultKey>)} and read back as
     * {@code DSL.field("__rk_" + env.getField().getResultKey())}. See {@link TermAlias#BY_RESULT_KEY}.
     *
     * <p>The bare form is the {@link AliasOwner.Shared} arm of
     * {@link #resultKeyPrefix}; an owner-qualified projection composes its prefix there instead of
     * reading this constant directly.
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
     * The scatter key: an input VALUES table's index column projected under a synthetic name and
     * read back by the per-class scatter helpers that place each flat row at its key's position.
     * Two writers mint it, for the same reason on different rails: the batched launcher body
     * (regrouping per DataLoader key) and the root lookup's list arm (one output slot per input
     * key, null where the key matched no row).
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

    /**
     * A chain hop's table alias: {@code <fieldName>_<index>}, the index counting hops across the
     * whole chain from zero. What a re-read declares each aliased table local as and what its joins
     * and its terminal projection then name.
     *
     * <p>The catalog-alias namespace rather than the reserved one above, and deliberately: this
     * aliases a table a statement actually selects from, where every constant above aliases a value
     * the catalog does not name. So it carries no double underscore and claims no disjointness from
     * client result keys; the two namespaces never meet, one addressing a {@code FROM} entry and the
     * other a projected column. One holder for both because an alias is an alias, and a second
     * holder is how a writer and a reader stop spelling the same one.
     *
     * <p>The index is the hop's position along the chain and not its position within the
     * {@code @reference} application that wrote it, which is why a chain of several applications
     * numbers continuously across them. A store-sourced reader reaches it as the chain node's own
     * sequence less one, the routine node the chain departs from being sequence zero and no hop.
     *
     * @param fieldName the field the chain is written on, whose name every hop of it shares
     * @param chainSeq  the hop's sequence in the chain relation, one for the first hop after the
     *                  routine node
     */
    public static String chainHop(String fieldName, int chainSeq) {
        if (chainSeq < 1) {
            throw new IllegalArgumentException(
                "a chain hop's sequence counts from one, sequence zero being the routine node the"
                + " chain departs from rather than a hop; got " + chainSeq + " for '" + fieldName
                + "'");
        }
        return fieldName + "_" + (chainSeq - 1);
    }
}
