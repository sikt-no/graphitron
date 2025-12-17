package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.AddressRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.example.service.records.MockUpdateAddressAndCustomerResultRecord;
import org.jooq.DSLContext;
import org.jooq.Row1;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MockService {

    public MockService(DSLContext context) {
    }

    public Map<Row1<Integer>, List<FilmRecord>> filmsFromCity(Set<Row1<Integer>> cityKeys) {
        var counter = new int[]{1};
        return cityKeys.stream().collect(Collectors.toMap(
            key -> key,
            key -> {
                var film = new FilmRecord();
                film.setFilmId(String.valueOf(counter[0]++));
                film.setTitle("Film from service");
                return List.of(film);
            }
        ));
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
