package dictionary.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Bảng màu & font. Phong cách "doodle study desk": giấy kem, mực tím than đậm, nét viền dày,
 * bóng cứng kiểu sticker, các màu phẳng (hồng, xanh dương, tím nhạt, vàng bơ điểm xuyết) lấy từ ảnh tham khảo.
 * Hỗ trợ sáng/tối.
 */
public final class Theme {
    private Theme() { }

    private static boolean dark = false;

    public static boolean isDark() { return dark; }
    public static void setDark(boolean d) { dark = d; }

    private static Color pick(int light, int darkC) { return new Color(dark ? darkC : light); }

    // ---- nền & bề mặt (hồng + xanh dương pastel) ----
    public static Color bg()        { return pick(0xFFF1F6, 0x191330); }   // giấy hồng nhạt / đêm tím than
    public static Color doodle()    { return pick(0x6FA8F0, 0xFFB3D1); }   // nét vẽ nền (alpha áp dụng khi vẽ)
    public static Color card()      { return pick(0xFFFFFF, 0x261E45); }
    public static Color cream()     { return pick(0xFFDCE9, 0x3A2C5E); }   // hồng pastel
    public static Color creamDeep() { return pick(0xFFC4DA, 0x4D3A7A); }
    public static Color ink()       { return pick(0x2C2A5A, 0xFFD0E3); }   // màu viền "mực"
    public static Color shadow()    { return pick(0x2C2A5A, 0x0C0820); }   // bóng cứng
    public static Color sidebar()   { return pick(0xB9DAFF, 0x201838); }   // xanh dương pastel
    public static Color sidebarHi() { return pick(0xFFFFFF, 0x3A2C5E); }

    // ---- màu nhấn ----
    public static Color accent()    { return pick(0x7DB6FF, 0x7DB4FF); }   // xanh dương pastel (nút chính)
    public static Color accentDark(){ return pick(0x2F5FC4, 0xA9D0FF); }   // xanh đậm dùng cho chữ
    public static Color gold()      { return pick(0xFF9EC4, 0xFF8FBB); }   // hồng pastel (nút nhấn)
    public static Color butter()    { return new Color(0xFFE08A); }        // vàng bơ, chỉ để điểm xuyết
    public static Color coral()     { return pick(0xFF8FB1, 0xFF9AB8); }
    public static Color sky()       { return pick(0xA8D4FF, 0x7CB6F0); }
    public static Color lilac()     { return pick(0xD3C4FF, 0xB5A3EA); }
    public static Color mint()      { return pick(0xD2E8FF, 0x35507F); }   // xanh nhạt (nền minh hoạ)

    // ---- chữ ----
    public static Color text()      { return pick(0x2C2A5A, 0xF6EEFF); }
    public static Color muted()     { return pick(0x726F9E, 0xB4A9D6); }
    public static Color onAccent()  { return pick(0x2C2A5A, 0x1A1333); }
    public static Color onGold()    { return pick(0x2C2A5A, 0x1A1333); }
    public static Color border()    { return pick(0xE6DDF0, 0x3F3367); }   // viền mảnh
    public static Color inputBg()   { return pick(0xFFFFFF, 0x1C1638); }

    // ---- trạng thái ----
    public static Color good()      { return pick(0x2E9E7B, 0x4CC9A0); }
    public static Color goodBg()    { return pick(0xD3F3E8, 0x1D4A40); }
    public static Color bad()       { return pick(0xE5486B, 0xFF6B8B); }
    public static Color badBg()     { return pick(0xFFDCE4, 0x4F2342); }

    public static Color sidebarText(){ return pick(0x2C2A5A, 0xF1E9FF); }
    public static Color sidebarMuted(){ return pick(0x4F5C93, 0xB4A9D6); }

    /** Bảng màu xoay vòng cho các thẻ / huy hiệu (đầu vào: chỉ số bất kỳ). */
    public static Color rainbow(int i) {
        Color[] c = {coral(), sky(), butter(), lilac(), gold()};
        return c[Math.floorMod(i, c.length)];
    }

    // ---------- font ----------
    private static final Map<String, Font> LOADED = new HashMap<>();
    private static final boolean HAVE_BUNDLED = loadBundled();

    private static boolean loadBundled() {
        String[] names = {"Fredoka_600SemiBold", "Fredoka_700Bold", "Nunito_400Regular", "Nunito_400Regular_Italic",
                "Nunito_600SemiBold", "Nunito_700Bold", "Nunito_800ExtraBold"};
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (String n : names) {
                try (InputStream in = Theme.class.getResourceAsStream("/fonts/" + n + ".ttf")) {
                    if (in == null) return false;
                    Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                    ge.registerFont(f);
                    LOADED.put(n, f);
                }
            }
            return true;
        } catch (Exception e) {
            LOADED.clear();
            return false;
        }
    }

    private static Font bundled(String key, float size) { return LOADED.get(key).deriveFont(size); }

    /** Chữ thân bài (Nunito). BOLD → đậm. */
    public static Font font(int style, float size) {
        if (!HAVE_BUNDLED) return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, size);
        if ((style & Font.BOLD) != 0) return bundled("Nunito_700Bold", size);
        if ((style & Font.ITALIC) != 0) return bundled("Nunito_400Regular_Italic", size);
        return bundled("Nunito_400Regular", size);
    }

    /** Nhấn mạnh vừa phải (Nunito semi-bold). */
    public static Font semi(float size) {
        return HAVE_BUNDLED ? bundled("Nunito_600SemiBold", size) : new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(Font.BOLD, size);
    }

    /** Chữ tiêu đề tròn, vui (Fredoka). */
    public static Font display(float size) {
        return HAVE_BUNDLED ? bundled("Fredoka_700Bold", size) : new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(Font.BOLD, size);
    }

    /** Chữ cực đậm cho nút / huy hiệu (Nunito ExtraBold). */
    public static Font heavy(float size) {
        return HAVE_BUNDLED ? bundled("Nunito_800ExtraBold", size) : new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(Font.BOLD, size);
    }

    /** Họ font dùng trong HTML của JEditorPane. */
    public static String bodyFamily() { return HAVE_BUNDLED ? "Nunito" : "SansSerif"; }
    public static String displayFamily() { return HAVE_BUNDLED ? "Fredoka" : "SansSerif"; }

    public static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }
}
