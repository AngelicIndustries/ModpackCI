package io.github.angelicindustries.modpackci;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * An HTTP CONNECT proxy that drops connections to Microsoft's login hosts and tunnels everything
 * else. Prism only launches offline without asking questions when it can't reach the login server
 * at all; any reply from Microsoft makes it ask to log in again.
 */
public final class LoginBlockingProxy implements AutoCloseable {
    private static final Set<String> BLOCKED = Set.of("login.microsoftonline.com", "login.live.com");
    private final ServerSocket server;

    public LoginBlockingProxy() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::acceptLoop, "login-blocking-proxy");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "proxy-connection");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String[] request = readHeaders(in).split("\r\n", 2)[0].split(" ");
            if (request.length < 2 || !request[0].equals("CONNECT")) {
                out.write("HTTP/1.1 501 Not Implemented\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            int colon = request[1].lastIndexOf(':');
            String host = request[1].substring(0, colon);
            if (BLOCKED.contains(host)) {
                return; // dropping the connection looks like a network error to Prism
            }
            try (Socket upstream = new Socket(host, Integer.parseInt(request[1].substring(colon + 1)))) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread back = new Thread(() -> pipe(upstream, client), "proxy-upstream");
                back.setDaemon(true);
                back.start();
                pipe(client, upstream);
                back.join();
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // A broken connection only affects that one download; Prism reports it if it matters.
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        for (int b; (b = in.read()) != -1; ) {
            buf.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                break;
            }
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static void pipe(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException e) {
            // Either side hung up.
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException e) {
                // Already closed.
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
