package graphql.nadel.dsl;

import graphql.Internal;
import graphql.language.AbstractNode;
import graphql.language.Comment;
import graphql.language.IgnoredChars;
import graphql.language.Node;
import graphql.language.NodeChildrenContainer;
import graphql.language.NodeVisitor;
import graphql.language.SourceLocation;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.Assert.assertTrue;

@Internal
public class UnderlyingServiceHydration extends AbstractNode<UnderlyingServiceHydration> {

    private final String serviceName;
    private final String topLevelField;
    private final String syntheticField;
    private final List<RemoteArgumentDefinition> arguments;
    private final String objectIdentifier;
    private final boolean objectIndexed;
    private final Integer batchSize;

    public UnderlyingServiceHydration(SourceLocation sourceLocation,
                                      List<Comment> comments,
                                      String serviceName,
                                      String topLevelField,
                                      String syntheticField,
                                      List<RemoteArgumentDefinition> arguments,
                                      String objectIdentifier,
                                      boolean objectIndexed,
                                      Integer batchSize,
                                      Map<String, String> additionalData
    ) {
        super(sourceLocation, comments, IgnoredChars.EMPTY, additionalData);
        assertTrue(!objectIndexed ^ objectIdentifier == null, () -> "An object identifier cannot be provided if the hydration is by index");

        this.serviceName = serviceName;
        this.topLevelField = topLevelField;
        this.arguments = arguments;
        this.objectIdentifier = objectIdentifier;
        this.objectIndexed = objectIndexed;
        this.batchSize = batchSize;
        this.syntheticField = syntheticField;
    }


    public Integer getBatchSize() {
        return batchSize;
    }

    public String getObjectIdentifier() {
        return objectIdentifier;
    }

    public boolean isObjectMatchByIndex() {
        return objectIndexed;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getTopLevelField() {
        return topLevelField;
    }

    public List<RemoteArgumentDefinition> getArguments() {
        return new ArrayList<>(arguments);
    }

    @Override
    public List<Node> getChildren() {
        return null;
    }

    @Override
    public NodeChildrenContainer getNamedChildren() {
        return null;
    }

    @Override
    public UnderlyingServiceHydration withNewChildren(NodeChildrenContainer newChildren) {
        return null;
    }

    @Override
    public boolean isEqualTo(Node node) {
        return false;
    }

    @Override
    public UnderlyingServiceHydration deepCopy() {
        return null;
    }


    @Override
    public TraversalControl accept(TraverserContext<Node> context, NodeVisitor visitor) {
        return null;
    }

    public String getSyntheticField() {
        return syntheticField;
    }
}
