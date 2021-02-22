package graphql.nadel.engine;

import graphql.GraphQLError;
import graphql.Internal;
import graphql.execution.ExecutionId;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.AbstractNode;
import graphql.nadel.Tuples;
import graphql.nadel.TuplesTwo;
import graphql.nadel.dsl.NodeId;
import graphql.nadel.dsl.UnderlyingServiceHydration;
import graphql.nadel.engine.transformation.FieldTransformation;
import graphql.nadel.engine.transformation.HydrationTransformation;
import graphql.nadel.engine.transformation.TransformationMetadata;
import graphql.nadel.engine.transformation.TransformationMetadata.NormalizedFieldAndError;
import graphql.nadel.engine.transformation.UnapplyResult;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.normalized.NormalizedQueryFromAst;
import graphql.nadel.result.ExecutionResultNode;
import graphql.nadel.result.LeafExecutionResultNode;
import graphql.nadel.result.ListExecutionResultNode;
import graphql.nadel.result.ObjectExecutionResultNode;
import graphql.nadel.result.ResultCounter;
import graphql.nadel.result.RootExecutionResultNode;
import graphql.schema.GraphQLSchema;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TraverserVisitorStub;
import graphql.util.TreeTransformerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertShouldNeverHappen;
import static graphql.Assert.assertTrue;
import static graphql.nadel.engine.ExecutionResultNodeMapper.checkForTypeRename;
import static graphql.nadel.engine.StrategyUtil.changeFieldIsInResultNode;
import static graphql.util.FpKit.groupingBy;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;

@Internal
public class ServiceResultNodesToOverallResult {

    ExecutionResultNodeMapper executionResultNodeMapper = new ExecutionResultNodeMapper();

    ResolvedValueMapper resolvedValueMapper = new ResolvedValueMapper();

    ResultNodesTransformer resultNodesTransformer = new ResultNodesTransformer();

    private static final Logger log = LoggerFactory.getLogger(ServiceResultNodesToOverallResult.class);

    @SuppressWarnings("UnnecessaryLocalVariable")
    public ExecutionResultNode convert(ExecutionId executionId,
                                       ExecutionResultNode resultNode,
                                       GraphQLSchema overallSchema,
                                       ExecutionResultNode correctRootNode,
                                       Map<String, FieldTransformation> fieldIdToTransformation,
                                       Map<FieldTransformation, String> transformationToFieldId,
                                       Map<String, String> typeRenameMappings,
                                       NadelContext nadelContext,
                                       TransformationMetadata transformationMetadata,
                                       Set<ResultPath> hydrationInputPaths) {
        return convertImpl(executionId, resultNode, null, overallSchema, correctRootNode, false, false, fieldIdToTransformation, transformationToFieldId, typeRenameMappings, false, nadelContext, transformationMetadata, hydrationInputPaths);
    }

    public ExecutionResultNode convertChildren(ExecutionId executionId,
                                               ExecutionResultNode root,
                                               NormalizedQueryField normalizedRootField,
                                               GraphQLSchema overallSchema,
                                               ExecutionResultNode correctRootNode,
                                               boolean isHydrationTransformation,
                                               boolean batched,
                                               Map<String, FieldTransformation> fieldIdToTransformation,
                                               Map<FieldTransformation, String> transformationToFieldId,
                                               Map<String, String> typeRenameMappings,
                                               NadelContext nadelContext,
                                               TransformationMetadata transformationMetadata,
                                               Set<ResultPath> hydrationInputPaths) {
        return convertImpl(executionId, root, normalizedRootField, overallSchema, correctRootNode, isHydrationTransformation, batched, fieldIdToTransformation, transformationToFieldId, typeRenameMappings, true, nadelContext, transformationMetadata, hydrationInputPaths);
    }

    private ExecutionResultNode convertImpl(ExecutionId executionId,
                                            ExecutionResultNode root,
                                            NormalizedQueryField normalizedRootField,
                                            GraphQLSchema overallSchema,
                                            ExecutionResultNode correctRootNode,
                                            boolean isHydrationTransformation,
                                            boolean batched,
                                            Map<String, FieldTransformation> fieldIdToTransformation,
                                            Map<FieldTransformation, String> transformationToFieldId,
                                            Map<String, String> typeRenameMappings,
                                            boolean onlyChildren,
                                            NadelContext nadelContext,
                                            TransformationMetadata transformationMetadata,
                                            Set<ResultPath> hydrationInputPaths) {
        ResultCounter resultCounter = new ResultCounter();

        HandleResult handleResult = convertSingleNode(root,
                null/*not for root*/,
                null,
                executionId,
                root,
                normalizedRootField,
                overallSchema,
                isHydrationTransformation,
                batched,
                fieldIdToTransformation,
                transformationToFieldId,
                typeRenameMappings,
                onlyChildren,
                nadelContext,
                transformationMetadata,
                resultCounter,
                hydrationInputPaths);
        assertNotNull(handleResult, () -> "can't delete root");
        assertTrue(handleResult.siblings.isEmpty(), () -> "can't add siblings to root");

        ExecutionResultNode changedNode = handleResult.changedNode;
        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (ExecutionResultNode child : changedNode.getChildren()) {
            // pass in the correct root node as parent, not root
            HandleResult handleResultChild = convertRecursively(child,
                    correctRootNode,
                    changedNode,
                    executionId,
                    root,
                    normalizedRootField,
                    overallSchema,
                    isHydrationTransformation,
                    batched,
                    fieldIdToTransformation,
                    transformationToFieldId,
                    typeRenameMappings,
                    onlyChildren,
                    nadelContext,
                    transformationMetadata,
                    resultCounter,
                    hydrationInputPaths);
            if (handleResultChild == null) {
                continue;
            }
            newChildren.add(handleResultChild.changedNode);
            newChildren.addAll(handleResultChild.siblings);
        }

        return changedNode.transform(
                builder -> builder
                        .children(newChildren)
                        .totalNodeCount(resultCounter.getNodeCount())
                        .totalFieldRenameCount(resultCounter.getFieldRenameCount())
                        .totalTypeRenameCount(resultCounter.getTypeRenameCount())
        );
    }

    private HandleResult convertRecursively(ExecutionResultNode node,
                                            ExecutionResultNode correctParentNode,
                                            ExecutionResultNode directParentNode,
                                            ExecutionId executionId,
                                            ExecutionResultNode root,
                                            NormalizedQueryField normalizedRootField,
                                            GraphQLSchema overallSchema,
                                            boolean isHydrationTransformation,
                                            boolean batched,
                                            Map<String, FieldTransformation> fieldIdToTransformation,
                                            Map<FieldTransformation, String> transformationToFieldId,
                                            Map<String, String> typeRenameMappings,
                                            boolean onlyChildren,
                                            NadelContext nadelContext,
                                            TransformationMetadata transformationMetadata,
                                            ResultCounter resultCounter,
                                            Set<ResultPath> hydrationInputPaths) {
        HandleResult handleResult = convertSingleNode(node, correctParentNode, directParentNode, executionId, root, normalizedRootField, overallSchema, isHydrationTransformation, batched, fieldIdToTransformation, transformationToFieldId, typeRenameMappings, onlyChildren, nadelContext, transformationMetadata, resultCounter, hydrationInputPaths);
        if (handleResult == null) {
            return null;
        }
        if (handleResult.traversalControl == TraversalControl.ABORT) {
            return handleResult;
        }
        ExecutionResultNode changedNode = handleResult.changedNode;
        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (ExecutionResultNode child : changedNode.getChildren()) {
            HandleResult handleResultChild = convertRecursively(child, changedNode, changedNode, executionId, root, normalizedRootField, overallSchema, isHydrationTransformation, batched, fieldIdToTransformation, transformationToFieldId, typeRenameMappings, onlyChildren, nadelContext, transformationMetadata, resultCounter, hydrationInputPaths);
            if (handleResultChild == null) {
                continue;
            }
            newChildren.add(handleResultChild.changedNode);
            // additional siblings are not descended, just added
            newChildren.addAll(handleResultChild.siblings);
        }
        handleResult.changedNode = changedNode.withNewChildren(newChildren);
        return handleResult;
    }

    private HandleResult convertSingleNode(ExecutionResultNode node,
                                           ExecutionResultNode correctParentNode,
                                           ExecutionResultNode directParentNode,
                                           ExecutionId executionId,
                                           ExecutionResultNode root,
                                           NormalizedQueryField normalizedRootField,
                                           GraphQLSchema overallSchema,
                                           boolean isHydrationTransformation,
                                           boolean batched,
                                           Map<String, FieldTransformation> fieldIdTransformation,
                                           Map<FieldTransformation, String> transformationToFieldId,
                                           Map<String, String> typeRenameMappings,
                                           boolean onlyChildren,
                                           NadelContext nadelContext,
                                           TransformationMetadata transformationMetadata,
                                           ResultCounter resultCounter,
                                           Set<ResultPath> hydrationInputPaths) {
        resultCounter.incrementNodeCount();

        if (onlyChildren && node == root) {
            // Could be a possible type rename if this is hydrated
            if (normalizedRootField != null) {
                checkForTypeRename(normalizedRootField.getFieldDefinition(), node.getFieldDefinition(), typeRenameMappings, resultCounter, 0);
            }
            if (root instanceof ObjectExecutionResultNode) {
                ExecutionResultNode executionResultNode = addDeletedChildren((ObjectExecutionResultNode) node, normalizedRootField, nadelContext, transformationMetadata);
                return HandleResult.simple(executionResultNode);
            } else {
                return HandleResult.simple(node);
            }
        }

        if (node instanceof RootExecutionResultNode) {
            ExecutionResultNode convertedNode = mapRootResultNode((RootExecutionResultNode) node);
            return HandleResult.simple(convertedNode);
        }
        if (node instanceof LeafExecutionResultNode) {
            if (ArtificialFieldUtils.isArtificialField(nadelContext, node.getAlias())) {
                resultCounter.decrementNodeCount();
                return null;
            }
        }

        TuplesTwo<Set<FieldTransformation>, List<String>> transformationsAndNotTransformedFields =
                getTransformationsAndNotTransformedFields(node, fieldIdTransformation, transformationMetadata);

        List<FieldTransformation> transformations = new ArrayList<>(transformationsAndNotTransformedFields.getT1());

        UnapplyEnvironment unapplyEnvironment = new UnapplyEnvironment(
                correctParentNode,
                isHydrationTransformation,
                batched,
                typeRenameMappings,
                overallSchema
        );
        HandleResult result;
        if (transformations.size() == 0) {
            result = HandleResult.simple(mapNode(node, unapplyEnvironment, resultCounter));
        } else {
            result = unapplyTransformations(executionId, node, directParentNode, transformations, unapplyEnvironment, fieldIdTransformation, transformationToFieldId, nadelContext, transformationMetadata, resultCounter, hydrationInputPaths);
            if (result == null) {
                return null;
            }
        }

        if (result.changedNode instanceof ObjectExecutionResultNode && !(correctParentNode instanceof HydrationInputNode)) {
            result.changedNode = addDeletedChildren((ObjectExecutionResultNode) result.changedNode, null, nadelContext, transformationMetadata);
        }
        return result;
    }

    private ExecutionResultNode addDeletedChildren(ObjectExecutionResultNode resultNode,
                                                   NormalizedQueryField normalizedQueryField,
                                                   NadelContext nadelContext,
                                                   TransformationMetadata transformationMetadata
    ) {
        if (normalizedQueryField == null) {
            normalizedQueryField = getNormalizedQueryFieldForResultNode(resultNode, nadelContext.getNormalizedOverallQuery());
        }
        List<NormalizedFieldAndError> removedFields = transformationMetadata.getRemovedFieldsForParent(normalizedQueryField);
        for (NormalizedFieldAndError normalizedFieldAndError : removedFields) {
            MergedField mergedField = nadelContext.getNormalizedOverallQuery().getMergedFieldByNormalizedFields().get(normalizedFieldAndError.getNormalizedField());
            LeafExecutionResultNode newChild = createRemovedFieldResult(resultNode, mergedField, normalizedFieldAndError.getNormalizedField(), normalizedFieldAndError.getError());
            resultNode = resultNode.transform(b -> b.addChild(newChild));
        }
        return resultNode;
    }

    private LeafExecutionResultNode createRemovedFieldResult(ExecutionResultNode parent,
                                                             MergedField mergedField,
                                                             NormalizedQueryField normalizedQueryField,
                                                             GraphQLError error) {
        ResultPath parentPath = parent.getResultPath();
        ResultPath executionPath = parentPath.segment(normalizedQueryField.getResultKey());

        LeafExecutionResultNode removedNode = LeafExecutionResultNode.newLeafExecutionResultNode()
                .executionPath(executionPath)
                .alias(mergedField.getSingleField().getAlias())
                .fieldIds(NodeId.getIds(mergedField))
                .objectType(normalizedQueryField.getObjectType())
                .fieldDefinition(normalizedQueryField.getFieldDefinition())
                .completedValue(null)
                .errors(singletonList(error))
                .build();
        return removedNode;
    }

    private HandleResult unapplyTransformations(ExecutionId executionId,
                                                ExecutionResultNode node,
                                                ExecutionResultNode directParentNode,
                                                List<FieldTransformation> transformations,
                                                UnapplyEnvironment unapplyEnvironment,
                                                Map<String, FieldTransformation> fieldIdToTransformation,
                                                Map<FieldTransformation, String> transformationToFieldId,
                                                NadelContext nadelContext,
                                                TransformationMetadata transformationMetadata,
                                                ResultCounter resultCounter,
                                                Set<ResultPath> hydrationInputPaths) {

        if (isArtificialHydrationNode(node.getFieldIds(), new HashSet<String>(transformationToFieldId.values()))) {
            return null;
        }

        Map<AbstractNode, ? extends List<FieldTransformation>> transformationByDefinition = groupingBy(transformations, FieldTransformation::getDefinition);
        TuplesTwo<ExecutionResultNode, Map<AbstractNode, List<ExecutionResultNode>>> splittedNodes = splitTreeByTransformationDefinition(node, directParentNode, fieldIdToTransformation, transformationToFieldId, transformationMetadata);
        ExecutionResultNode notTransformedTree = splittedNodes.getT1();
        Map<AbstractNode, List<ExecutionResultNode>> nodesWithTransformedFields = splittedNodes.getT2();

        List<UnapplyResult> unapplyResults = new ArrayList<>();

        for (AbstractNode definition : nodesWithTransformedFields.keySet()) {
            List<FieldTransformation> transformationsForDefinition = transformationByDefinition.get(definition);
            FieldTransformation transformation = transformationsForDefinition.get(0);
            boolean isHydrationTransformation = transformation instanceof HydrationTransformation;

            List<ExecutionResultNode> transformedNodes = nodesWithTransformedFields.get(definition);
            ExecutionResultNode resultNode = transformedNodes.get(0);

            if (isHydrationTransformation) {
                resultNode = mergeHydrationNodes(transformedNodes, resultNode);
            }
            UnapplyResult unapplyResult = transformation.unapplyResultNode(resultNode, transformationsForDefinition, unapplyEnvironment);

            if (isHydrationTransformation) {
                // For every list node, it's children will also have a renamed type so the type rename count is decremented based on
                // the size of it's children.
                // //E.g. /foo , /foo[0], /foo[1], /foo[2] => type rename count becomes -2, -1, 0, 1
                int typeDecrementValue = unapplyResult.getNode() instanceof ListExecutionResultNode ? -unapplyResult.getNode().getChildren().size() : -1;
                checkForTypeRename(unapplyResult.getNode().getFieldDefinition(), node.getFieldDefinition(), unapplyEnvironment.typeRenameMappings, resultCounter, typeDecrementValue);
                hydrationInputPaths.add(unapplyResult.getNode().getResultPath());
            } else {
                // typeDecrementAmount = 0 because for a field rename it's children will not know about the underlying type.
                checkForTypeRename(unapplyResult.getNode().getFieldDefinition(), node.getFieldDefinition(), unapplyEnvironment.typeRenameMappings, resultCounter, 0);
                resultCounter.incrementFieldRenameCount();
            }
            unapplyResults.add(unapplyResult);
        }

        HandleResult handleResult = HandleResult.newHandleResultWithSiblings();
        boolean first = true;
        // the not transformed part should simply continue to be converted
        if (notTransformedTree != null) {
            ExecutionResultNode mappedNode = mapNode(notTransformedTree, unapplyEnvironment, resultCounter);
            mappedNode = convertChildren(executionId,
                    mappedNode,
                    null,
                    unapplyEnvironment.overallSchema,
                    unapplyEnvironment.parentNode,
                    unapplyEnvironment.isHydrationTransformation,
                    unapplyEnvironment.batched,
                    fieldIdToTransformation,
                    transformationToFieldId,
                    unapplyEnvironment.typeRenameMappings,
                    nadelContext,
                    transformationMetadata,
                    hydrationInputPaths);
            handleResult.changedNode = mappedNode;
            resultCounter.incrementFieldRenameCount(mappedNode.getTotalFieldRenameCount());
            resultCounter.incrementTypeRenameCount(mappedNode.getTotalTypeRenameCount());
            resultCounter.incrementNodeCount(mappedNode.getTotalNodeCount() - 1);
            first = false;
        }

        // each unapply result is either continued to processed
        for (UnapplyResult unapplyResult : unapplyResults) {
            ExecutionResultNode transformedResult;
            if (unapplyResult.getTraversalControl() != TraversalControl.CONTINUE) {
                transformedResult = unapplyResult.getNode();
            } else {
                ExecutionResultNode unapplyResultNode = unapplyResult.getNode();
                transformedResult = convertChildren(executionId,
                        unapplyResultNode,
                        null,
                        unapplyEnvironment.overallSchema,
                        unapplyResultNode,
                        unapplyEnvironment.isHydrationTransformation,
                        unapplyEnvironment.batched,
                        fieldIdToTransformation,
                        transformationToFieldId,
                        unapplyEnvironment.typeRenameMappings,
                        nadelContext,
                        transformationMetadata,
                        hydrationInputPaths);
            }
            resultCounter.incrementFieldRenameCount(transformedResult.getTotalFieldRenameCount());
            resultCounter.incrementTypeRenameCount(transformedResult.getTotalTypeRenameCount());

            if (first) {
                handleResult.changedNode = transformedResult;
                first = false;
            } else {
                handleResult.siblings.add(transformedResult);
            }
        }
        handleResult.traversalControl = TraversalControl.ABORT;
        return handleResult;
    }

    private ExecutionResultNode mergeHydrationNodes(List<ExecutionResultNode> nodesWithTransformedFields, ExecutionResultNode primaryNode) {
        if (primaryNode.isNullValue()) {
            return primaryNode;
        }

        Map<String, Object> completedValues = new LinkedHashMap<>();
        boolean isListSource = primaryNode instanceof ListExecutionResultNode;

        for (ExecutionResultNode hydrationNode : nodesWithTransformedFields) {
            Object value = hydrationNode.getCompletedValue();
            String resultKey = hydrationNode.getResultKey();
            completedValues.put(resultKey, value);

            if (hydrationNode.getResultPath().equals(primaryNode.getResultPath())) {
                primaryNode = hydrationNode;
            }
            if (isListSource) {
                assertTrue(hydrationNode instanceof ListExecutionResultNode, () -> String.format("Expected source argument %s to return a list of values", hydrationNode.getResultKey()));
            } else {
                assertTrue(!(hydrationNode instanceof ListExecutionResultNode), () -> String.format("Expected source argument %s to return a single value", hydrationNode.getResultKey()));
            }
        }
        if (isListSource) {
            return mergeListHydrationNodeValues(primaryNode, completedValues);
        } else if (primaryNode instanceof ObjectExecutionResultNode) {
            return mergeObjectHydrationNodeValues(primaryNode, completedValues);
        }
        return primaryNode.withNewCompletedValue(completedValues);
    }

    private ExecutionResultNode mergeListHydrationNodeValues(ExecutionResultNode primaryNode, Map<String, Object> completedValues) {
        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (int index = 0; index < primaryNode.getChildren().size(); index++) {
            ExecutionResultNode child = primaryNode.getChildren().get(index);
            Map<String, Object> childCompletedValues = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : completedValues.entrySet()) {
                Object value = entry.getValue();
                value = (index < ((List) value).size()) ? ((List) value).get(index) : null;
                childCompletedValues.put(entry.getKey(), value);
            }
            if (child instanceof ObjectExecutionResultNode) {
                child = mergeObjectHydrationNodeValues(child, childCompletedValues);
            } else {
                if (!child.isNullValue()) {
                    child = child.withNewCompletedValue(childCompletedValues);
                }
            }
            newChildren.add(child);
        }
        return primaryNode.withNewChildren(newChildren).transform(builder -> builder.fieldId(primaryNode.getFieldIds().get(0)));
    }

    private ExecutionResultNode mergeObjectHydrationNodeValues(ExecutionResultNode node, Map<String, Object> completedValues) {
        return changeLeafValueInObjectNode(node, completedValues).get(0);
    }

    private List<ExecutionResultNode> changeLeafValueInObjectNode(ExecutionResultNode node, Map<String, Object> completedValues) {
        if (node instanceof LeafExecutionResultNode) {
            if (!node.isNullValue()) {
                node = node.withNewCompletedValue(completedValues);
            }
            return new ArrayList<>(Collections.singletonList(node));
        }

        List<ExecutionResultNode> modifiedChildren = changeLeafValueInObjectNode(node.getChildren().get(0), completedValues);
        return new ArrayList<>(Collections.singletonList(node.transform(builder -> builder.children(modifiedChildren))));
    }

    private TuplesTwo<ExecutionResultNode, Map<AbstractNode, List<ExecutionResultNode>>> splitTreeByTransformationDefinition(
            ExecutionResultNode executionResultNode,
            ExecutionResultNode directParentNode,
            Map<String, FieldTransformation> fieldIdToTransformation,
            Map<FieldTransformation, String> transformationToFieldId,
            TransformationMetadata transformationMetadata) {
        if (executionResultNode instanceof RootExecutionResultNode) {
            return Tuples.of(executionResultNode, emptyMap());
        }

        Map<AbstractNode, Set<String>> transformationIdsByTransformationDefinition = new LinkedHashMap<>();
        List<String> fieldIds = executionResultNode.getFieldIds();
        for (String fieldId : fieldIds) {
            List<String> transformationIds = FieldMetadataUtil.getRootOfTransformationIds(fieldId, transformationMetadata.getMetadataByFieldId());
            for (String transformationId : transformationIds) {
                FieldTransformation fieldTransformation = assertNotNull(fieldIdToTransformation.get(transformationId));
                // This checks that the current fieldTransformation is for this specific fieldID as we can have a n:1 mapping of transformations to fieldIds
                // due to multiple source arguments
                if (transformationToFieldId.containsKey(fieldTransformation) && transformationToFieldId.get(fieldTransformation) == fieldId) {
                    AbstractNode definition = fieldTransformation.getDefinition();
                    transformationIdsByTransformationDefinition.putIfAbsent(definition, new LinkedHashSet<>());
                    transformationIdsByTransformationDefinition.get(definition).add(transformationId);
                }
            }
        }
        Map<AbstractNode, List<ExecutionResultNode>> treesByDefinition = new LinkedHashMap<>();
        for (AbstractNode definition : transformationIdsByTransformationDefinition.keySet()) {
            Set<String> transformationIds = transformationIdsByTransformationDefinition.get(definition);
            treesByDefinition.putIfAbsent(definition, new ArrayList<>());
            treesByDefinition.get(definition).add(nodesWithTransformationIds(executionResultNode, transformationIds, transformationMetadata));
            if (definition instanceof UnderlyingServiceHydration) {
                for (ExecutionResultNode child : directParentNode.getChildren()) {
                    // Makes sure no unnecessary traversals occur
                    if (getFieldIdsWithTransformationIds(child, transformationIds, transformationMetadata).size() != 0
                            && child != executionResultNode
                    ) {
                        ExecutionResultNode resultNode = nodesWithTransformationIds(child, transformationIds, transformationMetadata);
                        treesByDefinition.get(definition).add(resultNode);
                    }
                }
            }
        }
        ExecutionResultNode treeWithout = nodesWithTransformationIds(executionResultNode, null, transformationMetadata);
        return Tuples.of(treeWithout, treesByDefinition);
    }

    private ExecutionResultNode nodesWithTransformationIds(ExecutionResultNode
                                                                   executionResultNode, Set<String> transformationIds, TransformationMetadata transformationMetadata) {
        return resultNodesTransformer.transform(executionResultNode, new TraverserVisitorStub<ExecutionResultNode>() {

            @Override
            public TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                ExecutionResultNode node = context.thisNode();
                List<String> fieldIdsWithId;
                if (transformationIds == null) {
                    fieldIdsWithId = getFieldIdsWithoutTransformationId(node, transformationMetadata);
                } else {
                    fieldIdsWithId = getFieldIdsWithTransformationIds(node, transformationIds, transformationMetadata);
                }

                if (fieldIdsWithId.size() == 0) {
                    return TreeTransformerUtil.deleteNode(context);
                }
                ExecutionResultNode changedNode = changeFieldIsInResultNode(node, fieldIdsWithId);
                return TreeTransformerUtil.changeNode(context, changedNode);
            }
        });
    }

    private List<String> getFieldIdsWithoutTransformationId(ExecutionResultNode node, TransformationMetadata transformationMetadata) {
        return node.getFieldIds().stream().filter(fieldId -> FieldMetadataUtil.getTransformationIds(fieldId, transformationMetadata.getMetadataByFieldId()).size() == 0).collect(Collectors.toList());
    }

    private List<String> getFieldIdsWithTransformationIds(ExecutionResultNode
                                                                  node, Set<String> transformationIds, TransformationMetadata transformationMetadata) {
        return node.getFieldIds().stream().filter(fieldId -> {
            List<String> transformationIdsForField = FieldMetadataUtil.getTransformationIds(fieldId, transformationMetadata.getMetadataByFieldId());
            return transformationIdsForField.containsAll(transformationIds);
        }).collect(Collectors.toList());
    }

    private ExecutionResultNode mapNode(ExecutionResultNode node, UnapplyEnvironment environment, ResultCounter resultCounter) {
        ExecutionResultNode mappedNode = executionResultNodeMapper.mapERNFromUnderlyingToOverall(node, environment, resultCounter);
        mappedNode = resolvedValueMapper.mapCompletedValue(mappedNode, environment);
        return mappedNode;
    }

    private TuplesTwo<Set<FieldTransformation>, List<String>> getTransformationsAndNotTransformedFields(
            ExecutionResultNode node,
            Map<String, FieldTransformation> fieldIdToTransformation,
            TransformationMetadata transformationMetadata
    ) {
        Set<FieldTransformation> transformations = new LinkedHashSet<>();
        List<String> notTransformedFields = new ArrayList<>();
        for (String fieldId : node.getFieldIds()) {

            if (node.getResultPath().isListSegment()) {
                notTransformedFields.add(fieldId);
                continue;
            }

            List<String> rootTransformationIds = FieldMetadataUtil.getRootOfTransformationIds(fieldId, transformationMetadata.getMetadataByFieldId());
            if (rootTransformationIds.size() == 0) {
                notTransformedFields.add(fieldId);
                continue;
            }
            for (String transformationId : rootTransformationIds) {
                FieldTransformation fieldTransformation = fieldIdToTransformation.get(transformationId);
                transformations.add(fieldTransformation);
            }
        }
        return Tuples.of(transformations, notTransformedFields);
    }

    private RootExecutionResultNode mapRootResultNode(RootExecutionResultNode resultNode) {
        return RootExecutionResultNode.newRootExecutionResultNode()
                .children(resultNode.getChildren())
                .errors(resultNode.getErrors())
                .extensions(resultNode.getExtensions())
                .elapsedTime(resultNode.getElapsedTime())
                .build();
    }

    private NormalizedQueryField getNormalizedQueryFieldForResultNode(ObjectExecutionResultNode resultNode,
                                                                      NormalizedQueryFromAst normalizedQueryFromAst) {
        String id = resultNode.getFieldIds().get(0);
        List<NormalizedQueryField> normalizedFields = assertNotNull(normalizedQueryFromAst.getNormalizedFieldsByFieldId(id));

        for (NormalizedQueryField normalizedField : normalizedFields) {
            if (resultNode.getObjectType() == normalizedField.getObjectType() &&
                    resultNode.getFieldDefinition() == normalizedField.getFieldDefinition()) {
                return normalizedField;
            }
        }
        return assertShouldNeverHappen("Can't find normalized query field");
    }

    private boolean isArtificialHydrationNode(List<String> fieldIds, Set<String> transformationToFieldIds) {
        return fieldIds.stream().noneMatch(transformationToFieldIds::contains);
    }

    public static class HandleResult {
        ExecutionResultNode changedNode;
        List<ExecutionResultNode> siblings = emptyList();
        TraversalControl traversalControl = TraversalControl.CONTINUE;

        public HandleResult() {

        }

        public static HandleResult newHandleResultWithSiblings() {
            HandleResult handleResult = new HandleResult();
            handleResult.siblings = new ArrayList<>();
            return handleResult;
        }

        public HandleResult(ExecutionResultNode changedNode, List<ExecutionResultNode> siblings, TraversalControl traversalControl) {
            this.changedNode = changedNode;
            this.siblings = siblings;
            this.traversalControl = traversalControl;
        }

        public static HandleResult simple(ExecutionResultNode executionResultNode) {
            HandleResult handleResult = new HandleResult(executionResultNode, emptyList(), TraversalControl.CONTINUE);
            return handleResult;
        }
    }
}
