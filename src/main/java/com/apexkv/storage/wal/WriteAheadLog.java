package com.apexkv.storage.wal;

import com.apexkv.core.DataRecord;
import com.apexkv.exception.StorageEngineException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only Write-Ahead Log (WAL) backed by {@link FileChannel}.
 * Guarantees zero data-loss crash durability by persisting mutations to disk
 * sequentially before modifying in-memory MemTables.
 */
public class WriteAheadLog implements AutoCloseable {
    private final Path logPath;
    private final FileChannel fileChannel;
    private final boolean syncOnWrite;
    private final AtomicLong bytesWritten;
    private final AtomicBoolean isClosed;

    public WriteAheadLog(Path logPath, boolean syncOnWrite) {
        this.logPath = Objects.requireNonNull(logPath, "logPath cannot be null");
        this.syncOnWrite = syncOnWrite;
        this.bytesWritten = new AtomicLong(0);
        this.isClosed = new AtomicBoolean(false);

        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            this.fileChannel = FileChannel.open(
                    logPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
            this.bytesWritten.set(fileChannel.size());
        } catch (IOException e) {
            throw new StorageEngineException("Failed to initialize WriteAheadLog at: " + logPath, e);
        }
    }

    /**
     * Appends a record to the write-ahead log.
     * Synchronized to guarantee sequential ordering of writes to the file channel.
     *
     * @param record the DataRecord to persist
     * @return the number of bytes written
     */
    public synchronized int append(DataRecord record) {
        ensureOpen();
        WalRecord walRecord = WalRecord.fromDataRecord(record);
        ByteBuffer buffer = walRecord.serialize();
        int bytes = buffer.remaining();

        try {
            while (buffer.hasRemaining()) {
                fileChannel.write(buffer);
            }
            if (syncOnWrite) {
                fileChannel.force(false);
            }
            bytesWritten.addAndGet(bytes);
            return bytes;
        } catch (IOException e) {
            throw new StorageEngineException("Failed to append record to WAL: " + logPath, e);
        }
    }

    /**
     * Forces all buffered WAL writes to the underlying physical storage media.
     */
    public synchronized void sync() {
        ensureOpen();
        try {
            fileChannel.force(false);
        } catch (IOException e) {
            throw new StorageEngineException("Failed to sync WAL to disk: " + logPath, e);
        }
    }

    public Path getLogPath() {
        return logPath;
    }

    public long getBytesWritten() {
        return bytesWritten.get();
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    private void ensureOpen() {
        if (isClosed.get()) {
            throw new IllegalStateException("WriteAheadLog is already closed: " + logPath);
        }
    }

    @Override
    public synchronized void close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                if (fileChannel.isOpen()) {
                    fileChannel.force(true);
                    fileChannel.close();
                }
            } catch (IOException e) {
                throw new StorageEngineException("Failed to close WAL file channel: " + logPath, e);
            }
        }
    }
}
