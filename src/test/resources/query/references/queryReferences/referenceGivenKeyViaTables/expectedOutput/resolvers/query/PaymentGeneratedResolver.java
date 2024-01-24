package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.PaymentDBQueries;
import fake.graphql.example.package.api.PaymentResolver;
import fake.graphql.example.package.model.Language;
import fake.graphql.example.package.model.Payment;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class PaymentGeneratedResolver implements PaymentResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private PaymentDBQueries paymentDBQueries;

    @Override
    public CompletableFuture<Language> originalLanguage(Payment payment,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, Language> loader = DataLoaders.getDataLoader(env, "originalLanguageForPayment", (ids, selectionSet) -> paymentDBQueries.originalLanguageForPayment(ctx, ids, selectionSet));
        return DataLoaders.load(loader, payment.getId(), env);
    }
}