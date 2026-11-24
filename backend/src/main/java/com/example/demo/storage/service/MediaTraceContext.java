package com.example.demo.storage.service;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.amqp.core.MessageProperties;

import java.util.HashMap;
import java.util.List;

record MediaTraceContext(String parent, String state) {
    private static final String PARENT = "traceparent";
    private static final String STATE = "tracestate";
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<MediaTraceContext> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(MediaTraceContext carrier) {
            return List.of(PARENT, STATE);
        }

        @Override
        public String get(MediaTraceContext carrier, String key) {
            if (carrier == null) {
                return null;
            }
            return switch (key) {
                case PARENT -> carrier.parent();
                case STATE -> carrier.state();
                default -> null;
            };
        }
    };

    static MediaTraceContext capture() {
        var headers = new HashMap<String, String>();
        PROPAGATOR.inject(Context.current(), headers, HashMap::put);
        return new MediaTraceContext(headers.get(PARENT), headers.get(STATE));
    }

    Scope makeCurrent() {
        return PROPAGATOR.extract(Context.current(), this, GETTER).makeCurrent();
    }

    void inject(MessageProperties properties) {
        if (parent != null) {
            properties.setHeader(PARENT, parent);
        }
        if (state != null) {
            properties.setHeader(STATE, state);
        }
    }
}
