package com.mnc.ausy.repo;
import com.mnc.ausy.domain.TUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
public interface TUploadRepository extends JpaRepository<TUpload, Long> {
    List<TUpload> findTop50ByOrderByAdddtDesc();
    /** Catatan upload per nomor batch — untuk nama file di List Data GL (REQ_AUSY_00036). */
    List<TUpload> findByNoPaketIn(Collection<String> pakets);
}
