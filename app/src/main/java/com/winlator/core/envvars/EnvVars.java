package com.winlator.core.envvars;

import androidx.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class EnvVars implements Iterable<String> {
    private static final String VK_INSTANCE_LAYERS = "VK_INSTANCE_LAYERS";
    private static final String VK_LOADER_LAYERS_ENABLE = "VK_LOADER_LAYERS_ENABLE";

    private final LinkedHashMap<String, String> data = new LinkedHashMap<>();

    public EnvVars() {}

    public EnvVars(String values) {
        putAll(values);
    }

    public void put(String name, Object value) {
        String stringValue = String.valueOf(value);
        data.put(name, stringValue);

        // Vulkan loader 1.3.234+ added the loader-filter environment controls and
        // deprecated VK_INSTANCE_LAYERS. GameNative still needs the legacy variable
        // for older Wine/Vulkan-loader builds, so mirror that selection forward rather
        // than choosing one loader generation. This is layer-name based, not GPU/vendor
        // based, and therefore applies to LSFG as well as any other explicitly enabled
        // Vulkan layer.
        if (VK_INSTANCE_LAYERS.equals(name)) {
            mirrorLegacyVulkanLayersToModernFilter(stringValue);
        }
    }

    public void putAll(String values) {
        if (values == null || values.isEmpty()) return;
        for (String part : splitOnUnescapedSpaces(values)) {
            int index = part.indexOf("=");
            // tolerate stray tokens (legacy data corrupted by old unescaped serializer)
            if (index < 0) continue;
            String name = unescape(part.substring(0, index));
            String value = unescape(part.substring(index + 1));
            put(name, value);
        }
    }

    public void putAll(EnvVars envVars) {
        if (envVars == this) return;
        for (Map.Entry<String, String> entry : envVars.data.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public String get(String name) {
        return data.getOrDefault(name, "");
    }

    public void remove(String name) {
        data.remove(name);
    }

    public boolean has(String name) {
        return data.containsKey(name);
    }

    public void clear() {
        data.clear();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    // canonical persistence form: escape so putAll round-trips losslessly
    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String key : data.keySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(escape(key)).append('=').append(escape(data.get(key)));
        }
        return sb.toString();
    }

    // for shell composition (env KEY=val ... cmd) — same escape rules
    public String toEscapedString() {
        return toString();
    }

    // for execve envp — values must be raw, no escaping
    public String[] toStringArray() {
        String[] stringArray = new String[data.size()];
        int index = 0;
        for (String key : data.keySet()) stringArray[index++] = key+"="+data.get(key);
        return stringArray;
    }

    @NonNull
    @Override
    public Iterator<String> iterator() {
        return data.keySet().iterator();
    }

    private void mirrorLegacyVulkanLayersToModernFilter(String legacyLayers) {
        Set<String> enabledLayers = new LinkedHashSet<>();
        addSeparatedValues(enabledLayers, data.get(VK_LOADER_LAYERS_ENABLE), ",");
        addSeparatedValues(enabledLayers, legacyLayers, "[:;]");
        if (!enabledLayers.isEmpty()) {
            data.put(VK_LOADER_LAYERS_ENABLE, String.join(",", enabledLayers));
        }
    }

    private static void addSeparatedValues(Set<String> target, String values, String separatorRegex) {
        if (values == null || values.isEmpty()) return;
        for (String value : values.split(separatorRegex)) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) target.add(trimmed);
        }
    }

    private static String escape(String s) {
        // escape backslash FIRST so we don't double-escape the slashes we add for spaces
        return s.replace("\\", "\\\\").replace(" ", "\\ ");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static java.util.List<String> splitOnUnescapedSpaces(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));
            } else if (c == ' ') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
