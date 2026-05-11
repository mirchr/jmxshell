package com.jmxshell;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the built-in HTTP server only ever returns the two files jmxshell
 * is meant to serve (woot.html and compromise.jar) and rejects everything
 * else — including arbitrary files in the same directory, path-traversal
 * attempts, and symlinks that re-use a whitelisted name to point at files
 * outside the served directory.
 */
class WebHandlerTest {

    private static final byte[] WOOT_BODY = "<html>mlet</html>".getBytes();
    private static final byte[] JAR_BODY = new byte[]{0x50, 0x4b, 0x03, 0x04, 1, 2, 3};

    @TempDir Path webDir;
    HttpServer server;
    int port;

    @BeforeEach
    void setUp() throws IOException {
        Files.write(webDir.resolve("woot.html"), WOOT_BODY);
        Files.write(webDir.resolve("compromise.jar"), JAR_BODY);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new JmxShell.WebHandler(webDir));
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "webhandler-test");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // --- positive: the two allowed files come back ----------------------

    @Test
    void servesWootHtml() throws Exception {
        Response r = get("/woot.html");
        assertEquals(200, r.code);
        assertArrayEquals(WOOT_BODY, r.body);
    }

    @Test
    void servesCompromiseJar() throws Exception {
        Response r = get("/compromise.jar");
        assertEquals(200, r.code);
        assertArrayEquals(JAR_BODY, r.body);
    }

    // --- negative: anything else is 404 ---------------------------------

    @Test
    void rejectsArbitraryFileInWebDir() throws Exception {
        Files.write(webDir.resolve("SECRETS.txt"), "operator-only".getBytes());
        assertEquals(404, get("/SECRETS.txt").code);
    }

    @Test
    void rejectsRoot() throws Exception {
        assertEquals(404, get("/").code);
    }

    @Test
    void rejectsCaseVariant() throws Exception {
        // Whitelist is case-sensitive — Woot.html != woot.html
        assertEquals(404, get("/Woot.html").code);
    }

    @Test
    void rejectsCloseButNotEqual() throws Exception {
        assertEquals(404, get("/woot.htmla").code);
        assertEquals(404, get("/compromise.jar.bak").code);
        assertEquals(404, get("/woot").code);
    }

    @Test
    void rejectsArbitraryHtmlInWebDir() throws Exception {
        Files.write(webDir.resolve("other.html"), "<html>other</html>".getBytes());
        assertEquals(404, get("/other.html").code);
    }

    @Test
    void rejectsArbitraryJarInWebDir() throws Exception {
        Files.write(webDir.resolve("other.jar"), new byte[]{1, 2, 3});
        assertEquals(404, get("/other.jar").code);
    }

    @Test
    void rejectsSubdirectory() throws Exception {
        Path sub = Files.createDirectory(webDir.resolve("nested"));
        Files.write(sub.resolve("woot.html"), "<html>nested</html>".getBytes());
        assertEquals(404, get("/nested/woot.html").code);
    }

    // --- symlink defense -------------------------------------------------

    @Test
    void rejectsSymlinkUsingWhitelistedName() throws Exception {
        // Operator's web dir is compromised by a symlink named woot.html
        // that points at a sensitive file outside the served directory.
        Path outside = Files.createTempFile("outside-secret-", ".txt");
        Files.write(outside, "very-secret".getBytes());
        try {
            Files.delete(webDir.resolve("woot.html"));
            try {
                Files.createSymbolicLink(webDir.resolve("woot.html"), outside);
            } catch (UnsupportedOperationException | IOException e) {
                // Filesystems without symlink support — skip rather than fail.
                org.junit.jupiter.api.Assumptions.abort(
                        "symlinks unsupported on this filesystem: " + e.getMessage());
                return;
            }
            Response r = get("/woot.html");
            assertEquals(404, r.code,
                    "symlink named woot.html must not be served (would leak " + outside + ")");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsSymlinkUsingArbitraryName() throws Exception {
        Path outside = Files.createTempFile("outside-secret-", ".txt");
        Files.write(outside, "very-secret".getBytes());
        try {
            try {
                Files.createSymbolicLink(webDir.resolve("escape"), outside);
            } catch (UnsupportedOperationException | IOException e) {
                org.junit.jupiter.api.Assumptions.abort(
                        "symlinks unsupported on this filesystem: " + e.getMessage());
                return;
            }
            assertEquals(404, get("/escape").code);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // --- helpers --------------------------------------------------------

    private Response get(String path) throws Exception {
        URL u = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        byte[] body = new byte[0];
        InputStream in = (code < 400) ? c.getInputStream() : c.getErrorStream();
        if (in != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            body = buf.toByteArray();
            in.close();
        }
        c.disconnect();
        return new Response(code, body);
    }

    private static final class Response {
        final int code;
        final byte[] body;
        Response(int code, byte[] body) { this.code = code; this.body = body; }
    }
}
