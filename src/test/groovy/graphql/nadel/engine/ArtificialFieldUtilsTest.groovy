package graphql.nadel.engine

import graphql.language.AstPrinter
import graphql.nadel.NadelExecutionHints
import graphql.nadel.result.ExecutionResultNode
import graphql.nadel.result.LeafExecutionResultNode
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import graphql.util.TreeTransformerUtil
import spock.lang.Specification

import static graphql.nadel.engine.ArtificialFieldUtils.TYPE_NAME_ALIAS_PREFIX_FOR_INTERFACES_AND_UNIONS
import static graphql.nadel.testutils.ExecutionResultNodeUtil.leaf
import static graphql.nadel.testutils.ExecutionResultNodeUtil.list
import static graphql.nadel.testutils.ExecutionResultNodeUtil.object
import static graphql.nadel.testutils.ExecutionResultNodeUtil.root
import static graphql.nadel.testutils.ExecutionResultNodeUtil.toData
import static graphql.nadel.testutils.TestUtil.mkField

class ArtificialFieldUtilsTest extends Specification {

    def context = NadelContext.newContext()
                              .nadelExecutionHints(NadelExecutionHints.newHints().optimizeOnNoTransformations(true).build())
                              .build()
    def underscoreTypeNameAlias = TYPE_NAME_ALIAS_PREFIX_FOR_INTERFACES_AND_UNIONS + context.underscoreTypeNameAlias
    def underscoreTypeNameAliasOnEmptySelections = "empty_selection_set_" + context.underscoreTypeNameAlias
    def interfaceType = GraphQLInterfaceType.newInterface().name("I").build()
    def objectType = GraphQLObjectType.newObject().name("O").build()

    def "test that underscore type alias is added on interfaces"() {

        def petField = mkField('''
            pet {
                name
                title
            }
        ''')

        when:
        def newField = ArtificialFieldUtils.maybeAddUnderscoreTypeName(context, petField, interfaceType)
        then:
        AstPrinter.printAstCompact(newField) == """pet {name title $underscoreTypeNameAlias:__typename}"""
    }

    def "test that underscore type alias is added on interfaces even if they have an __typename"() {

        def petField = mkField('''
            pet {
                name
                title
                ... {
                    __typename
                }
            }
        ''')

        when:
        def newField = ArtificialFieldUtils.maybeAddUnderscoreTypeName(context, petField, interfaceType)
        then:
        AstPrinter.printAstCompact(newField) == """pet {name title ... {__typename} $underscoreTypeNameAlias:__typename}"""
    }

    def "test that underscore type alias is added on empty selection sets"() {

        def petField = mkField('''
            pet
        ''')

        when:
        def newField = ArtificialFieldUtils.maybeAddEmptySelectionSetUnderscoreTypeName(context, petField, objectType)
        then:
        AstPrinter.printAstCompact(newField) == """pet {$underscoreTypeNameAliasOnEmptySelections:__typename}"""
    }

    def "test that underscore type alias for empty selections is not added if alias for interfaces is already present"() {

        def petField = mkField("""
            pet {
                $underscoreTypeNameAlias:__typename
            }
        """)

        when:
        def newField = ArtificialFieldUtils.maybeAddEmptySelectionSetUnderscoreTypeName(context, petField, objectType)
        then:
        AstPrinter.printAstCompact(newField) == """pet {$underscoreTypeNameAlias:__typename}"""
    }

    def "test that underscore type alias for empty selections is not added if it is already present"() {

        def petField = mkField("""
            pet {
                $underscoreTypeNameAliasOnEmptySelections:__typename
            }
        """)

        when:
        def newField = ArtificialFieldUtils.maybeAddEmptySelectionSetUnderscoreTypeName(context, petField, interfaceType)
        then:
        AstPrinter.printAstCompact(newField) == """pet {$underscoreTypeNameAliasOnEmptySelections:__typename}"""
    }

    def "test that underscore type alias is skipped on object"() {

        def petField = mkField('''
            pet {
                name
                title
            }
        ''')

        when:
        def newField = ArtificialFieldUtils.maybeAddUnderscoreTypeName(context, petField, objectType)
        then:
        AstPrinter.printAstCompact(newField) == """pet {name title}"""
    }

    def "test that it removes the aliased fields but leaves the specific one alone"() {

        def startingNode = root([
                object("pet", [
                        leaf("__typename"), //  <-- manually added by consumer
                        list("owners", [
                                object("X", [
                                        leaf("__typename"), //  <-- manually added by consumer say via Fragment Spread
                                        leaf("name"),
                                        leaf("__typename", underscoreTypeNameAlias),
                                        leaf("title"),
                                ]),
                                object("Y", [
                                        leaf("name"),
                                        leaf("title"),
                                ]),
                                object("Z", [
                                        leaf("__typename", underscoreTypeNameAliasOnEmptySelections),
                                        leaf("name"),
                                        leaf("title"),
                                ])
                        ])
                ])
        ])

        when:
        def newNode = removeArtificialFields(context, startingNode)
        def data = toData(newNode)
        then:
        data == [
                pet: [
                        __typename: "__typenameVal",
                        owners    : [
                                [
                                        __typename: "__typenameVal",
                                        name      : "nameVal",
                                        title     : "titleVal",
                                ],
                                [
                                        name : "nameVal",
                                        title: "titleVal",
                                ],
                                [
                                        name : "nameVal",
                                        title: "titleVal",
                                ],
                        ]
                ]
        ]
    }

    def "test that leaves everything alone"() {

        def startingNode = root([
                object("pet", [
                        leaf("__typename"), //  <-- manually added by consumer
                        list("owners", [
                                object("X", [
                                        leaf("__typename"), //  <-- manually added by consumer say via Fragment Spread
                                        leaf("name"),
                                        leaf("title"),
                                ]),
                                object("Y", [
                                        leaf("name"),
                                        leaf("title"),
                                ]),
                        ])
                ])
        ])

        when:
        def newNode = removeArtificialFields(context, startingNode)
        then:
        // zippers allow for no change to objects if there is no change
        newNode == startingNode
    }

    static ExecutionResultNode removeArtificialFields(NadelContext nadelContext, ExecutionResultNode resultNode) {
        ResultNodesTransformer resultNodesTransformer = new ResultNodesTransformer();
        ExecutionResultNode newNode = resultNodesTransformer.transform(resultNode, new TraverserVisitorStub<ExecutionResultNode>() {
            @Override
            TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                ExecutionResultNode node = context.thisNode()
                if (node instanceof LeafExecutionResultNode) {
                    LeafExecutionResultNode leaf = (LeafExecutionResultNode) node;

                    if (ArtificialFieldUtils.isArtificialField(nadelContext, leaf.getAlias())) {
                        return TreeTransformerUtil.deleteNode(context);
                    }
                }
                return TraversalControl.CONTINUE;
            }
        });
        return newNode;
    }


}
