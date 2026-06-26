package ac.eva.hyproxy.io.proto;

import ac.eva.hyproxy.common.util.ProtocolUtil;
import com.nimbusds.jose.util.JSONObjectUtils;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.text.ParseException;
import java.util.Arrays;
import java.util.Map;

/**
 * A player's cosmetic appearance, mirroring the engine's {@code PlayerSkin} structure. The proxy
 * treats it as an opaque pass-through: each part is an optional id sourced from the signed identity
 * token's {@code profile.skin} claim and re-serialized into {@link ac.eva.hyproxy.io.packet.impl.auth.InsecurePlayerOptions}
 * so insecure-mode backends can render the player. The backend is responsible for validating the parts.
 *
 * <p>Wire layout (little-endian): nullBits(3, one bit per part in {@link #PART_KEYS} order), then 20
 * int32-LE offset slots (-1 when the part is absent) relative to the variable block at byte 83,
 * followed by each present part as a var-ascii string.
 *
 * @author santio
 */
@Slf4j
public class PlayerSkin {
    private static final int NULL_BITS_SIZE = 3;

    // order is significant: it defines each part's nullBits bit and offset slot, and must match the engine
    private static final String[] PART_KEYS = {
            "bodyCharacteristic", "underwear", "face", "eyes", "ears", "mouth", "facialHair", "haircut",
            "eyebrows", "pants", "overpants", "undertop", "overtop", "shoes", "headAccessory", "faceAccessory",
            "earAccessory", "skinFeature", "gloves", "cape"
    };

    private final @Nullable String[] parts;

    private PlayerSkin(@Nullable String[] parts) {
        this.parts = parts;
    }

    /**
     * Parses the {@code profile.skin} JSON carried in the identity token into a skin.
     * @param json the raw skin json, or null/empty when the player has no skin
     * @return the parsed skin, or null when absent or unparseable
     */
    public static @Nullable PlayerSkin fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        final Map<String, Object> skin;
        try {
            skin = JSONObjectUtils.parse(json);
        } catch (ParseException e) {
            log.warn("failed to parse skin json from identity token", e);
            return null;
        }

        final String[] parts = new String[PART_KEYS.length];
        for (int i = 0; i < PART_KEYS.length; i++) {
            if (skin.get(PART_KEYS[i]) instanceof String part) {
                parts[i] = part;
            }
        }

        return new PlayerSkin(parts);
    }

    public void serialize(ByteBuf buf) {
        final byte[] nullBits = new byte[NULL_BITS_SIZE];
        for (int i = 0; i < this.parts.length; i++) {
            if (this.parts[i] != null) {
                nullBits[i >> 3] |= (byte) (1 << (i & 7));
            }
        }

        buf.writeBytes(nullBits);

        final int slotsStart = buf.writerIndex();
        for (int i = 0; i < this.parts.length; i++) {
            buf.writeIntLE(0);
        }

        final int varsOffset = buf.writerIndex();
        for (int i = 0; i < this.parts.length; i++) {
            final int slot = slotsStart + i * Integer.BYTES;

            if (this.parts[i] == null) {
                buf.setIntLE(slot, -1);
                continue;
            }

            buf.setIntLE(slot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.parts[i]);
        }
    }

    @Override
    public String toString() {
        return "PlayerSkin" + Arrays.toString(this.parts);
    }
}
