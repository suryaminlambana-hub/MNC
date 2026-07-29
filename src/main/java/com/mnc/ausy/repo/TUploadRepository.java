package com.mnc.ausy.repo;
import com.mnc.ausy.domain.TUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface TUploadRepository extends JpaRepository<TUpload, Long> {
    List<TUpload> findTop50ByOrderByAdddtDesc();
}
