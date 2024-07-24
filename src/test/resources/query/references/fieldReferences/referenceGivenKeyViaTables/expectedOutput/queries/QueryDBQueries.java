package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Payment;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Payment paymentForQuery(DSLContext ctx, String id, SelectionSet select) {
        var payment_rental_left = PAYMENT.rental().as("rental_61302898");
        var rental_61302898_inventory_left = payment_rental_left.inventory().as("inventory_2622920513");
        var inventory_2622920513_film_left = rental_61302898_inventory_left.film().as("film_2895304902");
        var film_2895304902_filmoriginallanguageidfkey_left = inventory_2622920513_film_left.filmOriginalLanguageIdFkey().as("filmOriginalLanguageIdFkey_458113126");
        return ctx
                .select(
                        DSL.row(
                                PAYMENT.getId(),
                                select.optional("originalLanguageName", film_2895304902_filmoriginallanguageidfkey_left.NAME)
                        ).mapping(Functions.nullOnAllNull(Payment::new))
                )
                .from(PAYMENT)
                .leftJoin(payment_rental_left)
                .leftJoin(rental_61302898_inventory_left)
                .leftJoin(inventory_2622920513_film_left)
                .leftJoin(film_2895304902_filmoriginallanguageidfkey_left)
                .where(PAYMENT.ID.eq(id))
                .fetchOne(it -> it.into(Payment.class));
    }
}
