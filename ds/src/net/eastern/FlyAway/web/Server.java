package net.eastern.FlyAway.web;

import com.sun.net.httpserver.*;
import net.eastern.FlyAway.util.Utils;

import javax.net.ssl.*;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;

public class Server {
    private HttpsServer server;
    private SSLContext sslContext;

    /**
     * Creates Server.
     * @param portnum
     * @throws Exception
     */
    public Server(int portnum) throws Exception {
        try {
            startHttpsServer(portnum);

            Utils.Infoprintln("Done! Server up on port " + portnum);
        } catch (Exception e) {
            Utils.Errprintln("Server start failed: " + e.getMessage());
        }

    }

    /**
     * Despite not aptly named, this configures the server for HTTPS.
     * @param portnum
     * @throws Exception
     */
    public void startHttpsServer(int portnum) throws Exception {
        InetSocketAddress addr = new InetSocketAddress(portnum);
        Utils.Infoprintln("Starting up https Server on Port " + portnum);
        server = HttpsServer.create(addr, 0);
        sslContext = SSLContext.getInstance("TLS");
        Utils.Infoprintln("Importing TLS/SSL Keyring...");
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        InputStream is = classloader.getResourceAsStream("crt/cert.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(is, "".toCharArray());
        is.close();

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, "".toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ks);

        Utils.Infoprintln("Configuring HTTPS Server...");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            public void configure(HttpsParameters params) {
                try {
                    // Initialise the SSL context
                    SSLContext c = getSSLContext();
                    SSLEngine engine = c.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());

                    // Get the default parameters
                    SSLParameters defaultSSLParameters = c.getSupportedSSLParameters();
                    params.setSSLParameters(defaultSSLParameters);
                } catch (Exception ex) {
                    ex.printStackTrace(System.err);
                }
            }
        });
        server.setExecutor(null);
    }

    /**
     * Creates a Context Path to a handler for the Server.
     * @param path
     * @param handler
     * @return
     */
    public HttpContext createContext(String path, HttpHandler handler) {
        return server.createContext(path, handler);
    }

    /**
     * Actually Starts the Server.
     */
    public void start() {
        server.start();
    }

}
