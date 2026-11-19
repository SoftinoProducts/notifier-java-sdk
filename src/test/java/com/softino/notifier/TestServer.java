package com.softino.notifier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded HTTP server for the async tests. Records requests and delegates to a configurable
 * handler. Not a test class itself (no {@code Test} suffix), so surefire ignores it.
 */
final class TestServer implements AutoCloseable {

    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    static final class Recorded {
        final String method;
        final String path;
        final Map<String, List<String>> headers;
        final String body;

        Recorded(String method, String path, Map<String, List<String>> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().isEmpty() ? null : e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Recorded> requests = new CopyOnWriteArrayList<Recorded>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile Handler handler;

    TestServer(Handler handler) throws IOException {
        this.handler = handler;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "test-hub-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            Recorded recorded = new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    new LinkedHashMap<String, List<String>>(exchange.getRequestHeaders()),
                    readBody(exchange.getRequestBody()));
            requests.add(recorded);
            requestCount.incrementAndGet();
            try {
                TestServer.this.handler.handle(exchange);
            } catch (Exception e) {
                // A client-side timeout can close the socket first; tests assert on request
                // counts, so a broken pipe is not a test failure.
                try {
                    exchange.close();
                } catch (RuntimeException ignored) {
                    // ignored
                }
            }
        });
        server.start();
    }

    void handler(Handler handler) {
        this.handler = handler;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Recorded> requests() {
        return Collections.unmodifiableList(new ArrayList<Recorded>(requests));
    }

    int requestCount() {
        return requestCount.get();
    }

    Recorded lastRequest() {
        List<Recorded> copy = requests();
        return copy.isEmpty() ? null : copy.get(copy.size() - 1);
    }

    static void respond(HttpExchange exchange, int code, String jsonBody) throws IOException {
        byte[] bytes = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void respondJson(HttpExchange exchange, int code, String json) throws IOException {
        respond(exchange, code, json);
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
