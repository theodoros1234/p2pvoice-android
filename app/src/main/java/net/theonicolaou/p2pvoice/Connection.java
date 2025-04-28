package net.theonicolaou.p2pvoice;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public abstract class Connection {
    public static final int MSG_SIZE_MAX = 2 * 1024 * 1024;
    protected static final int PIPE_OUT_CAPACITY = 130;

    // Used to signal new connections
    public interface StatusListener {
        void onConnect();
        void onDisconnect();
        void onError(Exception e);
        void onVideoStop();
        void onVideoStart(int degrees);
        void onEndCall();
    }

    public static class InvalidMessage extends IllegalArgumentException {}
    public static class ConnectionClosed extends IOException {}

    // Constants for packet types
    public static final int DATA_VIDEO = 0;
    public static final int DATA_AUDIO = 1;
    public static final int DATA_VIDEO_STOP = 2;
    public static final int DATA_VIDEO_START_90 = 3;
    public static final int DATA_VIDEO_START_270 = 4;
    public static final int DATA_END_CALL = 5;

    public abstract void start();
    public abstract void stop();

    public abstract @NotNull ConnectionMessagePipe getOutgoingMessagePipe();
    public abstract void setIncomingMessagePipe(int message_type, ConnectionMessagePipe pipe);
}
