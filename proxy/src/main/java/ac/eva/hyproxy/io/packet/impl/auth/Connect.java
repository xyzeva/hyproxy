package ac.eva.hyproxy.io.packet.impl.auth;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.proto.ClientType;
import ac.eva.hyproxy.io.proto.HostAddress;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.common.util.VarIntUtil;

import java.nio.charset.StandardCharsets;

@Getter
@RequiredArgsConstructor
@ToString
public class Connect implements Packet {
    private final int protocolCrc;
    private final int protocolBuildNumber;
    private final String clientVersion;
    private final ClientType clientType;
    private final @Nullable String identityToken;
    private final @Nullable String language;
    private final byte @Nullable [] referralData;
    private final @Nullable HostAddress referralSource;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static Connect deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        int protocolCrc = buf.readIntLE();
        int protocolBuildNumber = buf.readIntLE();

        byte[] clientVersionBytes = new byte[20];
        buf.readBytes(clientVersionBytes);
        String clientVersion = new String(clientVersionBytes, StandardCharsets.US_ASCII);

        ClientType clientType = ClientType.getById(buf.readByte());

        int identityTokenOffset = buf.readIntLE();
        int languageOffset = buf.readIntLE();
        int referralDataOffset = buf.readIntLE();
        int referralSourceOffset = buf.readIntLE();
        int varsOffset = buf.readerIndex();

        int readViaOffsets = 0;

        String identityToken = null;

        if ((nullBits & 0x1) != 0) {
            int offset = varsOffset + identityTokenOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 8192);
            identityToken = varString.left();
            readViaOffsets += varString.right();
        }

        int offset = varsOffset + languageOffset;
        Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 16);
        String language = varString.left();
        readViaOffsets += varString.right();

        byte[] referralData = null;

        if ((nullBits & 0x2) != 0) {
            offset = varsOffset + referralDataOffset;

            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.peek(buf, offset);

            if (length < 0 || length > 4096) {
                throw new IllegalStateException("referral data is larger then 4096 bytes or below zero");
            }

            referralData = new byte[length];
            buf.getBytes(offset + varIntLength, referralData);

            readViaOffsets += varIntLength + length;
        }

        HostAddress referralSource = null;

        if ((nullBits & 0x4) != 0) {
            offset = varsOffset + referralSourceOffset;

            Pair<HostAddress, Integer> hostAddressPair = HostAddress.deserialize(buf, offset);
            referralSource = hostAddressPair.left();
            readViaOffsets += hostAddressPair.right();
        }


        buf.readerIndex(varsOffset + readViaOffsets);
        return new Connect(protocolCrc, protocolBuildNumber, clientVersion, clientType, identityToken, language, referralData, referralSource);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;

        if (this.identityToken != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        if (this.referralData != null) {
            nullBits = (byte) (nullBits | 0x2);
        }

        if (this.referralSource != null) {
            nullBits = (byte) (nullBits | 0x4);
        }

        buf.writeByte(nullBits);
        buf.writeIntLE(this.protocolCrc);
        buf.writeIntLE(this.protocolBuildNumber);
        
        // clientVersion is a fixed 20-byte ASCII field; pad/truncate to exactly 20 bytes so the
        // fixed block stays aligned (must match deserialize's US_ASCII 20-byte read).
        byte[] clientVersionBytes = new byte[20];
        byte[] clientVersionSrc = this.clientVersion.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(clientVersionSrc, 0, clientVersionBytes, 0, Math.min(clientVersionSrc.length, 20));
        buf.writeBytes(clientVersionBytes);
        buf.writeByte(this.clientType.getId());

        int identityTokenOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int languageOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int referralDataOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int referralSourceOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);

        int varsOffset = buf.writerIndex();

        if (this.identityToken != null) {
            buf.setIntLE(identityTokenOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.identityToken);
        }

        buf.setIntLE(languageOffsetSlot, buf.writerIndex() - varsOffset);
        ProtocolUtil.writeVarString(buf, this.language != null ? this.language : "");

        if (this.referralData != null) {
            buf.setIntLE(referralDataOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.referralData.length);
            buf.writeBytes(this.referralData);
        }

        if (this.referralSource != null) {
            buf.setIntLE(referralSourceOffsetSlot, buf.writerIndex() - varsOffset);
            this.referralSource.serialize(buf);
        }
    }
}
