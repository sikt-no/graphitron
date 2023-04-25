package no.fellesstudentsystem.graphitron.conditions;

import no.fellesstudentsystem.kjerneapi.tables.Emne;
import org.jooq.Condition;

import java.util.List;

/**
 * Fake service for condition tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class EmneTestConditions {
    public static Condition emneKode(Emne emne, String emneKode) {
        return null;
    }

    public static Condition emneKoder(Emne emne, List<String> emneKoder) {
        return null;
    }

    public static Condition emneAll(Emne emne, String eierInstitusjon, List<String> emneKoder) {
        return null;
    }

    public static Condition emneInputAll(Emne emne, String eierInstitusjon, String emneKode, String versjonskode) {
        return null;
    }
}