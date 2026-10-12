package app.pollydrop;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransferIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17.6-alpine");
    static final Path STORE;
    static { try { STORE=Files.createTempDirectory("pollydrop-test-"); } catch(IOException e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("app.master-key",()->Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)));p.add("app.storage-dir",STORE::toString);
    }
    @Autowired TransferService service;
    @Autowired DeliveryService delivery;
    @Autowired TransferRepository repository;
    @Autowired CipherStorage storage;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    TransferService.Created text(String password,int limit)throws IOException {return service.create(new TransferService.Options("TEXT","Привет 👋 <script>alert(1)</script>",null,600,limit,password),null,null);}
    Transfer row(TransferService.Created c){return repository.findByReadHash(Tokens.hash(c.readToken())).orElseThrow();}
    void failure(Runnable action,int status,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class,e->{assertThat(e.status).isEqualTo(status);assertThat(e.code).isEqualTo(code);});}

    @Test void realHttpTextLifecycleAndSeparateManagement()throws Exception {
        var options=new MockMultipartFile("options","options.json","application/json",json.writeValueAsBytes(new TransferService.Options("TEXT","Unicode: Привет 👋",null,600,1,null)));
        var result=http.perform(multipart("/api/transfers").file(options)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        var created=json.readValue(result.getResponse().getContentAsByteArray(),TransferService.Created.class);
        assertThat(created.readToken()).hasSize(43).isNotEqualTo(created.manageToken());
        http.perform(get("/api/transfers/"+created.readToken())).andExpect(status().isOk()).andExpect(jsonPath("downloads").value(0));
        http.perform(get("/api/manage/"+created.readToken())).andExpect(status().isNotFound());
        http.perform(post("/api/transfers/"+created.readToken()+"/download").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(content().bytes("Unicode: Привет 👋".getBytes(StandardCharsets.UTF_8)));
        http.perform(post("/api/transfers/"+created.readToken()+"/download").contentType("application/json").content("{}")).andExpect(status().isGone()).andExpect(jsonPath("code").value("EXHAUSTED"));
        assertThat(service.manageView(created.manageToken()).downloads()).isEqualTo(1);
    }
    @Test void binaryUploadPreservesBytesAndSanitizesName()throws Exception {
        byte[] bytes=new byte[180000];new Random(4).nextBytes(bytes);
        var created=service.create(new TransferService.Options("FILE",null,null,600,3,null),bytes,"../проект\n.bin");
        assertThat(created.transfer().name()).isEqualTo("проект.bin");
        var lease=service.reserve(created.readToken(),null,"test");var output=new ByteArrayOutputStream();delivery.send(lease,output);
        assertThat(output.toByteArray()).isEqualTo(bytes);assertThat(service.manageView(created.manageToken()).remaining()).isEqualTo(2);
    }
    @Test void encryptedStorageAndArgonPasswordAndTokenHashes()throws Exception {
        var c=text("secret",1);var t=row(c);
        assertThat(t.readHash).isEqualTo(Tokens.hash(c.readToken())).doesNotContain(c.readToken());assertThat(t.passwordHash).startsWith("$argon2id$").doesNotContain("secret");
        byte[] disk=Files.readAllBytes(STORE.resolve(t.id+".enc"));assertThat(new String(disk,StandardCharsets.UTF_8)).doesNotContain("Привет");
        assertThat(service.publicView(c.readToken()).name()).isEqualTo("Защищённая передача");
        failure(()->service.reserve(c.readToken(),null,"password-client"),401,"PASSWORD_REQUIRED");
        for(int i=0;i<5;i++)failure(()->service.reserve(c.readToken(),"wrong","password-client"),401,"WRONG_PASSWORD");
        failure(()->service.reserve(c.readToken(),"secret","password-client"),429,"RATE_LIMITED");
        var lease=service.reserve(c.readToken(),"secret","another-client");service.finish(lease,false);
    }
    @Test void interruptionReleasesReservationWithoutConsumingLimit()throws Exception {
        var c=service.create(new TransferService.Options("FILE",null,null,600,1,null),new byte[200000],"test.bin");
        var lease=service.reserve(c.readToken(),null,"test");
        OutputStream failed=new OutputStream(){int written;public void write(int value)throws IOException{if(++written>70000)throw new IOException("simulated disconnect");}};
        assertThatThrownBy(()->delivery.send(lease,failed)).isInstanceOf(IOException.class);
        assertThat(service.publicView(c.readToken()).state()).isEqualTo("AVAILABLE");assertThat(service.publicView(c.readToken()).downloads()).isZero();
        var output=new ByteArrayOutputStream();delivery.send(service.reserve(c.readToken(),null,"test"),output);
        assertThat(output.size()).isEqualTo(200000);assertThat(service.publicView(c.readToken()).downloads()).isEqualTo(1);
    }
    @Test void concurrentReservationsHaveOneWinner()throws Exception {
        var c=text(null,1);ExecutorService pool=Executors.newFixedThreadPool(100);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<100;i++)results.add(pool.submit(()->{start.await();try{service.reserve(c.readToken(),null,"test");return true;}catch(ApiFailure e){assertThat(e.code).isEqualTo("IN_PROGRESS");return false;}}));
            start.countDown();int wins=0;for(var f:results)if(f.get(20,TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);assertThat(service.manageView(c.manageToken()).downloads()).isZero();
            service.recover();assertThat(service.publicView(c.readToken()).state()).isEqualTo("AVAILABLE");
        } finally {pool.shutdownNow();}
    }
    @Test void revokeAndExpiryPreventReadAndCleanupErasesContent()throws Exception {
        var revoked=text(null,1);var id=row(revoked).id;
        http.perform(delete("/api/manage/"+revoked.manageToken())).andExpect(status().isNoContent());
        failure(()->service.reserve(revoked.readToken(),null,"test"),410,"REVOKED");service.cleanup();assertThat(Files.exists(STORE.resolve(id+".enc"))).isFalse();
        assertThat(service.manageView(revoked.manageToken()).state()).isEqualTo("REVOKED");
        var expired=text(null,1);var row=row(expired);row.expiresAt=Instant.now().minusSeconds(1);repository.saveAndFlush(row);
        failure(()->service.reserve(expired.readToken(),null,"test"),410,"EXPIRED");service.cleanup();assertThat(Files.exists(STORE.resolve(row.id+".enc"))).isFalse();
    }
    @Test void tamperEmitsNoPlaintextAndDoesNotConsume()throws Exception {
        var c=text(null,1);var t=row(c);var path=STORE.resolve(t.id+".enc");byte[] contents=Files.readAllBytes(path);contents[contents.length-1]^=1;Files.write(path,contents);
        var output=new ByteArrayOutputStream();assertThatThrownBy(()->delivery.send(service.reserve(c.readToken(),null,"test"),output)).isInstanceOf(IOException.class);
        assertThat(output.size()).isZero();assertThat(service.publicView(c.readToken()).downloads()).isZero();
    }
    @Test void oldReservationCannotCompleteOrSendAfterRecovery()throws Exception {
        var c=text(null,1);var old=service.reserve(c.readToken(),null,"test");service.recover();var fresh=service.reserve(c.readToken(),null,"test");
        service.finish(old,true);assertThat(service.publicView(c.readToken()).downloads()).isZero();
        var out=new ByteArrayOutputStream();assertThatThrownBy(()->delivery.send(old,out)).isInstanceOf(IOException.class);assertThat(out.size()).isZero();
        assertThat(service.publicView(c.readToken()).state()).isEqualTo("IN_PROGRESS");delivery.send(fresh,out);assertThat(service.publicView(c.readToken()).downloads()).isEqualTo(1);
    }
    @Test void validationAndQuota()throws Exception {
        assertThatThrownBy(()->service.create(new TransferService.Options("TEXT","x",null,1,1,null),null,null)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.status).isEqualTo(400));
        assertThatThrownBy(()->service.create(new TransferService.Options("TEXT","x".repeat(65537),null,600,1,null),null,null)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.status).isEqualTo(413));
        failure(()->service.publicView("bad-token"),404,"NOT_FOUND");
        Path tiny=Files.createTempDirectory("pollydrop-quota-");var limited=new CipherStorage(tiny.toString(),Base64.getEncoder().encodeToString(new byte[32]),50);
        assertThatThrownBy(()->limited.write(UUID.randomUUID(),new byte[30])).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.status).isEqualTo(507));
        try(var entries=Files.list(tiny)){assertThat(entries.count()).isZero();}Files.delete(tiny);
    }
}
