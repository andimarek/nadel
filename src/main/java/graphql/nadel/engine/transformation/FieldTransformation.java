package graphql.nadel.engine.transformation;

import graphql.Assert;
import graphql.Internal;
import graphql.execution.ResultPath;
import graphql.language.AbstractNode;
import graphql.language.Field;
import graphql.nadel.dsl.NodeId;
import graphql.nadel.engine.UnapplyEnvironment;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.result.ExecutionResultNode;
import graphql.nadel.result.RootExecutionResultNode;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLOutputType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static graphql.Assert.assertShouldNeverHappen;
import static graphql.Assert.assertTrue;
import static java.util.UUID.randomUUID;

@Internal
public abstract class FieldTransformation {


    private ApplyEnvironment environment;
    private String transformationId = getClass().getSimpleName() + "-" + randomUUID().toString();

    public abstract ApplyResult apply(ApplyEnvironment environment);

    /*
     * This is a bit strange method because n FieldTransformations map to one unapply method and we don't know the mapping until
     * this method is called. So we actually give all relevant transformations as a List
     */
    public abstract UnapplyResult unapplyResultNode(ExecutionResultNode executionResultNode,
                                                    List<FieldTransformation> allTransformations,
                                                    UnapplyEnvironment environment);

    public abstract AbstractNode getDefinition();

    public String getTransformationId() {
        return transformationId;
    }

    public void setEnvironment(ApplyEnvironment environment) {
        this.environment = environment;
    }

    public ApplyEnvironment getApplyEnvironment() {
        return environment;
    }


    public Field getOriginalField() {
        return getApplyEnvironment().getField();
    }

    public GraphQLOutputType getOriginalFieldType() {
        return getApplyEnvironment().getFieldDefinitionOverall().getType();
    }

    public GraphQLFieldsContainer getOriginalFieldsContainer() {
        return getApplyEnvironment().getFieldsContainerOverall();
    }

    public GraphQLFieldDefinition getOriginalFieldDefinition() {
        return getApplyEnvironment().getFieldDefinitionOverall();
    }

    protected NormalizedQueryField getMatchingNormalizedQueryFieldBasedOnParent(ExecutionResultNode parent) {
        List<NormalizedQueryField> normalizedFields = getApplyEnvironment().getNormalizedQueryFieldsOverall();
        if (parent instanceof RootExecutionResultNode) {
            Assert.assertTrue(normalizedFields.size() == 1, () -> "only one normalized field expected");
            return normalizedFields.get(0);
        }
        ResultPath path = parent.getResultPath();
        List<String> parentQueryPath = executionPathToQueryPath(path);

        for (NormalizedQueryField normalizedField : normalizedFields) {
            NormalizedQueryField parentNormalizedField = normalizedField.getParent();
            if (!parentQueryPath.equals(parentNormalizedField.getPath())) {
                continue;
            }
            if (parentNormalizedField.getObjectType() == parent.getObjectType() &&
                    parentNormalizedField.getFieldDefinition() == parent.getFieldDefinition() &&
                    parentNormalizedField.getResultKey().equals(parent.getResultKey())) {
                return normalizedField;
            }
        }
        return assertShouldNeverHappen("could not find matching normalized field for parent node %s ", parent);
    }

    private static List<String> executionPathToQueryPath(ResultPath executionPath) {
        return executionPath.toList()
                .stream()
                .filter(o -> o instanceof String)
                .map(String.class::cast)
                .collect(Collectors.toList());
    }

    protected ExecutionResultNode replaceFieldIdsWithOriginalValue(List<FieldTransformation> allTransformations,
                                                                   ExecutionResultNode executionResultNode) {
        List<String> underlyingFieldIds = executionResultNode.getFieldIds();
        assertTrue(allTransformations.size() == underlyingFieldIds.size());

        List<String> newFieldIds = new ArrayList<>();
        for (FieldTransformation fieldTransformation : allTransformations) {
            newFieldIds.add(NodeId.getId(fieldTransformation.getOriginalField()));
        }
        return executionResultNode.transform((builder -> builder.fieldIds(newFieldIds)));
    }

    protected ExecutionResultNode mapToOverallFieldAndTypes(ExecutionResultNode node,
                                                            List<FieldTransformation> allTransformations,
                                                            NormalizedQueryField matchingNormalizedOverallField) {
        node = replaceFieldIdsWithOriginalValue(allTransformations, node);
        node = node.transform(builder -> builder
                .alias(matchingNormalizedOverallField.getAlias())
                .objectType(matchingNormalizedOverallField.getObjectType())
                .fieldDefinition(matchingNormalizedOverallField.getFieldDefinition())
                .objectType(matchingNormalizedOverallField.getObjectType())
        );
        return node;
    }


}
