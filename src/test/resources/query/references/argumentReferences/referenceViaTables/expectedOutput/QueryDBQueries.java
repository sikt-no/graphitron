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
    public static Payment paymentForQuery(DSLContext ctx, String id, String title,
            SelectionSet select) {
        var payment_rental_left = PAYMENT.rental().as("rental_61302898");
        var rental_61302898_inventory_left = payment_rental_left.inventory().as("inventory_2622920513");
        var inventory_2622920513_film_left = rental_61302898_inventory_left.film().as("film_2895304902");
        return ctx
                .select(
                        DSL.row(
                                PAYMENT.getId()
                        ).mapping(Functions.nullOnAllNull(Payment::new))
                )
                .from(PAYMENT)
                .leftJoin(payment_rental_left)
                .leftJoin(rental_61302898_inventory_left)
                .leftJoin(inventory_2622920513_film_left)
                .where(PAYMENT.ID.eq(id))
                .and(inventory_2622920513_film_left.TITLE.eq(title))
                .fetchOne(it -> it.into(Payment.class));
    }
}
