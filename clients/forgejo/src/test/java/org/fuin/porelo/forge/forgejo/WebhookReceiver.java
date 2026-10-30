package org.fuin.porelo.forge.forgejo;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Live-only loopback receiver; unit replays never instantiate this class. */
final class WebhookReceiver implements AutoCloseable {
    static final int LIMIT = 2 * 1024 * 1024;
    private final HttpServer server;
    private final byte[] secret;
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private volatile boolean rejected;

    WebhookReceiver(byte[] secret) throws IOException {
        this.secret = secret.clone();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext(
                "/delivery",
                exchange -> {
                    try (exchange) {
                        byte[] body = exchange.getRequestBody().readNBytes(LIMIT + 1);
                        String signature =
                                exchange.getRequestHeaders().getFirst("X-Forgejo-Signature");
                        String event = exchange.getRequestHeaders().getFirst("X-Forgejo-Event");
                        boolean valid = false;
                        if (body.length <= LIMIT
                                && signature != null
                                && signature.matches("[a-fA-F0-9]{64}")
                                && event != null) {
                            valid =
                                    MessageDigest.isEqual(
                                            ForgejoWebhook.hmac(body, this.secret),
                                            HexFormat.of().parseHex(signature));
                        }
                        if (valid) {
                            deliveries.add(
                                    new Delivery(event, new String(body, StandardCharsets.UTF_8)));
                        } else {
                            rejected = true;
                        }
                        exchange.sendResponseHeaders(valid ? 204 : 401, -1);
                    }
                });
        server.start();
    }

    String urlFromContainer() {
        // slirp4netns with allow_host_loopback reaches the host's loopback at 10.0.2.2;
        // host.containers.internal names the host's outer address, where the receiver does not
        // listen.
        return "http://10.0.2.2:" + server.getAddress().getPort() + "/delivery";
    }

    List<Delivery> deliveries() {
        if (rejected) {
            throw new IllegalStateException(
                    "A live delivery failed signature or size verification");
        }
        return List.copyOf(deliveries);
    }

    /** Stops the owned receiver. */
    @Override
    public void close() {
        server.stop(0);
    }

    record Delivery(String event, String body) {}
}
