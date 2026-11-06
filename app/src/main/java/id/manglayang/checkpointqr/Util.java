package id.manglayang.checkpointqr;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Util {
    private Util() {}

    private static final Pattern BIB_POLOS = Pattern.compile("^[A-Za-z0-9-]{1,12}$");
    private static final Pattern BIB_DALAM = Pattern.compile("(?i)bib[=:#\\s]*([A-Za-z0-9-]{1,12})");

    /** Ambil nomor BIB dari isi QR. QR cetakan laptop berisi BIB saja ("101"). null kalau bukan BIB. */
    public static String ambilBib(String teks) {
        if (teks == null) return null;
        String t = teks.trim();
        if (BIB_POLOS.matcher(t).matches()) return t.toUpperCase(Locale.ROOT);
        Matcher m = BIB_DALAM.matcher(t);
        if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
        return null;
    }

    public static String jam(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    public static String jamTanggal(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(ms));
    }

    /** "berapa lama lalu" singkat, contoh "45 dtk", "3 mnt", "1 j 5 mnt". */
    public static String lalu(long ms) {
        long d = Math.max(0, ms / 1000);
        if (d < 60) return d + " dtk";
        if (d < 3600) return (d / 60) + " mnt";
        return (d / 3600) + " j " + ((d % 3600) / 60) + " mnt";
    }

    /** "192.168.43.20" -> "http://192.168.43.20:8000"; https://… dibiarkan. */
    public static String rapikanUrl(String u) {
        String s = u == null ? "" : u.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        if (!s.contains("://")) s = "http://" + s;
        if (s.startsWith("http://")) {
            String sisa = s.substring(7);
            int garis = sisa.indexOf('/');
            String host = garis >= 0 ? sisa.substring(0, garis) : sisa;
            if (!host.contains(":")) s = "http://" + host + ":8000" + (garis >= 0 ? sisa.substring(garis) : "");
        }
        return s;
    }

    /** QR setup dari laptop: "RTCP1;url=http://…:8000;pin=123456" -> map. null kalau bukan QR setup. */
    public static Map<String, String> bacaQrSetup(String teks) {
        if (teks == null || !teks.startsWith("RTCP1;")) return null;
        Map<String, String> hasil = new HashMap<>();
        for (String bagian : teks.substring(6).split(";")) {
            int i = bagian.indexOf('=');
            if (i > 0) hasil.put(bagian.substring(0, i).trim(), bagian.substring(i + 1).trim());
        }
        return hasil.containsKey("url") ? hasil : null;
    }

    public static String csvSel(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
