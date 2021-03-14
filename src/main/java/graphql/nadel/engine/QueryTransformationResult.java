package graphql.nadel.engine;

import graphql.Internal;
import graphql.execution.MergedField;
import graphql.language.Document;
import graphql.language.FragmentDefinition;
import graphql.language.OperationDefinition;
import graphql.nadel.engine.transformation.FieldTransformation;
import graphql.nadel.engine.transformation.TransformationMetadata;

import java.util.List;
import java.util.Map;

@Internal
public class QueryTransformationResult {

    private final Document document;
    // will be provided to the ServiceExecution
    private final OperationDefinition operationDefinition;
    // will be provided to the ServiceExecution
    private final Map<String, FragmentDefinition> transformedFragments;
    // used to only pass down the references variables to ServiceExecution
    private final List<String> referencedVariables;

    // used when the underlying raw result is converted into a tree
    private final List<MergedField> transformedMergedFields;

    private final Map<String, Object> variableValues;

    private final TransformationMetadata removedFieldMap;

    private final TransformationState transformations;

    public QueryTransformationResult(Document document,
                                     OperationDefinition operationDefinition,
                                     List<MergedField> transformedMergedFields,
                                     List<String> referencedVariables,
                                     Map<String, FragmentDefinition> transformedFragments,
                                     Map<String, Object> variableValues,
                                     TransformationMetadata removedFieldMap,
                                     TransformationState transformations) {
        this.document = document;
        this.operationDefinition = operationDefinition;
        this.transformedMergedFields = transformedMergedFields;
        this.referencedVariables = referencedVariables;
        this.transformedFragments = transformedFragments;
        this.variableValues = variableValues;
        this.removedFieldMap = removedFieldMap;
        this.transformations = transformations;
    }

    public Document getDocument() {
        return document;
    }

    public OperationDefinition getOperationDefinition() {
        return operationDefinition;
    }

    public List<MergedField> getTransformedMergedFields() {
        return transformedMergedFields;
    }

    public List<String> getReferencedVariables() {
        return referencedVariables;
    }

    public TransformationState getTransformations() {
        return transformations;
    }

    public Map<String, FragmentDefinition> getTransformedFragments() {
        return transformedFragments;
    }

    public Map<String, Object> getVariableValues() {
        return variableValues;
    }

    public TransformationMetadata getRemovedFieldMap() {
        return removedFieldMap;
    }

    public List<String> getHintTypenames() {
        return transformations.getHintTypenames();
    }
}

