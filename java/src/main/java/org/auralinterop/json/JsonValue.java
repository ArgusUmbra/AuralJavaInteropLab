package org.auralinterop.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable JSON values supported by the AURAL Draft 0.2 integer-only profile. */
public sealed interface JsonValue permits JsonValue.JsonObject, JsonValue.JsonArray,
        JsonValue.JsonString, JsonValue.JsonInteger, JsonValue.JsonBoolean, JsonValue.JsonNull {

    long MIN_SAFE_INTEGER = -9_007_199_254_740_991L;
    long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    record JsonObject(Map<String, JsonValue> members) implements JsonValue {
        public JsonObject {
            Objects.requireNonNull(members, "members");
            LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> entry : members.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "object key"),
                        Objects.requireNonNull(entry.getValue(), "object value"));
            }
            members = Collections.unmodifiableMap(copy);
        }
    }

    record JsonArray(List<JsonValue> values) implements JsonValue {
        public JsonArray {
            Objects.requireNonNull(values, "values");
            ArrayList<JsonValue> copy = new ArrayList<>(values.size());
            for (JsonValue value : values) {
                copy.add(Objects.requireNonNull(value, "array value"));
            }
            values = Collections.unmodifiableList(copy);
        }
    }

    record JsonString(String value) implements JsonValue {
        public JsonString {
            Objects.requireNonNull(value, "value");
        }
    }

    record JsonInteger(long value) implements JsonValue {
        public JsonInteger {
            if (value < MIN_SAFE_INTEGER || value > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("integer is outside the AURAL safe range");
            }
        }
    }

    record JsonBoolean(boolean value) implements JsonValue {
    }

    enum JsonNull implements JsonValue {
        INSTANCE
    }
}
