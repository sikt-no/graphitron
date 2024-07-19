package no.fellesstudentsystem.graphitron_newtestorder.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.City;

public class MapperAddressJavaRecord {
    String id, postalCode;
    City cityJOOQ;
    MapperCityJavaRecord cityJava;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public City getCityJOOQ() {
        return cityJOOQ;
    }

    public void setCityJOOQ(City cityJOOQ) {
        this.cityJOOQ = cityJOOQ;
    }

    public MapperCityJavaRecord getCityJava() {
        return cityJava;
    }

    public void setCityJava(MapperCityJavaRecord cityJava) {
        this.cityJava = cityJava;
    }
}
