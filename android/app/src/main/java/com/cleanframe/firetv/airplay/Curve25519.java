/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Ed25519 (RFC 8032) and X25519 (RFC 7748) in plain Java, because Fire OS (Android 9/11) has
 * neither in its crypto providers. BigInteger arithmetic is slow but only runs once per
 * connection (pair-verify). Not constant-time: acceptable for a LAN receiver's per-session keys.
 */
package com.cleanframe.firetv.airplay;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

final class Curve25519 {
    private Curve25519() {}

    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.multiply(TWO).mod(P);
    private static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final Point B;

    static {
        BigInteger y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        B = Point.decode(encodeLe(y, 32));
    }

    static final SecureRandom RANDOM = new SecureRandom();

    /* ---------- little-endian helpers ---------- */
    static BigInteger decodeLe(byte[] b, int off, int len) {
        byte[] be = new byte[len + 1];
        for (int i = 0; i < len; i++) be[len - i] = b[off + i];
        return new BigInteger(be);
    }

    static byte[] encodeLe(BigInteger v, int len) {
        byte[] be = v.toByteArray(), out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /* ---------- Edwards points (extended coordinates) ---------- */
    static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x; this.y = y; this.z = z; this.t = t;
        }

        static final Point ZERO = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

        Point add(Point q) {
            BigInteger a = y.subtract(x).multiply(q.y.subtract(q.x)).mod(P);
            BigInteger b = y.add(x).multiply(q.y.add(q.x)).mod(P);
            BigInteger c = t.multiply(D2).multiply(q.t).mod(P);
            BigInteger d = z.multiply(TWO).multiply(q.z).mod(P);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Point multiply(BigInteger s) {
            Point q = ZERO, r = this;
            for (int i = 0; i < s.bitLength(); i++) {
                if (s.testBit(i)) q = q.add(r);
                r = r.add(r);
            }
            return q;
        }

        boolean sameAs(Point q) {
            return x.multiply(q.z).subtract(q.x.multiply(z)).mod(P).signum() == 0
                && y.multiply(q.z).subtract(q.y.multiply(z)).mod(P).signum() == 0;
        }

        byte[] encode() {
            BigInteger zi = z.modInverse(P), ax = x.multiply(zi).mod(P), ay = y.multiply(zi).mod(P);
            byte[] out = encodeLe(ay, 32);
            if (ax.testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }

        /** Returns null for an invalid encoding. */
        static Point decode(byte[] s) {
            if (s.length != 32) return null;
            byte[] c = s.clone();
            int sign = (c[31] >> 7) & 1;
            c[31] &= 0x7f;
            BigInteger y = decodeLe(c, 0, 32);
            if (y.compareTo(P) >= 0) return null;
            BigInteger y2 = y.multiply(y).mod(P);
            BigInteger u = y2.subtract(BigInteger.ONE).mod(P), v = D.multiply(y2).add(BigInteger.ONE).mod(P);
            BigInteger x = u.multiply(v.pow(3)).multiply(u.multiply(v.pow(7)).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
            BigInteger vx2 = v.multiply(x).multiply(x).mod(P);
            if (vx2.equals(u)) {
                // x is a root
            } else if (vx2.equals(u.negate().mod(P))) {
                x = x.multiply(SQRT_M1).mod(P);
            } else {
                return null;
            }
            if (x.signum() == 0 && sign == 1) return null;
            if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x);
            return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
        }
    }

    /* ---------- Ed25519 ---------- */
    static final class Ed25519 {
        final byte[] seed, publicKey;
        private final BigInteger a;
        private final byte[] prefix;

        Ed25519(byte[] seed) {
            if (seed.length != 32) throw new IllegalArgumentException("Ed25519 seed");
            this.seed = seed.clone();
            byte[] h = sha512(seed);
            h[0] &= (byte) 248;
            h[31] &= 127;
            h[31] |= 64;
            a = decodeLe(h, 0, 32);
            prefix = Arrays.copyOfRange(h, 32, 64);
            publicKey = B.multiply(a).encode();
        }

        static Ed25519 generate() {
            byte[] seed = new byte[32];
            RANDOM.nextBytes(seed);
            return new Ed25519(seed);
        }

        byte[] sign(byte[] message) {
            BigInteger r = decodeLe(sha512(prefix, message), 0, 64).mod(L);
            byte[] rEnc = B.multiply(r).encode();
            BigInteger k = decodeLe(sha512(rEnc, publicKey, message), 0, 64).mod(L);
            BigInteger s = r.add(k.multiply(a)).mod(L);
            byte[] sig = new byte[64];
            System.arraycopy(rEnc, 0, sig, 0, 32);
            System.arraycopy(encodeLe(s, 32), 0, sig, 32, 32);
            return sig;
        }

        static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
            if (publicKey.length != 32 || signature.length != 64) return false;
            Point pa = Point.decode(publicKey), pr = Point.decode(Arrays.copyOfRange(signature, 0, 32));
            if (pa == null || pr == null) return false;
            BigInteger s = decodeLe(signature, 32, 32);
            if (s.compareTo(L) >= 0) return false;
            BigInteger k = decodeLe(sha512(Arrays.copyOfRange(signature, 0, 32), publicKey, message), 0, 64).mod(L);
            return B.multiply(s).sameAs(pr.add(pa.multiply(k)));
        }
    }

    /* ---------- X25519 ---------- */
    static byte[] x25519(byte[] scalar, byte[] u) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger kn = decodeLe(k, 0, 32);
        byte[] uc = u.clone();
        uc[31] &= 127;
        BigInteger x1 = decodeLe(uc, 0, 32).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = kn.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;
            BigInteger aa = x2.add(z2), a2 = aa.multiply(aa).mod(P);
            BigInteger bb = x2.subtract(z2), b2 = bb.multiply(bb).mod(P);
            BigInteger e = a2.subtract(b2);
            BigInteger c = x3.add(z3), d = x3.subtract(z3);
            BigInteger da = d.multiply(aa).mod(P), cb = c.multiply(bb).mod(P);
            BigInteger s1 = da.add(cb), s2 = da.subtract(cb);
            x3 = s1.multiply(s1).mod(P);
            z3 = x1.multiply(s2.multiply(s2)).mod(P);
            x2 = a2.multiply(b2).mod(P);
            z2 = e.multiply(a2.add(A24.multiply(e))).mod(P);
        }
        if (swap == 1) {
            x2 = x3;
            z2 = z3;
        }
        return encodeLe(x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P), 32);
    }

    static byte[] x25519Base(byte[] scalar) {
        byte[] nine = new byte[32];
        nine[0] = 9;
        return x25519(scalar, nine);
    }
}
