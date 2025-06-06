/*
 * This file is generated by jOOQ.
 */
package no.sikt.graphitron.example.generated.jooq.tables.records;


import java.time.LocalDateTime;

import no.sikt.graphitron.example.generated.jooq.tables.Country;

import org.jooq.Record1;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class CountryRecord extends UpdatableRecordImpl<CountryRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>public.country.country_id</code>.
     */
    public void setCountryId(Integer value) {
        set(0, value);
    }

    /**
     * Getter for <code>public.country.country_id</code>.
     */
    public Integer getCountryId() {
        return (Integer) get(0);
    }

    /**
     * Setter for <code>public.country.country</code>.
     */
    public void setCountry(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>public.country.country</code>.
     */
    public String getCountry() {
        return (String) get(1);
    }

    /**
     * Setter for <code>public.country.last_update</code>.
     */
    public void setLastUpdate(LocalDateTime value) {
        set(2, value);
    }

    /**
     * Getter for <code>public.country.last_update</code>.
     */
    public LocalDateTime getLastUpdate() {
        return (LocalDateTime) get(2);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Integer> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached CountryRecord
     */
    public CountryRecord() {
        super(Country.COUNTRY);
    }

    /**
     * Create a detached, initialised CountryRecord
     */
    public CountryRecord(Integer countryId, String country, LocalDateTime lastUpdate) {
        super(Country.COUNTRY);

        setCountryId(countryId);
        setCountry(country);
        setLastUpdate(lastUpdate);
        resetChangedOnNotNull();
    }
}
