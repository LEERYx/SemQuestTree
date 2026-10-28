package org.example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** 极简 HTTP POST（零第三方依赖，Java 8 HttpURLConnection）。 */
final class Http {

    private Http() { }

    static final Charset UTF8 = Charset.forName("UTF-8");

    /** POST JSON 并返回响应体；非 200 抛 IOException（带状态码与响应体，截断）。 */
    static String post(String url, String apiKey, String jsonBody, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        OutputStream out = null;
        try {
            out = conn.getOutputStream();
            out.write(jsonBody.getBytes(UTF8));
        } finally {
            if (out != null) out.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = in == null ? "" : readAll(in);
        if (code != 200) {
            throw new IOException("HTTP " + code + ": " + abbrev(body));
        }
        return body;
    }

    /** GET 并返回响应体（模型列表等元数据）；非 200 抛 IOException。 */
    static String get(String url, String apiKey, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        if (apiKey != null) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = in == null ? "" : readAll(in);
        if (code != 200) {
            throw new IOException("HTTP " + code + ": " + abbrev(body));
        }
        return body;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        try {
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        } finally {
            in.close();
        }
        return new String(buf.toByteArray(), UTF8);
    }

    private static String abbrev(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
