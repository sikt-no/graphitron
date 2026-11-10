package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_KEY_RULE;

/**
 * The capture-cadence writer of {@code graphitron_minted_key}: the federation keys a graph mints on
 * its table-bound types, each with the rule that minted it, from the rule view beside it.
 *
 * <p>Runs as a stage of the graphitron gatherer after the nodes and the federation link it reads and
 * before the emitted population, whose minted application sets read what it writes. It decides
 * membership from facts upstream of the element, so a key is stated for a type before the type is
 * known to be emitted; the application sets join the element and render a key only on a type that
 * is. Clears and re-derives the graph's rows on {@link Nodes}' terms: the rows are a function of
 * relations the gatherer has just rewritten, and a key on a type that stops being table bound goes by
 * the cascade from {@code graphitron_tabletype}.
 */
public final class MintedKeys {

    private MintedKeys() {}

    /** Clears and re-derives the graph's minted keys; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        var k = GRAPHITRON_MINTED_KEY;
        var r = GRAPHITRON_MINTED_KEY_RULE;
        dsl.deleteFrom(k).where(k.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.TYPE_NAME, k.ROLE, k.FIELDS, k.RESOLVABLE, k.ORDINAL)
            .select(dsl.select(r.GRAPH_NAME, r.TYPE_NAME, r.ROLE, r.FIELDS, r.RESOLVABLE, r.ORDINAL)
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
