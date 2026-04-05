package com.coin.coin.repository;

import com.coin.coin.entity.CoinCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CoinCodeRepository extends JpaRepository<CoinCode, String> {

    @Query("SELECT t.coinCode FROM CoinCode t")
    List<String> findAllCoinCode();
}
