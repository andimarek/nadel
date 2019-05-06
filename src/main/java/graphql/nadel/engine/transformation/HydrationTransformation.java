package graphql.nadel.engine.transformation;

import graphql.analysis.QueryVisitorFieldEnvironment;
import graphql.language.Field;
import graphql.language.Node;
import graphql.nadel.dsl.InnerServiceHydration;
import graphql.nadel.dsl.RemoteArgumentDefinition;
import graphql.nadel.dsl.RemoteArgumentSource;
import graphql.schema.GraphQLOutputType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

import java.util.List;

import static graphql.Assert.assertTrue;

public class HydrationTransformation extends AbstractFieldTransformation {

    private String originalName;
    private Field originalField;
    private Field newField;
    private GraphQLOutputType fieldType;

    private InnerServiceHydration innerServiceHydration;

    public HydrationTransformation(InnerServiceHydration innerServiceHydration) {
        this.innerServiceHydration = innerServiceHydration;
    }

    @Override
    public TraversalControl apply(QueryVisitorFieldEnvironment environment) {
        super.apply(environment);

        TraverserContext<Node> context = environment.getTraverserContext();
        List<RemoteArgumentDefinition> arguments = innerServiceHydration.getArguments();
        assertTrue(arguments.size() == 1, "only hydration with one argument are supported");
        RemoteArgumentDefinition remoteArgumentDefinition = arguments.get(0);
        RemoteArgumentSource remoteArgumentSource = remoteArgumentDefinition.getRemoteArgumentSource();
        assertTrue(remoteArgumentSource.getSourceType() == RemoteArgumentSource.SourceType.OBJECT_FIELD,
                "only object field arguments are supported at the moment");
        String hydrationSourceName = remoteArgumentSource.getName();
        originalField = environment.getField();
        originalName = environment.getField().getName();
        newField = environment.getField().transform(builder -> builder.selectionSet(null).name(hydrationSourceName));
        return TreeTransformerUtil.changeNode(context, newField);
    }

    @Override
    public Field unapplyField(Field field) {
        return originalField;
    }

    public Field getNewField() {
        return newField;
    }

    public InnerServiceHydration getInnerServiceHydration() {
        return innerServiceHydration;
    }

}
