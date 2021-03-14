package graphql.nadel.engine;

import graphql.GraphQLError;
import graphql.Internal;
import graphql.execution.ExecutionContext;
import graphql.execution.ValuesResolver;
import graphql.introspection.Introspection;
import graphql.language.Argument;
import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Node;
import graphql.language.NodeVisitorStub;
import graphql.language.ObjectField;
import graphql.language.OperationDefinition;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import graphql.nadel.Service;
import graphql.nadel.dsl.ExtendedFieldDefinition;
import graphql.nadel.dsl.FieldMappingDefinition;
import graphql.nadel.dsl.TypeMappingDefinition;
import graphql.nadel.dsl.UnderlyingServiceHydration;
import graphql.nadel.engine.transformation.ApplyEnvironment;
import graphql.nadel.engine.transformation.ApplyResult;
import graphql.nadel.engine.transformation.FieldRenameTransformation;
import graphql.nadel.engine.transformation.FieldTransformation;
import graphql.nadel.engine.transformation.HydrationTransformation;
import graphql.nadel.engine.transformation.OverallTypeInfo;
import graphql.nadel.engine.transformation.OverallTypeInformation;
import graphql.nadel.engine.transformation.TransformationMetadata;
import graphql.nadel.hooks.NewVariableValue;
import graphql.nadel.hooks.ServiceExecutionHooks;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.normalized.NormalizedQueryFromAst;
import graphql.nadel.schema.NadelDirectives;
import graphql.nadel.util.FpKit;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputValueDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.GraphQLUnmodifiedType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static graphql.Assert.assertShouldNeverHappen;
import static graphql.Assert.assertTrue;
import static graphql.introspection.Introspection.TypeNameMetaFieldDef;
import static graphql.language.TypeName.newTypeName;
import static graphql.nadel.dsl.NodeId.getId;
import static graphql.nadel.engine.UnderlyingTypeContext.newUnderlyingTypeContext;
import static graphql.nadel.util.FpKit.filter;
import static graphql.nadel.util.Util.getTypeMappingDefinitionFor;
import static graphql.schema.GraphQLTypeUtil.unwrapAll;
import static graphql.util.TreeTransformerUtil.changeNode;


@Internal
public class Transformer extends NodeVisitorStub {

    private final ValuesResolver valuesResolver = new ValuesResolver();

    final ExecutionContext executionContext;
    final GraphQLSchema underlyingSchema;
    final Set<String> referencedFragmentNames;
    final Map<String, VariableDefinition> referencedVariables;
    final NadelContext nadelContext;
    private final Map<String, VariableDefinition> variableDefinitions;
    final ServiceExecutionHooks serviceExecutionHooks;
    private OverallTypeInformation<?> overallTypeInformation;
    private TransformationMetadata transformationMetadata;
    private Map<NormalizedQueryField, GraphQLError> forbiddenFields;
    private Service service;
    private Object serviceContext;
    private Map<String, Object> variableValues;
    private TransformationState transformations;

    public Transformer(ExecutionContext executionContext,
                       GraphQLSchema underlyingSchema,
                       Set<String> referencedFragmentNames,
                       Map<String, VariableDefinition> referencedVariables,
                       NadelContext nadelContext,
                       ServiceExecutionHooks serviceExecutionHooks,
                       OverallTypeInformation overallTypeInformation,
                       Map<String, Object> variableValues,
                       Service service,
                       Object serviceContext,
                       TransformationMetadata transformationMetadata,
                       Map<NormalizedQueryField, GraphQLError> forbiddenFields,
                       TransformationState transformations) {
        this.executionContext = executionContext;
        this.underlyingSchema = underlyingSchema;
        this.referencedFragmentNames = referencedFragmentNames;
        this.referencedVariables = referencedVariables;
        this.nadelContext = nadelContext;
        this.serviceExecutionHooks = serviceExecutionHooks;
        this.overallTypeInformation = overallTypeInformation;
        this.transformationMetadata = transformationMetadata;
        this.forbiddenFields = forbiddenFields;
        OperationDefinition operationDefinition = executionContext.getOperationDefinition();
        this.variableDefinitions = FpKit.getByName(operationDefinition.getVariableDefinitions(), VariableDefinition::getName);
        this.variableValues = variableValues;
        this.service = service;
        this.serviceContext = serviceContext;
        this.transformations = transformations;
    }

    @Override
    public TraversalControl visitVariableReference(VariableReference variableReference, TraverserContext<Node> context) {
        VariableDefinition variableDefinition = variableDefinitions.get(variableReference.getName());
        referencedVariables.put(variableDefinition.getName(), variableDefinition);
        return super.visitVariableReference(variableReference, context);
    }

    @Override
    public TraversalControl visitObjectField(ObjectField node, TraverserContext<Node> context) {

        UnderlyingTypeContext underlyingTypeContext = context.getVarFromParents(UnderlyingTypeContext.class);
        GraphQLUnmodifiedType unmodifiedType = unwrapAll(underlyingTypeContext.getInputValueDefinitionUnderlying().getType());
        //
        // technically a scalar type can have an AST object field - eg field( arg : Json) -> field(arg : { ast : "here" })
        if (unmodifiedType instanceof GraphQLInputObjectType) {
            GraphQLInputObjectType inputObjectType = (GraphQLInputObjectType) unmodifiedType;
            GraphQLInputObjectField inputObjectTypeField = inputObjectType.getField(node.getName());
            underlyingTypeContext = underlyingTypeContext.transform(builder -> builder.inputValueDefinitionUnderlying(inputObjectTypeField));
            context.setVar(UnderlyingTypeContext.class, underlyingTypeContext);
        }
        return TraversalControl.CONTINUE;
    }

    @Override
    public TraversalControl visitArgument(Argument argument, TraverserContext<Node> context) {

        UnderlyingTypeContext underlyingTypeContext = context.getVarFromParents(UnderlyingTypeContext.class);

        GraphQLFieldDefinition fieldDefinition = underlyingTypeContext.getFieldDefinitionUnderlying();
        GraphQLArgument graphQLArgument = fieldDefinition.getArgument(argument.getName());
        String argumentName = graphQLArgument.getName();
        Object argumentValue = underlyingTypeContext.getFieldArgumentValues().getOrDefault(argumentName, null);

        UnderlyingTypeContext newContext = underlyingTypeContext.transform(builder -> builder
                .argumentValue(argumentValue)
                .argumentDefinitionUnderlying(graphQLArgument)
                .inputValueDefinitionUnderlying(graphQLArgument));
        context.setVar(UnderlyingTypeContext.class, newContext);
        return TraversalControl.CONTINUE;
    }

    @Override
    protected TraversalControl visitValue(Value<?> value, TraverserContext<Node> context) {
        UnderlyingTypeContext typeContext = context.getVarFromParents(UnderlyingTypeContext.class);
        GraphQLInputValueDefinition inputValueDefinition = typeContext.getInputValueDefinitionUnderlying();

        OverallTypeInfo overallTypeInfo = overallTypeInformation.getOverallTypeInfo(getId(value));

        HooksVisitArgumentValueEnvironmentImpl hooksVisitArgumentValueEnvironment = new HooksVisitArgumentValueEnvironmentImpl(
                inputValueDefinition,
                overallTypeInfo.getGraphQLInputValueDefinition(),
                typeContext.getArgumentDefinitionUnderlying(),
                overallTypeInfo.getGraphQLArgument(),
                context,
                value,
                variableValues,
                service,
                serviceContext);

        NewVariableValue newVariableValue = serviceExecutionHooks.visitArgumentValueInQuery(hooksVisitArgumentValueEnvironment);
        if (newVariableValue != null) {
            variableValues.put(newVariableValue.getName(), newVariableValue.getValue());
        }

        return TraversalControl.CONTINUE;
    }


    @Override
    public TraversalControl visitField(Field field, TraverserContext<Node> context) {

        if (field.getName().equals(TypeNameMetaFieldDef.getName())) {
            return TraversalControl.CONTINUE;
        }

        UnderlyingTypeContext typeContext = context.getVarFromParents(UnderlyingTypeContext.class);
        OverallTypeInfo overallTypeInfo = overallTypeInformation.getOverallTypeInfo(getId(field));
        // this means we have a new field which was added by a transformation and we don't have overall type info about it
        if (overallTypeInfo == null) {
            updateTypeContext(context, typeContext.getOutputTypeUnderlying());
            return TraversalControl.CONTINUE;
        }

        GraphQLFieldDefinition fieldDefinitionOverall = overallTypeInfo.getFieldDefinition();
        GraphQLNamedOutputType fieldTypeOverall = (GraphQLNamedOutputType) GraphQLTypeUtil.unwrapAll(fieldDefinitionOverall.getType());
        String fieldContainerName = overallTypeInfo.getFieldsContainer().getName();

        NormalizedQueryFromAst normalizedOverallQuery = nadelContext.getNormalizedOverallQuery();
        List<NormalizedQueryField> normalizedFields = normalizedOverallQuery.getNormalizedFieldsByFieldId(getId(field));

        int numberOfRemovedNormalizedFields = filter(normalizedFields, normalizedField -> {
            GraphQLError forbiddenFieldError = forbiddenFields.get(normalizedField);
            if (forbiddenFieldError == null) {
                return false;
            }
            transformationMetadata.removeField(normalizedField, forbiddenFieldError);
            return true;
        }).size();

        if (!normalizedFields.isEmpty() && numberOfRemovedNormalizedFields == normalizedFields.size()) {
            return TreeTransformerUtil.deleteNode(context);
        }

        extractAndRecordTypeMappingDefinition(fieldTypeOverall.getName());
        FieldTransformation transformation = createTransformation(fieldDefinitionOverall);
        if (transformation != null) {
            //
            // major side effect alert - we are relying on transformation to call TreeTransformerUtil.changeNode
            // inside itself here
            //
            ApplyEnvironment applyEnvironment = createApplyEnvironment(field, context, overallTypeInfo, normalizedFields, executionContext.getFragmentsByName());
            ApplyResult applyResult = transformation.apply(applyEnvironment);
            Field changedField = (Field) applyEnvironment.getTraverserContext().thisNode();


            String fieldId = FieldMetadataUtil.getUniqueRootFieldId(changedField, this.transformationMetadata.getMetadataByFieldId());
            transformations.putFieldIdToTransformation(fieldId, transformation);
            transformations.putTransformationToFieldId(transformation, getId(changedField));

            if (transformation instanceof FieldRenameTransformation) {
                maybeAddUnderscoreTypeName(context, changedField, fieldTypeOverall);
            }
            if (applyResult.getTraversalControl() == TraversalControl.CONTINUE) {
                updateTypeContext(context, typeContext.getOutputTypeUnderlying(), fieldContainerName);
            }
            return applyResult.getTraversalControl();

        } else {
            maybeAddUnderscoreTypeName(context, field, fieldTypeOverall);
        }
        updateTypeContext(context, typeContext.getOutputTypeUnderlying(), fieldContainerName);
        return TraversalControl.CONTINUE;
    }

    private void updateTypeContext(TraverserContext<Node> context, GraphQLOutputType currentOutputTypeUnderlying) {
        updateTypeContext(context, currentOutputTypeUnderlying, null);
    }

    private void updateTypeContext(TraverserContext<Node> context, GraphQLOutputType currentOutputTypeUnderlying, String fieldContainerName) {
        Field newField = (Field) context.thisNode();
        GraphQLFieldsContainer fieldsContainerUnderlying = (GraphQLFieldsContainer) unwrapAll(currentOutputTypeUnderlying);
        assertTrue(fieldsContainerUnderlying instanceof GraphQLFieldsContainer, () -> String.format("Schema mismatch: The underlying schema is missing required interface type %s", fieldContainerName));

        GraphQLFieldDefinition fieldDefinitionUnderlying = Introspection.getFieldDef(underlyingSchema, fieldsContainerUnderlying, newField.getName());
        GraphQLOutputType newOutputTypeUnderlying = fieldDefinitionUnderlying.getType();


        Map<String, Object> argumentValues = valuesResolver.getArgumentValues(underlyingSchema.getCodeRegistry(), fieldDefinitionUnderlying.getArguments(), newField.getArguments(), executionContext.getVariables());
        UnderlyingTypeContext.Builder newTypeContext = newUnderlyingTypeContext()
                .field(newField)
                .outputTypeUnderlying(newOutputTypeUnderlying)
                .fieldsContainerUnderlying(fieldsContainerUnderlying)
                .fieldDefinitionUnderlying(fieldDefinitionUnderlying)
                .fieldArgumentValues(argumentValues);
        context.setVar(UnderlyingTypeContext.class, newTypeContext.build());
    }

    ApplyEnvironment createApplyEnvironment(Field field,
                                            TraverserContext<Node> context,
                                            OverallTypeInfo overallTypeInfo,
                                            List<NormalizedQueryField> normalizedQueryFields,
                                            Map<String, FragmentDefinition> fragmentDefinitionMap) {
        return new ApplyEnvironment(field,
                overallTypeInfo.getFieldDefinition(),
                overallTypeInfo.getFieldsContainer(),
                context,
                normalizedQueryFields,
                this.transformationMetadata.getMetadataByFieldId(),
                fragmentDefinitionMap);
    }


    private void maybeAddUnderscoreTypeName(TraverserContext<Node> traverserContext, Field field, GraphQLOutputType fieldType) {
        Field changedNode = ArtificialFieldUtils.maybeAddUnderscoreTypeName(nadelContext, field, fieldType);
        if (changedNode != field) {
            TreeTransformerUtil.changeNode(traverserContext, changedNode);
            transformations.addHintTypename(changedNode.getName());
        }
    }


    @Override
    public TraversalControl visitInlineFragment(InlineFragment inlineFragment, TraverserContext<Node> context) {
        // inline fragments are allowed not have type conditions, if so the parent type counts

        TypeName typeCondition = inlineFragment.getTypeCondition();
        if (typeCondition == null) {
            return TraversalControl.CONTINUE;
        }

        TypeMappingDefinition typeMappingDefinition = typeTransformationForFragment(executionContext, typeCondition);
        String underlyingTypeName = typeCondition.getName();
        if (typeMappingDefinition != null) {
            recordTypeRename(typeMappingDefinition);
            InlineFragment changedFragment = inlineFragment.transform(f -> {
                TypeName newTypeName = newTypeName(typeMappingDefinition.getUnderlyingName()).build();
                f.typeCondition(newTypeName);
            });
            underlyingTypeName = typeMappingDefinition.getUnderlyingName();
            changeNode(context, changedFragment);
        }
        updateTypeContextForInlineFragment(underlyingTypeName, context);
        //TODO: what if all fields inside inline fragment get deleted? we should recheck it on LEAVING the node
        //(after transformations are applied); So we can see what happened. Alternative would be  to do second pass
        return TraversalControl.CONTINUE;
    }


    private void updateTypeContextForInlineFragment(String underlyingType, TraverserContext<Node> context) {
        UnderlyingTypeContext typeContext = context.getVarFromParents(UnderlyingTypeContext.class);
        GraphQLCompositeType fragmentConditionUnderlying = (GraphQLCompositeType) underlyingSchema.getType(underlyingType);
        context.setVar(UnderlyingTypeContext.class, typeContext.transform(builder -> builder
                .outputTypeUnderlying(fragmentConditionUnderlying)));
    }

    @Override
    public TraversalControl visitFragmentDefinition(FragmentDefinition fragment, TraverserContext<Node> context) {
        TypeName typeName = fragment.getTypeCondition();
        TypeMappingDefinition typeMappingDefinition = typeTransformationForFragment(executionContext, typeName);
        String underlyingTypeName = typeName.getName();
        if (typeMappingDefinition != null) {
            recordTypeRename(typeMappingDefinition);
            FragmentDefinition changedFragment = fragment.transform(f -> {
                TypeName newTypeName = newTypeName(typeMappingDefinition.getUnderlyingName()).build();
                f.typeCondition(newTypeName);
            });
            underlyingTypeName = typeMappingDefinition.getUnderlyingName();
            changeNode(context, changedFragment);
        }

        updateTypeContextForFragmentDefinition(fragment, underlyingTypeName, context);
        return TraversalControl.CONTINUE;
    }

    private void updateTypeContextForFragmentDefinition(FragmentDefinition fragmentDefinition, String underlyingTypeName, TraverserContext<Node> context) {
        UnderlyingTypeContext typeContext = context.getVarFromParents(UnderlyingTypeContext.class);
        GraphQLCompositeType fragmentConditionUnderlying = (GraphQLCompositeType) underlyingSchema.getType(underlyingTypeName);
        context.setVar(UnderlyingTypeContext.class, typeContext.transform(builder -> builder
                .outputTypeUnderlying(fragmentConditionUnderlying)));
    }


    @Override
    public TraversalControl visitFragmentSpread(FragmentSpread fragmentSpread, TraverserContext<Node> context) {
        referencedFragmentNames.add(fragmentSpread.getName());
        return TraversalControl.CONTINUE;
    }

    private TypeMappingDefinition recordTypeRename(TypeMappingDefinition typeMappingDefinition) {
        if (typeMappingDefinition != null) {
            transformations.putTypeRenameMapping(typeMappingDefinition.getUnderlyingName(), typeMappingDefinition.getOverallName());
        }
        return typeMappingDefinition;
    }

    @SuppressWarnings("ConstantConditions")
    private TypeMappingDefinition typeTransformationForFragment(ExecutionContext executionContext, TypeName typeNameOverall) {
        GraphQLType type = executionContext.getGraphQLSchema().getType(typeNameOverall.getName());
        assertTrue(type instanceof GraphQLFieldsContainer, () -> String.format("Expected type '%s' to be an field container type", typeNameOverall));
        return extractAndRecordTypeMappingDefinition(executionContext.getGraphQLSchema(), type);
    }


    @SuppressWarnings("ConstantConditions")
    private TypeMappingDefinition extractAndRecordTypeMappingDefinition(String typeNameOverall) {
        GraphQLType type = executionContext.getGraphQLSchema().getType(typeNameOverall);
        return extractAndRecordTypeMappingDefinition(executionContext.getGraphQLSchema(), type);
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    private TypeMappingDefinition extractAndRecordTypeMappingDefinition(GraphQLSchema graphQLSchema, GraphQLType type) {

        TypeMappingDefinition typeMappingDefinition = getTypeMappingDefinitionFor(type);
        recordTypeRename(typeMappingDefinition);

        if (type instanceof GraphQLInterfaceType) {
            GraphQLInterfaceType interfaceType = (GraphQLInterfaceType) type;

            graphQLSchema.getImplementations(interfaceType).forEach(objectType -> {
                extractAndRecordTypeMappingDefinition(graphQLSchema, objectType);
            });
        }
        if (type instanceof GraphQLUnionType) {
            GraphQLUnionType unionType = (GraphQLUnionType) type;
            unionType.getTypes().forEach(typeMember -> {
                extractAndRecordTypeMappingDefinition(graphQLSchema, typeMember);
            });
        }
        return typeMappingDefinition;
    }

    private graphql.nadel.dsl.FieldTransformation transformationDefinitionForField(FieldDefinition definition) {
        if (definition instanceof ExtendedFieldDefinition) {
            return ((ExtendedFieldDefinition) definition).getFieldTransformation();
        }
        return null;
    }


    private FieldTransformation createTransformation(GraphQLFieldDefinition fieldDefinitionOverallSchema) {

        UnderlyingServiceHydration hydration = NadelDirectives.createUnderlyingServiceHydration(fieldDefinitionOverallSchema);
        if (hydration != null) {
            return new HydrationTransformation(hydration);
        }

        FieldMappingDefinition mappingDefinition = NadelDirectives.createFieldMapping(fieldDefinitionOverallSchema);
        if (mappingDefinition != null) {
            return new FieldRenameTransformation(mappingDefinition);
        }

        graphql.nadel.dsl.FieldTransformation definition = transformationDefinitionForField(fieldDefinitionOverallSchema.getDefinition());

        if (definition == null) {
            return null;
        }
        if (definition.getFieldMappingDefinition() != null) {
            return new FieldRenameTransformation(definition.getFieldMappingDefinition());
        } else if (definition.getUnderlyingServiceHydration() != null) {
            return new HydrationTransformation(definition.getUnderlyingServiceHydration());
        } else {
            return assertShouldNeverHappen();
        }
    }

}
