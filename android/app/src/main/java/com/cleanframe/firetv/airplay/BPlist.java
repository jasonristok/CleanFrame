/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Apple binary property lists ("bplist00"), the body format of AirPlay requests and replies.
 * Values map to Java as: dict -> Map<String,Object> (insertion order kept), array -> List<Object>,
 * string -> String, data -> byte[], integer -> Long, real -> Double, bool -> Boolean.
 */
package com.cleanframe.firetv.airplay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BPlist {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF16 = Charset.forName("UTF-16BE");
    private static final int MAX_OBJECTS = 100000;

    private BPlist() {}

    /* ---------- reading ---------- */

    /** Parses a binary plist. Throws IllegalArgumentException for anything malformed. */
    static Object parse(byte[] data) {
        if (data == null || data.length < 40 || !new String(data, 0, 8, ASCII).equals("bplist00")) throw new IllegalArgumentException("not a bplist");
        int t = data.length - 32;
        int offsetSize = data[t + 6] & 0xff, refSize = data[t + 7] & 0xff;
        long count = be(data, t + 8, 8), top = be(data, t + 16, 8), tableOffset = be(data, t + 24, 8);
        if (offsetSize < 1 || offsetSize > 8 || refSize < 1 || refSize > 8 || count < 1 || count > MAX_OBJECTS
            || top >= count || tableOffset < 8 || tableOffset + count * offsetSize > t) throw new IllegalArgumentException("bad bplist trailer");
        long[] offsets = new long[(int) count];
        for (int i = 0; i < count; i++) {
            offsets[i] = be(data, (int) tableOffset + i * offsetSize, offsetSize);
            if (offsets[i] < 8 || offsets[i] >= tableOffset) throw new IllegalArgumentException("bad bplist offset");
        }
        return new Reader(data, offsets, refSize).object((int) top, 0);
    }

    private static long be(byte[] b, int off, int len) {
        if (off < 0 || off + len > b.length) throw new IllegalArgumentException("bplist truncated");
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    private static final class Reader {
        final byte[] d;
        final long[] offsets;
        final int refSize;

        Reader(byte[] d, long[] offsets, int refSize) {
            this.d = d;
            this.offsets = offsets;
            this.refSize = refSize;
        }

        Object object(int ref, int depth) {
            if (depth > 32 || ref < 0 || ref >= offsets.length) throw new IllegalArgumentException("bad bplist reference");
            int off = (int) offsets[ref];
            int marker = d[off] & 0xff, type = marker >> 4, info = marker & 0x0f;
            switch (type) {
                case 0x0:
                    if (info == 0x8) return Boolean.FALSE;
                    if (info == 0x9) return Boolean.TRUE;
                    return null;
                case 0x1:
                    return be(d, off + 1, 1 << info);
                case 0x2:
                    if (info == 2) return (double) Float.intBitsToFloat((int) be(d, off + 1, 4));
                    if (info == 3) return Double.longBitsToDouble(be(d, off + 1, 8));
                    throw new IllegalArgumentException("bad bplist real");
                case 0x3:
                    return Double.longBitsToDouble(be(d, off + 1, 8));
                case 0x4: {
                    int[] lh = length(off, info);
                    check(lh[1], lh[0]);
                    byte[] out = new byte[lh[0]];
                    System.arraycopy(d, lh[1], out, 0, lh[0]);
                    return out;
                }
                case 0x5: {
                    int[] lh = length(off, info);
                    check(lh[1], lh[0]);
                    return new String(d, lh[1], lh[0], ASCII);
                }
                case 0x6: {
                    int[] lh = length(off, info);
                    check(lh[1], lh[0] * 2);
                    return new String(d, lh[1], lh[0] * 2, UTF16);
                }
                case 0x8:
                    return be(d, off + 1, info + 1);
                case 0xA: {
                    int[] lh = length(off, info);
                    check(lh[1], lh[0] * refSize);
                    List<Object> list = new ArrayList<Object>(lh[0]);
                    for (int i = 0; i < lh[0]; i++) list.add(object((int) be(d, lh[1] + i * refSize, refSize), depth + 1));
                    return list;
                }
                case 0xD: {
                    int[] lh = length(off, info);
                    check(lh[1], lh[0] * 2 * refSize);
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < lh[0]; i++) {
                        Object key = object((int) be(d, lh[1] + i * refSize, refSize), depth + 1);
                        Object value = object((int) be(d, lh[1] + (lh[0] + i) * refSize, refSize), depth + 1);
                        map.put(String.valueOf(key), value);
                    }
                    return map;
                }
                default:
                    throw new IllegalArgumentException("unsupported bplist type " + type);
            }
        }

        /** {length, offset of the content} */
        int[] length(int off, int info) {
            if (info != 0xf) return new int[] {info, off + 1};
            int m = d[off + 1] & 0xff;
            if ((m >> 4) != 0x1) throw new IllegalArgumentException("bad bplist length");
            int size = 1 << (m & 0xf);
            long len = be(d, off + 2, size);
            if (len < 0 || len > d.length) throw new IllegalArgumentException("bad bplist length");
            return new int[] {(int) len, off + 2 + size};
        }

        void check(int off, long len) {
            if (len < 0 || off + len > d.length) throw new IllegalArgumentException("bplist truncated");
        }
    }

    /* ---------- convenience getters ---------- */

    @SuppressWarnings("unchecked")
    static Map<String, Object> dict(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    static byte[] data(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof byte[] ? (byte[]) o : null;
    }

    static String string(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof String ? (String) o : null;
    }

    static long number(Map<String, Object> m, String key, long fallback) {
        Object o = m == null ? null : m.get(key);
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) return (long) (double) (Double) o;
        return fallback;
    }

    static boolean bool(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return Boolean.TRUE.equals(o) || (o instanceof Long && (Long) o != 0);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof List ? (List<Object>) o : null;
    }

    /* ---------- writing ---------- */

    static byte[] write(Object root) {
        List<Object> objects = new ArrayList<Object>();
        flatten(root, objects);
        int count = objects.size();
        int refSize = count < 256 ? 1 : count < 65536 ? 2 : 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b'); out.write('p'); out.write('l'); out.write('i'); out.write('s'); out.write('t'); out.write('0'); out.write('0');
        long[] offsets = new long[count];
        Map<Object, Integer> index = new java.util.IdentityHashMap<Object, Integer>();
        for (int i = 0; i < count; i++) index.put(objects.get(i), i);
        for (int i = 0; i < count; i++) {
            offsets[i] = out.size();
            writeObject(out, objects.get(i), index, refSize);
        }
        long tableOffset = out.size();
        int offsetSize = tableOffset < 256 ? 1 : tableOffset < 65536 ? 2 : tableOffset < (1L << 32) ? 4 : 8;
        for (long o : offsets) putBe(out, o, offsetSize);
        for (int i = 0; i < 6; i++) out.write(0);
        out.write(offsetSize);
        out.write(refSize);
        putBe(out, count, 8);
        putBe(out, 0, 8);
        putBe(out, tableOffset, 8);
        return out.toByteArray();
    }

    /* Lists every object once, depth first, as a node that refers to its children's nodes. */
    private static Object flatten(Object o, List<Object> objects) {
        if (o instanceof Map) {
            DictNode node = new DictNode();
            objects.add(node);
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) node.keys.add(flatten(new Key(String.valueOf(e.getKey())), objects));
            for (Object v : ((Map<?, ?>) o).values()) node.values.add(flatten(v, objects));
            return node;
        }
        if (o instanceof List) {
            ArrayNode node = new ArrayNode();
            objects.add(node);
            for (Object v : (List<?>) o) node.items.add(flatten(v, objects));
            return node;
        }
        Object leaf = o instanceof Key ? o : new Leaf(o);
        objects.add(leaf);
        return leaf;
    }

    private static final class Key {
        final String s;
        Key(String s) { this.s = s; }
    }

    private static final class Leaf {
        final Object v;
        Leaf(Object v) { this.v = v; }
    }

    private static final class DictNode {
        final List<Object> keys = new ArrayList<Object>(), values = new ArrayList<Object>();
    }

    private static final class ArrayNode {
        final List<Object> items = new ArrayList<Object>();
    }

    private static void writeObject(ByteArrayOutputStream out, Object o, Map<Object, Integer> index, int refSize) {
        if (o instanceof DictNode) {
            DictNode d = (DictNode) o;
            header(out, 0xD, d.keys.size());
            for (Object k : d.keys) putBe(out, index.get(k), refSize);
            for (Object v : d.values) putBe(out, index.get(v), refSize);
        } else if (o instanceof ArrayNode) {
            ArrayNode a = (ArrayNode) o;
            header(out, 0xA, a.items.size());
            for (Object v : a.items) putBe(out, index.get(v), refSize);
        } else if (o instanceof Key) {
            string(out, ((Key) o).s);
        } else {
            Object v = o instanceof Leaf ? ((Leaf) o).v : o;
            if (v == null) out.write(0x00);
            else if (v instanceof Boolean) out.write((Boolean) v ? 0x09 : 0x08);
            else if (v instanceof Long || v instanceof Integer || v instanceof Short) integer(out, ((Number) v).longValue());
            else if (v instanceof Double || v instanceof Float) {
                out.write(0x23);
                putBe(out, Double.doubleToLongBits(((Number) v).doubleValue()), 8);
            } else if (v instanceof byte[]) {
                byte[] b = (byte[]) v;
                header(out, 0x4, b.length);
                out.write(b, 0, b.length);
            } else string(out, String.valueOf(v));
        }
    }

    private static void string(ByteArrayOutputStream out, String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7f) { ascii = false; break; }
        byte[] b = s.getBytes(ascii ? ASCII : UTF16);
        header(out, ascii ? 0x5 : 0x6, s.length());
        out.write(b, 0, b.length);
    }

    private static void integer(ByteArrayOutputStream out, long v) {
        if (v >= 0 && v < 0x100) { out.write(0x10); putBe(out, v, 1); }
        else if (v >= 0 && v < 0x10000) { out.write(0x11); putBe(out, v, 2); }
        else if (v >= 0 && v < 0x100000000L) { out.write(0x12); putBe(out, v, 4); }
        else { out.write(0x13); putBe(out, v, 8); }
    }

    private static void header(ByteArrayOutputStream out, int type, int length) {
        if (length < 15) {
            out.write((type << 4) | length);
        } else {
            out.write((type << 4) | 0xf);
            integer(out, length);
        }
    }

    private static void putBe(ByteArrayOutputStream out, long v, int size) {
        for (int i = size - 1; i >= 0; i--) out.write((int) (v >>> (8 * i)) & 0xff);
    }
}
