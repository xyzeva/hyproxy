package ac.eva.hyproxy.io.proto.message;

import ac.eva.hyproxy.common.util.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import org.jspecify.annotations.NonNull;

public record FormattedMessageImage(
        @NonNull String filePath,
        int width,
        int height
) {
    public static Pair<FormattedMessageImage, Integer> deserialize(ByteBuf buf, int offset) {
        int width = buf.getIntLE(offset);
        int height = buf.getIntLE(offset + 4);
        Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset + 8, 4096);

        return Pair.of(new FormattedMessageImage(
                varString.left(),
                width,
                height
        ), 8 + varString.right());
    }

    public void serialize(ByteBuf buf) {
        buf.writeIntLE(this.width());
        buf.writeIntLE(this.height());
        ProtocolUtil.writeVarString(buf, this.filePath());
    }
}
