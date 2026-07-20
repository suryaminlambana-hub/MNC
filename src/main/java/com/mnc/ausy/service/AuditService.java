package com.mnc.ausy.service;

import com.mnc.ausy.domain.TlogActivity;
import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import org.springframework.stereotype.Service;

/**
 * Immutable audit trail (PDF §7.3 / §8 / §9). Append-only by design:
 * this service exposes inserts only — no update or delete — so the log
 * satisfies the immutable-logging requirement for banking traceability.
 */
@Service
public class AuditService {
    private final TlogActivityRepository activities;
    private final TlogTransactionRepository transactions;

    public AuditService(TlogActivityRepository a, TlogTransactionRepository t) {
        this.activities = a; this.transactions = t;
    }

    /** A user action / security event (login, logout, navigation). */
    public void activity(String userId, String ip, String name, String info) {
        TlogActivity a = new TlogActivity();
        a.setUserId(userId); a.setIp(ip); a.setNameActivity(name); a.setInfo(info);
        activities.save(a);
    }

    /** A business transaction (upload, approve, reject, GL add/edit/delete). */
    public void transaction(String userId, String name, String tinfo) {
        TlogTransaction t = new TlogTransaction();
        t.setUserId(userId); t.setNameActivity(name); t.setTinfo(tinfo);
        transactions.save(t);
    }
}
