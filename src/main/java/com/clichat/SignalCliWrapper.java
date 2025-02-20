package com.clichat;

import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.CaptchaRequiredException;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl;
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl;
import org.whispersystems.signalservice.internal.configuration.SignalContactDiscoveryUrl;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.util.KeyHelper;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.signalservice.api.crypto.InvalidCiphertextException;
import com.google.protobuf.InvalidProtocolBufferException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.io.*;
import java.util.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import okhttp3.*;
import java.util.concurrent.TimeUnit;

public class SignalCliWrapper {
    private static final String SIGNAL_URL = "https://chat.signal.org";
    private static final String CDN_URL = "https://cdn.signal.org";
    private static final String CONTACT_DISCOVERY_URL = "https://api.directory.signal.org";
    private static final String USER_AGENT = "signal-cli";

    private final SignalServiceConfiguration serviceConfiguration;
    private final TrustManager[] trustManagers;
    private final OkHttpClient httpClient;
    private final TrustStore trustStore;

    private String phoneNumber;
    private String password;
    private UUID uuid;
    private int registrationId;
    private IdentityKeyPair identityKeyPair;

    public SignalCliWrapper(String phoneNumber) throws IOException {
        // Initialize BouncyCastle provider
        Security.addProvider(new BouncyCastleProvider());
        
        // Load configuration
        Config config = new Config();
        this.phoneNumber = config.getPhoneNumber();
        this.password = config.getPassword();
        this.uuid = UUID.fromString(config.getUuid());
        this.registrationId = config.getRegistrationId();

        // Initialize identity key pair
        byte[] privateKeyBytes = Base64.getDecoder().decode(config.getIdentityPrivateKey());
        byte[] publicKeyBytes = Base64.getDecoder().decode(config.getIdentityPublicKey());
        try {
            IdentityKey publicKey = new IdentityKey(publicKeyBytes, 0);
            ECPrivateKey privateKey = Curve.decodePrivatePoint(privateKeyBytes);
            this.identityKeyPair = new IdentityKeyPair(publicKey, privateKey);
        } catch (InvalidKeyException e) {
            throw new IOException("Failed to initialize identity key pair: " + e.getMessage(), e);
        }

        // Initialize trust store
        trustManagers = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            }
        };

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());

            httpClient = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustManagers[0])
                .hostnameVerifier((hostname, session) -> true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 1, TimeUnit.MINUTES))
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build();

            trustStore = new TrustStore() {
                @Override public InputStream getKeyStoreInputStream() { return null; }
                @Override public String getKeyStorePassword() { return null; }
            };

            // Initialize service configuration
            serviceConfiguration = new SignalServiceConfiguration(
                new SignalServiceUrl[] { new SignalServiceUrl(SIGNAL_URL, trustStore) },
                new SignalCdnUrl[] { new SignalCdnUrl(CDN_URL, trustStore) },
                new SignalContactDiscoveryUrl[] { new SignalContactDiscoveryUrl(CONTACT_DISCOVERY_URL, trustStore) }
            );

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException("Failed to initialize SSL context: " + e.getMessage(), e);
        }
    }

    public String sendMessage(String recipient, String message) throws IOException {
        try {
            // Run signal-cli send command
            ProcessBuilder pb = new ProcessBuilder(
                "signal-cli", "-u", phoneNumber, "send", "-m", message, recipient
            );
            Process p = pb.start();
            
            // Wait for the process to complete
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                // Check stderr for any error messages
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
                throw new IOException("Failed to send message: " + error.toString());
            }
            
            return "Message sent successfully!";
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Message sending was interrupted", e);
        }
    }

    public String receiveMessages() throws IOException {
        try {
            // Run signal-cli receive command
            ProcessBuilder pb = new ProcessBuilder(
                "signal-cli", "-u", phoneNumber, "receive"
            );
            Process p = pb.start();
            
            // Read the output
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            // Wait for the process to complete
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                // Check stderr for any error messages
                reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                StringBuilder error = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
                throw new IOException("Failed to receive messages: " + error.toString());
            }
            
            return output.length() > 0 ? output.toString() : "No new messages";
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Message receiving was interrupted", e);
        }
    }
}
