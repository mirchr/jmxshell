package com.jmxshell.target;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;

/**
 * A deliberately-vulnerable JMX target for testing jmxshell.
 *
 * Run with the JMX system properties already set so the management agent
 * binds an unauthenticated, unencrypted JMX/RMI endpoint on the requested
 * port. The Gradle :runTarget task wires those properties up; if you launch
 * this class directly, set them yourself, e.g.:
 *
 *   java \
 *     -Dcom.sun.management.jmxremote \
 *     -Dcom.sun.management.jmxremote.port=1099 \
 *     -Dcom.sun.management.jmxremote.rmi.port=1099 \
 *     -Dcom.sun.management.jmxremote.authenticate=false \
 *     -Dcom.sun.management.jmxremote.ssl=false \
 *     -Dcom.sun.management.jmxremote.local.only=false \
 *     -Djava.rmi.server.hostname=127.0.0.1 \
 *     -jar build/target/jmx-target.jar
 *
 * DO NOT run this on a host reachable from an untrusted network.
 */
public class JmxTarget {

    public static void main(String[] args) throws Exception {
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        String jmxPort = System.getProperty("com.sun.management.jmxremote.port", "(unset)");
        String authProp = System.getProperty("com.sun.management.jmxremote.authenticate", "(unset)");
        String sslProp = System.getProperty("com.sun.management.jmxremote.ssl", "(unset)");

        System.out.println("================================================================");
        System.out.println(" Vulnerable JMX target (jmxshell test fixture)");
        System.out.println(" Runtime:        " + runtime);
        System.out.println(" Java version:   " + System.getProperty("java.version"));
        System.out.println(" JMX port:       " + jmxPort);
        System.out.println(" Authenticate:   " + authProp);
        System.out.println(" SSL:            " + sslProp);
        System.out.println(" Listening for connections. Press Ctrl-C to terminate.");
        System.out.println("================================================================");

        new CountDownLatch(1).await();
    }
}
