package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.StoreRecord;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution-tier fixture: a root {@code @service} answering store records for a type whose
 * projection reads the session ({@code Query.sessionStoresByService} returning {@code SessionStore},
 * whose {@code customersOfPrincipal} carries a {@code @condition} binding
 * {@code $session.principal}). The lift selecting the stores off the records hands the projection
 * the statement's connection, the session being read off it.
 */
public final class SessionStoreService {

    private SessionStoreService() {}

    /** The stores with the given ids, in input order; an unknown id is left out. */
    public static List<StoreRecord> storesByIds(List<Integer> ids, DSLContext dsl) {
        if (ids == null || ids.isEmpty()) return List.of();
        var fetched = dsl.selectFrom(Tables.STORE)
            .where(Tables.STORE.STORE_ID.in(ids))
            .fetch();
        Map<Integer, StoreRecord> byPk = new HashMap<>(fetched.size());
        for (StoreRecord r : fetched) byPk.put(r.get(Tables.STORE.STORE_ID), r);
        List<StoreRecord> ordered = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            StoreRecord r = byPk.get(id);
            if (r != null) ordered.add(r);
        }
        return ordered;
    }
}
