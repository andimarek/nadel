package graphql.nadel.engine;

import graphql.GraphQLError;
import graphql.execution.ExecutionContext;
import graphql.language.Argument;
import graphql.language.Field;
import graphql.language.Node;
import graphql.nadel.hooks.ServiceExecutionHooks;
import graphql.nadel.normalized.NormalizedQueryField;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static graphql.introspection.Introspection.TypeNameMetaFieldDef;
import static graphql.nadel.dsl.NodeId.getId;
import static graphql.nadel.util.FpKit.flatMap;
import static graphql.nadel.util.FpKit.map;

public class AsyncIsFieldForbidden {

    private final Map<NormalizedQueryField, GraphQLError> fieldsToErrors = new ConcurrentHashMap<>();
    private final ServiceExecutionHooks serviceExecutionHooks;
    private final ExecutionContext executionContext;
    private final NadelContext nadelContext;
    private final List<Argument> hydrationRootArguments;

    public AsyncIsFieldForbidden(
            ServiceExecutionHooks serviceExecutionHooks,
            ExecutionContext executionContext,
            NadelContext nadelContext,
            List<Argument> hydrationRootArguments
    ) {
        this.serviceExecutionHooks = serviceExecutionHooks;
        this.executionContext = executionContext;
        this.nadelContext = nadelContext;
        this.hydrationRootArguments = hydrationRootArguments;
    }

    public CompletableFuture<Map<NormalizedQueryField, GraphQLError>> getForbiddenFields(Node<?> root) {
        List<NormalizedQueryField> normalisedFields = getNormalisedFields(root);
        List<CompletableFuture<Void>> visitNormalisedFields = map(normalisedFields, this::visitField);
        return allOf(visitNormalisedFields)
                .thenApply(ignored -> fieldsToErrors);
    }

    private List<NormalizedQueryField> getNormalisedFields(Node<?> node) {
        if (node instanceof Field) {
            return nadelContext.getNormalizedOverallQuery()
                    .getNormalizedFieldsByFieldId(getId(node));
        }
        return flatMap(node.getChildren(), this::getNormalisedFields);
    }

    private CompletableFuture<Void> visitField(NormalizedQueryField field) {
        if (field.getName().equals(TypeNameMetaFieldDef.getName())) {
            return CompletableFuture.completedFuture(null);
        }
        return serviceExecutionHooks.isFieldForbidden(field, hydrationRootArguments, executionContext, nadelContext.getUserSuppliedContext())
                .thenCompose(graphQLError -> {
                    if (graphQLError.isPresent()) {
                        fieldsToErrors.put(field, graphQLError.get());
                        return CompletableFuture.completedFuture(null);
                    }
                    List<CompletableFuture<Void>> visitChildren = map(field.getChildren(), this::visitField);
                    return allOf(visitChildren);
                });
    }

    private static CompletableFuture<Void> allOf(List<CompletableFuture<Void>> cfs) {
        return CompletableFuture.allOf(cfs.toArray(new CompletableFuture<?>[0]));
    }
}
