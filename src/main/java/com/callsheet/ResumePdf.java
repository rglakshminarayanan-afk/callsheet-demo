package com.callsheet;

import com.callsheet.Models.Actor;
import com.callsheet.Models.Credit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a simple one-page PDF resume with no external library (standard Helvetica fonts, ASCII text).
 * Used only to give the demo actors a resume to open. Real uploads are stored as-is.
 */
final class ResumePdf {
    private ResumePdf() {}

    private static final int LEFT = 50;
    private static final int WRAP = 88;

    static byte[] build(Actor a, String managerName, String managerOrg) {
        StringBuilder c = new StringBuilder();
        int[] y = {800};

        line(c, y, a.name(), 22, true, 0);
        line(c, y, a.industry() + ", based in " + a.city(), 11, false, 0);
        line(c, y, "Playing age " + a.playingAge() + "   Height " + a.height(), 11, false, 0);

        section(c, y, "Profile");
        for (String l : wrap(a.bio())) line(c, y, l, 10, false, 0);

        section(c, y, "Languages");
        line(c, y, String.join(", ", a.languages()), 10, false, 0);
        section(c, y, "Genres");
        line(c, y, String.join(", ", a.genres()), 10, false, 0);
        section(c, y, "Skills");
        for (String l : wrap(String.join(", ", a.skills()))) line(c, y, l, 10, false, 0);
        section(c, y, "Training");
        for (String t : a.training()) for (String l : wrap(t)) line(c, y, l, 10, false, 0);

        section(c, y, "Selected credits");
        for (Credit cr : a.credits()) {
            line(c, y, cr.year() + "   " + cr.title() + " - " + cr.role() + " (" + cr.type() + ")", 10, false, 0);
        }
        if (!a.awards().isEmpty()) {
            section(c, y, "Recognition");
            for (String w : a.awards()) for (String l : wrap(w)) line(c, y, l, 10, false, 0);
        }

        section(c, y, "Representation");
        line(c, y, managerName + ", " + managerOrg, 10, false, 0);
        line(c, y, "Demo profile. This is a fictional person and a generated sample resume.", 9, false, 8);

        return assemble(c.toString());
    }

    private static void section(StringBuilder c, int[] y, String title) {
        line(c, y, title, 12, true, 10);
    }

    private static void line(StringBuilder c, int[] y, String text, int size, boolean bold, int gapBefore) {
        y[0] -= gapBefore + size + 3;
        if (y[0] < 50) return; // page is full: drop the rest rather than overflow
        c.append("BT /").append(bold ? "F2" : "F1").append(' ').append(size)
                .append(" Tf ").append(LEFT).append(' ').append(y[0]).append(" Td (")
                .append(esc(text)).append(") Tj ET\n");
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split("\\s+")) {
            if (cur.length() + w.length() + 1 > WRAP && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Escape for a PDF literal string and force plain ASCII. */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '\\' || ch == '(' || ch == ')') b.append('\\').append(ch);
            else if (ch < 32 || ch > 126) b.append('?');
            else b.append(ch);
        }
        return b.toString();
    }

    private static byte[] assemble(String stream) {
        List<String> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
                + "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>");
        objs.add("<< /Length " + stream.length() + " >>\nstream\n" + stream + "endstream");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] off = new int[objs.size() + 1];
        for (int i = 0; i < objs.size(); i++) {
            off[i + 1] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objs.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
        for (int i = 1; i <= objs.size(); i++) {
            pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", off[i]));
        }
        pdf.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
