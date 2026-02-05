package com.coin.coin.repository;

import com.coin.coin.entity.KakaoToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KakaoTokenRepository extends JpaRepository<KakaoToken, String> {

    @Query("SELECT t FROM KakaoToken t WHERE t.no = 1")
    KakaoToken getKakaoToken();
}
