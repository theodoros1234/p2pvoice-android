package net.theonicolaou.p2pvoice;

import android.content.Context;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class TestConnectionSocketServer extends TestConnectionSocket {
    private final InetSocketAddress address;
    private boolean signal_shutdown = false, signal_reconnect = false;
    private final StatusListener listener;
    private final Handler main_thread;

    TestConnectionSocketServer(@NotNull Context context, @NotNull StatusListener listener, String host, int port) {
        this.listener = listener;
        address = new InetSocketAddress(host, port);
        main_thread = new Handler(context.getMainLooper());
    }

    @Override
    public void run() {
        boolean signal_shutdown = false;

        // Initialize and bind server socket
        ServerSocket socket_server;
        Socket socket;
        try {
            socket_server = new ServerSocket();
            socket_server.bind(address, 1);
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "Failed to bind server socket to " + address);
            return;
        }

        while (!signal_shutdown) {
            // Accept connection from next client
            try {
                Log.i(getClass().getName(), "Listening for new connection on " + address);
                socket = socket_server.accept();
                Log.i(getClass().getName(), "Incoming connection from " + socket.getInetAddress());

                // Connected, send file descriptor to listener
                ParcelFileDescriptor fd_parcel = ParcelFileDescriptor.fromSocket(socket);
                FileDescriptor fd = fd_parcel.getFileDescriptor();
                main_thread.post(() -> {
                    listener.onIncomingConnection(fd);
                });

                // Wait for reconnect or shutdown request
                synchronized (this) {
                    signal_shutdown = this.signal_shutdown;
                    if (signal_reconnect) {
                        signal_reconnect = false;
                    } else if (!signal_shutdown) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {}
                    }
                }

                Log.i(getClass().getName(), "Closing connection from " + socket.getInetAddress());

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
                signal_reconnect = false;
            }
        }

        try {
            socket_server.close();
        } catch (IOException ignored) {}
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
