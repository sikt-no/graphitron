package no.sikt.graphitron.model.sources;

import org.jooq.Condition;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * What a graph's claim on a source says to the gatherer that would forget it.
 *
 * <p>Here rather than in a gatherer's package because it is no one gatherer's: every gatherer that
 * reads sources writes its own graph's claims in {@code store_graph_source}, and each owns the
 * {@code store_source} rows of the kinds it reads. The predicate is shared and the reclaims are
 * not, so which gatherer forgets which slice of the registry stays readable at its call site.
 *
 * <p>The ruling is in {@code docs/architecture/explanation/fact-model.adoc}: a sweep forgets a
 * source only when its file is gone and no graph claims it, and the foreign key from the claim is
 * a backstop rather than the mechanism.
 */
public final class SourceClaims {

    private SourceClaims() {}

    /**
     * A {@code store_source} row no graph claims. Put in a delete's own {@code WHERE}, so the check
     * and the delete are one statement: a source another graph still reads is left for that graph's
     * next capture, which drops its own claim and then forgets the source.
     */
    public static Condition unclaimed() {
        return notExists(selectOne().from(STORE_GRAPH_SOURCE)
            .where(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(STORE_SOURCE.SOURCE_NAME)));
    }
}
