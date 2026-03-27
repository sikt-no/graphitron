package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.service.records.NestedRecordOuterInput;
import no.sikt.graphitron.example.service.records.NestedRecordResult;
import org.jooq.DSLContext;

import java.util.List;

public class NestedRecordPresenceService {

    public NestedRecordPresenceService(DSLContext ctx) {
    }

    public List<NestedRecordResult> check(List<NestedRecordOuterInput> input) {
        return input.stream()
                .map(item -> {
                    var nested = item.getNested();
                    var deep = nested != null ? nested.getDeepNested() : null;
                    return new NestedRecordResult(
                            item.getTopField(),
                            nested != null ? nested.getMiddleField() : null,
                            deep != null ? deep.getDeepField() : null,
                            deep != null ? deep.getDeepNumber() : null
                    );
                })
                .toList();
    }
}
