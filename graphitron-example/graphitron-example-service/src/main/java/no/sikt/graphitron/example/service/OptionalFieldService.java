package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.service.records.JooqChangedStatusResult;
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

    public List<JooqChangedStatusResult> checkJooqChangedStatus(CustomerRecord input) {
        return List.of(new JooqChangedStatusResult(
                describeChangedState(input)
        ));
    }

    private static String describeChangedState(CustomerRecord record) {
        if (!record.changed(Customer.CUSTOMER.FIRST_NAME)) {
            return "OMITTED";
        }
        String value = record.getFirstName();
        return value == null ? "EXPLICIT_NULL" : value;
    }
}
