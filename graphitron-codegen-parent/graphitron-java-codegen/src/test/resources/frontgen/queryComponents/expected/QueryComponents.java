import fake.code.generated.frontend.AddressesByPostalCodeQueryComponent;
import fake.code.generated.frontend.CustomersQueryComponent;
import fake.code.generated.frontend.FilmsQueryComponent;
import fake.code.generated.frontend.LanguagesQueryComponent;
import fake.code.generated.frontend.PaymentsQueryComponent;
import java.util.List;
import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;

public class QueryComponents {
    public static List<QueryComponent> getComponents(QueryBackedView view) {
        return List.of(
                new CustomersQueryComponent().createComponent(view),
                new FilmsQueryComponent().createComponent(view),
                new LanguagesQueryComponent().createComponent(view),
                new PaymentsQueryComponent().createComponent(view),
                new AddressesByPostalCodeQueryComponent().createComponent(view)
        );
    }
}
