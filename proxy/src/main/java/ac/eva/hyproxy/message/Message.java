package ac.eva.hyproxy.message;

import ac.eva.hyproxy.io.proto.param.type.*;
import com.nimbusds.jose.util.ArrayUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;
import lombok.experimental.Delegate;
import ac.eva.hyproxy.io.proto.message.FormattedMessage;
import ac.eva.hyproxy.io.proto.MaybeBool;
import ac.eva.hyproxy.util.ColorUtil;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * a nicer interface to work with for hytale's protocol format for messages, {@link FormattedMessage}
 */
public class Message {
    @Delegate
    @Getter
    private final FormattedMessage formatted;

    protected Message() {
        this.formatted = new FormattedMessage();
    }
    public Message(String message, boolean i18n) {
        this();
        if (i18n) {
            this.formatted.setMessageId(message);
            return;
        }

        this.formatted.setRawText(message);
    }

    public Message(FormattedMessage formatted) {
        this.formatted = formatted;
    }

    // note: this doesn't recurse through children, use MessageUtil.toAnsiString for that
    public String getAnsiMessage() {
        String rawText = this.formatted.getRawText();
        if (rawText != null) {
            return rawText;
        }

        String messageId = this.formatted.getMessageId();
        if (messageId == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(messageId);
        if (this.formatted.getParams() != null) {
            sb.append(this.formatted.getParams());
        }

        if (this.formatted.getMessageParams() != null) {
            for (Map.Entry<String, FormattedMessage> p : this.formatted.getMessageParams().entrySet()) {
                sb.append(p.getValue()).append("=").append(new Message(p.getValue()).getAnsiMessage());
            }
        }

        return sb.toString();
    }

    public List<Message> getChildren() {
        if (this.formatted.getChildren() == null) {
            return Collections.emptyList();
        } else {
            List<Message> children = new ObjectArrayList<>();

            for (FormattedMessage value : this.formatted.getChildren()) {
                children.add(new Message(value));
            }

            return children;
        }
    }

    public static Message empty() {
        return new Message();
    }

    public static Message raw(String message) {
        return new Message(message, false);
    }

    public static Message translation(String messageId) {
        return new Message(messageId, true);
    }

    public Message param(String key, String value) {
        if (this.formatted.getParams() == null) {
            this.formatted.setParams(new HashMap<>());
        }

        this.formatted.getParams().put(key, new StringParamValue(value));
        return this;
    }

    public Message param(String key, boolean value) {
        if (this.formatted.getParams() == null) {
            this.formatted.setParams(new HashMap<>());
        }

        this.formatted.getParams().put(key, new BoolParamValue(value));
        return this;
    }

    public Message param(String key, double value) {
        if (this.formatted.getParams() == null) {
            this.formatted.setParams(new HashMap<>());
        }

        this.formatted.getParams().put(key, new DoubleParamValue(value));
        return this;
    }

    public Message param(String key, int value) {
        if (this.formatted.getParams() == null) {
            this.formatted.setParams(new HashMap<>());
        }

        this.formatted.getParams().put(key, new IntParamValue(value));
        return this;
    }

    public Message param(String key, long value) {
        if (this.formatted.getParams() == null) {
            this.formatted.setParams(new HashMap<>());
        }

        this.formatted.getParams().put(key, new LongParamValue(value));
        return this;
    }

    public Message param(String key, float value) {
        if (this.formatted.getParams() == null) {
            this.formatted.setParams(new HashMap<>());
        }

        this.formatted.getParams().put(key, new DoubleParamValue(value));
        return this;
    }

    public Message param(String key, FormattedMessage value) {
        if (this.formatted.getMessageParams() == null) {
            this.formatted.setMessageParams(new HashMap<>());
        }

        this.formatted.getMessageParams().put(key, value);
        return this;
    }

    public Message bold(boolean bold) {
        this.formatted.setBold(MaybeBool.fromBool(bold));
        return this;
    }

    public Message italic(boolean italic) {
        this.formatted.setItalic(MaybeBool.fromBool(italic));
        return this;
    }

    public Message monospace(boolean monospace) {
        this.formatted.setMonospace(MaybeBool.fromBool(monospace));
        return this;
    }

    public Message color(String color) {
        this.formatted.setColor(color);
        return this;
    }

    public Message color(Color color) {
        this.formatted.setColor(ColorUtil.colorToHex(color));
        return this;
    }

    public Message link(String link) {
        this.formatted.setLink(link);
        return this;
    }

    public Message insert(Message child) {
        if (this.formatted.getChildren() == null) {
            this.formatted.setChildren(new FormattedMessage[] {child.formatted});
            return this;
        }
        this.formatted.setChildren(ArrayUtils.concat(this.formatted.getChildren(), new FormattedMessage[] {child.formatted}));
        return this;
    }

    public Message insert(String message) {
        this.insert(raw(message));
        return this;
    }
}
