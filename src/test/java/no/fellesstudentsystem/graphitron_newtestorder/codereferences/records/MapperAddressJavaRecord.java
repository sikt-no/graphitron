package no.fellesstudentsystem.graphitron_newtestorder.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.City;

public class MapperAddressJavaRecord {
    String id;
    City city;
    MapperCityJavaRecord cityRecord;

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

    public MapperCityJavaRecord getCityRecord() {
        return cityRecord;
    }

    public void setCityRecord(MapperCityJavaRecord cityRecord) {
        this.cityRecord = cityRecord;
    }
}
