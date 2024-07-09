package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.City;
import fake.graphql.example.model.CityInput;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<City> paramConditionForQuery(DSLContext ctx, String countryId,
            List<String> cityNames, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(CITY.COUNTRY_ID.eq(countryId))
                .and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
    public static List<City> paramConditionOverrideForQuery(DSLContext ctx, String countryId,
            List<String> cityNames, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(CITY.COUNTRY_ID.eq(countryId))
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
    public static List<City> fieldConditionForQuery(DSLContext ctx, String countryId,
            List<String> cityNames, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(CITY.COUNTRY_ID.eq(countryId))
                .and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
    public static List<City> fieldConditionOverrideForQuery(DSLContext ctx, String countryId,
            List<String> cityNames, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
    public static List<City> fieldAndParamConditionForQuery(DSLContext ctx, String countryId,
            List<String> cityNames, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(CITY.COUNTRY_ID.eq(countryId))
                .and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
    public static List<City> fieldAndParamConditionOverrideForQuery(DSLContext ctx, String countryId,
            List<String> cityNames, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
    public static List<City> fieldAndParamConditionOverrideBothForQuery(DSLContext ctx, String countryId,
            List<String> cityNames, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
    public static List<City> fieldInputConditionForQuery(DSLContext ctx, String countryId,
            CityInput cityInput, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                select.optional("name", CITY.CITY),
                                select.optional("lastUpdate", CITY.LAST_UPDATE)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .where(CITY.COUNTRY_ID.eq(countryId))
                .and(cityInput != null ? CITY.CITY.eq(cityInput.getName()) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityName(CITY, cityInput != null ? cityInput.getName() : null))
                .and(cityInput != null ? CITY.CITY_ID.eq(cityInput.getCityId()) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityInputAll(CITY, countryId, cityInput != null ? cityInput.getName() : null, cityInput != null ? cityInput.getCityId() : null))
                .orderBy(CITY.getIdFields())
                .fetch(it -> it.into(City.class));
    }
}
