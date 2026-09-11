package io.github.zhumaniezov.codex.edt.protocol;

import java.io.IOException;
import java.io.StringReader;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

public final class CodexProtocol {
    public static final Gson JSON = new Gson();
    private CodexProtocol() { }

    public static JsonObject parse(String line) throws IOException {
        try (var reader = new JsonReader(new StringReader(line))) {
            reader.setLenient(false);
            JsonElement value = JSON.getAdapter(JsonElement.class).read(reader);
            if (value == null || !value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Ожидался один JSON-объект.");
            }
            return value.getAsJsonObject();
        } catch (RuntimeException | IOException error) {
            // Исходная строка может содержать личные данные; в исключение она не попадает.
            throw new IOException("Codex прислал некорректный JSONL.");
        }
    }

    public static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            result.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        }
        return result;
    }

    public static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    public static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    public static String redact(String text) {
        return text.replaceAll("(?i)(bearer\\s+)[^\\s,;\\\"]+", "$1[скрыто]")
            .replaceAll("(?:sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]+)", "[скрыто]")
            .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[скрыто]")
            .replaceAll("(?i)((?:token|password|secret|api[_-]?key)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"']+", "$1[скрыто]");
    }
}

