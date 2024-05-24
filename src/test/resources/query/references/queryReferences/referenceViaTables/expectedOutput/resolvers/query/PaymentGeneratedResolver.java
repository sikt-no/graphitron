package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.PaymentDBQueries;
import fake.graphql.example.api.PaymentResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Payment;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class PaymentGeneratedResolver implements PaymentResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private PaymentDBQueries paymentDBQueries;

    @Override
    public CompletableFuture<Film> film(Payment payment, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load("filmForPayment", payment.getId(), (ctx, ids, selectionSet) -> paymentDBQueries.filmForPayment(ctx, ids, selectionSet));
    }
}