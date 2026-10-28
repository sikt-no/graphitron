package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Sort keys an {@code @order} or {@code @defaultOrder} entry names through {@code FieldSort.method:}:
 * the film's table in, one value per film out, derived here from a to-many relation that a
 * {@code path:} could not follow.
 */
public final class FilmSortKeys {

    private FilmSortKeys() {}

    /**
     * The store holding the film's most recently stocked copy: the {@code inventory} row with the
     * highest id decides, so the rule for which related row counts lives here once. A film with no
     * inventory gets {@code NULL}.
     */
    public static Field<Integer> latestStockStore(Film film) {
        var stock = Tables.INVENTORY.as("latest_stock");
        return DSL.field(DSL.select(stock.STORE_ID)
            .from(stock)
            .where(stock.FILM_ID.eq(film.FILM_ID))
            .orderBy(stock.INVENTORY_ID.desc())
            .limit(1));
    }
}
