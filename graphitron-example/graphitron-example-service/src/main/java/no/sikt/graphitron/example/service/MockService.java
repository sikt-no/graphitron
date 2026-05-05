package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.AddressRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.CityRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.FilmActorRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.example.service.records.MockUpdateAddressAndCustomerResultRecord;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MockService {

    public MockService(DSLContext context) {
    }

    public Map<CityRecord, List<FilmRecord>> filmsFromCityByYear(Set<CityRecord> cityKeys, Integer year) {
        //in practice the service would do something with year, but this is not important here.
        return filmsFromCity(cityKeys);
    }

    public Map<CityRecord, List<FilmRecord>> filmsFromCity(Set<CityRecord> cityKeys) {
        return cityKeys.stream().collect(Collectors.toMap(
                key -> key,
                key -> {
                    var film = new FilmRecord();
                    film.setFilmId(String.valueOf(key.getCityId()));
                    film.setTitle("Film from service");
                    return List.of(film);
                }
        ));
    }

    /**
     * Returns a hardcoded mix of resolver-key shapes to exercise dataloader normalization:
     * null, partial PK (one PK column null), PK + non-PK column populated, PK only.
     * Only the latter two should resolve to a Film. The incoming keys are intentionally
     * ignored — the dataloader is responsible for matching these against whatever was
     * actually requested via PK normalization.
     */
    public Map<FilmActorRecord, List<FilmRecord>> filmsWithMixedResolverKeys(Set<FilmActorRecord> keys) {
        var film = new FilmRecord();
        film.setFilmId("1");
        film.setTitle("Film from service");
        var films = List.of(film);

        var partialPk = new FilmActorRecord();
        partialPk.setActorId(10);

        var pkPlusNonPk = new FilmActorRecord();
        pkPlusNonPk.setActorId(20);
        pkPlusNonPk.setFilmId(1);
        pkPlusNonPk.setLastUpdate(LocalDateTime.of(2026, 1, 1, 0, 0));

        var pkOnly = new FilmActorRecord();
        pkOnly.setActorId(30);
        pkOnly.setFilmId(1);

        var result = new HashMap<FilmActorRecord, List<FilmRecord>>();
        result.put(null, films);
        result.put(partialPk, films);
        result.put(pkPlusNonPk, films);
        result.put(pkOnly, films);
        return result;
    }

    public MockUpdateAddressAndCustomerResultRecord mockUpdateAddressAndCustomer() {
        var result = new MockUpdateAddressAndCustomerResultRecord();

        var address1 = new AddressRecord();
        address1.setAddressId(9);
        result.setMyAddress(address1);

        var address2 = new AddressRecord();
        address2.setAddressId(14);
        result.setAddress(address2);

        var customer6 = new CustomerRecord();
        customer6.setCustomerId(6);
        var customer2 = new CustomerRecord();
        customer2.setCustomerId(2);
        result.setCustomers(List.of(customer6, new CustomerRecord(), customer2));

        return result;
    }
}
