package no.sikt.graphitron.rewrite.test.services;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

/**
 * Fixture for the owned path's one-transaction-per-mutation-field contract: {@code @service}
 * mutations that write marker rows into {@code mutation_scope_marker} and then end the ways a
 * field can end, so {@code MutationScopeExecutionTest} can read back which writes survived. Every
 * write runs on the {@code DSLContext} graphitron hands the service, in autocommit as far as this
 * code can tell; the field's transaction is what decides whether the rows stay.
 */
public final class MutationScopeService {

    private MutationScopeService() {}

    /** Writes one marker row and returns the marker. */
    public static String writeOne(DSLContext dsl, String marker) {
        insert(dsl, marker);
        return marker;
    }

    /** Writes two marker rows, then throws an exception no {@code @error} channel claims. */
    public static String writeTwiceThenThrow(DSLContext dsl, String marker) {
        insert(dsl, marker);
        insert(dsl, marker);
        throw new IllegalStateException("mutation-scope fixture: failing after two writes");
    }

    /** Writes two marker rows, then throws the exception the field's {@code @error} channel claims. */
    public static MutationScopeRefusedPayload writeTwiceThenRefuse(DSLContext dsl, String marker) {
        insert(dsl, marker);
        insert(dsl, marker);
        throw new MutationScopeRefusedException("refused after two writes");
    }

    /**
     * Writes a marker row, runs a statement that fails, catches it and returns normally. On
     * PostgreSQL the failed statement aborts the transaction, so the write cannot commit.
     */
    public static String writeThenCatchAFailedStatement(DSLContext dsl, String marker) {
        insert(dsl, marker);
        try {
            dsl.execute("select 1 / 0");
        } catch (DataAccessException expected) {
            // The service decides the failure is not fatal; the database has already decided otherwise.
        }
        return marker;
    }

    /**
     * Writes a marker row and a child row whose deferred foreign key names no parent: both
     * statements succeed, and the commit fails.
     */
    public static String writeADeferredOrphan(DSLContext dsl, String marker) {
        insert(dsl, marker);
        dsl.execute("insert into mutation_scope_deferred_child (parent_id, marker) values (-1, ?)", marker);
        return marker;
    }

    /** Touches no database. */
    public static String noDatabase(String marker) {
        return marker;
    }

    private static void insert(DSLContext dsl, String marker) {
        dsl.insertInto(DSL.table("mutation_scope_marker"), DSL.field("marker", String.class))
            .values(marker)
            .execute();
    }
}
