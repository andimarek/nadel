package graphql.nadel.engine

import graphql.ExecutionInput
import graphql.GraphQLError
import graphql.execution.ExecutionId
import graphql.execution.nextgen.ExecutionHelper
import graphql.nadel.DefinitionRegistry
import graphql.nadel.FieldInfo
import graphql.nadel.FieldInfos
import graphql.nadel.NadelExecutionHints
import graphql.nadel.Service
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionParameters
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.StrategyTestHelper
import graphql.nadel.dsl.ServiceDefinition
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.instrumentation.NadelInstrumentation
import graphql.nadel.result.ResultComplexityAggregator
import graphql.nadel.result.ResultNodesUtil
import graphql.nadel.result.RootExecutionResultNode
import graphql.nadel.schema.UnderlyingWiringFactory
import graphql.nadel.testutils.MockedWiringFactory
import graphql.nadel.testutils.TestUtil
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser

import java.util.concurrent.CompletableFuture

import static graphql.language.AstPrinter.printAstCompact
import static graphql.nadel.testutils.TestUtil.createNormalizedQuery
import static graphql.nadel.testutils.TestUtil.parseQuery
import static java.util.concurrent.CompletableFuture.completedFuture

class NadelExecutionStrategyTest extends StrategyTestHelper {

    ExecutionHelper executionHelper
    def service1Execution
    def service2Execution
    def serviceDefinition
    def definitionRegistry
    def instrumentation
    def serviceExecutionHooks
    def resultComplexityAggregator

    void setup() {
        executionHelper = new ExecutionHelper()
        service1Execution = Mock(ServiceExecution)
        service2Execution = Mock(ServiceExecution)
        serviceDefinition = ServiceDefinition.newServiceDefinition().build()
        definitionRegistry = Mock(DefinitionRegistry)
        instrumentation = new NadelInstrumentation() {}
        serviceExecutionHooks = new ServiceExecutionHooks() {}
        resultComplexityAggregator = new ResultComplexityAggregator()
    }

    def "one call to one service"() {
        given:
        def underlyingSchema = TestUtil.schema("""
        type Query {
            foo: String  
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([service1:"""
        service service1 {
            type Query {
                foo: String
            }
        }
        """])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service = new Service("service", underlyingSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)

        def query = "{foo}"
        def executionData = createExecutionData(query, overallSchema)

        def expectedQuery = "query nadel_2_service {foo}"

        when:
        nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({
            printAstCompact(it.query) == expectedQuery
        } as ServiceExecutionParameters) >> completedFuture(new ServiceExecutionResult(null))

        // Zero total and service node count as execution skipped.
        resultComplexityAggregator.getTotalNodeCount() == 0
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service") == 0
    }

    def "one call to one service with list result"() {
        given:
        def underlyingSchema = TestUtil.schema("""
        type Query {
            foo: [String]
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([service1:"""
        service service1 {
            type Query {
                foo: [String]
            }
        }
        """])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service = new Service("service", underlyingSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)

        def query = "{foo}"
        def executionData = createExecutionData(query, overallSchema)

        def expectedQuery = "query nadel_2_service {foo}"

        def serviceResultData = [foo: ["foo1", "foo2"]]

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({
            printAstCompact(it.query) == expectedQuery
        } as ServiceExecutionParameters) >> completedFuture(new ServiceExecutionResult(serviceResultData))
        resultData(response) == [foo: ["foo1", "foo2"]]
    }


    def underlyingHydrationSchema1 = TestUtil.schema("""
        type Query {
            foo(id : ID) : Foo
        }
        type Foo {
            id: ID
            barId: ID
            fooDetails: FooDetails
        }
        type FooDetails {
            externalBarId: ID
        }
        """)

    def underlyingHydrationSchema2 = TestUtil.schema("""
        type Query {
            barById(id: ID): Bar
        }
        type Bar {
            id: ID
            name : String
        }
        """)

    def overallHydrationSchema = TestUtil.schemaFromNdsl([
            service1: '''
        service service1 {
            type Query {
                foo(id : ID): Foo
            }
            type Foo {
                id: ID
                bar: Bar => hydrated from service2.barById(id: $source.barId)
                barLongerInput: Bar => hydrated from service2.barById(id: $source.fooDetails.externalBarId)
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
        '''])


    def "one hydration call with variables defined"() {
        given:
        def hydrationService1 = new Service("service1", underlyingHydrationSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def hydrationService2 = new Service("service2", underlyingHydrationSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fooFieldDefinition = overallHydrationSchema.getQueryType().getFieldDefinition("foo")

        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, hydrationService1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([hydrationService1, hydrationService2], fieldInfos, overallHydrationSchema, instrumentation, serviceExecutionHooks)


        def query = '''
            query($var : ID, $unusedVar2 : ID) {foo(id : $var) {bar{id name}}}
        '''
        def expectedQuery1 = 'query nadel_2_service1($var:ID) {foo(id:$var) {barId}}'
        def response1 = new ServiceExecutionResult([foo: [barId: "barId"]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId\") {id name}}"
        def response2 = new ServiceExecutionResult([barById: [id: "barId", name: "Bar1"]])

        def executionData = createExecutionData(query, overallHydrationSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({
            printAstCompact(it.query) == expectedQuery1
        } as ServiceExecutionParameters) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({
            printAstCompact(it.query) == expectedQuery2
        } as ServiceExecutionParameters) >> completedFuture(response2)

        resultData(response) == [foo: [bar: [id: "barId", name: "Bar1"]]]
        resultComplexityAggregator.getTotalNodeCount() == 6
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 3
    }


    def "one hydration call with fragments defined"() {
        given:
        def hydrationService1 = new Service("service1", underlyingHydrationSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def hydrationService2 = new Service("service2", underlyingHydrationSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fooFieldDefinition = overallHydrationSchema.getQueryType().getFieldDefinition("foo")

        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, hydrationService1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([hydrationService1, hydrationService2], fieldInfos, overallHydrationSchema, instrumentation, serviceExecutionHooks)


        def query = '''
            query { foo { ... frag1 } } 
            
            fragment frag1 on Foo {
                bar { id name }
            }
            fragment unusedFrag2 on Foo {
                bar { id name }
            }

        '''
        def expectedQuery1 = 'query nadel_2_service1 {foo {...frag1}} fragment frag1 on Foo {barId}'
        def response1 = new ServiceExecutionResult([foo: [barId: "barId"]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId\") {id name}}"
        def response2 = new ServiceExecutionResult([barById: [id: "barId", name: "Bar1"]])


        def executionData = createExecutionData(query, overallHydrationSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        resultData(response) == [foo: [bar: [id: "barId", name: "Bar1"]]]
        resultComplexityAggregator.getTotalNodeCount() == 6
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 3
    }

    def "basic hydration"() {
        given:
        def hydrationService1 = new Service("service1", underlyingHydrationSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def hydrationService2 = new Service("service2", underlyingHydrationSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fooFieldDefinition = overallHydrationSchema.getQueryType().getFieldDefinition("foo")

        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, hydrationService1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([hydrationService1, hydrationService2], fieldInfos, overallHydrationSchema, instrumentation, serviceExecutionHooks)


        def query = '''
            query { foo { bar { name } } } 
        '''
        def expectedQuery1 = 'query nadel_2_service1 {foo {barId}}'
        def response1 = new ServiceExecutionResult([foo: [barId: "barId"]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId\") {name}}"
        def response2 = new ServiceExecutionResult([barById: [id: "barId", name: "Bar1"]])


        def executionData = createExecutionData(query, overallHydrationSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        resultData(response) == [foo: [bar: [name: "Bar1"]]]
        resultComplexityAggregator.getTotalNodeCount() == 5
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 2
    }

    def "one hydration call with input value having longer path"() {
        given:
        def hydrationService1 = new Service("service1", underlyingHydrationSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def hydrationService2 = new Service("service2", underlyingHydrationSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fooFieldDefinition = overallHydrationSchema.getQueryType().getFieldDefinition("foo")

        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, hydrationService1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([hydrationService1, hydrationService2], fieldInfos, overallHydrationSchema, instrumentation, serviceExecutionHooks)


        def query = '''
            query { foo { barLongerInput  { name } } } 
        '''
        def expectedQuery1 = 'query nadel_2_service1 {foo {fooDetails {externalBarId}}}'
        def response1 = new ServiceExecutionResult([foo: [fooDetails: [externalBarId: "barId"]]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId\") {name}}"
        def response2 = new ServiceExecutionResult([barById: [id: "barId", name: "Bar1"]])

        def executionData = createExecutionData(query, overallHydrationSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        resultData(response) == [foo: [barLongerInput: [name: "Bar1"]]]
        resultComplexityAggregator.getTotalNodeCount() == 5
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 2
    }


    def "one hydration call with longer path and same named overall field"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorDetails: [AuthorDetail]
        }
        type AuthorDetail {
            authorId: ID
            name: String
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(id: [ID]): [User]
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''

        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authorDetails: [AuthorDetail]
                authors: [User] => hydrated from UserService.usersByIds(id: $source.authorDetails.authorId) object identified by id, batch size 2
            }
            type AuthorDetail {
                name: String
            }
        }
        ''',
                UserService: '''
        service UserService {
            type Query {
                usersByIds(id: [ID]): [User]
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authors {id} authorDetails {name}}}"

        def expectedQuery1 = "query nadel_2_Issues {issues {id authorDetails {authorId} authorDetails {name}}}"
        def issue1 = [id: "ISSUE-1", authorDetails: [[authorId: "USER-1", name: "User 1"], [authorId: "USER-2", name: "User 2"]]]
        def response1 = new ServiceExecutionResult([issues: [issue1]])


        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\",\"USER-2\"]) {id object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", name: "User 1", object_identifier__UUID: "USER-1"], [id: "USER-2", name: "User 2", object_identifier__UUID: "USER-2"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        def issue1Result = [id: "ISSUE-1", authorDetails: [[name: "User 1"], [name: "User 2"]], authors: [[id: "USER-1"], [id: "USER-2"]]]
        resultData(response) == [issues: [issue1Result]]

        resultComplexityAggregator.getTotalNodeCount() == 13
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 9
        resultComplexityAggregator.getNodeCountsForService("UserService") == 4

    }

    def "one hydration call with longer path arguments and merged fields"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorIds: [ID]
            authors: [IssueUser]
        }
        type IssueUser {
            authorId: ID
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(id: [ID]): [User]
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authors: [User] => hydrated from UserService.usersByIds(id: $source.authors.authorId) object identified by id, batch size 2
            }
        }
        ''',
                UserService: '''  
        service UserService {
            type Query {
                usersByIds(id: [ID]): [User]
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authors {name id}}}"
        def expectedQuery1 = "query nadel_2_Issues {issues {id authors {authorId}}}"
        def issue1 = [id: "ISSUE-1", authors: [[authorId: "USER-1"], [authorId: "USER-2"]]]
        def response1 = new ServiceExecutionResult([issues: [issue1]])


        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\",\"USER-2\"]) {name id object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", name: "User 1", object_identifier__UUID: "USER-1"], [id: "USER-2", name: "User 2", object_identifier__UUID: "USER-2"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        def issue1Result = [id: "ISSUE-1", authors: [[id: "USER-1", name: "User 1"], [id: "USER-2", name: "User 2"]]]
        resultData(response) == [issues: [issue1Result]]

        resultComplexityAggregator.getTotalNodeCount() == 11
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 5
        resultComplexityAggregator.getNodeCountsForService("UserService") == 6

    }

    def "batching of hydration list"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorIds: [ID]
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(id: [ID]): [User]
        }
        type User {
            id: ID
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authors: [User] => hydrated from UserService.usersByIds(id: $source.authorIds) object identified by id, batch size 3
            }
        }''',
                UserService: '''  
        service UserService {
            type Query {
                usersByIds(id: [ID]): [User]
            }
            type User {
                id: ID
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authors {id}}}"
        def expectedQuery1 = "query nadel_2_Issues {issues {id authorIds}}"
        def issue1 = [id: "ISSUE-1", authorIds: ["USER-1", "USER-2"]]
        def issue2 = [id: "ISSUE-2", authorIds: ["USER-3"]]
        def issue3 = [id: "ISSUE-3", authorIds: ["USER-2", "USER-4", "USER-5",]]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2, issue3]])


        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\",\"USER-2\",\"USER-3\"]) {id object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", object_identifier__UUID: "USER-1"], [id: "USER-2", object_identifier__UUID: "USER-2"], [id: "USER-3", object_identifier__UUID: "USER-3"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def expectedQuery3 = "query nadel_2_UserService {usersByIds(id:[\"USER-2\",\"USER-4\",\"USER-5\"]) {id object_identifier__UUID:id}}"
        def batchResponse2 = [[id: "USER-2", object_identifier__UUID: "USER-2"], [id: "USER-4", object_identifier__UUID: "USER-4"], [id: "USER-5", object_identifier__UUID: "USER-5"]]
        def response3 = new ServiceExecutionResult([usersByIds: batchResponse2])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery3
        }) >> completedFuture(response3)


        def issue1Result = [id: "ISSUE-1", authors: [[id: "USER-1"], [id: "USER-2"]]]
        def issue2Result = [id: "ISSUE-2", authors: [[id: "USER-3"]]]
        def issue3Result = [id: "ISSUE-3", authors: [[id: "USER-2"], [id: "USER-4"], [id: "USER-5"]]]
        resultData(response) == [issues: [issue1Result, issue2Result, issue3Result]]
        resultComplexityAggregator.getTotalNodeCount() == 23
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 11
        resultComplexityAggregator.getNodeCountsForService("UserService") == 12

    }

    def "batching of hydration list with flattened arguments"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorIds: [ID]
            authors: [IssueUser]
        }
        type IssueUser {
            authorId: ID
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(id: [ID]): [User]
        }
        type User {
            id: ID
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authors: [User] => hydrated from UserService.usersByIds(id: $source.authors.authorId) object identified by id, batch size 3
            }
        }
        ''',
                UserService: '''
        service UserService {
            type Query {
                usersByIds(id: [ID]): [User]
            }
            type User {
                id: ID
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authors {id}}}"
        def expectedQuery1 = "query nadel_2_Issues {issues {id authors {authorId}}}"
        def issue1 = [id: "ISSUE-1", authors: [[authorId: "USER-1"], [authorId: "USER-2"]]]
        def issue2 = [id: "ISSUE-2", authors: [[authorId: "USER-3"]]]
        def issue3 = [id: "ISSUE-3", authors: [[authorId: "USER-2"], [authorId: "USER-4"], [authorId: "USER-5"]]]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2, issue3]])


        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\",\"USER-2\",\"USER-3\"]) {id object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", object_identifier__UUID: "USER-1"], [id: "USER-2", object_identifier__UUID: "USER-2"], [id: "USER-3", object_identifier__UUID: "USER-3"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def expectedQuery3 = "query nadel_2_UserService {usersByIds(id:[\"USER-2\",\"USER-4\",\"USER-5\"]) {id object_identifier__UUID:id}}"
        def batchResponse2 = [[id: "USER-2", object_identifier__UUID: "USER-2"], [id: "USER-4", object_identifier__UUID: "USER-4"], [id: "USER-5", object_identifier__UUID: "USER-5"]]
        def response3 = new ServiceExecutionResult([usersByIds: batchResponse2])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery3
        }) >> completedFuture(response3)


        def issue1Result = [id: "ISSUE-1", authors: [[id: "USER-1"], [id: "USER-2"]]]
        def issue2Result = [id: "ISSUE-2", authors: [[id: "USER-3"]]]
        def issue3Result = [id: "ISSUE-3", authors: [[id: "USER-2"], [id: "USER-4"], [id: "USER-5"]]]
        resultData(response) == [issues: [issue1Result, issue2Result, issue3Result]]
        resultComplexityAggregator.getTotalNodeCount() == 23
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 11
        resultComplexityAggregator.getNodeCountsForService("UserService") == 12

    }


    def "hydration list"() {
        given:
        def underlyingSchema1 = TestUtil.schema("""
        type Query {
            foo : Foo
        }
        type Foo {
            id: ID
            barId: [ID]
        }
        """)
        def underlyingSchema2 = TestUtil.schema("""
        type Query {
            barById(id: ID): Bar
        }
        type Bar {
            id: ID
            name : String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                service1: '''
        service service1 {
            type Query {
                foo: Foo
            }
            type Foo {
                id: ID
                bar: [Bar] => hydrated from service2.barById(id: $source.barId)
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
        '''])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service1 = new Service("service1", underlyingSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("service2", underlyingSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{foo {bar{id name}}}"
        def expectedQuery1 = "query nadel_2_service1 {foo {barId}}"
        def response1 = new ServiceExecutionResult([foo: [barId: ["barId1", "barId2", "barId3"]]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId1\") {id name}}"
        def response2 = new ServiceExecutionResult([barById: [id: "barId1", name: "Bar1"]])

        def expectedQuery3 = "query nadel_2_service2 {barById(id:\"barId2\") {id name}}"
        def response3 = new ServiceExecutionResult([barById: [id: "barId2", name: "Bar3"]])

        def expectedQuery4 = "query nadel_2_service2 {barById(id:\"barId3\") {id name}}"
        def response4 = new ServiceExecutionResult([barById: [id: "barId3", name: "Bar4"]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery3
        }) >> completedFuture(response3)
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery4
        }) >> completedFuture(response4)

        resultData(response) == [foo: [bar: [[id: "barId1", name: "Bar1"], [id: "barId2", name: "Bar3"], [id: "barId3", name: "Bar4"]]]]
        resultComplexityAggregator.getTotalNodeCount() == 12
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 9
    }

    def "rename with first path element returning null"() {

        def issueSchema = TestUtil.schema("""
        type Query {
            issue : Issue
        }
        type Issue {
            details: IssueDetails
        }
        type IssueDetails {
            name: String
        }
        """)
        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issue: Issue
            }
            type Issue {
                name: String => renamed from details.name
            }
        }
        '''])
        def issueFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issue")
        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issueFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issue {name}}"
        def expectedQuery1 = "query nadel_2_Issues {issue {details {name}}}"
        def response1 = new ServiceExecutionResult([issue: [details: null]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        resultData(response) == [issue: [name: null]]
        resultComplexityAggregator.getTotalNodeCount() == 3
        resultComplexityAggregator.getFieldRenamesCount() == 1
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 3
    }

    def "rename with first last path element returning null"() {

        def issueSchema = TestUtil.schema("""
        type Query {
            issue : Issue
        }
        type Issue {
            details: IssueDetails
        }
        type IssueDetails {
            name: String
        }
        """)
        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issue: Issue
            }
            type Issue {
                name: String => renamed from details.name
            }
        }
        '''])
        def issueFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issue")
        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issueFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issue {name}}"
        def expectedQuery1 = "query nadel_2_Issues {issue {details {name}}}"
        def response1 = new ServiceExecutionResult([issue: [details: [name: null]]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        resultData(response) == [issue: [name: null]]
        resultComplexityAggregator.getTotalNodeCount() == 3
        resultComplexityAggregator.getFieldRenamesCount() == 1
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 3
    }

    def "hydration list with batching"() {
        given:
        def underlyingSchema1 = TestUtil.schema("""
        type Query {
            foo : Foo
        }
        type Foo {
            id: ID
            barId: [ID]
        }
        """)
        def underlyingSchema2 = TestUtil.schema("""
        type Query {
            barsById(id: [ID]): [Bar]
        }
        type Bar {
            id: ID
            name : String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                service1: '''
        service service1 {
            type Query {
                foo: Foo
            }
            type Foo {
                id: ID
                bar: [Bar] => hydrated from service2.barsById(id: $source.barId)
            }
        }
        ''',
                service2: '''
        service service2 {
            type Query {
                barsById(id: [ID]): [Bar]
            }
            type Bar {
                id: ID
                name: String
            }
        }
        '''])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service1 = new Service("service1", underlyingSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("service2", underlyingSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{foo {bar{ name}}}"
        def expectedQuery1 = "query nadel_2_service1 {foo {barId}}"
        def response1 = new ServiceExecutionResult([foo: [barId: ["barId1", "barId2", "barId3"]]])

        def expectedQuery2 = "query nadel_2_service2 {barsById(id:[\"barId1\",\"barId2\",\"barId3\"]) {name object_identifier__UUID:id}}"
        def response2 = new ServiceExecutionResult([barsById: [[object_identifier__UUID: "barId1", name: "Bar1"], [object_identifier__UUID: "barId2", name: "Bar2"], [object_identifier__UUID: "barId3", name: "Bar3"]]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
            !sep.hydrationCall
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
            sep.hydrationCall
        }) >> completedFuture(response2)

        resultData(response) == [foo: [bar: [[name: "Bar1"], [name: "Bar2"], [name: "Bar3"]]]]
        resultComplexityAggregator.getTotalNodeCount() == 9
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 6
    }

    def "hydration batching returns null"() {
        given:
        def underlyingSchema1 = TestUtil.schema("""
        type Query {
            foo : Foo
        }
        type Foo {
            id: ID
            barId: [ID]
        }
        """)
        def underlyingSchema2 = TestUtil.schema("""
        type Query {
            barsById(id: [ID]): [Bar]
        }
        type Bar {
            id: ID
            name : String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                service1: '''
        service service1 {
            type Query {
                foo: Foo
            }
            type Foo {
                id: ID
                bar: [Bar] => hydrated from service2.barsById(id: $source.barId)
            }
        }
        ''',
                service2: '''
        service service2 {
            type Query {
                barsById(id: [ID]): [Bar]
            }
            type Bar {
                id: ID
                name: String
            }
        }
        '''])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service1 = new Service("service1", underlyingSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("service2", underlyingSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{foo {bar{ name}}}"
        def expectedQuery1 = "query nadel_2_service1 {foo {barId}}"
        def response1 = new ServiceExecutionResult([foo: [barId: ["barId1", "barId2", "barId3"]]])

        def expectedQuery2 = "query nadel_2_service2 {barsById(id:[\"barId1\",\"barId2\",\"barId3\"]) {name object_identifier__UUID:id}}"
        def response2 = new ServiceExecutionResult(null)

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        resultData(response) == [foo: [bar: [null, null, null]]]
        resultComplexityAggregator.getTotalNodeCount() == 3
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
    }

    def "hydration list with one element"() {
        given:
        def underlyingSchema1 = TestUtil.schema("""
        type Query {
            foo : Foo
        }
        type Foo {
            id: ID
            barId: [ID]
        }
        """)
        def underlyingSchema2 = TestUtil.schema("""
        type Query {
            barById(id: ID): Bar
        }
        type Bar {
            id: ID
            name : String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                service1: '''
        service service1 {
            type Query {
                foo: Foo
            }
            type Foo {
                id: ID
                bar: [Bar] => hydrated from service2.barById(id: $source.barId)
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
        '''])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service1 = new Service("service1", underlyingSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("service2", underlyingSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{foo {bar{id name}}}"
        def expectedQuery1 = "query nadel_2_service1 {foo {barId}}"
        def response1 = new ServiceExecutionResult([foo: [barId: ["barId1"]]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId1\") {id name}}"
        def response2 = new ServiceExecutionResult([barById: [id: "barId1", name: "Bar1"]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        resultData(response) == [foo: [bar: [[id: "barId1", name: "Bar1"]]]]
        resultComplexityAggregator.getTotalNodeCount() == 6
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 3
    }

    FieldInfos topLevelFieldInfo(GraphQLFieldDefinition fieldDefinition, Service service) {
        FieldInfo fieldInfo = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, service, fieldDefinition)
        return new FieldInfos([(fieldDefinition): fieldInfo])
    }

    ExecutionHelper.ExecutionData createExecutionData(String query, GraphQLSchema overallSchema) {
        createExecutionData(query, [:], overallSchema)
    }

    ExecutionHelper.ExecutionData createExecutionData(String query, Map<String, Object> variables, GraphQLSchema overallSchema) {
        def document = parseQuery(query)
        def normalizedQuery = createNormalizedQuery(overallSchema, document)

        def nadelContext = NadelContext.newContext()
                .artificialFieldsUUID("UUID")
                .normalizedOverallQuery(normalizedQuery)
                .nadelExecutionHints(NadelExecutionHints.newHints().optimizeOnNoTransformations(true).build())
                .build()
        def executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .context(nadelContext)
                .build()
        ExecutionHelper.ExecutionData executionData = executionHelper.createExecutionData(document, overallSchema, ExecutionId.generate(), executionInput, null)
        executionData
    }

    Object resultData(CompletableFuture<RootExecutionResultNode> response) {
        ResultNodesUtil.toExecutionResult(response.get()).data
    }

    List<GraphQLError> resultErrors(CompletableFuture<RootExecutionResultNode> response) {
        ResultNodesUtil.toExecutionResult(response.get()).errors
    }

    def "two deep renames"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorDetails: AuthorDetail
        }
        type AuthorDetail {
            authorId: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authorId: ID => renamed from authorDetails.authorId
                authorName: ID => renamed from authorDetails.name
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authorId authorName}}"

        def expectedQuery1 = "query nadel_2_Issues {issues {id authorDetails {authorId} authorDetails {name}}}"
        def issue1 = [id: "ISSUE-1", authorDetails: [authorId: "USER-1", name: "User 1"]]
        def issue2 = [id: "ISSUE-2", authorDetails: [authorId: "USER-2", name: "User 2"]]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2]])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def issue1Result = [id: "ISSUE-1", authorId: "USER-1", authorName: "User 1"]
        def issue2Result = [id: "ISSUE-2", authorId: "USER-2", authorName: "User 2"]
        resultData(response) == [issues: [issue1Result, issue2Result]]
        resultComplexityAggregator.getTotalNodeCount() == 8
        resultComplexityAggregator.getFieldRenamesCount() == 4
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 8

    }

    def "two deep renames, merged fields with same path and field rename"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issue : Issue
        }
        type Issue {
            id: ID
            authorDetails: AuthorDetail
        }
        type AuthorDetail {
            authorId: ID
            name: String
            extraInfo: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issue: Issue
            }
            type Issue {
                id: ID
                authorId: ID => renamed from authorDetails.authorId
                authorName: ID => renamed from authorDetails.name
                details: AuthorDetail  => renamed from authorDetails
            }
            type AuthorDetail {
                extra: String => renamed from extraInfo
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issue")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issue {id authorId authorName details { extra }}}"

        def expectedQuery1 = "query nadel_2_Issues {issue {id authorDetails {authorId} authorDetails {name} authorDetails {extraInfo}}}"
        def issue1 = [id: "ISSUE-1", authorDetails: [authorId: "USER-1", name: "User 1", extraInfo: "extra 1"]]
        def response1 = new ServiceExecutionResult([issue: issue1])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def issue1Result = [id: "ISSUE-1", authorId: "USER-1", authorName: "User 1", details: [extra: "extra 1"]]
        resultData(response) == [issue: issue1Result]
        resultComplexityAggregator.getTotalNodeCount() == 4
        resultComplexityAggregator.getFieldRenamesCount() == 4
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 4

    }

    def "deep rename of an object"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorDetails: AuthorDetail
        }
        type AuthorDetail {
            name: Name 
        }
        type Name {
            fName: String
            lName: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authorName: Name => renamed from authorDetails.name
            }
            type Name {
                firstName: String => renamed from fName
                lastName: String => renamed from lName
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authorName {firstName lastName}}}"

        def expectedQuery1 = "query nadel_2_Issues {issues {id authorDetails {name {fName lName}}}}"
        def issue1 = [id: "ISSUE-1", authorDetails: [name: [fName: "George", lName: "Smith"]]]
        def issue2 = [id: "ISSUE-2", authorDetails: [name: [fName: "Elizabeth", lName: "Windsor"]]]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2]])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def issue1Result = [id: "ISSUE-1", authorName: [firstName: "George", lastName: "Smith"]]
        def issue2Result = [id: "ISSUE-2", authorName: [firstName: "Elizabeth", lastName: "Windsor"]]
        resultData(response) == [issues: [issue1Result, issue2Result]]
        resultComplexityAggregator.getTotalNodeCount() == 8
        resultComplexityAggregator.getFieldRenamesCount() == 6
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 8
    }

    def "deep rename of list"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            details : [IssueDetail]
        }
        type IssueDetail {
            issue: Issue
        }
        type Issue {
            labels: [String] 
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                details: [IssueDetail]
            }
            type IssueDetail {
                labels: [String] => renamed from issue.labels
            }
        }
        '''])
        def detailsDefinition = overallSchema.getQueryType().getFieldDefinition("details")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(detailsDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{details {labels}}"

        def expectedQuery1 = "query nadel_2_Issues {details {issue {labels}}}"
        def detail1 = [issue: [labels: ["label1", "label2"]]]
        def response1 = new ServiceExecutionResult([details: [detail1]])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def detail1Result = [labels: ["label1", "label2"]]
        resultData(response) == [details: [detail1Result]]
        resultComplexityAggregator.getTotalNodeCount() == 4
        resultComplexityAggregator.getFieldRenamesCount() == 1
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 4
    }

    def "deep rename of list of list"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            details : [IssueDetail]
        }
        type IssueDetail {
            issue: Issue
        }
        type Issue {
            labels: [[String]] 
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                details: [IssueDetail]
            }
            type IssueDetail {
                labels: [[String]] => renamed from issue.labels
            }
        }
        '''])
        def detailsDefinition = overallSchema.getQueryType().getFieldDefinition("details")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(detailsDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{details {labels}}"

        def expectedQuery1 = "query nadel_2_Issues {details {issue {labels}}}"
        def detail1 = [issue: [labels: [["label1", "label2"], ["label3"]]]]
        def response1 = new ServiceExecutionResult([details: [detail1]])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def detail1Result = [labels: [["label1", "label2"], ["label3"]]]
        resultData(response) == [details: [detail1Result]]
        resultComplexityAggregator.getTotalNodeCount() == 4
        resultComplexityAggregator.getFieldRenamesCount() == 1
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 4
    }

    def "deep rename of an object with transformations inside object"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorDetails: AuthorDetail
        }
        type AuthorDetail {
            name: OriginalName 
        }
        type OriginalName {
            originalFirstName: String
            lastName: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authorName: Name => renamed from authorDetails.name
            }
            type Name => renamed from OriginalName {
                firstName: String => renamed from originalFirstName
                lastName: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authorName {firstName lastName}}}"

        def expectedQuery1 = "query nadel_2_Issues {issues {id authorDetails {name {originalFirstName lastName}}}}"
        def issue1 = [id: "ISSUE-1", authorDetails: [name: [originalFirstName: "George", lastName: "Smith"]]]
        def issue2 = [id: "ISSUE-2", authorDetails: [name: [originalFirstName: "Elizabeth", lastName: "Windsor"]]]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2]])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def issue1Result = [id: "ISSUE-1", authorName: [firstName: "George", lastName: "Smith"]]
        def issue2Result = [id: "ISSUE-2", authorName: [firstName: "Elizabeth", lastName: "Windsor"]]
        resultData(response) == [issues: [issue1Result, issue2Result]]
        resultComplexityAggregator.getTotalNodeCount() == 8
        resultComplexityAggregator.getFieldRenamesCount() == 4
        resultComplexityAggregator.getTypeRenamesCount() == 2
        resultComplexityAggregator.getNodeCountsForService("Issues") == 8
    }

    def "hydration call with argument value from original field argument "() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorId: ID
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(extraArg: String, id: [ID]): [User]
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                author(extraArg: String): User => hydrated from UserService.usersByIds(extraArg: $argument.extraArg,id: $source.authorId) object identified by id, batch size 2
            }
        }
        ''',
                UserService: '''
        service UserService {
            type Query {
                usersByIds(extraArg: String, id: [ID]): [User]
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = '{issues {id author(extraArg: "extraArg") {name} }}'
        def expectedQuery1 = "query nadel_2_Issues {issues {id authorId}}"
        def issue1 = [id: "ISSUE-1", authorId: "USER-1"]
        def response1 = new ServiceExecutionResult([issues: [issue1]])


        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\"],extraArg:\"extraArg\") {name object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", name: "User 1", object_identifier__UUID: "USER-1"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        def issue1Result = [id: "ISSUE-1", author: [name: "User 1"]]
        resultData(response) == [issues: [issue1Result]]
        resultComplexityAggregator.getTotalNodeCount() == 7
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 5
        resultComplexityAggregator.getNodeCountsForService("UserService") == 2
    }

    def "hydration call with two argument values from original field arguments "() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorId: ID
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(extraArg1: String, extraArg2: Int, id: [ID]): [User]
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                author(extraArg: String): User => hydrated from UserService.usersByIds(extraArg1: $argument.extraArg1, extraArg2: $argument.extraArg2, id: $source.authorId) object identified by id, batch size 2
            }
        }
        ''',
                UserService: '''    
        service UserService {
            type Query {
                usersByIds(extraArg1: String, extraArg2: Int, id: [ID]): [User]
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = '{issues {id author(extraArg1: "extraArg1", extraArg2: 10) {name} }}'
        def expectedQuery1 = "query nadel_2_Issues {issues {id authorId}}"
        def issue1 = [id: "ISSUE-1", authorId: "USER-1"]
        def response1 = new ServiceExecutionResult([issues: [issue1]])


        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\"],extraArg1:\"extraArg1\",extraArg2:10) {name object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", name: "User 1", object_identifier__UUID: "USER-1"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        def issue1Result = [id: "ISSUE-1", author: [name: "User 1"]]
        resultData(response) == [issues: [issue1Result]]
        resultComplexityAggregator.getTotalNodeCount() == 7
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 5
        resultComplexityAggregator.getNodeCountsForService("UserService") == 2

    }

    def "call with variables inside input objects"() {
        given:
        def serviceSchema = TestUtil.schema("""
        type Query {
            hello(arg: Arg, otherArg: String): String
        }
        input Arg {
            ids: [ID]
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([MyService: '''
        service  MyService{
            type Query {
                hello(arg: Arg, otherArg: String): String
            }
            input Arg {
                ids: [ID]
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("hello")

        def service1 = new Service("MyService", serviceSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = 'query myQuery($varIds: [ID], $otherVar: String) { hello(arg: {ids: $varIds}, otherArg: $otherVar) }'
        def expectedQuery1 = 'query nadel_2_MyService($varIds:[ID],$otherVar:String) {hello(arg:{ids:$varIds},otherArg:$otherVar)}'
        def response1 = new ServiceExecutionResult([hello: "world"])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)


        resultData(response) == [hello: "world"]
        // Zero total and service node count as execution skipped.
        resultComplexityAggregator.getTotalNodeCount() == 0
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("MyService") == 0
    }

    def "two top level fields with a fragment"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorId: ID
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            user : User
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
            }
        }
        ''',
                UserService: '''        
        service UserService {
            type Query {
                user: User
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")
        def userFieldDefinition = overallSchema.getQueryType().getFieldDefinition("user")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        FieldInfo fieldInfo1 = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, service1, issuesFieldDefinition)
        FieldInfo fieldInfo2 = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, service2, userFieldDefinition)
        def fieldInfos = new FieldInfos([(issuesFieldDefinition): fieldInfo1, (userFieldDefinition): fieldInfo2])

        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = """
        fragment I on Issue {
            id
        }
        fragment U on User {
            id 
            name
        }
        {issues {...I } user { ...U } }
        """
        def expectedQuery1 = "query nadel_2_Issues {issues {...I}} fragment I on Issue {id}"
        def issue1 = [id: "ISSUE-1"]
        def issue2 = [id: "ISSUE-2"]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2]])

        def expectedQuery2 = "query nadel_2_UserService {user {...U}} fragment U on User {id name}"
        def user = [id: "USER-1", name: "User 1"]
        def response2 = new ServiceExecutionResult([user: user])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        resultData(response) == [issues: [issue1, issue2], user: user]

        // Zero total and services node count as execution skipped.
        resultComplexityAggregator.getTotalNodeCount() == 0
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 0
        resultComplexityAggregator.getNodeCountsForService("UserService") == 0

    }

    def "hydration call with fragments in the hydrated part"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorDetails: [AuthorDetail]
        }
        type AuthorDetail {
            authorId: ID
            name: String
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(id: [ID]): [User]
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authorDetails: [AuthorDetail]
                authors: [User] => hydrated from UserService.usersByIds(id: $source.authorDetails.authorId) object identified by id, batch size 2
            }
            type AuthorDetail {
                name: String
            }
        }
        ''',
                UserService: '''     
        service UserService {
            type Query {
                usersByIds(id: [ID]): [User]
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")
        def usersByIdFieldDefinition = overallSchema.getQueryType().getFieldDefinition("usersByIds")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        FieldInfo fieldInfo1 = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, service1, issuesFieldDefinition)
        FieldInfo fieldInfo2 = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, service2, usersByIdFieldDefinition)
        def fieldInfos = new FieldInfos([(issuesFieldDefinition): fieldInfo1, (usersByIdFieldDefinition): fieldInfo2])
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = """
            fragment IssueFragment on Issue {
                id
            } 
            {
                issues {...IssueFragment id authors {id ...UserFragment1} } 
                usersByIds(id: ["USER-1"]){ ...UserFragment1 }
            }
            fragment UserFragment1 on User {
               id 
               name
               ...UserFragment2
            }
            fragment UserFragment2 on User {
                name
            } 
        """

        def expectedQuery1 = "query nadel_2_Issues {issues {...IssueFragment id authorDetails {authorId}}} fragment IssueFragment on Issue {id}"
        def issue1 = [id: "ISSUE-1", authorDetails: [[authorId: "USER-1"], [authorId: "USER-2"]]]
        def response1 = new ServiceExecutionResult([issues: [issue1]])

        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\"]) {...UserFragment1}} fragment UserFragment1 on User {id name ...UserFragment2} fragment UserFragment2 on User {name}"
        def user1 = [[id: "USER-1", name: "User 1", object_identifier__UUID: "USER-1"]]
        def response2 = new ServiceExecutionResult([usersByIds: user1])


        def expectedQuery3 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\",\"USER-2\"]) {id ...UserFragment1 object_identifier__UUID:id}} fragment UserFragment1 on User {id name ...UserFragment2} fragment UserFragment2 on User {name}"
        def batchResponse1 = [[id: "USER-1", name: "User 1", object_identifier__UUID: "USER-1"], [id: "USER-2", name: "User 2", object_identifier__UUID: "USER-2"]]
        def response3 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)


        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery3
        }) >> completedFuture(response3)

        def issue1Result = [id: "ISSUE-1", authors: [[id: "USER-1", name: "User 1"], [id: "USER-2", name: "User 2"]]]
        resultData(response) == [issues: [issue1Result], usersByIds: [[id: "USER-1", name: "User 1"]]]

        // Total complexity: 16 total nodes, 11 with skipped execution.
        resultComplexityAggregator.getTotalNodeCount() == 11
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 5
        // UserService node count is non-zero as there are 6 nodes counted in hydration resolver step.
        resultComplexityAggregator.getNodeCountsForService("UserService") == 6

    }

    def "deep rename inside another rename of type List"() {

        def issueSchema = TestUtil.schema("""
        type Query {
            boardScope: BoardScope
        }
        type BoardScope {
            board: Board
        }
        type Board {
            id: ID
            issueChildren: [Card]
        }
        type Card {
            id: ID
            issue: Issue 
        }
        type Issue {
            id: ID
            key: String 
            summary: String
        }
        """)
        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                boardScope: BoardScope
            }
            type BoardScope {
                board: SoftwareBoard
            }
            type SoftwareBoard => renamed from Board{
                cardChildren: [SoftwareCard] => renamed from issueChildren
            }
            type SoftwareCard => renamed from Card {
                id: ID 
                key: String => renamed from issue.key
                summary: String => renamed from issue.summary
            }
        }
        '''])
        def boardScopeDefinition = overallSchema.getQueryType().getFieldDefinition("boardScope")
        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(boardScopeDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = """
          { boardScope {
            board{
              cardChildren{
                id
                key
                summary
              }
            }
          }
         }"""
        def expectedQuery1 = "query nadel_2_Issues {boardScope {board {issueChildren {id issue {key} issue {summary}}}}}"

        def response1 = new ServiceExecutionResult([boardScope: [board: [issueChildren: [[id: "1234", issue: [key: "abc", summary: "Summary 1"]], [id: "456", issue: [key: "def", summary: "Summary 2"]]]]]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        resultData(response) == [boardScope: [board: [cardChildren: [[id: "1234", key: "abc", summary: "Summary 1"], [id: "456", key: "def", summary: "Summary 2"]]]]]

        resultComplexityAggregator.getTotalNodeCount() == 4
        resultComplexityAggregator.getFieldRenamesCount() == 5
        resultComplexityAggregator.getTypeRenamesCount() == 2
        resultComplexityAggregator.getNodeCountsForService("Issues") == 4

    }

    def "hydration call over itself with renamed types"() {
        given:
        def testingSchema = TestUtil.schema("""
        type Query {
            testing: Testing
            characters(ids: [ID!]!): [Character]
        }

        type Testing {
            movies: [Movie]
        }

        type Character {
            id: ID!
            name: String
        }

        type Movie {
            id: ID!
            name: String
            characterIds: [ID]
        }
        """)
        def overallSchema = TestUtil.schemaFromNdsl([testing: '''
        service testing {
            type Query {
                testing: Testing
            }

            type Testing {
                movies: [TestingMovie]
            }
            type TestingCharacter => renamed from Character  {
                id: ID!
                name: String
            }

            type TestingMovie => renamed from Movie {
                id: ID!
                name: String
                characters: [TestingCharacter] => hydrated from testing.characters(ids: $source.characterIds) object identified by id, batch size  3

            }
        }
        '''])
        def testingFieldDefinition = overallSchema.getQueryType().getFieldDefinition("testing")

        def testingService = new Service("testing", testingSchema, service1Execution, serviceDefinition, definitionRegistry)
        FieldInfo fieldInfo1 = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, testingService, testingFieldDefinition)
        def fieldInfos = new FieldInfos([(testingFieldDefinition): fieldInfo1])
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([testingService], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = """
{
  testing {
    movies {
      id
      name
       characters {
         id
         name
       }
       ...F1
    }
  }
}
fragment F1 on TestingCharacter {
   name 
}
        """

        def expectedQuery1 = "query nadel_2_testing {testing {movies {id name characterIds ...F1}}} fragment F1 on Character {name}"
        def movies = [[id: "M1", name: "Movie 1", characterIds: ["C1", "C2"]], [id: "M2", name: "Movie 2", characterIds: ["C1", "C2", "C3"]]]
        def response1 = new ServiceExecutionResult([testing: [movies: movies]])

        def expectedQuery2 = "query nadel_2_testing {characters(ids:[\"C1\",\"C2\",\"C1\"]) {id name object_identifier__UUID:id}}"
        def characters1 = [[id: "C1", name: "Luke", object_identifier__UUID: "C1"], [id: "C2", name: "Leia", object_identifier__UUID: "C2"], [id: "C1", name: "Luke", object_identifier__UUID: "C1"]]
        def response2 = new ServiceExecutionResult([characters: characters1])


        def expectedQuery3 = "query nadel_2_testing {characters(ids:[\"C2\",\"C3\"]) {id name object_identifier__UUID:id}}"
        def characters2 = [[id: "C2", name: "Leia", object_identifier__UUID: "C2"], [id: "C3", name: "Anakin", object_identifier__UUID: "C3"]]
        def response3 = new ServiceExecutionResult([characters: characters2])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery3
        }) >> completedFuture(response3)

        def result = [movies: [[id: "M1", name: "Movie 1", characters: [[id: "C1", name: "Luke"], [id: "C2", name: "Leia"]]], [id: "M2", name: "Movie 2", characters: [[id: "C1", name: "Luke"], [id: "C2", name: "Leia"], [id: "C3", name: "Anakin"]]]]]
        resultData(response) == [testing: result]
        resultComplexityAggregator.getTotalNodeCount() == 26
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 3
        resultComplexityAggregator.getNodeCountsForService("testing") == 26
    }

    def "hydration input is empty list"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorIds: [ID]
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(ids: [ID]): [User]
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authors: [User] => hydrated from UserService.usersByIds(ids: $source.authorIds) object identified by id, batch size 2
            }
        }
        ''',
                UserService: '''
        service UserService {
            type Query {
                usersByIds(ids: [ID]): [User]
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = '{issues {id authors {name} }}'
        def expectedQuery1 = "query nadel_2_Issues {issues {id authorIds}}"
        def issue1 = [id: "ISSUE-1", authorIds: []]
        def response1 = new ServiceExecutionResult([issues: [issue1]])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def issue1Result = [id: "ISSUE-1", authors: []]
        resultData(response) == [issues: [issue1Result]]

        resultComplexityAggregator.getTotalNodeCount() == 5
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 5

    }

    def "hydration input is null"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorIds: [ID]
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(ids: [ID]): [User]
        }
        type User {
            id: ID
            name: String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authors: [User] => hydrated from UserService.usersByIds(ids: $source.authorIds) object identified by id, batch size 2
            }
        }
        ''',
                UserService: '''
        service UserService {
            type Query {
                usersByIds(ids: [ID]): [User]
            }
            type User {
                id: ID
                name: String
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = '{issues {id authors {name} }}'
        def expectedQuery1 = "query nadel_2_Issues {issues {id authorIds}}"
        def issue1 = [id: "ISSUE-1", authorIds: null]
        def response1 = new ServiceExecutionResult([issues: [issue1]])


        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def issue1Result = [id: "ISSUE-1", authors: null]
        resultData(response) == [issues: [issue1Result]]

        resultComplexityAggregator.getTotalNodeCount() == 5
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 5

    }

    def "batching with default batch size"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorIds: [ID]
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(id: [ID]): [User] 
        }
        type User {
            id: ID
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                Issues     : '''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authors: [User] => hydrated from UserService.usersByIds(id: $source.authorIds) object identified by id
            }
        }
        ''',
                UserService: '''
        service UserService {
            type Query {
                usersByIds(id: [ID]): [User] default batch size 3
            }
            type User {
                id: ID
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("UserService", userServiceSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {id authors {id}}}"
        def expectedQuery1 = "query nadel_2_Issues {issues {id authorIds}}"
        def issue1 = [id: "ISSUE-1", authorIds: ["USER-1", "USER-2"]]
        def issue2 = [id: "ISSUE-2", authorIds: ["USER-3"]]
        def issue3 = [id: "ISSUE-3", authorIds: ["USER-2", "USER-4", "USER-5",]]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2, issue3]])


        def expectedQuery2 = "query nadel_2_UserService {usersByIds(id:[\"USER-1\",\"USER-2\",\"USER-3\"]) {id object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", object_identifier__UUID: "USER-1"], [id: "USER-2", object_identifier__UUID: "USER-2"], [id: "USER-3", object_identifier__UUID: "USER-3"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def expectedQuery3 = "query nadel_2_UserService {usersByIds(id:[\"USER-2\",\"USER-4\",\"USER-5\"]) {id object_identifier__UUID:id}}"
        def batchResponse2 = [[id: "USER-2", object_identifier__UUID: "USER-2"], [id: "USER-4", object_identifier__UUID: "USER-4"], [id: "USER-5", object_identifier__UUID: "USER-5"]]
        def response3 = new ServiceExecutionResult([usersByIds: batchResponse2])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery3
        }) >> completedFuture(response3)


        def issue1Result = [id: "ISSUE-1", authors: [[id: "USER-1"], [id: "USER-2"]]]
        def issue2Result = [id: "ISSUE-2", authors: [[id: "USER-3"]]]
        def issue3Result = [id: "ISSUE-3", authors: [[id: "USER-2"], [id: "USER-4"], [id: "USER-5"]]]
        resultData(response) == [issues: [issue1Result, issue2Result, issue3Result]]

        resultComplexityAggregator.getTotalNodeCount() == 23
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 11
        resultComplexityAggregator.getNodeCountsForService("UserService") == 12

    }


    GraphQLSchema underlyingSchema(String sdl) {
        def registry = new SchemaParser().parse(sdl)
        def options = SchemaGenerator.Options.defaultOptions()
        def runtimeWiring = RuntimeWiring.newRuntimeWiring().wiringFactory(new UnderlyingWiringFactory(new MockedWiringFactory())).build()
        return new SchemaGenerator().makeExecutableSchema(options, registry, runtimeWiring)
    }

    def "__typename is correctly passed on and artificial typename is removed"() {
        given:
        def issueSchema = underlyingSchema("""
        type Query {
            issues : [AbstractIssue]
        }
        interface AbstractIssue {
            id: ID
        }
        type Issue implements AbstractIssue{
            id: ID
            authorIds: [ID]
        }
        """)
        def overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issues : [AbstractIssue]
            }
            interface AbstractIssue {
                id: ID
            }
            type Issue implements AbstractIssue{
                id: ID
                authorIds: [ID]
            }
        }
        '''])
        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("issues")

        def service1 = new Service("Issues", issueSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{issues {__typename id ... on Issue {authorIds}}}"
        def expectedQuery1 = "query nadel_2_Issues {issues {__typename id ... on Issue {authorIds} type_hint_typename__UUID:__typename}}"
        def issue1 = [id: "ISSUE-1", authorIds: ["USER-1", "USER-2"], __typename: "Issue", type_hint_typename__UUID: "Issue"]
        def issue2 = [id: "ISSUE-2", authorIds: ["USER-3"], __typename: "Issue", type_hint_typename__UUID: "Issue"]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        def issue1Result = [id: "ISSUE-1", authorIds: ["USER-1", "USER-2"], __typename: "Issue"]
        def issue2Result = [id: "ISSUE-2", authorIds: ["USER-3"], __typename: "Issue"]
        resultData(response) == [issues: [issue1Result, issue2Result]]

        resultComplexityAggregator.getTotalNodeCount() == 13
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("Issues") == 13
    }

    def "hydration call forwards error"() {
        given:
        def underlyingSchema1 = TestUtil.schema("""
        type Query {
            foo : Foo
        }
        type Foo {
            id: ID
            barId: ID
        }
        """)
        def underlyingSchema2 = TestUtil.schema("""
        type Query {
            barById(id: ID): Bar
        }
        type Bar {
            id: ID
            name : String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
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
        '''])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service1 = new Service("service1", underlyingSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("service2", underlyingSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{foo {bar{ name}}}"
        def expectedQuery1 = "query nadel_2_service1 {foo {barId}}"
        def response1 = new ServiceExecutionResult([foo: [barId: "barId1"]])

        def expectedQuery2 = "query nadel_2_service2 {barById(id:\"barId1\") {name}}"
        def response2 = new ServiceExecutionResult(null, [[message: "Some error occurred"]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        def errors = resultErrors(response)
        errors.size() == 1
        errors[0].message == "Some error occurred"

        //want to make sure we still get node counts when there's an error
        resultComplexityAggregator.getTotalNodeCount() == 4
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
        resultComplexityAggregator.getNodeCountsForService("service2") == 1
    }


    def "hydration list with batching forwards error"() {
        given:
        def underlyingSchema1 = TestUtil.schema("""
        type Query {
            foo : Foo
        }
        type Foo {
            id: ID
            barId: [ID]
        }
        """)
        def underlyingSchema2 = TestUtil.schema("""
        type Query {
            barsById(id: [ID]): [Bar]
        }
        type Bar {
            id: ID
            name : String
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl(
                [service1: '''
        service service1 {
            type Query {
                foo: Foo
            }
            type Foo {
                id: ID
                bar: [Bar] => hydrated from service2.barsById(id: $source.barId)
            }
        }
        ''',
                 service2: '''
        service service2 {
            type Query {
                barsById(id: [ID]): [Bar]
            }
            type Bar {
                id: ID
                name: String
            }
        }
        '''])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service1 = new Service("service1", underlyingSchema1, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("service2", underlyingSchema2, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = "{foo {bar{ name}}}"
        def expectedQuery1 = "query nadel_2_service1 {foo {barId}}"
        def response1 = new ServiceExecutionResult([foo: [barId: ["barId1", "barId2", "barId3"]]])

        def expectedQuery2 = "query nadel_2_service2 {barsById(id:[\"barId1\",\"barId2\",\"barId3\"]) {name object_identifier__UUID:id}}"
        def response2 = new ServiceExecutionResult(null, [[message: "Some error occurred"]])

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        def errors = resultErrors(response)
        errors.size() == 1
        errors[0].message == "Some error occurred"

        resultComplexityAggregator.getTotalNodeCount() == 3
        resultComplexityAggregator.getFieldRenamesCount() == 0
        resultComplexityAggregator.getTypeRenamesCount() == 0
        resultComplexityAggregator.getNodeCountsForService("service1") == 3
    }

    def "hydration inside a renamed field"() {
        given:
        def underlyingFooSchema = TestUtil.schema("""
            type Query {
                fooOriginal: Foo
            }
            type Foo {
                id: ID!
                fooBarId: ID
            }
        """)
        def underlyingBarSchema = TestUtil.schema("""
            type Query {
                barById(id: ID!): Bar
            }
            type Bar {
                id: ID
            }
        """)
        def overallSchema = TestUtil.schemaFromNdsl([
                Foo: """
            service Foo {
                type Query {
                    foo: Foo => renamed from fooOriginal
                }
                type Foo {
                    id: ID!
                    fooBar: RenamedBar => hydrated from Bar.barById(id: \$source.fooBarId)
                }
            }
            """,
                Bar: """
            service Bar {
                type Query {
                    barById(id: ID!): RenamedBar
                }
                type RenamedBar => renamed from Bar {
                    id: ID!
                }
            }
        """])
        def query = """
            {
                foo {
                    id
                    fooBar {
                        id
                    }
                }
            }
        """

        def expectedQuery1 = "query nadel_2_Foo {fooOriginal {id fooBarId}}"
        def expectedQuery2 = "query nadel_2_Bar {barById(id:\"hydrated-bar\") {id}}"

        def data1 = [fooOriginal: [id: "Foo", fooBarId: "hydrated-bar"]]
        def data2 = [barById: [id: "hydrated-bar"]]
        def delegatedExecutionResult1 = new ServiceExecutionResult(data1)
        def delegatedExecutionResult2 = new ServiceExecutionResult(data2)

        def fooField = overallSchema.getQueryType().getFieldDefinition("foo")
        def fooService = new Service("Foo", underlyingFooSchema, service1Execution, serviceDefinition, definitionRegistry)
        def barService = new Service("Bar", underlyingBarSchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooField, fooService)

        def nadelExecutionStrategy = new NadelExecutionStrategy([fooService, barService], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)
        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(delegatedExecutionResult1)
        // The {service2Execution} service is not hit if hydration doesn't go through
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(delegatedExecutionResult2)

        resultData(response) == [
                foo: [
                        id    : "Foo",
                        fooBar: [
                                id: "hydrated-bar",
                        ],
                ],
        ]

        resultComplexityAggregator.getTotalNodeCount() == 4
        resultComplexityAggregator.getFieldRenamesCount() == 1
        resultComplexityAggregator.getTypeRenamesCount() == 1
        resultComplexityAggregator.getNodeCountsForService("Foo") == 2
        resultComplexityAggregator.getNodeCountsForService("Bar") == 2
    }

    def "Expecting one child Error on extensive field argument passed to hydration"() {
        given:
        def boardSchema = TestUtil.schema("""
        type Query {
            board(id: ID) : Board
        }
        type Board {
            id: ID
            issueChildren: [Card]
        }
        type Card {
            id: ID
            issue: Issue
        }
        
        type Issue {
            id: ID
            assignee: TestUser
        }
        
        type TestUser {
            accountId: String
        }
        """)

        def identitySchema = TestUtil.schema("""
        type Query {
            users(accountIds: [ID]): [User] 
        }
        type User {
            accountId: ID
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([
                TestBoard: '''
        service TestBoard {
            type Query {
                board(id: ID) : SoftwareBoard
            }
            
            type SoftwareBoard => renamed from Board {
                id: ID
                cardChildren: [SoftwareCard] => renamed from issueChildren
            }
            
            type SoftwareCard => renamed from Card {
                id: ID
                assignee: User => hydrated from Users.users(accountIds: $source.issue.assignee.accountId) object identified by accountId, batch size 3
            }
        }
       ''',
                Users    : '''
        service Users {
            type Query {
                users(accountIds: [ID]): [User]
            }
            type User {
                accountId: ID
            }
        }
        '''])

        def query = '''{
                        board(id:1) {
                            id 
                            cardChildren { 
                                id
                                assignee { 
                                    accountId
                                 } 
                            }
                        }
                        }'''

        def expectedQuery1 = "query nadel_2_TestBoard {board(id:1) {id issueChildren {id issue {assignee {accountId}}}}}"
        def data1 = [board: [id: "1", issueChildren: [[id:"a1", issue: [assignee: [accountId: "1"]]], [id:"a2", issue: [assignee: [accountId: "2"]]], [id:"a3", issue: [assignee: [accountId: "3"]]]]]]
        def response1 = new ServiceExecutionResult(data1)

        def expectedQuery2 = "query nadel_2_Users {users(accountIds:[\"1\",\"2\",\"3\"]) {accountId object_identifier__UUID:accountId}}"
        def response2 = new ServiceExecutionResult([users: [[accountId: "1", object_identifier__UUID: "1"], [accountId: "2", object_identifier__UUID: "2"], [accountId: "3", object_identifier__UUID: "3"]]])

        def issuesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("board")
        def service1 = new Service("TestBoard", boardSchema, service1Execution, serviceDefinition, definitionRegistry)
        def service2 = new Service("Users", identitySchema, service2Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(issuesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1, service2], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)

        def executionData = createExecutionData(query, overallSchema)

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)

        then:
        1 * service1Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery1
        }) >> completedFuture(response1)

        then:
        1 * service2Execution.execute({ ServiceExecutionParameters sep ->
            println printAstCompact(sep.query)
            printAstCompact(sep.query) == expectedQuery2
        }) >> completedFuture(response2)

        resultData(response) == [board: [id: "1", cardChildren: [ [id:"a1",assignee: [accountId: "1"]], [id:"a2", assignee: [accountId: "2"]], [id:"a3", assignee: [accountId: "3"]]]]]
        resultComplexityAggregator.getFieldRenamesCount() == 1
        resultComplexityAggregator.getTypeRenamesCount() == 2
    }


}
