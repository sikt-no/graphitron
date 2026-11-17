package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.VouchedResultDetailRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.VouchedResultRecord;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.VOUCHED_RESULT_DETAIL;

/**
 * Accepting fixture: a child service below a vouched result. The details it reads are admitted by
 * the row policies only on the connection the vouched entity hands down, so the execution tier sees
 * which connection served it by whether any come back.
 */
public final class VouchedResultService {

    private VouchedResultService() {}

    /** The details of each result, read on the connection handed to the service. */
    public static Map<VouchedResultRecord, List<VouchedResultDetailRecord>> servicedDetails(
            Set<VouchedResultRecord> results, DSLContext dsl) {
        Map<Integer, List<VouchedResultDetailRecord>> details = dsl.selectFrom(VOUCHED_RESULT_DETAIL)
            .where(VOUCHED_RESULT_DETAIL.RESULT_ID.in(results.stream().map(VouchedResultRecord::getResultId).toList()))
            .orderBy(VOUCHED_RESULT_DETAIL.DETAIL_ID)
            .fetch().stream().collect(Collectors.groupingBy(VouchedResultDetailRecord::getResultId));
        return results.stream().collect(Collectors.toMap(Function.identity(),
            result -> details.getOrDefault(result.getResultId(), List.of())));
    }
}
