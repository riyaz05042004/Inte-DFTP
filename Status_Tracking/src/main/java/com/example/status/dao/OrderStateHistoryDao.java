package com.example.status.dao;
import com.example.status.entity.OrderStateHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;  

public interface OrderStateHistoryDao extends JpaRepository<OrderStateHistoryEntity, Long> {

    // New flexible query: find by fileId, orderId, and distributorId
    Optional<OrderStateHistoryEntity> findTopByFileIdAndOrderIdAndDistributorIdOrderByEventTimeDesc(
        String fileId,
        String orderId,
        Integer distributorId
    );

    // Fallback: find by any combination of fields
    Optional<OrderStateHistoryEntity> findTopByFileIdOrderByEventTimeDesc(String fileId);
    
    Optional<OrderStateHistoryEntity> findTopByOrderIdAndDistributorIdOrderByEventTimeDesc(
        String orderId,
        Integer distributorId
    );
    
    Optional<OrderStateHistoryEntity> findTopByOrderIdOrderByEventTimeDesc(String orderId);

    boolean existsByFileId(String fileId);

}   
