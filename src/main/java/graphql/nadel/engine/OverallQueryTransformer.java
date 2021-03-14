package graphql.nadel.engine;

import graphql.Internal;
import graphql.execution.Async;
import graphql.execution.ExecutionContext;
import graphql.execution.MergedField;
import graphql.language.Argument;
import graphql.language.AstNodeAdapter;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.language.OperationDefinition;
import graphql.language.SelectionSet;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import graphql.nadel.Operation;
import graphql.nadel.Service;
import graphql.nadel.dsl.TypeMappingDefinition;
import graphql.nadel.engine.transformation.OverallTypeInfo;
import graphql.nadel.engine.transformation.OverallTypeInformation;
import graphql.nadel.engine.transformation.RecordOverallTypeInformation;
import graphql.nadel.engine.transformation.TransformationMetadata;
import graphql.nadel.hooks.ServiceExecutionHooks;
import graphql.nadel.util.FpKit;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.TypeInfo;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TraverserVisitorStub;
import graphql.util.TreeTransformer;
import graphql.util.TreeTransformerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static graphql.language.OperationDefinition.newOperationDefinition;
import static graphql.language.SelectionSet.newSelectionSet;
import static graphql.nadel.dsl.NodeId.getId;
import static graphql.nadel.engine.UnderlyingTypeContext.newUnderlyingTypeContext;
import static graphql.nadel.util.FpKit.map;
import static graphql.nadel.util.Util.getTypeMappingDefinitionFor;
import static graphql.util.FpKit.groupingByUniqueKey;

@Internal
public class OverallQueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(OverallQueryTransformer.class);

    private final RecordOverallTypeInformation recordOverallTypeInformation = new RecordOverallTypeInformation();


    CompletableFuture<QueryTransformationResult> transformHydratedTopLevelField(
            ExecutionContext executionContext,
            GraphQLSchema underlyingSchema,
            String operationName,
            Operation operation,
            Field rootField,
            GraphQLCompositeType topLevelFieldTypeOverall,
            ServiceExecutionHooks serviceExecutionHooks,
            Service service,
            Object serviceContext,
            boolean isSynthetic
    ) {
        long startTime = System.currentTimeMillis();
        Set<String> referencedFragmentNames = new LinkedHashSet<>();
        Map<String, VariableDefinition> referencedVariables = new LinkedHashMap<>();
        Map<String, Object> variableValues = new LinkedHashMap<>(executionContext.getVariables());
        TransformationMetadata removedFieldMap = new TransformationMetadata();
        TransformationState transformations = new TransformationState();

        NadelContext nadelContext = executionContext.getContext();

        SelectionSet selectionSet = rootField.getSelectionSet();
        Field topLevelField = rootField;
        if (isSynthetic) {
            topLevelField = (Field) selectionSet.getSelections().get(0);
            selectionSet = topLevelField.getSelectionSet();
        }

        Map<String, VariableDefinition> variableDefinitionMap = FpKit.getByName(executionContext.getOperationDefinition().getVariableDefinitions(), VariableDefinition::getName);
        NodeVisitorStub collectReferencedVariables = new NodeVisitorStub() {
            @Override
            public TraversalControl visitVariableReference(VariableReference variableReference, TraverserContext<Node> context) {
                String name = variableReference.getName();
                referencedVariables.put(name, variableDefinitionMap.get(name));
                return super.visitVariableReference(variableReference, context);
            }
        };

        NodeTraverser nodeTraverser = new NodeTraverser();
        nodeTraverser.depthFirst(collectReferencedVariables, topLevelField);


        CompletableFuture<SelectionSet> topLevelFieldSelectionSetCF = transformNode(
                executionContext,
                underlyingSchema,
                selectionSet,
                topLevelFieldTypeOverall,
                referencedFragmentNames,
                referencedVariables,
                nadelContext,
                serviceExecutionHooks,
                variableValues,
                service,
                serviceContext,
                removedFieldMap,
                transformations,
                topLevelField.getArguments()
        );
        Field finalTopLevelField = topLevelField;
        return topLevelFieldSelectionSetCF.thenCompose(topLevelFieldSelectionSet -> {

            Field transformedRootField = finalTopLevelField.transform(builder -> builder.selectionSet(topLevelFieldSelectionSet));

            Field maybeTransformedRootField = ArtificialFieldUtils.maybeAddUnderscoreTypeName(nadelContext, transformedRootField, topLevelFieldTypeOverall);
            if (maybeTransformedRootField != transformedRootField) {
                transformations.addHintTypename(maybeTransformedRootField.getName());
            }
            transformedRootField = maybeTransformedRootField;
            transformedRootField = ArtificialFieldUtils.maybeAddEmptySelectionSetUnderscoreTypeName(nadelContext, transformedRootField, topLevelFieldTypeOverall);

            if (isSynthetic) {
                Field tempTransformedRootLevelField = transformedRootField;
                transformedRootField = rootField.transform(builder -> builder.selectionSet(newSelectionSet().selection(tempTransformedRootLevelField).build()));
            }

            List<VariableDefinition> variableDefinitions = buildReferencedVariableDefinitions(referencedVariables, executionContext.getGraphQLSchema(), transformations.getTypeRenameMappings());
            List<String> referencedVariableNames = new ArrayList<>(referencedVariables.keySet());

            CompletableFuture<Map<String, FragmentDefinition>> transformedFragmentsCF = transformFragments(executionContext,
                    underlyingSchema,
                    executionContext.getFragmentsByName(),
                    referencedFragmentNames,
                    referencedVariables,
                    serviceExecutionHooks,
                    variableValues,
                    service,
                    serviceContext,
                    removedFieldMap,
                    transformations);
            Field finalTransformedRootField = transformedRootField;
            return transformedFragmentsCF.thenApply(transformedFragments -> {

                SelectionSet newOperationSelectionSet = newSelectionSet().selection(finalTransformedRootField).build();
                OperationDefinition operationDefinition = newOperationDefinition()
                        .name(operationName)
                        .operation(operation.getAstOperation())
                        .selectionSet(newOperationSelectionSet)
                        .variableDefinitions(variableDefinitions)
                        .build();

                Document newDocument = newDocument(operationDefinition, transformedFragments);

                MergedField transformedMergedField = MergedField.newMergedField(finalTransformedRootField).build();
                long elapsedTime = System.currentTimeMillis() - startTime;
                log.debug("OverallQueryTransformer.transformHydratedTopLevelField time: {}, executionId: {}", elapsedTime, executionContext.getExecutionId());
                return new QueryTransformationResult(
                        newDocument,
                        operationDefinition,
                        Collections.singletonList(transformedMergedField),
                        referencedVariableNames,
                        transformedFragments,
                        variableValues,
                        removedFieldMap,
                        transformations);
            });
        });

    }

    CompletableFuture<QueryTransformationResult> transformMergedFields(
            ExecutionContext executionContext,
            GraphQLSchema underlyingSchema,
            String operationName, Operation operation,
            List<MergedField> mergedFields,
            ServiceExecutionHooks serviceExecutionHooks,
            Service service,
            Object serviceContext
    ) {
        long startTime = System.currentTimeMillis();
        NadelContext nadelContext = executionContext.getContext();
        Set<String> fragmentsDirectlyReferenced = new LinkedHashSet<>();
        Map<String, VariableDefinition> referencedVariables = new LinkedHashMap<>();
        Map<String, Object> variableValues = new LinkedHashMap<>(executionContext.getVariables());
        TransformationMetadata removedFieldMap = new TransformationMetadata();
        TransformationState transformations = new TransformationState();

        List<CompletableFuture<Field>> transformedFieldsCF = new ArrayList<>();

        for (MergedField mergedField : mergedFields) {
            List<Field> fields = mergedField.getFields();

            List<CompletableFuture<Field>> transformedCF = map(fields, field -> {
                GraphQLObjectType rootType = operation.getRootType(executionContext.getGraphQLSchema());

                CompletableFuture<Field> newFieldCF = transformNode(
                        executionContext,
                        underlyingSchema,
                        field,
                        rootType,
                        fragmentsDirectlyReferenced,
                        referencedVariables,
                        nadelContext,
                        serviceExecutionHooks,
                        variableValues,
                        service,
                        serviceContext,
                        removedFieldMap,
                        transformations,
                        Collections.emptyList()
                );
                return newFieldCF.thenApply(newField -> {
                    // Case happens when the high level field is removed
                    if (newField == null) {
                        newField = field.transform(builder -> builder.selectionSet(SelectionSet.newSelectionSet().build()));
                    }
                    // if all child fields of the high level field are removed then the top-level field is nulled
                    GraphQLOutputType fieldType = rootType.getFieldDefinition(field.getName()).getType();

                    Field maybeNewField = ArtificialFieldUtils.maybeAddUnderscoreTypeName(nadelContext, newField, fieldType);
                    if (maybeNewField != newField) {
                        transformations.addHintTypename(maybeNewField.getName());
                    }
                    newField = maybeNewField;
                    return newField;

                });
            });
            transformedFieldsCF.addAll(transformedCF);
        }
        return Async.each(transformedFieldsCF).thenCompose(transformedFields -> {
            List<VariableDefinition> variableDefinitions = buildReferencedVariableDefinitions(referencedVariables, executionContext.getGraphQLSchema(), transformations.getTypeRenameMappings());
            List<String> referencedVariableNames = new ArrayList<>(referencedVariables.keySet());

            // create a new Document including referenced Fragments
            SelectionSet newSelectionSet = newSelectionSet(transformedFields).build();

            OperationDefinition operationDefinition = newOperationDefinition()
                    .operation(operation.getAstOperation())
                    .name(operationName)
                    .selectionSet(newSelectionSet)
                    .variableDefinitions(variableDefinitions)
                    .build();

            CompletableFuture<Map<String, FragmentDefinition>> transformedFragmentsCF = transformFragments(
                    executionContext,
                    underlyingSchema,
                    executionContext.getFragmentsByName(),
                    fragmentsDirectlyReferenced,
                    referencedVariables,
                    serviceExecutionHooks,
                    variableValues,
                    service,
                    serviceContext,
                    removedFieldMap,
                    transformations);
            return transformedFragmentsCF.thenApply(transformedFragments -> {
                Document newDocument = newDocument(operationDefinition, transformedFragments);

                List<MergedField> transformedMergedFields = map(transformedFields, transformed -> MergedField.newMergedField(transformed).build());
                long elapsedTime = System.currentTimeMillis() - startTime;
                log.debug("OverallQueryTransformer.transformMergedFields time: {}, executionId: {}", elapsedTime, executionContext.getExecutionId());
                return new QueryTransformationResult(
                        newDocument,
                        operationDefinition,
                        transformedMergedFields,
                        referencedVariableNames,
                        transformedFragments,
                        variableValues,
                        removedFieldMap,
                        transformations);
            });
        });

    }

    private Document newDocument(OperationDefinition operationDefinition, Map<String, FragmentDefinition> transformedFragments) {
        Document.Builder newDocumentBuilder = Document.newDocument();
        newDocumentBuilder.definition(operationDefinition);
        for (FragmentDefinition transformedFragment : transformedFragments.values()) {
            newDocumentBuilder.definition(transformedFragment);
        }
        return newDocumentBuilder.build();
    }


    private CompletableFuture<Map<String, FragmentDefinition>> transformFragments(ExecutionContext executionContext,
                                                                                  GraphQLSchema underlyingSchema,
                                                                                  Map<String, FragmentDefinition> fragments,
                                                                                  Set<String> referencedFragmentNames,
                                                                                  Map<String, VariableDefinition> referencedVariables,
                                                                                  ServiceExecutionHooks serviceExecutionHooks,
                                                                                  Map<String, Object> variableValues,
                                                                                  Service service,
                                                                                  Object serviceContext,
                                                                                  TransformationMetadata removedFieldMap,
                                                                                  TransformationState transformations) {

        Set<String> fragmentsToTransform = Collections.synchronizedSet(new LinkedHashSet<>(referencedFragmentNames));
        Set<FragmentDefinition> transformedFragmentsInput = Collections.synchronizedSet(new LinkedHashSet<>());
        return transformFragmentImpl(
                executionContext,
                underlyingSchema,
                fragments,
                referencedVariables,
                serviceExecutionHooks,
                variableValues,
                service,
                serviceContext,
                removedFieldMap,
                fragmentsToTransform,
                transformedFragmentsInput,
                transformations).
                thenApply(transformedFragments -> groupingByUniqueKey(transformedFragmentsInput, FragmentDefinition::getName));
    }

    private CompletableFuture<Set<FragmentDefinition>> transformFragmentImpl(ExecutionContext executionContext,
                                                                             GraphQLSchema underlyingSchema,
                                                                             Map<String, FragmentDefinition> fragments,
                                                                             Map<String, VariableDefinition> referencedVariables,
                                                                             ServiceExecutionHooks serviceExecutionHooks,
                                                                             Map<String, Object> variableValues,
                                                                             Service service,
                                                                             Object serviceContext,
                                                                             TransformationMetadata removedFieldMap,
                                                                             Set<String> fragmentsToTransform,
                                                                             Set<FragmentDefinition> transformedFragments,
                                                                             TransformationState transformations) {
        if (fragmentsToTransform.isEmpty()) {
            return CompletableFuture.completedFuture(transformedFragments);
        }

        String fragmentName = fragmentsToTransform.iterator().next();
        Set<String> newReferencedFragments = new LinkedHashSet<>();
        CompletableFuture<FragmentDefinition> transformedFragmentCF = transformFragmentDefinition(
                executionContext,
                underlyingSchema,
                fragments.get(fragmentName),
                newReferencedFragments,
                referencedVariables,
                serviceExecutionHooks,
                variableValues,
                service,
                serviceContext,
                removedFieldMap,
                transformations
        );
        return transformedFragmentCF.thenCompose(transformedFragment -> {
            fragmentsToTransform.remove(fragmentName);
            transformedFragments.add(transformedFragment);
            fragmentsToTransform.addAll(newReferencedFragments);
            return transformFragmentImpl(
                    executionContext,
                    underlyingSchema,
                    fragments,
                    referencedVariables,
                    serviceExecutionHooks,
                    variableValues,
                    service,
                    serviceContext,
                    removedFieldMap,
                    fragmentsToTransform,
                    transformedFragments,
                    transformations);
        });
    }

    private CompletableFuture<FragmentDefinition> transformFragmentDefinition(ExecutionContext executionContext,
                                                                              GraphQLSchema underlyingSchema,
                                                                              FragmentDefinition fragmentDefinitionWithoutTypeInfo,
                                                                              Set<String> referencedFragmentNames,
                                                                              Map<String, VariableDefinition> referencedVariables,
                                                                              ServiceExecutionHooks serviceExecutionHooks,
                                                                              Map<String, Object> variableValues,
                                                                              Service service,
                                                                              Object serviceContext,
                                                                              TransformationMetadata removedFieldMap,
                                                                              TransformationState transformations) {
        NadelContext nadelContext = executionContext.getContext();

        OverallTypeInformation<FragmentDefinition> overallTypeInformation = recordOverallTypeInformation.recordOverallTypes(
                fragmentDefinitionWithoutTypeInfo,
                executionContext.getGraphQLSchema(),
                null);

        AsyncIsFieldForbidden asyncIsFieldForbidden = new AsyncIsFieldForbidden(serviceExecutionHooks, executionContext, nadelContext, Collections.emptyList());

        return asyncIsFieldForbidden.getForbiddenFields(fragmentDefinitionWithoutTypeInfo).thenApply(forbiddenFields -> {

            Transformer transformer = new Transformer(
                    executionContext,
                    underlyingSchema,
                    referencedFragmentNames,
                    referencedVariables,
                    nadelContext,
                    serviceExecutionHooks,
                    overallTypeInformation,
                    variableValues,
                    service,
                    serviceContext,
                    removedFieldMap,
                    forbiddenFields,
                    transformations
            );
            Map<Class<?>, Object> rootVars = new LinkedHashMap<>();
            rootVars.put(UnderlyingTypeContext.class, newUnderlyingTypeContext().build());
            TreeTransformer<Node> treeTransformer = new TreeTransformer<>(AstNodeAdapter.AST_NODE_ADAPTER);
            Node newNode = treeTransformer.transform(fragmentDefinitionWithoutTypeInfo, new TraverserVisitorStub<Node>() {
                        @Override
                        public TraversalControl enter(TraverserContext<Node> context) {
                            return context.thisNode().accept(context, transformer);
                        }
                    },
                    rootVars
            );
            //noinspection unchecked
            return (FragmentDefinition) newNode;
        });
    }

    private List<VariableDefinition> buildReferencedVariableDefinitions(Map<String, VariableDefinition> referencedVariables,
                                                                        GraphQLSchema graphQLSchema,
                                                                        Map<String, String> typeRenameMappings) {
        List<VariableDefinition> variableDefinitions = new ArrayList<>();
        for (VariableDefinition vd : referencedVariables.values()) {
            TypeInfo typeInfo = TypeInfo.typeInfo(vd.getType());

            GraphQLType type = graphQLSchema.getType(typeInfo.getName());
            TypeMappingDefinition mappingDefinition = getTypeMappingDefinitionFor(type);
            if (mappingDefinition != null) {
                typeRenameMappings.put(mappingDefinition.getUnderlyingName(), mappingDefinition.getOverallName());
                String newName = mappingDefinition.getUnderlyingName();
                TypeInfo newTypeInfo = typeInfo.renameAs(newName);
                vd = vd.transform(builder -> builder.type(newTypeInfo.getRawType()));
            }
            variableDefinitions.add(vd);
        }
        return variableDefinitions;
    }

    private <T extends Node> CompletableFuture<T> transformNode(ExecutionContext executionContext,
                                                                GraphQLSchema underlyingSchema,
                                                                T nodeWithoutTypeInfo,
                                                                GraphQLCompositeType parentTypeOverall,
                                                                Set<String> referencedFragmentNames,
                                                                Map<String, VariableDefinition> referencedVariables,
                                                                NadelContext nadelContext,
                                                                ServiceExecutionHooks serviceExecutionHooks,
                                                                Map<String, Object> variableValues,
                                                                Service service,
                                                                Object serviceContext,
                                                                TransformationMetadata removedFieldMap,
                                                                TransformationState transformations,
                                                                List<Argument> hydrationRootArguments) {
        OverallTypeInformation<T> overallTypeInformation = recordOverallTypeInformation.recordOverallTypes(
                nodeWithoutTypeInfo,
                executionContext.getGraphQLSchema(),
                parentTypeOverall);

        AsyncIsFieldForbidden asyncIsFieldForbidden = new AsyncIsFieldForbidden(serviceExecutionHooks, executionContext, nadelContext, hydrationRootArguments);

        return asyncIsFieldForbidden.getForbiddenFields(nodeWithoutTypeInfo).thenApply(forbiddenFields -> {
            Transformer transformer = new Transformer(
                    executionContext,
                    underlyingSchema,
                    referencedFragmentNames,
                    referencedVariables,
                    nadelContext,
                    serviceExecutionHooks,
                    overallTypeInformation,
                    variableValues,
                    service,
                    serviceContext,
                    removedFieldMap,
                    forbiddenFields,
                    transformations
            );
            Map<Class<?>, Object> rootVars = new LinkedHashMap<>();
            String underlyingParentName = getUnderlyingTypeNameAndRecordMapping(parentTypeOverall, transformations.getTypeRenameMappings());
            GraphQLOutputType underlyingSchemaParent = (GraphQLOutputType) underlyingSchema.getType(underlyingParentName);
            rootVars.put(UnderlyingTypeContext.class, newUnderlyingTypeContext()
                    .outputTypeUnderlying(underlyingSchemaParent)
                    .build());
            TreeTransformer<Node> treeTransformer = new TreeTransformer<>(AstNodeAdapter.AST_NODE_ADAPTER);
            Node newNode = treeTransformer.transform(nodeWithoutTypeInfo, new TraverserVisitorStub<Node>() {
                        @Override
                        public TraversalControl enter(TraverserContext<Node> context) {
                            return context.thisNode().accept(context, transformer);
                        }
                    },
                    rootVars
            );

            if (removedFieldMap.hasRemovedFields()) {
                newNode = addUnderscoreTypeNameToEmptySelectionSets(nadelContext, overallTypeInformation, rootVars, treeTransformer, newNode);
            }
            //noinspection unchecked
            return (T) newNode;

        });

    }

    private Node addUnderscoreTypeNameToEmptySelectionSets(NadelContext nadelContext,
                                                           OverallTypeInformation<?> overallTypeInformation,
                                                           Map<Class<?>, Object> rootVars,
                                                           TreeTransformer<Node> treeTransformer,
                                                           Node field
    ) {
        if (field == null) {
            return null;
        }
        NodeVisitorStub addDummyFieldToEmptySubSelectsVisitor = new NodeVisitorStub() {

            @Override
            public TraversalControl visitField(Field node, TraverserContext<Node> context) {
                OverallTypeInfo overallTypeInfo = overallTypeInformation.getOverallTypeInfo(getId(node));
                if (overallTypeInfo == null) {
                    return TraversalControl.CONTINUE;
                }
                GraphQLFieldDefinition fieldDefinitionOverall = overallTypeInfo.getFieldDefinition();
                GraphQLNamedOutputType fieldTypeOverall = (GraphQLNamedOutputType) GraphQLTypeUtil.unwrapAll(fieldDefinitionOverall.getType());
                Field newField = ArtificialFieldUtils.maybeAddEmptySelectionSetUnderscoreTypeName(nadelContext, node, fieldTypeOverall);
                if (newField != node) {
                    TreeTransformerUtil.changeNode(context, newField);
                }
                return TraversalControl.CONTINUE;
            }
        };

        return treeTransformer.transform(field, new TraverserVisitorStub<Node>() {
                    @Override
                    public TraversalControl enter(TraverserContext<Node> context) {
                        return context.thisNode().accept(context, addDummyFieldToEmptySubSelectsVisitor);
                    }
                },
                rootVars
        );
    }

    private String getUnderlyingTypeNameAndRecordMapping(GraphQLCompositeType typeOverall, Map<String, String> typeRenameMappings) {
        TypeMappingDefinition mappingDefinition = getTypeMappingDefinitionFor(typeOverall);
        if (mappingDefinition == null) {
            return typeOverall.getName();
        }
        typeRenameMappings.put(mappingDefinition.getUnderlyingName(), mappingDefinition.getOverallName());
        return mappingDefinition.getUnderlyingName();
    }


}
