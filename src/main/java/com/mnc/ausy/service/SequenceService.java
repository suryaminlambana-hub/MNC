package com.mnc.ausy.service;

import com.mnc.ausy.domain.WebSequence;
import com.mnc.ausy.repo.WebSequenceRepository;
import org.springframework.stereotype.Service;

@Service
public class SequenceService {
    private final WebSequenceRepository repo;
    public SequenceService(WebSequenceRepository repo) { this.repo = repo; }
    /** Insert a row and use its auto-increment id as the next sequence value. */
    public long next() { return repo.save(new WebSequence()).getId(); }
}
