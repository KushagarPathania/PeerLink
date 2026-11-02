package com.peerlink.agent.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable peer agent settings. Each value is resolved from, in order of precedence:
 * a {@code --name=value} command-line option, an environment variable, then a default.
 *
 * <p>The agent credential is deliberately <em>not</em> accepted as a command-line option, because
 * command lines are visible to other users of the machine. It comes from a file
 * ({@code --credential-file} / {@code PEERLINK_CREDENTIAL_FILE}) or {@code PEERLINK_AGENT_CREDENTIAL}.
 *
 * @param peerId            registered peer to keep online, or {@code null} to only check the server
 * @param credential        that peer's agent credential, or {@code null}
 * @param heartbeatInterval time between heartbeats
 * @param listenHost        address the peer listener binds to (loopback unless configured otherwise)
 * @param listenEnabled     whether a configured peer also accepts direct connections
 * @param maxConnections    simultaneous incoming peer connections
 * @param connectTo         when set, run a single connection test to this peer and exit
 */
public record AgentConfig(URI serverUrl, int listenPort, Path downloadDirectory, UUID peerId,
                          AgentCredential credential, Duration heartbeatInterval, InetAddress listenHost,
                          boolean listenEnabled, int maxConnections, UUID connectTo) {

    public static final String DEFAULT_SERVER_URL = "http://localhost:8080";
    public static final int DEFAULT_LISTEN_PORT = 9090;
    public static final int DEFAULT_HEARTBEAT_SECONDS = 15;
    static final int MAX_HEARTBEAT_SECONDS = 3600;
    public static final int DEFAULT_MAX_CONNECTIONS = 8;
    static final int MAX_CONNECTIONS_LIMIT = 64;

    static final String OPT_SERVER_URL = "server-url";
    static final String OPT_PORT = "port";
    static final String OPT_DOWNLOAD_DIR = "download-dir";
    static final String OPT_PEER_ID = "peer-id";
    static final String OPT_CREDENTIAL_FILE = "credential-file";
    static final String OPT_HEARTBEAT_INTERVAL = "heartbeat-interval";
    static final String OPT_LISTEN_HOST = "listen-host";
    static final String OPT_LISTEN = "listen";
    static final String OPT_MAX_CONNECTIONS = "max-connections";
    static final String OPT_CONNECT_TO = "connect-to";
    static final String ENV_SERVER_URL = "PEERLINK_SERVER_URL";
    static final String ENV_PORT = "PEERLINK_AGENT_PORT";
    static final String ENV_DOWNLOAD_DIR = "PEERLINK_DOWNLOAD_DIR";
    static final String ENV_PEER_ID = "PEERLINK_PEER_ID";
    static final String ENV_CREDENTIAL = "PEERLINK_AGENT_CREDENTIAL";
    static final String ENV_CREDENTIAL_FILE = "PEERLINK_CREDENTIAL_FILE";
    static final String ENV_HEARTBEAT_INTERVAL = "PEERLINK_HEARTBEAT_INTERVAL";
    static final String ENV_LISTEN_HOST = "PEERLINK_LISTEN_HOST";
    static final String ENV_LISTEN = "PEERLINK_LISTEN_ENABLED";
    static final String ENV_MAX_CONNECTIONS = "PEERLINK_MAX_CONNECTIONS";

    private static final Set<String> KNOWN_OPTIONS = Set.of(OPT_SERVER_URL, OPT_PORT, OPT_DOWNLOAD_DIR, OPT_PEER_ID,
            OPT_CREDENTIAL_FILE, OPT_HEARTBEAT_INTERVAL, OPT_LISTEN_HOST, OPT_LISTEN, OPT_MAX_CONNECTIONS,
            OPT_CONNECT_TO);

    public AgentConfig {
        if (listenPort < 1 || listenPort > 65535) {
            throw new IllegalArgumentException("Listen port must be between 1 and 65535, got " + listenPort);
        }
        if ((peerId == null) != (credential == null)) {
            throw new IllegalArgumentException("A peer id and an agent credential must be configured together "
                    + "(" + ENV_PEER_ID + " and " + ENV_CREDENTIAL + " or a credential file)");
        }
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        }
        if (listenHost == null) {
            throw new IllegalArgumentException("Listen host is required");
        }
        if (maxConnections < 1 || maxConnections > MAX_CONNECTIONS_LIMIT) {
            throw new IllegalArgumentException("Max connections must be between 1 and " + MAX_CONNECTIONS_LIMIT
                    + ", got " + maxConnections);
        }
        if (connectTo != null && peerId == null) {
            throw new IllegalArgumentException("--connect-to needs this device's peer id and agent credential");
        }
        if (connectTo != null && connectTo.equals(peerId)) {
            throw new IllegalArgumentException("--connect-to must name another peer");
        }
    }

    /** True when a peer is configured, i.e. the agent should keep that peer online. */
    public boolean presenceConfigured() {
        return peerId != null;
    }

    public static AgentConfig resolve(String[] args, Map<String, String> env) {
        Map<String, String> options = parseOptions(args);

        String serverUrl = lookup(options, env, OPT_SERVER_URL, ENV_SERVER_URL, DEFAULT_SERVER_URL);
        String port = lookup(options, env, OPT_PORT, ENV_PORT, String.valueOf(DEFAULT_LISTEN_PORT));
        String downloadDir = lookup(options, env, OPT_DOWNLOAD_DIR, ENV_DOWNLOAD_DIR,
                Path.of(System.getProperty("user.home"), "PeerLink", "downloads").toString());
        String peerId = lookup(options, env, OPT_PEER_ID, ENV_PEER_ID, null);
        String heartbeat = lookup(options, env, OPT_HEARTBEAT_INTERVAL, ENV_HEARTBEAT_INTERVAL,
                String.valueOf(DEFAULT_HEARTBEAT_SECONDS));

        String listenHost = lookup(options, env, OPT_LISTEN_HOST, ENV_LISTEN_HOST, "127.0.0.1");
        String listen = lookup(options, env, OPT_LISTEN, ENV_LISTEN, "true");
        String maxConnections = lookup(options, env, OPT_MAX_CONNECTIONS, ENV_MAX_CONNECTIONS,
                String.valueOf(DEFAULT_MAX_CONNECTIONS));

        return new AgentConfig(parseServerUrl(serverUrl), parsePort(port),
                Path.of(downloadDir).toAbsolutePath().normalize(), parsePeerId(peerId),
                resolveCredential(options, env), parseHeartbeatInterval(heartbeat), parseListenHost(listenHost),
                parseBoolean(OPT_LISTEN, listen), parseInt(OPT_MAX_CONNECTIONS, maxConnections),
                parsePeerId(options.get(OPT_CONNECT_TO)));
    }

    public static String usage() {
        return """
                Usage: java -jar peer-agent.jar [options]

                Options (environment variable in brackets):
                  --server-url=<url>          Coordination server base URL [%s] (default %s)
                  --port=<port>               TCP port for incoming peer connections [%s] (default %d)
                  --download-dir=<path>       Directory for received files [%s] (default ~/PeerLink/downloads)
                  --peer-id=<uuid>            Registered peer to keep online [%s]
                  --credential-file=<path>    File containing that peer's agent credential [%s]
                  --heartbeat-interval=<sec>  Seconds between heartbeats [%s] (default %d)
                  --listen-host=<ip>          Address to accept peer connections on [%s] (default 127.0.0.1;
                                              use this device's LAN IP or 0.0.0.0 for other devices)
                  --listen=<true|false>       Accept direct peer connections [%s] (default true)
                  --max-connections=<n>       Simultaneous incoming connections [%s] (default %d)
                  --connect-to=<peer-uuid>    Test a direct connection to that peer, then exit
                  --help                      Show this message

                The agent credential can also be given in %s. It is never accepted on the
                command line. Without a peer id the agent only checks the server and exits.
                """.formatted(ENV_SERVER_URL, DEFAULT_SERVER_URL, ENV_PORT, DEFAULT_LISTEN_PORT, ENV_DOWNLOAD_DIR,
                ENV_PEER_ID, ENV_CREDENTIAL_FILE, ENV_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_SECONDS, ENV_LISTEN_HOST,
                ENV_LISTEN, ENV_MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS, ENV_CREDENTIAL);
    }

    @Override
    public String toString() {
        return "AgentConfig[serverUrl=" + serverUrl + ", listenPort=" + listenPort + ", downloadDirectory="
                + downloadDirectory + ", peerId=" + peerId + ", credential=" + (credential == null ? "none" : "****")
                + ", heartbeatInterval=" + heartbeatInterval + ", listenHost=" + listenHost.getHostAddress()
                + ", listenEnabled=" + listenEnabled + ", maxConnections=" + maxConnections + ", connectTo=" + connectTo
                + "]";
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            int separator = arg.indexOf('=');
            if (!arg.startsWith("--") || separator < 0) {
                throw new IllegalArgumentException("Expected an option of the form --name=value, got '" + arg + "'");
            }
            String name = arg.substring(2, separator);
            if (!KNOWN_OPTIONS.contains(name)) {
                throw new IllegalArgumentException("Unknown option '--" + name + "'");
            }
            options.put(name, arg.substring(separator + 1));
        }
        return options;
    }

    private static String lookup(Map<String, String> options, Map<String, String> env,
                                 String option, String envVar, String defaultValue) {
        String value = options.get(option);
        if (value == null || value.isBlank()) {
            value = env.get(envVar);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static AgentCredential resolveCredential(Map<String, String> options, Map<String, String> env) {
        String file = lookup(options, env, OPT_CREDENTIAL_FILE, ENV_CREDENTIAL_FILE, null);
        if (file != null) {
            try {
                return AgentCredential.of(Files.readString(Path.of(file), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read credential file '" + file + "': " + e.getMessage(), e);
            }
        }
        String inline = env.get(ENV_CREDENTIAL);
        return inline == null || inline.isBlank() ? null : AgentCredential.of(inline);
    }

    private static URI parseServerUrl(String value) {
        // Strip trailing slashes so endpoint paths can be appended without producing "//".
        String normalized = value.replaceAll("/+$", "");
        try {
            URI uri = new URI(normalized);
            boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (!httpScheme || uri.getHost() == null) {
                throw new IllegalArgumentException("Server URL must be an http(s) URL with a host, got '" + value + "'");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid server URL '" + value + "'", e);
        }
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number, got '" + value + "'", e);
        }
    }

    /** An IP literal or "localhost"; host names are refused so no DNS lookup decides where we listen. */
    private static InetAddress parseListenHost(String value) {
        if (value.equals("localhost")) {
            return InetAddress.getLoopbackAddress();
        }
        if (!value.matches("[0-9a-fA-F.:]{2,45}")) {
            throw new IllegalArgumentException("Listen host must be an IP address (or localhost), got '" + value + "'");
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid listen host '" + value + "'", e);
        }
    }

    private static boolean parseBoolean(String option, String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException("--" + option + " must be true or false, got '" + value + "'");
        };
    }

    private static int parseInt(String option, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be a number, got '" + value + "'", e);
        }
    }

    private static UUID parsePeerId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Peer id must be a UUID, got '" + value + "'", e);
        }
    }

    private static Duration parseHeartbeatInterval(String value) {
        try {
            int seconds = Integer.parseInt(value);
            if (seconds < 1 || seconds > MAX_HEARTBEAT_SECONDS) {
                throw new IllegalArgumentException("Heartbeat interval must be between 1 and "
                        + MAX_HEARTBEAT_SECONDS + " seconds, got " + seconds);
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Heartbeat interval must be a number of seconds, got '" + value + "'", e);
        }
    }
}
