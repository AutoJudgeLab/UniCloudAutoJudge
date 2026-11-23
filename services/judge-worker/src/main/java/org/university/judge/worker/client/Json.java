package org.university.judge.worker.client;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON for the worker transport; unknown response fields are ignored so the API can grow. */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() { }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (RuntimeException e) {
            throw new ApiException("Malformed " + type.getSimpleName() + " from the API", e);
        } catch (Exception e) {
            throw new ApiException("Malformed " + type.getSimpleName() + " from the API", e);
        }
    }
}
