package fake.code.example.package.queries.query;

import static no.fellesstudentsystem.kjerneapi.Keys.*;
import static no.fellesstudentsystem.kjerneapi.Tables.*;

import java.lang.String;
import java.util.List;
import fake.graphql.example.model.Kull;
import fake.graphql.example.model.ProgramStudierett;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<ProgramStudierett> programStudierettForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                STUDIERETT.getId().as("id"),
                                DSL.row(
                                        STUDIERETT.kull().getId().as("id")
                                ).mapping(Functions.nullOnAllNull(Kull::new)).as("kull")
                        ).mapping(Functions.nullOnAllNull(ProgramStudierett::new)).as("programStudierett")
                )
                .from(STUDIERETT)
                .where(STUDIERETT.ID.eq(id))
                .orderBy(STUDIERETT.getIdFields())
                .fetch(0, ProgramStudierett.class);
    }
}
