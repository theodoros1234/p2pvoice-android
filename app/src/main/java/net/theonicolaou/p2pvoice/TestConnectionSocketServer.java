package net.theonicolaou.p2pvoice;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TestConnectionSocketServer extends TestConnectionSocket {
    private static final String TAG = "TestConnectionSocketServer";

    private final InetSocketAddress address;
    private boolean signal_shutdown = false;
    private final StatusListener listener;
    private final Handler main_thread;
    private Socket socket = null;
    private ServerSocket socket_server = null;
    private OutputStream socket_writer = null;

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
        try {
            socket_server = new ServerSocket();
            socket_server.bind(address, 1);
            synchronized (this) {
                if (this.signal_shutdown) {
                    Log.i(TAG, "Closing server before any clients were connected");
                    socket_server.close();
                    return;
                }
                this.socket_server = socket_server;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind server socket to " + address);
            main_thread.post(() -> listener.onError(e));
            return;
        }

        while (!signal_shutdown) {
            InputStream socket_reader;

            // Try to connect
            try {
                Log.i(TAG,  "Listening for new connection on " + address);
                Socket socket = socket_server.accept();
                Log.i(TAG, "Incoming connection from " + socket.getInetAddress());
                synchronized (this) {
                    if (this.signal_shutdown) {
                        Log.i(TAG, "Closing connection before any I/O streams were used");
                        socket.close();
                        break;
                    }
                    socket_reader = socket.getInputStream();
                    socket_writer = socket.getOutputStream();
                    this.socket = socket;
                }
            } catch (SocketTimeoutException e) {
                Log.w(TAG, "Connection from " + address + " timed out.");
                continue;
            } catch (IOException e) {
                Log.w(TAG, "Connection from " + address + " failed: " + e.getMessage());
                main_thread.post(() -> listener.onError(e));
                continue;
            }

            // Connected
            main_thread.post(listener::onConnect); // TODO: should send the remote host's address

            try {
                // Start reading data
                // TODO: Make a buffer that everything gets stored into
                ByteBuffer param_parser = ByteBuffer.wrap(new byte[8]).order(ByteOrder.BIG_ENDIAN);
                while (true) {
                    // Get message type and size
                    int type, size;
                    param_parser.rewind();
                    if (socket_reader.read(param_parser.array()) == -1)
                        break;  // Connection closed
                    type = param_parser.getInt();
                    size = param_parser.getInt();
                    // TODO: Check for invalid type

                    if (size > MSG_SIZE_MAX) {
                        // Ignore oversized messages
                        // TODO: Throw exception when there are too many consecutive oversized messages
                        Log.w(TAG, "Skipping oversized message (" + (size / 1024) + " KB)");

                        long bytes_read, bytes_read_total = 0;
                        do {
                            bytes_read = socket_reader.skip(size);
                            bytes_read_total += bytes_read;
                            if (bytes_read == 0) {
                                // return of 0 can mean many things, check if it's EOF
                                if (socket_reader.read() == -1)
                                    bytes_read = -1;
                                else
                                    bytes_read_total++;
                            }
                        } while (bytes_read_total < size && bytes_read != -1);

                        if (bytes_read == -1)
                            break;  // Connection closed
                    } else {
                        // Read message into byte array
                        byte[] message = new byte[size];
                        int bytes_read, bytes_read_total = 0;
                        do {
                            bytes_read = socket_reader.read(message, bytes_read_total, size - bytes_read_total);
                            bytes_read_total += bytes_read;
                        } while (bytes_read_total < size && bytes_read != -1);

                        // Send message to callback
                        main_thread.post(() -> listener.onReceive(type, message));
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "IOException while receiving from " + address + ": " + e.getMessage());
                main_thread.post(() -> listener.onError(e));
            }

            // Shut down and close socket
            main_thread.post(listener::onDisconnect);
            try {
                socket.shutdownOutput();
            } catch (IOException ignored) {}
            synchronized (this) {
                socket_writer = null;
                try {
                    socket.close();
                } catch (IOException ignored) {}
                this.socket = null;

                // Check if shutting down
                signal_shutdown = this.signal_shutdown;
            }
        }

        Log.i(TAG, "Stopping server");
        synchronized (this) {
            try {
                if (!socket_server.isClosed())
                    socket_server.close();
            } catch (IOException ignored) {}
            this.socket_server = null;
        }
        Log.d(TAG, "Stopped socket thread.");
    }

    @Override
    public synchronized void start() {
        signal_shutdown = false;
        super.start();
    }

    @Override
    public void shutdown() {
        Log.d(TAG, "Sending shutdown signal");
        synchronized (this) {
            if (socket != null) {
                try {
                    if (!socket.isInputShutdown())
                        socket.shutdownInput();
                } catch (IOException ignored) {}
            }
            if (socket_server != null) {
                try {
                    if (!socket_server.isClosed())
                        socket_server.close();
                } catch (IOException ignored) {}
            }
            signal_shutdown = true;
            Log.d(TAG, "Shutdown signal sent");
        }
    }

    @Override
    public void send(int type, byte[] data) throws InvalidMessage, IOException {
        if (data.length > MSG_SIZE_MAX) {
            Log.e(TAG, "Can't send oversized message (" + (data.length / 1024) + " KB)");
            throw new InvalidMessage();
        }
        synchronized (this) {
            if (socket == null)
                throw new ConnectionClosed();
            byte[] header = new byte[8];
            ByteBuffer.wrap(header)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(type)
                    .putInt(data.length);
            try {
                socket_writer.write(header);
                socket_writer.write(data);
            } catch (IOException e) {
                Log.w(TAG, "IOException while sending message: " + e.getMessage());
                throw e;
            }
        }
    }
}
