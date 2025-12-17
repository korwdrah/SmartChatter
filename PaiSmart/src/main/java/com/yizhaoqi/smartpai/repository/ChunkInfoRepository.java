package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
    //加了索引的两个列 直接查找
    Optional<Integer> findChunkIndexByFileMd5AndChunkIndex(String fileMd5, int chunkIndex);
}
