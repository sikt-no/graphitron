package fake.code.example.package.queries.query;

import static no.fellesstudentsystem.kjerneapi.Keys.*;
import static no.fellesstudentsystem.kjerneapi.Tables.*;

import java.lang.String;
import java.util.List;
import fake.graphql.example.package.model.StudentVedInstitusjon;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<StudentVedInstitusjon> studentForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                STUDENT.getId().as("id"),
                                select.optional("studentnummer", STUDENT.STUDENTNR_TILDELT).as("studentnummer")
                        ).mapping(Functions.nullOnAllNull(StudentVedInstitusjon::new)).as("student")
                )
                .from(STUDENT)
                .where(STUDENT.ID.eq(id))
                .orderBy(STUDENT.getIdFields())
                .fetch(0, StudentVedInstitusjon.class);
    }
}
