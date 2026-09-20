package io.github.yufeiyufei888.hearthcrew.backend.numen;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Ordered, forced WAL. Workers receive immutable text only, never a world or player. */
final class ExecutionLog {
    private static final ExecutorService WRITER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"HearthCrew execution log");t.setDaemon(true);return t;});
    private final Path path;
    private CompletableFuture<Void> tail=CompletableFuture.completedFuture(null);
    ExecutionLog(Path path){this.path=path;}
    synchronized CompletableFuture<Void> append(String immutableJson){
        byte[] bytes=(immutableJson+"\n").getBytes(StandardCharsets.UTF_8);
        tail=tail.thenRunAsync(()->{
            long began=System.nanoTime();
            try {
                Files.createDirectories(path.getParent());
                try(var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
                    var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                }
            }catch(java.io.IOException error){throw new CompletionException(error);}
            if(System.nanoTime()-began>100_000_000L)System.out.println("HEARTHCREW_SLOW execution_log ms="+(System.nanoTime()-began)/1_000_000);
        },WRITER);
        return tail;
    }
    synchronized CompletableFuture<Void> barrier(){return tail;}
}
