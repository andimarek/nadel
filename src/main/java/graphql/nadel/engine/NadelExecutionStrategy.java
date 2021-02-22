package graphql.nadel.engine;

import graphql.GraphQLError;
import graphql.Internal;
import graphql.execution.Async;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ExecutionStepInfoFactory;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.execution.nextgen.FieldSubSelection;
import graphql.nadel.BenchmarkContext;
import graphql.nadel.FieldInfo;
import graphql.nadel.FieldInfos;
import graphql.nadel.Operation;
import graphql.nadel.Service;
import graphql.nadel.dsl.NodeId;
import graphql.nadel.engine.transformation.FieldTransformation;
import graphql.nadel.engine.transformation.TransformationMetadata.NormalizedFieldAndError;
import graphql.nadel.hooks.CreateServiceContextParams;
import graphql.nadel.hooks.ResultRewriteParams;
import graphql.nadel.hooks.ServiceExecutionHooks;
import graphql.nadel.instrumentation.NadelInstrumentation;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.normalized.NormalizedQueryFromAst;
import graphql.nadel.result.ExecutionResultNode;
import graphql.nadel.result.ResultComplexityAggregator;
import graphql.nadel.result.RootExecutionResultNode;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static graphql.Assert.assertNotEmpty;
import static graphql.Assert.assertNotNull;
import static graphql.nadel.result.RootExecutionResultNode.newRootExecutionResultNode;
import static graphql.nadel.util.FpKit.map;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;

@Internal
public class NadelExecutionStrategy {

    private final ExecutionStepInfoFactory executionStepInfoFactory = new ExecutionStepInfoFactory();
    private final ServiceResultNodesToOverallResult serviceResultNodesToOverallResult = new ServiceResultNodesToOverallResult();
    private final OverallQueryTransformer queryTransformer = new OverallQueryTransformer();
    private final ServiceResultToResultNodes resultToResultNode = new ServiceResultToResultNodes();

    private final FieldInfos fieldInfos;
    private final GraphQLSchema overallSchema;
    private final ServiceExecutor serviceExecutor;
    private final HydrationInputResolver hydrationInputResolver;
    private final ServiceExecutionHooks serviceExecutionHooks;
    private final ExecutionPathSet hydrationInputPaths;

    private static final Logger log = LoggerFactory.getLogger(NadelExecutionStrategy.class);

    public NadelExecutionStrategy(List<Service> services,
                                  FieldInfos fieldInfos,
                                  GraphQLSchema overallSchema,
                                  NadelInstrumentation instrumentation,
                                  ServiceExecutionHooks serviceExecutionHooks) {
        this.overallSchema = overallSchema;
        assertNotEmpty(services);
        this.fieldInfos = fieldInfos;
        this.serviceExecutionHooks = serviceExecutionHooks;
        this.serviceExecutor = new ServiceExecutor(instrumentation);
        this.hydrationInputPaths = new ExecutionPathSet();
        this.hydrationInputResolver = new HydrationInputResolver(services, overallSchema, serviceExecutor, serviceExecutionHooks, hydrationInputPaths);
    }

    public CompletableFuture<RootExecutionResultNode> execute(ExecutionContext executionContext, FieldSubSelection fieldSubSelection, ResultComplexityAggregator resultComplexityAggregator) {
        long startTime = System.currentTimeMillis();
        ExecutionStepInfo rootExecutionStepInfo = fieldSubSelection.getExecutionStepInfo();
        NadelContext nadelContext = getNadelContext(executionContext);
        Operation operation = Operation.fromAst(executionContext.getOperationDefinition().getOperation());
        CompletableFuture<List<OneServiceExecution>> oneServiceExecutionsCF = prepareServiceExecution(executionContext, fieldSubSelection, rootExecutionStepInfo);

        return oneServiceExecutionsCF.thenCompose(oneServiceExecutions -> {
            Map<Service, Object> serviceContextsByService = serviceContextsByService(oneServiceExecutions);
            List<CompletableFuture<RootExecutionResultNode>> resultNodes =
                    executeTopLevelFields(executionContext, nadelContext, operation, oneServiceExecutions, resultComplexityAggregator, hydrationInputPaths);

            CompletableFuture<RootExecutionResultNode> rootResult = mergeTrees(resultNodes);
            return rootResult
                    .thenCompose(
                            //
                            // all the nodes that are hydrated need to make new service calls to get their eventual value
                            //
                            rootExecutionResultNode -> hydrationInputResolver.resolveAllHydrationInputs(executionContext, rootExecutionResultNode, serviceContextsByService, resultComplexityAggregator)
                                    .thenApply(resultNode -> (RootExecutionResultNode) resultNode))
                    .whenComplete((resultNode, throwable) -> {
                        possiblyLogException(resultNode, throwable);
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        log.debug("NadelExecutionStrategy time: {} ms, executionId: {}", elapsedTime, executionContext.getExecutionId());
                    });
        }).whenComplete(this::possiblyLogException);
    }

    private Map<Service, Object> serviceContextsByService(List<OneServiceExecution> oneServiceExecutions) {
        Map<Service, Object> result = new LinkedHashMap<>();
        for (OneServiceExecution oneServiceExecution : oneServiceExecutions) {
            result.put(oneServiceExecution.service, oneServiceExecution.serviceContext);
        }
        return result;
    }

    private CompletableFuture<List<OneServiceExecution>> prepareServiceExecution(ExecutionContext executionCtx, FieldSubSelection fieldSubSelection, ExecutionStepInfo rootExecutionStepInfo) {
        List<CompletableFuture<OneServiceExecution>> result = new ArrayList<>();
        for (MergedField mergedField : fieldSubSelection.getMergedSelectionSet().getSubFieldsList()) {
            ExecutionStepInfo fieldExecutionStepInfo = executionStepInfoFactory.newExecutionStepInfoForSubField(executionCtx, mergedField, rootExecutionStepInfo);
            Service service = getServiceForFieldDefinition(fieldExecutionStepInfo.getFieldDefinition());

            CreateServiceContextParams parameters = CreateServiceContextParams.newParameters()
                    .from(executionCtx)
                    .service(service)
                    .executionStepInfo(fieldExecutionStepInfo)
                    .build();

            CompletableFuture<Object> serviceContextCF = serviceExecutionHooks.createServiceContext(parameters);
            CompletableFuture<OneServiceExecution> serviceCF = serviceContextCF.thenApply(serviceContext -> new OneServiceExecution(service, serviceContext, fieldExecutionStepInfo));
            result.add(serviceCF);
        }
        return Async.each(result);
    }

    private List<CompletableFuture<RootExecutionResultNode>> executeTopLevelFields(
            ExecutionContext executionContext,
            NadelContext nadelContext,
            Operation operation,
            List<OneServiceExecution> oneServiceExecutions,
            ResultComplexityAggregator resultComplexityAggregator,
            Set<ResultPath> hydrationInputPaths) {

        List<CompletableFuture<RootExecutionResultNode>> resultNodes = new ArrayList<>();
        for (OneServiceExecution oneServiceExecution : oneServiceExecutions) {
            Service service = oneServiceExecution.service;
            ExecutionStepInfo esi = oneServiceExecution.stepInfo;
            Object serviceContext = oneServiceExecution.serviceContext;

            String operationName = buildOperationName(service, executionContext);
            MergedField mergedField = esi.getField();

            //
            // take the original query and transform it into the underlying query needed for that top level field
            //
            GraphQLSchema underlyingSchema = service.getUnderlyingSchema();
            CompletableFuture<QueryTransformationResult> transformedQueryCF = queryTransformer
                    .transformMergedFields(executionContext, underlyingSchema, operationName, operation, singletonList(mergedField), serviceExecutionHooks, service, serviceContext);

            resultNodes.add(transformedQueryCF.thenCompose(transformedQuery -> {
                Map<String, FieldTransformation> fieldIdToTransformation = transformedQuery.getFieldIdToTransformation();
                Map<String, String> typeRenameMappings = transformedQuery.getTypeRenameMappings();
                Map<FieldTransformation, String> transformationToFieldId = transformedQuery.getTransformationToFieldId();

                ExecutionContext newExecutionContext = buildServiceVariableOverrides(executionContext, transformedQuery.getVariableValues());

                Optional<GraphQLError> maybeFieldForbiddenError = getForbiddenTopLevelFieldError(esi, transformedQuery);
                // If field is forbidden, do NOT execute it
                if (maybeFieldForbiddenError.isPresent()) {
                    GraphQLError fieldForbiddenError = maybeFieldForbiddenError.get();
                    return CompletableFuture.completedFuture(getForbiddenTopLevelFieldResult(nadelContext, esi, fieldForbiddenError));
                }

                CompletableFuture<RootExecutionResultNode> serviceCallResult = serviceExecutor
                        .execute(newExecutionContext, transformedQuery, service, operation, serviceContext, false);

                CompletableFuture<RootExecutionResultNode> convertedResult = serviceCallResult
                        .thenApply(resultNode -> {
                            if (nadelContext.getUserSuppliedContext() instanceof BenchmarkContext) {
                                BenchmarkContext benchmarkContext = (BenchmarkContext) nadelContext.getUserSuppliedContext();
                                benchmarkContext.serviceResultNodesToOverallResult.executionId = newExecutionContext.getExecutionId();
                                benchmarkContext.serviceResultNodesToOverallResult.resultNode = resultNode;
                                benchmarkContext.serviceResultNodesToOverallResult.overallSchema = overallSchema;
                                benchmarkContext.serviceResultNodesToOverallResult.correctRootNode = resultNode;
                                benchmarkContext.serviceResultNodesToOverallResult.fieldIdToTransformation = fieldIdToTransformation;
                                benchmarkContext.serviceResultNodesToOverallResult.typeRenameMappings = typeRenameMappings;
                                benchmarkContext.serviceResultNodesToOverallResult.nadelContext = nadelContext;
                                benchmarkContext.serviceResultNodesToOverallResult.transformationMetadata = transformedQuery.getRemovedFieldMap();
                            }
                            return (RootExecutionResultNode) serviceResultNodesToOverallResult
                                    .convert(newExecutionContext.getExecutionId(),
                                            resultNode,
                                            overallSchema,
                                            resultNode,
                                            fieldIdToTransformation,
                                            transformationToFieldId,
                                            typeRenameMappings,
                                            nadelContext,
                                            transformedQuery.getRemovedFieldMap(),
                                            hydrationInputPaths);
                        });

                //set the result node count for this service
                convertedResult.thenAccept(rootExecutionResultNode -> {
                    resultComplexityAggregator.incrementServiceNodeCount(service.getName(), rootExecutionResultNode.getTotalNodeCount());
                    resultComplexityAggregator.incrementFieldRenameCount(rootExecutionResultNode.getTotalFieldRenameCount());
                    resultComplexityAggregator.incrementTypeRenameCount(rootExecutionResultNode.getTotalTypeRenameCount());
                });

                CompletableFuture<RootExecutionResultNode> serviceResult = convertedResult
                        .thenCompose(rootResultNode -> {
                            ResultRewriteParams resultRewriteParams = ResultRewriteParams.newParameters()
                                    .from(executionContext)
                                    .service(service)
                                    .serviceContext(serviceContext)
                                    .executionStepInfo(esi)
                                    .resultNode(rootResultNode)
                                    .build();
                            return serviceExecutionHooks.resultRewrite(resultRewriteParams);
                        });

                return serviceResult;
            }));
        }
        return resultNodes;
    }

    /**
     * A top level field error is present if the field should not be executed and an
     * error should be put in lieu. We check this before calling out to the underlying
     * service. This error is usually present when the field has been forbidden by
     * {@link ServiceExecutionHooks#isFieldForbidden(NormalizedQueryField, Object)}.
     *
     * @param esi              the {@link ExecutionStepInfo} for the top level field
     * @param transformedQuery the query for that specific top level field
     * @return a {@link GraphQLError} if the field was forbidden before, otherwise empty
     */
    private Optional<GraphQLError> getForbiddenTopLevelFieldError(ExecutionStepInfo esi, QueryTransformationResult transformedQuery) {
        GraphQLFieldDefinition fieldDefinition = esi.getFieldDefinition();
        String topLevelFieldId = NodeId.getId(fieldDefinition);
        return transformedQuery.getRemovedFieldMap()
                .getRemovedFieldById(topLevelFieldId)
                .map(NormalizedFieldAndError::getError);
    }

    /**
     * Creates the {@link RootExecutionResultNode} for a forbidden field. In that
     * case the underlying service should not be called and we would fill the
     * overall GraphQL response with an error for that specific top level field.
     *
     * @param nadelContext context for the execution
     * @param esi          the {@link ExecutionStepInfo} for the top level field
     * @param error        the {@link GraphQLError} to put in the overall response
     * @return {@link RootExecutionResultNode} with the specified top level field nulled out and with the given GraphQL error
     */
    private RootExecutionResultNode getForbiddenTopLevelFieldResult(NadelContext nadelContext, ExecutionStepInfo esi, GraphQLError error) {
        String topLevelFieldResultKey = esi.getResultKey();
        NormalizedQueryFromAst overallQuery = nadelContext.getNormalizedOverallQuery();
        NormalizedQueryField topLevelField = overallQuery.getTopLevelField(topLevelFieldResultKey);
        return resultToResultNode.createResultWithNullTopLevelField(overallQuery, topLevelField, singletonList(error), emptyMap());
    }

    @SuppressWarnings("unused")
    private <T> void possiblyLogException(T result, Throwable exception) {
        if (exception != null) {
            exception.printStackTrace();
        }
    }

    private ExecutionContext buildServiceVariableOverrides(ExecutionContext executionContext, Map<String, Object> overrideVariables) {
        if (!overrideVariables.isEmpty()) {
            Map<String, Object> newVariables = mergeVariables(executionContext.getVariables(), overrideVariables);
            executionContext = executionContext.transform(builder -> builder.variables(newVariables));
        }
        return executionContext;
    }

    private Map<String, Object> mergeVariables(Map<String, Object> variables, Map<String, Object> overrideVariables) {
        Map<String, Object> newVariables = new LinkedHashMap<>(variables);
        newVariables.putAll(overrideVariables);
        return newVariables;
    }

    private CompletableFuture<RootExecutionResultNode> mergeTrees(List<CompletableFuture<RootExecutionResultNode>> resultNodes) {
        return Async.each(resultNodes).thenApply(rootNodes -> {
            List<ExecutionResultNode> mergedChildren = new ArrayList<>();
            List<GraphQLError> errors = new ArrayList<>();
            map(rootNodes, RootExecutionResultNode::getChildren).forEach(mergedChildren::addAll);
            map(rootNodes, RootExecutionResultNode::getErrors).forEach(errors::addAll);
            Map<String, Object> extensions = new LinkedHashMap<>();
            rootNodes.forEach(node -> extensions.putAll(node.getExtensions()));
            return newRootExecutionResultNode()
                    .children(mergedChildren)
                    .errors(errors)
                    .extensions(extensions)
                    .build();
        });
    }

    private static class OneServiceExecution {

        public OneServiceExecution(Service service, Object serviceContext, ExecutionStepInfo stepInfo) {
            this.service = service;
            this.serviceContext = serviceContext;
            this.stepInfo = stepInfo;
        }

        final Service service;
        final Object serviceContext;
        final ExecutionStepInfo stepInfo;
    }

    public static class ExecutionPathSet extends LinkedHashSet<ResultPath> {
        @Override
        public boolean add(ResultPath executionPath) {
            ResultPath path = executionPath.getParent();
            while (path != null) {
                super.add(path);
                path = path.getParent();
            }
            return super.add(executionPath);
        }
    }


    private Service getServiceForFieldDefinition(GraphQLFieldDefinition fieldDefinition) {
        FieldInfo info = assertNotNull(fieldInfos.getInfo(fieldDefinition), () -> String.format("no field info for field %s", fieldDefinition.getName()));
        return info.getService();
    }

    private String buildOperationName(Service service, ExecutionContext executionContext) {
        // to help with downstream debugging we put our name and their name in the operation
        NadelContext nadelContext = executionContext.getContext();
        if (nadelContext.getOriginalOperationName() != null) {
            return format("nadel_2_%s_%s", service.getName(), nadelContext.getOriginalOperationName());
        } else {
            return format("nadel_2_%s", service.getName());
        }
    }

    private NadelContext getNadelContext(ExecutionContext executionContext) {
        return executionContext.getContext();
    }
}


