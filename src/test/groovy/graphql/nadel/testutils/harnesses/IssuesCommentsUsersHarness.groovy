package graphql.nadel.testutils.harnesses

import graphql.GraphQL
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionFactory
import graphql.nadel.testutils.TestUtil
import graphql.schema.DataFetcher
import graphql.schema.TypeResolver
import graphql.schema.idl.TypeDefinitionRegistry

import static graphql.nadel.testutils.TestUtil.delayedServiceExecution
import static graphql.nadel.testutils.TestUtil.schema
import static graphql.nadel.testutils.TestUtil.serviceFactory
import static graphql.nadel.testutils.TestUtil.typeDefinitions
import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring
import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring

/**
 * This test harness class is there here to give you a live graphql executable
 * set of services around the domain model of issues / comments and users.
 *
 * Tests can wire this together using their own Nadel declarations or use the one provided
 */
class IssuesCommentsUsersHarness {

    static def ndsl = [
            IssueService  : '''
        service IssueService {
         
            type Query {
                issues(jql : String) : [Issue]
                issueById(id : String) :  Issue
            }
            
            type Issue {
                id : ID
                key : String
                summary : String
                description : String
                epic: Epic
                reporter : User => hydrated from UserService.userById(id: $source.reporterId)
                comments : [Comment] => hydrated from CommentService.commentById(id: $source.commentIds)
            }
            
            type Epic {
                id: ID
                title: String
                description : String
            }
         }
         ''',
            CommentService: '''   
         service CommentService {
            type Query {
                comments(ids : [ID]) : [Comment]
                commentById(id : ID) : Comment
            }
            
            type Comment {
                id : ID
                commentText : String => renamed from text
                created : String
                author : User => hydrated from UserService.userById(id: $source.authorId)
            }
        }
        ''',
            UserService   : '''
        service UserService {
            type Query {
                users(ids : [ID]) : [User]
                userById(id : ID) : User
            }
            
            type User {
                userId : ID
                displayName : String
                avatarUrl : String
            }
        }
    ''']

    static def ISSUES_SDL = '''
        type Query {
            issues(jql : String) : [Issue]
            issueById(id : String) :  Issue
        }
        
        type Issue {
            id : ID
            key : String
            summary : String
            description : String
            epic: Epic
            reporterId : ID
            commentIds : [ID]
        }
        
        type Epic {
            id: ID
            title: String
            description : String
        }
    '''

    static def COMMENTS_SDL = '''
        type Query {
            comments(ids : [ID]) : [Comment]
            commentById(id : ID) : Comment
        }
        
        type Comment {
            id : ID
            text : String
            created : String
            authorId : ID
        }
        
    '''

    static def USERS_SDL = '''

        type Query {
            users(ids : [ID]) : [User]
            userById(id : ID) : User
        }
        
        # 
        # this is not quire working yet
        # but we should have interfaces fully working at some point
        #
        # interface User {
        #
        type User {
            userId : ID
            displayName : String
            avatarUrl : String
        }

        # type HumanUser implements User {
        #     userId : ID
        #     displayName : String
        #     avatarUrl : String
        # }

        # type AddOnUser implements User {
        #     userId : ID
        #     displayName : String
        #     avatarUrl : String
        #    addOnKey : String
        # }
    '''


    private static TypeDefinitionRegistry issuesSchema = typeDefinitions(ISSUES_SDL)
    private static TypeDefinitionRegistry commentsSchema = typeDefinitions(COMMENTS_SDL)
    private static TypeDefinitionRegistry usersSchema = typeDefinitions(USERS_SDL)

    static ServiceExecution issuesServiceExecution = TestUtil.serviceExecutionImpl(buildIssuesImpl())
    static ServiceExecution commentsServiceExecution = TestUtil.serviceExecutionImpl(buildCommentsImpl())
    static ServiceExecution usersServiceExecution = TestUtil.serviceExecutionImpl(buildUsersImpl())

    static def serviceFactory = serviceFactoryWithDelay(0)

    static ServiceExecutionFactory serviceFactoryWithDelay(def ms) {
        serviceFactory([
                IssueService  : new Tuple2(delayedServiceExecution(ms, issuesServiceExecution), issuesSchema),
                CommentService: new Tuple2(delayedServiceExecution(ms, commentsServiceExecution), commentsSchema),
                UserService   : new Tuple2(delayedServiceExecution(ms, usersServiceExecution), usersSchema),
        ])
    }

    //
    // Implementation under here
    //


    private static GraphQL buildIssuesImpl() {
        DataFetcher issuesDF = { env ->
            return issueData
        }
        DataFetcher issueByIdDF = { env ->
            def id = env.getArgument("id")
            def issue = issueData.find({ it.id == id })
            issue
        }
        def runtimeWiring = newRuntimeWiring()
                .type(newTypeWiring("Query")
                        .dataFetcher("issues", issuesDF)
                        .dataFetcher("issueById", issueByIdDF))
                .build()
        def graphQLSchema = schema(ISSUES_SDL, runtimeWiring)
        return GraphQL.newGraphQL(graphQLSchema).build()
    }

    private static GraphQL buildCommentsImpl() {
        DataFetcher commentsDF = { env ->
            List<String> ids = env.getArgument("ids")
            def comments = commentData.findAll({ cmt -> ids.contains(cmt.id) })
            return comments
        }
        DataFetcher commentByIdDF = { env ->
            def id = env.getArgument("id")
            def comment = commentData.find({ it.id == id })
            comment
        }
        def runtimeWiring = newRuntimeWiring()
                .type(newTypeWiring("Query")
                        .dataFetcher("comments", commentsDF)
                        .dataFetcher("commentById", commentByIdDF))
                .build()
        def graphQLSchema = schema(COMMENTS_SDL, runtimeWiring)
        return GraphQL.newGraphQL(graphQLSchema).build()
    }

    private static GraphQL buildUsersImpl() {
        DataFetcher usersDF = { env ->
            List<String> ids = env.getArgument("ids")
            def users = userData.findAll({ user -> ids.contains(user.userId) })
            return users
        }
        DataFetcher userByIdDF = { env ->
            def id = env.getArgument("id")
            def user = userData.find({ it.userId == id })
            user
        }
        TypeResolver userTR = { env ->
            def typeName = env.getObject() instanceof AddOnUser ? "AddOnUser" : "HumanUser"
            def type = env.schema.getObjectType(typeName)
            type
        }
        def runtimeWiring = newRuntimeWiring()
                .type(newTypeWiring("Query")
                        .dataFetcher("users", usersDF)
                        .dataFetcher("userById", userByIdDF))
                .type(newTypeWiring("User")
                        .typeResolver(userTR))
                .build()
        def graphQLSchema = schema(USERS_SDL, runtimeWiring)
        return GraphQL.newGraphQL(graphQLSchema).build()
    }


    static epicData = [
            new Epic("E1", "tech debt", "epic with tech debt")
    ]

    static issueData = [
            new Issue("I1", "fred", ["C1", "C3", "C5"], epicData[0]),
            new Issue("I2", "zed", ["C2", "C4", "C6"], epicData[0])
    ]

    static commentData = [
            new Comment("C1", "fred"),
            new Comment("C2", "ned"),
            new Comment("C3", "zed"),
            new Comment("C4", "jed"),
            new Comment("C5", "fred"),
            new Comment("C6", "ted"),
    ]

    static userData = [
            new User("fred"),
            new User("ted"),
            new User("zed"),
            new AddOnUser("jed"),
            new AddOnUser("ned"),
    ]

    static class Issue {

        String id
        String key
        String summary
        String description
        Epic epic
        String reporterId
        List<String> commentIds

        Issue(String id, String reporterId, List<String> commentIds, Epic epic) {
            this.id = id
            this.key = "WORK-" + id
            this.summary = "Summary for $key"
            this.description = "Description of $key"
            this.reporterId = reporterId
            this.commentIds = commentIds
            this.epic = epic
        }
    }

    static class Epic {
        String id
        String title
        String description

        Epic(String id, String title, String description) {
            this.id = id
            this.title = title
            this.description = description
        }
    }

    static class User {
        String userId
        String displayName
        String avatarUrl

        User(String userId) {
            this.userId = userId
            this.displayName = "Display name of $userId"
            this.avatarUrl = "http://avatar/$userId"
        }
    }

    static class AddOnUser extends User {
        String addOnKey

        AddOnUser(String userId) {
            super(userId)
            this.addOnKey = "addKey4$userId"
        }
    }

    static class Comment {
        String id
        String text
        String created
        String authorId

        Comment(String id, String authorId) {
            this.id = id
            this.authorId = authorId
            this.text = "Text of $id"
            this.created = "1969-08-08@$id"
        }
    }

    // run this to make sure our basic graphql underlying system is working as expected
    static void main(String[] args) {
        def er = buildIssuesImpl().execute('{ issues(jql:"whateva") { id, key, summary, description, commentIds, reporterId} } ')
        println er.data

        er = buildCommentsImpl().execute('{ comments(ids : ["C1", "C5"]) { id, text, created, authorId} } ')
        println er.data

        er = buildUsersImpl().execute('{ users(ids : ["fred", "zed"]) { userId, displayName, avatarUrl} } ')
        println er.data

        er = buildUsersImpl().execute('{ userById(id : "zed") { userId, displayName, avatarUrl} } ')
        println er.data

        er = buildIssuesImpl().execute('{ issueById(id : "I1") { id, key, summary, description} } ')
        println er.data
    }

}
