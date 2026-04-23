package com.strategy.halfauto.mapper;

import com.strategy.halfauto.model.HalfAutoPosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface HalfAutoPositionMapper {

    /** 插入仓位记录，自动回填 id */
    void insert(HalfAutoPosition position);

    /** 查询未关闭的仓位记录（closed_at IS NULL） */
    HalfAutoPosition findOpen(@Param("symbol") String symbol, @Param("positionSide") String positionSide);

    /** 查询所有未关闭的仓位记录 */
    List<HalfAutoPosition> findAllOpen();

    /** 仓位关闭时设置 closed_at */
    void close(@Param("id") Long id);

    /** 删除仓位记录 */
    void delete(@Param("id") Long id);
}
