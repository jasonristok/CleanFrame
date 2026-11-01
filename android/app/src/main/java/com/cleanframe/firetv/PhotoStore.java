package com.cleanframe.firetv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Own wallpapers on the device. Plain Java (no Android classes) so it can be tested on a desktop JVM.
 *
 * incoming/<id>.jpg  a photo received from the phone, waiting for the page to prepare it
 * <id>/<name>.jpg    the prepared background, thumbnail, panel and four material images
 * <id>/meta.json     written last; a folder without it is incomplete and never listed
 */
final class PhotoStore {
    static final int LIMIT = 8;
    static final int MAX_UPLOAD = 15 * 1024 * 1024;
    static final List<String> NAMES = Arrays.asList("photo", "thumb", "panel", "field-blur", "field-frost", "field-clear", "study-soft");
    private static final Pattern ID = Pattern.compile("u[0-9a-f]{12}");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File root;
    private final File incoming;
    private final SecureRandom random = new SecureRandom();

    PhotoStore(File base) {
        root = new File(base, "wallpapers");
        incoming = new File(base, "wallpapers-incoming");
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Stores a received JPEG and returns its new id, or "full" / "invalid". */
    synchronized String accept(byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 1024 || jpeg.length > MAX_UPLOAD
                || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8 || (jpeg[2] & 0xff) != 0xff) return "invalid";
        if (ready().size() + pending().size() >= LIMIT) return "full";
        String id;
        do {
            id = "u" + hex(6);
        } while (new File(root, id).exists() || incomingFile(id).exists());
        write(incomingFile(id), jpeg);
        return id;
    }

    File incomingFile(String id) {
        return new File(incoming, id + ".jpg");
    }

    File file(String id, String name) {
        return new File(new File(root, id), name + ".jpg");
    }

    synchronized boolean save(String id, String name, byte[] jpeg) throws IOException {
        if (!validId(id) || !NAMES.contains(name) || jpeg == null || jpeg.length < 100) return false;
        write(file(id, name), jpeg);
        return true;
    }

    /** Marks a prepared wallpaper complete. meta is the page's JSON object, kept as written. */
    synchronized boolean commit(String id, String meta) throws IOException {
        if (!validId(id) || meta == null || meta.length() > 4000 || !meta.trim().startsWith("{")) return false;
        for (String name : NAMES) if (!file(id, name).isFile()) return false;
        write(new File(new File(root, id), "meta.json"), meta.getBytes(UTF8));
        delete(incomingFile(id));
        return true;
    }

    synchronized boolean remove(String id) {
        if (!validId(id)) return false;
        boolean existed = new File(root, id).exists() || incomingFile(id).exists();
        deleteTree(new File(root, id));
        delete(incomingFile(id));
        return existed;
    }

    /** Drops a photo that could not be prepared. */
    synchronized void discard(String id) {
        if (!validId(id)) return;
        delete(incomingFile(id));
        if (!new File(new File(root, id), "meta.json").isFile()) deleteTree(new File(root, id));
    }

    synchronized List<String> pending() {
        List<String> ids = new ArrayList<String>();
        File[] files = incoming.listFiles();
        if (files == null) return ids;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".jpg") && validId(name.substring(0, name.length() - 4))) ids.add(name.substring(0, name.length() - 4));
        }
        return ids;
    }

    /** Complete wallpapers, oldest first. */
    synchronized List<String> ready() {
        List<File> dirs = new ArrayList<File>();
        File[] all = root.listFiles();
        if (all != null) for (File dir : all) if (validId(dir.getName()) && new File(dir, "meta.json").isFile()) dirs.add(dir);
        java.util.Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = new File(a, "meta.json").lastModified(), y = new File(b, "meta.json").lastModified();
                return x < y ? -1 : x > y ? 1 : a.getName().compareTo(b.getName());
            }
        });
        List<String> ids = new ArrayList<String>();
        for (File dir : dirs) ids.add(dir.getName());
        return ids;
    }

    /** [{"id":..., "state":"ready", "meta":"<json text>"}, {"id":..., "state":"pending"}] */
    synchronized String listJson() {
        StringBuilder out = new StringBuilder("[");
        for (String id : ready()) {
            String meta;
            try {
                meta = new String(read(new File(new File(root, id), "meta.json")), UTF8);
            } catch (IOException error) {
                continue;
            }
            if (out.length() > 1) out.append(',');
            out.append("{\"id\":").append(quote(id)).append(",\"state\":\"ready\",\"meta\":").append(quote(meta)).append('}');
        }
        for (String id : pending()) {
            if (out.length() > 1) out.append(',');
            out.append("{\"id\":").append(quote(id)).append(",\"state\":\"pending\"}");
        }
        return out.append(']').toString();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || c == 0x2028 || c == 0x2029) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    static byte[] read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0, n;
            while (offset < data.length && (n = in.read(data, offset, data.length - offset)) > 0) offset += n;
            return offset == data.length ? data : Arrays.copyOf(data, offset);
        } finally {
            in.close();
        }
    }

    private String hex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    /** Writes through a temporary file so a reader never sees half an image. */
    private static void write(File target, byte[] data) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File temp = new File(dir, target.getName() + ".part");
        OutputStream out = new FileOutputStream(temp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        if (!temp.renameTo(target)) {
            delete(target);
            if (!temp.renameTo(target)) throw new IOException("Cannot store " + target);
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private static void deleteTree(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) delete(file);
        delete(dir);
    }
}
