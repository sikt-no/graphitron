package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class PaymentDBQueries {
    public static Map<String, Film> filmForPayment(DSLContext ctx, Set<String> paymentIds,
            SelectionSet select) {
        var payment_rental_left = PAYMENT.rental().as("rental_61302898");
        var rental_61302898_inventory_left = payment_rental_left.inventory().as("inventory_2622920513");
        var inventory_2622920513_film_left = rental_61302898_inventory_left.film().as("film_2895304902");
        return ctx
                .select(
                        PAYMENT.getId(),
                        DSL.row(
                                inventory_2622920513_film_left.getId(),
                                select.optional("title", inventory_2622920513_film_left.TITLE)
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(PAYMENT)
                .leftJoin(payment_rental_left)
                .leftJoin(rental_61302898_inventory_left)
                .leftJoin(inventory_2622920513_film_left)
                .where(PAYMENT.hasIds(paymentIds))
                .orderBy(inventory_2622920513_film_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
