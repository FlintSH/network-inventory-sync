package dev.flint.invsync;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class SessionTest {
    @Test void operationsAreSerialEvenOnParallelPoolAndRecoverAfterFailedOperation()throws Exception{
        Session s=new Session(UUID.randomUUID());ExecutorService executor=Executors.newFixedThreadPool(4);List<Integer> order=Collections.synchronizedList(new ArrayList<>());
        try{for(int i=0;i<100;i++){int n=i;s.enqueue(executor,()->{order.add(n);if(n==20)throw new IllegalStateException();});}s.enqueue(executor,()->{}).get(5,TimeUnit.SECONDS);assertEquals(100,order.size());for(int i=0;i<100;i++)assertEquals(i,order.get(i));}finally{executor.shutdownNow();}
    }
    @Test void rejectionIsVisibleAndFreshnessUsesMonotonicTime(){Session s=new Session(UUID.randomUUID());Executor executor=work->{throw new RejectedExecutionException();};assertTrue(s.enqueue(executor,()->{}).isCompletedExceptionally());assertTrue(s.fresh(Fixtures.options()));s.verified=System.nanoTime()-TimeUnit.MINUTES.toNanos(5);assertFalse(s.fresh(Fixtures.options()));}
}
