package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/** Hands back {@link StintBatch}es, the parent of the key-table-without-primary-key fixture. */
public final class StintBatchService {

    private StintBatchService() {}

    public static List<StintBatch> batches() {
        return List.of();
    }
}
