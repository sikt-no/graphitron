package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.Record1;
import org.jooq.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Child services under Film whose batch the frame does not read, each refused as
 * {@code SERVICE_BATCH_SHAPE_UNSUPPORTED}: keys as a jOOQ Result of the key's own records rather than
 * the parent's table records, an answer in a Set, and an answer in order under keys a Set gives none.
 * Never called.
 */
public final class FilmBatchShapeService {

    private FilmBatchShapeService() {}

    public static Map<Record1<Integer>, String> titlesByKeyResult(Result<Record1<Integer>> keys) {
        return Map.of();
    }

    public static Set<String> titlesAsSet(Set<FilmRecord> films) {
        return Set.of();
    }

    public static List<String> titlesInOrder(Set<FilmRecord> films) {
        return List.of();
    }
}
