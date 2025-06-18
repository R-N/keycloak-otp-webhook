package co.jeel.keycloak.authenticator.utils;

import co.jeel.keycloak.authenticator.webhook.WebhookSPIRequest;
import org.apache.commons.codec.digest.HmacAlgorithms;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Formatter;

public class HmacUtils {
    private static final String hmacKey = PropertiesLoader.HMAC_KEY;

    public static String getHmacSignature(String realmName, WebhookSPIRequest webhookSPIRequest) {
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(hmacKey.getBytes(), HmacAlgorithms.HMAC_SHA_256.getName());
            Mac mac = Mac.getInstance(HmacAlgorithms.HMAC_SHA_256.getName());
            mac.init(secretKeySpec);
            String toBeHashed = "realm=" + realmName
                    + "dest=" + webhookSPIRequest.getUserIdentifier()
                    + "otp=" + webhookSPIRequest.getOtp()
                    + "expiry=" + webhookSPIRequest.getOtpExpiryTimestamp();
            byte[] bytes = mac.doFinal(toBeHashed.getBytes());
            Formatter formatter = new Formatter();
            for (byte b : bytes) {
                formatter.format("%02x", b);
            }
            return formatter.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }
}
