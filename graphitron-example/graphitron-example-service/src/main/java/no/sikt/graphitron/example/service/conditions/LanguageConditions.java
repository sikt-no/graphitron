package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Address;
import no.sikt.graphitron.example.generated.jooq.tables.Language;
import org.jooq.Condition;

import static org.jooq.impl.DSL.falseCondition;

public class LanguageConditions {

    public static Condition spokenLanguageForAddressByPostalCode(Address address, Language language) {
        return falseCondition();
    }
}
