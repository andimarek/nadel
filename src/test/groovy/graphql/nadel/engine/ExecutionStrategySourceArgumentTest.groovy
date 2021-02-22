package graphql.nadel.engine

import graphql.GraphQLError
import graphql.execution.nextgen.ExecutionHelper
import graphql.nadel.DefinitionRegistry
import graphql.nadel.Service
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionParameters
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.StrategyTestHelper
import graphql.nadel.dsl.ServiceDefinition
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.instrumentation.NadelInstrumentation
import graphql.nadel.result.ResultComplexityAggregator
import graphql.nadel.testutils.TestUtil
import graphql.schema.GraphQLSchema

import java.util.concurrent.ExecutionException

import static graphql.language.AstPrinter.printAstCompact
import static java.util.concurrent.CompletableFuture.completedFuture

class ExecutionStrategySourceArgumentTest extends StrategyTestHelper {

    ExecutionHelper executionHelper = new ExecutionHelper()
    def service1Execution = Mock(ServiceExecution)
    def service2Execution = Mock(ServiceExecution)
    def serviceDefinition = ServiceDefinition.newServiceDefinition().build()
    def definitionRegistry = Mock(DefinitionRegistry)
    def instrumentation = new NadelInstrumentation() {}
    def serviceExecutionHooks = new ServiceExecutionHooks() {}
    def resultComplexityAggregator = new ResultComplexityAggregator()

    def "single source that hydrates a list object for list type"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 authors: [User] => hydrated from Issues.users(userId: $source.authorId) object identified by userId
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                users(userId: ID): [User]
              } 
              type Issue {
                authorId: ID
              }
              type User {
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { authors { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {authorId}}"
        def response1 = [issue: [authorId: "ID"]]
        def expectedQuery2 = "query nadel_2_Issues {users(userId:[\"ID\"]) {name object_identifier__UUID:userId}}"
        def response2 = [users: [[name: "name", object_identifier__UUID: "ID"], [name: "name2", object_identifier__UUID: "ID2"]]]
        def overallResponse = [issue: [authors: [name: "name"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "list source that hydrates an object for list type"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 authors: [User] => hydrated from Issues.users(userId: $source.authorIds)
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                users(userId: [ID]): User
              } 
              type Issue {
                authorIds: [ID]
              }
              type User {
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { authors { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {authorIds}}"
        def response1 = [issue: [authorIds: ["ID", "ID2", "ID3"]]]
        def expectedQuery2 = "query nadel_2_Issues {users(userId:\"ID\") {name}}"
        def response2 = [users: [name: "name1"]]
        def expectedQuery3 = "query nadel_2_Issues {users(userId:\"ID2\") {name}}"
        def response3 = [users: [name: "name2"]]
        def expectedQuery4 = "query nadel_2_Issues {users(userId:\"ID3\") {name}}"
        def response4 = [users: [name: "name3"]]
        def responses = [response1, response2, response3, response4]
        def expectedQueries = [expectedQuery1, expectedQuery2, expectedQuery3, expectedQuery4]

        def overallResponse = [issue: [authors: [[name: "name1"], [name: "name2"], [name: "name3"]]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                expectedQueries,
                responses,
                4,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "single source that hydrates an object for list type"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 details: [Detail] => hydrated from Foo.detail(detailIds: $source.fooId)
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                detail(detailIds: [ID]): Detail
              } 
              type Foo {
                fooId: ID
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
        """)
        def query = """{ foo { details { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooId}}"
        def response1 = [foo: [fooId: "ID"]]
        def expectedQuery2 = "query nadel_2_Foo {detail(detailIds:\"ID\") {name}}"
        def response2 = [detail: [name: "apple"]]
        def overallResponse = [foo: [details: [name: "apple"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "single nested source with a null hydration input value"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 details: [Detail] => hydrated from Foo.detail(detailIds: $source.issue.fooId)
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                detail(detailIds: [ID]): [Detail]
              } 
              type Foo {
                issue: Issue
              }
              type Issue {
                fooId: ID
              }

              type Detail {
                 detailId: ID!
                 name: String
              }
        """)
        def query = """{ foo { details { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {issue {fooId}}}"
        def response1 = [foo: [issue:[fooId: null]]]
        def overallResponse = [foo:[details:null]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1],
                [response1],
                1,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "list source with a null value that hydrates a list of objects"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 users: [User] => hydrated from Foo.people(userIds: $source.users.accountId) object identified by userId
              }
              type User {
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID]): [User]
              } 
              type Foo {
                fooId: ID
                users: [Owner]
              }
              
              type Owner {
                accountId: ID
              }

              type User {
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ foo { users { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {users {accountId}}}"
        def response1 = [foo: [users: [[accountId:"id1"], [accountId:null], [accountId:"id3"]]]]
        def expectedQuery2 = "query nadel_2_Foo {people(userIds:[\"id1\",\"id3\"]) {name object_identifier__UUID:userId}}"
        def response2 = [people: [[name: "name", object_identifier__UUID: "id1"],[name: "name3", object_identifier__UUID: "id3"]]]
        def overallResponse = [foo: [users: [[name: "name"],null, [name: "name3"]]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "list source that returns an overall null hydration value"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 users: [User] => hydrated from Foo.people(userIds: $source.users.accountId) object identified by userId
              }
              type User {
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID]): [User]
              } 
              type Foo {
                fooId: ID
                users: [Owner]
              }
              
              type Owner {
                accountId: ID
              }

              type User {
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ foo { users { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {users {accountId}}}"
        def response1 = [foo: [users: null]]
        def overallResponse = [foo:[users:null]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1],
                [response1],
                1,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }


    def "one source value that hydrates a list of objects with nested id"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                fooUsers: [FooUser]
              }
              type FooUser {
                fooId: ID
                assignee: User => hydrated from Foo.people(userIds: $source.issue.owner.accountId)
              }

              type User {
                id: String
                name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID]): [User]
              } 
              
              type Foo {
                fooUsers: [FooUser]
              }
              
              type FooUser {
                fooId: ID!
                issue: Issue
              }
              
              type Issue {
                owner: Owner
              }
              
              type Owner {
                accountId: String
              }
              
              type User {
                 id: ID
                 name: String
              }
              
        """)
        def query = """{ foo { fooUsers {fooId assignee { name}}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooUsers {fooId issue {owner {accountId}}}}}"
        def response1 = [foo: [fooUsers: [[fooId:"id1", issue:[owner:[accountId:"one"]]], [fooId:"id2", issue:[owner:[accountId:"two"]]], [fooId:"id3", issue:[owner:[accountId:"three"]]]]]]
        def expectedQuery2 = "query nadel_2_Foo {people(userIds:[\"one\",\"two\",\"three\"]) {name object_identifier__UUID:id}}"
        def response2 = [people: [[name: "name", object_identifier__UUID: "one"],[name: "name2", object_identifier__UUID: "two"],[name: "name3", object_identifier__UUID: "three"]]]
        def overallResponse = [foo:[fooUsers:[[fooId:"id1", assignee:[name:"name"]], [fooId:"id2", assignee:[name:"name2"]], [fooId:"id3", assignee:[name:"name3"]]]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }



    def "a list source combined with a single source throws assert exception"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Songs:'''
        service Songs {
              type Query {
                music: Music
              } 
              type Music {
                 cds: [CD] => hydrated from Songs.disks(ids: $source.productionIds, category: $source.genreId) object identified by cdId
              }
              type CD {
                 id: ID!
                 cdId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                music: Music 
                disks(ids: [ID], category: ID): [CD]
              } 
              type Music {
                genreId: ID
                productionIds: [String]
              }
              type CD {
                 cdId: ID!
                 name: String
              }
        """)
        def query = """{ music { cds { name}}}"""

        def expectedQuery1 = "query nadel_2_Songs {music {productionIds genreId}}"
        def response1 = [music: [productionIds: ["prod1", "prod2"], genreId: "yodelling"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Songs",
                underlyingSchema,
                query,
                ["music"],
                [expectedQuery1],
                [response1],
                1,
                resultComplexityAggregator
        )
        then:
        def error = thrown(ExecutionException)
        error.message.contains("Expected source argument genreId to return a list of values")
    }

    def "a single source with a list source that throws an assert exception"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Songs:'''
        service Songs {
              type Query {
                music: Music
              } 
              type Music {
                 cds: [CD] => hydrated from Songs.disks(category: $source.genreId, ids: $source.productionIds) object identified by cdId
              }
              type CD {
                 id: ID!
                 cdId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                music: Music 
                disks(category: ID, ids: [ID]): [CD]
              } 
              type Music {
                genreId: ID
                productionIds: [String]
              }
              type CD {
                 cdId: ID!
                 name: String
              }
        """)
        def query = """{ music { cds { name}}}"""

        def expectedQuery1 = "query nadel_2_Songs {music {genreId productionIds}}"
        def response1 = [music: [genreId: "yodelling", productionIds: ["prod1", "prod2"]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Songs",
                underlyingSchema,
                query,
                ["music"],
                [expectedQuery1],
                [response1],
                1,
                resultComplexityAggregator
        )
        then:
        def error = thrown(ExecutionException)
        error.message.contains("Expected source argument productionIds to return a single value")
    }

    def "two single sources that return a list object"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 author: User => hydrated from Issues.users(id: $source.accountId, userId: $source.authorId) object identified by userId
                 user: User
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                users(id: ID, userId: ID): [User]
              } 
              type Issue {
                authorId: ID
                accountId: ID
                user: User
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { author { name}}}"""
        def expectedQuery1 = "query nadel_2_Issues {issue {accountId authorId}}"

        def response1 = [issue: [accountId: "a1", authorId: "ID1"]]
        def expectedQuery2 = "query nadel_2_Issues {users(id:[\"a1\"],userId:[\"ID1\"]) {name object_identifier__UUID:userId}}"
        def response2 = [users: [[name: "name1", object_identifier__UUID: "a2"], [name: "name2", object_identifier__UUID: "a1"]]]
        def overallResponse = [issue: [author: [name: "name2"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }


    def "two list sources that return a list object"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 author: [User] => hydrated from Issues.users(ids: $source.accountIds, userIds: $source.authorIds) object identified by userId
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                users(ids: [ID], userIds: [ID]): [User]
              } 
              type Issue {
                authorIds: [ID]
                accountIds: [ID]
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { author { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {accountIds authorIds}}"
        def response1 = [issue: [accountIds: ["a1", "a2", "a3"], authorIds: ["ID1", "ID2", "ID3"]]]

        def expectedQuery2 = "query nadel_2_Issues {users(ids:[\"a1\",\"a2\",\"a3\"]," +
                "userIds:[\"ID1\",\"ID2\",\"ID3\"]) " +
                "{name object_identifier__UUID:userId}}"
        def response2 = [users: [[name: "name", object_identifier__UUID: "a1"],
                                 [name: "name2", object_identifier__UUID: "a2"],
                                 [name: "name3", object_identifier__UUID: "a3"]]]

        def overallResponse = [issue: [author: [[name: "name"], [name: "name2"], [name: "name3"]]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "2 list sources that hydrates a list but secondary source has null field"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 author: [User] => hydrated from Issues.users(ids: $source.accountIds, userIds: $source.authorIds) object identified by userId
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                users(ids: [ID], userIds: [ID]): [User]
              } 
              type Issue {
                authorIds: [ID]
                accountIds: [ID]
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { author { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {accountIds authorIds}}"
        def response1 = [issue: [accountIds: ["a1", "a2", "a3"], authorIds: ["ID1", null, "ID3"]]]
        def expectedQuery2 = "query nadel_2_Issues {users(ids:[\"a1\",\"a2\",\"a3\"]," +
                "userIds:[\"ID1\",null,\"ID3\"]) " +
                "{name object_identifier__UUID:userId}}"
        def response2 = [users: [[name: "name", object_identifier__UUID: "a1"],
                                 [name: "name3", object_identifier__UUID: "a3"]]]

        def overallResponse = [issue: [author: [[name: "name"], null, [name: "name3"]]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "2 list sources that hydrates a list but secondary source has less values"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 author: [User] => hydrated from Issues.users(ids: $source.accountIds, userIds: $source.authorIds) object identified by userId
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                users(ids: [ID], userIds: [ID]): [User]
              } 
              type Issue {
                authorIds: [ID]
                accountIds: [ID]
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { author { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {accountIds authorIds}}"
        def response1 = [issue: [accountIds: ["a1", "a2", "a3"], authorIds: ["ID1", "ID2"]]]
        def expectedQuery2 = "query nadel_2_Issues {users(ids:[\"a1\",\"a2\",\"a3\"]," +
                "userIds:[\"ID1\",\"ID2\",null]) " +
                "{name object_identifier__UUID:userId}}"
        def response2 = [users: [[name: "name", object_identifier__UUID: "a1"],
                                 [name: "name2", object_identifier__UUID: "a2"]]]

        def overallResponse = [issue: [author: [[name: "name"], [name: "name2"], null]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse

    }

    def "two list sources that return a single object for list type"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 authors: [User] => hydrated from Issues.user(id: $source.accountIds, userId: $source.authorIds)
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                user(id: ID, userId: ID): User
              } 
              type Issue {
                authorIds: [ID]
                accountIds: [ID]
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { authors { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {accountIds authorIds}}"
        def response1 = [issue: [accountIds: ["a1", "a2", "a3"], authorIds: ["ID1", "ID2", "ID3"]]]

        def expectedQuery2 = "query nadel_2_Issues {user(id:\"a1\",userId:\"ID1\") {name}}"
        def response2 = [user: [name: "name"]]
        def expectedQuery3 = "query nadel_2_Issues {user(id:\"a2\",userId:\"ID2\") {name}}"
        def response3 = [user: [name: "name2"]]
        def expectedQuery4 = "query nadel_2_Issues {user(id:\"a3\",userId:\"ID3\") {name}}"
        def response4 = [user: [name: "name3"]]

        def overallResponse = [issue: [authors: [[name: "name"], [name: "name2"], [name: "name3"]]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2, expectedQuery3, expectedQuery4],
                [response1, response2, response3, response4],
                4,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }


    def "two single sources that hydrates a single object"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 author: User => hydrated from Issues.user(id: $source.accountId, userId: $source.authorId)
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                user(id: ID, userId: ID): User
              } 
              type Issue {
                authorId: ID
                accountId: ID
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { author { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {accountId authorId}}"
        def response1 = [issue: [accountId: "a1", authorId: "ID1"]]
        def expectedQuery2 = "query nadel_2_Issues {user(id:\"a1\",userId:\"ID1\") {name}}"
        def response2 = [user: [name: "name1"]]
        def overallResponse = [issue: [author: [name: "name1"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "two single sources that hydrates a single object where secondary source is null"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 author: User => hydrated from Issues.user(id: $source.accountId, userId: $source.authorId)
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                user(id: ID, userId: ID): User
              } 
              type Issue {
                authorId: ID
                accountId: ID
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { author { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {accountId authorId}}"
        def response1 = [issue: [accountId: "a1", authorId: null]]
        def expectedQuery2 = "query nadel_2_Issues {user(id:\"a1\",userId:null) {name}}"
        def response2 = [user: [name: "name1"]]
        def overallResponse = [issue: [author: [name: "name1"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "three single sources that hydrates a single object"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
              type Query {
                issue: Issue
              } 
              type Issue {
                 author: User => hydrated from Issues.user(id: $source.accountId, userId: $source.authorId, number: $source.number)
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                issue: Issue 
                user(id: ID, userId: ID, number: String): User
              } 
              type Issue {
                authorId: ID
                accountId: ID
                number: String
              }
              type User {
                 id: ID!
                 userId: ID!
                 name: String
              }
        """)
        def query = """{ issue { author { name}}}"""

        def expectedQuery1 = "query nadel_2_Issues {issue {accountId authorId number}}"
        def response1 = [issue: [accountId: "a1", authorId: "ID1", number: "123"]]
        def expectedQuery2 = "query nadel_2_Issues {user(id:\"a1\",userId:\"ID1\",number:\"123\") {name}}"
        def response2 = [user: [name: "name1"]]
        def overallResponse = [issue: [author: [name: "name1"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Issues",
                underlyingSchema,
                query,
                ["issue"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }


    def "two single sources that hydrates a single object with nested id field"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                bar: Bar
              } 
              type Bar {
                 foo: Foo => hydrated from Foo.foo(firstId: $source.compressedBar.barId, secondId: $source.extendedBar.barId)
              }
              type Foo {
                 id: ID!
                 fooId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                bar: Bar 
                foo(firstId: ID, secondId: ID): Foo
              } 
              type Bar {
                extendedBar: ExtendedBar
                compressedBar: CompressedBar
              }
              type ExtendedBar {
                barId: ID
              }
              type CompressedBar {
                barId: ID
              }
              type Foo {
                 fooId: ID!
                 name: String
              }
        """)
        def query = """{ bar { foo { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {bar {compressedBar {barId} extendedBar {barId}}}"
        def response1 = [bar: [compressedBar: [barId: "c1"], extendedBar: [barId: "e1"]]]
        def expectedQuery2 = "query nadel_2_Foo {foo(firstId:\"c1\",secondId:\"e1\") {name}}"
        def response2 = [foo: [name: "fooBar"]]
        def overallResponse = [bar: [foo: [name: "fooBar"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["bar"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "two list sources that hydrates a list object with nested id fields"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                bar: Bar
              } 
              type Bar {
                 foo: [Foo] => hydrated from Foo.foo(firstIds: $source.compressedBars.barId, secondIds: $source.extendedBars.barId) object identified by fooId
              }
              type Foo {
                 id: ID!
                 fooId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                bar: Bar 
                foo(firstIds: [ID], secondIds: [ID]): [Foo]
              } 
              type Bar {
                extendedBars: [ExtendedBar]
                compressedBars: [CompressedBars]
              }
              type ExtendedBar {
                barId: ID
              }
              type CompressedBars {
                barId: ID
              }
              type Foo {
                 fooId: ID!
                 name: String
              }
        """)
        def query = """{ bar { foo { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {bar {compressedBars {barId} extendedBars {barId}}}"
        def response1 = [bar: [compressedBars: [[barId: "c1"], [barId: "c2"]], extendedBars: [[barId: "b1"], [barId: "b2"]]]]
        def expectedQuery2 = "query nadel_2_Foo {foo(firstIds:[\"c1\",\"c2\"],secondIds:[\"b1\",\"b2\"]) {name object_identifier__UUID:fooId}}"
        def response2 = [foo: [[name: "name1", object_identifier__UUID: "c1"], [name: "name2", object_identifier__UUID: "c2"]]]
        def overallResponse = [bar: [foo: [[name: "name1"], [name: "name2"]]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["bar"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "two single source values inside a list of objects with nested ids and a rename"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                fooUsers : [FooUser]
              }
              type FooUser {
                fooId: ID!
                owner : String => renamed from issue.name
                assignee: User => hydrated from Foo.people(userIds: $source.issue.assignee.accountId, names: $source.issue.assignee.name) object identified by userId
              }

              type User {
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID], names: [String]): [User]
              } 
              
              type Foo {
                fooUsers: [FooUser]
              }
              
              type FooUser {
                fooId: ID!
                issue: Issue
              }
              
              type Issue {
                assignee: Assignee
                name : String
              }
              
              type Assignee {
                accountId: String
                name : String
              }
              
              type User {
                 userId: ID!
                 name: String
              }
              
        """)
        def query = """{ foo { fooUsers {fooId owner assignee { name}}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooUsers {fooId issue {name} issue {assignee {accountId}} issue {assignee {name}}}}}"
        def response1 = [foo: [fooUsers: [[fooId:"id1", issue:[name: "Peter Pettigrew", assignee:[accountId:"one", name:"Jack"]]]]]]
        def expectedQuery2 = "query nadel_2_Foo {people(userIds:[\"one\"],names:[\"Jack\"]) {name object_identifier__UUID:userId}}"
        def response2 = [people: [[name: "Jack Pepper", object_identifier__UUID: "one"]]]
        def overallResponse = [foo:[fooUsers:[[fooId:"id1", owner:"Peter Pettigrew", assignee:[name:"Jack Pepper"]]]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "two single source values where primary source value is null"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                fooUsers : [FooUser]
              }
              type FooUser {
                fooId: ID!
                owner : String => renamed from issue.name
                assignee: User => hydrated from Foo.people(userIds: $source.issue.assignee.accountId, names: $source.issue.assignee.name) object identified by userId
              }
              
              type User {
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID], names: [String]): [User]
              } 
              
              type Foo {
                fooUsers: [FooUser]
              }
              
              type FooUser {
                fooId: ID!
                issue: Issue
              }
              
              type Issue {
                assignee: Assignee
                name : String
              }
              
              type Assignee {
                accountId: String
                name : String
              }
              
              type User {
                 userId: ID!
                 name: String
              }
              
        """)
        def query = """{ foo { fooUsers {fooId owner assignee { name} }}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooUsers {fooId issue {name} issue {assignee {accountId}} issue {assignee {name}}}}}"
        def response1 = [foo: [fooUsers: [[fooId:"id1", issue:[name: "Peter Pettigrew", assignee:[accountId:null, name:"Jack"]]]]]]
        def overallResponse = [foo:[fooUsers:[[fooId:"id1", owner:"Peter Pettigrew", assignee:null]]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1],
                [response1],
                1,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "two single source values where source path is uneven"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                fooUsers : [FooUser]
              }
              type FooUser {
                fooId: ID!
                owner : String => renamed from issue.name
                assignee: User => hydrated from Foo.people(userIds: $source.issue.assignee.accountId, names: $source.issue.name) object identified by userId
              }
              
              type User {
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID], names: [String]): [User]
              } 
              
              type Foo {
                fooUsers: [FooUser]
              }
              
              type FooUser {
                fooId: ID!
                issue: Issue
              }
              
              type Issue {
                assignee: Assignee
                name : String
              }
              
              type Assignee {
                accountId: String
                name : String
              }
              
              type User {
                 userId: ID!
                 name: String
              }
              
        """)
        def query = """{ foo { fooUsers {fooId owner assignee { name} }}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooUsers {fooId issue {name} issue {assignee {accountId}} issue {name}}}}"
        def response1 = [foo: [fooUsers: [[fooId:"id1", issue:[name: "Peter Pettigrew", assignee:[accountId:"1", name:"Jack"]]]]]]
        def expectedQuery2 = "query nadel_2_Foo {people(userIds:[\"1\"],names:[\"Peter Pettigrew\"]) {name object_identifier__UUID:userId}}"
        def response2 = [people: [[name: "Jack Pepper", object_identifier__UUID: "1"]]]

        def overallResponse = [foo:[fooUsers:[[fooId:"id1", owner:"Peter Pettigrew", assignee:[name:"Jack Pepper"]]]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }


    def "three list source values with nested ids in the same parent path and a null hydration input value"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                fooUsers : [FooUser]
              }
              type FooUser {
                fooId: ID!
                assignee: User => hydrated from Foo.people(userIds: $source.assignees.accountId, names: $source.assignees.name, numbers: $source.contacts.number) object identified by userId
              }
              
              type User {
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID], names: [String], numbers: [String]): [User]
              } 
              
              type Foo {
                fooUsers: [FooUser]
              }
              
              type FooUser {
                fooId: ID!
                assignees: [Assignee]
                contacts: [Contact]
              }
              
              type Contact {
                number: String
              }
                            
              type Assignee {
                accountId: String
                name : String
              }
              
              type User {
                 userId: ID!
                 name: String
              }
              
        """)
        def query = """{ foo { fooUsers {fooId  assignee { name}}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooUsers {fooId assignees {accountId} assignees {name} contacts {number}}}}"

        def assigneeData = [[accountId:"1", name:"Jack"],[accountId:null, name:"George"], [accountId:"3", name:null]]
        def contactData = [[number:"123"], [number:"456"], [number:"789"]]
        def fooId = "id1"
        def response1 = [foo: [fooUsers: [[fooId:fooId, assignees:assigneeData, contacts:contactData]]]]
        def expectedQuery2 = "query nadel_2_Foo {people(userIds:[\"1\",\"3\"],names:[\"Jack\",null],numbers:[\"123\",\"789\"]) {name object_identifier__UUID:userId}}"
        def response2 = [people: [[name: "Jack Pepper", object_identifier__UUID: "1"], [name: "George Salt", object_identifier__UUID: "3"]]]

        def overallResponse = [foo:[fooUsers:[[fooId:"id1", assignee:[[name:"Jack Pepper"], null, [name:"George Salt"]]]]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "two list source values with nested ids in the same parent path"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                fooUsers : [FooUser]
              }
              type FooUser {
                fooId: ID!
                assignee: User => hydrated from Foo.people(userIds: $source.assignees.accountId, names: $source.assignees.name) object identified by userId
              }
              
              type User {
                 userId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                people(userIds: [ID], names: [String]): [User]
              } 
              
              type Foo {
                fooUsers: [FooUser]
              }
              
              type FooUser {
                fooId: ID!
                assignees: [Assignee]
              }
              
              type Assignee {
                accountId: String
                name : String
              }
              
              type User {
                 userId: ID!
                 name: String
              }
              
        """)
        def query = """{ foo { fooUsers {fooId  assignee { name}}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooUsers {fooId assignees {accountId} assignees {name}}}}"

        def assigneeData = [[accountId:"1", name:"Jack"],[accountId:"2", name:"George"], [accountId:"3", name:"Bob"]]
        def fooId = "id1"
        def response1 = [foo: [fooUsers: [[fooId:fooId, assignees:assigneeData]]]]
        def expectedQuery2 = "query nadel_2_Foo {people(userIds:[\"1\",\"2\",\"3\"],names:[\"Jack\",\"George\",\"Bob\"]) {name object_identifier__UUID:userId}}"
        def response2 = [people: [[name: "Jack Pepper", object_identifier__UUID: "1"],[name: "Harry Paprika", object_identifier__UUID: "2"], [name: "George Salt", object_identifier__UUID: "3"]]]

        def overallResponse = [foo:[fooUsers:[[fooId:"id1", assignee:[[name:"Jack Pepper"], [name:"Harry Paprika"], [name:"George Salt"]]]]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "swapped primary source and secondary source for 2 hydrations"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 issue: Issue => hydrated from Foo.issue(issueId: $source.fooId, id2: $source.fooId2)
                 detail: Detail => hydrated from Foo.detail(detailId: $source.fooId2, id2: $source.fooId)
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
              type Issue {
                fooId: ID
                field: String
              }

        }
        '''])
        def underlyingSchema = TestUtil.schema("""
          type Query {
            foo: Foo 
            detail(detailId: ID, id2: ID): Detail
            issue(issueId: ID, id2: ID): Issue
          } 
          type Foo {
            field: String
            fooId: ID
            fooId2: ID
            issue: Issue
          }
          
          type Issue {
            fooId: ID
            field: String
          }
          type Detail {
             detailId: ID!
             name: String
          }
    """)
        def query = """{ foo {issue {field} detail { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooId fooId2 fooId2 fooId}}"
        def response1 = [foo: [fooId: "ID", fooId2:"ID2"]]
        def expectedQuery2 = "query nadel_2_Foo {issue(issueId:\"ID\",id2:\"ID2\") {field}}"
        def response2 = [issue: [field: "field_name"]]
        def expectedQuery3 = "query nadel_2_Foo {detail(detailId:\"ID2\",id2:\"ID\") {name}}"
        def response3 = [detail: [name: "apple"]]

        def overallResponse = [foo: [issue: [field: "field_name"], detail: [name: "apple"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2, expectedQuery3],
                [response1, response2, response3],
                3,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }




    def "batching hydration with three list sources"() {
        given:
        def issueSchema = TestUtil.schema("""
        type Query {
            issues : [Issue]
        }
        type Issue {
            id: ID
            authorIds: [ID]
            names: [String]
            links: [String]
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersByIds(ids: [ID], names: [String], related: [String]): [User] 
        }
        type User {
            id: ID
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([Issues:'''
        service Issues {
            type Query {
                issues: [Issue]
            }
            type Issue {
                id: ID
                authors: [User] => hydrated from UserService.usersByIds(id: $source.authorIds, names: $source.names, related: $source.links) object identified by id
            }
        }
        ''',
                                                     UserService:'''
        service UserService {
            type Query {
                usersByIds(id: [ID],names: [String], related: [String]): [User] default batch size 3
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
        def expectedQuery1 = "query nadel_2_Issues {issues {id authorIds names links}}"
        def issue1 = [id: "ISSUE-1", authorIds: ["USER-1", "USER-2"], names: ["applebottom", "river"], links: ["siteA", "siteB"]]
        def issue2 = [id: "ISSUE-2", authorIds: ["USER-3"], names: ["smithsondon"], links: ["siteC"]]
        def issue3 = [id: "ISSUE-3", authorIds: ["USER-2", "USER-4", "USER-5"], names: ["river", "hearth", "hiraeth"], links: ["siteB", "siteD", "siteE"]]
        def response1 = new ServiceExecutionResult([issues: [issue1, issue2, issue3]])


        def expectedQuery2 = "query nadel_2_UserService " +
                "{usersByIds(id:[\"USER-1\",\"USER-2\",\"USER-3\"]," +
                "names:[\"applebottom\",\"river\",\"smithsondon\"]" +
                ",related:[\"siteA\",\"siteB\",\"siteC\"]) " +
                "{id object_identifier__UUID:id}}"
        def batchResponse1 = [[id: "USER-1", object_identifier__UUID: "USER-1"], [id: "USER-2", object_identifier__UUID: "USER-2"], [id: "USER-3", object_identifier__UUID: "USER-3"]]
        def response2 = new ServiceExecutionResult([usersByIds: batchResponse1])

        def expectedQuery3 = "query nadel_2_UserService " +
                "{usersByIds(id:[\"USER-2\",\"USER-4\",\"USER-5\"]," +
                "names:[\"river\",\"hearth\",\"hiraeth\"]," +
                "related:[\"siteB\",\"siteD\",\"siteE\"]) " +
                "{id object_identifier__UUID:id}}"
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
    }

    ExecutionHelper.ExecutionData createExecutionData(String query, GraphQLSchema overallSchema) {
        createExecutionData(query, [:], overallSchema)
    }

}