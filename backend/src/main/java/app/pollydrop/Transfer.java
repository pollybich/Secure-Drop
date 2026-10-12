package app.pollydrop;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {
    @Id UUID id;
    @Column(nullable=false, unique=true, length=64) String readHash;
    @Column(nullable=false, unique=true, length=64) String manageHash;
    @Column(nullable=false, length=8) String kind;
    @Column(nullable=false, length=240) String name;
    @Column(nullable=false, length=180) String mediaType;
    @Column(nullable=false) long byteSize;
    @Column(length=256) String passwordHash;
    @Column(nullable=false) Instant createdAt;
    @Column(nullable=false) Instant expiresAt;
    @Column(nullable=false) int maxDownloads;
    @Column(nullable=false) int downloads;
    @Column(nullable=false) boolean revoked;
    UUID activeLease;
    Instant purgedAt;
    public Transfer() {}
}
