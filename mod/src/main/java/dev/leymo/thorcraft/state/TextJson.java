package dev.leymo.thorcraft.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.Optional;

/** Text -> [{t:"hello", c:"#FF5555", b:1, it:1}, ...] so the panel can show colored names and lore. */
public final class TextJson {
    private TextJson() {}

    public static JsonArray segs(Text text) {
        JsonArray arr = new JsonArray();
        if (text == null) return arr;
        StringVisitable.StyledVisitor<Object> visitor = (style, str) -> {
            if (!str.isEmpty()) {
                JsonObject s = new JsonObject();
                s.addProperty("t", str);
                TextColor c = style.getColor();
                if (c != null) s.addProperty("c", String.format("#%06X", c.getRgb() & 0xFFFFFF));
                if (style.isBold()) s.addProperty("b", 1);
                if (style.isItalic()) s.addProperty("it", 1);
                if (style.isStrikethrough()) s.addProperty("st", 1);
                if (style.isUnderlined()) s.addProperty("u", 1);
                if (style.isObfuscated()) s.addProperty("ob", 1);
                arr.add(s);
            }
            return Optional.empty();
        };
        text.visit(visitor, Style.EMPTY);
        return arr;
    }

    /** Same as segs(Text) for already-wrapped lines (Minecraft "OrderedText"), keeping colours. */
    public static JsonArray segs(net.minecraft.text.OrderedText text) {
        JsonArray arr = new JsonArray();
        if (text == null) return arr;
        StringBuilder run = new StringBuilder();
        Style[] runStyle = {null};
        text.accept((index, style, codePoint) -> {
            if (runStyle[0] != null && !style.equals(runStyle[0])) {
                arr.add(seg(run.toString(), runStyle[0]));
                run.setLength(0);
            }
            runStyle[0] = style;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (run.length() > 0 && runStyle[0] != null) arr.add(seg(run.toString(), runStyle[0]));
        return arr;
    }

    private static JsonObject seg(String str, Style style) {
        JsonObject s = new JsonObject();
        s.addProperty("t", str);
        TextColor c = style.getColor();
        if (c != null) s.addProperty("c", String.format("#%06X", c.getRgb() & 0xFFFFFF));
        if (style.isBold()) s.addProperty("b", 1);
        if (style.isItalic()) s.addProperty("it", 1);
        return s;
    }

    public static String plain(Object o) {
        if (o == null) return "";
        if (o instanceof Text t) return t.getString();
        return String.valueOf(o);
    }
}
