package com.jmxshell.target;

import java.lang.management.ManagementFactory;
import java.rmi.registry.LocateRegistry;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import javax.management.MBeanServer;
import javax.management.remote.JMXAuthenticator;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXPrincipal;
import javax.management.remote.JMXServiceURL;
import javax.security.auth.Subject;

/**
 * A deliberately-vulnerable JMX target for testing jmxshell.
 *
 * Starts a JMX/RMI connector programmatically so it can be launched with
 * just `java -jar jmx-target.jar` (defaults: 127.0.0.1:1099, no auth, no SSL).
 *
 * DO NOT run this on a host reachable from an untrusted network.
 */
public class JmxTarget {

    public static void main(String[] args) throws Exception {
        Options opts = Options.parse(args);

        // The RMI stub handed to remote clients embeds this hostname; clients
        // dial back to it for the actual JMX-over-RMI traffic. Must be set
        // before the registry/connector are created.
        System.setProperty("java.rmi.server.hostname", opts.lhost);

        LocateRegistry.createRegistry(opts.jmxPort);

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        JMXServiceURL url = new JMXServiceURL(String.format(
                "service:jmx:rmi://%s:%d/jndi/rmi://%s:%d/jmxrmi",
                opts.lhost, opts.jmxPort, opts.lhost, opts.jmxPort));

        Map<String, Object> env = new HashMap<>();
        boolean authEnabled = opts.username != null;
        if (authEnabled) {
            env.put(JMXConnectorServer.AUTHENTICATOR,
                    new SingleUserAuthenticator(opts.username, opts.password));
        }

        JMXConnectorServer server = JMXConnectorServerFactory.newJMXConnectorServer(url, env, mbs);
        server.start();

        System.out.println("================================================================");
        System.out.println(" Vulnerable JMX target (jmxshell test fixture)");
        System.out.println(" Java version:   " + System.getProperty("java.version"));
        System.out.println(" Bind host:      " + opts.lhost);
        System.out.println(" JMX port:       " + opts.jmxPort);
        System.out.println(" Auth:           " + (authEnabled ? "user=" + opts.username : "disabled"));
        System.out.println(" Service URL:    " + server.getAddress());
        System.out.println(" Listening for connections. Press Ctrl-C to terminate.");
        System.out.println("================================================================");

        new CountDownLatch(1).await();
    }

    private static final class Options {
        String lhost = "127.0.0.1";
        int jmxPort = 1099;
        String username;
        String password;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--lhost":
                        o.lhost = requireValue(args, ++i, a);
                        break;
                    case "--jmxport":
                        o.jmxPort = parsePort(requireValue(args, ++i, a));
                        break;
                    case "--username":
                        o.username = requireValue(args, ++i, a);
                        break;
                    case "--password":
                        o.password = requireValue(args, ++i, a);
                        break;
                    case "-h":
                    case "--help":
                        printUsage(System.out);
                        System.exit(0);
                        break;
                    default:
                        System.err.println("Unknown option: " + a);
                        printUsage(System.err);
                        System.exit(2);
                }
            }
            if ((o.username == null) != (o.password == null)) {
                System.err.println("--username and --password must be supplied together");
                System.exit(2);
            }
            return o;
        }

        private static String requireValue(String[] args, int i, String flag) {
            if (i >= args.length) {
                System.err.println("Missing value for " + flag);
                System.exit(2);
            }
            return args[i];
        }

        private static int parsePort(String s) {
            try {
                int p = Integer.parseInt(s);
                if (p < 1 || p > 65535) {
                    throw new NumberFormatException(s);
                }
                return p;
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + s);
                System.exit(2);
                return -1;
            }
        }

        private static void printUsage(java.io.PrintStream out) {
            out.println("Usage: jmx-target [options]");
            out.println("  --lhost <addr>      RMI hostname advertised to clients (default 127.0.0.1)");
            out.println("  --jmxport <port>    JMX/RMI port (default 1099)");
            out.println("  --username <user>   require this user (must be paired with --password)");
            out.println("  --password <pass>   password for --username");
        }
    }

    private static final class SingleUserAuthenticator implements JMXAuthenticator {
        private final String user;
        private final String pass;

        SingleUserAuthenticator(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        @Override
        public Subject authenticate(Object credentials) {
            if (!(credentials instanceof String[])) {
                throw new SecurityException("Credentials must be String[]{user, password}");
            }
            String[] cs = (String[]) credentials;
            if (cs.length != 2 || !user.equals(cs[0]) || !pass.equals(cs[1])) {
                throw new SecurityException("Invalid credentials");
            }
            Set<Principal> principals = Collections.<Principal>singleton(new JMXPrincipal(user));
            return new Subject(true, principals, Collections.emptySet(), Collections.emptySet());
        }
    }
}
