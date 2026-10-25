package dev.sunwenqi.tcpreverserelay.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** Applied before storage and again before returning historical entries. */
public final class SecretRedactor {
    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private static final Pattern NAMED = Pattern.compile("(?i)((?:AES_KEY|TOKEN_KEY|ADMIN_TOKEN|password|authorization|autograph|encrypt|nonce)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s,;\\\"'}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[^\\s,;\\\"']+");

    public void add(String secret) {
        if (secret != null && !secret.isBlank() && !secrets.contains(secret)) secrets.add(secret);
    }
    public String clean(String value) {
        if (value == null) return "";
        String result = value;
        for (String secret : secrets.stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).toList())
            result = result.replace(secret, "[已隐藏]");
        result = NAMED.matcher(result).replaceAll("$1[已隐藏]");
        return BEARER.matcher(result).replaceAll("$1[已隐藏]");
    }
}
