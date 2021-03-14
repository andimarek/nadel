package graphql.nadel;

import graphql.PublicApi;

@PublicApi
public class NadelExecutionHints {
    private final boolean optimizeOnNoTransformations;

    private NadelExecutionHints(Builder builder) {
        this.optimizeOnNoTransformations = builder.optimizeOnNoTransformations;
    }

    public static Builder newHints() {
        return new Builder();
    }

    public boolean isOptimizeOnNoTransformations() {
        return optimizeOnNoTransformations;
    }

    public static class Builder {
        private boolean optimizeOnNoTransformations;

        private Builder() {
        }

        public Builder optimizeOnNoTransformations(boolean flag) {
            this.optimizeOnNoTransformations = flag;
            return this;
        }

        public NadelExecutionHints build() {
            return new NadelExecutionHints(this);
        }
    }
}
