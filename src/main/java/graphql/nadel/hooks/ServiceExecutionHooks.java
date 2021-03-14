package graphql.nadel.hooks;

import graphql.GraphQLError;
import graphql.PublicSpi;
import graphql.execution.ExecutionContext;
import graphql.language.Argument;
import graphql.nadel.engine.HooksVisitArgumentValueEnvironment;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.result.RootExecutionResultNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * These hooks allow you to change the way service execution happens
 */
@PublicSpi
public interface ServiceExecutionHooks {

    /**
     * Called per top level field for a service.  This allows you to create a "context" object that will be passed into further calls.
     *
     * @param params the parameters to this call
     * @return an async context object of your choosing
     */
    default CompletableFuture<Object> createServiceContext(CreateServiceContextParams params) {
        return CompletableFuture.completedFuture(null);
    }

    default NewVariableValue visitArgumentValueInQuery(HooksVisitArgumentValueEnvironment env) {
        return null;
    }

    /**
     * Called to determine whether a field is forbidden which means it should be omitted from the query to the underlying service.
     * When a field is forbidden, the field is set to null and a GraphQL error is inserted into the overall response.
     *
     * @param normalizedField        the field in question
     * @param hydrationRootArguments parameters supplied to the top level field of the hydration
     * @param executionContext       for context into the query execution
     * @param userSuppliedContext    the context supplied to Nadel in {@link graphql.nadel.NadelExecutionInput}
     * @return an error if the field should be omitted, empty optional otherwise
     */
    default CompletableFuture<Optional<GraphQLError>> isFieldForbidden(
            NormalizedQueryField normalizedField,
            List<Argument> hydrationRootArguments,
            ExecutionContext executionContext,
            Object userSuppliedContext
    ) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Called to allow a service to post process the service result in some fashion.
     *
     * @param params the parameters to this call
     * @return an async possible result node
     */
    default CompletableFuture<RootExecutionResultNode> resultRewrite(ResultRewriteParams params) {
        return CompletableFuture.completedFuture(params.getResultNode());
    }
}
