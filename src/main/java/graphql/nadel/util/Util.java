package graphql.nadel.util;

import graphql.Assert;
import graphql.Internal;
import graphql.execution.MissingRootTypeException;
import graphql.language.Definition;
import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationDefinition;
import graphql.language.SDLDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.nadel.DefinitionRegistry;
import graphql.nadel.dsl.CommonDefinition;
import graphql.nadel.dsl.EnumTypeDefinitionWithTransformation;
import graphql.nadel.dsl.InputObjectTypeDefinitionWithTransformation;
import graphql.nadel.dsl.InterfaceTypeDefinitionWithTransformation;
import graphql.nadel.dsl.ObjectTypeDefinitionWithTransformation;
import graphql.nadel.dsl.ScalarTypeDefinitionWithTransformation;
import graphql.nadel.dsl.ServiceDefinition;
import graphql.nadel.dsl.StitchingDsl;
import graphql.nadel.dsl.TypeMappingDefinition;
import graphql.nadel.dsl.UnionTypeDefinitionWithTransformation;
import graphql.nadel.schema.NadelDirectives;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.List;

import static graphql.Assert.assertShouldNeverHappen;
import static graphql.language.OperationDefinition.Operation.MUTATION;
import static graphql.language.OperationDefinition.Operation.QUERY;
import static graphql.language.OperationDefinition.Operation.SUBSCRIPTION;

@Internal
public class Util {

    public static DefinitionRegistry buildServiceRegistry(List<CommonDefinition> commonDefinitions) {
        DefinitionRegistry definitionRegistry = new DefinitionRegistry();
        for (CommonDefinition commonDefinition : commonDefinitions) {
            for (Definition<?> definition : commonDefinition.getTypeDefinitions()) {
                definitionRegistry.add((SDLDefinition<?>) definition);
            }

        }
        return definitionRegistry;
    }

    public static DefinitionRegistry buildServiceRegistry(ServiceDefinition serviceDefinition) {
        DefinitionRegistry definitionRegistry = new DefinitionRegistry();
        for (Definition<?> definition : serviceDefinition.getTypeDefinitions()) {
            definitionRegistry.add((SDLDefinition<?>) definition);
        }
        return definitionRegistry;
    }


    public static ServiceDefinition buildServiceDefinition(String serviceName, StitchingDsl stitchingDsl) {
        ServiceDefinition serviceDefinition;
        if (stitchingDsl.getServiceDefinition() == null) {
            // if we don't have an actual `service X { .. }` then we must have plain old graphql type definitions
            serviceDefinition = ServiceDefinition.newServiceDefinition()
                    .name(serviceName).addDefinitions(stitchingDsl.getSDLDefinitions())
                    .build();
        } else {
            serviceDefinition = stitchingDsl.getServiceDefinition();
        }
        //
        // its possible that a nadel service definition MIGHT not be the sane name as its outer container name
        // so lets assert that to ensure sanity
        Assert.assertTrue(serviceDefinition.getName().equals(serviceName),
                () -> String.format("The outer service name is '%s' but internally its declared as '%s'.  They MUST be the same.", serviceName, serviceDefinition.getName()));

        return serviceDefinition;
    }

    public static boolean isInterfaceOrUnionField(GraphQLOutputType fieldOutputType) {
        return GraphQLTypeUtil.unwrapAll(fieldOutputType) instanceof GraphQLInterfaceType || fieldOutputType instanceof GraphQLUnionType;
    }

    public static boolean isScalar(GraphQLOutputType fieldOutputType) {
        return GraphQLTypeUtil.unwrapAll(fieldOutputType) instanceof GraphQLScalarType;
    }

    public static TypeMappingDefinition getTypeMappingDefinitionFor(GraphQLType type) {
        TypeMappingDefinition typeMappingDefinition = null;
        if (type instanceof GraphQLDirectiveContainer) {
            typeMappingDefinition = NadelDirectives.createTypeMapping((GraphQLDirectiveContainer) type);
            if (typeMappingDefinition != null) {
                return typeMappingDefinition;
            }
        }
        if (type instanceof GraphQLObjectType) {
            ObjectTypeDefinition definition = ((GraphQLObjectType) type).getDefinition();
            if (definition instanceof ObjectTypeDefinitionWithTransformation) {
                typeMappingDefinition = ((ObjectTypeDefinitionWithTransformation) definition).getTypeMappingDefinition();
            }
        }
        if (type instanceof GraphQLInterfaceType) {
            InterfaceTypeDefinition definition = ((GraphQLInterfaceType) type).getDefinition();
            if (definition instanceof InterfaceTypeDefinitionWithTransformation) {
                typeMappingDefinition = ((InterfaceTypeDefinitionWithTransformation) definition).getTypeMappingDefinition();
            }
        }
        if (type instanceof GraphQLUnionType) {
            UnionTypeDefinition definition = ((GraphQLUnionType) type).getDefinition();
            if (definition instanceof UnionTypeDefinitionWithTransformation) {
                typeMappingDefinition = ((UnionTypeDefinitionWithTransformation) definition).getTypeMappingDefinition();
            }
        }
        if (type instanceof GraphQLInputObjectType) {
            InputObjectTypeDefinition definition = ((GraphQLInputObjectType) type).getDefinition();
            if (definition instanceof InputObjectTypeDefinitionWithTransformation) {
                typeMappingDefinition = ((InputObjectTypeDefinitionWithTransformation) definition).getTypeMappingDefinition();
            }
        }
        if (type instanceof GraphQLEnumType) {
            EnumTypeDefinition definition = ((GraphQLEnumType) type).getDefinition();
            if (definition instanceof EnumTypeDefinitionWithTransformation) {
                typeMappingDefinition = ((EnumTypeDefinitionWithTransformation) definition).getTypeMappingDefinition();
            }
        }
        if (type instanceof GraphQLScalarType) {
            ScalarTypeDefinition definition = ((GraphQLScalarType) type).getDefinition();
            if (definition instanceof ScalarTypeDefinitionWithTransformation) {
                typeMappingDefinition = ((ScalarTypeDefinitionWithTransformation) definition).getTypeMappingDefinition();
            }
        }
        return typeMappingDefinition;
    }


    public static GraphQLObjectType getOperationRootType(GraphQLSchema graphQLSchema, OperationDefinition operationDefinition) {
        OperationDefinition.Operation operation = operationDefinition.getOperation();
        if (operation == MUTATION) {
            GraphQLObjectType mutationType = graphQLSchema.getMutationType();
            if (mutationType == null) {
                throw new MissingRootTypeException("Schema is not configured for mutations.", operationDefinition.getSourceLocation());
            }
            return mutationType;
        } else if (operation == QUERY) {
            GraphQLObjectType queryType = graphQLSchema.getQueryType();
            if (queryType == null) {
                throw new MissingRootTypeException("Schema does not define the required query root type.", operationDefinition.getSourceLocation());
            }
            return queryType;
        } else if (operation == SUBSCRIPTION) {
            GraphQLObjectType subscriptionType = graphQLSchema.getSubscriptionType();
            if (subscriptionType == null) {
                throw new MissingRootTypeException("Schema is not configured for subscriptions.", operationDefinition.getSourceLocation());
            }
            return subscriptionType;
        } else {
            return assertShouldNeverHappen("Unhandled case.  An extra operation enum has been added without code support");
        }
    }
}
