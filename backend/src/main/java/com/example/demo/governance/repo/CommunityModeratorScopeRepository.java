package com.example.demo.governance.repo;

import com.example.demo.governance.entity.CommunityModeratorScope;
import com.example.demo.governance.entity.ModeratorScopeId;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CommunityModeratorScopeRepository
        extends JpaRepository<CommunityModeratorScope, ModeratorScopeId> {
    @Query("select scope.id.sectionId from CommunityModeratorScope scope where scope.id.userId = :userId order by scope.id.sectionId")
    List<Long> findSectionIdsByUserId(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("delete from CommunityModeratorScope scope where scope.id.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Query("select count(scope) > 0 from CommunityModeratorScope scope "
            + "where scope.id.userId = :userId and scope.id.sectionId = :sectionId")
    boolean existsByUserIdAndSectionId(@Param("userId") Long userId, @Param("sectionId") Long sectionId);
}
