package com.clichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;

public class Config {
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.local/share/signal-cli/data";
    private JsonNode accountConfig;
    private JsonNode accounts;
    private String phoneNumber;
    private String accountPath;

    public Config() {
        loadConfig();
    }

    private void loadConfig() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            
            // Read accounts.json first
            accounts = mapper.readTree(new File(CONFIG_DIR + "/accounts.json"));
            JsonNode accountsList = accounts.get("accounts");
            if (accountsList.size() > 0) {
                JsonNode account = accountsList.get(0);
                phoneNumber = account.get("number").asText();
                accountPath = account.get("path").asText();
                
                // Now read the account config
                accountConfig = mapper.readTree(new File(CONFIG_DIR + "/" + accountPath));
            } else {
                throw new RuntimeException("No Signal accounts found. Please configure signal-cli first.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + e.getMessage(), e);
        }
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getPassword() {
        return accountConfig.get("password").asText();
    }

    public int getRegistrationId() {
        return accountConfig.get("aciAccountData").get("registrationId").asInt();
    }

    public String getIdentityPrivateKey() {
        return accountConfig.get("aciAccountData").get("identityPrivateKey").asText();
    }

    public String getIdentityPublicKey() {
        return accountConfig.get("aciAccountData").get("identityPublicKey").asText();
    }

    public String getUuid() {
        return accountConfig.get("aciAccountData").get("serviceId").asText();
    }
}
