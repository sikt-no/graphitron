package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.service.records.OptionalFieldInput;
import no.sikt.graphitron.example.service.records.OptionalFieldResult;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

public class OptionalFieldService {
    public OptionalFieldService(DSLContext context) {
    }

    public List<OptionalFieldResult> checkOptionalFields(List<OptionalFieldInput> input) {
        var record = input.get(0);
        return List.of(new OptionalFieldResult(
                record.getTitle(),
                describeOptionalState(record.getRentalDuration()),
                describeOptionalState(record.getDescription())
        ));
    }

    private static String describeOptionalState(Optional<?> field) {
        if (field == null) {
            return "OMITTED";
        }
        return field.map(Object::toString).orElse("EXPLICIT_NULL");
    }
}
