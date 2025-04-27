package net.theonicolaou.p2pvoice;

public class ConnectionMessage {
    public int type;
    public byte[] data;

    ConnectionMessage(int type, byte[] data) {
        this.type = type;
        this.data = data;
    }
}
