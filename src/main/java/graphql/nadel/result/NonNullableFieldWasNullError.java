package graphql.nadel.result;

import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorHelper;
import graphql.PublicApi;
import graphql.execution.ResultPath;
import graphql.language.SourceLocation;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.List;

import static graphql.schema.GraphQLTypeUtil.simplePrint;

/**
 * This is the base error that indicates that a non null field value was in fact null.
 */
@PublicApi
public class NonNullableFieldWasNullError implements GraphQLError {

    private final String message;
    private final List<Object> path;

    public NonNullableFieldWasNullError(GraphQLNonNull nonNullType, ResultPath executionPath) {
        GraphQLType unwrappedTyped = GraphQLTypeUtil.unwrapNonNull(nonNullType);
        this.path = executionPath.toList();
        this.message = String.format(
                "The field at path '%s' was declared as a non null type, but the code involved in retrieving" +
                        " data has wrongly returned a null value.  The graphql specification requires that the" +
                        " parent field be set to null, or if that is non nullable that it bubble up null to its parent and so on." +
                        " The non-nullable type is '%s'.",
                executionPath, simplePrint(unwrappedTyped));
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public List<Object> getPath() {
        return path;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return null;
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.NullValueInNonNullableField;
    }

    @Override
    public String toString() {
        return "NonNullableFieldWasNullError{" +
                "message='" + message + '\'' +
                ", path=" + path +
                '}';
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override
    public boolean equals(Object o) {
        return GraphqlErrorHelper.equals(this, o);
    }

    @Override
    public int hashCode() {
        return GraphqlErrorHelper.hashCode(this);
    }
}
