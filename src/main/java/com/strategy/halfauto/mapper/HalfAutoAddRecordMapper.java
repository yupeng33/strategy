package com.strategy.halfauto.mapper;

import com.strategy.halfauto.model.HalfAutoAddRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HalfAutoAddRecordMapper {

    /** 插入加仓记录 */
    void insert(HalfAutoAddRecord record);

    /** 查询指定仓位最新的加仓记录 */
    HalfAutoAddRecord findLatest(@Param("positionId") Long positionId);

    /** 删除指定仓位的所有加仓记录 */
    void deleteByPositionId(@Param("positionId") Long positionId);
}
