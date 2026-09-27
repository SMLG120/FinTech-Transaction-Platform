package com.fintech.platform.customer.persistence;

import com.fintech.platform.customer.domain.Customer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Customer lookups.
 *
 * <p>Only digest and blind-index queries are here, and that is deliberate. A lookup by encrypted
 * column is not possible in any case, since the ciphertext differs for every record, but it is worth
 * being explicit that none was written: the temptation to add {@code findByEmailEncrypted} is the
 * first step toward treating a cipher as a lookup key, and it does not work anyway.
 *
 * <p>Lookups by email and phone are by blind index. Those are keyed hashes and therefore stable across
 * records, which is what makes a duplicate check possible at all; it is also what makes them a
 * low-entropy equality oracle, so they are never logged and never returned.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findBySubject(String subject);

    /**
     * Duplicate-submission guard for registration.
     *
     * <p>The live-subject index in the schema is the real guarantee; this is here so the service can
     * return a clear error instead of letting a racing request surface as a constraint violation.
     */
    @Query("select c from Customer c where c.subjectDigest = :digest")
    Optional<Customer> findBySubjectDigest(@Param("digest") String subjectDigest);

    @Query("select c from Customer c where c.emailBlindIndex = :bidx")
    Optional<Customer> findByEmailBlindIndex(@Param("bidx") String emailBlindIndex);

    @Query("select c from Customer c where c.phoneBlindIndex = :bidx")
    Optional<Customer> findByPhoneBlindIndex(@Param("bidx") String phoneBlindIndex);
}
