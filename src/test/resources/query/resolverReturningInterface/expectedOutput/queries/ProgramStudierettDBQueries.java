package fake.code.example.package.queries.query;

import static no.fellesstudentsystem.kjerneapi.Keys.*;
import static no.fellesstudentsystem.kjerneapi.Tables.*;

import java.lang.String;
import java.util.Map;
import java.util.Set;
import fake.graphql.example.model.Kull;
import fake.graphql.example.model.ProgramStudierett;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class ProgramStudierettDBQueries {
    public static Map<String, ProgramStudierett> loadProgramStudierettByIdsAsReferertNode(DSLContext ctx, Set<String> ids,
            SelectionSet select) {
        return ctx
                .select(
                        STUDIERETT.getId(),
                        DSL.row(
                                STUDIERETT.getId(),
                                DSL.row(
                                        STUDIERETT.kull().getId()
                                ).mapping(Functions.nullOnAllNull(Kull::new))
                        ).mapping(Functions.nullOnAllNull(ProgramStudierett::new))
                )
                .from(STUDIERETT)
                .where(STUDIERETT.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
