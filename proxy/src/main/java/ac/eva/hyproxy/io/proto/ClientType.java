package ac.eva.hyproxy.io.proto;

public enum ClientType {
    GAME,
    EDITOR;


    public byte getId() {
        return (byte) this.ordinal();
    }

    public static ClientType getById(byte id) {
        ClientType[] values = ClientType.values();
        if (id < 0 || id >= values.length) {
            return null;
        }
        return values[id];
    }
}
