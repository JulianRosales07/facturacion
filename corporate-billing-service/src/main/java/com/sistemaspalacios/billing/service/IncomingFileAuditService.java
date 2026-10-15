package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.IncomingFileHistory;
import com.sistemaspalacios.billing.repository.IncomingFileHistoryRepository;
import com.sistemaspalacios.billing.service.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.data.domain.*;

@Service
public class IncomingFileAuditService {
    private final IncomingFileHistoryRepository repository;
    private final CurrentUserProvider user;
    public IncomingFileAuditService(IncomingFileHistoryRepository repository, CurrentUserProvider user) {
        this.repository=repository; this.user=user;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String filename, String source, String status, String hash, int created, int repeated, String detail) {
        repository.save(new IncomingFileHistory(limit(filename,255), source, status, hash, created, repeated,
                limit(detail,1500), limit(user.getCurrentUsername(),255)));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordIfChanged(String filename, String source, String status, String hash, int created, int repeated, String detail) {
        var latestOpt = repository.findFirstBySha256OrderByProcessedAtDesc(hash);
        if (latestOpt.isPresent()) {
            var latest = latestOpt.get();
            boolean sameStatus = status.equals(latest.getStatus());
            String newDetail = limit(detail, 1500);
            String oldDetail = latest.getDetail();
            boolean sameDetail = (newDetail == null && oldDetail == null) || (newDetail != null && newDetail.equals(oldDetail));
            if (sameStatus && sameDetail) {
                return;
            }
        }
        record(filename, source, status, hash, created, repeated, detail);
    }
    @Transactional(readOnly=true)
    public boolean isAlreadyProcessed(String hash) {
        if (hash == null || hash.isBlank()) return false;
        return repository.existsBySha256AndStatusIn(hash, java.util.List.of("IMPORTADO", "OMITIDO"));
    }
    @Transactional(readOnly=true)
    public java.util.Optional<IncomingFileHistory> findLatestByHash(String hash) {
        if (hash == null || hash.isBlank()) return java.util.Optional.empty();
        return repository.findFirstBySha256OrderByProcessedAtDesc(hash);
    }
    @Transactional(readOnly=true)
    public IncomingInvoiceQueryService.PageResult<IncomingFileHistory> history(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginación inválida");
        var result=repository.findAll(PageRequest.of(page,size,Sort.by(Sort.Order.desc("processedAt"),Sort.Order.desc("id"))));
        return new IncomingInvoiceQueryService.PageResult<>(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    private String limit(String value,int length) { return value == null ? null : value.substring(0,Math.min(value.length(),length)); }
}
