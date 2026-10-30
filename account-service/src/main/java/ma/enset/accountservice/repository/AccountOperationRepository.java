package ma.enset.accountservice.repository;
import ma.enset.accountservice.domain.AccountOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface AccountOperationRepository extends JpaRepository<AccountOperation, UUID> {}
