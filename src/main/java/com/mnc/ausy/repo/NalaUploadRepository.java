package com.mnc.ausy.repo;
import com.mnc.ausy.domain.NalaUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface NalaUploadRepository extends JpaRepository<NalaUpload, Long> {
    Optional<NalaUpload> findByUploadId(String uploadId);
}
