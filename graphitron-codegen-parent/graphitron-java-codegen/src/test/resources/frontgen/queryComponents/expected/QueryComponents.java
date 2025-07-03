import fake.code.generated.frontend.CustomerQueryComponent;
import fake.code.generated.frontend.FilmQueryComponent;
import fake.code.generated.frontend.LanguageQueryComponent;
import fake.code.generated.frontend.PaymentQueryComponent;
import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;

import java.util.List;

public class QueryComponents {
    public static List<QueryComponent> getComponents(QueryBackedView view) {
        return List.of(
                new CustomerQueryComponent().createComponent(view),
                new FilmQueryComponent().createComponent(view),
                new LanguageQueryComponent().createComponent(view),
                new PaymentQueryComponent().createComponent(view)
        );
    }
}