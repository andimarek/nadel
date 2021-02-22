package graphql.nadel.schema;

import graphql.Assert;
import graphql.language.BooleanValue;
import graphql.language.Description;
import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.IntValue;
import graphql.language.NonNullType;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.TypeName;
import graphql.nadel.dsl.FieldMappingDefinition;
import graphql.nadel.dsl.RemoteArgumentDefinition;
import graphql.nadel.dsl.RemoteArgumentSource;
import graphql.nadel.dsl.TypeMappingDefinition;
import graphql.nadel.dsl.UnderlyingServiceHydration;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertTrue;
import static graphql.introspection.Introspection.DirectiveLocation.ENUM;
import static graphql.introspection.Introspection.DirectiveLocation.FIELD_DEFINITION;
import static graphql.introspection.Introspection.DirectiveLocation.INPUT_OBJECT;
import static graphql.introspection.Introspection.DirectiveLocation.INTERFACE;
import static graphql.introspection.Introspection.DirectiveLocation.OBJECT;
import static graphql.introspection.Introspection.DirectiveLocation.SCALAR;
import static graphql.introspection.Introspection.DirectiveLocation.UNION;
import static graphql.language.DirectiveLocation.newDirectiveLocation;
import static graphql.language.InputValueDefinition.newInputValueDefinition;
import static graphql.language.ListType.newListType;
import static graphql.language.TypeName.newTypeName;
import static graphql.nadel.dsl.RemoteArgumentSource.SourceType.CONTEXT;
import static graphql.nadel.dsl.RemoteArgumentSource.SourceType.FIELD_ARGUMENT;
import static graphql.nadel.dsl.RemoteArgumentSource.SourceType.OBJECT_FIELD;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public class NadelDirectives {

    static final DirectiveDefinition RENAMED_DIRECTIVE_DEFINITION;
    static final DirectiveDefinition HYDRATED_DIRECTIVE_DEFINITION;
    static final InputObjectTypeDefinition NADEL_HYDRATION_ARGUMENT_DEFINITION;

    static {
        RENAMED_DIRECTIVE_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
                .name("renamed")
                .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
                .directiveLocation(newDirectiveLocation().name(OBJECT.name()).build())
                .directiveLocation(newDirectiveLocation().name(INTERFACE.name()).build())
                .directiveLocation(newDirectiveLocation().name(UNION.name()).build())
                .directiveLocation(newDirectiveLocation().name(INPUT_OBJECT.name()).build())
                .directiveLocation(newDirectiveLocation().name(SCALAR.name()).build())
                .directiveLocation(newDirectiveLocation().name(ENUM.name()).build())
                .description(createDescription("This allows you to rename a type or field in the overall schema"))
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("from")
                                .description(createDescription("The type to be renamed"))
                                .type(nonNull("String"))
                                .build())
                .build();

        NADEL_HYDRATION_ARGUMENT_DEFINITION = InputObjectTypeDefinition.newInputObjectDefinition()
                .name("NadelHydrationArgument")
                .description(createDescription("This allows you to hydrate new values into fields"))
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("name")
                                .type(nonNull("String"))
                                .build())
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("value")
                                .type(nonNull("String"))
                                .build())
                .build();

        HYDRATED_DIRECTIVE_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
                .name("hydrated")
                .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
                .description(createDescription("This allows you to hydrate new values into fields"))
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("service")
                                .description(createDescription("The target service"))
                                .type(nonNull("String"))
                                .build())
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("field")
                                .description(createDescription("The target top level field"))
                                .type(nonNull("String"))
                                .build())
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("identifiedBy")
                                .description(createDescription("How to identify matching results"))
                                .type(nonNull("String"))
                                .defaultValue(StringValue.newStringValue("id").build())
                                .build())
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("indexed")
                                .description(createDescription("Are results indexed"))
                                .type(typeOf("Boolean"))
                                .defaultValue(BooleanValue.newBooleanValue(false).build())
                                .build())
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("batchSize")
                                .description(createDescription("The batch size"))
                                .type(typeOf("Int"))
                                .defaultValue(IntValue.newIntValue().value(BigInteger.valueOf(200)).build())
                                .build())
                .inputValueDefinition(
                        newInputValueDefinition()
                                .name("arguments")
                                .description(createDescription("The arguments to the hydrated field"))
                                .type(newListType().type(nonNull("NadelHydrationArgument")).build())
                                .build())
                .build();

    }

    private static TypeName typeOf(String typename) {
        return newTypeName().name(typename).build();
    }

    private static NonNullType nonNull(String typeName) {
        return NonNullType.newNonNullType(typeOf(typeName)).build();
    }

    private static Description createDescription(String s) {
        return new Description(s, null, false);
    }

    public static UnderlyingServiceHydration createUnderlyingServiceHydration(GraphQLFieldDefinition fieldDefinition) {
        GraphQLDirective directive = fieldDefinition.getDirective(HYDRATED_DIRECTIVE_DEFINITION.getName());
        if (directive == null) {
            return null;
        }

        String service = getDirectiveValue(directive, "service", String.class);
        String field = getDirectiveValue(directive, "field", String.class);
        String objectIdentifier = getDirectiveValue(directive, "identifiedBy", String.class);
        Boolean objectIndexed = getDirectiveValue(directive, "indexed", Boolean.class, false);
        if (objectIndexed) {
            objectIdentifier = null; // we cant have both but it has a default
        }
        int batchSize = getDirectiveValue(directive, "batchSize", Integer.class, 200);
        List<RemoteArgumentDefinition> arguments = createArgs(directive.getArgument("arguments").getValue());

        List<String> fieldNames = dottedString(field);
        assertTrue(fieldNames.size() >= 1);
        String topLevelFieldName = fieldNames.get(0);
        String syntheticField = null;
        if (fieldNames.size() > 1) {
            syntheticField = fieldNames.get(0);
            topLevelFieldName = fieldNames.get(1);
        }

        return new UnderlyingServiceHydration(emptySrc(), emptyList(),
                service,
                topLevelFieldName,
                syntheticField,
                arguments,
                objectIdentifier,
                objectIndexed,
                batchSize,
                emptyMap()
        );
    }

    @SuppressWarnings("unchecked")
    private static List<RemoteArgumentDefinition> createArgs(Object arguments) {
        List<RemoteArgumentDefinition> remoteArgumentDefinitions = new ArrayList<>();
        List<Object> args = (List<Object>) arguments;
        for (Object arg : args) {
            Map<String, String> argMap = (Map<String, String>) arg;

            String remoteArgName = argMap.get("name");
            String remoteArgValue = argMap.get("value");
            RemoteArgumentSource remoteArgumentSource = createRemoteArgumentSource(remoteArgValue);
            RemoteArgumentDefinition remoteArgumentDefinition = new RemoteArgumentDefinition(remoteArgName, remoteArgumentSource, emptySrc(), emptyMap());
            remoteArgumentDefinitions.add(remoteArgumentDefinition);
        }
        return remoteArgumentDefinitions;
    }

    private static RemoteArgumentSource createRemoteArgumentSource(String value) {
        List<String> values = dottedString(String.valueOf(value));
        assertTrue(values.size() >= 2);

        String type = values.get(0);
        RemoteArgumentSource.SourceType argumentType = null;
        String argumentName = null;
        List<String> path = null;
        if ("$source".equals(type)) {
            argumentType = OBJECT_FIELD;
            path = values.subList(1, values.size());
        } else if ("$argument".equals(type)) {
            argumentType = FIELD_ARGUMENT;
            argumentName = values.get(1);
        } else if ("$context".equals(type)) {
            argumentType = CONTEXT;
            argumentName = values.get(1);
        } else {
            Assert.assertShouldNeverHappen("The hydration arguments are invalid : %s", value);
        }
        return new RemoteArgumentSource(argumentName, path, argumentType, emptySrc(), emptyMap());
    }

    public static FieldMappingDefinition createFieldMapping(GraphQLFieldDefinition fieldDefinition) {
        GraphQLDirective directive = fieldDefinition.getDirective(RENAMED_DIRECTIVE_DEFINITION.getName());
        if (directive == null) {
            return null;
        }

        String fromValue = getDirectiveValue(directive, "from", String.class);
        List<String> fromPath = dottedString(String.valueOf(fromValue));
        return new FieldMappingDefinition(fromPath, emptySrc(), emptyList(), emptyMap());
    }

    public static TypeMappingDefinition createTypeMapping(GraphQLDirectiveContainer directivesContainer) {
        GraphQLDirective directive = directivesContainer.getDirective(RENAMED_DIRECTIVE_DEFINITION.getName());
        if (directive == null) {
            return null;
        }

        String from = getDirectiveValue(directive, "from", String.class);
        TypeMappingDefinition typeMappingDefinition = new TypeMappingDefinition(emptySrc(), emptyList(), emptyMap());
        typeMappingDefinition.setOverallName(directivesContainer.getName());
        typeMappingDefinition.setUnderlyingName(from);
        return typeMappingDefinition;
    }


    private static List<String> dottedString(String from) {
        String[] split = from.split("\\.");
        return Arrays.asList(split);
    }

    private static SourceLocation emptySrc() {
        return new SourceLocation(-1, -1);
    }

    private static <T> T getDirectiveValue(GraphQLDirective directive, String name, Class<T> clazz) {
        GraphQLArgument argument = directive.getArgument(name);
        assertNotNull(argument, () -> String.format("The @%s directive argument '%s argument MUST be present - how is this possible?", directive.getName(), name));
        Object value = argument.getValue();
        return clazz.cast(value);
    }

    private static <T> T getDirectiveValue(GraphQLDirective directive, String name, Class<T> clazz, T defaultValue) {
        T value = getDirectiveValue(directive, name, clazz);
        if (value == null) {
            return defaultValue;
        }
        return value;
    }

}