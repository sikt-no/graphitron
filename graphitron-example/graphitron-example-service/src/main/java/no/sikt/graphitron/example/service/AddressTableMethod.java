package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.Address;

public class AddressTableMethod {

    public Address addressTableMethod(Address address, String postalCode) {
        return address.where(address.POSTAL_CODE.eq(postalCode));
    }
}
