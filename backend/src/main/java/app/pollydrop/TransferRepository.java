package app.pollydrop;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
    Optional<Transfer> findByReadHash(String hash);
    Optional<Transfer> findByManageHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.readHash = :hash")
    Optional<Transfer> lockRead(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.manageHash = :hash")
    Optional<Transfer> lockManage(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> lockId(@Param("id") UUID id);
    @Modifying
    @Query("update Transfer t set t.activeLease = null where t.activeLease is not null")
    int clearAbandonedLeases();
    @Query("select t.id from Transfer t where t.purgedAt is null and t.activeLease is null and (t.revoked = true or t.expiresAt <= :now or t.downloads >= t.maxDownloads)")
    List<UUID> cleanupCandidates(@Param("now") Instant now);
}
