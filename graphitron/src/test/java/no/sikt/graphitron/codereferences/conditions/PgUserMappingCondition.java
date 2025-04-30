package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.pg_catalog.tables.PgUser;
import no.sikt.graphitron.jooq.generated.testdata.pg_catalog.tables.PgUserMapping;
import org.jooq.Condition;

public class PgUserMappingCondition {
    public static Condition userMapping(PgUserMapping address, PgUser firstname) {
        return null;
    }
}
