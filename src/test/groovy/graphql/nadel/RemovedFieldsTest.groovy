package graphql.nadel

import graphql.GraphQLError
import graphql.execution.AbortExecutionException
import graphql.execution.ExecutionContext
import graphql.language.Argument
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.normalized.NormalizedQueryField
import graphql.nadel.result.ResultComplexityAggregator
import graphql.nadel.testutils.TestUtil
import graphql.nadel.testutils.harnesses.IssuesCommentsUsersHarness
import graphql.schema.GraphQLSchema

import java.util.concurrent.CompletableFuture

import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput

class RemovedFieldsTest extends StrategyTestHelper {

    GraphQLSchema overallSchema = TestUtil.schemaFromNdsl(IssuesCommentsUsersHarness.ndsl)
    def issueSchema = TestUtil.schema(IssuesCommentsUsersHarness.ISSUES_SDL)
    def commentSchema = TestUtil.schema(IssuesCommentsUsersHarness.COMMENTS_SDL)
    def userServiceSchema = TestUtil.schema(IssuesCommentsUsersHarness.USERS_SDL)

    def "field is removed from hydrated field"() {
        given:
        def query = """
        {
            commentById(id:"C1") {
                author {
                    displayName
                    userId
                }
            }
        }
        """

        def expectedQuery1 = """query nadel_2_CommentService {commentById(id:"C1") {authorId}}"""
        def expectedQuery2 = """query nadel_2_UserService {userById(id:"fred") {displayName}}"""
        Map response1 = [commentById: [authorId: "fred"]]
        Map response2 = [userById: [displayName: "Display name of Fred"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test2Services(
                overallSchema,
                "CommentService",
                commentSchema,
                "UserService",
                userServiceSchema,
                query,
                ["commentById"],
                expectedQuery1,
                response1,
                expectedQuery2,
                response2,
                createServiceExecutionHooksWithFieldRemoval(["userId"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [commentById: [author: [displayName: "Display name of Fred", userId: null]]]
        errors.size() == 1
        errors[0].message.contains("removed field")
    }

    def "all fields are removed from hydrated field"() {
        given:
        def query = """
        {
            commentById(id:"C1") {
                author {
                    displayName
                    userId
                }
            }
        }
        """

        def expectedQuery1 = """query nadel_2_CommentService {commentById(id:"C1") {authorId}}"""
        def expectedQuery2 = """query nadel_2_UserService {userById(id:"fred") {empty_selection_set_typename__UUID:__typename}}"""
        Map response1 = [commentById: [authorId: "fred"]]
        Map response2 = [userById: [empty_selection_set_typename__UUID: "Query"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test2Services(
                overallSchema,
                "CommentService",
                commentSchema,
                "UserService",
                userServiceSchema,
                query,
                ["commentById"],
                expectedQuery1,
                response1,
                expectedQuery2,
                response2,
                createServiceExecutionHooksWithFieldRemoval(["userId", "displayName"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [commentById: [author: [displayName: null, userId: null]]]
        errors.size() == 2
        errors[0].message.contains("removed field")
        errors[1].message.contains("removed field")
    }

    def "hydrated field is removed"() {
        given:
        def query = """
        {
            commentById(id:"C1") {
                author {
                    displayName
                    userId
                }
            }
        }
        """

        def expectedQuery1 = """query nadel_2_CommentService {commentById(id:"C1") {empty_selection_set_typename__UUID:__typename}}"""
        Map response1 = [commentById: [authorId: "fred"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "CommentService",
                commentSchema,
                query,
                ["commentById"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["author"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [commentById: [author: null]]
        errors.size() == 1
        errors[0].message.contains("removed field")
    }

    def "nested hydrated field is removed"() {
        given:
        def query = """
        {
            issueById(id : "I1") {
                comments {
                    author {
                        displayName
                    }
                }
            } 
        }
        """

        def expectedQuery1 = """query nadel_2_IssueService {issueById(id:"I1") {commentIds}}"""
        def expectedQuery2 = """query nadel_2_CommentService {commentById(id:"C1") {empty_selection_set_typename__UUID:__typename}}"""
        Map response1 = [issueById: [commentIds: ["C1"]]]
        Map response2 = [commentById: [empty_selection_set_typename__UUID: "Query"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test2Services(
                overallSchema,
                "IssueService",
                issueSchema,
                "CommentService",
                commentSchema,
                query,
                ["issueById"],
                expectedQuery1,
                response1,
                expectedQuery2,
                response2,
                createServiceExecutionHooksWithFieldRemoval(["author"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [issueById: [comments: [[author: null]]]]
        errors.size() == 1
        errors[0].message == "removed field"
    }

    def "field is removed from nested hydrated field"() {
        given:
        def query = '''
        {
            issues {
                key
                summary
                key
                summary
                reporter {
                    displayName
                }
                comments {
                    commentText
                    author {
                        displayName
                        userId
                    }
                }
            }
        }
        '''


        def serviceExecutionFactory = IssuesCommentsUsersHarness.serviceFactoryWithDelay(2)

        Nadel nadel = newNadel()
                .dsl(IssuesCommentsUsersHarness.ndsl)
                .serviceExecutionFactory(serviceExecutionFactory)
                .serviceExecutionHooks(createServiceExecutionHooksWithFieldRemoval(["userId"]))
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query)).join()

        then:
        result.data == [issues: [
                [key     : "WORK-I1", summary: "Summary for WORK-I1", reporter: [displayName: "Display name of fred"],
                 comments: [[commentText: "Text of C1", author: [displayName: "Display name of fred", userId: null]],
                            [commentText: "Text of C3", author: [displayName: "Display name of zed", userId: null]],
                            [commentText: "Text of C5", author: [displayName: "Display name of fred", userId: null]]]],
                [key     : "WORK-I2", summary: "Summary for WORK-I2", reporter: [displayName: "Display name of zed"],
                 comments: [[commentText: "Text of C2", author: [displayName: "Display name of ned", userId: null]],
                            [commentText: "Text of C4", author: [displayName: "Display name of jed", userId: null]],
                            [commentText: "Text of C6", author: [displayName: "Display name of ted", userId: null]]]]],
        ]
        result.errors.size() == 6
        result.errors.every { it.message.contains("removed field") }
    }

    def "field with selections is removed"() {
        given:
        def query = """
        { 
            issueById(id : "I1") {
                id
                epic {
                    id
                    title
                } 
            } 
        }
        """
        def expectedQuery1 = """query nadel_2_IssueService {issueById(id:"I1") {id}}"""
        Map response1 = [issueById: [id: "I1"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "IssueService",
                issueSchema,
                query,
                ["issueById"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["epic"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [issueById: [id: "I1", epic: null]]
        errors.size() == 1
        errors[0].message == "removed field"
    }

    def "field in a selection set is removed"() {
        given:
        def query = """
        { 
            issueById(id : "I1") {
                id
                epic {
                    id
                    title
                }
            }
        }
        """
        def expectedQuery1 = """query nadel_2_IssueService {issueById(id:"I1") {id epic {id}}}"""
        Map response1 = [issueById: [id: "I1", epic: [id: "E1"]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "IssueService",
                issueSchema,
                query,
                ["issueById"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["title"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [issueById: [id: "I1", epic: [id: "E1", title: null]]]
        errors.size() == 1
        errors[0].message == "removed field"
    }

    def "the only field in a selection set is removed"() {
        given:
        def query = """
        {
            issueById(id : "I1") {
                id
                epic {
                    title
                }
            }
        }
        """

        def expectedQuery1 = """query nadel_2_IssueService {issueById(id:"I1") {id epic {empty_selection_set_typename__UUID:__typename}}}"""
        Map response1 = [issueById: [id: "I1", epic: [empty_selection_set_typename__UUID: "Query"]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "IssueService",
                issueSchema,
                query,
                ["issueById"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["title"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [issueById: [id: "I1", epic: [title: null]]]
        errors.size() == 1
        errors[0].message == "removed field"
    }

    def "all fields in a selection set are removed"() {
        given:
        def query = """
        {
            issueById(id : "I1") {
                id
                epic {
                    title
                    description
                }
            }
        }
        """

        def expectedQuery1 = """query nadel_2_IssueService {issueById(id:"I1") {id epic {empty_selection_set_typename__UUID:__typename}}}"""
        Map response1 = [issueById: [id: "I1", epic: [empty_selection_set_typename__UUID: "Query"]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "IssueService",
                issueSchema,
                query,
                ["issueById"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["title", "description"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [issueById: [id: "I1", epic: [title: null, description: null]]]
        errors.size() == 2
        errors[0].message == "removed field"
        errors[1].message == "removed field"
    }

    def "field in non-hydrated query is removed"() {
        given:
        def query = """
        {
            commentById(id:"C1") {
                id
                created
            }
        }
        """

        def expectedQuery1 = """query nadel_2_CommentService {commentById(id:"C1") {id}}"""
        Map response1 = [commentById: [id: "C1"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "CommentService",
                commentSchema,
                query,
                ["commentById"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["created"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [commentById: [id: "C1", created: null]]
        errors.size() == 1
        errors[0].message == "removed field"
    }

    def "all non-hydrated fields in query are removed"() {
        given:
        def query = """
        {
            commentById(id:"C1") {
                id
                created
                commentText
            }
        }
        """

        def expectedQuery1 = """query nadel_2_CommentService {commentById(id:"C1") {empty_selection_set_typename__UUID:__typename}}"""
        Map response1 = [commentById: [empty_selection_set_typename__UUID: "Query"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "CommentService",
                commentSchema,
                query,
                ["commentById"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["id", "created", "commentText"]),
                Mock(ResultComplexityAggregator)
        )
        then:
        response == [commentById: ["id": null, "created": null, "commentText": null]]
        errors.size() == 3
        errors.every { it.message.contains("removed field") }
    }

    def "top level field is removed"() {
        given:
        def query = """
        {
            commentById(id:"C1") {
                id
            }
        }
        """
        def serviceExecutionFactory = IssuesCommentsUsersHarness.serviceFactoryWithDelay(2)

        Nadel nadel = newNadel()
                .dsl(IssuesCommentsUsersHarness.ndsl)
                .serviceExecutionFactory(serviceExecutionFactory)
                .serviceExecutionHooks(createServiceExecutionHooksWithFieldRemoval(["commentById"]))
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query)).join()

        then:
        result.data == [commentById: null]
        result.errors.size() == 1
        result.errors[0].message.contains("removed field")
    }

    def "one of top level fields is removed"() {
        given:
        def query = """
        {
            commentById(id:"C1") {
                id
            }
            
            issues {
                key
            }
        }
        """
        def serviceExecutionFactory = IssuesCommentsUsersHarness.serviceFactoryWithDelay(2)

        Nadel nadel = newNadel()
                .dsl(IssuesCommentsUsersHarness.ndsl)
                .serviceExecutionFactory(serviceExecutionFactory)
                .serviceExecutionHooks(createServiceExecutionHooksWithFieldRemoval(["commentById"]))
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query)).join()

        then:
        result.data == [commentById: null, issues: [[key: "WORK-I1"], [key: "WORK-I2"]]]
        result.errors.size() == 1
        result.errors[0].message.contains("removed field")
    }

    def "top level field in batched query is removed"() {
        given:
        def query = '''
        {
            issues {
                key
                comments {
                    commentText
                    author {
                        displayName
                        userId
                    }
                }
            }
        }
        '''

        def expectedQuery1 = """query nadel_2_IssueService {issues {key}}"""
        Map response1 = [issues: [[key: "WORK-I1"], [key: "WORK-I2"]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                overallSchema,
                "IssueService",
                issueSchema,
                query,
                ["issues"],
                expectedQuery1,
                response1,
                createServiceExecutionHooksWithFieldRemoval(["comments"]),
                Mock(ResultComplexityAggregator)
        )

        then:
        response == [issues: [
                [key: "WORK-I1", comments: null],
                [key: "WORK-I2", comments: null]]]

        errors.size() == 1
        errors[0].message.contains("removed field")
    }


    ServiceExecutionHooks createServiceExecutionHooksWithFieldRemoval(List<String> fieldsToRemove) {
        return new ServiceExecutionHooks() {
            @Override
            CompletableFuture<Optional<GraphQLError>> isFieldForbidden(NormalizedQueryField normalizedField, List<Argument> hydrationRootArguments, ExecutionContext executionContext, Object userSuppliedContext) {
                if (fieldsToRemove.contains(normalizedField.getName())) {
                    //temporary GraphQLError ->  need to implement a field permissions denied error
                    return CompletableFuture.completedFuture(Optional.of(new AbortExecutionException("removed field")))
                }
                return CompletableFuture.completedFuture(Optional.empty())
            }
        }
    }

    def setupFragmentTests() {
        GraphQLSchema overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issue: Issue
            }
            type Issue {
                id: ID
                relatedIssue: Issue                 
                restricted: String
            }
        }
        '''])
        GraphQLSchema issueSchema = TestUtil.schema("""
        type Query {
            issue : Issue
        }
        type Issue {
            id: ID
            relatedIssue: Issue                 
            restricted: String
        }
        """)
        def query = "{issue { ...IssueFragment relatedIssue { ...IssueFragment }}} fragment IssueFragment on Issue { id restricted }"

        return [overallSchema, issueSchema, query]
    }

    def "restricted field via fragments used twice"() {
        given:
        def (GraphQLSchema overallSchema, GraphQLSchema issueSchema, String query) = setupFragmentTests()

        def hooks = createServiceExecutionHooksWithFieldRemoval(["restricted"])

        def expectedQuery1 = """query nadel_2_Issues {issue {...IssueFragment relatedIssue {...IssueFragment}}} fragment IssueFragment on Issue {id}"""
        def response1 = [issue: [id: "ID1", relatedIssue: [id: "ID2"]]]

        def overallResponse = [issue: [id: "ID1", restricted: null, relatedIssue: [id: "ID2", restricted: null]]]

        when:
        def (Map response, List<GraphQLError> errors) = test1Service(
                overallSchema,
                "Issues",
                issueSchema,
                query,
                ["issue"],
                expectedQuery1,
                response1,
                hooks,
                Mock(ResultComplexityAggregator)
        )
        then:
        response == overallResponse
        errors.size() == 2
        errors[0].message.contains("removed field")
        errors[1].message.contains("removed field")
    }

    def "restricted single field via fragments used twice"() {
        given:
        def (GraphQLSchema overallSchema, GraphQLSchema issueSchema, String query) = setupFragmentTests()

        def hooks = new ServiceExecutionHooks() {
            @Override
            CompletableFuture<Optional<GraphQLError>> isFieldForbidden(NormalizedQueryField normalizedField, List<Argument> hydrationRootArguments, ExecutionContext executionContext, Object userSuppliedContext) {
                if (normalizedField.getName() == "restricted" && normalizedField.getParent().getName() == "issue") {
                    //temporary GraphQLError ->  need to implement a field permissions denied error
                    return CompletableFuture.completedFuture(Optional.of(new AbortExecutionException("removed field")))
                }
                return CompletableFuture.completedFuture(Optional.empty())
            }
        }

        def expectedQuery1 = "query nadel_2_Issues {issue {...IssueFragment relatedIssue {...IssueFragment}}} fragment IssueFragment on Issue {id restricted}"
        def response1 = [issue: [id: "ID1", restricted: "secret", relatedIssue: [id: "ID2", restricted: "secret"]]]

        def overallResponse = [issue: [id: "ID1", restricted: null, relatedIssue: [id: "ID2", restricted: "secret"]]]

        when:
        def (Map response, List<GraphQLError> errors) = test1Service(
                overallSchema,
                "Issues",
                issueSchema,
                query,
                ["issue"],
                expectedQuery1,
                response1,
                hooks,
                Mock(ResultComplexityAggregator)
        )
        then:
        response == overallResponse
        errors.size() == 1
        errors[0].message.contains("removed field")
    }

    def "inserts one error for a forbidden field in a list"() {
        given:
        GraphQLSchema overallSchema = TestUtil.schemaFromNdsl([Issues: '''
        service Issues {
            type Query {
                issues: [Issue]
            }
 
            type Issue {
                id: ID
                relatedIssue: Issue                 
                restricted: String
            }
        }
        '''])
        GraphQLSchema issueSchema = TestUtil.schema("""
        type Query {
            issues: [Issue]
        }

        type Issue {
            id: ID
            relatedIssue: Issue                 
            restricted: String
        }
        """)
        def query = "{issues {id restricted}}"

        def hooks = createServiceExecutionHooksWithFieldRemoval(["restricted"])

        def expectedQuery1 = "query nadel_2_Issues {issues {id}}"
        def response1 = [issues: [[id: "test-1",], [id: "test-2",], [id: "test-3",]]]

        when:
        def (Map response, List<GraphQLError> errors) = test1Service(
                overallSchema,
                "Issues",
                issueSchema,
                query,
                ["issues"],
                expectedQuery1,
                response1,
                hooks,
                Mock(ResultComplexityAggregator)
        )

        then:
        println response
        errors.size() == 1
        errors[0].message.contains("removed field")
    }

    def setupFragmentHydrationTests() {
        def overallSchema = TestUtil.schemaFromNdsl([Issues     : '''
        service Issues {
            type Query {
                issue: Issue
            }
            type Issue {
                id: ID
                relatedIssue: Issue                 
                author: User => hydrated from UserService.usersById(id: $source.authorId) object identified by id
            }
        }
        ''',
                                                     UserService: '''
        service UserService {
            type Query {
                userByIds(id: ID): User
            }
            type User {
                id: ID
                restricted: String 
            }
        }
        '''])
        def issueSchema = TestUtil.schema("""
        type Query {
            issue : Issue
            myIssue: Issue
        }
        type Issue {
            id: ID
            authorId: ID
            relatedIssue: Issue                 
        }
        """)
        def userServiceSchema = TestUtil.schema("""
        type Query {
            usersById(id: [ID]): [User]
        }
        type User {
            id: ID
            restricted: String
        }
        """)
        def query = "{issue { ...IssueFragment relatedIssue { ...IssueFragment }}} fragment IssueFragment on Issue {id author {id restricted}}"

        return [overallSchema, issueSchema, userServiceSchema, query]
    }

    def "restricted field inside hydration via fragments used twice"() {
        given:
        def (GraphQLSchema overallSchema, GraphQLSchema issueSchema, GraphQLSchema userServiceSchema, String query) = setupFragmentHydrationTests()

        ServiceExecutionHooks hooks = createServiceExecutionHooksWithFieldRemoval(["restricted"])

        def expectedQuery1 = "query nadel_2_Issues {issue {...IssueFragment relatedIssue {...IssueFragment}}} fragment IssueFragment on Issue {id authorId}"
        def response1 = [issue: [id: "ID1", authorId: "USER-1", relatedIssue: [id: "ID2", authorId: "USER-2"]]]

        def expectedQuery2 = "query nadel_2_UserService {usersById(id:[\"USER-1\",\"USER-2\"]) {id object_identifier__UUID:id}}"
        def response2 = [usersById: [
                [id: "USER-1", object_identifier__UUID: "USER-1"],
                [id: "USER-2", object_identifier__UUID: "USER-2"]
        ]]

        def overallResponse = [issue: [id: "ID1", author: [id: "USER-1", restricted: null], relatedIssue: [id: "ID2", author: [id: "USER-2", restricted: null]]]]

        when:
        def (Map response, List<GraphQLError> errors) = test2Services(
                overallSchema,
                "Issues",
                issueSchema,
                "UserService",
                userServiceSchema,
                query,
                ["issue"],
                expectedQuery1,
                response1,
                expectedQuery2,
                response2,
                hooks,
                Mock(ResultComplexityAggregator)
        )
        then:
        response == overallResponse
        errors.size() == 1
        errors[0].message.contains("removed field")
    }

    def "restricted single field inside hydration via fragments used twice"() {
        given:
        def (GraphQLSchema overallSchema, GraphQLSchema issueSchema, GraphQLSchema userServiceSchema, String query) = setupFragmentHydrationTests()

        def hooks = new ServiceExecutionHooks() {
            @Override
            CompletableFuture<Optional<GraphQLError>> isFieldForbidden(NormalizedQueryField normalizedField, List<Argument> hydrationRootArguments, ExecutionContext executionContext, Object userSuppliedContext) {
                if (normalizedField.getName() == "restricted" && normalizedField.getParent().getParent().getName() == "issue") {
                    //temporary GraphQLError ->  need to implement a field permissions denied error
                    return CompletableFuture.completedFuture(Optional.of(new AbortExecutionException("removed field")))
                }
                return CompletableFuture.completedFuture(Optional.empty())
            }
        }

        def expectedQuery1 = "query nadel_2_Issues {issue {...IssueFragment relatedIssue {...IssueFragment}}} fragment IssueFragment on Issue {id authorId}"
        def response1 = [issue: [id: "ID1", authorId: "USER-1", relatedIssue: [id: "ID2", authorId: "USER-2"]]]

        def expectedQuery2 = "query nadel_2_UserService {usersById(id:[\"USER-1\",\"USER-2\"]) {id restricted object_identifier__UUID:id}}"
        def response2 = [usersById: [
                [id: "USER-1", restricted: "superSecret", object_identifier__UUID: "USER-1"],
                [id: "USER-2", restricted: "secret", object_identifier__UUID: "USER-2"]
        ]]

        def overallResponse = [issue: [id: "ID1", author: [id: "USER-1", restricted: null], relatedIssue: [id: "ID2", author: [id: "USER-2", restricted: "secret"]]]]

        when:
        def (Map response, List<GraphQLError> errors) = test2Services(
                overallSchema,
                "Issues",
                issueSchema,
                "UserService",
                userServiceSchema,
                query,
                ["issue"],
                expectedQuery1,
                response1,
                expectedQuery2,
                response2,
                hooks,
                Mock(ResultComplexityAggregator)
        )
        then:
        response == overallResponse
        errors.size() == 1
        errors[0].message.contains("removed field")
    }

}
