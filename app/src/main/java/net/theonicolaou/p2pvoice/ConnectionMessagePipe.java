package net.theonicolaou.p2pvoice;

import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionMessagePipe {
    private final String TAG = "ConnectionMessagePipe";

    public static final int RATE_UNDERFLOW = -1;
    public static final int RATE_NORMAL = 0;
    public static final int RATE_OVERFLOW = 1;

    private final Lock lock;
    private final Condition condition_receiver, condition_sender;
    private final Queue<ConnectionMessage> queue;
    private boolean open_receiver = false, open_sender = false;
    private final boolean drop_frames;
    private final int capacity;
    private int rate_hint;

    ConnectionMessagePipe(int capacity, boolean drop_frames) {
        this.capacity = capacity;
        this.drop_frames = drop_frames;
        lock = new ReentrantLock(true);
        condition_receiver = lock.newCondition();
        condition_sender = lock.newCondition();
        queue = new ArrayDeque<>(capacity);
    }

    public boolean send(int type, @NotNull byte[] data) throws Connection.InvalidMessage {
        if (data.length > Connection.MSG_SIZE_MAX)
            throw new Connection.InvalidMessage();

        lock.lock();
        try {
            if (queue.size() > capacity / 4)
                rate_hint = RATE_OVERFLOW;

            if (drop_frames && (queue.size() >= capacity)) {
                Log.w(TAG, "Dropping frame on full pipe, size=" + queue.size());
                return false;
            }
            while (open_receiver && open_sender && (queue.size() >= capacity)) {
                if (queue.size() >= capacity)
                    Log.w(TAG, "Waiting on full pipe, size=" + queue.size());
                condition_sender.awaitUninterruptibly();
            }

            if (open_receiver && open_sender) {
                queue.add(new ConnectionMessage(type, data));
                condition_receiver.signal();
                return true;
            } else {
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    public ConnectionMessage receive() {
        lock.lock();
        try {
            if (queue.isEmpty())
                rate_hint = RATE_UNDERFLOW;
            else if (queue.size() < capacity / 4)
                rate_hint = RATE_NORMAL;

            while (open_receiver && open_sender && queue.isEmpty()) {
                condition_receiver.awaitUninterruptibly();
            }

            if (open_receiver && !queue.isEmpty()) {
                condition_sender.signal();
                return queue.remove();
            } else {
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    public void openSender() {
        lock.lock();
        open_sender = true;
        lock.unlock();
    }

    public void openReceiver() {
        lock.lock();
        open_receiver = true;
        lock.unlock();
    }

    public void closeSender() {
        lock.lock();
        try {
            open_sender = false;
            condition_sender.signalAll();
            condition_receiver.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void closeReceiver() {
        lock.lock();
        try {
            open_receiver = false;
            queue.clear();
            condition_sender.signalAll();
            condition_receiver.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int getRateHint() {
        lock.lock();
        try {
            return rate_hint;
        } finally {
            lock.unlock();
        }
    }
}
