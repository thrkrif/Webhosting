package com.example.webhosting.repository;

import com.example.webhosting.entity.Host;
import com.example.webhosting.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface HostRepository extends JpaRepository<Host, Long> {
    List<Host> findByUser(User user);
    List<Host> findByUserOrderByCreatedAtDesc(User user);
    Optional<Host> findByIdAndUser(Long id, User user);
    boolean existsByHostNameAndUser(String hostName, User user);
    Optional<Host> findByVmId(String vmId);
}