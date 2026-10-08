package art.rehra.mineqtt.mqtt.core;

import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.MqttClientSslConfigBuilder;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

/** Builds the HiveMQ TLS configuration: JVM truststore, a PEM CA file, or (loudly logged) no verification at all. */
final class TlsSupport {
    private TlsSupport() {
    }

    static MqttClientSslConfig sslConfig(BrokerConfig config) throws IOException, GeneralSecurityException {
        MqttClientSslConfigBuilder builder = MqttClientSslConfig.builder();
        if (config.insecureSkipVerify()) {
            // A set hostname verifier also stops HiveMQ from enabling HTTPS endpoint identification.
            builder = builder.trustManagerFactory(new InsecureTrustManagerFactory()).hostnameVerifier((host, session) -> true);
        } else if (config.caFile() != null) {
            builder = builder.trustManagerFactory(trustManagerFactory(config.caFile()));
        }
        return builder.build();
    }

    /** Trusts exactly the certificates in a PEM (or DER) file. */
    static TrustManagerFactory trustManagerFactory(Path caFile) throws IOException, GeneralSecurityException {
        Collection<? extends Certificate> certificates;
        try (InputStream in = Files.newInputStream(caFile)) {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
        }
        if (certificates.isEmpty()) throw new GeneralSecurityException("No certificate found in " + caFile);
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int index = 0;
        for (Certificate certificate : certificates) {
            trustStore.setCertificateEntry("mineqtt-ca-" + index++, certificate);
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory;
    }

    /**
     * Accepts every certificate. It extends {@link X509ExtendedTrustManager} because the JDK wraps plain
     * {@code X509TrustManager}s and would still check the host name.
     */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static final class InsecureTrustManagerFactory extends TrustManagerFactory {
        private static final Provider PROVIDER = new Provider("MineQTT-InsecureTrust", "1", "Trusts every certificate") {
        };

        InsecureTrustManagerFactory() {
            super(new TrustManagerFactorySpi() {
                @Override
                protected void engineInit(KeyStore keyStore) {
                }

                @Override
                protected void engineInit(ManagerFactoryParameters parameters) {
                }

                @Override
                protected TrustManager[] engineGetTrustManagers() {
                    return new TrustManager[]{new TrustAll()};
                }
            }, PROVIDER, "InsecureTrustAll");
        }
    }
}
