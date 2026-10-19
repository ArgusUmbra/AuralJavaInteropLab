package org.auralinterop.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, parser-independent representation of an extension JSON value. */
public sealed interface ManifestValue permits ManifestValue.ObjectValue, ManifestValue.ArrayValue,
        ManifestValue.StringValue, ManifestValue.IntegerValue, ManifestValue.BooleanValue,
        ManifestValue.NullValue {

    record ObjectValue(Map<String, ManifestValue> members) implements ManifestValue {
        public ObjectValue {
            Objects.requireNonNull(members, "members");
            LinkedHashMap<String, ManifestValue> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ManifestValue> entry : members.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "member name"),
                        Objects.requireNonNull(entry.getValue(), "member value"));
            }
            members = Collections.unmodifiableMap(copy);
        }
    }

    record ArrayValue(List<ManifestValue> values) implements ManifestValue {
        public ArrayValue {
            Objects.requireNonNull(values, "values");
            ArrayList<ManifestValue> copy = new ArrayList<>(values.size());
            for (ManifestValue value : values) {
                copy.add(Objects.requireNonNull(value, "array value"));
            }
            values = Collections.unmodifiableList(copy);
        }
    }

    record StringValue(String value) implements ManifestValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record IntegerValue(long value) implements ManifestValue {
        public IntegerValue {
            if (value < -9_007_199_254_740_991L || value > 9_007_199_254_740_991L) {
                throw new IllegalArgumentException("integer is outside the Draft 0.2 safe range");
            }
        }
    }

    record BooleanValue(boolean value) implements ManifestValue {
    }

    enum NullValue implements ManifestValue {
        INSTANCE
    }
}
