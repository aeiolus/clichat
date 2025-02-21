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

import org.json.*;

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

    private Process receiveProcess;
    private Thread receiveThread;
    private volatile boolean running;
    private List<MessageListener> messageListeners = new ArrayList<>();
    private final Object sendLock = new Object();

    public interface MessageListener {
        void onMessageReceived(Message message);
    }

    public void addMessageListener(MessageListener listener) {
        messageListeners.add(listener);
    }

    public void startReceiving() {
        synchronized (sendLock) {
            if (running) {
                // System.out.println("Receive thread already running, skipping start");
                return;
            }
            running = true;
            // System.out.println("Starting receive thread...");

            // Start signal-cli receive in background
            receiveThread = new Thread(() -> {
                while (running) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(
                                "signal-cli", "-u", phoneNumber, "-o", "json", "receive", "--timeout", "5"
                        );
                        // System.out.println("Starting signal-cli receive process: " + String.join(" ", pb.command()));
                        receiveProcess = pb.start();

                        // Read the output continuously
                        BufferedReader reader = new BufferedReader(new InputStreamReader(receiveProcess.getInputStream()));
                        BufferedReader errorReader = new BufferedReader(new InputStreamReader(receiveProcess.getErrorStream()));

                        // Start error reader thread
                        Thread errorThread = new Thread(() -> {
                            try {
                                String line;
                                while ((line = errorReader.readLine()) != null) {
                                    System.err.println("signal-cli receive error: " + line);
                                }
                            } catch (IOException e) {
                                if (running) {
                                    System.err.println("Error reading from signal-cli error stream: " + e.getMessage());
                                }
                            }
                        });
                        errorThread.start();

                        String line;
                        // System.out.println("Waiting for messages...");
                        while (running && (line = reader.readLine()) != null) {
                            // System.out.println("Received line: " + line);
                            if (line.contains("\"envelope\"")) {
                                try {
                                    JSONObject json = new JSONObject(line);
                                    JSONObject envelope = json.getJSONObject("envelope");

                                    String sourceNumber = envelope.optString("sourceNumber", "");
                                    String sourceName = envelope.optString("sourceName", "");
                                    long timestamp = envelope.optLong("timestamp", 0);

                                    JSONObject dataMessage = envelope.optJSONObject("dataMessage");
                                    if (dataMessage != null) {
                                        String messageText = dataMessage.optString("message", "");
                                        // System.out.println("Processing message from " + sourceNumber + ": " + messageText);
                                        boolean sent = sourceNumber.isEmpty() || sourceNumber.equals(phoneNumber);
                                        Message message = new Message(messageText, timestamp, sent, sourceNumber);

                                        // Notify listeners of new message
                                        // System.out.println("Notifying " + messageListeners.size() + " listeners of message: " + message);
                                        for (MessageListener listener : messageListeners) {
                                            listener.onMessageReceived(message);
                                        }
                                    }
                                } catch (JSONException e) {
                                    System.err.println("Failed to parse message JSON: " + e.getMessage());
                                    System.err.println("Raw JSON: " + line);
                                }
                            }
                        }
                        // System.out.println("Receive process ended, restarting in 1 second...");
                        Thread.sleep(1000);
                    } catch (IOException | InterruptedException e) {
                        if (running) {
                            System.err.println("Error in receive thread: " + e.getMessage());
                            e.printStackTrace();
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } finally {
                        if (receiveProcess != null) {
                            receiveProcess.destroy();
                            receiveProcess = null;
                        }
                    }
                }
                // System.out.println("Receive thread loop ended. running=" + running);
            });
            receiveThread.setName("SignalReceiveThread");
            receiveThread.start();
            // System.out.println("Receive thread started");
        }
    }

    public void stopReceiving() {
        synchronized (sendLock) {
            // System.out.println("Stopping receive thread...");
            running = false;
            if (receiveProcess != null) {
                // System.out.println("Destroying receive process");
                receiveProcess.destroy();
            }
            if (receiveThread != null) {
                // System.out.println("Interrupting receive thread");
                receiveThread.interrupt();
                try {
                    // System.out.println("Waiting for receive thread to finish");
                    receiveThread.join(1000);
                    // System.out.println("Receive thread finished");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Interrupted while waiting for receive thread");
                }
            }
            receiveProcess = null;
            receiveThread = null;
            // System.out.println("Receive thread stopped");
        }
    }

    public String sendMessage(String recipient, String message) throws IOException {
        synchronized (sendLock) {
            // Stop receiving messages temporarily
            stopReceiving();

            try {
                // Run signal-cli send command
                ProcessBuilder pb = new ProcessBuilder(
                        "signal-cli", "-u", phoneNumber, "send", "-m", message, recipient
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
                    throw new IOException("Failed to send message: " + error.toString());
                }

                return output.length() > 0 ? output.toString() : "Message sent successfully";

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Message sending was interrupted", e);
            } finally {
                // Restart receiving messages
                startReceiving();
            }
        }
    }

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

    public List<Contact> listContacts() throws IOException {
        try {
            // Run signal-cli listContacts command
            ProcessBuilder pb = new ProcessBuilder(
                    "signal-cli", "-u", phoneNumber, "listContacts"
            );
            Process p = pb.start();

            // Read the output
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<Contact> contacts = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Number: ")) {
                    // Parse contact information
                    String[] parts = line.split(" ");
                    String number = null;
                    String name = "";
                    String profileName = "";

                    for (int i = 0; i < parts.length; i++) {
                        if (parts[i].equals("Number:") && i + 1 < parts.length) {
                            number = parts[i + 1];
                        } else if (parts[i].equals("Name:") && i + 1 < parts.length) {
                            name = parts[i + 1];
                        } else if (parts[i].equals("Profile") && parts[i + 1].equals("name:") && i + 2 < parts.length) {
                            profileName = parts[i + 2];
                        }
                    }

                    if (number != null && !number.isEmpty()) {
                        contacts.add(new Contact(number, name, profileName));
                    }
                }
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
                throw new IOException("Failed to list contacts: " + error.toString());
            }

            return contacts;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Contact listing was interrupted", e);
        }
    }

    public static class Message {
        private final String content;
        private final long timestamp;
        private final boolean sent;
        private final String sourceNumber;
        private final List<Attachment> attachments;

        public Message(String content, long timestamp, boolean sent, String sourceNumber) {
            this(content, timestamp, sent, sourceNumber, new ArrayList<>());
        }

        public Message(String content, long timestamp, boolean sent, String sourceNumber, List<Attachment> attachments) {
            this.content = content;
            this.timestamp = timestamp;
            this.sent = sent;
            this.sourceNumber = sourceNumber;
            this.attachments = attachments;
        }

        public String getContent() {
            return content;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isSent() {
            return sent;
        }

        public String getSourceNumber() {
            return sourceNumber;
        }

        public List<Attachment> getAttachments() {
            return attachments;
        }

        @Override
        public String toString() {
            return String.format("Message{content='%s', timestamp=%d, sent=%b, sourceNumber='%s', attachments=%d}",
                    content, timestamp, sent, sourceNumber, attachments.size());
        }
    }

    public static class Attachment {
        private final String filePath;
        private final String contentType;
        private final byte[] data;

        public Attachment(String filePath, String contentType, byte[] data) {
            this.filePath = filePath;
            this.contentType = contentType;
            this.data = data;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getData() {
            return data;
        }
    }

    public static class Contact {
        private final String number;
        private final String name;
        private final String profileName;

        public Contact(String number, String name, String profileName) {
            this.number = number;
            this.name = name;
            this.profileName = profileName;
        }

        public String getNumber() {
            return number;
        }

        public String getName() {
            return name;
        }

        public String getProfileName() {
            return profileName;
        }

        public String getDisplayName() {
            if (!name.isEmpty()) {
                return name;
            } else if (!profileName.isEmpty()) {
                return profileName;
            }
            return number;
        }
    }
}
