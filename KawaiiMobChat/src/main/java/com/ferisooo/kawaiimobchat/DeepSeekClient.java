package com.ferisooo.kawaiimobchat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

public final class DeepSeekClient {

    private final HttpClient http;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public DeepSeekClient(String endpoint, String apiKey, String model, int maxTokens, double temperature) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static final class Reply {
        public final String mood;   // angry | scared | sad | neutral | curious | friendly | excited
        public final String reply;  // mob's spoken line
        public final String error;  // null on success, error msg otherwise
        public Reply(String mood, String reply, String error) {
            this.mood = mood; this.reply = reply; this.error = error;
        }
    }

    /** A single turn in the conversation, forwarded as-is to the API. */
    public static final class Message {
        public final String role;     // "user" | "assistant"
        public final String content;
        public Message(String role, String content) { this.role = role; this.content = content; }
    }

    public Reply chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, Collections.emptyList());
    }

    public Reply chat(String systemPrompt, String userPrompt, List<Message> history) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.contains("PASTE")) {
            return new Reply(null, null, "missing api key");
        }
        StringBuilder messages = new StringBuilder();
        messages.append("{\"role\":\"system\",\"content\":\"")
                .append(jsonEscape(systemPrompt)).append("\"}");
        if (history != null) {
            for (Message m : history) {
                if (m == null || m.role == null || m.content == null) continue;
                messages.append(",{\"role\":\"").append(jsonEscape(m.role))
                        .append("\",\"content\":\"").append(jsonEscape(m.content)).append("\"}");
            }
        }
        messages.append(",{\"role\":\"user\",\"content\":\"")
                .append(jsonEscape(userPrompt)).append("\"}");

        String body = "{"
                + "\"model\":\"" + jsonEscape(model) + "\","
                + "\"messages\":[" + messages + "],"
                + "\"thinking\":{\"type\":\"disabled\"},"
                + "\"response_format\":{\"type\":\"json_object\"},"
                + "\"max_tokens\":" + maxTokens + ","
                + "\"temperature\":" + temperature + ","
                + "\"stream\":false"
                + "}";

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "KawaiiMobChat/1.0.3")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            return new Reply(null, null, "bad endpoint: " + ex.getMessage());
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return new Reply(null, null, "request failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        int status = resp.statusCode();
        String responseBody = resp.body();
        if (status / 100 != 2) {
            return new Reply(null, null, "HTTP " + status + ": " + truncate(responseBody, 200));
        }

        // Extract content from outer envelope
        String content = extractStringField(responseBody, "content");
        if (content == null) {
            return new Reply(null, null, "no content field in response");
        }

        // Some models wrap JSON in ```json ... ``` despite response_format. Strip it.
        content = stripCodeFences(content).trim();

        // Inner content is a JSON object — extract mood + reply (lenient: returns
        // partial value if response was truncated mid-string)
        String mood = extractStringField(content, "mood");
        String reply = extractStringField(content, "reply");
        if (reply == null) reply = extractStringField(content, "message");
        if (reply == null) reply = extractStringField(content, "text");

        if (reply == null || reply.trim().isEmpty()) {
            // Last-ditch effort: maybe the model just returned a plain string with no JSON
            // structure. If content doesn't look like JSON, treat the whole thing as the reply.
            String trimmed = content.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                if (trimmed.length() > 200) trimmed = trimmed.substring(0, 200) + "...";
                return new Reply(mood != null ? mood : "neutral", trimmed, null);
            }
            // It DOES look like JSON but reply field is missing — give up cleanly rather
            // than dumping raw JSON into the chat.
            return new Reply(null, null, "couldn't extract reply from: " + truncate(content, 120));
        }

        if (mood == null) mood = "neutral";
        mood = mood.toLowerCase().trim();
        if (!isKnownMood(mood)) {
            // Map a few common synonyms before giving up.
            if (mood.startsWith("happ") || mood.equals("joyful"))           mood = "friendly";
            else if (mood.startsWith("hostil") || mood.startsWith("furi"))  mood = "angry";
            else if (mood.startsWith("afraid") || mood.equals("anxious"))   mood = "scared";
            else if (mood.startsWith("melanchol") || mood.equals("upset"))  mood = "sad";
            else if (mood.startsWith("intrigu") || mood.equals("curious"))  mood = "curious";
            else mood = "neutral";
        }
        return new Reply(mood, reply, null);
    }

    private static boolean isKnownMood(String m) {
        return "angry".equals(m) || "scared".equals(m) || "sad".equals(m)
            || "neutral".equals(m) || "curious".equals(m)
            || "friendly".equals(m) || "excited".equals(m);
    }

    /** Remove leading/trailing ```json ... ``` style fences. */
    private static String stripCodeFences(String s) {
        if (s == null) return null;
        String t = s.trim();
        // ```json\n...\n```  or  ```\n...\n```
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl != -1) t = t.substring(firstNl + 1);
            else t = t.substring(3);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    // ============== tiny JSON helpers ==============

    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Extract the first quoted string value associated with the given key.
     * Walks the JSON character-by-character — handles escaped quotes correctly,
     * so works on the outer envelope AND on nested-as-string JSON content.
     */
    public static String extractStringField(String json, String key) {
        if (json == null || key == null) return null;
        String search = "\"" + key + "\"";
        int start = 0;
        while (true) {
            int idx = json.indexOf(search, start);
            if (idx == -1) return null;
            // find the colon following the key
            int after = idx + search.length();
            int colon = -1;
            for (int j = after; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == ':') { colon = j; break; }
                if (!Character.isWhitespace(c)) break;
            }
            if (colon == -1) { start = after; continue; }
            // skip whitespace, expect a quote
            int q = -1;
            for (int j = colon + 1; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '"') { q = j; break; }
                if (!Character.isWhitespace(c)) break;
            }
            if (q == -1) { start = after; continue; }
            // walk the string with escape handling
            StringBuilder sb = new StringBuilder();
            int i = q + 1;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(i + 1);
                    switch (n) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            if (i + 5 < json.length()) {
                                try {
                                    int cp = Integer.parseInt(json.substring(i + 2, i + 6), 16);
                                    sb.append((char) cp);
                                    i += 6; continue;
                                } catch (NumberFormatException ex) {
                                    sb.append(n);
                                }
                            } else sb.append(n);
                            break;
                        default: sb.append(n);
                    }
                    i += 2;
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                    i++;
                }
            }
            // EOF before closing quote — return whatever we have rather than null
            // (handles truncated responses gracefully)
            String partial = sb.toString().trim();
            return partial.isEmpty() ? null : partial;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
