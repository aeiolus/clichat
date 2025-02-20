package com.clichat;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.Medium;

import java.util.LinkedList;
import java.util.List;

public class KeyHelper {
    public static class SessionKeys {
        private final IdentityKeyPair identityKeyPair;
        private final int registrationId;
        private final List<PreKeyRecord> preKeys;
        private final SignedPreKeyRecord signedPreKey;

        public SessionKeys(IdentityKeyPair identityKeyPair, int registrationId,
                         List<PreKeyRecord> preKeys, SignedPreKeyRecord signedPreKey) {
            this.identityKeyPair = identityKeyPair;
            this.registrationId = registrationId;
            this.preKeys = preKeys;
            this.signedPreKey = signedPreKey;
        }

        public IdentityKeyPair getIdentityKeyPair() {
            return identityKeyPair;
        }

        public int getRegistrationId() {
            return registrationId;
        }

        public List<PreKeyRecord> getPreKeys() {
            return preKeys;
        }

        public SignedPreKeyRecord getSignedPreKey() {
            return signedPreKey;
        }
    }

    public static SessionKeys generateSessionKeys(int preKeyCount) throws Exception {
        IdentityKeyPair identityKeyPair = generateIdentityKeyPair();
        int registrationId = generateRegistrationId();
        List<PreKeyRecord> preKeys = generatePreKeys(preKeyCount);
        SignedPreKeyRecord signedPreKey = generateSignedPreKey(identityKeyPair);

        return new SessionKeys(identityKeyPair, registrationId, preKeys, signedPreKey);
    }

    public static IdentityKeyPair generateIdentityKeyPair() {
        ECKeyPair keyPair = Curve.generateKeyPair();
        return new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
    }

    public static int generateRegistrationId() {
        return org.whispersystems.libsignal.util.KeyHelper.generateRegistrationId(false);
    }

    public static List<PreKeyRecord> generatePreKeys(int count) {
        List<PreKeyRecord> records = new LinkedList<>();
        int start = Medium.MAX_VALUE / 2;

        for (int i = 0; i < count; i++) {
            int preKeyId = (start + i) % Medium.MAX_VALUE;
            ECKeyPair keyPair = Curve.generateKeyPair();
            PreKeyRecord record = new PreKeyRecord(preKeyId, keyPair);
            records.add(record);
        }

        return records;
    }

    public static SignedPreKeyRecord generateSignedPreKey(IdentityKeyPair identityKeyPair) throws Exception {
        int signedPreKeyId = Medium.MAX_VALUE / 2;
        ECKeyPair keyPair = Curve.generateKeyPair();
        byte[] signature = Curve.calculateSignature(identityKeyPair.getPrivateKey(), keyPair.getPublicKey().serialize());
        return new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);
    }
}
