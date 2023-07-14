package no.fellesstudentsystem.graphitron.conditions;

import no.fellesstudentsystem.kjerneapi.tables.Termin;
import org.jooq.Condition;

/**
 * Fake service for condition tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class TerminTestConditions {

    public static Condition terminer(Termin t, String type) {
        return null;
    }

    public static Condition terminAll(Termin t, String type, Integer arstall) {
        return null;
    }

    public static Condition terminInputAll(Termin t, Integer arstall1, String type, Integer arstall2) {
        return null;
    }
}
