package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Address;
import no.sikt.graphitron.example.generated.jooq.tables.Language;
import no.sikt.graphitron.example.service.records.LanguageNameFilterRecord;
import org.jooq.Condition;

import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.noCondition;

public class LanguageConditions {

    public static Condition spokenLanguageForAddressByPostalCode(Address address, Language language) {
        return falseCondition();
    }

    public static Condition byNames(Language language, LanguageNameFilterRecord filter) {
        var names = filter == null ? null : filter.getNames();
        return names == null || names.isEmpty() ? noCondition() : language.NAME.in(names);
    }
}
