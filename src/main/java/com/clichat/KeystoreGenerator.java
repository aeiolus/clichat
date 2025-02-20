package com.clichat;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.Security;
import java.nio.file.Path;
import java.nio.file.Paths;

public class KeystoreGenerator {
    public static void main(String[] args) throws Exception {
        // Add BouncyCastle provider
        Security.addProvider(new BouncyCastleProvider());
        
        // Create BKS keystore
        KeyStore keyStore = KeyStore.getInstance("BKS", "BC");
        keyStore.load(null, "signal".toCharArray());
        
        // Create resources directory if it doesn't exist
        Path resourcesPath = Paths.get("src/main/resources");
        if (!resourcesPath.toFile().exists()) {
            resourcesPath.toFile().mkdirs();
        }
        
        // Save the keystore
        try (FileOutputStream fos = new FileOutputStream("src/main/resources/signal.store")) {
            keyStore.store(fos, "signal".toCharArray());
        }
        
        System.out.println("Keystore created successfully at src/main/resources/signal.store");
    }
}
