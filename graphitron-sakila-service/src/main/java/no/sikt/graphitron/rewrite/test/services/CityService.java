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
 * <em>no</em> other force-projecting children ({@code @splitQuery} siblings).
 * The {@code Film} service-child fixtures ({@link FilmService}) cannot pin the SourceKey
 * force-projection because Film's {@code cast}/{@code castByKey} split children already project
 * {@code FILM_ID} into every parent SELECT; here the {@code @service} child itself is the only
 * reason {@code CITY_ID} lands in the projection, so a query selecting the service child without
 * any key-mapped field turns red if the {@code BatchKeyField} arm in
 * the projection producer's required-projection walk regresses.
 *
 * <p>Both methods look the key column up in the database (the opptak reproducer shape,
 * {@code WHERE <keyCol> IN (...)}), so neither can paper over a bad key with a key-independent
 * body. Neither method is entered at all if the projection regresses: both key extractions read
 * {@code CITY_ID} off the parent row by jOOQ field identity, which throws on a field absent from
 * the row type, so the failure surfaces through {@code ErrorRouter} before the batch is
 * dispatched.
 */
public final class CityService {

    private CityService() {}

    /**
     * Typed-{@link CityRecord} source shape ({@code SourceKey.Wrap.TableRecord}), the shape that
     * surfaced the federation {@code _entities}-fetch bug. The framework copies the key columns
     * onto a fresh typed record with {@code key.set(CITY_ID, source.get(CITY_ID))}; without the
     * force-projection that read throws and the request fails before this method is entered.
     *
     * <p>The canonical shape for the PK-only contract: the keys carry {@code CITY_ID} alone, so
     * the value the field resolves to is fetched here, in one batched query, through the injected
     * {@code DSLContext}. {@link FilmService#titleTitlecase} is the same pattern.
     */
    public static Map<CityRecord, String> cityUppercase(Set<CityRecord> cities, DSLContext dsl) {
        List<Integer> ids = cities.stream().map(CityRecord::getCityId).toList();
        Map<Integer, String> namesById = dsl
            .selectFrom(City.CITY)
            .where(City.CITY.CITY_ID.in(ids))
            .fetchMap(City.CITY.CITY_ID, City.CITY.CITY_);

        Map<CityRecord, String> result = new LinkedHashMap<>();
        for (CityRecord key : cities) {
            String name = namesById.get(key.getCityId());
            result.put(key, name == null ? null : name.toUpperCase());
        }
        return result;
    }

    /**
     * {@code Row1} source shape ({@code SourceKey.Wrap.Row}), the same guarantee reached through a
     * different key shape. The framework wraps the same per-column read in
     * {@code DSL.row(((Record) env.getSource()).get(Tables.CITY.CITY_ID))} instead of setting it
     * on a typed record, so the pair differs in the shape of the key it builds and not in what the
     * projection owes it, nor in how either fails without it.
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
