package graphql.nadel.e2e

import graphql.AssertException
import graphql.ErrorType
import graphql.GraphQLError
import graphql.GraphqlErrorException
import graphql.execution.ExecutionId
import graphql.execution.ExecutionIdProvider
import graphql.execution.instrumentation.InstrumentationState
import graphql.nadel.Nadel
import graphql.nadel.NadelExecutionInput
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionFactory
import graphql.nadel.ServiceExecutionParameters
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.instrumentation.NadelInstrumentation
import graphql.nadel.instrumentation.parameters.NadelInstrumentRootExecutionResultParameters
import graphql.nadel.instrumentation.parameters.NadelInstrumentationCreateStateParameters
import graphql.nadel.result.ResultNodesUtil
import graphql.nadel.result.RootExecutionResultNode
import graphql.nadel.schema.SchemaTransformationHook
import graphql.nadel.testutils.TestUtil
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.SchemaTransformer
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

import static graphql.language.AstPrinter.printAstCompact
import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import static graphql.nadel.testutils.TestUtil.typeDefinitions
import static graphql.util.TreeTransformerUtil.changeNode
import static java.util.concurrent.CompletableFuture.completedFuture

class NadelE2ETest extends Specification {

    def simpleNDSL = [MyService: '''
         service MyService {
            type Query {
                hello: World
            }
            type World {
                id: ID
                name: String
            }
            type Mutation {
                hello: String  
            }
         }
        ''']

    def simpleUnderlyingSchema = typeDefinitions('''
            type Query {
                hello: World
            }
            type World {
                id: ID
                name: String
            }
            type Mutation {
                hello: String
            }
        ''')

    def delegatedExecution = Mock(ServiceExecution)
    def serviceFactory = TestUtil.serviceFactory(delegatedExecution, simpleUnderlyingSchema)

    def "query to one service with execution input passed down"() {

        given:
        def query = '''
        query OpName { hello {name} hello {id} }
        '''

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .variables(["var1": "val1"])
                .context("contextObj")
                .operationName("OpName")
                .build()
        def data = [hello: [id: "3", name: "earth"]]

        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) == "query nadel_2_MyService_OpName {hello {name} hello {id}}"
            assert params.context == "contextObj"
            assert params.operationDefinition.name == "nadel_2_MyService_OpName"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.join().data == data
    }

    def "graphql-java validation is invoked"() {
        given:
        def query = '''
        query OpName($unusedVariable : String) { hello {name} hello {id} }
        '''

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        !result.errors.isEmpty()
        def error = result.errors[0] as GraphQLError
        error.errorType == ErrorType.ValidationError

    }

    def "query to two services with field rename"() {

        def nsdl = [
                Foo: '''
         service Foo {
            type Query{
                foo: Foo  => renamed from fooOriginal 
            } 
            type Foo {
                name: String
            }
         }
         ''',
                Bar: '''
         service Bar {
            type Query{
                bar: Bar 
            } 
            type Bar {
                name: String => renamed from title
            }
         }
        ''']
        def query = '''
        { otherFoo: foo {name} bar{name}}
        '''
        def underlyingSchema1 = typeDefinitions('''
            type Query{
                fooOriginal: Foo  
                
            } 
            type Foo {
                name: String
            }
        ''')
        def underlyingSchema2 = typeDefinitions('''
            type Query{
                bar: Bar 
            } 
            type Bar {
                title: String
            }
        ''')
        ServiceExecution delegatedExecution1 = Mock(ServiceExecution)
        ServiceExecution delegatedExecution2 = Mock(ServiceExecution)

        ServiceExecutionFactory serviceFactory = TestUtil.serviceFactory([
                Foo: new Tuple2(delegatedExecution1, underlyingSchema1),
                Bar: new Tuple2(delegatedExecution2, underlyingSchema2)]
        )

        given:
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceExecutionFactory(serviceFactory)
                .build()
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        def data1 = [otherFoo: [name: "Foo"]]
        def data2 = [bar: [title: "Bar"]]
        ServiceExecutionResult delegatedExecutionResult1 = new ServiceExecutionResult(data1, [], [ext1: "val1", merged: "m1"])
        ServiceExecutionResult delegatedExecutionResult2 = new ServiceExecutionResult(data2, [], [ext2: "val2", merged: "m2"])
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution1.execute(_) >> completedFuture(delegatedExecutionResult1)
        1 * delegatedExecution2.execute(_) >> completedFuture(delegatedExecutionResult2)
        def er = result.join()
        er.data == [otherFoo: [name: "Foo"], bar: [name: "Bar"]]
        //
        // we added extension data
        // we have request complexity added at the end as well
        er.extensions["ext1"] == "val1"
        er.extensions["ext2"] == "val2"
        er.extensions["merged"] == "m2"
        er.extensions.containsKey("resultComplexity")
    }

    def "query with three nested hydrations"() {

        def nsdl = [
                Foo: '''
         service Foo {
            type Query{
                foos: [Foo]  
            } 
            type Foo {
                name: String
                bar: Bar => hydrated from Bar.barsById(id: $source.barId) object identified by barId, batch size 2
            }
         }
         ''',
                Bar: '''
         service Bar {
            type Query{
                bar: Bar 
            } 
            type Bar {
                barId: ID
                name: String 
                nestedBar: Bar => hydrated from Bar.barsById(id: $source.nestedBarId) object identified by barId
            }
         }
        ''']
        def underlyingSchema1 = typeDefinitions('''
            type Query{
                foos: [Foo]  
            } 
            type Foo {
                name: String
                barId: ID
            }
        ''')
        def underlyingSchema2 = typeDefinitions('''
            type Query{
                bar: Bar 
                barsById(id: [ID]): [Bar]
            } 
            type Bar {
                barId: ID
                name: String
                nestedBarId: ID
            }
        ''')

        def query = '''
                { foos { bar { name nestedBar {name nestedBar { name } } } } }
        '''
        ServiceExecution serviceExecution1 = Mock(ServiceExecution)
        ServiceExecution serviceExecution2 = Mock(ServiceExecution)

        ServiceExecutionFactory serviceFactory = TestUtil.serviceFactory([
                Foo: new Tuple2(serviceExecution1, underlyingSchema1),
                Bar: new Tuple2(serviceExecution2, underlyingSchema2)]
        )
        given:
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceExecutionFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .artificialFieldsUUID("UUID")
                .build()

        def topLevelData = [foos: [[barId: "bar1"], [barId: "bar2"], [barId: "bar3"]]]
        def hydrationDataBatch1 = [barsById: [[object_identifier__UUID: "bar1", name: "Bar 1", nestedBarId: "nestedBar1"], [object_identifier__UUID: "bar2", name: "Bar 2", nestedBarId: "nestedBar2"]]]
        def hydrationDataBatch2 = [barsById: [[object_identifier__UUID: "bar3", name: "Bar 3", nestedBarId: null]]]
        def hydrationData2 = [barsById: [[object_identifier__UUID: "nestedBar1", name: "NestedBarName1", nestedBarId: "nestedBarId456"]]]
        def hydrationData3 = [barsById: [[object_identifier__UUID: "nestedBarId456", name: "NestedBarName2"]]]
        ServiceExecutionResult topLevelResult = new ServiceExecutionResult(topLevelData)
        ServiceExecutionResult hydrationResult1_1 = new ServiceExecutionResult(hydrationDataBatch1)
        ServiceExecutionResult hydrationResult1_2 = new ServiceExecutionResult(hydrationDataBatch2)
        ServiceExecutionResult hydrationResult2 = new ServiceExecutionResult(hydrationData2)
        ServiceExecutionResult hydrationResult3 = new ServiceExecutionResult(hydrationData3)
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * serviceExecution1.execute(_) >>

                completedFuture(topLevelResult)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult1_1)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult1_2)


        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult2)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult3)

        result.join().data == [foos: [[bar: [name: "Bar 1", nestedBar: [name: "NestedBarName1", nestedBar: [name: "NestedBarName2"]]]], [bar: [name: "Bar 2", nestedBar: null]], [bar: [name: "Bar 3", nestedBar: null]]]]
    }

    def "query with three nested hydrations and simple data"() {

        def nsdl = [
                Foo: '''
         service Foo {
            type Query{
                foos: [Foo]  
            } 
            type Foo {
                name: String
                bar: Bar => hydrated from Bar.barsById(id: $source.barId) object identified by barId, batch size 2
            }
         }
         ''',
                Bar: '''
         service Bar {
            type Query{
                bar: Bar 
            } 
            type Bar {
                barId: ID
                name: String 
                nestedBar: Bar => hydrated from Bar.barsById(id: $source.nestedBarId) object identified by barId
            }
         }
        ''']
        def underlyingSchema1 = typeDefinitions('''
            type Query{
                foos: [Foo]  
            } 
            type Foo {
                name: String
                barId: ID
            }
        ''')
        def underlyingSchema2 = typeDefinitions('''
            type Query{
                bar: Bar 
                barsById(id: [ID]): [Bar]
            } 
            type Bar {
                barId: ID
                name: String
                nestedBarId: ID
            }
        ''')

        def query = '''
                { foos { bar { name nestedBar {name nestedBar { name } } } } }
        '''
        ServiceExecution serviceExecution1 = Mock(ServiceExecution)
        ServiceExecution serviceExecution2 = Mock(ServiceExecution)

        ServiceExecutionFactory serviceFactory = TestUtil.serviceFactory([
                Foo: new Tuple2(serviceExecution1, underlyingSchema1),
                Bar: new Tuple2(serviceExecution2, underlyingSchema2)]
        )
        given:
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceExecutionFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .artificialFieldsUUID("UUID")
                .build()

        def topLevelData = [foos: [[barId: "bar1"]]]
        def hydrationDataBatch1 = [barsById: [[object_identifier__UUID: "bar1", name: "Bar 1", nestedBarId: "nestedBar1"]]]
        def hydrationData2 = [barsById: [[object_identifier__UUID: "nestedBar1", name: "NestedBarName1", nestedBarId: "nestedBarId456"]]]
        def hydrationData3 = [barsById: [[object_identifier__UUID: "nestedBarId456", name: "NestedBarName2"]]]
        ServiceExecutionResult topLevelResult = new ServiceExecutionResult(topLevelData)
        ServiceExecutionResult hydrationResult1_1 = new ServiceExecutionResult(hydrationDataBatch1)
        ServiceExecutionResult hydrationResult2 = new ServiceExecutionResult(hydrationData2)
        ServiceExecutionResult hydrationResult3 = new ServiceExecutionResult(hydrationData3)
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * serviceExecution1.execute(_) >>

                completedFuture(topLevelResult)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult1_1)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult2)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult3)

        result.join().data == [foos: [[bar: [name: "Bar 1", nestedBar: [name: "NestedBarName1", nestedBar: [name: "NestedBarName2"]]]]]]
    }

    def 'mutation can be executed'() {

        def query = '''
        mutation M{ hello }
        '''

        given:
        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        def data = [hello: "world"]
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) == "mutation nadel_2_MyService_M {hello}"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.join().data == data
    }

    def "can declare common types"() {

        def nsdl = [
                common      : '''
         common {
            interface Node {
                id: ID!
            }
         }
         ''',
                IssueService: '''     
         service IssueService {
            type Query{
                node: Node
            } 
            type Issue implements Node {
                id: ID!
                name: String
            }
         }
        ''']
        def query = '''
        { node { ...on Issue { name } } }
        '''
        def underlyingSchema = typeDefinitions('''
            type Query{
                node: Node  
                
            } 
            interface Node {
                id: ID!
            }
            type Issue implements Node {
                id: ID!
                name: String
            }
        ''')
        ServiceExecution serviceExecution = Mock(ServiceExecution)

        ServiceExecutionFactory serviceFactory = TestUtil.serviceFactory([
                IssueService: new Tuple2(serviceExecution, underlyingSchema)]
        )

        given:
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceExecutionFactory(serviceFactory)
                .build()
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .artificialFieldsUUID("uuid")
                .build()
        def data1 = [node: [type_hint_typename__uuid: "Issue", name: "My Issue"]]
        ServiceExecutionResult serviceExecutionResult = new ServiceExecutionResult(data1)
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * serviceExecution.execute(_) >> completedFuture(serviceExecutionResult)
        result.join().data == [node: [name: "My Issue"]]
    }

    def "deep rename works"() {

        def nsdl = [IssueService: '''
         service IssueService {
            type Query{
                issue: Issue
            } 
            type Issue {
                name: String => renamed from detail.detailName
            }
         }
        ''']
        def underlyingSchema = typeDefinitions('''
            type Query{
                issue: Issue 
                
            } 
            type Issue {
                detail: IssueDetails
            }
            type IssueDetails {
                detailName: String
            }
        ''')
        ServiceExecution serviceExecution = Mock(ServiceExecution)

        ServiceExecutionFactory serviceFactory = TestUtil.serviceFactory([
                IssueService: new Tuple2(serviceExecution, underlyingSchema)]
        )

        given:
        def query = '''
        { issue { name } } 
        '''
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceExecutionFactory(serviceFactory)
                .build()
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .artificialFieldsUUID("uuid")
                .build()
        def data1 = [issue: [detail: [detailName: "My Issue"]]]
        ServiceExecutionResult serviceExecutionResult = new ServiceExecutionResult(data1)
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * serviceExecution.execute({ params ->
            println printAstCompact(params.query)
            printAstCompact(params.query) == "query nadel_2_IssueService {issue {detail {detailName}}}"
        }) >> completedFuture(serviceExecutionResult)
        result.join().data == [issue: [name: "My Issue"]]
    }

    ExecutionIdProvider idProvider = new ExecutionIdProvider() {
        @Override
        ExecutionId provide(String queryStr, String operationName, Object context) {
            return ExecutionId.from("fromProvider")
        }
    }

    def "executionId is transferred from input"() {
        given:
        def query = '''
        query { hello {name} hello {id} }
        '''
        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .executionIdProvider(idProvider)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .executionId(ExecutionId.from("fromInput"))
                .build()
        when:
        nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert params.executionId == ExecutionId.from("fromInput")
            completedFuture(new ServiceExecutionResult([:]))
        }
    }


    def "executionId is transferred from provider if missing in input"() {
        given:
        def query = '''
        query { hello {name} hello {id} }
        '''
        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .executionIdProvider(idProvider)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        when:
        nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert params.executionId == ExecutionId.from("fromProvider")
            completedFuture(new ServiceExecutionResult([:]))
        }
    }

    def "schema transformation is applied"() {
        given:
        def transformer = new SchemaTransformationHook() {
            @Override
            GraphQLSchema apply(GraphQLSchema originalSchema) {
                return SchemaTransformer.transformSchema(originalSchema, new GraphQLTypeVisitorStub() {
                    @Override
                    TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                        if ((context.getParentNode() as GraphQLObjectType).name == 'World' && node.name == "name") {
                            def changedNode = node.transform({ builder -> builder.name("nameChanged") })
                            return changeNode(context, changedNode)
                        }

                        return TraversalControl.CONTINUE
                    }
                })
            }
        }

        def underlyingSchemaChanged = typeDefinitions('''
            type Query{
                hello: World  
            } 
            type World {
                id: ID
                nameChanged: String
            }
            type Mutation{
                hello: String  
            } 
        ''')
        def query = '''
        query OpName { hello {nameChanged} }
        '''

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(TestUtil.serviceFactory(delegatedExecution, underlyingSchemaChanged))
                .schemaTransformationHook(transformer)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .variables(["var1": "val1"])
                .context("contextObj")
                .operationName("OpName")
                .build()
        def data = [hello: [nameChanged: "earth"]]

        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) == "query nadel_2_MyService_OpName {hello {nameChanged}}"
            assert params.context == "contextObj"
            assert params.operationDefinition.name == "nadel_2_MyService_OpName"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.join().data == data
    }

    def "extending types from another service is possible"() {
        def ndsl = [
                Service1: '''
         service Service1 {
            extend type Query{
                root: Root  
            } 
            extend type Query {
                anotherRoot: String
            }
            type Root {
                id: ID
            }
            extend type Root {
                name: String
            }
         }
         ''',
                Service2: '''
         service Service2 {
            extend type Root {
                extension: Extension => hydrated from Service2.lookup(id: $source.id) object identified by id 
            }
            type Extension {
                id: ID
                name: String
            }
         }
        ''']
        def underlyingSchema1 = typeDefinitions('''
            type Query{
                root: Root  
            } 
            extend type Query {
                anotherRoot: String
            }
            type Root {
                id: ID
            }
            extend type Root {
                name: String
            }
        ''')
        def underlyingSchema2 = typeDefinitions('''
            type Query{
                lookup(id:ID): Extension
            } 
            type Extension {
                id: ID
                name: String
            }
        ''')

        def execution1 = Mock(ServiceExecution)
        def execution2 = Mock(ServiceExecution)
        def serviceFactory = new ServiceExecutionFactory() {
            @Override
            ServiceExecution getServiceExecution(String serviceName) {
                switch (serviceName) {
                    case "Service1":
                        return execution1
                    case "Service2":
                        return execution2
                    default:
                        throw new RuntimeException()
                }
            }

            @Override
            TypeDefinitionRegistry getUnderlyingTypeDefinitions(String serviceName) {
                switch (serviceName) {
                    case "Service1":
                        return underlyingSchema1
                    case "Service2":
                        return underlyingSchema2
                    default:
                        throw new RuntimeException()
                }
            }
        }
        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .executionIdProvider(idProvider)
                .build()

        def query = """
        { root {
            id
            name
            extension {
                id
                name
            }
        }
        anotherRoot}
        """
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .executionId(ExecutionId.from("fromInput"))
                .build()


        def service1Data1 = [root: [id: "rootId", name: "rootName"]]
        def service1Data2 = [anotherRoot: "anotherRoot"];
        def service2Data = [lookup: [id: "rootId", name: "extensionName"]]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * execution1.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            println printAstCompact(params.getQuery())
            assert printAstCompact(params.getQuery()) == "query nadel_2_Service1 {root {id name id}}"
            completedFuture(new ServiceExecutionResult(service1Data1))
        }
        1 * execution1.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            println printAstCompact(params.getQuery())
            assert printAstCompact(params.getQuery()) == "query nadel_2_Service1 {anotherRoot}"
            completedFuture(new ServiceExecutionResult(service1Data2))
        }
        1 * execution2.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            println printAstCompact(params.getQuery())
            assert printAstCompact(params.getQuery()) == "query nadel_2_Service2 {lookup(id:\"rootId\") {id name}}"
            completedFuture(new ServiceExecutionResult(service2Data))
        }

        result.data == [anotherRoot: "anotherRoot", root: [id: "rootId", name: "rootName", extension: [id: "rootId", name: "extensionName"]]]

    }

    def "makes timing metrics available"() {
        def nsdl = [
                service1: '''
         service service1 {
            type Query {
                foo: Foo
            }
            type Foo {
                id: ID
                bar: Bar => hydrated from service2.barById(id: $source.barId)
            }
        }
        ''',
                service2: '''
        service service2 {
            type Query {
                barById(id: ID): Bar
            }
            type Bar {
                id: ID
                name: String
            }
        }
        ''']
        def underlyingSchema1 = typeDefinitions("""
        type Query {
            foo : Foo
        }
        type Foo {
            id: ID
            barId: ID
        }
        """)

        def underlyingSchema2 = typeDefinitions("""
        type Query {
            barById(id: ID): Bar
        }
        type Bar {
            id: ID
            name : String
        }
        """)

        def execution1 = Mock(ServiceExecution)
        def execution2 = Mock(ServiceExecution)
        def serviceFactory = new ServiceExecutionFactory() {
            @Override
            ServiceExecution getServiceExecution(String serviceName) {
                switch (serviceName) {
                    case "service1":
                        return execution1
                    case "service2":
                        return execution2
                    default:
                        throw new RuntimeException()
                }
            }

            @Override
            TypeDefinitionRegistry getUnderlyingTypeDefinitions(String serviceName) {
                switch (serviceName) {
                    case "service1":
                        return underlyingSchema1
                    case "service2":
                        return underlyingSchema2
                    default:
                        throw new RuntimeException()
                }
            }
        }
        RootExecutionResultNode rootResultNode
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceExecutionFactory(serviceFactory)
                .executionIdProvider(idProvider)
                .instrumentation(new NadelInstrumentation() {
                    @Override
                    RootExecutionResultNode instrumentRootExecutionResult(RootExecutionResultNode rootExecutionResultNode, NadelInstrumentRootExecutionResultParameters parameters) {
                        rootResultNode = rootExecutionResultNode
                        return rootExecutionResultNode
                    }
                })
                .build()

        def query = """
        { 
            foo {
                bar { 
                    name
                }
            }
        }
        """
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        def expectedQuery1 = "query nadel_2_service1 {foo {barId}}"
        def response1 = new ServiceExecutionResult([foo: [barId: "barId1"]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId1\") {name}}"
        def response2 = new ServiceExecutionResult([barById: [name: "bar name"]])

        execution1.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        execution2.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> {
            def cf = new CompletableFuture();
            new Thread({
                Thread.sleep(251)
                cf.complete(response2)
            }).start()
            return cf
        }

        when:
        def result = nadel.execute(nadelExecutionInput).join()


        then:
        result.data == [foo: [bar: [name: "bar name"]]]
        rootResultNode.getChildren()[0].elapsedTime.duration.toMillis() < 250
        rootResultNode.getChildren()[0].getChildren()[0].elapsedTime.duration.toMillis() > 250
    }

    def "can instrument root execution result"() {
        given:
        def query = """
        query OpName {
            hello {
                name
            }
        }
        """
        RootExecutionResultNode originalExecutionResult
        NadelInstrumentRootExecutionResultParameters instrumentationParams
        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .instrumentation(new NadelInstrumentation() {
                    @Override
                    InstrumentationState createState(NadelInstrumentationCreateStateParameters parameters) {
                        return new InstrumentationState() {
                            @Override
                            int hashCode() {
                                return "test-instrumentation-state".hashCode()
                            }
                        }
                    }

                    @Override
                    RootExecutionResultNode instrumentRootExecutionResult(RootExecutionResultNode rootExecutionResultNode, NadelInstrumentRootExecutionResultParameters parameters) {
                        originalExecutionResult = rootExecutionResultNode
                        instrumentationParams = parameters
                        return rootExecutionResultNode.withNewErrors([
                                GraphqlErrorException.newErrorException().message("instrumented-error").build()
                        ])
                    }
                })
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .operationName("OpName")
                .build()

        def data = [hello: null]

        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            def params = args[0] as ServiceExecutionParameters
            assert printAstCompact(params.query) == "query nadel_2_MyService_OpName {hello {name}}"
            assert params.operationDefinition.name == "nadel_2_MyService_OpName"
            completedFuture(new ServiceExecutionResult(data))
        }

        then:
        result.errors[0].message == "instrumented-error"
        instrumentationParams != null
        instrumentationParams.instrumentationState.hashCode() == "test-instrumentation-state".hashCode()
        instrumentationParams.executionContext.operationDefinition.name == "OpName"
        originalExecutionResult != null
        ResultNodesUtil.toExecutionResult(originalExecutionResult).errors.isEmpty()
    }

    def "object type from underlying schema is removed"() {

        def simpleNDSL = [MyService: '''
         service MyService {
            type Query {
                hello: World
            }
            
            interface World {
                id: ID
                name: String
            }
            
            type Mars implements World {
                id: ID
                name: String
            }
         }
        ''']

        def underlyingSchemaChanged = typeDefinitions('''
            type Query{
                hello: World  
            } 
            
            interface World {
                id: ID
                name: String
            }
        ''')

        def query = '''
        query OpName { 
            hello {
                ... on Mars {
                    name
                }
            } 
        }
        '''

        def nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(TestUtil.serviceFactory(delegatedExecution, underlyingSchemaChanged))
                .build()

        def nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .operationName("OpName")
                .build()

        when:
        nadel.execute(nadelExecutionInput).join()
        then:
        def e = thrown CompletionException
        e.cause instanceof AssertException
        e.cause.message == "Schema mismatch: The underlying schema is missing required interface type Mars"

    }


}
