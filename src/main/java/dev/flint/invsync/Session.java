package dev.flint.invsync;

import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class Session {
    enum Phase { RESERVING, PENDING, LOADING, READY, CLOSING, FAILED, CLOSED }
    final UUID player;
    final long created=System.nanoTime();
    volatile Phase phase=Phase.RESERVING;
    volatile UUID token;
    volatile long revision;
    volatile byte[] payload;
    volatile long verified=System.nanoTime();
    volatile Player bound;
    volatile io.papermc.paper.connection.PlayerLoginConnection connection;
    volatile boolean applied,waitingForRespawn;
    final AtomicBoolean saving=new AtomicBoolean(),heartbeating=new AtomicBoolean();
    private CompletableFuture<Void> tail=CompletableFuture.completedFuture(null);
    Session(UUID player){this.player=player;}
    synchronized CompletableFuture<Void> enqueue(Executor executor,Runnable work){
        tail=tail.handle((v,e)->null).thenRunAsync(work,executor);return tail;
    }
    boolean fresh(Options o){return System.nanoTime()-verified<TimeUnit.SECONDS.toNanos(o.verificationSeconds());}
}
