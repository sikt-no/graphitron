package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.City;
import fake.graphql.example.model.CityInput1;
import java.util.List;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<City> cityForQuery(DSLContext ctx, CityInput1 cityInput,
                                          SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId()
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(cityInput != null ? CITY.CITY_ID.eq(cityInput.getCityId()) : DSL.noCondition())
                .and(cityInput != null && cityInput.getCityInput2() != null && cityInput.getCityInput2().size() > 0 ?
                        DSL.row(
                                CITY.CITY_ID,
                                CITY.CITY
                        ).in(cityInput.getCityInput2().stream().map(input -> DSL.row(
                                input.getCityId(),
                                input.getName())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
}
