package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.City;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.CityRecord;
import org.jooq.DSLContext;
import org.jooq.Row1;
import org.jooq.impl.DSL;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution fixture: child {@code @service} methods on the {@code City} type, which carries
 * <em>no</em> other force-projecting children ({@code @splitQuery}/{@code @tableMethod} siblings).
 * The {@code Film} service-child fixtures ({@link FilmService}) cannot pin the SourceKey
 * force-projection because Film's {@code cast}/{@code castByKey} split children already project
 * {@code FILM_ID} into every parent SELECT; here the {@code @service} child itself is the only
 * reason {@code CITY_ID} lands in the projection, so a query selecting the service child without
 * any key-mapped field turns red if the {@code BatchKeyField} arm in
 * {@code TypeClassGenerator.collectRequiredProjection} regresses.
 *
 * <p>Both methods look the key column up in the database (the opptak reproducer shape,
 * {@code WHERE <keyCol> IN (...)}), so a {@code null} key produces a {@code null} field value
 * rather than being papered over by a key-independent body.
 */
public final class CityService {

    private CityService() {}

    /**
     * Typed-{@link CityRecord} source shape ({@code SourceKey.Wrap.TableRecord}) — the
     * <em>silent-null</em> reproducer arm: the framework's key extraction is
     * {@code ((Record) env.getSource()).into(Tables.CITY)}, and {@code into} leaves absent
     * columns {@code null} instead of throwing. Without the force-projection this method
     * receives records whose {@code cityId} is {@code null} and every lookup misses.
     */
    public static Map<CityRecord, String> cityUppercase(Set<CityRecord> cities, DSLContext dsl) {
        List<Integer> ids = cities.stream().map(CityRecord::getCityId).toList();
        Map<Integer, String> namesById = new LinkedHashMap<>();
        for (CityRecord r : dsl.selectFrom(City.CITY).where(City.CITY.CITY_ID.in(ids)).fetch()) {
            namesById.put(r.getCityId(), r.getCity());
        }
        Map<CityRecord, String> result = new LinkedHashMap<>();
        for (CityRecord key : cities) {
            String name = namesById.get(key.getCityId());
            result.put(key, name == null ? null : name.toUpperCase());
        }
        return result;
    }

    /**
     * {@code Row1} source shape ({@code SourceKey.Wrap.Row}) — the <em>loud-throw</em> arm: the
     * framework's key extraction reads {@code ((Record) env.getSource()).get(Tables.CITY.CITY_ID)}
     * per column, which throws on an absent field instead of yielding {@code null}. Without the
     * force-projection this shape fails the request with a jOOQ field-lookup error.
     */
    public static Map<Row1<Integer>, String> cityLowercase(Set<Row1<Integer>> cityIds, DSLContext dsl) {
        if (cityIds.isEmpty()) return new LinkedHashMap<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Row1<Integer>[] keysArray = cityIds.toArray(new Row1[0]);

        Map<Row1<Integer>, String> result = new LinkedHashMap<>();
        for (CityRecord r : dsl.selectFrom(City.CITY)
                .where(DSL.row(City.CITY.CITY_ID).in(keysArray))
                .fetch()) {
            result.put(DSL.row(r.getCityId()), r.getCity().toLowerCase());
        }
        return result;
    }
}
