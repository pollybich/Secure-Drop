package app.pollydrop;

import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class ExpiryScheduler {
    private final ContentCleanup cleanup;
    private final ScheduledThreadPoolExecutor timer;
    private final ConcurrentHashMap<UUID,ScheduledFuture<?>> pending=new ConcurrentHashMap<>();
    public ExpiryScheduler(ContentCleanup cleanup){
        this.cleanup=cleanup;
        timer=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"transfer-expiry");t.setDaemon(true);return t;});
        timer.setRemoveOnCancelPolicy(true);
    }
    public void schedule(UUID id,Instant expiresAt){
        pending.compute(id,(key,previous)->{
            if(previous!=null)previous.cancel(false);
            long delay=Math.max(0,Duration.between(Instant.now(),expiresAt).toNanos());
            return timer.schedule(()->{try{cleanup.discard(id);}finally{pending.remove(id);}},delay,TimeUnit.NANOSECONDS);
        });
    }
    public void cancel(UUID id){ScheduledFuture<?> previous=pending.remove(id);if(previous!=null)previous.cancel(false);}
    @PreDestroy public void close(){timer.shutdownNow();pending.clear();}
}
