package io.github.t9homas.lceviaproxy.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.raphimc.viaproxy.util.logging.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Gets the Java player's skin and turns it into a skin an LCE (TU19 era, 64x32 only) client can draw.
 */
public final class JavaSkin {

    /** TexturePacket stores the byte count in a signed short. */
    private static final int MAX_BYTES = Short.MAX_VALUE;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Skin(byte[] png, int skinId, String path) {
    }

    private JavaSkin() {
    }

    /**
     * @param setting "auto" (Mojang skin of the user name), "none", another player's name or a PNG file
     * @return the skin, or null when there is none (the LCE server then picks a default skin)
     */
    public static CompletableFuture<Skin> load(final String setting, final String userName) {
        if (setting.equalsIgnoreCase("none") || setting.equalsIgnoreCase("off")) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                final BufferedImage image;
                boolean slim = false;
                final File file = new File(setting);
                if (setting.toLowerCase().endsWith(".png") && file.isFile()) {
                    image = ImageIO.read(file);
                } else {
                    final String name = setting.equalsIgnoreCase("auto") || setting.isEmpty() ? userName : setting;
                    final JsonObject skin = mojangSkin(name);
                    if (skin == null) {
                        Logger.LOGGER.info("LCE: no Java skin found for " + name + ", the server picks a default skin");
                        return null;
                    }
                    slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                            && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
                    image = ImageIO.read(new ByteArrayInputStream(get(skin.get("url").getAsString())));
                }
                if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
                    Logger.LOGGER.warn("LCE: skin is not a 64x64 or 64x32 image, not sending it");
                    return null;
                }
                final byte[] png = encode(toLce(image, slim));
                if (png.length > MAX_BYTES) {
                    Logger.LOGGER.warn("LCE: skin PNG is too big (" + png.length + " bytes)");
                    return null;
                }
                // The path names the texture on the server (which caches by name), so derive it from the pixels:
                // a changed skin gets a new name. "ugcskin" ids live in bits 5..30.
                int id = java.util.Arrays.hashCode(png) & 0x7FFFFFE0;
                if (id == 0) id = 0x20;
                return new Skin(png, id, String.format("ugcskin%08X.png", id));
            } catch (Exception e) {
                Logger.LOGGER.warn("LCE: could not load the Java skin: " + e);
                return null;
            }
        });
    }

    private static JsonObject mojangSkin(final String name) throws Exception {
        final byte[] profile = get("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (profile == null) return null;
        final String uuid = JsonParser.parseString(new String(profile, StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString();
        final byte[] session = get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
        if (session == null) return null;
        for (var property : JsonParser.parseString(new String(session, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("properties")) {
            final JsonObject p = property.getAsJsonObject();
            if (!"textures".equals(p.get("name").getAsString())) continue;
            final JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(p.get("value").getAsString()), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("textures");
            return textures != null && textures.has("SKIN") ? textures.getAsJsonObject("SKIN") : null;
        }
        return null;
    }

    private static byte[] get(final String url) throws Exception {
        final HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return r.statusCode() == 200 ? r.body() : null;
    }

    private static byte[] encode(final BufferedImage image) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 64x64 -> 64x32

    static BufferedImage toLce(final BufferedImage src, final boolean slim) {
        final BufferedImage out = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        copy(src, 0, 0, 64, 32, out, 0, 0, false);
        if (src.getHeight() == 32) return out;
        if (slim) {
            slimArm(src, 40, 16, out, 40, 16, false);
        }
        // 64x32 skins have no second layer below the head: paint the jacket, right sleeve and right pants onto the base.
        copy(src, 16, 32, 24, 16, out, 16, 16, true);
        copy(src, 0, 32, 16, 16, out, 0, 16, true);
        if (slim) {
            slimArm(src, 40, 32, out, 40, 16, true);
        } else {
            copy(src, 40, 32, 16, 16, out, 40, 16, true);
        }
        return out;
    }

    /**
     * Slim (3 pixel) arm faces -> classic (4 pixel) arm faces, widening the 3 pixel faces by doubling the middle column.
     */
    private static void slimArm(final BufferedImage s, final int sx, final int sy, final BufferedImage d, final int dx, final int dy, final boolean over) {
        // top / bottom: rows 0..3
        widen(s, sx + 4, sy, 4, d, dx + 4, dy, over);
        widen(s, sx + 7, sy, 4, d, dx + 8, dy, over);
        // sides: rows 4..15
        copy(s, sx, sy + 4, 4, 12, d, dx, dy + 4, over);          // outer side
        widen(s, sx + 4, sy + 4, 12, d, dx + 4, dy + 4, over);     // front
        copy(s, sx + 7, sy + 4, 4, 12, d, dx + 8, dy + 4, over);   // inner side
        widen(s, sx + 11, sy + 4, 12, d, dx + 12, dy + 4, over);   // back
    }

    private static final int[] WIDEN = {0, 1, 1, 2};

    private static void widen(final BufferedImage s, final int sx, final int sy, final int h, final BufferedImage d, final int dx, final int dy, final boolean over) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < 4; x++) {
                put(d, dx + x, dy + y, s.getRGB(sx + WIDEN[x], sy + y), over);
            }
        }
    }

    private static void copy(final BufferedImage s, final int sx, final int sy, final int w, final int h, final BufferedImage d, final int dx, final int dy, final boolean over) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                put(d, dx + x, dy + y, s.getRGB(sx + x, sy + y), over);
            }
        }
    }

    private static void put(final BufferedImage d, final int x, final int y, final int argb, final boolean over) {
        if (!over) {
            d.setRGB(x, y, argb);
            return;
        }
        final int a = argb >>> 24;
        if (a == 0) return;
        if (a == 255) {
            d.setRGB(x, y, argb);
            return;
        }
        final int base = d.getRGB(x, y);
        int rgb = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            final int c = ((argb >> shift) & 0xFF) * a + ((base >> shift) & 0xFF) * (255 - a);
            rgb |= (c / 255) << shift;
        }
        d.setRGB(x, y, 0xFF000000 | rgb);
    }

}
