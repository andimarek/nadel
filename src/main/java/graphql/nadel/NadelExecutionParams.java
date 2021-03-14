package graphql.nadel;

import graphql.Internal;

@Internal
public class NadelExecutionParams {

    private final String artificialFieldsUUID;
    private final NadelExecutionHints nadelExecutionHints;

    public NadelExecutionParams(String artificialFieldsUUID,
                                NadelExecutionHints nadelExecutionHints) {
        this.artificialFieldsUUID = artificialFieldsUUID;
        this.nadelExecutionHints = nadelExecutionHints;
    }

    public String getArtificialFieldsUUID() {
        return artificialFieldsUUID;
    }

    public NadelExecutionHints getNadelExecutionHints() {
        return nadelExecutionHints;
    }
}
