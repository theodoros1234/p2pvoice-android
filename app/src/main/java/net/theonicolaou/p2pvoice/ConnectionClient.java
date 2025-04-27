package net.theonicolaou.p2pvoice;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ConnectionClient extends Connection {
    private static final String TAG = "ConnectionClient";
    private static final int connection_timeout = 5000;
    private static final int reconnection_delay = 1000;

    private final InetSocketAddress address;
    private boolean signal_shutdown = false;
    private final StatusListener listener;
    private final Handler main_thread;
    private Socket socket = null;
    private Thread thread;
    private final ConnectionMessagePipe pipe_out;
    private ConnectionMessagePipe pipe_in_video = null, pipe_in_audio = null;

    ConnectionClient(Context context, StatusListener listener, String host, int port) {
        this.listener = listener;
        address = new InetSocketAddress(host, port);
        main_thread = new Handler(context.getMainLooper());
        pipe_out = new ConnectionMessagePipe(Connection.PIPE_OUT_CAPACITY, false);
        pipe_out.openSender();
    }

    @Override
    public @NotNull ConnectionMessagePipe getOutgoingMessagePipe() {
        return pipe_out;
    }

    private void threadIncoming() {
        boolean signal_shutdown = false;
        while (!signal_shutdown) {
            InputStream socket_reader;
            OutputStream socket_writer;

            // Try to connect
            try {
                Log.i(TAG, "Trying to connect to " + address);
                Socket socket = new Socket();
                socket.connect(address, connection_timeout);
                Log.i(TAG, "Connected to " + address);
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
                Log.w(TAG, "Connection to " + address + " timed out.");
                main_thread.post(() -> listener.onError(e));
                synchronized (this) {
                    // Check if shutting down and pause before reconnecting
                    if (!this.signal_shutdown) {
                        try {
                            wait(reconnection_delay);
                        } catch (InterruptedException ignored) {}
                    }
                    signal_shutdown = this.signal_shutdown;
                }
                continue;
            } catch (IOException e) {
                Log.w(TAG, "Connection to " + address + " failed: " + e.getMessage());
                main_thread.post(() -> listener.onError(e));
                synchronized (this) {
                    // Check if shutting down and pause before reconnecting
                    if (!this.signal_shutdown) {
                        try {
                            wait(reconnection_delay);
                        } catch (InterruptedException ignored) {}
                    }
                    signal_shutdown = this.signal_shutdown;
                }
                continue;
            }

            // Open pipes
            pipe_out.openReceiver();
            if (pipe_in_video != null)
                pipe_in_video.openSender();
            if (pipe_in_audio != null)
                pipe_in_audio.openSender();

            // Connected
            main_thread.post(listener::onConnect);

            // Start sender thread
            final OutputStream socket_writer_lambda = socket_writer;
            Thread thread_out = new Thread(() -> threadOutgoing(socket_writer_lambda));
            thread_out.start();

            try {
                // Start reading data
                // TODO: Make a buffer that everything gets stored into
                ByteBuffer param_parser = ByteBuffer.wrap(new byte[8]).order(ByteOrder.BIG_ENDIAN);
                while (true) {
                    // Get message type and size
                    int type, size;
                    param_parser.rewind();

                    int header_bytes_read = 0, header_bytes_read_total = 0;
                    while (header_bytes_read_total < 8) {
                        header_bytes_read = socket_reader.read(param_parser.array(), header_bytes_read_total, 8 - header_bytes_read_total);
                        if (header_bytes_read <= 0)
                            break;  // Connection closed
                        header_bytes_read_total += header_bytes_read;
                    }
                    if (header_bytes_read <= 0)
                        break;

                    type = param_parser.getInt();
                    size = param_parser.getInt();
                    // TODO: Check for invalid type

                    if (size < 0) {
                        // Terminate connection on negative size message
                        Log.e(TAG, "Terminating connection due to negative size message (" + size + ")");
                        main_thread.post(() -> listener.onError(new InvalidMessage()));
                        break;
                    } else if (size > MSG_SIZE_MAX) {
                        // Ignore oversized messages
                        // TODO: Throw exception when there are too many consecutive oversized messages
                        Log.w(TAG, "Skipping oversized message (" + (size / 1024) + " KB)");

                        long bytes_read, bytes_read_total = 0;
                        do {
                            bytes_read = socket_reader.skip(size);
                            bytes_read_total += bytes_read;
                            if (bytes_read == 0) {
                                // return of 0 can mean many things, check if it's EOF
                                if (socket_reader.read() <= 0)
                                    bytes_read = -1;
                                else
                                    bytes_read_total++;
                            }
                        } while (bytes_read_total < size && bytes_read > 0);

                        if (bytes_read <= 0)
                            break;  // Connection closed
                    } else {
                        // Read message into byte array
                        byte[] message = new byte[size];
                        int bytes_read, bytes_read_total = 0;
                        do {
                            bytes_read = socket_reader.read(message, bytes_read_total, size - bytes_read_total);
                            bytes_read_total += bytes_read;
                        } while (bytes_read_total < size && bytes_read > 0);
                        if (bytes_read <= 0)
                            break;

                        // Send message to message pipe
                        switch (type) {
                            case Connection.DATA_VIDEO:
                                if (pipe_in_video != null)
                                    pipe_in_video.send(type, message);
                                break;

                            case Connection.DATA_AUDIO:
                                if (pipe_in_audio != null)
                                    pipe_in_audio.send(type, message);
                                break;

                            default:
                                Log.w(TAG, "Ignoring message of type=" + type + " size=" + size);
                        }
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "IOException while receiving from " + address + ": " + e.getMessage());
                main_thread.post(() -> listener.onError(e));
            }

            // Shut down and close socket
            main_thread.post(listener::onDisconnect);
            pipe_out.closeReceiver();
            if (pipe_in_video != null)
                pipe_in_video.closeSender();
            if (pipe_in_audio != null)
                pipe_in_audio.closeSender();
            try {
                thread_out.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            synchronized (this) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                this.socket = null;

                // Check if shutting down and pause before reconnecting
                if (!this.signal_shutdown) {
                    try {
                        wait(reconnection_delay);
                    } catch (InterruptedException ignored) {}
                }
                signal_shutdown = this.signal_shutdown;
            }
        }
        Log.d(TAG, "Stopped socket thread.");
    }

    private void threadOutgoing(OutputStream socket_writer) {
        ConnectionMessage message;
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        try {
            while (true) {
                message = pipe_out.receive();
                if (message == null)
                    break;
                header.rewind();
                header.putInt(message.type).putInt(message.data.length);

                socket_writer.write(header.array());
                socket_writer.write(message.data);
            }
        } catch (IOException ignored) {}
        pipe_out.closeReceiver();
    }

    @Override
    public synchronized void start() {
        if (thread != null)
            throw new IllegalStateException();
        signal_shutdown = false;
        thread = new Thread(this::threadIncoming);
        thread.start();
    }

    @Override
    public void stop() {
        Log.d(TAG, "Sending shutdown signal");
        synchronized (this) {
            pipe_out.closeReceiver();
            if (pipe_in_video != null)
                pipe_in_video.closeSender();
            if (pipe_in_audio != null)
                pipe_in_audio.closeSender();
            if (socket != null) {
                try {
                    socket.shutdownInput();
                    Log.d(TAG, "Socket I/O was shut down");
                } catch (IOException e) {
                    Log.w(TAG, "IOException when shutting down I/O: " + e.getMessage());
                }
            }
            signal_shutdown = true;
            notify();   // Interrupts reconnection delay
            Log.d(TAG, "Shutdown signal sent");
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Log.i(TAG, "Shutdown complete");
        thread = null;
    }

    @Override
    public void setIncomingMessagePipe(int message_type, ConnectionMessagePipe pipe) {
        switch (message_type) {
            case Connection.DATA_VIDEO:
                pipe_in_video = pipe;
                break;

            case Connection.DATA_AUDIO:
                pipe_in_audio = pipe;
                break;

            default:
                throw new IllegalArgumentException("Message type " + message_type + " doesn't take a message pipe.");
        }
    }
}
