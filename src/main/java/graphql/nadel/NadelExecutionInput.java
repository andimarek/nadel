package graphql.nadel;

import graphql.PublicApi;
import graphql.execution.ExecutionId;

import java.util.LinkedHashMap;
import java.util.Map;

import static graphql.Assert.assertNotNull;
import static graphql.GraphQLContext.newContext;
import static java.util.Objects.requireNonNull;

@PublicApi
public class NadelExecutionInput {

    private final String query;
    private final String operationName;
    private final Object context;
    private final Map<String, Object> variables;
    private final String artificialFieldsUUID;
    private final ExecutionId executionId;
    private final NadelExecutionHints nadelExecutionHints;

    private NadelExecutionInput(String query,
                                String operationName,
                                Object context, Map<String, Object> variables,
                                String artificialFieldsUUID,
                                ExecutionId executionId,
                                NadelExecutionHints nadelExecutionHints) {
        this.query = requireNonNull(query);
        this.operationName = operationName;
        this.context = context;
        this.variables = requireNonNull(variables);
        this.artificialFieldsUUID = artificialFieldsUUID;
        this.executionId = executionId;
        this.nadelExecutionHints = nadelExecutionHints;
    }

    public static Builder newNadelExecutionInput() {
        return new Builder();
    }

    public String getQuery() {
        return query;
    }

    public String getArtificialFieldsUUID() {
        return artificialFieldsUUID;
    }

    public String getOperationName() {
        return operationName;
    }

    public Object getContext() {
        return context;
    }

    public Map<String, Object> getVariables() {
        return new LinkedHashMap<>(variables);
    }
    /**
     * @return Id that will be/was used to execute this operation.
     */
    public ExecutionId getExecutionId() {
        return executionId;
    }

    public NadelExecutionHints getNadelExecutionHints() {
        return nadelExecutionHints;
    }

    public static class Builder {
        private String query;
        private String operationName;
        private Object context = newContext().build();
        private Map<String, Object> variables = new LinkedHashMap<>();
        private String artificialFieldsUUID;
        private ExecutionId executionId;
        private NadelExecutionHints nadelExecutionHints = NadelExecutionHints.newHints().build();

        private Builder() {
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder operationName(String operationName) {
            this.operationName = operationName;
            return this;
        }

        public Builder context(Object context) {
            this.context = context;
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            this.variables = variables;
            return this;
        }

        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder artificialFieldsUUID(String artificialFieldsUUID) {
            this.artificialFieldsUUID = artificialFieldsUUID;
            return this;
        }

        public Builder nadelExecutionHints(NadelExecutionHints nadelExecutionHints) {
            this.nadelExecutionHints = assertNotNull(nadelExecutionHints);
            return this;
        }

        public NadelExecutionInput build() {
            return new NadelExecutionInput(query, operationName, context, variables, artificialFieldsUUID, executionId, nadelExecutionHints);
        }

    }
}
