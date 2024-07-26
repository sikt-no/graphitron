package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Language;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class PaymentDBQueries {
    public static Map<String, Language> originalLanguageForPayment(DSLContext ctx,
            Set<String> paymentIds, SelectionSet select) {
        var payment_rental_left = PAYMENT.rental().as("rental_61302898");
        var rental_61302898_inventory_left = payment_rental_left.inventory().as("inventory_2622920513");
        var inventory_2622920513_film_left = rental_61302898_inventory_left.film().as("film_2895304902");
        var film_2895304902_filmoriginallanguageidfkey_left = inventory_2622920513_film_left.filmOriginalLanguageIdFkey().as("filmOriginalLanguageIdFkey_458113126");
        return ctx
                .select(
                        PAYMENT.getId(),
                        DSL.row(
                                film_2895304902_filmoriginallanguageidfkey_left.getId(),
                                select.optional("name", film_2895304902_filmoriginallanguageidfkey_left.NAME)
                        ).mapping(Functions.nullOnAllNull(Language::new))
                )
                .from(PAYMENT)
                .leftJoin(payment_rental_left)
                .leftJoin(rental_61302898_inventory_left)
                .leftJoin(inventory_2622920513_film_left)
                .leftJoin(film_2895304902_filmoriginallanguageidfkey_left)
                .where(PAYMENT.hasIds(paymentIds))
                .orderBy(film_2895304902_filmoriginallanguageidfkey_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
