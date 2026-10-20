package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Address;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Customer;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.List;

/**
 * {@code @condition} methods whose input-object parameters are beans rather than the delivered
 * {@code Map}: the generated conditions class builds each bean from the map before the call. Each
 * filters by a bean member, so an execution test sees the bean arrive populated; before the bean
 * was built, these signatures compiled and threw {@code ClassCastException} on every request.
 */
public final class BeanConditionFixtures {

    private BeanConditionFixtures() {}

    /** A record bean bound by {@code argMapping}: the film whose title equals the filter's. */
    public static Condition filmTitleRecord(Table<?> table, FilmTitleFilter filter) {
        if (filter == null || filter.title() == null) {
            return DSL.noCondition();
        }
        return table.field(Film.FILM.TITLE).eq(filter.title());
    }

    /** A list of record beans on a list slot: films whose title equals any filter's. */
    public static Condition filmTitlesRecordList(Table<?> table, List<FilmTitleFilter> titles) {
        if (titles == null || titles.isEmpty()) {
            return DSL.noCondition();
        }
        return table.field(Film.FILM.TITLE).in(titles.stream().map(FilmTitleFilter::title).toList());
    }

    /**
     * A setter bean whose member is named after a filter field carrying {@code @field(name:)} and
     * {@code @reference}: customers whose address is in the filter's district.
     */
    public static Condition customerDistrictBean(Table<?> table, CustomerDistrictFilter filter) {
        if (filter == null || filter.getAddressDistrict() == null) {
            return DSL.noCondition();
        }
        return table.field(Customer.CUSTOMER.ADDRESS_ID).in(
            DSL.select(Address.ADDRESS.ADDRESS_ID).from(Address.ADDRESS)
                .where(Address.ADDRESS.DISTRICT.eq(filter.getAddressDistrict())));
    }

    /**
     * A setter bean with a nested bean member, bound by a dotted {@code argMapping}: films whose
     * title starts with the filter's, within the nested length range.
     */
    public static Condition filmSearchBean(Table<?> table, FilmSearchFilter search) {
        if (search == null) {
            return DSL.noCondition();
        }
        Condition condition = DSL.noCondition();
        if (search.getTitle() != null) {
            condition = condition.and(table.field(Film.FILM.TITLE).startsWith(search.getTitle()));
        }
        var length = search.getLength();
        if (length != null && length.getMin() != null) {
            condition = condition.and(table.field(Film.FILM.LENGTH).ge(length.getMin().shortValue()));
        }
        if (length != null && length.getMax() != null) {
            condition = condition.and(table.field(Film.FILM.LENGTH).le(length.getMax().shortValue()));
        }
        return condition;
    }
}
