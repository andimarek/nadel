package graphql.nadel.result;

import graphql.Assert;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.Internal;
import graphql.execution.ResultPath;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLTypeUtil;
import graphql.util.NodeLocation;
import graphql.util.NodeMultiZipper;
import graphql.util.NodeZipper;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TraverserVisitorStub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.Assert.assertTrue;
import static graphql.nadel.result.ResultNodeAdapter.RESULT_NODE_ADAPTER;
import static graphql.schema.GraphQLTypeUtil.simplePrint;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;

@Internal
public class ResultNodesUtil {

    public static ExecutionResult toExecutionResult(RootExecutionResultNode root) {
        ExecutionResultData executionResultData = toDataImpl(root);
        if (executionResultData.nonNullableFieldWasNullError != null) {
            return ExecutionResultImpl.newExecutionResult()
                    .data(null)
                    .addError(executionResultData.nonNullableFieldWasNullError)
                    .build();
        }
        // this is a bug in graphql-java code - the builder should accept <String,Object>
        Map<Object, Object> bugMap = new LinkedHashMap<>(executionResultData.extensions);

        return ExecutionResultImpl.newExecutionResult()
                .data(executionResultData.data)
                .errors(executionResultData.errors)
                .extensions(bugMap)
                .build();
    }

    private static class ExecutionResultData {
        Object data;
        List<GraphQLError> errors = emptyList();
        Map<String, Object> extensions = emptyMap();
        NonNullableFieldWasNullError nonNullableFieldWasNullError;


        public ExecutionResultData(Object data, List<GraphQLError> errors, Map<String, Object> extensions) {
            this.data = data;
            this.errors = errors;
            this.extensions = extensions;
        }

        public ExecutionResultData(Object data, List<GraphQLError> errors) {
            this.data = data;
            this.errors = errors;
        }

        public ExecutionResultData(NonNullableFieldWasNullError nonNullableFieldWasNullError) {
            this.data = null;
            this.nonNullableFieldWasNullError = nonNullableFieldWasNullError;
        }
    }


    private static ExecutionResultData data(Object data, ExecutionResultNode executionResultNode) {
        List<GraphQLError> allErrors = new ArrayList<>(executionResultNode.getErrors());
        return new ExecutionResultData(data, allErrors);
    }

    private static ExecutionResultData data(Object data, List<GraphQLError> errors) {
        return new ExecutionResultData(data, errors);
    }

    private static ExecutionResultData data(Object data, List<GraphQLError> errors, Map<String, Object> extensions) {
        return new ExecutionResultData(data, errors, extensions);
    }

    private static ExecutionResultData data(NonNullableFieldWasNullError error) {
        return new ExecutionResultData(error);
    }

    private static ExecutionResultData toDataImpl(final ExecutionResultNode root) {

        if (root instanceof UnresolvedObjectResultNode) {
            return data("Not resolved : " + root.getResultPath() + " with field " + root.getFieldName(), emptyList());
        }

        if (root instanceof LeafExecutionResultNode) {
            return root.getNonNullableFieldWasNullError() != null ? data(root.getNonNullableFieldWasNullError()) : data(root.getCompletedValue(), root);
        }
        if (root instanceof ListExecutionResultNode) {
            return toDataImplList(root);
        }

        if (root instanceof ObjectExecutionResultNode) {
            return toDataImplObject(root);
        }
        return Assert.assertShouldNeverHappen("An unexpected node type %s", root.getClass());
    }

    private static ExecutionResultData toDataImplObject(ExecutionResultNode root) {
        boolean isNonNull = false;
        GraphQLOutputType actualType = null;
        if (root instanceof RootExecutionResultNode) {
            isNonNull = true;
        }
        if (root.getFieldDefinition() != null) {
            actualType = getActualType(root.getFieldDefinition(), root.getResultPath());
            isNonNull = GraphQLTypeUtil.isNonNull(actualType);
        }

        Map<String, Object> extensions = root.getExtensions();
        Map<String, Object> resultMap = new LinkedHashMap<>();
        List<GraphQLError> errors = new ArrayList<>();
        for (ExecutionResultNode child : root.getChildren()) {
            ExecutionResultData executionResultData = toDataImpl(child);
            if (isNonNull && (child.getNonNullableFieldWasNullError() != null || executionResultData.nonNullableFieldWasNullError != null)) {
                if (actualType != null) {
                    return data(new NonNullableFieldWasNullError((GraphQLNonNull) actualType, root.getResultPath()));
                } else {
                    return data(new NonNullableFieldWasNullError((GraphQLNonNull) child.getFieldDefinition().getType(), root.getResultPath()));
                }
            } else if (executionResultData.nonNullableFieldWasNullError != null) {
                return data(null, singletonList(executionResultData.nonNullableFieldWasNullError), extensions);
            }
            resultMap.put(child.getResultKey(), executionResultData.data);
            errors.addAll(executionResultData.errors);
        }
        errors.addAll(root.getErrors());

        return data(resultMap, errors, extensions);
    }

    private static ExecutionResultData toDataImplList(ExecutionResultNode root) {
        boolean isNonNull = false;
        GraphQLOutputType actualType = null;
        if (root.getFieldDefinition() != null) {
            actualType = getActualType(root.getFieldDefinition(), root.getResultPath());
            isNonNull = GraphQLTypeUtil.isNonNull(actualType);
        }

        List<GraphQLError> errors = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        for (ExecutionResultNode child : root.getChildren()) {

            ExecutionResultData executionResultData = toDataImpl(child);
            if (isNonNull && (child.getNonNullableFieldWasNullError() != null || executionResultData.nonNullableFieldWasNullError != null)) {
                return data(new NonNullableFieldWasNullError((GraphQLNonNull) actualType, root.getResultPath()));
            } else if (executionResultData.nonNullableFieldWasNullError != null) {
                return data(null, singletonList(executionResultData.nonNullableFieldWasNullError));
            }
            data.add(executionResultData.data);
            errors.addAll(executionResultData.errors);
        }
        errors.addAll(root.getErrors());
        return data(data, errors);
    }


    private static GraphQLOutputType getActualType(GraphQLFieldDefinition fieldDefinition, ResultPath resultPath) {
        // example: field definition type: [[String]!]!, path: /foo/bar/type[3] => result is [String]!
        GraphQLOutputType result = fieldDefinition.getType();
        ResultPath executionPath = resultPath;
        while (executionPath.isListSegment()) {
            executionPath = executionPath.dropSegment();
            // might be non null or not
            GraphQLOutputType tempType = (GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(result);
            assertTrue(tempType instanceof GraphQLList, () -> String.format("Expected an embedded list type but got type %s  - complete type is %s for path %s",
                    simplePrint(tempType), simplePrint(fieldDefinition.getType()), resultPath));
            result = tempType;
            result = (GraphQLOutputType) ((GraphQLList) result).getWrappedType();
        }
        return result;

    }

    public static List<NodeZipper<ExecutionResultNode>> getUnresolvedNodes(Collection<ExecutionResultNode> roots) {
        List<NodeZipper<ExecutionResultNode>> result = new ArrayList<>();

        ResultNodeTraverser traverser = ResultNodeTraverser.depthFirst();
        traverser.traverse(new TraverserVisitorStub<ExecutionResultNode>() {
            @Override
            public TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                if (context.thisNode() instanceof UnresolvedObjectResultNode) {
                    result.add(new NodeZipper<>(context.thisNode(), context.getBreadcrumbs(), RESULT_NODE_ADAPTER));
                }
                return TraversalControl.CONTINUE;
            }

        }, roots);
        return result;
    }

    public static NodeMultiZipper<ExecutionResultNode> getUnresolvedNodes(ExecutionResultNode root) {
        List<NodeZipper<ExecutionResultNode>> unresolvedNodes = getUnresolvedNodes(singleton(root));
        return new NodeMultiZipper<>(root, unresolvedNodes, RESULT_NODE_ADAPTER);
    }


    public static NodeLocation key(String name) {
        return new NodeLocation(name, 0);
    }

    public static NodeLocation index(int index) {
        return new NodeLocation(null, index);
    }

}
