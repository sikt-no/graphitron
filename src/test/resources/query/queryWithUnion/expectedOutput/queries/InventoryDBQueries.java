package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Extra;
import fake.graphql.example.model.Film2;
import fake.graphql.example.model.FilmDataA;
import fake.graphql.example.model.FilmDataB;
import fake.graphql.example.model.FilmDataC;
import fake.graphql.example.model.FilmDataD;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class InventoryDBQueries {
    public Map<String, List<Film2>> filmsForInventory(DSLContext ctx, Set<String> inventoryIds,
                                                      SelectionSet select) {
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                INVENTORY.film().getId().as("id"),
                                DSL.row(
                                        INVENTORY.film().DESCRIPTION.as("description")
                                ).mapping(Functions.nullOnAllNull(FilmDataA::new)).as("filmDetailsA"),
                                DSL.row(
                                        INVENTORY.film().LENGTH.as("length")
                                ).mapping(Functions.nullOnAllNull(FilmDataB::new)).as("filmDetailsA"),
                                DSL.row(
                                        INVENTORY.film().RELEASE_YEAR.as("releaseYear")
                                ).mapping(Functions.nullOnAllNull(FilmDataC::new)).as("filmDetailsA"),
                                DSL.row(
                                        INVENTORY.film().RELEASE_YEAR.as("releaseYear")
                                ).mapping(Functions.nullOnAllNull(FilmDataC::new)).as("filmDetailsB"),
                                DSL.row(
                                        INVENTORY.film().RATING.as("rating")
                                ).mapping(Functions.nullOnAllNull(FilmDataD::new)).as("filmDetailsB"),
                                DSL.row(
                                        select.optional("extra/title", INVENTORY.film().TITLE).as("title"),
                                        select.optional("extra/description", INVENTORY.film().DESCRIPTION).as("description"),
                                        select.optional("extra/length", INVENTORY.film().LENGTH).as("length")
                                ).mapping(Extra::new).as("extra")
                        ).mapping((a0, a1_0, a1_1, a1_2, a2_0, a2_1, a3) -> a0 == null && a1_0 != null && a1_1 != null && a1_2 != null && a2_0 != null && a2_1 != null && (a3 == null || new Extra().equals(a3)) ? null : new Film2(a0, a1_0 != null ? a1_0 : a1_1 != null ? a1_1 : a1_2, a2_0 != null ? a2_0 : a2_1, a3)).as("films")
                )
                .from(INVENTORY)
                .where(INVENTORY.hasIds(inventoryIds))
                .orderBy(INVENTORY.film().getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}