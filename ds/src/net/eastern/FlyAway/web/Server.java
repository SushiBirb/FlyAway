package net.eastern.FlyAway.web;

import com.sun.net.httpserver.*;
import net.eastern.FlyAway.util.Utils;

import javax.net.ssl.*;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedded HTTPS Server wrapper.
 *
 * Configures TLS 1.3 socket listeners using PKCS12 key stores, manages request thread pools,
 * and attaches HTTP exchange context handlers.
 */
public class Server {
    private HttpsServer server;
    private SSLContext sslContext;
    private ExecutorService threadPool;

    /**
     * Creates Server.
     * @param portnum The TCP port to listen on.
     * @throws Exception If initialization fails.
     */
    public Server(int portnum) throws Exception {
        try {
            startHttpsServer(portnum);
            Utils.Infoprintln("Done! Server up on port " + portnum);
        } catch (Exception e) {
            Utils.Errprintln("Server start failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Configures the server for TLS/HTTPS with concurrent request worker threads.
     *
     * @param portnum The TCP port to listen on.
     * @throws Exception If TLS setup or socket binding encounters an error.
     */
    public void startHttpsServer(int portnum) throws Exception {
        String bindHost = System.getenv("FLYAWAY_BIND_HOST");
        if (bindHost == null || bindHost.trim().isEmpty()) {
            bindHost = "0.0.0.0";
        }

        InetSocketAddress addr = new InetSocketAddress(bindHost, portnum);
        Utils.Infoprintln("Starting up HTTPS Server on " + bindHost + ":" + portnum);
        server = HttpsServer.create(addr, 0);

        sslContext = SSLContext.getInstance("TLS");
        Utils.Infoprintln("Importing TLS/SSL Keyring...");

        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        try (InputStream is = classloader.getResourceAsStream("crt/cert.p12")) {
            if (is == null) {
                throw new IllegalStateException("Embedded certificate resource 'crt/cert.p12' not found on classpath!");
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(is, "".toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, "".toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);

            Utils.Infoprintln("Configuring HTTPS SSL Context...");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        }

        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLContext c = getSSLContext();
                    SSLEngine engine = c.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());

                    SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                    params.setSSLParameters(defaultSSLParameters);
                } catch (Exception ex) {
                    Utils.Errprintln("SSL Configuration error: " + ex.getMessage());
                }
            }
        });

        // Use fixed thread pool for concurrent request handling
        int poolSize = 16;
        try {
            String poolEnv = System.getenv("FLYAWAY_WORKER_THREADS");
            if (poolEnv != null && !poolEnv.isEmpty()) {
                poolSize = Integer.parseInt(poolEnv);
            }
        } catch (NumberFormatException ignored) {}

        threadPool = Executors.newFixedThreadPool(poolSize);
        server.setExecutor(threadPool);

        // Register clean shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Utils.Infoprintln("Shutting down HTTPS server and worker pool...");
            if (server != null) {
                server.stop(1);
            }
            if (threadPool != null) {
                threadPool.shutdown();
            }
        }));
    }

    /**
     * Creates a Context Path to a handler for the Server.
     */
    public HttpContext createContext(String path, HttpHandler handler) {
        return server.createContext(path, handler);
    }

    /**
     * Starts the Server.
     */
    public void start() {
        server.start();
    }

    /**
     * Stops the Server.
     */
    public void stop(int delay) {
        if (server != null) {
            server.stop(delay);
        }
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }
}
