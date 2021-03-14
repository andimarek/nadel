package graphql.nadel.engine;

import graphql.Internal;
import graphql.nadel.result.ExecutionResultNode;
import graphql.schema.GraphQLSchema;

import java.util.Map;

@Internal
public class UnapplyEnvironment {


    public UnapplyEnvironment(ExecutionResultNode correctParentNode,
                              ExecutionResultNode directParentNode,
                              boolean isHydrationTransformation,
                              boolean batched,
                              Map<String, String> typeRenameMappings,
                              GraphQLSchema overallSchema
    ) {
        this.isHydrationTransformation = isHydrationTransformation;
        this.batched = batched;
        this.typeRenameMappings = typeRenameMappings;
        this.overallSchema = overallSchema;
        this.correctParentNode = correctParentNode;
        this.directParentNode = directParentNode;
    }

    // this is the parent node mapped to the overall schema
    public ExecutionResultNode correctParentNode;
    // this is the parent node returned from the underlying service;
    public ExecutionResultNode directParentNode;
    public boolean isHydrationTransformation;
    public boolean batched;
    public Map<String, String> typeRenameMappings;
    public GraphQLSchema overallSchema;

}
