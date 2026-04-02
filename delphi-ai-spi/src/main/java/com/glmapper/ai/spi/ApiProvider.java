package com.glmapper.ai.spi;

import com.glmapper.ai.api.AssistantMessageEventStream;
import com.glmapper.ai.api.Context;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.StreamOptions;

public interface ApiProvider {
    String api();

    AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);

    default AssistantMessageEventStream streamSimple(Model model, Context context, StreamOptions options) {
        return stream(model, context, options);
    }
}
