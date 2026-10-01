package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown on the rare concurrent-create race for the same
 * {@code (organisationId, source, externalKey)} — the ordinary re-ingestion path updates the existing
 * item instead of raising this (idempotent ingest, not a duplicate error; see
 * {@code InitiateDiscoveredItemUseCase}).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateDiscoveredItemException extends BusinessException {

    private static final String ERROR_CODE = "ERR-DSC-00409";

    public DuplicateDiscoveredItemException(String message) {
        super(ERROR_CODE, message);
    }
}
