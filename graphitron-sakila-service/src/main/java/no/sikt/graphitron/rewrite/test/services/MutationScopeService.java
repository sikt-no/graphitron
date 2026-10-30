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

    /**
     * Writes a marker row, then another inside a nested transaction that succeeds. The nested scope
     * joins the field's transaction: no savepoint, and nothing commits until the field does.
     */
    public static String nestedScopeSucceeds(DSLContext dsl, String marker) {
        insert(dsl, marker);
        dsl.transaction(tx -> insert(DSL.using(tx), marker));
        return marker;
    }

    /**
     * Writes a marker row, opens a nested transaction that writes and then throws, catches the
     * exception, writes again and returns normally. The field still rolls back, on every database.
     */
    public static String nestedScopeFailsAndIsCaught(DSLContext dsl, String marker) {
        insert(dsl, marker);
        try {
            dsl.transaction(tx -> {
                insert(DSL.using(tx), marker);
                throw new IllegalStateException("mutation-scope fixture: the nested scope fails");
            });
        } catch (IllegalStateException expected) {
            // The service decides the nested failure is not fatal.
        }
        insert(dsl, marker);
        return marker;
    }

    /**
     * Writes a marker row, opens a nested transaction that fails on a SQL error, catches it and
     * writes again. On PostgreSQL the nested failure aborted the whole transaction, so the next
     * statement fails too.
     */
    public static String nestedSqlFailureIsCaught(DSLContext dsl, String marker) {
        insert(dsl, marker);
        try {
            dsl.transaction(tx -> DSL.using(tx).execute("select 1 / 0"));
        } catch (DataAccessException expected) {
            // The service decides the failure is not fatal; the database has already decided otherwise.
        }
        insert(dsl, marker);
        return marker;
    }

    /** Writes a marker row, then opens a nested transaction that throws, and does not catch it. */
    public static String nestedScopeFailsUncaught(DSLContext dsl, String marker) {
        insert(dsl, marker);
        dsl.transaction(tx -> {
            throw new IllegalStateException("mutation-scope fixture: the nested scope fails and is not caught");
        });
        return marker;
    }

    /**
     * A Query field's service that opens a transaction of its own, nests another inside it, and
     * catches the nested failure. The outer transaction then cannot commit, and the caller sees the
     * failure the service swallowed.
     */
    public static String queryNestedFailureIsCaught(DSLContext dsl, String marker) {
        return dsl.transactionResult(outer -> {
            try {
                DSL.using(outer).transaction(inner -> {
                    throw new IllegalStateException("mutation-scope fixture: the nested scope fails");
                });
            } catch (IllegalStateException expected) {
                // The service decides the nested failure is not fatal.
            }
            return marker;
        });
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
