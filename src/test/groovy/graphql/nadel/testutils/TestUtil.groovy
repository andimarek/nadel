package graphql.nadel.testutils

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.ser.FilterProvider
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.TypeResolutionEnvironment
import graphql.execution.ExecutionId
import graphql.execution.MergedField
import graphql.execution.MergedSelectionSet
import graphql.execution.nextgen.ExecutionHelper
import graphql.introspection.Introspection
import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.Node
import graphql.language.OperationDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.SelectionSet
import graphql.nadel.DefinitionRegistry
import graphql.nadel.NSDLParser
import graphql.nadel.NadelGraphQLParser
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionFactory
import graphql.nadel.ServiceExecutionParameters
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.engine.NadelContext
import graphql.nadel.normalized.NormalizedQueryFactory
import graphql.nadel.normalized.NormalizedQueryFromAst
import graphql.nadel.schema.NeverWiringFactory
import graphql.nadel.schema.OverallSchemaGenerator
import graphql.nadel.util.FpKit
import graphql.nadel.util.Util
import graphql.schema.Coercing
import graphql.schema.DataFetcher
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.TypeResolver
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.TypeRuntimeWiring
import graphql.schema.idl.WiringFactory
import graphql.schema.idl.errors.SchemaProblem
import groovy.json.JsonSlurper

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import java.util.stream.Collectors

import static graphql.ExecutionInput.newExecutionInput

class TestUtil {

    private static String printAstAsJson(Node node) {
        String[] ignoredProperties = [
                "sourceLocation", "children", "ignoredChars", "namedChildren", "directivesByName", "additionalData"
        ]
        SimpleBeanPropertyFilter theFilter = SimpleBeanPropertyFilter
                .serializeAllExcept(ignoredProperties) as SimpleBeanPropertyFilter
        FilterProvider filters = new SimpleFilterProvider()
        filters.addFilter("myFilter" as String, theFilter as SimpleBeanPropertyFilter)

        ObjectMapper mapper = new ObjectMapper()
        mapper.addMixIn(Object.class, FilterMixin.class)
        mapper.disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS)
        mapper.disable(SerializationFeature.WRITE_NULL_MAP_VALUES)
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        mapper.setFilterProvider(filters)
        def s = mapper.writeValueAsString(node)
        println(s)
        return s
    }

    static Map astAsMap(Node node) {
        def json = printAstAsJson(node)
        return new JsonSlurper().parseText(json)
    }

    static Map expectedJson(String fileName) {
        def stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName)
        assert stream != null, "bad file read of $fileName"
        return new JsonSlurper().parseText(stream.text)
    }


    static Field mkField(String fieldText) {
        def q = "{ $fieldText }"
        def parser = new NadelGraphQLParser()
        def document = parser.parseDocument(q)
        def definition = document.getDefinitions()[0] as OperationDefinition
        def selectionSet = definition.getChildren()[0] as SelectionSet
        def field = selectionSet.selections[0] as Field
        return field
    }

    static Map<String, FragmentDefinition> mkFragments(String fragmentText) {
        def parser = new NadelGraphQLParser()
        def document = parser.parseDocument(fragmentText)
        def frags = document.getDefinitions().findAll({ it instanceof FragmentDefinition })
        def map = FpKit.getByName(frags, { (it as FragmentDefinition).name })
        return map
    }


    static ServiceExecutionResult serviceResultFrom(ExecutionResult er) {
        def toSpecification = er.toSpecification()
        def data = toSpecification.get("data")
        def errors = toSpecification.get("errors")
        return new ServiceExecutionResult(data, errors ?: [])
    }

    static CompletableFuture<ServiceExecutionResult> serviceResultFromPromise(CompletableFuture<ExecutionResult> promise) {
        return promise.thenApply({ er -> serviceResultFrom(er) })
    }

    static GraphQLSchema schemaFile(String fileName) {
        return schemaFile(fileName, mockRuntimeWiring)
    }


    static GraphQLSchema schemaFromResource(String resourceFileName, RuntimeWiring wiring) {
        def stream = TestUtil.class.getClassLoader().getResourceAsStream(resourceFileName)
        return schema(stream, wiring)
    }


    static GraphQLSchema schemaFile(String fileName, RuntimeWiring wiring) {
        def stream = TestUtil.class.getClassLoader().getResourceAsStream(fileName)

        def typeRegistry = new SchemaParser().parse(new InputStreamReader(stream))
        def options = SchemaGenerator.Options.defaultOptions()
        def schema = new SchemaGenerator().makeExecutableSchema(options, typeRegistry, wiring)
        schema
    }

    static GraphQLSchema schema(String spec, Map<String, Map<String, DataFetcher>> dataFetchers) {
        def wiring = RuntimeWiring.newRuntimeWiring()
        dataFetchers.each { type, fieldFetchers ->
            def tw = TypeRuntimeWiring.newTypeWiring(type).dataFetchers(fieldFetchers)
            wiring.type(tw)
        }
        schema(spec, wiring)
    }

    static GraphQLSchema schema(String spec, RuntimeWiring.Builder runtimeWiring) {
        schema(spec, runtimeWiring.build())
    }


    static TypeDefinitionRegistry typeDefinitions(String spec) {
        try {
            def registry = new SchemaParser().parse(spec)
            return registry
        } catch (SchemaProblem e) {
            assert false: "The underlying schema could not be compiled : ${e}"
            return null
        }
    }

    static GraphQLSchema schema(String spec) {
        schema(spec, mockRuntimeWiring)
    }

    static GraphQLSchema schema(Reader specReader) {
        schema(specReader, mockRuntimeWiring)
    }

    static GraphQLSchema schema(String spec, RuntimeWiring runtimeWiring) {
        schema(new StringReader(spec), runtimeWiring)
    }

    static GraphQLSchema schema(InputStream specStream, RuntimeWiring runtimeWiring) {
        schema(new InputStreamReader(specStream), runtimeWiring)
    }

    static GraphQLSchema schema(Reader specReader, RuntimeWiring runtimeWiring) {
        try {
            def registry = new SchemaParser().parse(specReader)
            def options = SchemaGenerator.Options.defaultOptions()
            return new SchemaGenerator().makeExecutableSchema(options, registry, runtimeWiring)
        } catch (SchemaProblem e) {
            assert false: "The schema could not be compiled : ${e}"
            return null
        }
    }

    static GraphQLSchema schema(SchemaGenerator.Options options, String spec, RuntimeWiring runtimeWiring) {
        try {
            def registry = new SchemaParser().parse(spec)
            return new SchemaGenerator().makeExecutableSchema(options, registry, runtimeWiring)
        } catch (SchemaProblem e) {
            assert false: "The schema could not be compiled : ${e}"
            return null
        }
    }

    static GraphQLSchema schemaFromNdsl(Map<String, String> serviceDSLs) {
        def defRegistries = []
        for (Map.Entry<String, String> e : serviceDSLs.entrySet()) {
            def serviceName = e.getKey()
            def stitchingDsl = new NSDLParser().parseDSL(e.getValue())
            def serviceDefinition = Util.buildServiceDefinition(serviceName, stitchingDsl)
            def definitionRegistry = Util.buildServiceRegistry(serviceDefinition)
            defRegistries.add(definitionRegistry)
        }
        return new OverallSchemaGenerator().buildOverallSchema(defRegistries, new DefinitionRegistry(), new NeverWiringFactory())
    }

    static GraphQL.Builder graphQL(String spec) {
        return graphQL(new StringReader(spec), mockRuntimeWiring)
    }

    static GraphQL.Builder graphQL(String spec, RuntimeWiring runtimeWiring) {
        return graphQL(new StringReader(spec), runtimeWiring)
    }

    static GraphQL.Builder graphQL(String spec, RuntimeWiring.Builder runtimeWiring) {
        return graphQL(new StringReader(spec), runtimeWiring.build())
    }

    static GraphQL.Builder graphQL(String spec, Map<String, Map<String, DataFetcher>> dataFetchers) {
        toGraphqlBuilder({ -> schema(spec, dataFetchers) })
    }

    static GraphQL.Builder graphQL(Reader specReader, RuntimeWiring runtimeWiring) {
        return toGraphqlBuilder({ -> schema(specReader, runtimeWiring) })
    }

    private static GraphQL.Builder toGraphqlBuilder(Supplier<GraphQLSchema> supplier) {
        try {
            def schema = supplier.get()
            return GraphQL.newGraphQL(schema)
        } catch (SchemaProblem e) {
            assert false: "The schema could not be compiled : ${e}"
            return null
        }
    }

    static WiringFactory mockWiringFactory = new MockedWiringFactory()

    static RuntimeWiring mockRuntimeWiring = RuntimeWiring.newRuntimeWiring().wiringFactory(mockWiringFactory).build()

    static GraphQLScalarType mockScalar(String name) {
        new GraphQLScalarType(name, name, mockCoercing())
    }

    private static Coercing mockCoercing() {
        new Coercing() {
            @Override
            Object serialize(Object dataFetcherResult) {
                return null
            }

            @Override
            Object parseValue(Object input) {
                return null
            }

            @Override
            Object parseLiteral(Object input) {
                return null
            }
        }
    }

    static GraphQLScalarType mockScalar(ScalarTypeDefinition definition) {
        new GraphQLScalarType(
                definition.getName(),
                definition.getDescription() == null ? null : definition.getDescription().getContent(),
                mockCoercing(),
                definition.getDirectives().stream().map({
                    mockDirective(it.getName())
                }).collect(Collectors.toList()),
                definition)
    }

    static GraphQLDirective mockDirective(String name) {
        new GraphQLDirective(name, name, EnumSet.noneOf(Introspection.DirectiveLocation.class), Collections.emptyList(), false, false, false)
    }

    static TypeRuntimeWiring mockTypeRuntimeWiring(String typeName, boolean withResolver) {
        def builder = TypeRuntimeWiring.newTypeWiring(typeName)
        if (withResolver) {
            builder.typeResolver(new TypeResolver() {
                @Override
                GraphQLObjectType getType(TypeResolutionEnvironment env) {
                    return null
                }
            })
        }
        return builder.build()
    }


    static Document parseQuery(String query) {
        new NadelGraphQLParser().parseDocument(query)
    }

    static NormalizedQueryFromAst createNormalizedQuery(GraphQLSchema schema, Document document) {
        NormalizedQueryFactory normalizedQueryFactory = new NormalizedQueryFactory()
        return normalizedQueryFactory.createNormalizedQuery(schema, document, null, [:])
    }

    static Field parseField(String sdlField) {
        String spec = """ query Foo {
        $sdlField
        }
        """
        def document = parseQuery(spec)
        def op = document.getDefinitionsOfType(OperationDefinition.class)[0]
        return op.getSelectionSet().getSelectionsOfType(Field.class)[0] as Field
    }

    static MergedField parseMergedField(String sdlField) {
        def field = parseField(sdlField)
        MergedField.newMergedField(field).build()
    }

    static MergedField mergedField(List<Field> fields) {
        return MergedField.newMergedField(fields).build()
    }

    static MergedField mergedField(Field field) {
        return MergedField.newMergedField(field).build()
    }

    static MergedSelectionSet mergedSelectionSet(Map<String, MergedField> subFields) {
        return MergedSelectionSet.newMergedSelectionSet().subFields(subFields).build()
    }


    static def executionData(GraphQLSchema schema, Document query, Map variables = [:]) {
        def normalizedQuery = new NormalizedQueryFactory().createNormalizedQuery(schema, query, null, variables)
        def nadelContext = NadelContext.newContext()
                .originalOperationName(query, null)
                .normalizedOverallQuery(normalizedQuery)
                .artificialFieldsUUID("UUID")
                .build()
        ExecutionInput executionInput = newExecutionInput()
                .query(AstPrinter.printAst(query))
                .context(nadelContext)
                .variables(variables)
                .build()
        ExecutionHelper executionHelper = new ExecutionHelper()
        def executionData = executionHelper.createExecutionData(query, schema, ExecutionId.generate(), executionInput, null)
        [executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext)]
    }


    static ServiceExecutionFactory serviceFactory(ServiceExecution delegatedExecution, TypeDefinitionRegistry underlyingSchema) {
        new ServiceExecutionFactory() {
            @Override
            ServiceExecution getServiceExecution(String serviceName) {
                return delegatedExecution
            }

            @Override
            TypeDefinitionRegistry getUnderlyingTypeDefinitions(String serviceName) {
                underlyingSchema
            }
        }
    }

    static ServiceExecutionFactory serviceFactory(Map<String, Tuple2<ServiceExecution, TypeDefinitionRegistry>> serviceMap) {
        new ServiceExecutionFactory() {
            @Override
            ServiceExecution getServiceExecution(String serviceName) {
                return serviceMap.get(serviceName).get(0)
            }

            @Override
            TypeDefinitionRegistry getUnderlyingTypeDefinitions(String serviceName) {
                return serviceMap.get(serviceName).get(1)
            }
        }
    }

    static ServiceExecution serviceExecutionImpl(GraphQL graphQL) {
        ServiceExecution serviceExecution = { parameters ->
            def queryText = AstPrinter.printAst(parameters.query)
            def executionInput = newExecutionInput()
                    .query(queryText)
                    .operationName(parameters.operationDefinition.name)
                    .variables(parameters.variables)
                    .context(parameters.context)
                    .build()
            def er = graphQL.executeAsync(executionInput)
            return serviceResultFromPromise(er)
        }
        return serviceExecution
    }

    static ServiceExecution delayedServiceExecution(int ms, ServiceExecution serviceExecution) {
        if (ms > 0) {
            return new ServiceExecution() {
                @Override
                CompletableFuture<ServiceExecutionResult> execute(ServiceExecutionParameters serviceExecutionParameters) {
                    def cf = serviceExecution.execute(serviceExecutionParameters)
                    return cf.thenApply({ r -> Thread.sleep(ms); r })
                }
            }
        } else {
            return serviceExecution
        }
    }
}
