package fake.code.example.package.resolvers.query;

import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import fake.graphql.example.api.ProgramStudierettResolver;
import fake.graphql.example.model.Node;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import fake.code.example.package.queries.query.KullDBQueries;
package.queries.query.ProgramStudierettDBQueries;
import no.fellesstudentsystem.kjerneapi.Tables;
import org.jooq.DSLContext;

public class ProgramStudierettGeneratedResolver implements ProgramStudierettResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private KullDBQueries kullDBQueries;

    @Inject
    private ProgramStudierettDBQueries programStudierettDBQueries;

    @Override
    public CompletableFuture<Node> referertNode(ProgramStudierett programStudierett, String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        String tablePartOfId = FieldHelperHack.getTablePartOf(id);

        if (tablePartOfId.equals(Tables.KULL.getViewId().toString())) {
            return DataFetcher.loadInterface(env, tablePartOfId, id, (ids, selectionSet) -> kullDBQueries.loadKullByIdsAsReferertNode(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Tables.STUDIERETT.getViewId().toString())) {
            return DataFetcher.loadInterface(env, tablePartOfId, id, (ids, selectionSet) -> programStudierettDBQueries.loadProgramStudierettByIdsAsReferertNode(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for id with prefix " + tablePartOfId);
    }
}
