package com.strategy.arbitrage.mapper;

import com.strategy.arbitrage.model.Kline;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KlineMapper {

    void insertIgnore(Kline kline);

    void batchInsertIgnore(@Param("list") List<Kline> klines);

    List<Kline> findBySymbolAndInterval(@Param("symbol") String symbol,
                                        @Param("intervalType") String intervalType,
                                        @Param("limit") int limit);

    Long findMaxOpenTime(@Param("symbol") String symbol,
                         @Param("intervalType") String intervalType);
}
