package net.theonicolaou.p2pvoice;

import java.io.FileDescriptor;

public abstract class TestConnectionSocket extends Thread {
    // Used to signal new connections
    public interface StatusListener {
        void onIncomingConnection(FileDescriptor fd);
    }

    public abstract void reconnect();
    public abstract void shutdown();
}
