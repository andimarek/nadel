package graphql.nadel.e2e


import graphql.nadel.Nadel
import graphql.nadel.NadelExecutionInput
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.testutils.TestUtil
import org.junit.Ignore
import spock.lang.Specification

import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import static graphql.nadel.testutils.TestUtil.typeDefinitions
import static java.util.concurrent.CompletableFuture.completedFuture
@Ignore
class NadelE2ERenameHydrationTest extends Specification {
    // There's currently a bug that makes this test incorrectly pass
    def 'mutation with nested renamed fields, field types and a hydration call'() {
        def simpleNDSL = [IssuesService: '''
         service IssuesService {
            type Query {
                findIssueOwner(id: ID): SpecificIssueOwner
            }
           
            type SpecificIssueOwner => renamed from IssueOwner {
                identity: String
            }
 
            type SpecificIssue => renamed from Issue  {
                id: ID
                name: SpecificIssueOwner => hydrated from IssuesService.findIssueOwner(id: $source.id)
            }

            type UpdateSpecificIssuePayload => renamed from UpdateIssuePayload {
                specificIssue: SpecificIssue => renamed from issue
            }
 
            type Mutation {
                updateSpecificIssue: UpdateSpecificIssuePayload => renamed from updateIssue
            }
         }
        ''']

        def directiveBaseNDSL = [IssuesService: '''
            type Query {
                findIssueOwner(id: ID): SpecificIssueOwner
            }
            
            type SpecificIssueOwner @renamed(from : "IssueOwner") {
                identity: String
            }
            
            type SpecificIssue @renamed(from : "Issue")  {
                id: ID
                name: SpecificIssueOwner @hydrated(service: "IssuesService" field: "findIssueOwner" arguments : [{ name : "id" value : "$source.id"}] )
            }

            type UpdateSpecificIssuePayload @renamed(from : "UpdateIssuePayload") {
                specificIssue: SpecificIssue @renamed(from : "issue")
            }
            type Mutation {
                updateSpecificIssue: UpdateSpecificIssuePayload @renamed(from : "updateIssue")
            }
        ''']

        def underlyingSchema = typeDefinitions('''
            type Query {
                findIssueOwner(id: ID): IssueOwner
            }
 
            type IssueOwner {
                identity: String
            }
 
            type Issue {
                id: ID
            }
 
            type UpdateIssuePayload {
                issue: Issue
            }
 
            type Mutation {
                updateIssue: UpdateIssuePayload
            }
        ''')

        def delegatedExecution = Mock(ServiceExecution)
        def serviceFactory = TestUtil.serviceFactory(delegatedExecution, underlyingSchema)

        def query = '''
        mutation { 
            updateSpecificIssue { 
                specificIssue { 
                    id
                    name {
                        identity
                    }
                }
            } 
        }
        '''

        given:
        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()

        Nadel directiveBasedNadel = newNadel()
                .dsl(directiveBaseNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        def topLevelData = [updateIssue: [issue: [id: "1"]]]

        def hydrationData = [findIssueOwner: [identity: "Luna"]]

        ServiceExecutionResult topLevelResult = new ServiceExecutionResult(topLevelData)
        ServiceExecutionResult hydrationResult = new ServiceExecutionResult(hydrationData)

        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >>
                completedFuture(topLevelResult)
        1 * delegatedExecution.execute(_) >>
                completedFuture(hydrationResult)

        result.join().data == [updateSpecificIssue: [specificIssue: [name: [identity: "Luna"]]]]


        when:
        result = directiveBasedNadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >>
                completedFuture(topLevelResult)
        1 * delegatedExecution.execute(_) >>
                completedFuture(hydrationResult)

        result.join().data == [updateSpecificIssue: [specificIssue: [name: [identity: "Luna"]]]]

    }

}
