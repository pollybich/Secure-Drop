package app.pollydrop;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DownloadTickets {
    public enum State {READY,SENDING,SENT,FAILED,EXPIRED}
    public static final class Ticket {
        final UUID id;
        final Instant expiresAt;
        volatile Instant retainUntil;
        final AtomicReference<State> state=new AtomicReference<>(State.READY);
        Ticket(UUID id,Instant now){this.id=id;expiresAt=now.plusSeconds(60);retainUntil=now.plusSeconds(300);}
    }
    public record Issued(String url,String statusUrl){}
    private final ConcurrentHashMap<String,Ticket> tickets=new ConcurrentHashMap<>();
    private final TransferService transfers;
    private final Clock clock;
    @Autowired public DownloadTickets(TransferService transfers){this(transfers,Clock.systemUTC());}
    DownloadTickets(TransferService transfers,Clock clock){this.transfers=transfers;this.clock=clock;}
    public synchronized Issued issue(String readToken,String password,String client){
        prune();
        if(tickets.size()>=1024)throw new ApiFailure(429,"SERVER_BUSY","Сервис занят. Попробуйте через минуту.");
        UUID id=transfers.authorizeDownload(readToken,password,client);
        String secret=Tokens.newToken();tickets.put(Tokens.hash(secret),new Ticket(id,clock.instant()));
        String url="/api/downloads/"+secret;
        return new Issued(url,url+"/status");
    }
    public Ticket begin(String secret){
        Ticket t=find(secret);
        if(!t.expiresAt.isAfter(clock.instant()) || !t.state.compareAndSet(State.READY,State.SENDING))throw new ApiFailure(410,"DOWNLOAD_EXPIRED","Нажмите «Получить файл» ещё раз.");
        return t;
    }
    public State status(String secret){
        Ticket t=find(secret);
        return t.state.get()==State.READY&&!t.expiresAt.isAfter(clock.instant())?State.EXPIRED:t.state.get();
    }
    public void finish(Ticket ticket,boolean completed){ticket.retainUntil=clock.instant().plusSeconds(120);ticket.state.set(completed?State.SENT:State.FAILED);}
    private Ticket find(String secret){
        Ticket t=tickets.get(Tokens.hash(secret));
        if(t==null)throw new ApiFailure(410,"DOWNLOAD_EXPIRED","Нажмите «Получить файл» ещё раз.");
        return t;
    }
    @Scheduled(fixedDelay=60000)
    public void prune(){Instant now=clock.instant();tickets.entrySet().removeIf(e->e.getValue().state.get()!=State.SENDING&&!e.getValue().retainUntil.isAfter(now));}
}
