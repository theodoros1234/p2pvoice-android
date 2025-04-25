package net.theonicolaou.p2pvoice;

import java.io.IOException;

public abstract class Connection {
    public static final int MSG_SIZE_MAX = 2 * 1024 * 1024;

    // Used to signal new connections
    public interface StatusListener {
        void onConnect();
        void onDisconnect();
        void onError(Exception e);
        void onReceive(int type, byte[] data);
    }

    public static class InvalidMessage extends IllegalArgumentException {}
    public static class ConnectionClosed extends IOException {}

    // Constants for packet types
    public static final int DATA_UNKNOWN = -1;
    public static final int DATA_CONTROL = 0;
    public static final int DATA_VIDEO = 1;
    public static final int DATA_AUDIO = 2;

    public abstract void start();
    public abstract void stop();
    public abstract void send(int type, byte[] data) throws InvalidMessage, IOException;
}
