package net.theonicolaou.p2pvoice;

import android.content.Context;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class TestConnectionSocketClient extends TestConnectionSocket {
    private static final int reconnection_delay = 1000;
    private static final int connection_timeout = 5000;

    private final InetSocketAddress address;
    private boolean signal_shutdown = false, signal_reconnect = false;
    private final StatusListener listener;
    private final Handler main_thread;

    TestConnectionSocketClient(Context context, StatusListener listener, String host, int port) {
        this.listener = listener;
        address = new InetSocketAddress(host, port);
        main_thread = new Handler(context.getMainLooper());
    }

    @Override
    public void run() {
        boolean signal_shutdown = false;
        while (!signal_shutdown) {
            // Try to connect
            try {
                Log.i(getClass().getName(), "Trying to connect to " + address);
                Socket socket = new Socket();
                socket.connect(address, connection_timeout);
                Log.i(getClass().getName(), "Connected to " + address);

                // Connected, send file descriptor to listener
                ParcelFileDescriptor fd_parcel = ParcelFileDescriptor.fromSocket(socket);
                FileDescriptor fd = fd_parcel.getFileDescriptor();
                main_thread.post(() -> {
                    listener.onIncomingConnection(fd);
                });

                // Wait for reconnect or shutdown request
                synchronized (this) {
                    signal_shutdown = this.signal_shutdown;
                    Log.d(getClass().getName(), "signal_shutdown=" + signal_shutdown);
                    Log.d(getClass().getName(), "signal_reconnect=" + signal_reconnect);
                    if (signal_reconnect) {
                        signal_reconnect = false;
                    } else if (!signal_shutdown) {
                        Log.d(getClass().getName(), "Sleeping");
                        try {
                            wait();
                        } catch (InterruptedException ignored) {}
                    }
                }

                Log.i(getClass().getName(), "Closing connection to " + address);

                try {
                    socket.shutdownInput();
                } catch (IOException ignored) {}
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {}
                fd_parcel.close();
                socket.close();
            } catch (SocketTimeoutException e) {
                Log.w(this.getClass().getName(), "Connection to " + address + " timed out.");
            } catch (IOException e) {
                Log.w(this.getClass().getName(), "Connection to " + address + " failed: " + e.getMessage());
            }

            // Connection closed or failed, check if thread is stopping
            synchronized (this) {
                signal_shutdown = this.signal_shutdown;
                if (signal_shutdown) {
                    // Stop
                    break;
                } else {
                    // Wait and retry
                    try {
                        wait(reconnection_delay);
                    } catch (InterruptedException ignored) {}
                    // Check if stopping again, cause it might have changed during sleep
                    signal_shutdown = this.signal_shutdown;
                }
                signal_reconnect = false;
            }
        }
        Log.d(this.getClass().getName(), "Stopped socket thread.");
    }

    @Override
    public synchronized void reconnect() {
        Log.d(this.getClass().getName(), "Reconnect signal received.");
        signal_reconnect = true;
        notify();
    }

    @Override
    public synchronized void shutdown() {
        Log.d(this.getClass().getName(), "Shutdown signal received.");
        signal_shutdown = true;
        interrupt();
    }
}
