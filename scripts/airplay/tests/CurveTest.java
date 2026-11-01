/* CleanFrame AirPlay receiver tests (desktop, see ../test.sh). SPDX-License-Identifier: GPL-3.0-or-later */
package com.cleanframe.firetv.airplay;

import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

/** Checks the plain-Java Ed25519/X25519 against RFC vectors and the JDK's own implementations. */
public final class CurveTest {
    static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }

    public static void main(String[] args) throws Exception {
        int fails = 0;
        // RFC 8032 test 2 (one-byte message)
        Curve25519.Ed25519 k = new Curve25519.Ed25519(hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"));
        fails += check("RFC 8032 public key", Arrays.equals(k.publicKey, hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")));
        byte[] sig = k.sign(hex("72"));
        fails += check("RFC 8032 signature", Arrays.equals(sig, hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")));
        fails += check("RFC 8032 verify", Curve25519.Ed25519.verify(k.publicKey, hex("72"), sig));
        sig[5] ^= 1;
        fails += check("tampered signature fails", !Curve25519.Ed25519.verify(k.publicKey, hex("72"), sig));
        // RFC 7748 6.1 Diffie-Hellman
        byte[] aPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        fails += check("RFC 7748 public key", Arrays.equals(Curve25519.x25519Base(aPriv), hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")));
        fails += check("RFC 7748 shared secret", Arrays.equals(Curve25519.x25519(aPriv, Curve25519.x25519Base(bPriv)), hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")));

        // Random comparison with the JDK (Java 15+ has Ed25519 and X25519).
        SecureRandom rnd = new SecureRandom();
        KeyFactory edKf = KeyFactory.getInstance("Ed25519");
        int edOk = 0, xOk = 0, n = Integer.parseInt(args.length > 0 ? args[0] : "60");
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            byte[] seed = new byte[32], msg = new byte[64];
            rnd.nextBytes(seed); rnd.nextBytes(msg);
            Curve25519.Ed25519 mine = new Curve25519.Ed25519(seed);
            PrivateKey jdkPriv = edKf.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(jdkPriv); s.update(msg);
            byte[] jdkSig = s.sign(), mySig = mine.sign(msg);
            boolean same = Arrays.equals(jdkSig, mySig) && Curve25519.Ed25519.verify(mine.publicKey, msg, jdkSig);
            if (same) edOk++;

            byte[] x1 = new byte[32], x2 = new byte[32];
            rnd.nextBytes(x1); rnd.nextBytes(x2);
            KeyFactory xKf = KeyFactory.getInstance("X25519");
            PrivateKey p1 = xKf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, x1));
            byte[] pub2 = Curve25519.x25519Base(x2);
            byte[] pub2be = new byte[33];
            for (int j = 0; j < 32; j++) pub2be[32 - j] = pub2[j];
            PublicKey jdkPub2 = xKf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(pub2be)));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(p1); ka.doPhase(jdkPub2, true);
            if (Arrays.equals(ka.generateSecret(), Curve25519.x25519(x1, pub2)) && Arrays.equals(Curve25519.x25519(x1, pub2), Curve25519.x25519(x2, Curve25519.x25519Base(x1)))) xOk++;
        }
        System.out.printf("Ed25519 identical to JDK: %d/%d, X25519 identical to JDK: %d/%d (%.0f ms per round)%n", edOk, n, xOk, n, (System.nanoTime() - t0) / 1e6 / n);
        if (edOk != n || xOk != n) fails++;
        System.exit(fails == 0 ? 0 : 1);
    }

    static int check(String name, boolean ok) { System.out.println((ok ? "ok - " : "FAIL - ") + name); return ok ? 0 : 1; }
}
