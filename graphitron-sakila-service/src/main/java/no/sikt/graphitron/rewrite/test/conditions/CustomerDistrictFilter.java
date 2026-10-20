package no.sikt.graphitron.rewrite.test.conditions;

/**
 * A setter bean a {@code @condition(override: true)} parameter takes for
 * {@code CustomerDistrictBeanInput}, whose one field names its column with {@code @field(name:)}.
 * The member is named after the GraphQL field, not the column; see {@link BeanConditionFixtures}.
 */
public class CustomerDistrictFilter {
    private String addressDistrict;

    public String getAddressDistrict() {
        return addressDistrict;
    }

    public void setAddressDistrict(String addressDistrict) {
        this.addressDistrict = addressDistrict;
    }
}
