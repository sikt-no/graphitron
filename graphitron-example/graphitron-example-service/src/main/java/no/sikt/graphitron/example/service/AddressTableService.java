package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.Address;

public class AddressTableService {

    public Address addressTableService(Address address, String postalCode) {
        return address.where(address.POSTAL_CODE.eq(postalCode));
    }
}
