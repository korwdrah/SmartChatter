package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
    //加了索引的两个列 直接查找
    Optional<Integer> findChunkIndexByFileMd5AndChunkIndex(String fileMd5, int chunkIndex);

    /**
     * 插入分片信息, 如果chunkMd5已存在则忽略(幂等)
     * 使用INSERT IGNORE避免重复插入
     * 用了Modifying就得用Transactional注解 事务管理 让这个方法在一个事务中执行
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT IGNORE INTO chunk_info (file_md5, chunk_index, chunk_md5, storage_path) " +
                   "VALUES (:fileMd5, :chunkIndex, :chunkMd5, :storagePath)", nativeQuery = true)
    void insertIgnore(@Param("fileMd5") String fileMd5,
                      @Param("chunkIndex") int chunkIndex,
                      @Param("chunkMd5") String chunkMd5,
                      @Param("storagePath") String storagePath);
}
