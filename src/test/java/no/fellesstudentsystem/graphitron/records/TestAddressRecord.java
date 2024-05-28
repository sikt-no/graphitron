package no.fellesstudentsystem.graphitron.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.City;

public class TestAddressRecord {
    String id;
    City city;
    TestCityRecord cityRecord;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public City getCity() {
        return city;
    }

    public void setCity(City city) {
        this.city = city;
    }

    public TestCityRecord getCityRecord() {
        return cityRecord;
    }

    public void setCityRecord(TestCityRecord cityRecord) {
        this.cityRecord = cityRecord;
    }
}
