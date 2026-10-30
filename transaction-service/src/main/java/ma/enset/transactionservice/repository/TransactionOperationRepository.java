package ma.enset.transactionservice.repository;
import jakarta.persistence.LockModeType;
import ma.enset.transactionservice.domain.TransactionOperation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface TransactionOperationRepository extends JpaRepository<TransactionOperation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from TransactionOperation o where o.id = :id")
    Optional<TransactionOperation> findForUpdate(@Param("id") UUID id);
}
