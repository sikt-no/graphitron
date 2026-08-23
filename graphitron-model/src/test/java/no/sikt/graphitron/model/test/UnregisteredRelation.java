package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.derive.Materializations;
import org.jooq.DSLContext;

/**
 * Reverses one materialization registration inside a live store, so every reader of the registered
 * name reads the rule on demand instead of the table holding its rows. What a case needs to ask what
 * a registration <em>costs</em>: the two shapes of one relation, in one process, with no DDL edit and
 * no model rebuild.
 *
 * <p>The move is {@link RunawayRelation}'s, rename-then-create, and this is its sibling: the target
 * table is renamed out of the way and a view of the same shape is created under the canonical name,
 * selecting from the registration's own source view. That view is where the rule already lives, which
 * is the whole reason the swap needs no SQL of its own; the register's shape guarantee is what makes
 * {@code SELECT *} the right projection, the target and its source view being column-for-column the
 * same relation.
 *
 * <p>Takes a {@link Materializations.Registration} rather than a relation name, so it cannot be
 * pointed at a relation nothing registered. Taking a name would mean reconstructing the source view
 * by appending a suffix, and nothing in the tree pins that convention:
 * {@code MaterializeRegistryGateTest} checks kinds, column lists, acyclicity and refresh order, and
 * never the spelling. The register holds the pair and {@link Materializations#registrations} hands it
 * out already paired.
 *
 * <p><b>Read through a reader minted after the swap, not through the writer surface that performed
 * it.</b> H2 resolves a view's table references once per session and keeps that resolution, so a
 * dependent view <em>already evaluated on the connection that renames the table</em> goes on reading
 * the renamed table for that connection's whole life, while every other session resolves the
 * canonical name to the view this installs. A store's writer surface is one long-lived connection
 * that capture has already read these views on, so it is exactly the session that sees the stale
 * answer; {@link no.sikt.graphitron.model.boot.GraphitronModelStore#reader} mints a connection of its
 * own per call and pools nothing, so a reader taken after the swap resolves afresh. Measure through
 * one of those. A case that reads the swapped relation on the writer surface is not measuring the
 * unregistered shape and nothing will tell it so.
 *
 * <p>Test-only, and one-way per store like its sibling, though for its own reasons rather than that
 * one's. {@code RunawayRelation} is one-way because its swap stops a relation terminating; this swap
 * preserves every answer, and what spends the store is that two mechanisms afterwards address the
 * canonical name as a table. {@link Materializations#refreshAll}, which
 * {@link SeededStore#withSeededStore} reaches through its derive step, would empty and refill a name
 * that is now a view, and {@link ThreadConfinedStore}'s clear refuses outright, H2 declining to
 * truncate a view. So a case that installs this owns the store it installs it into: take one from
 * {@link FactStores} and close it, and expect one store per registration under measurement rather
 * than one store carrying every shape.
 */
public final class UnregisteredRelation {

    private UnregisteredRelation() {}

    /** Suffix the materialized table is renamed to, readable by a case that wants its rows. */
    public static final String REGISTERED_SUFFIX = "_while_registered";

    /**
     * Makes every read of {@code registration}'s target evaluate its source view instead of reading
     * the table, keeping the materialized rows under the target name plus
     * {@link #REGISTERED_SUFFIX}.
     *
     * @param dsl the store's own writer surface, this being DDL rather than a read
     * @param registration a row of the register, source view and target table as it holds them
     */
    public static void install(DSLContext dsl, Materializations.Registration registration) {
        String target = registration.targetTableName();
        dsl.execute("ALTER TABLE " + target + " RENAME TO " + target + REGISTERED_SUFFIX);
        dsl.execute("CREATE VIEW " + target + " AS SELECT * FROM " + registration.sourceViewName());
    }
}
