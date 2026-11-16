package com.kooo.evcam.configplaza;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 银河 E5 离线配置快照数据模型及 R8 安全的无反射 JSON 编解码器。
 */
public final class E5ConfigSnapshot {

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_IMPORTED = "imported";

    public static final class SectionData {
        public final int version;
        @NonNull
        public final Map<String, Object> values;

        public SectionData(int version, @NonNull Map<String, Object> values) {
            this.version = version;
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    public final int schemaVersion;
    @NonNull
    public final String vehicleModel;
    @NonNull
    public final String id;
    @NonNull
    public final String name;
    @NonNull
    public final String description;
    @NonNull
    public final String authorNote;
    @NonNull
    public final String createdAt;
    @NonNull
    public final String sourceType;
    @NonNull
    public final Map<E5ConfigSection, SectionData> sections;

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public E5ConfigSnapshot(
            int schemaVersion,
            @NonNull String vehicleModel,
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull String authorNote,
            @NonNull String createdAt,
            @NonNull String sourceType,
            @NonNull Map<E5ConfigSection, SectionData> sections) {
        this.schemaVersion = schemaVersion;
        this.vehicleModel = vehicleModel;
        this.id = id;
        this.name = name;
        this.description = description;
        this.authorNote = authorNote;
        this.createdAt = createdAt;
        this.sourceType = normalizeSourceType(sourceType);
        this.sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections));
    }

    @NonNull
    public static String normalizeSourceType(@Nullable String raw) {
        if (SOURCE_BUILTIN.equals(raw)) {
            return SOURCE_BUILTIN;
        }
        if (SOURCE_IMPORTED.equals(raw)) {
            return SOURCE_IMPORTED;
        }
        return SOURCE_LOCAL;
    }

    @NonNull
    public Set<E5ConfigSection> getSectionSet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sections.keySet()));
    }

    @Nullable
    public SectionData getSection(@NonNull E5ConfigSection section) {
        return sections.get(section);
    }

    @NonNull
    public E5ConfigSnapshot withMetadata(
            @NonNull String newId,
            @NonNull String newName,
            @NonNull String newDescription,
            @NonNull String newAuthorNote,
            @NonNull String newCreatedAt,
            @NonNull String newSourceType) {
        return new E5ConfigSnapshot(
                schemaVersion,
                vehicleModel,
                newId,
                newName,
                newDescription,
                newAuthorNote,
                newCreatedAt,
                newSourceType,
                sections
        );
    }

    @NonNull
    public JsonObject toJsonObject() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", schemaVersion);
        root.addProperty("vehicleModel", vehicleModel);
        root.addProperty("id", id);
        root.addProperty("name", name);
        root.addProperty("description", description);
        root.addProperty("authorNote", authorNote);
        root.addProperty("createdAt", createdAt);
        root.addProperty("sourceType", sourceType);

        JsonObject sectionsObj = new JsonObject();
        for (Map.Entry<E5ConfigSection, SectionData> entry : sections.entrySet()) {
            E5ConfigSection section = entry.getKey();
            SectionData data = entry.getValue();
            if (section == null || data == null) {
                continue;
            }
            JsonObject secObj = new JsonObject();
            secObj.addProperty("version", data.version);
            JsonObject valuesObj = new JsonObject();
            for (Map.Entry<String, Object> valEntry : data.values.entrySet()) {
                putTypedValue(valuesObj, valEntry.getKey(), valEntry.getValue());
            }
            secObj.add("values", valuesObj);
            sectionsObj.add(section.getId(), secObj);
        }
        root.add("sections", sectionsObj);
        return root;
    }

    @NonNull
    public String toJsonString(boolean pretty) {
        JsonObject obj = toJsonObject();
        return pretty ? PRETTY_GSON.toJson(obj) : obj.toString();
    }

    @NonNull
    public static E5ConfigSchema.ValidationResult parseAndValidate(@Nullable String rawJson) {
        return E5ConfigSchema.parseAndValidate(rawJson);
    }

    @Nullable
    static JsonElement parseJsonElement(@Nullable String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(rawJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void putTypedValue(@NonNull JsonObject target, @NonNull String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean) {
            target.addProperty(key, (Boolean) value);
        } else if (value instanceof Integer) {
            target.addProperty(key, (Integer) value);
        } else if (value instanceof Long) {
            target.addProperty(key, (Long) value);
        } else if (value instanceof Float) {
            target.addProperty(key, (Float) value);
        } else if (value instanceof Double) {
            target.addProperty(key, ((Double) value).floatValue());
        } else if (value instanceof String) {
            target.addProperty(key, (String) value);
        } else if (value instanceof JsonElement) {
            target.add(key, (JsonElement) value);
        } else if (value instanceof List<?>) {
            JsonArray arr = new JsonArray();
            for (Object item : (List<?>) value) {
                if (item instanceof Boolean) {
                    arr.add(new JsonPrimitive((Boolean) item));
                } else if (item instanceof Number) {
                    arr.add(new JsonPrimitive((Number) item));
                } else if (item != null) {
                    arr.add(new JsonPrimitive(item.toString()));
                }
            }
            target.add(key, arr);
        }
    }

    @NonNull
    public static List<Float> parseFloatList(@Nullable JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray arr = el.getAsJsonArray();
        List<Float> list = new ArrayList<>(arr.size());
        for (JsonElement item : arr) {
            if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber()) {
                list.add(item.getAsFloat());
            } else {
                return Collections.emptyList();
            }
        }
        return list;
    }
}
