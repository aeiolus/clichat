package com.clichat;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class SignalTrustStore {
    private static final String KEYSTORE_PASSWORD = "whisper";
    private KeyStore keyStore;
    private X509Certificate certificate;

    public SignalTrustStore() {
        try {
            // Load the whisper.store file from resources
            InputStream inputStream = getClass().getResourceAsStream("/whisper.store");
            if (inputStream == null) {
                throw new RuntimeException("Could not load whisper.store from resources");
            }

            // Read the entire file into a byte array
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[4096];
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            byte[] trustStoreBytes = buffer.toByteArray();

            // Try different keystore types
            String[] types = {"JKS", "PKCS12", "BKS"};
            boolean loaded = false;
            
            for (String type : types) {
                try (ByteArrayInputStream trustStoreStream = new ByteArrayInputStream(trustStoreBytes)) {
                    keyStore = KeyStore.getInstance(type);
                    keyStore.load(trustStoreStream, KEYSTORE_PASSWORD.toCharArray());
                    loaded = true;
                    break;
                } catch (Exception e) {
                    // Continue to next type
                }
            }

            if (!loaded) {
                // If all keystore types fail, try loading it as a raw certificate
                try (ByteArrayInputStream trustStoreStream = new ByteArrayInputStream(trustStoreBytes)) {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    certificate = (X509Certificate) cf.generateCertificate(trustStoreStream);
                    
                    // Create a new keystore and add the certificate
                    keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                    keyStore.load(null, KEYSTORE_PASSWORD.toCharArray());
                    keyStore.setCertificateEntry("signal", certificate);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load certificate from whisper.store", e);
                }
            } else {
                // Get the certificate from the successfully loaded keystore
                String alias = keyStore.aliases().nextElement();
                certificate = (X509Certificate) keyStore.getCertificate(alias);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SignalTrustStore", e);
        }
    }

    public X509Certificate getCertificate() throws CertificateException {
        return certificate;
    }

    public String getKeyStorePassword() {
        return KEYSTORE_PASSWORD;
    }

    public TrustManager[] getTrustManagers() {
        try {
            javax.net.ssl.TrustManagerFactory trustManagerFactory = 
                javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            return trustManagerFactory.getTrustManagers();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust managers", e);
        }
    }
}