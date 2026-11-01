/* CleanFrame AirPlay receiver tests (desktop, see ../test.sh). SPDX-License-Identifier: GPL-3.0-or-later */
package com.cleanframe.firetv.airplay;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Round trip with Python's plistlib: Java writes -> Python reads, Python writes -> Java reads. */
public final class BPlistTest {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path dir = Paths.get(args[1]);
        if (mode.equals("write")) {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("deviceID", "AA:BB:CC:DD:EE:FF");
            root.put("features", 0x5A7FFEE6L | (1L << 40));
            root.put("name", "Jasons Fire TV – Wohnzimmer");
            root.put("pk", new byte[] {1, 2, 3, (byte) 0xff});
            root.put("vv", 2L);
            root.put("refreshRate", 1.0 / 60);
            root.put("keepAliveLowPower", Boolean.TRUE);
            root.put("off", Boolean.FALSE);
            List<Object> displays = new ArrayList<Object>();
            Map<String, Object> display = new LinkedHashMap<String, Object>();
            display.put("width", 1920L);
            display.put("height", 1080L);
            display.put("big", 70000L);
            display.put("huge", 5000000000L);
            displays.add(display);
            displays.add("second");
            root.put("displays", displays);
            List<Object> many = new ArrayList<Object>();
            for (int i = 0; i < 300; i++) many.add((long) i * 1000);
            root.put("many", many);
            byte[] blob = new byte[70000];
            new Random(1).nextBytes(blob);
            root.put("blob", blob);
            Files.write(dir.resolve("java.bplist"), BPlist.write(root));
            System.out.println("written");
        } else {
            Map<String, Object> m = BPlist.dict(BPlist.parse(Files.readAllBytes(dir.resolve("python.bplist"))));
            Map<String, Object> stream = BPlist.dict(BPlist.list(m, "streams").get(0));
            StringBuilder s = new StringBuilder();
            s.append(BPlist.string(m, "deviceID")).append('|').append(BPlist.string(m, "name")).append('|')
             .append(BPlist.data(m, "ekey").length).append('|').append(BPlist.data(m, "eiv")[15] & 0xff).append('|')
             .append(BPlist.number(m, "timingPort", -1)).append('|').append(BPlist.bool(m, "isScreenMirroringSession")).append('|')
             .append(BPlist.number(stream, "type", -1)).append('|').append(BPlist.number(stream, "streamConnectionID", -1)).append('|')
             .append(BPlist.number(m, "neg", 0)).append('|').append(m.get("real"));
            System.out.println(s);
        }
    }
}
