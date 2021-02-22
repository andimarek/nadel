package graphql.nadel.engine


import graphql.execution.ExecutionContext
import graphql.execution.ResultPath
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.nextgen.FieldSubSelection
import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.nadel.Operation
import graphql.nadel.Service
import graphql.nadel.dsl.NodeId
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.testutils.TestUtil
import graphql.schema.GraphQLSchema
import spock.lang.Specification

class OverallQueryTransformerTest extends Specification {
    def schema = TestUtil.schemaFromNdsl([
            example       : '''
        service example {
            type Query { 
                hello: String => renamed from helloWorld 
                foo(id: ID!): Foo
                bar(id: ID!): Bar
            }
            type Mutation {
                hello: String
            }
            
            type Foo {
                id: ID!
                barId: String => renamed from bazId
                qux: String!
                anotherFoo: AnotherFoo => hydrated from AnotherService.topLevel(id: $source.anotherSourceId)
            }
            
            type Bar => renamed from Baz {
                id: ID!
            }
        }
        ''',
            AnotherService: ''' 
        service AnotherService {
            type Query { 
                topLevel(id:ID): AnotherFoo
            }
            type AnotherFoo {
                id: ID!
            }
        }
         '''])

    def underlyingSchemaExampleService = TestUtil.schema("""
            type Query { 
                helloWorld: String 
                foo(id: ID!): Foo
                bar(id: ID!): Baz
            }
            type Mutation {
                hello: String
            }
            
            type Foo {
                id: ID!
                bazId: String 
                qux: String!
                anotherSourceId: ID
            }
            
            type Baz {
                id: ID!
            }
        """)

    def static esi

    void setup() {
        Field field = Field.newField().additionalData(NodeId.ID, UUID.randomUUID().toString()).build()
        MergedField mergedField = Mock(MergedField)
        ResultPath exPath = Mock(ResultPath)
        esi = Mock(ExecutionStepInfo)

        esi.getField() >> mergedField
        esi.getField().getSingleField() >> field
        esi.getField().getSingleField().getAdditionalData().getOrDefault(*_) >> "1"
        esi.getPath() >> exPath
    }

    def underlyingSchemaAnotherService = TestUtil.schema("""
            type Query { 
                topLevel(id:ID): AnotherFoo
            }
            type AnotherFoo {
                id: ID!
            }
        """)

    def serviceMock = Mock(Service)

    def "transforms query to delegate with field rename"() {
        def query = TestUtil.parseQuery(
                '''
            {
             hAlias: hello 
             foo(id: "1") {
                fooId: id
                barId
             }
            }
            ''')

        when:
        def delegateQuery = doTransform(schema, underlyingSchemaExampleService, query)

        then:
        AstPrinter.printAstCompact(delegateQuery) == "query {hAlias:helloWorld foo(id:\"1\") {fooId:id bazId}}"
    }

    def "simple mutation"() {
        def query = TestUtil.parseQuery(
                '''
            mutation M {
             hAlias: hello 
            }
            ''')

        when:
        def delegateQuery = doTransform(schema, underlyingSchemaExampleService, query, Operation.MUTATION, "M")

        then:
        AstPrinter.printAstCompact(delegateQuery) == "mutation M {hAlias:hello}"
    }

    def "used fragments are transformed and included and not used ones left out"() {
        def query = TestUtil.parseQuery(
                '''
            {
             f1: foo(id: "1") {
                ...frag1
             }
             f2: foo(id: "2") {
                ...frag2
             }
            }
            fragment frag1 on Foo {
                id
            }
            fragment frag2 on Foo {
                id
                barId
            }
            fragment unrefFrag on Foo {
                barId
            }
            ''')

        when:
        def delegateQuery = doTransform(schema, underlyingSchemaExampleService, query)

        then:
        AstPrinter.printAstCompact(delegateQuery) ==
                'query {f1:foo(id:"1") {...frag1} f2:foo(id:"2") {...frag2}} fragment frag1 on Foo {id} fragment frag2 on Foo {id bazId}'
    }

    def "used variables are included and not used ones left out"() {
        def query = TestUtil.parseQuery(
                '''query( $usedVariable : String, $unusedVariable : String )
            {
               foo(id : $usedVariable) {
                 id
               }
            }
            ''')

        when:
        def delegateQuery = doTransform(schema, underlyingSchemaExampleService, query)

        then:
        AstPrinter.printAstCompact(delegateQuery) ==
                'query ($usedVariable:String) {foo(id:$usedVariable) {id}}'
    }

    def "nested fragments are transformed and included"() {
        def query = TestUtil.parseQuery(
                '''
            {
             foo(id: "1") {
                ...frag1
                ...frag2
             }
             
             bar(id: "1") {
                ...barFrag
             }
            }
            fragment frag1 on Foo {
                id
                ...frag2
                ...frag3
            }
            fragment frag2 on Foo {
                barId
                ...frag3
                ...frag3
            }
            fragment frag3 on Foo {
                qux
            }
            fragment barFrag on Bar {
                barId: id
            }
            ''')

        when:
        def delegateQuery = doTransform(schema, underlyingSchemaExampleService, query)

        then:
        AstPrinter.printAstCompact(delegateQuery) ==
                'query {foo(id:"1") {...frag1 ...frag2} bar(id:"1") {...barFrag}} ' +
                'fragment frag1 on Foo {id ...frag2 ...frag3} ' +
                'fragment frag2 on Foo {bazId ...frag3 ...frag3} ' +
                'fragment barFrag on Baz {barId:id} ' +
                'fragment frag3 on Foo {qux}'
    }

    def "inline fragments are transformed and types are renamed"() {
        def query = TestUtil.parseQuery(
                '''
            {
             b1: bar(id: "1") {
                ... on Bar {
                    barId: id
                }
             }
            }
            ''')
        //TODO: add test case without type condition, currently getting NPE due to AstPrinter bug

        when:
        def delegateQuery = doTransform(schema, underlyingSchemaExampleService, query)

        then:
        AstPrinter.printAstCompact(delegateQuery) ==
                'query {b1:bar(id:"1") {... on Baz {barId:id}}}'
    }


    def "test hydration transformation"() {
        given:
        def query = TestUtil.parseQuery(
                '''
            {
                foo(id: "12") {
                    anotherFoo {
                        id
                    }
                }
            }
            ''')

        FieldSubSelection fieldSubSelection
        ExecutionContext executionContext
        (executionContext, fieldSubSelection) = TestUtil.executionData(schema, query)

        List<MergedField> fields = new ArrayList<>(fieldSubSelection.getSubFields().values())
        def transformer = new OverallQueryTransformer()
        def serviceExecutionHooks = new ServiceExecutionHooks() {}
        def transformationResult = transformer.transformMergedFields(executionContext, underlyingSchemaExampleService, null, Operation.QUERY, fields, serviceExecutionHooks, null, null)
        when:
        def document = transformationResult.get().document


        then:
        AstPrinter.printAstCompact(document) == 'query {foo(id:"12") {anotherSourceId}}'
    }


    private static Document doTransform(GraphQLSchema overallSchema,
                                        GraphQLSchema underlyingSchema,
                                        Document query,
                                        Operation operation = Operation.QUERY,
                                        String operationName = null) {
        FieldSubSelection fieldSubSelection
        ExecutionContext executionContext
        (executionContext, fieldSubSelection) = TestUtil.executionData(overallSchema, query)

        List<MergedField> fields = new ArrayList<>(fieldSubSelection.getSubFields().values())

        def transformer = new OverallQueryTransformer()
        def hooks = new ServiceExecutionHooks() {}
        Object serviceContext = new Object();
        def transformationResult = transformer.transformMergedFields(executionContext, underlyingSchema, operationName, operation, fields, hooks, null, serviceContext)
        return transformationResult.get().document
    }
}
