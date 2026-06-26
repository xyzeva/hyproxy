package ac.eva.hyproxy.io.proto.message;

import ac.eva.hyproxy.io.proto.MaybeBool;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import lombok.*;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.proto.param.ParamValue;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.common.util.VarIntUtil;

import java.util.HashMap;
import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Getter
@Setter
public class FormattedMessage {
    private @Nullable String rawText;
    private @Nullable String messageId;
    private FormattedMessage @Nullable [] children;
    private @Nullable Map<String, ParamValue> params;
    private @Nullable Map<String, FormattedMessage> messageParams;
    private @Nullable String color;
    private MaybeBool bold = MaybeBool.NULL;
    private MaybeBool italic = MaybeBool.NULL;
    private MaybeBool monospace = MaybeBool.NULL;
    private MaybeBool underlined = MaybeBool.NULL;
    private @Nullable String link;
    private @Nullable FormattedMessageImage image;
    private boolean markupEnabled;

    public static FormattedMessage deserialize(ByteBuf buf) {
        byte nullBits0 = buf.readByte();
        byte nullBits1 = buf.readByte();

        byte boldByte = buf.readByte();
        byte italicByte = buf.readByte();
        byte monospaceByte = buf.readByte();
        byte underlinedByte = buf.readByte();
        boolean markupEnabled = buf.readByte() != 0;

        MaybeBool bold = (nullBits0 & 0x1) != 0 ? MaybeBool.fromBool(boldByte != 0) : MaybeBool.NULL;
        MaybeBool italic = (nullBits0 & 0x2) != 0 ? MaybeBool.fromBool(italicByte != 0) : MaybeBool.NULL;
        MaybeBool monospace = (nullBits0 & 0x4) != 0 ? MaybeBool.fromBool(monospaceByte != 0) : MaybeBool.NULL;
        MaybeBool underlined = (nullBits0 & 0x8) != 0 ? MaybeBool.fromBool(underlinedByte != 0) : MaybeBool.NULL;

        int rawTextOffset = buf.readIntLE();
        int messageIdOffset = buf.readIntLE();
        int childrenOffset = buf.readIntLE();
        int paramsOffset = buf.readIntLE();
        int messageParamsOffset = buf.readIntLE();
        int colorOffset = buf.readIntLE();
        int linksOffset = buf.readIntLE();
        int imageOffset = buf.readIntLE();

        int varsOffset = buf.readerIndex();

        int readViaOffsets = 0;

        String rawText = null;
        if ((nullBits0 & 0x10) != 0) {
            int offset = varsOffset + rawTextOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 128);
            rawText = varString.left();
            readViaOffsets += varString.right();
        }

        String messageId = null;
        if ((nullBits0 & 0x20) != 0) {
            int offset = varsOffset + messageIdOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 128);
            messageId = varString.left();
            readViaOffsets += varString.right();
        }

        FormattedMessage[] children = null;
        if ((nullBits0 & 0x40) != 0) {
            int oldOffset = buf.readerIndex();

            int offset = varsOffset + childrenOffset;
            buf.readerIndex(offset);

            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.read(buf);
            if (length < 0 || length > 128) {
                throw new IllegalArgumentException("too many children in formatted message");
            }

            readViaOffsets += varIntLength;

            children = new FormattedMessage[length];

            for (int i = 0; i < length; i++) {
                int oldChildrenOffset = buf.readerIndex();
                children[i] = FormattedMessage.deserialize(buf);
                readViaOffsets += buf.readerIndex() - oldChildrenOffset;
            }

            buf.readerIndex(oldOffset);
        }

        Map<String, ParamValue> params = null;
        if ((nullBits0 & 0x80) != 0) {
            int oldOffset = buf.readerIndex();
            int offset = varsOffset + paramsOffset;

            buf.readerIndex(offset);
            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.read(buf);

            if (length < 0 || length > 128) {
                throw new IllegalArgumentException("too many params in formatted message");
            }

            readViaOffsets += varIntLength;
            params = new HashMap<>();

            for (int i = 0; i < length; i++) {
                int oldParamOffset = buf.readerIndex();
                String key = ProtocolUtil.readVarString(buf, 128);
                ParamValue value = ParamValue.deserialize(buf);

                params.put(key, value);
                readViaOffsets += buf.readerIndex() - oldParamOffset;
            }

            buf.readerIndex(oldOffset);
        }

        Map<String, FormattedMessage> messageParams = null;

        if ((nullBits1 & 0x1) != 0) {
            int oldOffset = buf.readerIndex();
            int offset = varsOffset + messageParamsOffset;

            buf.readerIndex(offset);
            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.read(buf);

            if (length < 0 || length > 128) {
                throw new IllegalArgumentException("too many message params in formatted message");
            }

            readViaOffsets += varIntLength;
            messageParams = new HashMap<>();

            for (int i = 0; i < length; i++) {
                int oldParamOffset = buf.readerIndex();
                String key = ProtocolUtil.readVarString(buf, 128);
                FormattedMessage value = FormattedMessage.deserialize(buf);

                messageParams.put(key, value);
                readViaOffsets += buf.readerIndex() - oldParamOffset;
            }

            buf.readerIndex(oldOffset);
        }

        String color = null;
        if ((nullBits1 & 0x2) != 0) {
            int offset = varsOffset + colorOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 32);
            color = varString.left();
            readViaOffsets += varString.right();
        }

        String link = null;
        if ((nullBits1 & 0x4) != 0) {
            int offset = varsOffset + linksOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 1024);
            link = varString.left();
            readViaOffsets += varString.right();
        }

        FormattedMessageImage image = null;
        if ((nullBits1 & 0x8) != 0) {
            int offset = varsOffset + imageOffset;
            Pair<FormattedMessageImage, Integer> pair = FormattedMessageImage.deserialize(buf, offset);
            image = pair.left();
            readViaOffsets += pair.right();
        }

        buf.readerIndex(varsOffset + readViaOffsets);
        return new FormattedMessage(
                rawText,
                messageId,
                children,
                params,
                messageParams,
                color,
                bold,
                italic,
                monospace,
                underlined,
                link,
                image,
                markupEnabled
        );
    }

    public void serialize(ByteBuf buf) {
        byte nullBits0 = 0;
        byte nullBits1 = 0;

        if (this.bold != MaybeBool.NULL) {
            nullBits0 = (byte) (nullBits0 | 0x1);
        }

        if (this.italic != MaybeBool.NULL) {
            nullBits0 = (byte) (nullBits0 | 0x2);
        }

        if (this.monospace != MaybeBool.NULL) {
            nullBits0 = (byte) (nullBits0 | 0x4);
        }

        if (this.underlined != MaybeBool.NULL) {
            nullBits0 = (byte) (nullBits0 | 0x8);
        }

        if (this.rawText != null) {
            nullBits0 = (byte) (nullBits0 | 0x10);
        }

        if (this.messageId != null) {
            nullBits0 = (byte) (nullBits0 | 0x20);
        }

        if (this.children != null) {
            nullBits0 = (byte) (nullBits0 | 0x40);
        }

        if (this.params != null) {
            nullBits0 = (byte) (nullBits0 | 0x80);
        }

        if (this.messageParams != null) {
            nullBits1 = (byte) (nullBits1 | 0x1);
        }

        if (this.color != null) {
            nullBits1 = (byte) (nullBits1 | 0x2);
        }

        if (this.link != null) {
            nullBits1 = (byte) (nullBits1 | 0x4);
        }

        if (this.image != null) {
            nullBits1 = (byte) (nullBits1 | 0x8);
        }

        buf.writeByte(nullBits0);
        buf.writeByte(nullBits1);
        buf.writeByte(this.bold == MaybeBool.TRUE ? 1 : 0);
        buf.writeByte(this.italic == MaybeBool.TRUE ? 1 : 0);
        buf.writeByte(this.monospace == MaybeBool.TRUE ? 1 : 0);
        buf.writeByte(this.underlined == MaybeBool.TRUE ? 1 : 0);
        buf.writeByte(this.markupEnabled ? 1 : 0);

        int rawTextOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int messageIdOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int childrenOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int paramsOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int messageParamsOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int colorOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int linkOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int imageOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);

        int varsOffset = buf.writerIndex();

        if (this.rawText != null) {
            buf.setIntLE(rawTextOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.rawText);
        }

        if (this.messageId != null) {
            buf.setIntLE(messageIdOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.messageId);
        }

        if (this.children != null) {
            buf.setIntLE(childrenOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.children.length);

            for (FormattedMessage child : this.children) {
                child.serialize(buf);
            }
        }

        if (this.params != null) {
            buf.setIntLE(paramsOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.params.size());

            for (Map.Entry<String, ParamValue> entry : this.params.entrySet()) {
                ProtocolUtil.writeVarString(buf, entry.getKey());
                entry.getValue().serializeWithTypeId(buf);
            }
        }

        if (this.messageParams != null) {
            buf.setIntLE(messageParamsOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.messageParams.size());

            for (Map.Entry<String, FormattedMessage> entry : this.messageParams.entrySet()) {
                ProtocolUtil.writeVarString(buf, entry.getKey());
                entry.getValue().serialize(buf);
            }
        }

        if (this.color != null) {
            buf.setIntLE(colorOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.color);
        }

        if (this.link != null) {
            buf.setIntLE(linkOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.link);
        }

        if (this.image != null) {
            buf.setIntLE(imageOffsetSlot, buf.writerIndex() - varsOffset);
            this.image.serialize(buf);
        }
    }
}
