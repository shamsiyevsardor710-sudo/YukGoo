package uz.yukgo.growth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceCodeRepository extends JpaRepository<SourceCode, Long> {
    Optional<SourceCode> findByCode(String code);

    boolean existsByCode(String code);

    List<SourceCode> findAllByOrderByCreatedAtDesc();
}
