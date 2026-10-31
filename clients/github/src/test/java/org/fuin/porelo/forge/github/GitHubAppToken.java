package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Mints a short-lived GitHub App JWT with the JDK alone; key material stays in memory. */
final class GitHubAppToken {
    private static final String ALGORITHM = "RS256"; // not-a-citation

    private GitHubAppToken() {}

    static String jwt(String appId, String pem, Instant now) {
        if (!appId.matches("[1-9][0-9]{0,19}"))
            throw new IllegalArgumentException("Invalid GitHub App ID");
        try {
            boolean rsa = pem.strip().startsWith("-----BEGIN RSA PRIVATE KEY-----");
            String label = rsa ? "RSA PRIVATE KEY" : "PRIVATE KEY";
            String begin = "-----BEGIN " + label + "-----";
            String end = "-----END " + label + "-----";
            String text = pem.strip();
            if (!text.startsWith(begin) || !text.endsWith(end))
                throw new IllegalArgumentException();
            byte[] key =
                    Base64.getDecoder()
                            .decode(
                                    text.substring(begin.length(), text.length() - end.length())
                                            .replaceAll("\\s", ""));
            if (rsa) {
                byte[] algorithm =
                        java.util.HexFormat.of().parseHex("300d06092a864886f70d0101010500");
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                contents.writeBytes(new byte[] {2, 1, 0});
                contents.writeBytes(algorithm);
                contents.writeBytes(der(4, key));
                key = der(48, contents.toByteArray());
            }
            var privateKey =
                    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
            ObjectMapper json = new ObjectMapper();
            String signingInput =
                    encode(json.writeValueAsBytes(Map.of("alg", ALGORITHM, "typ", "JWT")))
                            + "."
                            + encode(
                                    json.writeValueAsBytes(
                                            Map.of(
                                                    "iss",
                                                    appId,
                                                    "iat",
                                                    now.minusSeconds(60).getEpochSecond(),
                                                    "exp",
                                                    now.plusSeconds(540).getEpochSecond())));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encode(signature.sign());
        } catch (java.security.GeneralSecurityException
                | java.io.IOException
                | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid GitHub App signing configuration");
        }
    }

    private static byte[] der(int tag, byte[] bytes) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(tag);
        int length = bytes.length;
        if (length < 128) result.write(length);
        else {
            int count = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            result.write(128 | count);
            for (int i = count - 1; i >= 0; i--) result.write(length >>> (8 * i));
        }
        result.writeBytes(bytes);
        return result.toByteArray();
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
