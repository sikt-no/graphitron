package fake.code.example.package.queries.query;

import static no.fellesstudentsystem.kjerneapi.Keys.*;
import static no.fellesstudentsystem.kjerneapi.Tables.*;

import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import fake.graphql.example.package.model.Eksamenstilpasningssoknad;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class StudentVedInstitusjonDBQueries {
    public Map<String, List<Eksamenstilpasningssoknad>> eksamenstilpasningssoknaderForStudentVedInstitusjon(
            DSLContext ctx, Set<String> studentVedInstitusjonIds, SelectionSet select) {
        var eksamenstilpasning_vurderingsmelding = EKSAMENSTILPASNING.vurderingsmelding().as("eksamenstilpasning_vurderingsmelding");
        return ctx
                .select(
                        eksamenstilpasning_vurderingsmelding.getStudentId(),
                        DSL.row(
                                EKSAMENSTILPASNING.getId().as("id"),
                                select.optional("soknadsdato", EKSAMENSTILPASNING.DATO_SOKNAD).as("soknadsdato")
                        ).mapping(Functions.nullOnAllNull(Eksamenstilpasningssoknad::new)).as("eksamenstilpasningssoknader")
                )
                .from(EKSAMENSTILPASNING)
                .where(eksamenstilpasning_vurderingsmelding.hasStudentIds(studentVedInstitusjonIds))
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
